package io.policynim;

import io.policynim.cli.SourceCatalogCommand;
import io.policynim.ingest.IngestCommandLine;
import io.policynim.mcp.transport.GroundedStdioRuntime;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

@SpringBootApplication(exclude = {
    DataSourceAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class,
    JdbcTemplateAutoConfiguration.class,
    FlywayAutoConfiguration.class
})
@ConfigurationPropertiesScan
public class PolicyNimApplication {

    public static void main(String[] args) {
        if (args.length > 0 && "policies".equals(args[0])) {
            int status = SourceCatalogCommand.run(args, System.out, System.err);
            if (status != 0) {
                System.exit(status);
            }
            return;
        }
        var context = application(args).run(args);
        var stdio = context.getBeanProvider(GroundedStdioRuntime.class).getIfAvailable();
        if (stdio == null) {
            return;
        }
        // The main thread owns stdin after Spring has initialized the tools and their dependencies.
        try (context) {
            stdio.run();
        }
        catch (IOException failure) {
            throw new UncheckedIOException("Grounded MCP stdio failed", failure);
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Grounded MCP stdio was interrupted", interrupted);
        }
    }

    static SpringApplication application(String[] args) {
        SpringApplication application = new SpringApplication(PolicyNimApplication.class);
        if (IngestCommandLine.isIngestCommand(args)) {
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setDefaultProperties(Map.of(
                "spring.ai.mcp.server.enabled", "false",
                "spring.main.banner-mode", "off",
                "logging.level.root", "warn"
            ));
        }
        return application;
    }

}
