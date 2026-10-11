package io.toolgate.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Three agents behind one policy: Case A's headless invoice job, which reads each invoice, may look at
 * the supplier's terms page, and emails the accounts team; Case E's ticket triage; and an interactive
 * inbox assistant. ToolGate stands between the model and every tool; the application adds a policy
 * and nothing else.
 */
@SpringBootApplication
public class DemoApplication {

    /**
     * Starts the demo.
     *
     * @param args Spring Boot arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
