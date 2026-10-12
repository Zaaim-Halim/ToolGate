# Security policy

ToolGate is a security control: a bypass, a decision that allows what the policy denies, or a leak of
argument values is a vulnerability. Please report it privately.

## Reporting a vulnerability

Use GitHub's private vulnerability reporting: on the repository's **Security** tab, choose
**Report a vulnerability**. Do not open a public issue or pull request for a vulnerability.

Please include:

- the module and version, or the commit, you tested;
- the policy, or the smallest part of it that shows the problem;
- the tool call (tool and arguments) and the decision you expected and got;
- for a parser or validator bypass, the exact input, in hex if it contains unusual characters.

You will get an acknowledgement within a week. Once a fix is ready, it is released with an advisory
that credits you, unless you prefer otherwise.

## Supported versions

Before 1.0, only the latest release, and `main`, receive security fixes.

## Scope

In scope: the decision engine and its validators, the framework adapters (a tool call that reaches a
tool without being checked), the approval workflow and console, the JDBC stores, `SafeHttp`, `SafeJdbc`,
the webhook signature, and anything that writes argument values where the documentation says it does
not.

Known gaps are listed in [Known limitations](guide/limitations.md); a report that one of them is worse
than described is welcome.
