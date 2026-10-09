package io.toolgate.springai;

import io.toolgate.api.Principal;
import java.util.Optional;

/**
 * Finds who a chat request is for, when its tool context does not say. ToolGate's advisor calls it
 * once per request, on the thread that makes the request, and puts the answer in the tool context, so
 * tools running on other threads never read thread-locals.
 *
 * <p>With Spring Security present, a default resolver maps the signed-in user: the authentication's
 * name as the ID, its authorities as roles, and no tenant. Define a bean of this type to map tenants,
 * or to read the principal from elsewhere.
 */
@FunctionalInterface
public interface ToolGatePrincipalResolver {

    /**
     * The principal of the current request.
     *
     * @return the principal, or empty if nobody is signed in; the call then ends unless the request
     *         passed one in {@link ToolGateContext}
     */
    Optional<Principal> resolve();
}
