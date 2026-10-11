package io.toolgate.engine;

import io.toolgate.api.ArgValue;
import io.toolgate.api.ArgValueJson;
import io.toolgate.api.Decision;
import io.toolgate.api.ReviewArgument;
import io.toolgate.api.TextValue;
import io.toolgate.api.ToolCall;
import io.toolgate.api.policy.AgentPolicy;
import io.toolgate.api.policy.Mask;
import io.toolgate.api.policy.ReviewView;
import io.toolgate.api.policy.ToolArgument;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a deferred call into what a review screen may show: each argument shown, masked or hidden by
 * the agent's review view, with warnings on values that look like something else, and a digest that
 * binds an approval to exactly what was shown.
 */
final class ReviewViews {

    private static final String HIDDEN = "";
    private static final int MIN_QUOTED_LENGTH = 3;

    private ReviewViews() {}

    /** What a review screen shows of one call: its title, arguments, deferral reason, and their digest. */
    record Shown(String title, List<ReviewArgument> arguments, String reason, String digest) {}

    /**
     * What a review screen may show of a call. The agent may be gone, after a policy that dropped it was
     * deployed: then there is no view and no rule, so every argument is masked.
     */
    static Shown show(String approvalId, Optional<AgentPolicy> agent, ToolCall call, Decision.Defer deferral) {
        List<ReviewArgument> arguments = arguments(agent, call);
        String reason = reason(agent, call, deferral, arguments);
        String title = agent.map(found -> found.reviews().get(call.tool())).map(ReviewView::title).orElse(call.tool().name());
        return new Shown(title, arguments, reason, digest(approvalId, call, deferral.ruleId(), reason, arguments));
    }

    /**
     * The deferral reason, unless it would give away an argument the view masks or hides, such as "480 is
     * above 200" for a masked amount: then only the rule is named.
     */
    private static String reason(Optional<AgentPolicy> agent, ToolCall call, Decision.Defer deferral,
                                 List<ReviewArgument> arguments) {
        for (ReviewArgument argument : arguments) {
            if (argument.visibility() == ReviewArgument.Visibility.SHOWN) {
                continue;
            }
            String raw = text(call.args().fields().get(argument.name()));
            boolean ruleOnIt = new ToolArgument(call.tool(), argument.name()).ruleId().equals(deferral.ruleId());
            // Very short values, such as "1", appear in any sentence; quoting them gives nothing away.
            if (ruleOnIt || (raw.length() >= MIN_QUOTED_LENGTH && deferral.reason().contains(raw))) {
                return "withheld, since it quotes the argument '" + argument.name() + "', which this view does not show";
            }
        }
        return deferral.reason();
    }

    /**
     * The call's arguments as a reviewer may see them. Without a view, or for arguments the view does not
     * name, an argument with a rule is shown, since it is why the call waits, and any other is masked.
     */
    static List<ReviewArgument> arguments(Optional<AgentPolicy> agent, ToolCall call) {
        ReviewView view = agent.map(found -> found.reviews().get(call.tool())).orElse(null);
        Map<ToolArgument, ?> rules = agent.<Map<ToolArgument, ?>>map(AgentPolicy::argumentRules).orElse(Map.of());
        var arguments = new ArrayList<ReviewArgument>();
        for (Map.Entry<String, ArgValue> argument : call.args().fields().entrySet()) {
            String name = argument.getKey();
            String value = text(argument.getValue());
            if (view != null && view.hidden().contains(name)) {
                arguments.add(new ReviewArgument(name, HIDDEN, ReviewArgument.Visibility.HIDDEN, List.of()));
            } else if (view != null && view.masks().containsKey(name)) {
                arguments.add(masked(name, value, view.masks().get(name)));
            } else if ((view != null && view.show().contains(name))
                    || rules.containsKey(new ToolArgument(call.tool(), name))) {
                arguments.add(new ReviewArgument(name, value, ReviewArgument.Visibility.SHOWN, warnings(value)));
            } else {
                arguments.add(masked(name, value, Mask.full()));
            }
        }
        return arguments;
    }

    /** What the reviewer is told about a value: shown text could be built to look like something it is not. */
    static List<String> warnings(String value) {
        var warnings = new ArrayList<String>();
        if (value.codePoints().anyMatch(point -> Character.getType(point) == Character.FORMAT)) {
            warnings.add("contains invisible or text-direction characters");
        }
        if (value.codePoints().anyMatch(point -> Character.isISOControl(point) && point != '\n' && point != '\t')) {
            warnings.add("contains control characters");
        }
        if (value.codePoints().anyMatch(point -> point > 0x7F && Character.isLetterOrDigit(point))) {
            warnings.add("contains non-ASCII letters or digits, which can make one name look like another");
        }
        return warnings;
    }

    /** A hash of everything a review screen shows of a call, so an approval names exactly that. */
    private static String digest(String approvalId, ToolCall call, String deferralRule, String deferralReason,
                         List<ReviewArgument> arguments) {
        var shown = new ArrayList<ArgValue>();
        for (ReviewArgument argument : arguments) {
            shown.add(ArgValue.list(List.of(ArgValue.text(argument.name()),
                    ArgValue.text(argument.visibility().name()), ArgValue.text(argument.display()))));
        }
        String canonical = ArgValueJson.write(ArgValue.list(List.of(ArgValue.text(approvalId),
                ArgValue.text(call.tool().toString()), ArgValue.text(deferralRule), ArgValue.text(deferralReason),
                ArgValue.list(shown))));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("every JDK has SHA-256", impossible);
        }
    }

    private static ReviewArgument masked(String name, String value, Mask mask) {
        String display = mask.apply(value);
        return new ReviewArgument(name, display, ReviewArgument.Visibility.MASKED,
                display.equals(mask.apply("")) ? List.of() : warnings(display));
    }

    private static String text(ArgValue value) {
        return value instanceof TextValue(String text) ? text : ArgValueJson.write(value);
    }
}
