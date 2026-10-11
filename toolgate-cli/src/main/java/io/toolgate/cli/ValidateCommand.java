package io.toolgate.cli;

import io.toolgate.api.policy.ToolGatePolicy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

/** Builds a policy, which validates it, and prints its hash, or why it does not build. */
@Command(name = "validate", mixinStandardHelpOptions = true,
        description = "Build a policy and print its hash, or why it is invalid.")
final class ValidateCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Option(names = "--policy-class", required = true,
            description = "A class implementing Supplier<ToolGatePolicy>, with a public no-argument constructor.")
    String policyClass;

    @Option(names = "--classpath", split = ",", description = "Jars or folders with the policy class, comma-separated.")
    List<Path> classpath = new ArrayList<>();

    @Override
    public Integer call() {
        try (var classes = new Classes(classpath)) {
            ToolGatePolicy policy = classes.supplied(policyClass, ToolGatePolicy.class);
            spec.commandLine().getOut().println("valid: " + policyClass + " (mode " + policy.mode() + ", hash " + policy.hash() + ")");
            return 0;
        } catch (RuntimeException invalid) {
            spec.commandLine().getErr().println("invalid: " + invalid.getMessage());
            return 2;
        }
    }
}
