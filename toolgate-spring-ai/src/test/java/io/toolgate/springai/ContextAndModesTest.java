package io.toolgate.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.toolgate.api.Decision;
import io.toolgate.api.Mode;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.spi.AuditEvent;
import io.toolgate.springai.MockModelServer.Call;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Where a call's principal, agent and session come from, and what the policy-wide mode does to the adapter. */
class ContextAndModesTest {

    private static final MockModelServer MODEL = start();

    @AfterAll
    static void stop() {
        MODEL.close();
    }

    static void model(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", MODEL::baseUrl);
        registry.add("spring.ai.openai.api-key", () -> "test");
        registry.add("spring.ai.openai.chat.options.model", () -> "gpt-4o");
    }

    @Nested
    @SpringBootTest(classes = TestApplication.class, properties = "toolgate.agent=support-chat")
    class Defaults {

        @Autowired
        private ChatClient.Builder chatClients;

        private String conversation;

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            model(registry);
        }

        @BeforeEach
        void reset() {
            MODEL.reset();
            TestTools.RAN.clear();
            conversation = "conversation-" + UUID.randomUUID();
        }

        @AfterEach
        void signOut() {
            SecurityContextHolder.clearContext();
        }

        @Test
        void theSignedInUserTheDefaultAgentAndTheConversationMakeTheCall() {
            signIn("maria", "ROLE_support");
            MODEL.thenToolCalls(new Call("call_1", "getWeather", "{\"city\":\"Paris\"}")).thenText("It is sunny.");

            chatClients.build().prompt().user("Weather?").tools(new TestTools())
                    .advisors(advisors -> advisors.param(ChatMemory.CONVERSATION_ID, conversation))
                    .call().content();

            assertThat(TestTools.RAN).containsExactly("getWeather");
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(decisions(conversation)).singleElement()
                    .satisfies(decided -> {
                        assertThat(decided.agentId()).isEqualTo("support-chat");
                        assertThat(decided.principal()).isEqualTo(Principal.user("maria", Tenant.none(), "support"));
                        assertThat(decided.applied()).isInstanceOf(Decision.Allow.class);
                    }));
        }

        @Test
        void whatTheRequestPassesWins() {
            signIn("maria", "ROLE_support");
            Principal job = Principal.service("weather-job", Tenant.of("acme"));
            MODEL.thenToolCalls(new Call("call_1", "getWeather", "{\"city\":\"Paris\"}")).thenText("It is sunny.");

            chatClients.build().prompt().user("Weather?").tools(new TestTools())
                    .toolContext(ToolGateContext.of("one-call", job, conversation))
                    .call().content();

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(decisions(conversation)).singleElement()
                    .satisfies(decided -> {
                        assertThat(decided.agentId()).isEqualTo("one-call");
                        assertThat(decided.principal()).isEqualTo(job);
                    }));
        }

        @Test
        void withNobodySignedInTheRunEnds() {
            MODEL.thenToolCalls(new Call("call_1", "getWeather", "{\"city\":\"Paris\"}")).thenText("It is sunny.");

            assertThatThrownBy(() -> chatClients.build().prompt().user("Weather?").tools(new TestTools())
                    .advisors(advisors -> advisors.param(ChatMemory.CONVERSATION_ID, conversation))
                    .call().content())
                    .isInstanceOf(ToolGateViolationException.class)
                    .hasMessageContaining(ToolGateContext.PRINCIPAL);
            assertThat(TestTools.RAN).isEmpty();
        }

        /** The principal is resolved on the thread that subscribes, before tools run on Reactor's threads. */
        @Test
        void aStreamingRequestCarriesTheSignedInUser() {
            signIn("maria", "ROLE_support");
            MODEL.thenToolCalls(new Call("call_1", "getWeather", "{\"city\":\"Paris\"}")).thenText("It is sunny.");

            chatClients.build().prompt().user("Weather?").tools(new TestTools())
                    .advisors(advisors -> advisors.param(ChatMemory.CONVERSATION_ID, conversation))
                    .stream().content().collectList().block(Duration.ofSeconds(20));

            assertThat(TestTools.RAN).containsExactly("getWeather");
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(decisions(conversation)).singleElement()
                    .satisfies(decided -> assertThat(decided.principal().id()).isEqualTo("maria")));
        }

        private void signIn(String name, String... authorities) {
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(name, null,
                    List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList()));
        }
    }

    /** Monitor mode: the adapter's own refusals are logged, never blocking. */
    @Nested
    @SpringBootTest(classes = {TestApplication.class, Monitor.Policy.class})
    class Monitor {

        @Autowired
        private ChatClient.Builder chatClients;

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            model(registry);
        }

        @TestConfiguration
        static class Policy {

            @Bean
            @Primary
            ToolGatePolicy monitored() {
                return ToolGatePolicy.builder().mode(Mode.MONITOR).agent("support-chat", a -> a.allow("getWeather")).build();
            }
        }

        @Test
        void aCallWithoutContextOrWithAmbiguousArgumentsStillRuns() {
            MODEL.reset();
            TestTools.RAN.clear();
            MODEL.thenToolCalls(new Call("call_1", "getWeather", "{\"city\":\"Paris\"}"))
                    .thenToolCalls(new Call("call_2", "getWeather", "{\"city\":\"Paris\",\"city\":\"Oslo\"}"))
                    .thenText("Done.");

            chatClients.build().prompt().user("Weather?").tools(new TestTools()).call().content();

            assertThat(TestTools.RAN).containsExactly("getWeather", "getWeather");
        }
    }

    /** Off: the emergency switch; tools run straight through. */
    @Nested
    @SpringBootTest(classes = {TestApplication.class, Off.Policy.class})
    class Off {

        @Autowired
        private ChatClient.Builder chatClients;

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            model(registry);
        }

        @TestConfiguration
        static class Policy {

            @Bean
            @Primary
            ToolGatePolicy off() {
                return ToolGatePolicy.builder().mode(Mode.OFF).build();
            }
        }

        @Test
        void everyToolRunsUnchecked() {
            MODEL.reset();
            TestTools.RAN.clear();
            MODEL.thenToolCalls(new Call("call_1", "deleteAllOrders", "{}")).thenText("Done.");

            chatClients.build().prompt().user("Clean up").tools(new TestTools()).call().content();

            assertThat(TestTools.RAN).containsExactly("deleteAllOrders");
        }
    }

    private static List<AuditEvent.Decided> decisions(String session) {
        return TestApplication.AUDIT.stream()
                .filter(AuditEvent.Decided.class::isInstance).map(AuditEvent.Decided.class::cast)
                .filter(decided -> decided.sessionId().equals(session))
                .toList();
    }

    private static MockModelServer start() {
        try {
            return new MockModelServer();
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
