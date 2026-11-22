package io.policynim.mcp.transport;

import io.modelcontextprotocol.server.transport.HeaderAccessor;
import io.modelcontextprotocol.server.transport.ServerHttpHeaderValidator;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import org.springframework.http.HttpHeaders;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntSupplier;

/** The same configured authorities govern HTTP routing and both SDK handlers. */
final class GroundedHttpAuthorities implements ServerHttpHeaderValidator {

    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[0:0:0:0:0:0:0:1]");
    private static final Set<String> NON_SPECIFIC = Set.of("0.0.0.0", "255.255.255.255", "[0:0:0:0:0:0:0:0]",
        "[0.0.0.0]", "[255.255.255.255]");
    private final String bindHost;
    private final IntSupplier bindPort;
    private final URI publicOrigin;
    private final String publicHost;

    GroundedHttpAuthorities(String bindHost, IntSupplier bindPort, boolean authenticated, URI publicOrigin) {
        if (authenticated && publicOrigin == null) {
            throw new IllegalArgumentException(
                "Set policynim.mcp.public-base-url for an authenticated grounded-v1 HTTP server.");
        }
        String host = normalizeHost(bindHost);
        this.bindHost = authenticated || NON_SPECIFIC.contains(host) ? null : host;
        this.bindPort = bindPort;
        this.publicOrigin = publicOrigin;
        this.publicHost = publicOrigin != null && validOrigin(publicOrigin, true)
            ? normalizeHost(publicOrigin.getHost()) : null;
        if (publicOrigin != null && (publicHost == null || NON_SPECIFIC.contains(publicHost))) {
            throw new IllegalArgumentException("policynim.mcp.public-base-url must be an HTTP or HTTPS origin.");
        }
    }

    @Override
    public void validate(HeaderAccessor headers) throws ServerTransportSecurityException {
        validate(headers.getHeader(HttpHeaders.HOST), headers.getHeader(HttpHeaders.ORIGIN));
    }

    void validate(List<String> hosts, List<String> origins) throws ServerTransportSecurityException {
        if (hosts.size() != 1 || !allows(hosts.getFirst(), false)) {
            throw new ServerTransportSecurityException(421, "Invalid Host header");
        }
        if (origins.size() > 1 || (!origins.isEmpty() && !allows(origins.getFirst(), true))) {
            throw new ServerTransportSecurityException(403, "Invalid Origin header");
        }
    }

    private boolean allows(String value, boolean origin) {
        URI uri;
        String host;
        try {
            uri = URI.create(origin ? value : "http://" + value);
            if (!validOrigin(uri, false)) {
                return false;
            }
            host = normalizeHost(uri.getHost());
        }
        catch (IllegalArgumentException invalidAuthority) {
            return false;
        }
        String scheme = uri.getScheme();
        int port = uri.getPort();
        if (!origin || "http".equalsIgnoreCase(scheme)) {
            if (LOOPBACK.contains(host)) {
                return true;
            }
            if (host.equals(bindHost) && effectivePort(port, "http") == bindPort.getAsInt()) {
                return true;
            }
        }
        if (publicOrigin == null || (origin && !publicOrigin.getScheme().equalsIgnoreCase(scheme))) {
            return false;
        }
        String publicScheme = publicOrigin.getScheme();
        return host.equals(publicHost)
            && effectivePort(port, publicScheme) == effectivePort(publicOrigin.getPort(), publicScheme);
    }

    private static boolean validOrigin(URI uri, boolean configured) {
        return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
            && uri.getHost() != null && uri.getRawUserInfo() == null && !uri.getRawAuthority().endsWith(":")
            && uri.getHost().indexOf('%') < 0 && uri.getPort() != 0 && uri.getPort() <= 65535
            && uri.getRawQuery() == null && uri.getRawFragment() == null
            && (uri.getRawPath().isEmpty() || (configured && uri.getRawPath().equals("/")));
    }

    private static int effectivePort(int port, String scheme) {
        if (port >= 0) {
            return port;
        }
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    private static String normalizeHost(String host) {
        if (host.indexOf(':') < 0) {
            return host.toLowerCase(Locale.ROOT);
        }
        // Only parse IPv6 literals. Header hostnames must never trigger DNS resolution.
        try {
            return "[" + InetAddress.getByName(host).getHostAddress() + "]";
        }
        catch (UnknownHostException invalidAddress) {
            throw new IllegalArgumentException("Invalid IPv6 host.", invalidAddress);
        }
    }
}
