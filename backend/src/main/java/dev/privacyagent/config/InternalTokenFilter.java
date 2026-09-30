package dev.privacyagent.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Only the frontend proxy may call {@code /api/**}: it must present the shared {@code X-Internal-Token}.
 * The proxy also forwards the signed-in user as {@code X-User}, which is put in the MDC so audit events
 * record who did what. Health probes stay open for the platform.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class InternalTokenFilter extends OncePerRequestFilter {

    public static final String TOKEN_HEADER = "X-Internal-Token";
    public static final String USER_HEADER = "X-User";
    public static final String MDC_USER = "user";
    private static final Pattern SAFE_USER = Pattern.compile("[a-z0-9._-]{1,32}");

    private final SecurityProperties properties;
    private final byte[] expected;

    public InternalTokenFilter(SecurityProperties properties) {
        this.properties = properties;
        if (!properties.tokenConfigured()) {
            if (properties.requireInternalToken()) {
                throw new IllegalStateException(
                        "privacy.security.internal-token (INTERNAL_API_TOKEN) must be set when it is required");
            }
            log.warn("INTERNAL_API_TOKEN is not set: /api is callable without the frontend proxy (local use only)");
        }
        this.expected = properties.tokenConfigured()
                ? properties.internalToken().getBytes(StandardCharsets.UTF_8)
                : new byte[0];
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (properties.tokenConfigured()) {
            String presented = request.getHeader(TOKEN_HEADER);
            byte[] actual = presented == null ? new byte[0] : presented.getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(expected, actual)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"error\":\"UNAUTHORIZED\",\"message\":\"Not authenticated\"}");
                return;
            }
        }
        String user = request.getHeader(USER_HEADER);
        if (user != null && SAFE_USER.matcher(user).matches()) {
            MDC.put(MDC_USER, user);
        }
        chain.doFilter(request, response);
    }
}
