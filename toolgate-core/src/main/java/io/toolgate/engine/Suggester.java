package io.toolgate.engine;

import io.toolgate.api.ArgValue;
import io.toolgate.api.BooleanValue;
import io.toolgate.api.Decision;
import io.toolgate.api.ListValue;
import io.toolgate.api.NullValue;
import io.toolgate.api.NumberValue;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.SuggestedPolicy;
import io.toolgate.api.TextValue;
import io.toolgate.api.ToolId;
import io.toolgate.api.policy.BuiltInRules;
import io.toolgate.spi.SessionEvent;
import io.toolgate.validate.UrlCheck;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Drafts a policy from recorded tool calls: the tools each agent used, and for each argument the hosts,
 * email domains, path roots or number bounds its values show.
 *
 * <p>History holds whatever an injected model asked for too, so two things keep an attack out of the
 * draft. A call the recorded policy refused for a reason other than not being configured yet is left
 * out. And a tool, host, domain or path root seen in fewer sessions than the threshold is listed for
 * review in a comment, never allowed. Only generalizations are written, never an argument's value.
 */
public final class Suggester {

    /** Refusals that only say the policy did not list the call yet, as in monitor mode on a new policy. */
    private static final Set<String> NOT_CONFIGURED = Set.of(BuiltInRules.AGENT_KNOWN, BuiltInRules.ALLOW_LIST,
            BuiltInRules.MCP_SERVERS, BuiltInRules.ARGUMENTS_DECLARED);
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final int MIN_BUDGET = 10;

    private final int minSessions;
    private final String className;

    /**
     * @param minSessions how many sessions a tool or value must appear in before it is suggested
     * @param className   the generated class, with its package if any
     */
    public Suggester(int minSessions, String className) {
        this.minSessions = minSessions;
        this.className = className;
    }

    /**
     * Drafts a policy.
     *
     * @param history recorded session events
     * @return the draft, as Java source and figures
     */
    public SuggestedPolicy suggest(Stream<SessionEvent> history) {
        var agents = new TreeMap<String, AgentTally>();
        var leftOut = new TreeMap<String, Integer>();
        var sessions = new HashSet<String>();
        int calls = 0;
        int counted = 0;
        Instant from = null;
        Instant to = null;
        for (SessionEvent event : (Iterable<SessionEvent>) history::iterator) {
            if (!(event instanceof SessionEvent.CallDecided call)) {
                continue;
            }
            calls++;
            sessions.add(call.sessionId());
            from = from == null || call.at().isBefore(from) ? call.at() : from;
            to = to == null || call.at().isAfter(to) ? call.at() : to;
            if (!counts(call.computed())) {
                leftOut.merge(call.computed().ruleId(), 1, Integer::sum);
                continue;
            }
            counted++;
            agents.computeIfAbsent(call.agentId(), id -> new AgentTally()).add(call);
        }
        var review = new ArrayList<String>();
        String source = render(agents, review, calls, sessions.size(), leftOut, from, to);
        return new SuggestedPolicy(sessions.size(), calls, counted, leftOut, review.size(), from, to, source);
    }

    private static boolean counts(Decision computed) {
        return switch (computed) {
            case Decision.Allow allow -> true;
            case Decision.Downgrade downgrade -> true;
            case Decision.Deny deny -> NOT_CONFIGURED.contains(deny.ruleId());
            case Decision.Defer defer -> NOT_CONFIGURED.contains(defer.ruleId());
            case Decision.Terminate terminate -> false;
        };
    }

    // --- tallies ---------------------------------------------------------------------------------------

    /** How often something was seen, and in how many sessions. */
    private static final class Tally {
        private final Set<String> sessions = new HashSet<>();
        private int calls;

        void add(String sessionId) {
            sessions.add(sessionId);
            calls++;
        }

        String describe() {
            return calls + (calls == 1 ? " call" : " calls") + " in " + sessions.size()
                    + (sessions.size() == 1 ? " session" : " sessions");
        }
    }

    private static final class AgentTally {
        private final Tally tally = new Tally();
        private final Map<String, Integer> callsPerSession = new HashMap<>();
        private final Map<String, ToolTally> tools = new TreeMap<>();

        void add(SessionEvent.CallDecided call) {
            tally.add(call.sessionId());
            callsPerSession.merge(call.sessionId(), 1, Integer::sum);
            tools.computeIfAbsent(reference(call.tool()), reference -> new ToolTally(call.tool())).add(call);
        }
    }

    private static final class ToolTally {
        private final ToolId tool;
        private final Tally tally = new Tally();
        private final Map<String, ArgTally> arguments = new TreeMap<>();

