package io.policynim.ingest;

import org.springframework.boot.ApplicationArguments;
import org.springframework.util.StringUtils;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class IngestCommandLine {

    public static final String COMMAND_NAME = "ingest";
    public static final String COMMAND_EXAMPLE =
        "java -jar target/policynim.jar ingest --corpus-root=/absolute/path/to/policies";
    public static final String USAGE =
        "Usage: java -jar target/policynim.jar ingest --corpus-root=<path>";

    private static final String CORPUS_ROOT_OPTION = "corpus-root";
    private static final String HELP_OPTION = "help";

    private IngestCommandLine() {
    }

    public static boolean isIngestCommand(String[] args) {
        Objects.requireNonNull(args, "args must not be null");
        return firstNonOptionArg(args).map(COMMAND_NAME::equals).orElse(false);
    }

    static ParsedCommand parse(ApplicationArguments args) {
        Objects.requireNonNull(args, "args must not be null");
        List<String> nonOptionArgs = args.getNonOptionArgs();
        if (nonOptionArgs.isEmpty() || !COMMAND_NAME.equals(nonOptionArgs.getFirst())) {
            return ParsedCommand.none();
        }
        if (nonOptionArgs.size() > 1) {
            throw usageError("Use --corpus-root=<path>; positional corpus roots are not supported.");
        }
        if (args.containsOption(HELP_OPTION)) {
            return ParsedCommand.help();
        }

        List<String> values = args.getOptionValues(CORPUS_ROOT_OPTION);
        if (values == null || values.size() != 1 || !StringUtils.hasText(values.getFirst())) {
            throw usageError("Provide exactly one --corpus-root=<path> value.");
        }

        return ParsedCommand.ingest(corpusRoot(values.getFirst()));
    }

    private static Optional<String> firstNonOptionArg(String[] args) {
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                return Optional.of(arg);
            }
        }
        return Optional.empty();
    }

    private static Path corpusRoot(String value) {
        try {
            return Path.of(value).toAbsolutePath().normalize();
        }
        catch (InvalidPathException exception) {
            throw usageError("Policy corpus root is not a valid path: " + exception.getInput());
        }
    }

    private static IllegalArgumentException usageError(String message) {
        return new IllegalArgumentException(message + System.lineSeparator() + USAGE);
    }

    record ParsedCommand(Action action, Optional<Path> corpusRoot) {

        private static ParsedCommand none() {
            return new ParsedCommand(Action.NONE, Optional.empty());
        }

        private static ParsedCommand help() {
            return new ParsedCommand(Action.HELP, Optional.empty());
        }

        private static ParsedCommand ingest(Path corpusRoot) {
            return new ParsedCommand(Action.INGEST, Optional.of(corpusRoot));
        }
    }

    enum Action {
        NONE,
        HELP,
        INGEST
    }
}
