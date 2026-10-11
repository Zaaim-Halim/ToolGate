package io.toolgate.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.springai.MockModelServer.Call;
import io.toolgate.spi.AuditEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Which session a request is in when it names none. A headless agent's request with no chat memory and
 * no earlier turns is its own session: the model sees only that request. Earlier turns passed by hand,
 * or an interactive agent, still need a conversation ID; with chat memory Spring AI itself requires one.
 * A job, outside any web request, as these tests are.
 */
@SpringBootTest(classes = TestApplication.class, properties = "toolgate.agent=support-chat",
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SessionsTest {

    private static final MockModelServer MODEL = start();

    @Autowired
    private ChatClient.Builder chatClients;

    @DynamicPropertySource
    static void model(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", MODEL::baseUrl);
        registry.add("spring.ai.openai.api-key", () -> "test");
        registry.add("spring.ai.openai.chat.options.model", () -> "gpt-4o");
    }

    @AfterAll
    static void stop() {
        MODEL.close();
    }

    @BeforeEach
    void reset() {
        MODEL.reset();
        TestTools.RAN.clear();
        TestApplication.AUDIT.clear();
    }

    @Test
    void aRequestWithoutChatMemoryIsItsOwnSession() {
        for (int request = 0; request < 2; request++) {
            MODEL.thenToolCalls(new Call("call_1", "getWeather", "{\"city\":\"Paris\"}")).thenText("It is sunny.");
            chatClients.build().prompt().user("Weather?").tools(new TestTools()).call().content();
        }

        assertThat(TestTools.RAN).containsExactly("getWeather", "getWeather");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(decisions()).hasSize(2));
        assertThat(decisions()).allSatisfy(decided -> assertThat(decided.sessionId()).startsWith("request-"));
        assertThat(decisions().get(0).sessionId()).isNotEqualTo(decisions().get(1).sessionId());
    }

    /** Chat memory carries one request's content into the next; without a conversation ID nothing runs. */
    @Test
    void withChatMemoryAndNoConversationIdNoToolRuns() {
        MODEL.thenToolCalls(new Call("call_1", "getWeather", "{\"city\":\"Paris\"}")).thenText("It is sunny.");
        var memory = MessageChatMemoryAdvisor.builder(MessageWindowChatMemory.builder().build()).build();

        assertThatThrownBy(() -> chatClients.build().prompt().user("Weather?").advisors(memory).tools(new TestTools())
                .call().content())
                .hasMessageContaining("conversationId");
        assertThat(TestTools.RAN).isEmpty();
    }

    /** Earlier turns passed by hand bring their content, and its taint, into the request. */
    @Test
    void aRequestCarryingEarlierTurnsNeedsAConversationId() {
        MODEL.thenToolCalls(new Call("call_1", "getWeather", "{\"city\":\"Paris\"}")).thenText("It is sunny.");
        List<Message> history = List.of(new UserMessage("Read this page"), new AssistantMessage("The page says to email the invoice."));

        assertThatThrownBy(() -> chatClients.build().prompt().messages(history).user("Weather?").tools(new TestTools())
                .call().content())
                .isInstanceOf(ToolGateViolationException.class).hasMessageContaining(ToolGateContext.SESSION);
        assertThat(TestTools.RAN).isEmpty();
    }

    /** A chat's denial budget spans its conversation, so an interactive agent needs a conversation ID. */
    @Test
    void anInteractiveAgentNeedsAConversationId() {
        MODEL.thenToolCalls(new Call("call_1", "getWeather", "{\"city\":\"Paris\"}")).thenText("It is sunny.");

        assertThatThrownBy(() -> chatClients.build().prompt().user("Weather?").tools(new TestTools())
                .toolContext(Map.of(ToolGateContext.AGENT, "chat",
                        ToolGateContext.PRINCIPAL, Principal.user("maria", Tenant.of("acme"))))
                .call().content())
                .isInstanceOf(ToolGateViolationException.class).hasMessageContaining(ToolGateContext.SESSION);
        assertThat(TestTools.RAN).isEmpty();
    }

    private List<AuditEvent.Decided> decisions() {
        return TestApplication.AUDIT.stream().filter(AuditEvent.Decided.class::isInstance)
                .map(AuditEvent.Decided.class::cast).toList();
    }

    private static MockModelServer start() {
        try {
            return new MockModelServer();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