        ToolTally(ToolId tool) {
            this.tool = tool;
        }

        void add(SessionEvent.CallDecided call) {
            tally.add(call.sessionId());
            call.args().fields().forEach((name, value) ->
                    arguments.computeIfAbsent(name, n -> new ArgTally()).add(call.sessionId(), value));
        }
    }

    private enum Family { URL, EMAIL, PATH, NUMBER, TEXT, OTHER }

    /** A number an argument took, and the session it was in. */
    private record Amount(BigDecimal value, String sessionId) {}

    private static final class ArgTally {
        private final Set<Family> families = EnumSet.noneOf(Family.class);
        private final Map<String, Tally> hosts = new TreeMap<>();
        private final Map<String, Tally> addresses = new TreeMap<>();
        private final Set<String> schemes = new TreeSet<>();
        private int refusedUrls;
        private final Map<String, Tally> domains = new TreeMap<>();
        private final Map<String, Tally> roots = new TreeMap<>();
        private final List<Amount> numbers = new ArrayList<>();

        void add(String sessionId, ArgValue value) {
            switch (value) {
                case NullValue none -> { }
                case BooleanValue flag -> { }
                case ListValue list -> list.elements().forEach(element -> scalar(sessionId, element));
                default -> scalar(sessionId, value);
            }
        }

        private void scalar(String sessionId, ArgValue value) {
            switch (value) {
                case NumberValue number -> {
                    families.add(Family.NUMBER);
                    numbers.add(new Amount(number.value(), sessionId));
                }
                case TextValue text -> text(sessionId, text.value());
                case NullValue none -> { }
                case BooleanValue flag -> { }
                case ListValue list -> families.add(Family.OTHER);
                case ObjectValue object -> families.add(Family.OTHER);
            }
        }

        private void text(String sessionId, String text) {
            String lower = text.toLowerCase(Locale.ROOT);
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                families.add(Family.URL);
                switch (UrlCheck.check(text, UrlCheck.Rule.DEFAULT)) {
                    case UrlCheck.Accepted accepted -> {
                        schemes.add(accepted.target().scheme());
                        (accepted.target().address().isPresent() ? addresses : hosts)
                                .computeIfAbsent(accepted.target().host(), h -> new Tally()).add(sessionId);
                    }
                    case UrlCheck.Rejected rejected -> refusedUrls++;
                }
            } else if (EMAIL.matcher(text).matches()) {
                families.add(Family.EMAIL);
                domains.computeIfAbsent(text.substring(text.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT),
                        d -> new Tally()).add(sessionId);
            } else if (pathRoot(text) != null) {
                families.add(Family.PATH);
                roots.computeIfAbsent(pathRoot(text), r -> new Tally()).add(sessionId);
            } else {
                families.add(Family.TEXT);
            }
        }

