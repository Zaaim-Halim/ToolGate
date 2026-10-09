# M0 interception spike: LangChain4j 1.22.0

Do executors wrapped through ToolGate, plus ToolGate's `beforeToolExecution` hook, see every way an
AI service runs a tool? Run it with `../../mvnw test` from this folder. It keeps its own Maven
repository in `.m2/` (set in `.mvn/maven.config`), so it never touches `~/.m2`.

Stack: `langchain4j` and `langchain4j-open-ai` 1.22.0, against `MockModelServer`, the same scripted
OpenAI-compatible endpoint as the Spring AI spike. `Gate` stands where ToolGate would be: it wraps
the tools found by `ToolService.findTools` (and the tools of a `ToolProvider`), records every
executor method, and installs a hook that fails closed on any tool not registered through it.

A path is **covered** when every tool body that ran was seen by both the hook and the wrapped
executor, with the principal and session from the invocation parameters.

## Results (October 9, 2026)

| Path | Result | Notes |
| --- | --- | --- |
| A tool registered through ToolGate | Covered | `executeWithContext`; the model's call ID reaches hook and executor |
| A tool name the model invents | Never runs | The hook refuses it before LangChain4j looks for an executor |
| Several calls in one response, run concurrently | Covered | Each on its own virtual thread |
| A tool that returns immediately (`Result<T>` method) | Covered | |
| Streaming (`TokenStream`) | Covered | On a `ForkJoinPool` thread; a hook set on the token stream runs after ToolGate's, chained by LangChain4j |
| An AI service method returning `CompletableFuture` | Covered | Through `executeAsync`, on a virtual thread |
| A tool the application registers with `tools(object)`, not through ToolGate | Refused | The hook fails closed |
| A `ToolProvider` (how MCP tools arrive) | Refused unwrapped, covered wrapped | |
| The application's own hook, passed to ToolGate | Runs after ToolGate's check | |
| A hook set on the builder after ToolGate's | **Gap** | `AiServices.beforeToolExecution` replaces the previous hook, so unregistered tools run unchecked |
| Ending a run: the hook throws, as on Terminate | Works | `chat()` and streaming: the caller gets the exception, the second call of the batch is never attempted, no further model request |
| An exception from an executor | Becomes text for the model | Confirms that Terminate must come from the hook, never the executor |

## Findings for the adapter design

1. **The plan holds.** Wrapped executors plus a fail-closed `beforeToolExecution` hook see every path,
   including invented names, tool providers and tools registered around ToolGate.
2. **Three executor entry points.** `ToolExecutor` has `execute`, `executeWithContext` and
   `executeAsync`; the adapter's wrapper must gate all three, or async service methods bypass it.
3. **The hook is the decision point.** It runs before the executor lookup for every request, outside
   any try/catch, and it sees the request and the invocation context. Proposed, not tested here: the
   adapter checks there, throws on Terminate, and lets the wrapped executor apply a deny, defer or
   downgrade by returning text, with the model's call ID (`ToolExecutionRequest.id()`), which both
   see, to pass the decision on.
4. **The call ID is available**, unlike in Spring AI, so the adapter can use the provider's ID, with
   the caution in the design about providers reusing IDs.
5. **Only one builder hook.** A later `beforeToolExecution` on the builder replaces ToolGate's. The
   adapter must take the application's hook as an argument and wrap it, and document that setting
   one afterwards removes the gate's fail-closed check; executors registered through ToolGate stay
   gated either way.
6. **Threads vary by path**: the caller's thread, virtual threads for concurrent and async tools, and
   `ForkJoinPool` for streaming. The invocation parameters arrive on all of them.
