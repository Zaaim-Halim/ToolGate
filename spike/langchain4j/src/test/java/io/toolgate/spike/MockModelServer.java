package io.toolgate.spike;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * An OpenAI-compatible chat completions endpoint that answers with scripted replies, in order, so a
 * test decides exactly which tool calls "the model" makes. Streaming requests get the same reply as
 * server-sent events.
 */
final class MockModelServer implements AutoCloseable {

    /** One tool call the model asks for. */
    record Call(String id, String name, String arguments) {}

    private final HttpServer server;
    private final Deque<Reply> replies = new ArrayDeque<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();

    private sealed interface Reply permits ToolCalls, Text {}

    private record ToolCalls(List<Call> calls) implements Reply {}

    private record Text(String content) implements Reply {}

    MockModelServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(body);
            Reply reply;
            synchronized (replies) {
                reply = replies.pollFirst();
            }
            if (reply == null) {
                reply = new Text("(no scripted reply left)");
            }
            boolean streaming = body.replace(" ", "").contains("\"stream\":true");
            byte[] bytes = (streaming ? sse(reply) : json(reply)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    MockModelServer thenToolCalls(Call... calls) {
        synchronized (replies) {
            replies.addLast(new ToolCalls(List.of(calls)));
        }
        return this;
    }

    MockModelServer thenText(String content) {
        synchronized (replies) {
            replies.addLast(new Text(content));
        }
        return this;
    }

    List<String> requests() {
        return requests;
    }

    void reset() {
        synchronized (replies) {
            replies.clear();
        }
        requests.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static String json(Reply reply) {
        String message = switch (reply) {
            case ToolCalls(List<Call> calls) -> """
                    {"role":"assistant","content":null,"refusal":null,"tool_calls":[%s]}""".formatted(
                    calls.stream().map(MockModelServer::toolCall).collect(Collectors.joining(",")));
            case Text(String content) -> """
                    {"role":"assistant","content":%s,"refusal":null}""".formatted(quote(content));
        };
        return """
                {"id":"chatcmpl-1","object":"chat.completion","created":1760000000,"model":"gpt-4o",
                 "choices":[{"index":0,"message":%s,"logprobs":null,"finish_reason":"%s"}],
                 "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""".formatted(message, finishReason(reply));
    }

    private static String sse(Reply reply) {
        String delta = switch (reply) {
            case ToolCalls(List<Call> calls) -> {
                var indexed = new StringBuilder();
                for (int index = 0; index < calls.size(); index++) {
                    Call call = calls.get(index);
                    indexed.append(index > 0 ? "," : "").append("""
                            {"index":%d,"id":"%s","type":"function","function":{"name":"%s","arguments":%s}}""".formatted(
                            index, call.id(), call.name(), quote(call.arguments())));
                }
                yield "{\"role\":\"assistant\",\"tool_calls\":[" + indexed + "]}";
            }
            case Text(String content) -> "{\"role\":\"assistant\",\"content\":" + quote(content) + "}";
        };
        return chunk(delta, "null") + chunk("{}", "\"" + finishReason(reply) + "\"") + "data: [DONE]\n\n";
    }

    private static String chunk(String delta, String finishReason) {
        return "data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1760000000,\"model\":\"gpt-4o\","
                + "\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"logprobs\":null,\"finish_reason\":" + finishReason + "}]}\n\n";
    }

    private static String toolCall(Call call) {
        return """
                {"id":"%s","type":"function","function":{"name":"%s","arguments":%s}}""".formatted(
                call.id(), call.name(), quote(call.arguments()));
    }

    private static String finishReason(Reply reply) {
        return reply instanceof ToolCalls ? "tool_calls" : "stop";
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
