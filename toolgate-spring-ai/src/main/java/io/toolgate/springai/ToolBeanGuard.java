package io.toolgate.springai;

import io.toolgate.api.Decision;
import io.toolgate.api.Mode;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.Supplier;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;

/**
 * Refuses a {@code @Tool} method of an application bean that Spring AI runs without ToolGate, as a
 * {@code ChatClient} built by hand with {@code ChatClient.create(model)} does, or an application
 * {@code ToolAdvisor} ordered ahead of ToolGate's.
 *
 * <p>Every tool run that goes through ToolGate marks its thread for the length of the run; a tool
 * method that Spring AI's {@code MethodToolCallback} invokes on an unmarked thread was not checked.
 * The mark is a thread-local because Spring AI runs a method tool on the thread that called its
 * callback, and the mark never outlives that call. Application code calling a tool method directly
 * is not refused: only Spring AI's dispatch is.
 *
 * <p>A second line of defense: the chat model already refuses a request whose tools ToolGate did not
 * wrap ({@link GatedToolCallingManager}). The guard still catches an application {@code ToolAdvisor}
 * tied with ToolGate's, which the model cannot see. Covers tools that are beans, with a class that can be
 * proxied.
 */
final class ToolBeanGuard implements MethodInterceptor {

    private static final String METHOD_TOOL_CALLBACK = "org.springframework.ai.tool.method.MethodToolCallback";
    private static final System.Logger LOG = System.getLogger("toolgate.adapter");
    private static final ThreadLocal<Integer> GATED_RUNS = ThreadLocal.withInitial(() -> 0);

    private final Supplier<Gating> gating;
    private final String beanName;

    private ToolBeanGuard(Supplier<Gating> gating, String beanName) {
        this.gating = gating;
        this.beanName = beanName;
    }

    /** Runs a tool that ToolGate decided, marking the thread so its tool methods know they were checked. */
    static <T> T gatedRun(Supplier<T> run) {
        GATED_RUNS.set(GATED_RUNS.get() + 1);
        try {
            return run.get();
        } finally {
            int left = GATED_RUNS.get() - 1;
            if (left == 0) {
                GATED_RUNS.remove();
            } else {
                GATED_RUNS.set(left);
            }
        }
    }

    /**
     * A bean with {@code @Tool} methods, guarded; any other bean as it was.
     *
     * @return the bean, its proxy, or the same proxy with the guard added
     */
    static Object guard(Object bean, String beanName, Supplier<Gating> gating) {
        Class<?> type = AopUtils.getTargetClass(bean);
        if (MethodIntrospector.selectMethods(type, (MethodIntrospector.MetadataLookup<Tool>) method ->
                AnnotatedElementUtils.findMergedAnnotation(method, Tool.class)).isEmpty()) {
            return bean;
        }
        ToolBeanGuard guard = new ToolBeanGuard(gating, beanName);
        if (bean instanceof Advised advised && !advised.isFrozen()) {
            advised.addAdvice(0, guard);
            return bean;
        }
        if (Modifier.isFinal(type.getModifiers()) || AopUtils.isAopProxy(bean)) {
            LOG.log(System.Logger.Level.WARNING, "tool bean {0} ({1}) cannot be proxied, so ToolGate cannot refuse its"
                    + " tools when a ChatClient built by hand runs them; make the class non-final", beanName, type.getName());
            return bean;
        }
        ProxyFactory proxy = new ProxyFactory(bean);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(guard);
        return proxy.getProxy(type.getClassLoader());
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();
        if (GATED_RUNS.get() > 0 || AnnotatedElementUtils.findMergedAnnotation(method, Tool.class) == null
                || !dispatchedBySpringAi()) {
            return invocation.proceed();
        }
        Mode mode = gating.get().mode();
        String tool = beanName + "." + method.getName();
        return switch (mode) {
            case OFF -> invocation.proceed();
            case MONITOR -> {
                LOG.log(System.Logger.Level.WARNING, "monitor mode: tool {0} ran without ToolGate; build the ChatClient"
                        + " from Spring Boot''s ChatClient.Builder or ToolGateChatClients", tool);
                yield invocation.proceed();
            }
            case ENFORCE -> {
                LOG.log(System.Logger.Level.WARNING, "refused tool {0}: Spring AI ran it without ToolGate; build the"
                        + " ChatClient from Spring Boot''s ChatClient.Builder or ToolGateChatClients", tool);
                throw new UngatedToolException();
            }
        };
    }

    /**
     * Spring AI turns a tool's exception into text for the model, so the message is the generic denial;
     * the log has the details. The tool body has not run.
     */
    static final class UngatedToolException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        UngatedToolException() {
            super(Decision.Deny.MODEL_MESSAGE, null, false, false);
        }
    }

    private static boolean dispatchedBySpringAi() {
        return StackWalker.getInstance().walk(frames ->
                frames.anyMatch(frame -> frame.getClassName().equals(METHOD_TOOL_CALLBACK)));
    }
}
