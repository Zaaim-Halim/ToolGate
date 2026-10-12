package io.toolgate.approvals.web;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Posts JSON to Slack: incoming webhooks and response URLs. Over HTTPS only, never following redirects. */
interface SlackClient {

    /**
     * Posts a message.
     *
     * @param url  an HTTPS Slack URL
     * @param json the message
     * @throws IOException if Slack cannot be reached or answers with an error
     */
    void post(URI url, String json) throws IOException;

    /** The JDK's HTTP client, with a short timeout. */
    static SlackClient http() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return (url, json) -> {
            if (!"https".equals(url.getScheme())) {
                throw new IOException("Slack URLs must be HTTPS");
            }
            HttpRequest request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
            try {
                int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status / 100 != 2) {
                    throw new IOException("Slack answered " + status);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", interrupted);
            }
        };
    }
}
