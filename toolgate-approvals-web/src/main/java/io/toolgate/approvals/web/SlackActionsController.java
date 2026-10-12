package io.toolgate.approvals.web;

import io.toolgate.api.Approval;
import io.toolgate.api.ArgValue;
import io.toolgate.api.ArgValueJson;
import io.toolgate.api.ListValue;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
import io.toolgate.api.ReviewItem;
import io.toolgate.api.TextValue;
import io.toolgate.api.ToolGate;
import io.toolgate.api.policy.ToolGatePolicy;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where Slack sends a click on an approval message. Slack users are not signed in to the application, so the
 * request proves itself: its signature, checked over the raw body before anything else is read, then the
 * Slack app and workspace. The clicker is mapped to a reviewer by the application's
 * {@link ToolGateSlackReviewers}; ToolGate then approves exactly as from the console, bound to the digest of
 * the view the message showed. The call is released, not run here: the approval executor runs it, so Slack
 * gets its answer within its three seconds.
 */
@RestController
final class SlackActionsController {

    static final String PATH = "/toolgate/slack/actions";
    /** A click's payload is a few kilobytes; anything far larger is not from Slack. */
    static final int MAX_BODY = 64 * 1024;
    private static final System.Logger LOG = System.getLogger("toolgate.adapter");

    private final SlackProperties properties;
    private final Supplier<ToolGate> gate;
    private final Supplier<ToolGatePolicy> policy;
    private final ToolGateSlackReviewers reviewers;
    private final ToolGateRequesterDirectory requesters;
    private final SlackClient client;
    private final Clock clock;
    private final ExecutorService responder = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "toolgate-slack-response");
        thread.setDaemon(true);
        return thread;
    });

    SlackActionsController(SlackProperties properties, Supplier<ToolGate> gate, Supplier<ToolGatePolicy> policy,
                           ToolGateSlackReviewers reviewers, ToolGateRequesterDirectory requesters, SlackClient client,
                           Clock clock) {
        this.properties = properties;
        this.gate = gate;
        this.policy = policy;
        this.reviewers = reviewers;
        this.requesters = requesters;
        this.client = client;
        this.clock = clock;
    }

    @PostMapping(PATH)
    ResponseEntity<Void> act(HttpServletRequest request) throws IOException {
        // The raw bytes, read before anything parses the form: the signature covers exactly these.
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(MAX_BODY + 1);
        }
        if (body.length > MAX_BODY) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
        }
        if (!SlackSignature.valid(properties.signingSecret(), request.getHeader("X-Slack-Request-Timestamp"),
                request.getHeader("X-Slack-Signature"), body, clock.instant())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        ObjectValue payload;
        try {
            payload = payload(new String(body, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException unreadable) {
            return ResponseEntity.badRequest().build();
        }
        if (!"block_actions".equals(text(payload, "type"))) {
            return ResponseEntity.ok().build();
        }
        String team = text(object(payload, "team"), "id");
        ObjectValue user = object(payload, "user");
        String userTeam = text(user, "team_id");
        if (!properties.appId().equals(text(payload, "api_app_id")) || !properties.teamId().equals(team)
                || (userTeam != null && !properties.teamId().equals(userTeam))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Optional<ObjectValue> action = firstAction(payload);
        if (action.isEmpty() || !SlackMessages.APPROVE.equals(text(action.get(), "action_id"))) {
            // Opening the console sends a click too: nothing to do.
            return ResponseEntity.ok().build();
        }
        Optional<URI> responseUrl = responseUrl(text(payload, "response_url"));
        String reply = approve(text(user, "id"), text(action.get(), "value"));
        responseUrl.ifPresent(url -> responder.execute(() -> respond(url, reply)));
        return ResponseEntity.ok().build();
    }

    /** Approves, and says what happened; a reply starting with "!" is for the clicker only. */
    private String approve(String userId, String value) {
        String[] parts = value == null ? new String[0] : value.split(" ", 2);
        if (userId == null || parts.length != 2) {
            return "!That button is not one ToolGate posted.";
        }
        String approvalId = parts[0];
        Optional<Principal> mapped = reviewers.reviewer(properties.teamId(), userId);
        if (mapped.isEmpty()) {
            return "!Your Slack account is not linked to a reviewer in this application. Use the console.";
        }
        Principal reviewer = mapped.get();
        if (!Collections.disjoint(reviewer.roles(), policy.get().superAdmins())) {
            return "!Super admins decide in the console, where they give a justification.";
        }
        Optional<ReviewItem> item = gate.get().reviewItem(approvalId, reviewer);
        if (item.isEmpty() || !item.get().mayDecide()) {
            return "!This request is not one you may decide, or it no longer waits.";
        }
        Approval approval = gate.get().approve(approvalId, reviewer, requesters.current(item.get().requester()),
                "Approved in Slack by Slack user " + userId, parts[1]);
        return switch (approval) {
            case Approval.Released released -> "Approved by " + reviewer.id() + " in Slack. "
                    + item.get().agentId() + " runs " + item.get().tool() + " shortly.";
            case Approval.AwaitingConfirmation waiting -> "Approved by " + reviewer.id() + "; it waits for a second person.";
            case Approval.Refused refused -> "!Not approved: " + refused.reason();
            case Approval.NotPermitted notPermitted -> "!Not approved: " + notPermitted.reason();
            case Approval.AlreadyDecided decided -> "!It was already decided: " + decided.status() + ".";
            case Approval.Expired expired -> "This request expired before anyone decided.";
        };
    }

    private void respond(URI url, String reply) {
        boolean privately = reply.startsWith("!");
        try {
            client.post(url, SlackMessages.response(privately ? reply.substring(1) : reply, !privately));
        } catch (IOException failed) {
            LOG.log(System.Logger.Level.WARNING, "could not update a Slack approval message: {0}", failed.getMessage());
        }
    }

    /** The response URL, if it is an HTTPS URL on one of Slack's hosts; ToolGate posts nowhere else. */
    private Optional<URI> responseUrl(String text) {
        if (text == null) {
            return Optional.empty();
        }
        try {
            URI url = new URI(text);
            if ("https".equals(url.getScheme()) && url.getRawUserInfo() == null && url.getPort() == -1
                    && url.getHost() != null && properties.responseHosts().contains(url.getHost().toLowerCase(java.util.Locale.ROOT))) {
                return Optional.of(url);
            }
        } catch (URISyntaxException malformed) {
            // Falls through: no response.
        }
        LOG.log(System.Logger.Level.WARNING, "ignored a Slack response URL outside toolgate.approvals.slack.response-hosts");
        return Optional.empty();
    }

    private static ObjectValue payload(String form) {
        for (String pair : form.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals("payload")) {
                ArgValue json = ArgValueJson.read(URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
                if (json instanceof ObjectValue object) {
                    return object;
                }
            }
        }
        throw new IllegalArgumentException("no payload");
    }

    private static Optional<ObjectValue> firstAction(ObjectValue payload) {
        return payload.field("actions").filter(ListValue.class::isInstance).map(ListValue.class::cast)
                .flatMap(actions -> actions.elements().stream().findFirst())
                .filter(ObjectValue.class::isInstance).map(ObjectValue.class::cast);
    }

    private static ObjectValue object(ObjectValue parent, String name) {
        return parent.field(name).filter(ObjectValue.class::isInstance).map(ObjectValue.class::cast)
                .orElse(ObjectValue.of(java.util.Map.of()));
    }

    private static String text(ObjectValue parent, String name) {
        return parent.field(name).filter(TextValue.class::isInstance).map(value -> ((TextValue) value).value()).orElse(null);
    }
}
