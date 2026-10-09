package io.toolgate.spike;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

/** Tools whose bodies record that they ran, so a run the wrapper missed shows up. */
final class SpikeTools {

    /** Offered per request. */
    static final class Weather {

        @Tool(description = "Current weather in a city")
        String getWeather(String city) {
            Recorder.RAN.add("getWeather");
            return "sunny in " + city;
        }

        @Tool(description = "Look up an order; the result goes straight to the user", returnDirect = true)
        String lookupOrder(String id) {
            Recorder.RAN.add("lookupOrder");
            return "order " + id + " shipped";
        }
    }

    /** Registered in the application as a provider bean, never offered to the chat requests. */
    static final class Admin {

        @Tool(description = "Delete every order")
        String deleteAllOrders() {
            Recorder.RAN.add("deleteAllOrders");
            return "deleted";
        }
    }

    /** Served over MCP by the same application. */
    static final class Tickets {

        @Tool(description = "Search support tickets")
        String searchTickets(String query, ToolContext context) {
            Recorder.RAN.add("searchTickets");
            Recorder.SERVER_SIDE_CONTEXT.add(String.valueOf(context.getContext()));
            return "ticket 48213 matches " + query;
        }
    }

    private SpikeTools() {}
}
