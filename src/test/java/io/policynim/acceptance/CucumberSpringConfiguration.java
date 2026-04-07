package io.policynim.acceptance;

import io.policynim.support.PostgresTestContainerConfiguration;
import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@CucumberContextConfiguration
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestContainerConfiguration.class)
public class CucumberSpringConfiguration {
}
