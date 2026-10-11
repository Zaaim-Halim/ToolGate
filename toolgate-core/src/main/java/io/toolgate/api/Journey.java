package io.toolgate.api;

import static io.toolgate.support.Checks.requireNonBlank;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The story of one deferred call, as a graph a review screen can draw: every tool call the agent made in
 * its session with ToolGate's decision, where private data and untrusted content came from and which calls
 * they reached, what people decided, and how the call ran. It carries no argument values.
 *
 * @param approvalId the deferred call
 * @param sessionId  its session
 * @param agentId    the agent
 * @param steps      the steps, in the order they happened
 * @param flows      where taint went, from the call that brought it in to the calls it shaped
 * @param complete   whether the session's whole event log was available; when not, only the deferred
 *                   call and what followed it are shown
 */
public record Journey(String approvalId, String sessionId, String agentId, List<Step> steps, List<Flow> flows,
                      boolean complete) {

    /** Requires every component and copies the lists. */
    public Journey {
        requireNonBlank(approvalId, "approvalId");
        requireNonBlank(sessionId, "sessionId");
        requireNonBlank(agentId, "agentId");
        steps = List.copyOf(steps);
        flows = List.copyOf(flows);
    }

    /** What a step is. */
    public enum Kind {
        /** A tool call the agent asked for, with ToolGate's decision. */
        CALL,
        /** Application code removed a label from the session. */
        DECLASSIFIED,
        /** A person decided the deferred call: approved, rejected, withdrew or resolved it. */
        PERSON,
        /** How the deferred call ran once approved. */
        RUN
    }

    /**
     * One step of the story.
     *
     * @param id          unique within the journey; a call's ID for calls
     * @param kind        what it is
     * @param at          when
     * @param title       what to call it: the tool, the person, or how it ran
     * @param outcome     the decision or result, such as {@code allow}, {@code defer}, {@code approve} or
     *                    {@code executed}
     * @param ruleId      the rule behind a call's decision; null otherwise
     * @param detail      more to say: the held call's reason as its review view shows it, the safe tool of a
     *                    downgrade, a person's comment, an executor's name; null when nothing
     * @param labelsAdded the labels a call's result brought into the session, or a declassification removed
     * @param subject     whether this is the deferred call the journey is about
     */
    public record Step(String id, Kind kind, Instant at, String title, String outcome, String ruleId, String detail,
                       Set<Label> labelsAdded, boolean subject) {

        /** Requires the identifying components and copies the labels. */
        public Step {
            requireNonBlank(id, "id");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(at, "at");
            requireNonBlank(title, "title");
            requireNonBlank(outcome, "outcome");
            labelsAdded = Set.copyOf(labelsAdded);
        }
    }

    /**
     * Taint going from one call to another.
     *
     * @param from  the call whose result brought the label in
     * @param to    a later call the label shaped: one a taint rule decided, or the deferred call
     * @param label the label
     */
    public record Flow(String from, String to, Label label) {

        /** Requires every component. */
        public Flow {
            requireNonBlank(from, "from");
            requireNonBlank(to, "to");
            Objects.requireNonNull(label, "label");
        }
    }
}
