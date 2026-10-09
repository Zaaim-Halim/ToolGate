package io.toolgate.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.toolgate.demo.MockModelServer.Call;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** With no sink of its own, an application gets one JSON line per audit event in Spring Boot's log. */
@SpringBootTest(classes = DemoApplication.class)
@ExtendWith(OutputCaptureExtension.class)
class AuditLogTest {

    private static final MockModelServer MODEL = start();

    @Autowired
    private InvoiceJob job;

    @DynamicPropertySource
    static void model(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", MODEL::baseUrl);
        registry.add("spring.ai.openai.api-key", () -> "test");
    }

    @AfterAll
    static void stop() {
        MODEL.close();
    }

    @Test
    void decisionsReachSpringBootsLog(CapturedOutput output) {
        MODEL.thenToolCalls(new Call("call_1", "readInvoice", "{\"id\":\"INV-118\"}")).thenText("Done.");

        job.process("INV-118");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(output.getAll()).contains("toolgate.audit")
                .contains("\"event\":\"toolgate.decision\"").contains("\"tool\":\"local/readInvoice\""));
    }

    private static MockModelServer start() {
        try {
            return new MockModelServer();
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
