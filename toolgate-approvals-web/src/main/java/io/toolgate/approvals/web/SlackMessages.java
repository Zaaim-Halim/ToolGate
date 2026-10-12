package io.toolgate.approvals.web;

import io.toolgate.api.ArgValue;
import io.toolgate.api.ArgValueJson;
import io.toolgate.api.Provenance;
import io.toolgate.api.ReviewArgument;
import io.toolgate.api.ReviewItem;
import io.toolgate.api.Tenant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Approval messages as Slack Block Kit JSON. Every value from a call is {@code plain_text}, so Slack never
 * reads it as markup: a model-written {@code <!channel>}, mention or link stays text. A message carries an
 * Approve button only if it shows everything the approval's digest covers, in full; anything Slack's limits
 * would cut, and the reviewer goes to the console.
 */
final class SlackMessages {

    static final String APPROVE = "toolgate_approve";
    static final String OPEN = "toolgate_open";
    /** Slack's limit on a section's text. */
    static final int SECTION_LIMIT = 3000;
    /** Slack's limit on a message's blocks. */
    static final int MAX_BLOCKS = 50;
    private static final int HEADER_LIMIT = 150;

    private SlackMessages() {}

    /**
     * A message for a deferred call: the review view and an Approve button, if it all fits.
     *
     * @param item        the call as the channel's reviewers may see it
     * @param consoleLink the call in the console, or null
     * @return the message JSON
     */
    static String review(ReviewItem item, String consoleLink) {
        var blocks = new ArrayList<ArgValue>();
        blocks.add(header(item.agentId() + " asks to run " + item.tool()));
        var sections = new ArrayList<String>();
        sections.add(item.title());
        sections.add("Waits because of " + item.deferralRule()
                + (item.deferralReason() == null ? "" : ": " + item.deferralReason()));
        if (!item.provenance().isEmpty()) {
            sections.add("The session read " + item.provenance().stream().map(SlackMessages::source)
                    .collect(Collectors.joining(" and ")) + ". Its wording may be trying to talk you into this.");
        }
        for (ReviewArgument argument : item.arguments()) {
            var text = new StringBuilder(argument.name()).append(": ").append(argument.display());
            if (argument.visibility() != ReviewArgument.Visibility.SHOWN) {
                text.append(" (").append(argument.visibility().name().toLowerCase(java.util.Locale.ROOT)).append(')');
            }
            argument.warnings().forEach(warning -> text.append("\n⚠ ").append(warning));
            sections.add(text.toString());
        }
        boolean fits = sections.stream().allMatch(section -> section.length() <= SECTION_LIMIT)
                && sections.size() + 3 <= MAX_BLOCKS;
        sections.stream().limit(MAX_BLOCKS - 3).forEach(section -> blocks.add(section(cut(section, SECTION_LIMIT))));
        blocks.add(context("For " + item.requester().id() + tenant(item.requester().tenant()) + ". Expires "
                + item.expiresAt() + "." + (fits ? "" : " Too long to review here: open it in the console.")));
        var buttons = new ArrayList<ArgValue>();
        if (fits && item.mayDecide()) {
            buttons.add(object("type", "button", "action_id", APPROVE, "style", "primary",
                    "text", plain("Approve"), "value", item.approvalId() + " " + item.digest()));
        }
        if (consoleLink != null) {
            buttons.add(object("type", "button", "action_id", OPEN, "text", plain(fits ? "Reject or review in the console"
                    : "Review in the console"), "url", consoleLink));
        }
        if (!buttons.isEmpty()) {
            blocks.add(object("type", "actions", "elements", ArgValue.list(buttons)));
        }
        return message(item.agentId() + " asks to run " + item.tool() + ". It waits for a decision.", blocks);
    }

    /**
     * A message for a deferred call without its arguments: who asks to run what, and a link.
     *
     * @param agent       the agent
     * @param tool        the tool
     * @param consoleLink the call in the console, or null
     * @param approvalId  the call
     * @return the message JSON
     */
    static String notice(String agent, String tool, String consoleLink, String approvalId) {
        String text = agent + " asks to run " + tool + ". It waits for a decision.";
        var blocks = new ArrayList<ArgValue>();
        blocks.add(section(cut(consoleLink == null ? text + " Approval ID: " + approvalId : text, SECTION_LIMIT)));
        if (consoleLink != null) {
            blocks.add(object("type", "actions", "elements", ArgValue.list(List.of(object("type", "button",
                    "action_id", OPEN, "text", plain("Review in the console"), "url", consoleLink)))));
        }
        return message(text, blocks);
    }

    /**
     * What replaces a message once someone acted on it from Slack, or a reply only the clicker sees.
     *
     * @param text     what happened
     * @param replace  whether it replaces the original message; else it is shown to the clicker only
     * @return the response JSON
     */
    static String response(String text, boolean replace) {
        var fields = new LinkedHashMap<String, ArgValue>();
        fields.put("replace_original", ArgValue.bool(replace));
        if (!replace) {
            fields.put("response_type", ArgValue.text("ephemeral"));
        }
        fields.put("text", ArgValue.text(cut(text, SECTION_LIMIT)));
        fields.put("blocks", ArgValue.list(List.of(section(cut(text, SECTION_LIMIT)))));
        return ArgValueJson.write(ArgValue.object(fields));
    }

    private static String source(Provenance provenance) {
        return provenance.label().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + " from " + provenance.tool();
    }

    private static String tenant(Tenant tenant) {
        return tenant instanceof Tenant.Scoped scoped ? " in " + scoped.id() : "";
    }

    private static String message(String fallback, List<ArgValue> blocks) {
        var fields = new LinkedHashMap<String, ArgValue>();
        // The notification text is plain too: Slack reads mrkdwn in "text" unless told otherwise.
        fields.put("text", ArgValue.text(cut(fallback, SECTION_LIMIT)));
        fields.put("mrkdwn", ArgValue.bool(false));
        fields.put("blocks", ArgValue.list(blocks));
        return ArgValueJson.write(ArgValue.object(fields));
    }

    private static ArgValue header(String text) {
        return object("type", "header", "text", plain(cut(text, HEADER_LIMIT)));
    }

    private static ArgValue section(String text) {
        return object("type", "section", "text", plain(text));
    }

    private static ArgValue context(String text) {
        return object("type", "context", "elements", ArgValue.list(List.of(plain(cut(text, SECTION_LIMIT)))));
    }

    private static ArgValue plain(String text) {
        return object("type", "plain_text", "text", text, "emoji", false);
    }

    private static String cut(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
    }

    /** An object from name and value pairs: strings, booleans or ArgValues. */
    private static ArgValue object(Object... pairs) {
        Map<String, ArgValue> fields = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            fields.put((String) pairs[i], switch (value) {
                case ArgValue argValue -> argValue;
                case Boolean flag -> ArgValue.bool(flag);
                default -> ArgValue.text(String.valueOf(value));
            });
        }
        return ArgValue.object(fields);
    }
}