        /** The first two directories of a plain absolute path, such as {@code /srv/reports}; null otherwise. */
        private static String pathRoot(String text) {
            if (!text.startsWith("/") || text.contains("\\") || text.indexOf('\0') >= 0) {
                return null;
            }
            List<String> parts = List.of(text.substring(1).split("/", -1));
            if (parts.stream().anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))) {
                return null;
            }
            List<String> directories = parts.subList(0, parts.size() - 1);
            if (directories.isEmpty()) {
                return null;
            }
            return "/" + String.join("/", directories.subList(0, Math.min(2, directories.size())));
        }

        Family family() {
            return families.size() == 1 ? families.iterator().next() : Family.OTHER;
        }
    }

    // --- rendering -------------------------------------------------------------------------------------

    private String render(Map<String, AgentTally> agents, List<String> review, int calls, int sessions,
                          Map<String, Integer> leftOut, Instant from, Instant to) {
        var body = new StringBuilder();
        var servers = new TreeSet<String>();
        boolean[] usesEmail = {false};
        boolean[] usesDefer = {false};
        agents.forEach((agentId, agent) -> renderAgent(agentId, agent, body, servers, review, usesEmail, usesDefer));

        int dot = className.lastIndexOf('.');
        String simpleName = className.substring(dot + 1);
        var java = new StringBuilder();
        if (dot > 0) {
            java.append("package ").append(className, 0, dot).append(";\n\n");
        }
        java.append("import static io.toolgate.api.policy.ArgumentRules.*;\n");
        if (usesEmail[0]) {
            java.append("import static io.toolgate.email.EmailRules.emailDomains;\n");
        }
        java.append("\nimport io.toolgate.api.Mode;\n");
        if (usesDefer[0]) {
            java.append("import io.toolgate.api.policy.OnViolation;\n");
        }
        java.append("import io.toolgate.api.policy.ToolGatePolicy;\nimport java.util.function.Supplier;\n\n");
        java.append("/**\n * Suggested by {@code toolgate suggest} from ").append(calls).append(" tool calls in ")
                .append(sessions).append(" sessions");
        if (from != null) {
            java.append(", ").append(from).append(" to ").append(to);
        }
        java.append(".\n *\n");
        java.append(" * <p>Review every line before you use it: history also holds what an injected model asked for.\n");
        if (!leftOut.isEmpty()) {
            java.append(" * Left out, because the recorded policy refused them: ");
            java.append(String.join(", ", leftOut.entrySet().stream()
                    .map(entry -> entry.getValue() + " by " + comment(entry.getKey())).toList())).append(".\n");
        }
        java.append(" * Labels and effects cannot be seen in history: add them for each tool (see the policies guide).\n");
        java.append(" * Rules on arguments need each tool's definition in the tool catalog; the adapters fill it.\n");
        java.append(" */\n");
        java.append("public final class ").append(simpleName).append(" implements Supplier<ToolGatePolicy> {\n\n");
        java.append("    @Override\n    public ToolGatePolicy get() {\n");
        java.append("        return ToolGatePolicy.builder()\n");
        java.append("                .mode(Mode.MONITOR)\n");
        if (!servers.isEmpty()) {
            java.append("                .mcpServers(").append(literals(servers)).append(")\n");
        }
        java.append(body);
        if (!review.isEmpty()) {
            java.append("                // Seen in fewer than ").append(minSessions)
                    .append(" sessions, so not allowed; review each before adding it:\n");
            review.forEach(line -> java.append("                //   ").append(line).append('\n'));
        }
        java.append("                .build();\n    }\n}\n");
        return java.toString();
    }

    private void renderAgent(String agentId, AgentTally agent, StringBuilder body, Set<String> servers,
                             List<String> review, boolean[] usesEmail, boolean[] usesDefer) {
        var allowed = new ArrayList<ToolTally>();
        for (ToolTally tool : agent.tools.values()) {
            if (tool.tally.sessions.size() >= minSessions) {
                allowed.add(tool);
            } else {
                review.add("agent " + comment(agentId) + ", tool " + comment(reference(tool.tool)) + ": "
                        + tool.tally.describe());
            }
        }
        if (allowed.isEmpty()) {
            return;
        }
        body.append("                .agent(").append(literal(agentId)).append(", a -> a\n");
        body.append("                        // ").append(agent.tally.describe()).append('\n');
        body.append("                        .allow(")
                .append(literals(allowed.stream().map(tool -> reference(tool.tool)).toList())).append(")\n");
        for (ToolTally tool : allowed) {
            if (!tool.tool.isLocal()) {
                servers.add(tool.tool.source().substring("mcp:".length()));
            }
            tool.arguments.forEach((name, argument) ->
                    renderArgument(agentId, tool, name, argument, body, review, usesEmail, usesDefer));
        }
        int most = agent.callsPerSession.values().stream().max(Comparator.naturalOrder()).orElse(0);
        int budget = Math.max(MIN_BUDGET, (most * 3 + 1) / 2);
        body.append("                        // At most ").append(most).append(" calls in one session\n");
        body.append("                        .budget(b -> b.toolCalls(").append(budget).append(")))\n");
    }

    private void renderArgument(String agentId, ToolTally tool, String name, ArgTally argument, StringBuilder body,
                                List<String> review, boolean[] usesEmail, boolean[] usesDefer) {
        if (name.contains(".") || name.isBlank()) {
            return;
        }
        String target = literal((tool.tool.isLocal() ? tool.tool.name() : tool.tool.toString()) + "." + name);
        String where = "agent " + comment(agentId) + ", " + unquoted(target);
        switch (argument.family()) {
            case URL -> {
                List<String> hosts = frequent(argument.hosts, where + " host ", review);
                // A rule names hosts, never addresses: a URL to an address is denied by hosts(...), so review it.
                argument.addresses.forEach((address, tally) ->
                        review.add(where + " address " + address + ": " + tally.describe()));
                String refused = argument.refusedUrls == 0 ? "" : "; " + argument.refusedUrls
                        + " more the URL check refuses (special address, user info or port), which the rule denies too";
                if (hosts.isEmpty()) {
                    body.append("                        // ").append(unquoted(target))
                            .append(": no host seen often enough; add hosts(...) after review").append(refused).append('\n');
                    return;
                }
                body.append("                        // ").append(unquoted(target)).append(": ").append(hosts.size())
                        .append(hosts.size() == 1 ? " host" : " hosts").append(refused).append('\n');
                body.append("                        .rule(").append(target).append(", schemes(")
                        .append(literals(argument.schemes)).append("), hosts(").append(literals(hosts)).append("))\n");
            }
            case EMAIL -> {
                List<String> domains = frequent(argument.domains, where + " domain ", review);
                if (domains.isEmpty()) {
                    body.append("                        // ").append(unquoted(target))
                            .append(": no domain seen often enough; add emailDomains(...) after review\n");
                    return;
                }
                usesEmail[0] = true;
                body.append("                        .rule(").append(target).append(", emailDomains(")
                        .append(literals(domains)).append("))\n");
            }
            case PATH -> {
                List<String> roots = frequent(argument.roots, where + " under ", review);
                if (roots.isEmpty()) {
                    return;
                }
                body.append("                        .rule(").append(target).append(", under(")
                        .append(literals(roots)).append("))\n");
            }
            case NUMBER -> {
                List<BigDecimal> sorted = argument.numbers.stream().map(Amount::value).sorted().toList();
                BigDecimal max = sorted.getLast();
                BigDecimal p95 = sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * 0.95) - 1));
                BigDecimal common = commonMaximum(argument.numbers);
                if (common == null) {
                    review.add(where + ": numbers seen in fewer than " + minSessions + " sessions, so no limit");
                    return;
                }
                BigDecimal limit = p95.min(common);
                usesDefer[0] = true;
                body.append("                        // ").append(unquoted(target)).append(": ").append(sorted.size())
                        .append(" values, largest ").append(plain(max)).append("; limit ").append(plain(limit))
                        .append(limit.compareTo(p95) < 0 ? ", the largest seen in " + minSessions + " sessions"
                                : ", the 95th percentile")
                        .append("; larger ones wait for review\n");
                body.append("                        .rule(").append(target);
                if (sorted.getFirst().signum() >= 0) {
                    body.append(", atLeast(\"0\")");
                }
                body.append(", atMost(").append(literal(plain(limit))).append(").otherwise(OnViolation.DEFER))\n");
            }
            case TEXT, OTHER -> { }
        }
    }

    /**
     * The largest value that values at least as large reach in enough sessions, so that one session's
     * outlier, such as an injected amount, never sets the limit.
     *
     * @return the value, or null if all values together span too few sessions
     */
    private BigDecimal commonMaximum(List<Amount> numbers) {
        var sessions = new HashSet<String>();
        for (Amount number : numbers.stream().sorted(Comparator.comparing(Amount::value).reversed()).toList()) {
            sessions.add(number.sessionId());
            if (sessions.size() >= minSessions) {
                return number.value();
            }
        }
        return null;
    }

    /** Values seen in enough sessions, in order; the others go to the review list. */
    private List<String> frequent(Map<String, Tally> values, String label, List<String> review) {
        var frequent = new ArrayList<String>();
        values.forEach((value, tally) -> {
            if (tally.sessions.size() >= minSessions) {
                frequent.add(value);
            } else {
                review.add(label + comment(value) + ": " + tally.describe());
            }
        });
        return frequent;
    }

    private static String reference(ToolId tool) {
        return tool.isLocal() ? tool.name() : tool.toString();
    }

    private static String plain(BigDecimal number) {
        BigDecimal stripped = number.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0, RoundingMode.UNNECESSARY) : stripped).toPlainString();
    }

    private static String literals(Iterable<String> values) {
        var joined = new ArrayList<String>();
        values.forEach(value -> joined.add(literal(value)));
        return String.join(", ", joined);
    }

    /**
     * A Java string literal; anything outside printable ASCII is escaped, so the source stays plain.
     * Control characters take octal escapes, never Unicode escapes: the compiler decodes those before
     * it reads the source, so an escaped line break would end a comment or a literal.
     */
    static String literal(String value) {
        var text = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> text.append("\\\"");
                case '\\' -> text.append("\\\\");
                default -> {
                    if (c >= ' ' && c <= '~') {
                        text.append(c);
                    } else if (c < 0x80) {
                        text.append(String.format(Locale.ROOT, "\\%03o", (int) c));
                    } else {
                        text.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    }
                }
            }
        }
        return text.append('"').toString();
    }

    /** Text from history for a comment: escaped as in a literal, so it can never end the comment. */
    private static String comment(String text) {
        return unquoted(literal(text));
    }

    private static String unquoted(String literal) {
        return literal.substring(1, literal.length() - 1).replace("*/", "*\\/");
    }
}
