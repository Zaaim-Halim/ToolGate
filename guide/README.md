# ToolGate guide

ToolGate decides, outside the model, whether each tool call an AI agent makes may run, and records
every decision with its reason. The model proposes; ToolGate decides.

## Start here

Pick your framework. Both guides start in **monitor mode**: ToolGate decides and logs every call but
blocks nothing, so you can add it to a running application, watch what it would do, and tighten the
policy before you enforce it.

- [Spring AI](spring-ai.md): add the starter and a policy bean.
- [LangChain4j](langchain4j.md): register your tools through ToolGate.

Then:

- [Policies](policies.md): agents, allow-lists, argument rules, taint, budgets, approvals, modes.
- [Helpers for tool authors](helpers.md): email and SQL rules, SafeJdbc, SafeHttp.
- [Replaying a policy change](replay.md): the `toolgate` command.
- [Releasing](releasing.md): publishing to Maven Central.

Requires Java 21. Artifacts are under the group `io.github.zaaim-halim.toolgate`.
