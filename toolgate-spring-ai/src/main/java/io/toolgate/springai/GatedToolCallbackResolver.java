package io.toolgate.springai;

import java.util.function.Supplier;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;

/**
 * Gates the callbacks Spring AI finds by name, for applications that turn on resolution fallback:
 * Spring AI then runs tools a request never offered, and they must still reach ToolGate.
 */
final class GatedToolCallbackResolver implements ToolCallbackResolver {

    private final ToolCallbackResolver delegate;
    private final Supplier<Gating> gating;

    GatedToolCallbackResolver(ToolCallbackResolver delegate, Supplier<Gating> gating) {
        this.delegate = delegate;
        this.gating = gating;
    }

    ToolCallbackResolver delegate() {
        return delegate;
    }

    @Override
    public ToolCallback resolve(String toolName) {
        ToolCallback found = delegate.resolve(toolName);
        return found == null ? null : gating.get().wrap(found);
    }
}
