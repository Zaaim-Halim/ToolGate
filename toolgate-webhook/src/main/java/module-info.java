/**
 * Approval notifications over HTTP: {@link io.toolgate.webhook.ApprovalWebhook} posts what happens to deferred
 * calls as signed JSON or Slack messages, and {@link io.toolgate.webhook.WebhookSignature} lets a receiver
 * check them. Uses only the JDK besides ToolGate's core.
 */
module io.toolgate.webhook {
    requires transitive io.toolgate.core;
    requires transitive java.net.http;

    exports io.toolgate.webhook;
}
