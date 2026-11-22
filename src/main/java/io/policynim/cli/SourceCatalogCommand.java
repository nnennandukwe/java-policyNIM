package io.policynim.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.policynim.policy.catalog.SourceCatalog;
import io.policynim.policy.parse.InvalidPolicyDocumentException;

import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Runs local source inspection before the application opens a server or initializes storage. */
public final class SourceCatalogCommand {
    private static final String USAGE = """
        Usage: java -jar <jar> policies <command> --source --corpus-root=<path> [--format text|json]
          validate           Check source metadata and revisions.
          list               List the policies in the selected directory.
          show <policy ID>   Show an exact policy ID and its Markdown body.
        """;

    private SourceCatalogCommand() { }

    public static boolean isSourceCatalogCommand(String[] args) {
        for (String arg : args) {
            if (!arg.startsWith("-")) {
                return arg.equals("policies");
            }
        }
        return false;
    }

    public static int run(String[] args, PrintStream output, PrintStream errors) {
        Options options;
        try {
            options = Options.parse(args);
        }
        catch (IllegalArgumentException usage) {
            errors.println(usage.getMessage());
            errors.print(USAGE);
            return 2;
        }
        if (options.help()) {
            output.print(USAGE);
            return 0;
        }
        try {
            SourceCatalog catalog = SourceCatalog.read(options.root());
            JsonNode view = switch (options.command()) {
                case "validate" -> catalog.validate();
                case "list" -> catalog.list();
                case "show" -> catalog.show(options.policyId());
                default -> throw new IllegalStateException("Unrecognized source catalog operation");
            };
            if (options.json()) {
                output.println(view.toPrettyString());
            }
            else {
                printText(options.command(), view, output);
            }
            return 0;
        }
        catch (InvalidPolicyDocumentException rejected) {
            if (options.json()) {
                var error = JsonNodeFactory.instance.objectNode();
                error.put("status", "error").put("scope", "source").putNull("generation_id")
                    .put("message", rejected.getMessage());
                output.println(error);
            }
            else {
                errors.println(rejected.getMessage());
            }
            return 1;
        }
    }

    private static void printText(String command, JsonNode view, PrintStream output) {
        if (command.equals("show")) {
            JsonNode policy = view.get("policy");
            output.printf("%s: %s%nScope: source; generation: unknown%nSource: %s; state: %s%n",
                policy.get("policy_id").textValue(), policy.get("title").textValue(),
                pathForDisplay(view.get("path").textValue()), view.get("state").textValue());
            JsonNode provenance = policy.get("provenance");
            output.printf("Revision: %s%nGrounding: %s%n", provenance.get("content_digest").textValue(),
                provenance.get("grounding_kind").textValue());
            output.println(view.get("body").textValue());
            return;
        }
        boolean validate = command.equals("validate");
        output.printf("%s: %d policies; %s%n", validate ? "Valid source catalog" : "Source catalog",
            view.get("effective_count").intValue(), view.get(validate ? "catalog_digest" : "digest").textValue());
        output.println("Generation: unknown (source inspection only)");
        JsonNode unlisted = view.get("unlisted_markdown");
        if (validate) {
            output.println("Validation checks structure and declared metadata; it doesn't certify compliance.");
            if (!unlisted.isEmpty()) {
                List<String> paths = new ArrayList<>();
                unlisted.forEach(path -> paths.add(pathForDisplay(path.textValue())));
                output.println("Unlisted Markdown (not selected): " + String.join(", ", paths));
            }
            return;
        }
        for (JsonNode entry : view.get("policies")) {
            JsonNode policy = entry.get("policy");
            JsonNode provenance = policy.get("provenance");
            String pack = provenance.get("pack_id").isNull() ? "legacy directory (pack unknown)"
                : provenance.get("pack_id").textValue() + "@" + provenance.get("pack_version").textValue();
            output.printf("%s  %s  %s  %s  %s%n", policy.get("policy_id").textValue(), entry.get("state").textValue(),
                policy.get("domain").textValue(), pack, policy.get("title").textValue());
        }
        if (!unlisted.isEmpty()) {
            output.printf("Unlisted Markdown: %d file(s) aren't selected. "
                + "Run `java -jar <jar> policies validate --source --corpus-root=<path> --format json` for their paths.%n",
                unlisted.size());
        }
    }

    private static String pathForDisplay(String path) {
        var display = new StringBuilder(path.length());
        path.codePoints().forEach(point -> {
            switch (Character.getType(point)) {
                case Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR ->
                    display.append((point <= Character.MAX_VALUE ? "\\u%04x" : "\\U%08x").formatted(point));
                default -> {
                    if (point == '\\') {
                        display.append('\\');
                    }
                    display.appendCodePoint(point);
                }
            }
        });
        return display.toString();
    }

    private record Options(String command, String policyId, Path root, boolean json, boolean help) {
        static Options parse(String[] args) {
            if (args.length == 0 || !args[0].equals("policies")) {
                throw new IllegalArgumentException("Place policies first, followed by its command and source options.");
            }
            if (args.length == 2 && args[1].equals("--help")) {
                return new Options(null, null, null, false, true);
            }
            if (args.length < 2 || !List.of("validate", "list", "show").contains(args[1])) {
                throw new IllegalArgumentException("Choose validate, list or show.");
            }
            String command = args[1];
            List<String> operands = new ArrayList<>();
            Path root = null;
            String format = "text";
            boolean source = false;
            for (int i = 2; i < args.length; i++) {
                String arg = args[i];
                if (arg.equals("--")) {
                    operands.addAll(List.of(args).subList(i + 1, args.length));
                    break;
                }
                if (arg.equals("--help")) {
                    return new Options(command, null, null, false, true);
                }
                if (arg.equals("--source")) {
                    source = true;
                    continue;
                }
                if (!arg.startsWith("-")) {
                    operands.add(arg);
                    continue;
                }
                int separator = arg.indexOf('=');
                String name = separator < 0 ? arg : arg.substring(0, separator);
                if (!name.equals("--corpus-root") && !name.equals("--format")) {
                    throw new IllegalArgumentException("Unknown source catalog option " + name);
                }
                if (separator < 0 && (i + 1 >= args.length || args[i + 1].startsWith("--"))) {
                    throw new IllegalArgumentException("Provide a value for " + name);
                }
                String value = separator < 0 ? args[++i] : arg.substring(separator + 1);
                if (name.equals("--format")) {
                    if (!value.equals("text") && !value.equals("json")) {
                        throw new IllegalArgumentException("Choose --format text or --format json.");
                    }
                    format = value;
                }
                else {
                    if (value.isBlank() || root != null) {
                        throw new IllegalArgumentException("Provide exactly one nonblank --corpus-root.");
                    }
                    try {
                        root = Path.of(value);
                    }
                    catch (InvalidPathException invalid) {
                        throw new IllegalArgumentException("Provide a valid path for --corpus-root.", invalid);
                    }
                }
            }
            if (operands.size() != (command.equals("show") ? 1 : 0)) {
                throw new IllegalArgumentException(command.equals("show")
                    ? "Provide exactly one policy ID for show." : "This command accepts no positional arguments.");
            }
            if (!source || root == null) {
                throw new IllegalArgumentException("Select editable source files with --source and --corpus-root=<path>.");
            }
            return new Options(command, operands.isEmpty() ? null : operands.getFirst(), root, format.equals("json"), false);
        }
    }
}
