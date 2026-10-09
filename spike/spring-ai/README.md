# M0 interception spike: Spring AI 2.0.1

Can a `ToolCallingManager` bean that wraps every `ToolCallback`, then delegates to Spring AI's own
manager, see every tool Spring AI runs? Run it with `../../mvnw test` from this folder. It keeps its
own Maven repository in `.m2/` (set in `.mvn/maven.config`), so it never touches `~/.m2`.

Stack: Spring Boot 4.1.1, Spring AI 2.0.1 (Spring 7, Jackson 3, the official `openai-java` client),
MCP Java SDK 2.0.0. The model is `MockModelServer`, an OpenAI-compatible endpoint on the JDK's
`HttpServer` that returns scripted tool calls, as JSON or as server-sent events.

A path is **covered** when every tool body that ran was seen by the wrapper, with the principal and
session from the tool context. A **gap** is asserted as a gap, so a Spring AI change that closes or
opens it fails the test.

## Results (October 9, 2026)

| # | Path | Result | Notes |
| --- | --- | --- | --- |
| 1 | A tool offered in the request | Covered | |
| 2 | A tool name the model invents that exists elsewhere in the application | Never runs; covered with fallback on | With resolution fallback off, the default since the CVE fix, `DefaultToolCallingManager` throws `No ToolCallback found`. With it on, Spring AI runs the unoffered tool, and the wrapped resolver puts it in front of the wrapper (tested) |
| 3 | Several tool calls in one response | Covered | Run one after another, in order |
| 4 | A tool that returns its result directly | Covered | |
| 5 | Streaming with tool calls | Covered | Runs on a Reactor `boundedElastic` thread; the tool context still arrives, since it travels with the request |
| 6 | An MCP client tool | Covered | The callback is a `SyncMcpToolCallback` |
| 7 | The application executes tools itself | Covered only through the bean | The chat model alone runs no tools in 2.0.1; an application that calls the manager bean is gated, one that calls callbacks directly is not |
| — | `ChatClient.create(model)` or `ChatClient.builder(model)` | **Gap** | `DefaultChatClientBuilder` creates its own `ToolCallingManager`; only Boot's auto-configured `ChatClient.Builder` uses the bean |
| — | An application advisor implementing `ToolAdvisor` | **Gap** | It replaces the auto-registered `ToolCallingAdvisor`, with its own manager |
| — | Ending a run: the wrapper throws, as the adapter would on Terminate | Works | For `call()` and `stream()`: the caller gets the exception, the second tool call of the batch is never attempted, and no further model request is made |

## Findings for the adapter design

1. **Tools run in `ChatClient`, not in the chat model.** 2.0 moved execution into an auto-registered
   `ToolCallingAdvisor`. Replacing the manager bean covers Boot's `ChatClient.Builder` only.
2. **The wrapper never sees the model's tool call ID.** `ToolCallback.call(arguments, toolContext)`
   gets only the arguments and the tool context, so the adapter must generate `ToolCall` IDs.
3. **MCP sends the whole tool context to the server.** `SyncMcpToolCallback` converts the tool context
   into the request's `_meta` by default. Observed on the wire: `"_meta":{"toolgate.session":"s1",
   "toolgate.principal":"user:maria@acme"}` reaches the MCP server. The adapter must strip its keys
   before delegating to an MCP callback.
4. **The callback does not say which MCP server it belongs to.** `SyncMcpToolCallback` exposes the
   original tool name but not its client, and a server's own `serverInfo` is what the server claims.
   From the source, not run: Boot's MCP client auto-configuration builds one `SyncMcpToolCallbackProvider`
   over every connection, but sets each client's `clientInfo().title()` to the configured connection
   name, and the provider's `McpToolFilter` and `McpToolNamePrefixGenerator` receive the client for
   every tool. The adapter can name the server from there, without reflection.
5. **The MCP tool name carries no server prefix.** Boot's auto-configuration uses
   `McpToolNamePrefixGenerator.noPrefix()`, not the generator that prefixes on a clash. The leak in
   finding 3 goes through the `ToolContextToMcpMetaConverter` bean, which the adapter can replace with
   one that drops ToolGate's keys; the provider takes it only when it is the unique such bean.
8. **The delegate must keep Boot's settings.** The spike's manager builds a bare
   `DefaultToolCallingManager`. Replacing Boot's bean that way silently drops the application's
   tool-call limits, exception processor, observation registry and fallback setting; the adapter must
   build its delegate from the same `ToolCallingProperties` and beans.
6. **The streamable MCP server needs `spring.ai.mcp.server.protocol=STREAMABLE`** set explicitly,
   although it is the property's default.
7. **Spring AI 2.0 needs Spring Boot 4.** Teams on Spring Boot 3 stay on Spring AI 1.x, which this
   adapter does not cover.
