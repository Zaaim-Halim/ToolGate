package io.toolgate.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A headless invoice agent, as in Case A of the design: a nightly job reads each invoice, may look at
 * the supplier's terms page, and emails the accounts team. ToolGate stands between the model and every
 * tool; the application adds a policy and nothing else.
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
