package io.policynim.config;

import org.springframework.core.env.Environment;

/** Selects the wire contract served by this process. */
public enum McpContract {
    JAVA_V1("java-v1"),
    GROUNDED_V1("grounded-v1");

    public static final String PROPERTY = "policynim.mcp.contract";
    private final String setting;

    McpContract(String setting) {
        this.setting = setting;
    }

    public static McpContract resolve(Environment environment) {
        String requested = environment.getProperty(PROPERTY, JAVA_V1.setting);
        for (McpContract contract : values()) {
            if (contract.setting.equalsIgnoreCase(requested)) {
                return contract;
            }
        }
        throw new IllegalArgumentException(PROPERTY + " must be java-v1 or grounded-v1.");
    }
}
