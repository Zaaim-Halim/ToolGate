package io.toolgate.test;

import io.toolgate.api.policy.ToolGatePolicy;
import java.lang.reflect.Constructor;
import java.util.function.Supplier;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/** Resolves {@link ScriptedSession} parameters for classes annotated with {@link ToolGateTest}. */
public final class ToolGateExtension implements ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(ToolGateExtension.class);

    /** Creates the extension; JUnit does this through {@link ToolGateTest}. */
    public ToolGateExtension() {}

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        return parameter.getParameter().getType() == ScriptedSession.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        ToolGateTest settings = context.getRequiredTestClass().getAnnotation(ToolGateTest.class);
        if (settings == null) {
            throw new ParameterResolutionException("annotate " + context.getRequiredTestClass().getName()
                    + " with @ToolGateTest to get a ScriptedSession");
        }
        ScriptedSession session = ScriptedSession.of(policy(settings.policy()), settings.agent(),
                Principals.parse(settings.principal()));
        // The store closes AutoCloseable values when the test ends, which stops the gate's audit thread.
        context.getStore(NAMESPACE).put(context.getUniqueId(), session);
        return session;
    }

    private static ToolGatePolicy policy(Class<? extends Supplier<ToolGatePolicy>> supplier) {
        try {
            Constructor<? extends Supplier<ToolGatePolicy>> constructor = supplier.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance().get();
        } catch (ReflectiveOperationException | RuntimeException unusable) {
            throw new ParameterResolutionException("cannot get the policy from " + supplier.getName()
                    + ": it needs a no-argument constructor, and its get() must build the policy", unusable);
        }
    }
}
