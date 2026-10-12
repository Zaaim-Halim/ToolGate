package io.toolgate.approvals.web;

import io.toolgate.api.Decision;
import io.toolgate.api.Principal;
import io.toolgate.api.ReviewItem;
import io.toolgate.api.Tenant;
import io.toolgate.api.ToolGate;
import io.toolgate.spi.ApprovalListener;
import io.toolgate.spi.AuditEvent;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Posts a deferred call to the Slack channels configured for its agent: the review view and an Approve button
 * where the channel shows arguments and the application maps Slack users to reviewers, a link to the console
 * otherwise. Messages go out on one background thread, never on the path of the call.
 */
final class SlackApprovalNotifier implements ApprovalListener, AutoCloseable {

    private static final System.Logger LOG = System.getLogger("toolgate.adapter");
    private static final int ATTEMPTS = 3;

    private final List<SlackProperties.Channel> channels;
    private final Supplier<ToolGate> gate;
    private final boolean approveInSlack;
    private final String consoleUrl;
    private final SlackClient client;
    private final ExecutorService sender = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "toolgate-slack");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * @param approveInSlack whether Slack users can be mapped to reviewers, so an Approve button may be posted
     */
    SlackApprovalNotifier(List<SlackProperties.Channel> channels, Supplier<ToolGate> gate, boolean approveInSlack,
                          String consoleUrl, SlackClient client) {
        this.channels = List.copyOf(channels);
        this.gate = gate;
        this.approveInSlack = approveInSlack;
        this.consoleUrl = consoleUrl;
        this.client = client;
    }

    @Override
    public void deferred(AuditEvent.Decided event) {
        if (!(event.applied() instanceof Decision.Defer defer)) {
            return;
        }
        for (int index = 0; index < channels.size(); index++) {
            SlackProperties.Channel channel = channels.get(index);
            if (!channel.agents().isEmpty() && !channel.agents().contains(event.agentId())) {
                continue;
            }
            String json = message(index, channel, defer.approvalId(), event.agentId(), event.tool().toString());
            sender.execute(() -> post(channel.webhookUrl(), json));
        }
    }

    private String message(int index, SlackProperties.Channel channel, String approvalId, String agent, String tool) {
        String link = link(approvalId);
        if (channel.showArguments()) {
            Optional<ReviewItem> item = gate.get().reviewItem(approvalId, viewer(index, channel));
            if (item.isPresent()) {
                ReviewItem shown = approveInSlack ? item.get() : withoutDecision(item.get());
                return SlackMessages.review(shown, link);
            }
        }
        return SlackMessages.notice(agent, tool, link, approvalId);
    }

    /** The channel's members, as one reviewer of its tenant with its roles: the view they may all see. */
    static Principal viewer(int index, SlackProperties.Channel channel) {
        Tenant tenant = channel.tenant() == null || channel.tenant().isBlank() ? Tenant.none() : Tenant.of(channel.tenant());
        return Principal.user("slack-channel-" + index, tenant, channel.roles().toArray(String[]::new));
    }

    private static ReviewItem withoutDecision(ReviewItem item) {
        return new ReviewItem(item.approvalId(), item.agentId(), item.tool(), item.title(), item.requester(), item.status(),
                item.expiresAt(), item.deferralRule(), item.deferralReason(), item.arguments(), item.provenance(),
                item.decisions(), item.access(), false, item.digest());
    }

    private String link(String approvalId) {
        if (consoleUrl == null || consoleUrl.isBlank()) {
            return null;
        }
        return consoleUrl + (consoleUrl.contains("?") ? "&" : "?") + "id=" + URLEncoder.encode(approvalId, StandardCharsets.UTF_8);
    }

    private void post(URI url, String json) {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                client.post(url, json);
                return;
            } catch (IOException failed) {
                if (attempt == ATTEMPTS) {
                    // Only the host: a webhook URL is itself a secret.
                    LOG.log(System.Logger.Level.WARNING, "could not post an approval message to Slack ({0}): {1}",
                            url.getHost(), failed.getMessage());
                    return;
                }
                try {
                    TimeUnit.SECONDS.sleep(attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Override
    public void close() {
        sender.shutdown();
        try {
            sender.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
