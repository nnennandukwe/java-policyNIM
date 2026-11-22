package io.policynim.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.policynim.policy.catalog.SourceCatalog;
import io.policynim.policy.parse.InvalidPolicyDocumentException;

import java.io.File;
import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/** Creates local profiles and reports their source state before starting the application. */
public final class ProfileCommand {
    private static final String USAGE = """
        Usage: java -jar <jar> profile <command> <path> [--format text|json]
          init       Create new pins. Use --id NAME and repeat --pack DIR to select local packs.
          diff       Compare current sources with the pins and print a review digest.
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
            switch (options.command()) {
                case "init" -> initialize(options, output);
                case "diff" -> diff(options, output);
                case "preview" -> preview(SourceCatalog.readProfile(options.path()), options.json(), output);
                case "validate" -> validate(SourceCatalog.readProfile(options.path()), options.json(), output);
                default -> throw new IllegalStateException("Unrecognized profile operation");
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

    private static void initialize(Options options, PrintStream output) {
        var profile = SourceCatalog.initializeProfile(options.path(), options.packs(), options.profileId());
        String displayed = displayPath(options.path());
        if (options.json()) {
            var result = JsonNodeFactory.instance.objectNode().put("status", "ok").put("path", displayed);
            result.set("profile", profile);
            output.println(result.toPrettyString());
        } else {
            output.println("Created pinned profile: " + SourceCatalogCommand.pathForDisplay(displayed, File.separatorChar != '\\'));
            output.println("To inspect it, run profile preview with the same jar and path.");
        }
    }

    // The CLI displays the caller's path. Remove dot components while retaining parent components and symlinks.
    private static String displayPath(Path path) {
        Path displayed = path.getRoot() == null ? Path.of("") : path.getRoot();
        for (Path part : path) {
            if (!part.toString().equals(".")) {
                displayed = displayed.resolve(part);
            }
        }
        return displayed.toString().isEmpty() ? "." : displayed.toString();
    }

    private static void diff(Options options, PrintStream output) {
        var report = SourceCatalog.diffProfile(options.path());
        if (options.json()) {
            output.println(report.toPrettyString());
            return;
        }
        for (String field : List.of("added", "changed", "removed", "changed_packs", "invalidated_decisions")) {
            var names = new StringJoiner(", ").setEmptyValue("(none)");
            for (JsonNode id : report.required(field)) {
                names.add(SourceCatalogCommand.pathForDisplay(id.textValue(), true));
            }
            output.printf("%s: %s%n", field, names);
        }
        String token = report.required("review_digest").textValue();
        output.println("Review digest: " + token);
        JsonNode invalid = report.required("validation_error");
        if (invalid.isNull()) {
            output.println("To apply reviewed pins, use the Python CLI's profile update command with --expect-digest " + token);
        } else {
            output.println("Update blocked: " + invalid.textValue());
            output.println("Review current_policies with --format json for new text and target digests.");
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

    private record Options(String command, Path path, List<String> packs, String profileId, boolean json, boolean help) {
        private static Options help(String command) {
            return new Options(command, null, List.of(), "local", false, true);
        }

        static Options parse(String[] args) {
            if (args.length < 2 || !args[0].equals("profile")) {
                throw new IllegalArgumentException("Place profile first, followed by a command.");
            }
            String command = args[1];
            if (command.equals("--help") && args.length == 2) {
                return help(command);
            }
            if (!Set.of("init", "preview", "validate", "diff").contains(command)) {
                throw new IllegalArgumentException("Choose init, preview, validate or diff.");
            }
            var packs = new ArrayList<String>();
            String profileId = "local";
            String format = "text";
            Path path = null;
            boolean positional = false;
            for (int i = 2; i < args.length; i++) {
                String arg = args[i];
                if (!positional && arg.equals("--")) {
                    positional = true;
                } else if (!positional && arg.equals("--help")) {
                    return help(command);
                } else if (!positional && arg.startsWith("-")) {
                    int equals = arg.indexOf('=');
                    String key = equals < 0 ? arg : arg.substring(0, equals);
                    boolean initOption = command.equals("init") && (key.equals("--id") || key.equals("--pack"));
                    if (!key.equals("--format") && !initOption) {
                        throw new IllegalArgumentException("Unknown profile option.");
                    }
                    if (equals < 0 && i + 1 >= args.length) {
                        throw new IllegalArgumentException("Provide a value for " + key + ".");
                    }
                    String value = equals < 0 ? args[++i] : arg.substring(equals + 1);
                    switch (key) {
                        case "--pack" -> packs.add(value);
                        case "--id" -> profileId = value;
                        default -> {
                            if (!value.equals("text") && !value.equals("json")) {
                                throw new IllegalArgumentException("Choose --format text or --format json.");
                            }
                            format = value;
                        }
                    }
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
            return new Options(command, path, List.copyOf(packs), profileId, format.equals("json"), false);
        }
    }
}
