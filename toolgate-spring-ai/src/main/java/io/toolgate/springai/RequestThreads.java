package io.toolgate.springai;

import org.springframework.util.ClassUtils;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Whether the current thread is serving a Spring MVC request. A call made while serving a request
 * whose user cannot be identified must never fall back to a service identity; one made by a job may.
 * WebFlux requests leave no trace here, so WebFlux applications declare their agents' principals.
 */
final class RequestThreads {

    private static final boolean SPRING_WEB = ClassUtils.isPresent(
            "org.springframework.web.context.request.RequestContextHolder", RequestThreads.class.getClassLoader());

    private RequestThreads() {}

    static boolean servingRequest() {
        return SPRING_WEB && Web.servingRequest();
    }

    /** Loaded only when Spring Web is present. */
    private static final class Web {

        static boolean servingRequest() {
            return RequestContextHolder.getRequestAttributes() != null;
        }
    }
}
