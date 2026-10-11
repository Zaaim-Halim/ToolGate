package io.toolgate.approvals.web;

import io.toolgate.spi.ApprovalListener;
import io.toolgate.spi.AuditSink;
import io.toolgate.webhook.ApprovalWebhook;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The webhooks configured under {@code toolgate.approvals.notify.webhooks}, as one approval listener the gate
 * picks up. Each webhook keeps its own queue, so a slow receiver delays only its own deliveries.
 */
public final class ToolGateWebhooks implements ApprovalListener, AutoCloseable {

    private final List<ApprovalWebhook> webhooks;

    ToolGateWebhooks(NotifyProperties properties) {
        var built = new ArrayList<ApprovalWebhook>();
        try {
            for (int index = 0; index < properties.webhooks().size(); index++) {
                built.add(build(properties.webhooks().get(index), index, properties.consoleUrl()));
            }
        } catch (RuntimeException invalid) {
            built.forEach(ApprovalWebhook::close);
            throw invalid;
        }
        this.webhooks = List.copyOf(built);
    }

    /**
     * The webhooks, in the order configured, such as to report their {@link ApprovalWebhook#dropped()} counts.
     *
     * @return the webhooks
     */
    public List<ApprovalWebhook> webhooks() {
        return webhooks;
    }

    @Override
    public AuditSink asSink() {
        List<AuditSink> sinks = webhooks.stream().map(ApprovalListener::asSink).toList();
        return event -> sinks.forEach(sink -> sink.emit(event));
    }

    /** Sends what each webhook has queued, waiting a few seconds at most for each. */
    @Override
    public void close() {
        webhooks.forEach(ApprovalWebhook::close);
    }

    private static ApprovalWebhook build(NotifyProperties.Webhook settings, int index, String consoleUrl) {
        String prefix = "toolgate.approvals.notify.webhooks[" + index + "]";
        if (settings.url() == null) {
            throw new IllegalArgumentException(prefix + ".url is required");
        }
        ApprovalWebhook.Builder builder = switch (constant(settings.format())) {
            case "SIGNED_JSON" -> {
                if (settings.secret() == null || settings.secret().isBlank()) {
                    throw new IllegalArgumentException(prefix + ".secret is required for signed-json");
                }
                yield ApprovalWebhook.signed(settings.url(), settings.secret().getBytes(StandardCharsets.UTF_8));
            }
            case "SLACK" -> ApprovalWebhook.slack(settings.url());
            case "TEAMS" -> ApprovalWebhook.teams(settings.url());
            default -> throw new IllegalArgumentException(prefix + ".format must be signed-json, slack or teams, was "
                    + settings.format());
        };
        if (consoleUrl != null) {
            builder.consoleUrl(consoleUrl);
        }
        if (!settings.events().isEmpty()) {
            try {
                builder.events(settings.events().stream().map(name -> ApprovalWebhook.Event.valueOf(constant(name)))
                        .toArray(ApprovalWebhook.Event[]::new));
            } catch (IllegalArgumentException unknown) {
                // The message says all there is; the enum's own would only name the constant it tried.
                throw new IllegalArgumentException(prefix + ".events names an unknown event: " + settings.events()
                        + "; use deferred, expiring, decided, recorded, finished or super-admin-burst");
            }
        }
        return builder.build();
    }

    /** {@code signed-json} and {@code super-admin-burst} as their constants' names. */
    private static String constant(String name) {
        return name.strip().replace('-', '_').toUpperCase(Locale.ROOT);
    }
}
