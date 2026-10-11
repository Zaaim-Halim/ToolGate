package io.toolgate.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * The {@code toolgate} command. Policies stay Java: a command loads a class that supplies one, from
 * the application's classes or a jar given with {@code --classpath}.
 */
@Command(name = "toolgate", mixinStandardHelpOptions = true, version = "toolgate 0.1.0",
        description = "Validate ToolGate policies and replay recorded tool calls through a proposed one.",
        subcommands = {ValidateCommand.class, ReplayCommand.class})
public final class ToolGateCommand implements Runnable {

    /**
     * Runs the command line and exits with its code.
     *
     * @param args the arguments
     */
    public static void main(String[] args) {
        System.exit(new CommandLine(new ToolGateCommand()).execute(args));
    }

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
