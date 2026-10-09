package io.toolgate.springai;

import static org.assertj.core.api.Assertions.assertThat;

import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.springai.MockModelServer.Call;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class FallbackAndStartupTest {

    private static final MockModelServer MODEL = start();

    @AfterAll
    static void stop() {
        MODEL.close();
    }

    @Test
    void withoutAPolicyTheApplicationDoesNotStart() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ToolGateAutoConfiguration.class))
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("needs a ToolGatePolicy bean"));
    }

    /**
     * With resolution fallback on, Spring AI runs tools a request never offered (CVE-2026-59318's
     * scenario); the wrapped resolver still puts them in front of ToolGate.
     */
    @Nested
    @SpringBootTest(classes = {TestApplication.class, ResolutionFallback.Registered.class},
            properties = "spring.ai.tools.resolution.fallback.enabled=true")
    class ResolutionFallback {

        @Autowired
        private ChatClient.Builder chatClients;

        @DynamicPropertySource
        static void model(DynamicPropertyRegistry registry) {
            registry.add("spring.ai.openai.base-url", MODEL::baseUrl);
            registry.add("spring.ai.openai.api-key", () -> "test");
            registry.add("spring.ai.openai.chat.options.model", () -> "gpt-4o");
        }

        @TestConfiguration
        static class Registered {

            /** The application's tools, resolvable by name though no request offers them. */
            @Bean
            ToolCallbackProvider applicationTools() {
                return MethodToolCallbackProvider.builder().toolObjects(new TestTools()).build();
            }
        }

        @Test
        void aToolTheRequestNeverOfferedIsStillChecked() {
            MODEL.reset();
            TestTools.RAN.clear();
            MODEL.thenToolCalls(new Call("call_1", "deleteAllOrders", "{}")).thenText("I cannot do that.");

            chatClients.build().prompt().user("Clean up")
                    .toolContext(ToolGateContext.of("support-chat", Principal.user("maria", Tenant.of("acme")), "fallback"))
                    .call().content();

            assertThat(TestTools.RAN).isEmpty();
            assertThat(MODEL.requests().getLast()).contains(io.toolgate.api.Decision.Deny.MODEL_MESSAGE);
        }
    }

    private static MockModelServer start() {
        try {
            return new MockModelServer();
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
