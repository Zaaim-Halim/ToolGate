package io.toolgate.webhook;

import io.toolgate.webhook.ApprovalWebhook.Notice;

/** Writes a notice as signed-webhook JSON, a Slack message or a Teams card. */
final class Messages {

    private Messages() {}

    /** The signed format: flat JSON, fields that do not apply left out. */
    static String json(Notice notice, String link) {
        var json = new StringBuilder("{");
        field(json, "type", notice.type());
        field(json, "time", notice.time().toString());
        field(json, "approvalId", notice.approvalId());
        field(json, "agent", notice.agent());
        field(json, "tool", notice.tool());
        field(json, "tenant", notice.tenant());
        field(json, "status", notice.status());
        field(json, "by", notice.by());
        field(json, "access", notice.access());
        field(json, "expiresAt", notice.expiresAt() == null ? null : notice.expiresAt().toString());
        if (notice.burst() != null) {
            json.append(",\"decisions\":").append(notice.burst().decisions());
            field(json, "window", notice.burst().window().toString());
        }
        field(json, "link", link);
        return json.append('}').toString();
    }

    /** The Slack format: one sentence a reviewer can act on, and a link. */
    static String slack(Notice notice, String link) {
        String text = sentence(notice, Style.SLACK);
        if (link != null) {
            text += " <" + link + "|" + action(notice) + ">";
        } else if (notice.approvalId() != null) {
            text += " (" + Style.SLACK.escape(notice.approvalId()) + ")";
        }
        var json = new StringBuilder("{");
        field(json, "text", text);
        return json.append('}').toString();
    }

    /**
     * The Teams format: an Adaptive Card in a message, which Teams workflow webhooks ("Post to a channel when
     * a webhook request is received") and the older incoming webhooks both accept.
     */
    static String teams(Notice notice, String link) {
        String text = sentence(notice, Style.TEAMS);
        if (link == null && notice.approvalId() != null) {
            text += " (" + notice.approvalId() + ")";
        }
        var card = new StringBuilder("{\"type\":\"message\",\"attachments\":[{\"contentType\":")
                .append(string("application/vnd.microsoft.card.adaptive"))
                .append(",\"content\":{\"$schema\":").append(string("http://adaptivecards.io/schemas/adaptive-card.json"))
                .append(",\"type\":\"AdaptiveCard\",\"version\":\"1.4\",\"body\":[")
                .append("{\"type\":\"TextBlock\",\"text\":").append(string("ToolGate"))
                .append(",\"weight\":\"Bolder\",\"size\":\"Small\",\"isSubtle\":true},")
                .append("{\"type\":\"TextBlock\",\"text\":").append(string(text)).append(",\"wrap\":true}]");
        if (link != null) {
            card.append(",\"actions\":[{\"type\":\"Action.OpenUrl\",\"title\":").append(string(action(notice)))
                    .append(",\"url\":").append(string(link)).append("}]");
        }
        return card.append("}}]}").toString();
    }

    private static String action(Notice notice) {
        return notice.type().equals("approval.finished") ? "Open it" : "Review it";
    }

    /** One sentence a reviewer can act on, marked up for the chat tool. */
    private static String sentence(Notice notice, Style style) {
        String agent = style.bold(notice.agent());
        String tool = style.code(notice.tool());
        return switch (notice.type()) {
            case "approval.deferred" -> style.emoji(":hourglass:") + agent + " asks to run " + tool
                    + (notice.tenant() == null ? "" : " for " + style.escape(notice.tenant())) + ". It waits for a decision.";
            case "approval.expiring" -> style.emoji(":alarm_clock:") + "A request from " + agent + " to run " + tool
                    + " expires at " + notice.expiresAt() + " unless someone decides.";
            case "approval.recorded" -> style.emoji(":lock:") + style.escape(notice.by()) + " approved " + agent
                    + "'s request to run " + tool + " as a super admin. It needs a second person.";
            case "approval.decided" -> decided(notice, agent, tool, style);
            case "approval.finished" -> finished(notice, agent, tool, style);
            case "superadmin.burst" -> style.emoji(":rotating_light:") + notice.burst().decisions()
                    + " super admin decisions within " + notice.burst().window() + ", the last by "
                    + style.escape(notice.by()) + ". Check that they were expected.";
            default -> style.escape(notice.type());
        };
    }

    private static String decided(Notice notice, String agent, String tool, Style style) {
        String by = notice.by() == null || notice.by().equals("toolgate") ? "" : " by " + style.escape(notice.by());
        String request = agent + "'s request to run " + tool;
        return switch (notice.status()) {
            case "released" -> style.emoji(":white_check_mark:") + request + " was approved" + by + ".";
            case "refused" -> style.emoji(":no_entry:") + request + " was approved" + by
                    + ", but refused on re-check: something changed since.";
            case "rejected" -> style.emoji(":x:") + request + " was rejected" + by + ".";
            case "cancelled" -> style.emoji(":leftwards_arrow_with_hook:") + request + " was withdrawn" + by + ".";
            case "expired" -> style.emoji(":hourglass_flowing_sand:") + request + " expired: nobody decided.";
            default -> request + " is now " + style.escape(notice.status()) + by + ".";
        };
    }

    private static String finished(Notice notice, String agent, String tool, Style style) {
        return switch (notice.status()) {
            case "executed" -> style.emoji(":white_check_mark:") + tool + " ran for " + agent + ".";
            case "failed" -> style.emoji(":warning:") + tool + " failed for " + agent + ".";
            case "unknown" -> style.emoji(":question:") + "Nobody knows whether " + tool + " ran for " + agent
                    + ". Check the real system, then resolve it in the console.";
            default -> tool + " for " + agent + " is now " + style.escape(notice.status()) + ".";
        };
    }

    /** How a chat tool marks up a sentence. */
    private enum Style {
        /** Slack mrkdwn: bold, code, emoji shortcodes, and its three control characters escaped. */
        SLACK,
        /** Adaptive Card markdown: bold only, no emoji; the card's JSON escaping carries the rest. */
        TEAMS;

        String bold(String text) {
            return this == SLACK ? "*" + escape(text) + "*" : "**" + escape(text) + "**";
        }

        String code(String text) {
            return this == SLACK ? "`" + escape(text) + "`" : escape(text);
        }

        String emoji(String shortcode) {
            return this == SLACK ? shortcode + " " : "";
        }

        String escape(String text) {
            if (text == null) {
                return "";
            }
            // Adaptive Card markdown has no reliable escape, and IDs come from the application, not the model.
            return this == SLACK ? text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") : text;
        }
    }

    private static void field(StringBuilder json, String name, String value) {
        if (value == null) {
            return;
        }
        if (json.length() > 1) {
            json.append(',');
        }
        json.append('"').append(name).append("\":").append(string(value));
    }

    /** A JSON string, quoted and escaped. */
    private static String string(String value) {
        var json = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        return json.append('"').toString();
    }
}
