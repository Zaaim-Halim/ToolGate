package io.toolgate.approvals.web;

import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code toolgate.approvals.slack.*}: approval messages with an Approve button, posted through
 * a Slack app's incoming webhooks, and the endpoint Slack calls when someone clicks it.
 *
 * @param signingSecret the Slack app's signing secret, which proves a click came through Slack; keep it out
 *                      of source code
 * @param appId         the Slack app's ID ({@code api_app_id}); clicks from any other app are refused
 * @param teamId        the Slack workspace's ID; clicks from any other workspace, such as a Slack Connect
 *                      partner's, are refused
 * @param responseHosts hosts a click's {@code response_url} may point at; ToolGate updates the message there
 * @param channels      where to post approval messages
 */
@ConfigurationProperties("toolgate.approvals.slack")
public record SlackProperties(String signingSecret, String appId, String teamId,
                              @DefaultValue({"hooks.slack.com", "hooks.slack-gov.com"}) List<String> responseHosts,
                              @DefaultValue List<Channel> channels) {

    /**
     * One channel, reached through one of the Slack app's incoming webhooks.
     *
     * @param webhookUrl    the incoming webhook's URL, which is itself a secret
     * @param agents        the agents whose calls go here; every agent if empty
     * @param tenant        the tenant whose reviewers read the channel; the message shows what a reviewer of
     *                      this tenant may see, and nothing for a call they may not
     * @param roles         the reviewer roles of the channel's members, such as {@code finance}
     * @param showArguments whether the message shows the call's arguments, as the review view masks them, with
     *                      an Approve button; off by default, so no argument value reaches Slack, and the
     *                      message only links to the console
     */
    public record Channel(URI webhookUrl, @DefaultValue List<String> agents, String tenant,
                          @DefaultValue List<String> roles, boolean showArguments) {}
}
