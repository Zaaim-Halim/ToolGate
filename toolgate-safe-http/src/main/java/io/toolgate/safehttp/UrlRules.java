package io.toolgate.safehttp;

import io.toolgate.api.Destinations;
import io.toolgate.api.policy.ArgumentRule;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Optional;
import org.apache.hc.client5.http.classic.ExecChain;
import org.apache.hc.client5.http.classic.ExecChainHandler;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpException;

/**
 * Checks each request, the first and every redirect, against the URL rules. It sits in HttpClient's
 * execution chain after redirects and before the connection is made: request interceptors run only
 * once the client has resolved the host and connected, too late to refuse it.
 */
final class UrlRules implements ExecChainHandler {

    private final ArgumentRule[] rules;

    UrlRules(List<ArgumentRule> rules) {
        this.rules = rules.toArray(ArgumentRule[]::new);
    }

    @Override
    public ClassicHttpResponse execute(ClassicHttpRequest request, ExecChain.Scope scope, ExecChain chain)
            throws IOException, HttpException {
        String url;
        try {
            url = request.getUri().toString();
        } catch (URISyntaxException unreadable) {
            throw new RefusedDestinationException("SafeHttp refused an unreadable URL: " + unreadable.getMessage());
        }
        if (scope.route.getProxyHost() != null) {
            throw new RefusedDestinationException("SafeHttp refused " + url + ": it would go through a proxy");
        }
        Optional<String> problem = Destinations.urlProblem(url, rules);
        if (problem.isPresent()) {
            throw new RefusedDestinationException("SafeHttp refused " + url + ": " + problem.get());
        }
        return chain.proceed(request, scope);
    }
}
