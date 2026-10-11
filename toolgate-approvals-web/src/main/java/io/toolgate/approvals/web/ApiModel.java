package io.toolgate.approvals.web;

import io.toolgate.api.ApprovalDecision;
import io.toolgate.api.Journey;
import io.toolgate.api.Principal;
import io.toolgate.api.Provenance;
import io.toolgate.api.ReviewArgument;
import io.toolgate.api.ReviewItem;
import io.toolgate.api.Tenant;
import java.util.List;

/**
 * The JSON the REST API sends and receives. Plain strings and booleans only, so both web stacks write
 * the same bytes whatever their JSON settings.
 */
final class ApiModel {

    private ApiModel() {}

    /** A deferred call as a review screen shows it. Every string is data: render it as text. */
    record Item(String approvalId, String agent, String tool, String title, Party requester, String status,
                String expiresAt, String deferralRule, String deferralReason, List<Argument> arguments,
                List<Source> provenance, List<Decision> decisions, String access, boolean mayDecide, String digest) {

        static Item of(ReviewItem item) {
            return new Item(item.approvalId(), item.agentId(), item.tool().toString(), item.title(),
                    Party.of(item.requester()), item.status().name(), item.expiresAt().toString(), item.deferralRule(),
                    item.deferralReason(), item.arguments().stream().map(Argument::of).toList(),
                    item.provenance().stream().map(Source::of).toList(),
                    item.decisions().stream().map(Decision::of).toList(), item.access().name(), item.mayDecide(),
                    item.digest());
        }
    }

    /** A principal: who a call is for, or who is signed in. */
    record Party(String id, String kind, String tenant, List<String> roles) {

        static Party of(Principal principal) {
            String tenant = switch (principal.tenant()) {
                case Tenant.Scoped(String id) -> id;
                case Tenant.Unscoped unscoped -> null;
            };
            return new Party(principal.id(), principal.kind().name(), tenant, principal.roles().stream().sorted().toList());
        }
    }

    /** One argument, as the review view shows it. */
    record Argument(String name, String display, String visibility, List<String> warnings) {

        static Argument of(ReviewArgument argument) {
            return new Argument(argument.name(), argument.display(), argument.visibility().name(), argument.warnings());
        }
    }

    /** Where the session's taint came from. */
    record Source(String label, String tool, String callId) {

        static Source of(Provenance provenance) {
            return new Source(provenance.label().name(), provenance.tool().toString(), provenance.callId());
        }
    }

    /** One person's decision. */
    record Decision(String kind, String by, String access, String at, String comment) {

        static Decision of(ApprovalDecision decision) {
            return new Decision(decision.kind().name(), decision.by(), decision.access().name(), decision.at().toString(),
                    decision.comment());
        }
    }

    /** The story of one deferred call, as a graph. No argument values. */
    record JourneyView(String approvalId, String agent, boolean complete, List<StepView> steps, List<FlowView> flows) {

        static JourneyView of(Journey journey) {
            return new JourneyView(journey.approvalId(), journey.agentId(), journey.complete(),
                    journey.steps().stream().map(StepView::of).toList(),
                    journey.flows().stream().map(flow -> new FlowView(flow.from(), flow.to(), flow.label().name())).toList());
        }
    }

    /** One step: a tool call with ToolGate's decision, a person's decision, or how the call ran. */
    record StepView(String id, String kind, String at, String title, String outcome, String rule, String detail,
                    List<String> labelsAdded, boolean subject) {

        static StepView of(Journey.Step step) {
            return new StepView(step.id(), step.kind().name(), step.at().toString(), step.title(), step.outcome(),
                    step.ruleId(), step.detail(), step.labelsAdded().stream().map(Enum::name).sorted().toList(), step.subject());
        }
    }

    /** Taint going from the call that brought it in to a call it shaped. */
    record FlowView(String from, String to, String label) {}

    /** Who is signed in, for the console's header. */
    record Me(Party viewer, String application) {}

    /**
     * A decision a reviewer sends.
     *
     * @param approvalId the call
     * @param kind       {@code approve}, {@code reject}, {@code cancel} or {@code resolve}
     * @param comment    the justification or reason; required except for an approval by a reviewer
     * @param digest     the digest of the item the reviewer saw; required to approve
     * @param ran        for {@code resolve}: whether the tool did run
     */
    record DecisionRequest(String approvalId, String kind, String comment, String digest, Boolean ran) {}

    /**
     * What came of a decision.
     *
     * @param outcome {@code released}, {@code awaiting-confirmation}, {@code refused}, {@code not-permitted},
     *                {@code already-decided}, {@code expired}, {@code done} or {@code not-done}
     * @param reason  why, when ToolGate says
     * @param status  the call's status afterwards, when known
     */
    record DecisionResponse(String outcome, String reason, String status) {}

    /** A problem with the request itself. */
    record Problem(String error) {}
}
