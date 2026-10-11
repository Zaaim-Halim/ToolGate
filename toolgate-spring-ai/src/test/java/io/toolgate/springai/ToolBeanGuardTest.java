package io.toolgate.springai;

import static org.assertj.core.api.Assertions.assertThat;

import io.toolgate.api.Decision;
import io.toolgate.api.Mode;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.spi.AuditEvent;
import io.toolgate.springai.MockModelServer.Call;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The guard on the application's tool beans: Spring AI may not run them without ToolGate, through a
 * {@code ChatClient} built by hand or an application {@code ToolAdvisor} tied with ToolGate's.
 */
class ToolBeanGuardTest {

    private static final MockModelServer MODEL = start();
    private static final Principal MARIA = Principal.user("maria", Tenant.of("acme"));

    /** A tool bean, as applications declare them; its bodies record that they ran. */
    static class WeatherTools {

        static final List<String> RAN = new CopyOnWriteArrayList<>();

        @Tool(description = "Current weather in a city")
        String getWeather(String city) {
            RAN.add("getWeather " + city);
            return "sunny in " + city;
        }
    }

    /**
     * An application's own tool advisor at exactly the highest precedence, as ToolGate's: Spring AI's
     * builder refuses that order, but a subclass may report it.
     */
    static final class FirstOfAllToolAdvisor extends ToolCallingAdvisor {

        FirstOfAllToolAdvisor() {
            super(ToolCallingManager.builder().build(), DEFAULT_TOOL_EXECUTION_ELIGIBILITY_CHECKER,
                    Ordered.HIGHEST_PRECEDENCE + 1, true);
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }

    @TestConfiguration
    static class Tools {

        @Bean
        WeatherTools weatherTools() {
            return new WeatherTools();
        }
    }

    static void model(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", MODEL::baseUrl);
        registry.add("spring.ai.openai.api-key", () -> "test");
        registry.add("spring.ai.openai.chat.options.model", () -> "gpt-4o");
    }

    @AfterAll
    static void stop() {
        MODEL.close();
    }

    @Nested
    @SpringBootTest(classes = {TestApplication.class, Tools.class})
    class Enforced {

        @Autowired
        private ChatClient.Builder chatClients;
        @Autowired
        private ChatModel chatModel;
        @Autowired
        private WeatherTools tools;

        private String session;

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            model(registry);
        }

        @BeforeEach
        void reset() {
            MODEL.reset();
            WeatherTools.RAN.clear();
            TestApplication.AUDIT.clear();
            session = "s-" + UUID.randomUUID();
        }

        @Test
        void theToolBeanIsGuarded() {
            assertThat(AopUtils.isCglibProxy(tools)).isTrue();
        }

        @Test
        void aClientBuiltByHandCannotRunAToolBean() {
            MODEL.thenToolCalls(new Call("call_1", "getWeather", "{\"city\":\"Paris\"}")).thenText("Sorry.");

            ChatClient.create(chatModel).prompt().user("Weather?").tools(tools)
                    .toolContext(ToolGateContext.of("support-chat", MARIA, session)).call().content();

            assertThat(WeatherTools.RAN).as("the gap is closed for tool beans").isEmpty();
            assertThat(MODEL.requests().get(1)).as("the model learns only the generic denial")
                    .contains(Decision.Deny.MODEL_MESSAGE);
        }

        @Test
        void aToolAdvisorTiedWithToolGatesCannotRunAToolBeanUnchecked() {
            MODEL.thenToolCalls(new Call("call_1", "getWeather", "{\"city\":\"Paris\"}")).thenText("It is sunny.");

            chatClients.build().prompt().user("Weather?")
                    .advisors(new FirstOfAllToolAdvisor())
                    .tools(tools).toolContext(ToolGateContext.of("support-chat", MARIA, session)).call().content();

            // The application's advisor wins the tie and runs the tool without ToolGate: the guard refuses it.
            assertThat(WeatherTools.RAN).isEmpty();
            assertThat(MODEL.requests().get(1)).contains(Decision.Deny.MODEL_MESSAGE);
            assertThat(decisions()).as("ToolGate never saw the call").isEmpty();
        }

        @Test
        void theGatedPathRunsAToolBeanAsBefore() {
            MODEL.thenToolCalls(new Call("call_1", "getWeather", "{\"city\":\"Paris\"}")).thenText("It is sunny.");

            assertThat(chatClients.build().prompt().user("Weather?").tools(tools)
                    .toolContext(ToolGateContext.of("support-chat", MARIA, session)).call().content())
                    .isEqualTo("It is sunny.");

            assertThat(WeatherTools.RAN).containsExactly("getWeather Paris");
        }

        @Test
        void applicationCodeMayStillCallAToolMethodDirectly() {
            assertThat(tools.getWeather("Oslo")).isEqualTo("sunny in Oslo");
            assertThat(WeatherTools.RAN).containsExactly("getWeather Oslo");
        }

        private List<AuditEvent.Decided> decisions() {
            return TestApplication.AUDIT.stream().filter(AuditEvent.Decided.class::isInstance)
                    .map(AuditEvent.Decided.class::cast).filter(decided -> decided.sessionId().equals(session)).toList();
        }
    }

    /** Monitor mode: the bypass is logged, and the tool runs. */
    @Nested
    @SpringBootTest(classes = {TestApplication.class, Tools.class, Monitored.Policy.class})
    class Monitored {

        @Autowired
        private ChatModel chatModel;
        @Autowired
        private WeatherTools tools;

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
        void aClientBuiltByHandRunsTheToolBean() {
            MODEL.reset();
            WeatherTools.RAN.clear();
            MODEL.thenToolCalls(new Call("call_1", "getWeather", "{\"city\":\"Paris\"}")).thenText("It is sunny.");

            ChatClient.create(chatModel).prompt().user("Weather?").tools(tools).call().content();

            assertThat(WeatherTools.RAN).containsExactly("getWeather Paris");
        }
    }

    private static MockModelServer start() {
        try {
            return new MockModelServer();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
