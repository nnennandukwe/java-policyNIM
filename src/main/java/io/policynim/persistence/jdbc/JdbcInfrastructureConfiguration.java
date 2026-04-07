package io.policynim.persistence.jdbc;

import io.policynim.config.PolicyNimProperties;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "policynim.storage", name = "mode", havingValue = "jdbc")
@EnableConfigurationProperties(FlywayProperties.class)
class JdbcInfrastructureConfiguration {

    @Bean
    @ConfigurationProperties("spring.datasource")
    DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    DataSource dataSource(DataSourceProperties properties, ObjectProvider<JdbcConnectionDetails> connectionDetailsProvider) {
        JdbcConnectionDetails connectionDetails = connectionDetailsProvider.getIfAvailable();
        var builder = DataSourceBuilder.create(properties.getClassLoader());
        if (properties.getType() != null) {
            builder.type(properties.getType());
        }

        String driverClassName = connectionDetails != null
            ? connectionDetails.getDriverClassName()
            : properties.determineDriverClassName();
        if (StringUtils.hasText(driverClassName)) {
            builder.driverClassName(driverClassName);
        }

        builder.url(connectionDetails != null ? connectionDetails.getJdbcUrl() : properties.determineUrl());
        builder.username(connectionDetails != null ? connectionDetails.getUsername() : properties.determineUsername());
        builder.password(connectionDetails != null ? connectionDetails.getPassword() : properties.determinePassword());
        return builder.build();
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }

    @Bean(initMethod = "migrate")
    Flyway flyway(DataSource dataSource, FlywayProperties properties, PolicyNimProperties policyNimProperties) {
        FluentConfiguration configuration = Flyway.configure()
            .dataSource(dataSource)
            .locations(properties.getLocations().toArray(String[]::new))
            .failOnMissingLocations(properties.isFailOnMissingLocations());

        if (StringUtils.hasText(properties.getDefaultSchema())) {
            configuration.defaultSchema(properties.getDefaultSchema());
        }
        if (!properties.getSchemas().isEmpty()) {
            configuration.schemas(properties.getSchemas().toArray(String[]::new));
        }

        Map<String, String> placeholders = new LinkedHashMap<>(properties.getPlaceholders());
        placeholders.putIfAbsent("tableName", policyNimProperties.getStorage().getTableName());
        configuration.placeholders(placeholders);

        return configuration.load();
    }
}
