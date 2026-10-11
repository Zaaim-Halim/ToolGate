package io.toolgate.approvals.web;

import io.toolgate.api.ApprovalStatus;
import io.toolgate.api.Decision;
import io.toolgate.spi.ApprovalListener;
import io.toolgate.spi.AuditEvent;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;

/**
 * Emails reviewers when a call waits, expires soon or needs a second person, and everyone on the general
 * list when a run's outcome is lost or super admins act in a burst. Mails carry the agent, the tool and a
 * link to the console, never argument values, reasons or comments.
 *
 * <p>Mail is sent on its own thread, never the audit thread, so a slow mail server delays nothing else; a
 * mail that fails or finds the queue full is dropped and counted ({@link #dropped()}).
 */
public final class ApprovalMailer implements ApprovalListener, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ApprovalMailer.class.getName());
    private static final int QUEUE_CAPACITY = 500;

    private final MailSender sender;
    private final String from;
    private final List<String> everyone;
    private final Map<String, List<String>> byAgent;
    private final String consoleUrl;
    private final ThreadPoolExecutor mailThread;
    private final AtomicLong dropped = new AtomicLong();

    ApprovalMailer(MailSender sender, NotifyProperties properties) {
        this.sender = Objects.requireNonNull(sender, "sender");
        NotifyProperties.Email email = properties.email();
        if (email.from() == null || email.from().isBlank()) {
            throw new IllegalArgumentException("toolgate.approvals.notify.email.from is required to send approval mail");
        }
        if (email.to().isEmpty() && email.agents().isEmpty()) {
            throw new IllegalArgumentException("toolgate.approvals.notify.email needs recipients: set .to or .agents.<agent>");
        }
        this.from = email.from();
        this.everyone = List.copyOf(email.to());
        this.byAgent = Map.copyOf(email.agents());
        this.consoleUrl = properties.consoleUrl();
        this.mailThread = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                Thread.ofPlatform().name("toolgate-mail").daemon().factory());
    }

    @Override
    public void deferred(AuditEvent.Decided event) {
        if (event.applied() instanceof Decision.Defer defer) {
            mail(recipients(event.agentId()), event.agentId() + " asks to run " + event.tool(),
                    event.agentId() + " asks to run " + event.tool() + ". It waits for a decision.", defer.approvalId());
        }
    }

    @Override
    public void expiringSoon(AuditEvent.ApprovalExpiring event) {
        mail(recipients(event.agentId()), "Expires soon: " + event.agentId() + " asks to run " + event.tool(),
                "A request from " + event.agentId() + " to run " + event.tool() + " expires at " + event.expiresAt()
                        + " unless someone decides.", event.approvalId());
    }

    @Override
    public void recorded(AuditEvent.ApprovalRecorded event) {
        mail(recipients(event.agentId()), "Needs a second person: " + event.agentId() + " asks to run " + event.tool(),
                event.decidedBy() + " approved " + event.agentId() + "'s request to run " + event.tool()
                        + " as a super admin. It runs only once someone else approves it too.", event.approvalId());
    }

    @Override
    public void finished(AuditEvent.ApprovalDecided event) {
        if (event.status() == ApprovalStatus.UNKNOWN) {
            mail(recipients(event.agentId()), "Outcome unknown: " + event.tool() + " for " + event.agentId(),
                    "Nobody knows whether " + event.tool() + " ran for " + event.agentId() + ": its run lost contact."
                            + " Check the real system, then resolve it in the console.", event.approvalId());
        }
    }

    @Override
    public void superAdminBurst(AuditEvent.SuperAdminBurst event) {
        mail(everyone, "Super admin burst: " + event.decisions() + " decisions within " + event.window(),
                event.decisions() + " super admin decisions within " + event.window() + ", the last by " + event.lastBy()
                        + ". Check that they were expected; a stolen super admin account tends to act fast.", null);
    }

    /**
     * Mails that could not be sent: the mail server failed, or too many waited.
     *
     * @return how many since the application started
     */
    public long dropped() {
        return dropped.get();
    }

    /** Sends what is queued, waiting a few seconds at most. */
    @Override
    public void close() {
        mailThread.shutdown();
        try {
            if (!mailThread.awaitTermination(5, TimeUnit.SECONDS)) {
                dropped.addAndGet(mailThread.shutdownNow().size());
            }
        } catch (InterruptedException interrupted) {
            mailThread.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private List<String> recipients(String agentId) {
        var all = new LinkedHashSet<String>(everyone);
        all.addAll(byAgent.getOrDefault(agentId, List.of()));
        return List.copyOf(all);
    }

    private void mail(List<String> to, String subject, String text, String approvalId) {
        if (to.isEmpty()) {
            return;
        }
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to.toArray(String[]::new));
        message.setSubject("[ToolGate] " + subject);
        message.setText(text + "\n\n" + (approvalId == null ? "" : link(approvalId))
                + "\n\nSent by ToolGate. The request's details are in the console, where they are masked as the policy says.");
        try {
            mailThread.execute(() -> {
                try {
                    sender.send(message);
                } catch (MailException failure) {
                    dropped.incrementAndGet();
                    LOG.log(System.Logger.Level.WARNING, "could not send approval mail \"{0}\": {1}", subject, failure.getMessage());
                }
            });
        } catch (RejectedExecutionException full) {
            dropped.incrementAndGet();
        }
    }

    private String link(String approvalId) {
        if (consoleUrl == null) {
            return "Approval ID: " + approvalId;
        }
        return "Review it: " + consoleUrl + (consoleUrl.contains("?") ? "&" : "?") + "id="
                + URLEncoder.encode(approvalId, StandardCharsets.UTF_8);
    }
}
