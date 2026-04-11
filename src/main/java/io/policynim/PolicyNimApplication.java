package io.policynim;

import io.policynim.ingest.IngestCommandLine;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

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
        application(args).run(args);
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
