package io.toolgate.benchmarks;

import static io.toolgate.api.policy.ArgumentRules.hosts;
import static io.toolgate.api.policy.ArgumentRules.schemes;
import static io.toolgate.api.policy.ArgumentRules.under;

import io.toolgate.api.ArgValue;
import io.toolgate.api.Decision;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolGate;
import io.toolgate.api.ToolId;
import io.toolgate.api.policy.ToolGatePolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Time per {@code ToolGate.check}, as a distribution, against the design target of under 50
 * microseconds at the 99th percentile with the in-memory session store. Audit is on, with a sink
 * that discards events, so the cost of queueing them is included and the cost of a log backend is not.
 *
 * <p>Calls rotate over many sessions with no budget, so session state stays small, as in a service
 * with many concurrent conversations.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class CheckBenchmark {

    private static final int SESSIONS = 1_024;
    private static final Principal PRINCIPAL = Principal.user("maria", Tenant.of("acme"), "support");

    private ToolGate gate;
    private Path root;
    private ToolCall[] allowListCalls;
    private ToolCall[] urlCalls;
    private ToolCall[] deniedUrlCalls;
    private ToolCall[] pathCalls;
    private int next;

    @Setup(Level.Trial)
    public void setUp() throws IOException {
        root = Files.createTempDirectory("toolgate-bench");
        Files.writeString(root.resolve("q3.csv"), "q3");
        ToolGatePolicy policy = ToolGatePolicy.builder()
                .agent("support-chat", a -> a
                        .allow("getCustomer", "fetch", "readFile")
                        .rule("fetch.url", hosts("docs.example", "*.vendor.example"), schemes("https"))
                        .rule("readFile.path", under(root.toString())))
                .build();
        gate = ToolGate.builder(policy).auditSink(event -> { }).build();
        allowListCalls = calls("getCustomer", "id", "C-5512");
        urlCalls = calls("fetch", "url", "https://status.vendor.example/api/v2/status");
        deniedUrlCalls = calls("fetch", "url", "http://169.254.169.254/latest/meta-data/");
        pathCalls = calls("readFile", "path", root.resolve("q3.csv").toString());
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        gate.close();
        Files.deleteIfExists(root.resolve("q3.csv"));
        Files.deleteIfExists(root);
    }

    /** The allow-list and budgets only. */
    @Benchmark
    public Decision allowList() {
        return gate.check(allowListCalls[nextIndex()]);
    }

    /** A URL rule that passes: parse, normalize, match host patterns. */
    @Benchmark
    public Decision urlAllowed() {
        return gate.check(urlCalls[nextIndex()]);
    }

    /** A URL rule that denies the cloud metadata address. */
    @Benchmark
    public Decision urlDenied() {
        return gate.check(deniedUrlCalls[nextIndex()]);
    }

    /** A path rule: two real-path lookups and a link-count read, so this one includes file metadata I/O. */
    @Benchmark
    public Decision pathAllowed() {
        return gate.check(pathCalls[nextIndex()]);
    }

    private int nextIndex() {
        next = (next + 1) % SESSIONS;
        return next;
    }

    private static ToolCall[] calls(String tool, String argument, String value) {
        var calls = new ToolCall[SESSIONS];
        for (int index = 0; index < SESSIONS; index++) {
            calls[index] = new ToolCall("c" + index, PRINCIPAL, "support-chat", "session-" + index,
                    ToolId.local(tool), ObjectValue.of(Map.of(argument, ArgValue.text(value))), Instant.EPOCH);
        }
        return calls;
    }
}
