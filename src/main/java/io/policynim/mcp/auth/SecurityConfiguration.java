package io.policynim.mcp.auth;

import io.policynim.config.PolicyNimProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, PolicyNimProperties properties) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/healthz").permitAll()
                .anyRequest().authenticated()
            );

        if (properties.getMcp().getAuth().isEnabled()) {
            http
                .exceptionHandling(exceptionHandling -> exceptionHandling
                    .authenticationEntryPoint(new BearerTokenAuthenticationEntryPoint())
                )
                .addFilterBefore(
                    new BearerTokenAuthenticationFilter(properties.getMcp().getAuth().getBearerToken()),
                    UsernamePasswordAuthenticationFilter.class
                )
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);
        }
        else {
            http.httpBasic(Customizer.withDefaults());
        }

        return http.build();
    }
}
