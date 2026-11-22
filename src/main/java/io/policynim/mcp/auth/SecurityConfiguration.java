package io.policynim.mcp.auth;

import io.policynim.config.PolicyNimProperties;
import io.policynim.mcp.transport.StreamableHttpTransportMarker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.Optional;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
        HttpSecurity http, PolicyNimProperties properties, Optional<StreamableHttpTransportMarker> hosted
    ) throws Exception {
        boolean bearerAuthEnabled = hosted.isPresent() && properties.getMcp().getAuth().isEnabled();

        http.csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (bearerAuthEnabled) {
            http
                .authorizeHttpRequests(authorize -> authorize
                    .requestMatchers("/healthz", "/livez").permitAll()
                    .anyRequest().hasRole("MCP_CLIENT")
                )
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
            http
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers("/healthz", "/livez").permitAll();
                    hosted.ifPresent(endpoint ->
                        authorize.requestMatchers(endpoint.path(), endpoint.path() + "/**").permitAll()
                    );
                    authorize.anyRequest().denyAll();
                })
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);
        }

        return http.build();
    }
}
