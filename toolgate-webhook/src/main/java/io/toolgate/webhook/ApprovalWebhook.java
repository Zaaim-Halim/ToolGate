package io.toolgate.webhook;

import io.toolgate.api.Decision;
import io.toolgate.api.Tenant;
import io.toolgate.spi.ApprovalListener;
import io.toolgate.spi.AuditEvent;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Posts what happens to deferred calls to a URL: a call waits, expires soon, was decided or ran, or super
 * admins act in a burst. Add it to the gate as an approval listener:
 *
 * <pre>{@code
 * var webhook = ApprovalWebhook.signed(URI.create("https://ops.example.com/hooks/toolgate"), secret)
 *         .consoleUrl("https://refunds.example.com/toolgate/approvals/")
 *         .build();
 * ToolGate.builder(policy).approvalListener(webhook)
 * }</pre>
 *
 * <p><b>Formats.</b> {@link #signed} posts JSON signed with HMAC-SHA256 ({@link WebhookSignature}), for the
 * application's own receivers, relays and incident tools. {@link #slack} posts a readable message to a Slack
 * incoming webhook, whose URL is itself the secret, so it is not signed.
 *
 * <p><b>What is sent.</b> IDs, the agent, the tool, the status, who decided, and a link to the console:
 * never argument values, reasons or comments, which can quote them. A reviewer follows the link to see the
 * call, where the console masks what the policy says to mask.
 *
 * <p><b>Delivery.</b> Off the audit thread: events wait in a bounded queue and one background thread posts
 * them in order, retrying timeouts, 408, 429 and 5xx answers with a growing delay. A delivery that still
 * fails, or finds the queue full, is dropped and counted ({@link #dropped()}), never blocking the gate.
 * Redirects are not followed. {@link #close()} sends what is queued, waiting a few seconds at most.
 */
public final class ApprovalWebhook implements ApprovalListener, AutoCloseable {

    /** What the webhook can report; all of them by default. */
    public enum Event {
        /** A call was deferred and waits for review. */
        DEFERRED,
        /** A waiting call expires soon. */
        EXPIRING,
        /** A call was released, refused, rejected, cancelled or expired. */
        DECIDED,
        /** A super admin's approval was recorded and the call waits for a second person. */
        RECORDED,
        /** An approved call ran, failed, or has an unknown outcome. */
        FINISHED,
        /** Super admins made more decisions than normal in a short time. */
        SUPER_ADMIN_BURST
    }

    /** How a delivery is written. */
    public enum Format {
        /** JSON, signed in the {@value WebhookSignature#HEADER} header. */
        SIGNED_JSON,
        /** A Slack incoming-webhook message. */
        SLACK
    }

    private static final System.Logger LOG = System.getLogger(ApprovalWebhook.class.getName());
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(5);
    private static final Duration POLL = Duration.ofMillis(200);

    private final URI url;
    private final Format format;
    private final byte[] secret;
    private final String consoleUrl;
    private final Set<Event> events;
    private final HttpClient client;
    private final Duration timeout;
    private final int maxAttempts;
    private final Duration retryDelay;
    private final Clock clock;
    private final BlockingQueue<Delivery> queue;
    private final Thread sender;
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean closed;
    /** When a closing webhook stops retrying; set by {@link #close()}. */
    private volatile Instant drainDeadline = Instant.MAX;

    private ApprovalWebhook(Builder builder) {
        this.url = builder.url;
        this.format = builder.format;
        this.secret = builder.secret;
        this.consoleUrl = builder.consoleUrl;
        this.events = Set.copyOf(builder.events);
        this.client = builder.client != null ? builder.client
                : HttpClient.newBuilder().connectTimeout(builder.timeout).followRedirects(HttpClient.Redirect.NEVER).build();
        this.timeout = builder.timeout;
        this.maxAttempts = builder.maxAttempts;
        this.retryDelay = builder.retryDelay;
        this.clock = builder.clock;
        this.queue = new ArrayBlockingQueue<>(builder.capacity);
        this.sender = Thread.ofPlatform().name("toolgate-webhook").daemon().start(this::send);
    }

    /**
     * A webhook posting signed JSON.
     *
     * @param url    where to post; HTTPS, or HTTP to this machine for tests
     * @param secret the secret shared with the receiver, at least {@value WebhookSignature#MIN_SECRET_BYTES} random
     *               bytes
     * @return a builder
     * @throws IllegalArgumentException if the URL is not HTTPS or the secret is too short
     */
    public static Builder signed(URI url, byte[] secret) {
        return new Builder(url, Format.SIGNED_JSON, WebhookSignature.requireSecret(secret));
    }

    /**
     * A webhook posting to a Slack incoming webhook.
     *
     * @param url the incoming webhook's URL, which is its secret: keep it out of source code
     * @return a builder
     * @throws IllegalArgumentException if the URL is not HTTPS
     */
    public static Builder slack(URI url) {
        return new Builder(url, Format.SLACK, null);
    }

    @Override
    public void deferred(AuditEvent.Decided event) {
        if (event.applied() instanceof Decision.Defer defer) {
            String tenant = switch (event.principal().tenant()) {
                case Tenant.Scoped(String id) -> id;
                case Tenant.Unscoped unscoped -> null;
            };
            offer(Event.DEFERRED, new Notice("approval.deferred", event.time(), defer.approvalId(), event.agentId(),
                    event.tool().toString(), tenant, "pending", null, null, null, null));
        }
    }

    @Override
    public void expiringSoon(AuditEvent.ApprovalExpiring event) {
        offer(Event.EXPIRING, new Notice("approval.expiring", event.time(), event.approvalId(), event.agentId(),
                event.tool().toString(), null, "pending", null, null, event.expiresAt(), null));
    }

    @Override
    public void decided(AuditEvent.ApprovalDecided event) {
        offer(Event.DECIDED, decision("approval.decided", event));
    }

    @Override
    public void finished(AuditEvent.ApprovalDecided event) {
        offer(Event.FINISHED, decision("approval.finished", event));
    }

    @Override
    public void recorded(AuditEvent.ApprovalRecorded event) {
        offer(Event.RECORDED, new Notice("approval.recorded", event.time(), event.approvalId(), event.agentId(),
                event.tool().toString(), null, "pending", event.decidedBy(), lower(event.access().name()), null, null));
    }

    @Override
    public void superAdminBurst(AuditEvent.SuperAdminBurst event) {
        offer(Event.SUPER_ADMIN_BURST, new Notice("superadmin.burst", event.time(), null, null, null, null, null,
                event.lastBy(), "super_admin", null, new Burst(event.decisions(), event.window())));
    }

    /**
     * Deliveries that reached the receiver, with a 2xx answer.
     *
     * @return how many since the webhook was built
     */
    public long delivered() {
        return delivered.get();
    }

    /**
     * Deliveries given up: the queue was full, the receiver kept failing, refused one, or the webhook was
     * closed. Worth a metric: reviewers may not have heard of a waiting call.
     *
     * @return how many since the webhook was built
     */
    public long dropped() {
        return dropped.get();
    }

    /** Sends what is queued, retries included, for a few seconds at most, then stops; later events are dropped. */
    @Override
    public void close() {
        drainDeadline = Instant.now().plus(CLOSE_WAIT);
        closed = true;
        try {
            sender.join(CLOSE_WAIT.toMillis() + timeout.toMillis());
            if (sender.isAlive()) {
                // A receiver slower than the deadline: stop waiting on it.
                sender.interrupt();
                sender.join(timeout.toMillis());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        dropped.addAndGet(queue.size());
        queue.clear();
    }

    private Notice decision(String type, AuditEvent.ApprovalDecided event) {
        return new Notice(type, event.time(), event.approvalId(), event.agentId(), event.tool().toString(), null,
                lower(event.status().name()), event.decidedBy(), event.access() == null ? null : lower(event.access().name()),
                null, null);
    }

    private void offer(Event kind, Notice notice) {
        if (!events.contains(kind)) {
            return;
        }
        if (closed || !queue.offer(new Delivery(UUID.randomUUID().toString(), body(notice)))) {
            dropped.incrementAndGet();
        }
    }

    private String body(Notice notice) {
        String link = notice.approvalId() == null || consoleUrl == null ? null
                : consoleUrl + (consoleUrl.contains("?") ? "&" : "?") + "id="
                        + URLEncoder.encode(notice.approvalId(), StandardCharsets.UTF_8);
        return switch (format) {
            case SIGNED_JSON -> Messages.json(notice, link);
            case SLACK -> Messages.slack(notice, link);
        };
    }

    /** The sender loop: in order, each delivery to success or its last attempt; after close, until the queue is empty. */
    private void send() {
        while (true) {
            Delivery next;
            try {
                // Polling rather than waiting forever, so a closed webhook notices without being interrupted.
                next = queue.poll(POLL.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                return;
            }
            if (next == null) {
                if (closed) {
                    return;
                }
            } else if (draining()) {
                dropped.incrementAndGet();
            } else {
                deliver(next);
            }
        }
    }

    /** Whether close() has run out of patience. */
    private boolean draining() {
        return closed && Instant.now().isAfter(drainDeadline);
    }

    private void deliver(Delivery delivery) {
        Duration delay = retryDelay;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                int status = post(delivery);
                if (status / 100 == 2) {
                    delivered.incrementAndGet();
                    return;
                }
                if (!retryable(status)) {
                    LOG.log(System.Logger.Level.WARNING, "webhook {0} refused delivery {1} with {2}; not retried",
                            url.getHost(), delivery.id(), status);
                    break;
                }
            } catch (IOException failure) {
                if (attempt == maxAttempts) {
                    LOG.log(System.Logger.Level.WARNING, "webhook {0} unreachable for delivery {1}: {2}",
                            url.getHost(), delivery.id(), failure.toString());
                }
            } catch (InterruptedException interrupted) {
                // Only close() interrupts, once its deadline has passed.
                break;
            }
            if (attempt == maxAttempts || draining() || !pause(delay)) {
                break;
            }
            delay = delay.multipliedBy(2);
        }
        dropped.incrementAndGet();
    }

    private int post(Delivery delivery) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(url)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("User-Agent", "ToolGate-Webhook")
                .header(WebhookSignature.DELIVERY_HEADER, delivery.id())
                .POST(HttpRequest.BodyPublishers.ofString(delivery.body(), StandardCharsets.UTF_8));
        if (format == Format.SIGNED_JSON) {
            // Signed afresh on each attempt, so a retry is as recent as the receiver's tolerance expects.
            request.header(WebhookSignature.HEADER, WebhookSignature.header(secret, clock.instant(), delivery.body()));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static boolean retryable(int status) {
        return status == 408 || status == 429 || status / 100 == 5;
    }

    /** Waits before a retry; false if close() gave up waiting meanwhile. */
    private boolean pause(Duration delay) {
        try {
            TimeUnit.MILLISECONDS.sleep(delay.toMillis());
            return true;
        } catch (InterruptedException interrupted) {
            return false;
        }
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private record Delivery(String id, String body) {}

    /** What a delivery says, before formatting; null where it does not apply. */
    record Notice(String type, Instant time, String approvalId, String agent, String tool, String tenant, String status,
                  String by, String access, Instant expiresAt, Burst burst) {}

    /** A super admin burst's figures. */
    record Burst(int decisions, Duration window) {}

    /** Builds an {@link ApprovalWebhook}. */
    public static final class Builder {

        private final URI url;
        private final Format format;
        private final byte[] secret;
        private String consoleUrl;
        private Set<Event> events = EnumSet.allOf(Event.class);
        private HttpClient client;
        private Duration timeout = Duration.ofSeconds(10);
        private int maxAttempts = 5;
        private Duration retryDelay = Duration.ofSeconds(1);
        private int capacity = 1000;
        private Clock clock = Clock.systemUTC();

        private Builder(URI url, Format format, byte[] secret) {
            this.url = requireSafeUrl(url);
            this.format = format;
            this.secret = secret;
        }

        /**
         * The console's address, so each message links to the call, such as
         * {@code https://refunds.example.com/toolgate/approvals/}. Without it, messages carry the approval ID only.
         *
         * @param consoleUrl the console's address
         * @return this builder
         */
        public Builder consoleUrl(String consoleUrl) {
            this.consoleUrl = Objects.requireNonNull(consoleUrl, "consoleUrl");
            return this;
        }

        /**
         * Only these events, such as {@link Event#DEFERRED} and {@link Event#EXPIRING} for a reviewers' channel.
         *
         * @param only the events to send
         * @return this builder
         */
        public Builder events(Event... only) {
            if (only.length == 0) {
                throw new IllegalArgumentException("name at least one event");
            }
            this.events = EnumSet.of(only[0], only);
            return this;
        }

        /**
         * How long to wait for the receiver to connect and answer; 10 seconds by default.
         *
         * @param timeout the timeout
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = positive(timeout, "timeout");
            return this;
        }

        /**
         * How many times to try a delivery, and the delay before the first retry, doubled after each; 5 attempts
         * from 1 second by default.
         *
         * @param attempts   at least 1
         * @param firstDelay the delay before the first retry
         * @return this builder
         */
        public Builder retries(int attempts, Duration firstDelay) {
            if (attempts < 1) {
                throw new IllegalArgumentException("attempts must be at least 1, was " + attempts);
            }
            this.maxAttempts = attempts;
            this.retryDelay = Objects.requireNonNull(firstDelay, "firstDelay");
            return this;
        }

        /**
         * How many deliveries may wait; more are dropped. 1000 by default.
         *
         * @param capacity at least 1
         * @return this builder
         */
        public Builder capacity(int capacity) {
            if (capacity < 1) {
                throw new IllegalArgumentException("capacity must be at least 1, was " + capacity);
            }
            this.capacity = capacity;
            return this;
        }

        /**
         * The HTTP client to post with, such as one with the application's proxy or TLS settings. It should not
         * follow redirects.
         *
         * @param client the client
         * @return this builder
         */
        public Builder httpClient(HttpClient client) {
            this.client = Objects.requireNonNull(client, "client");
            return this;
        }

        /**
         * The clock that timestamps signatures; the system clock by default.
         *
         * @param clock the clock
         * @return this builder
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * Builds the webhook and starts its sender thread.
         *
         * @return the webhook, an approval listener to add to the gate; close it when the application stops
         */
        public ApprovalWebhook build() {
            return new ApprovalWebhook(this);
        }

        private static URI requireSafeUrl(URI url) {
            Objects.requireNonNull(url, "url");
            String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
            String host = url.getHost() == null ? "" : url.getHost();
            boolean local = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
            // Notifications name agents, tools and reviewers: plain HTTP only to this machine, for tests.
            if (!scheme.equals("https") && !(scheme.equals("http") && local)) {
                throw new IllegalArgumentException("a webhook URL must use HTTPS, was " + url);
            }
            return url;
        }

        private static Duration positive(Duration duration, String name) {
            if (duration.isNegative() || duration.isZero()) {
                throw new IllegalArgumentException(name + " must be positive, was " + duration);
            }
            return duration;
        }
    }
}
