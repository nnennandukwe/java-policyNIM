package io.policynim.cli;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.policynim.policy.catalog.SourceCatalog;
import io.policynim.policy.parse.InvalidPolicyDocumentException;

import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Runs offline inspection of a selected local profile before starting the application. */
public final class ProfileCommand {
    private static final String USAGE = """
        Usage: java -jar <jar> profile <command> <path> [--format text|json]
          preview    Show each authored policy, its state and the reason for a decision.
          validate   Check pack pins, policy IDs, decisions and declared conflicts.
        """;
    private static final String LIMITS = "Structure and declared conflicts only; prose compatibility isn't certified.";

    private ProfileCommand() { }

    public static boolean isProfileCommand(String[] args) {
        return SourceCatalogCommand.matchesFirstOperand(args, "profile");
    }

    public static int run(String[] args, PrintStream output, PrintStream errors) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException invalid) {
            errors.println(invalid.getMessage());
            errors.print(USAGE);
            return 2;
        }
        if (options.help()) {
            output.print(USAGE);
            return 0;
        }
        try {
            var catalog = SourceCatalog.readProfile(options.path());
            if (options.command().equals("preview")) {
                preview(catalog, options.json(), output);
            } else {
                validate(catalog, options.json(), output);
            }
            return 0;
        } catch (InvalidPolicyDocumentException invalid) {
            if (options.json()) {
                output.println(JsonNodeFactory.instance.objectNode().put("status", "error").put("message", invalid.getMessage()));
            } else {
                errors.println(invalid.getMessage());
            }
            return 1;
        }
    }

    private static void preview(SourceCatalog catalog, boolean json, PrintStream output) {
        var view = catalog.list();
        if (json) {
            output.println(view.toPrettyString());
            return;
        }
        output.printf("Profile %s: %d effective policies%nSource catalog: %s; indexed generation unknown%n",
            view.required("profile_id").textValue(), view.required("effective_count").intValue(),
            view.required("digest").textValue());
        for (var entry : view.required("policies")) {
            output.printf("%s  %s  %s%n", entry.required("policy").required("policy_id").textValue(),
                entry.required("state").textValue(), entry.required("reason").asText(""));
        }
    }

    private static void validate(SourceCatalog catalog, boolean json, PrintStream output) {
        var view = catalog.validate();
        String digest = view.required("catalog_digest").textValue();
        int count = view.required("effective_count").intValue();
        if (json) {
            var result = JsonNodeFactory.instance.objectNode().put("status", "ok").put("catalog_digest", digest)
                .put("effective_count", count).put("limits", LIMITS);
            output.println(result.toPrettyString());
        } else {
            output.printf("Valid profile: %d effective policies; %s%n", count, digest);
            output.println(LIMITS);
        }
    }

    private record Options(String command, Path path, boolean json, boolean help) {
        static Options parse(String[] args) {
            if (args.length < 2 || !args[0].equals("profile")) {
                throw new IllegalArgumentException("Place profile first, followed by preview or validate.");
            }
            String command = args[1];
            if (command.equals("--help") && args.length == 2) {
                return new Options(null, null, false, true);
            }
            if (!command.equals("preview") && !command.equals("validate")) {
                throw new IllegalArgumentException("Choose preview or validate.");
            }
            String format = "text";
            Path path = null;
            boolean positional = false;
            for (int i = 2; i < args.length; i++) {
                String arg = args[i];
                if (!positional && arg.equals("--")) {
                    positional = true;
                } else if (!positional && arg.equals("--help")) {
                    return new Options(command, null, false, true);
                } else if (!positional && (arg.equals("--format") || arg.startsWith("--format="))) {
                    if (arg.equals("--format")) {
                        if (i + 1 == args.length) {
                            throw new IllegalArgumentException("Provide a value for --format.");
                        }
                        format = args[++i];
                    } else {
                        format = arg.substring("--format=".length());
                    }
                    if (!format.equals("text") && !format.equals("json")) {
                        throw new IllegalArgumentException("Choose --format text or --format json.");
                    }
                } else if (!positional && arg.startsWith("-")) {
                    throw new IllegalArgumentException("Unknown profile option.");
                } else {
                    if (path != null) {
                        throw new IllegalArgumentException("Provide exactly one profile path.");
                    }
                    try {
                        path = Path.of(arg);
                    } catch (InvalidPathException invalid) {
                        throw new IllegalArgumentException("Provide a valid local profile path.", invalid);
                    }
                }
            }
            if (path == null) {
                throw new IllegalArgumentException("Provide a local profile path.");
            }
            return new Options(command, path, format.equals("json"), false);
        }
    }
}
