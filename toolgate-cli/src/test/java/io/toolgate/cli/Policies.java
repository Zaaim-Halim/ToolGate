package io.toolgate.cli;

import static io.toolgate.api.Effect.REVERSIBLE;
import static io.toolgate.api.Label.EXTERNAL_SINK;
import static io.toolgate.api.policy.ArgumentRules.hosts;

import io.toolgate.api.ToolDefinition;
import io.toolgate.api.ToolId;
import io.toolgate.api.policy.ArgumentRule;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.spi.ToolCatalog;
import java.util.function.Supplier;

/** Policies and a catalog as an application would ship them for the CLI: classes that supply them. */
final class Policies {

    private Policies() {}

    static ToolGatePolicy emails(ArgumentRule... toRules) {
        return ToolGatePolicy.builder()
                .tool("sendEmail", t -> t.labels(EXTERNAL_SINK).effect(REVERSIBLE))
                .agent("support-chat", a -> {
                    a.allow("sendEmail");
                    if (toRules.length > 0) {
                        a.rule("sendEmail.to", toRules);
                    }
                })
                .build();
    }

    /** Last week's policy: any recipient. */
    public static final class Current implements Supplier<ToolGatePolicy> {
        @Override
        public ToolGatePolicy get() {
            return emails();
        }
    }

    /** The proposal: recipients at mycompany.com only. */
    public static final class Strict implements Supplier<ToolGatePolicy> {
        @Override
        public ToolGatePolicy get() {
            return emails(io.toolgate.api.policy.ArgumentRules.validated(new Domains("mycompany.com")));
        }
    }

    /** The proposal with the supplier domains added. */
    public static final class WithSuppliers implements Supplier<ToolGatePolicy> {
        @Override
        public ToolGatePolicy get() {
            return emails(io.toolgate.api.policy.ArgumentRules.validated(
                    new Domains("mycompany.com", "supplier-a.example", "supplier-b.example")));
        }
    }

    /** Does not build: a URL rule on an email argument the catalog never declares is fine, a mixed family is not. */
    public static final class Broken implements Supplier<ToolGatePolicy> {
        @Override
        public ToolGatePolicy get() {
            return emails(hosts("x.example"), io.toolgate.api.policy.ArgumentRules.under("/tmp"));
        }
    }

    /** Not a policy supplier. */
    public static final class NotASupplier {
    }

    /** The tool definitions the rules are checked against. */
    public static final class Catalog implements Supplier<ToolCatalog> {
        @Override
        public ToolCatalog get() {
            return ToolCatalog.inMemory().put(ToolDefinition.of(ToolId.local("sendEmail"), "Send an email",
                    "{\"type\":\"object\",\"properties\":{\"to\":{\"type\":\"string\"},\"body\":{\"type\":\"string\"}}}"));
        }
    }

    /** The key an encrypted event log was written with. */
    public static final class Codec implements Supplier<io.toolgate.spi.ValueCodec> {
        static final byte[] KEY = new byte[32];

        @Override
        public io.toolgate.spi.ValueCodec get() {
            return io.toolgate.spi.ValueCodec.aesGcm("k1", KEY);
        }
    }

    /** A minimal domain check, so the CLI's tests need no email module. */
    private record Domains(java.util.List<String> allowed) implements io.toolgate.spi.ArgumentValidator {
        Domains(String... allowed) {
            this(java.util.List.of(allowed));
        }

        @Override
        public String kind() {
            return "domains";
        }

        @Override
        public String fingerprint() {
            return String.join(",", allowed);
        }

        @Override
        public java.util.Optional<String> problem(io.toolgate.api.ArgValue value) {
            return value instanceof io.toolgate.api.TextValue(String to) && allowed.contains(to.substring(to.indexOf('@') + 1))
                    ? java.util.Optional.empty()
                    : java.util.Optional.of(value + " is not at an allowed domain");
        }
    }
}
