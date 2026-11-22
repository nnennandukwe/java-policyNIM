package io.policynim.mcp.transport;

import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/** Checks the configured MCP authorities before authentication and slash redirects. */
final class GroundedHttpOriginFilter extends OncePerRequestFilter {

    private final GroundedHttpAuthorities authorities;

    GroundedHttpOriginFilter(GroundedHttpAuthorities authorities) {
        this.authorities = authorities;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        try {
            authorities.validate(Collections.list(request.getHeaders(HttpHeaders.HOST)),
                Collections.list(request.getHeaders(HttpHeaders.ORIGIN)));
        }
        catch (ServerTransportSecurityException rejected) {
            response.setStatus(rejected.getStatusCode());
            response.setContentType("text/plain");
            response.getWriter().write(rejected.getMessage());
            return;
        }
        chain.doFilter(request, response);
    }
}
