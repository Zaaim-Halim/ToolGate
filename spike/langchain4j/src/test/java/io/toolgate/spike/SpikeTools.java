package io.toolgate.spike;

import dev.langchain4j.agent.tool.ReturnBehavior;
import dev.langchain4j.agent.tool.Tool;

/** Tools whose bodies record that they ran, so a run the gate missed shows up. */
final class SpikeTools {

    static final class Weather {

        @Tool("Current weather in a city")
        public String getWeather(String city) {
            Recorder.RAN.add("getWeather");
            return "sunny in " + city;
        }

        @Tool(value = "Look up an order; the result goes straight to the user", returnBehavior = ReturnBehavior.IMMEDIATE)
        public String lookupOrder(String id) {
            Recorder.RAN.add("lookupOrder");
            return "order " + id + " shipped";
        }
    }

    /** Registered by the application directly, not through ToolGate. */
    static final class Admin {

        @Tool("Delete every order")
        public String deleteAllOrders() {
            Recorder.RAN.add("deleteAllOrders");
            return "deleted";
        }
    }

    private SpikeTools() {}
}
