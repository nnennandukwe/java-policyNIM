package io.policynim.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;

@TestConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties(PolicyNimProperties.class)
public class PolicyNimApplicationTestConfiguration {
}
