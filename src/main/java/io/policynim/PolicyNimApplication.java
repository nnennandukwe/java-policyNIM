package io.policynim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PolicyNimApplication {

    public static void main(String[] args) {
        SpringApplication.run(PolicyNimApplication.class, args);
    }

}
