package io.policynim.bootstrap;

import io.policynim.mcp.transport.McpServerBootstrap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class StartupMcpTransportLogger implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(StartupMcpTransportLogger.class);

    private final McpServerBootstrap bootstrap;

    public StartupMcpTransportLogger(McpServerBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    @Override
    public void run(ApplicationArguments args) {
        LOGGER.info(
            "PolicyNIM bootstrap selected MCP transport={} detail={}",
            bootstrap.transport().configValue(),
            bootstrap.description()
        );
    }
}
