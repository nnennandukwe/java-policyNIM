package io.policynim.cli;

import io.policynim.policy.catalog.SourceCatalog;
import io.policynim.policy.parse.InvalidPolicyDocumentException;
import io.policynim.policy.parse.SourceMarkdownParser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;

/** Selects a catalog before Spring starts, including on a fresh jar installation. */
final class CatalogSourceSettings {
    private static final String CORPUS = "POLICYNIM_CORPUS_DIR";
    private static final String PROFILE = "POLICYNIM_PROFILE";
    private static final String CONFIG = "POLICYNIM_CONFIG_FILE";
    private static final String ROOT_HINT = " Pass the policy directory with --corpus-root=<path>.";

    private CatalogSourceSettings() { }

    static SourceCatalog read(Path explicitRoot, Map<String, String> env) {
        if (explicitRoot != null) {
            return SourceCatalog.read(explicitRoot);
        }
        var settings = readConfig(env);
        String corpus = env.getOrDefault(CORPUS, settings.corpus());
        String root = corpus == null ? "" : SourceMarkdownParser.strip(corpus);
        String profile = env.getOrDefault(PROFILE, settings.profile());
        if (profile != null) {
            String problem = root.isEmpty() ? "Source inspection can't resolve " + PROFILE + " yet."
                : "Set only one of " + PROFILE + " and " + CORPUS + ".";
            throw new InvalidPolicyDocumentException(problem + ROOT_HINT);
        }
        return root.isEmpty() ? SourceCatalog.readBundled() : SourceCatalog.read(path(root, CORPUS));
    }

    private static SourceConfigFile readConfig(Map<String, String> env) {
        String configured = env.get(CONFIG);
        if (configured == null) {
            return new SourceConfigFile(null, null);
        }
        String name = SourceMarkdownParser.strip(configured);
        if (name.equals("~") || name.startsWith("~/") || name.startsWith("~" + File.separator)) {
            name = System.getProperty("user.home") + name.substring(1);
        }
        Path file = path(name, CONFIG);
        if (!Files.isRegularFile(file)) {
            throw new InvalidPolicyDocumentException(CONFIG + " must select an existing configuration file." + ROOT_HINT);
        }
        try {
            return SourceConfigFile.read(file, env);
        } catch (IOException error) {
            throw new InvalidPolicyDocumentException("Couldn't read " + CONFIG + "." + ROOT_HINT, error);
        }
    }

    private static Path path(String value, String setting) {
        try {
            return Path.of(value);
        } catch (InvalidPathException error) {
            throw new InvalidPolicyDocumentException("Provide a valid path for " + setting + "." + ROOT_HINT, error);
        }
    }
}
