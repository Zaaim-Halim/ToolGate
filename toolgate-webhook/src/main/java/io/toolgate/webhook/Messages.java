package io.toolgate.webhook;

import io.toolgate.webhook.ApprovalWebhook.Notice;

/** Writes a notice as signed-webhook JSON or as a Slack message. */
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
        String agent = "*" + escape(notice.agent()) + "*";
        String tool = "`" + escape(notice.tool()) + "`";
        String text = switch (notice.type()) {
            case "approval.deferred" -> ":hourglass: " + agent + " asks to run " + tool
                    + (notice.tenant() == null ? "" : " for " + escape(notice.tenant())) + ". It waits for a decision.";
            case "approval.expiring" -> ":alarm_clock: A request from " + agent + " to run " + tool + " expires at "
                    + notice.expiresAt() + " unless someone decides.";
            case "approval.recorded" -> ":lock: " + escape(notice.by()) + " approved " + agent + "'s request to run "
                    + tool + " as a super admin. It needs a second person.";
            case "approval.decided" -> decided(notice, agent, tool);
            case "approval.finished" -> finished(notice, agent, tool);
            case "superadmin.burst" -> ":rotating_light: " + notice.burst().decisions() + " super admin decisions within "
                    + notice.burst().window() + ", the last by " + escape(notice.by()) + ". Check that they were expected.";
            default -> escape(notice.type());
        };
        if (link != null) {
            text += " <" + link + "|" + (notice.type().equals("approval.finished") ? "Open it" : "Review it") + ">";
        } else if (notice.approvalId() != null) {
            text += " (" + escape(notice.approvalId()) + ")";
        }
        var json = new StringBuilder("{");
        field(json, "text", text);
        return json.append('}').toString();
    }

    private static String decided(Notice notice, String agent, String tool) {
        String by = notice.by() == null || notice.by().equals("toolgate") ? "" : " by " + escape(notice.by());
        return switch (notice.status()) {
            case "released" -> ":white_check_mark: " + agent + "'s request to run " + tool + " was approved" + by + ".";
            case "refused" -> ":no_entry: " + agent + "'s request to run " + tool
                    + " was approved" + by + ", but refused on re-check: something changed since.";
            case "rejected" -> ":x: " + agent + "'s request to run " + tool + " was rejected" + by + ".";
            case "cancelled" -> ":leftwards_arrow_with_hook: " + agent + "'s request to run " + tool + " was withdrawn" + by + ".";
            case "expired" -> ":hourglass_flowing_sand: " + agent + "'s request to run " + tool + " expired: nobody decided.";
            default -> agent + "'s request to run " + tool + " is now " + escape(notice.status()) + by + ".";
        };
    }

    private static String finished(Notice notice, String agent, String tool) {
        return switch (notice.status()) {
            case "executed" -> ":white_check_mark: " + tool + " ran for " + agent + ".";
            case "failed" -> ":warning: " + tool + " failed for " + agent + ".";
            case "unknown" -> ":question: Nobody knows whether " + tool + " ran for " + agent
                    + ". Check the real system, then resolve it in the console.";
            default -> tool + " for " + agent + " is now " + escape(notice.status()) + ".";
        };
    }

    /** Slack's three control characters; everything else is shown as written. */
    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void field(StringBuilder json, String name, String value) {
        if (value == null) {
            return;
        }
        if (json.length() > 1) {
            json.append(',');
        }
        json.append('"').append(name).append("\":\"");
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
        json.append('"');
    }
}
