package io.policynim.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestContainerConfiguration {

    @Bean
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.2-pg16").asCompatibleSubstituteFor("postgres")
        );
    }

    @Bean
    JdbcConnectionDetails jdbcConnectionDetails(PostgreSQLContainer<?> postgresContainer) {
        return new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return postgresContainer.getUsername();
            }

            @Override
            public String getPassword() {
                return postgresContainer.getPassword();
            }

            @Override
            public String getJdbcUrl() {
                return postgresContainer.getJdbcUrl();
            }

            @Override
            public String getDriverClassName() {
                return postgresContainer.getDriverClassName();
            }
        };
    }
}
