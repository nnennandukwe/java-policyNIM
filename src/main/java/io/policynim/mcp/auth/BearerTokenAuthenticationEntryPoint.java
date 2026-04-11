package io.policynim.mcp.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

public final class BearerTokenAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String CHALLENGE = "Bearer realm=\"PolicyNIM MCP\"";

    @Override
    public void commence(
        HttpServletRequest request,
        HttpServletResponse response,
        AuthenticationException authException
    ) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE);
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Bearer token required.");
    }
}
