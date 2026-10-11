package io.toolgate.springai;

import java.util.function.Supplier;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.ClassUtils;

/**
 * Runs an approved call without the signed-in user's identity. A reviewer who approves from a web request
 * would otherwise lend the tool their own authorities, so a {@code @PreAuthorize} on the tool, or code that
 * reads the current user, would act as the reviewer; and the same call run by the background executor
 * would behave differently. The call's own principal is in its tool context.
 */
final class WithoutSignedInUser {

    private static final boolean SPRING_SECURITY = ClassUtils.isPresent(
            "org.springframework.security.core.context.SecurityContextHolder", WithoutSignedInUser.class.getClassLoader());

    private WithoutSignedInUser() {}

    static <T> T call(Supplier<T> work) {
        return SPRING_SECURITY ? Cleared.call(work) : work.get();
    }

    /** Loaded only when Spring Security is present. */
    private static final class Cleared {

        static <T> T call(Supplier<T> work) {
            SecurityContext saved = SecurityContextHolder.getContext();
            SecurityContextHolder.clearContext();
            try {
                return work.get();
            } finally {
                SecurityContextHolder.setContext(saved);
            }
        }
    }
}
