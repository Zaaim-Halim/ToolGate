package io.toolgate.approvals.web;

import io.toolgate.api.Principal;
import java.util.Optional;

/**
 * Who a Slack user is, as a reviewer: the application's own user, with their tenant and roles. ToolGate never
 * guesses this from a Slack name or email, and posts no Approve button unless the application declares one.
 *
 * <pre>{@code
 * @Bean
 * ToolGateSlackReviewers slackReviewers(UserDirectory users) {
 *     return (teamId, userId) -> users.findBySlackId(userId)
 *             .map(user -> Principal.user(user.id(), Tenant.of(user.tenant()), user.roles().toArray(String[]::new)));
 * }
 * }</pre>
 */
@FunctionalInterface
public interface ToolGateSlackReviewers {

    /**
     * The reviewer a Slack user is.
     *
     * @param teamId the Slack workspace, already checked against {@code toolgate.approvals.slack.team-id}
     * @param userId the Slack user ID, such as {@code U024BE7LH}
     * @return the reviewer, or empty if the Slack user is not one; then the click is refused
     */
    Optional<Principal> reviewer(String teamId, String userId);
}
