package dev.rovernotes;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies a request limit to each request that has passed authorization.
 *
 * <p>Placed after the security chain's authorization filter rather than in front of it, so
 * a request that was going to be refused anyway is refused for the reason it deserves. A
 * limiter in front would spend a caller's allowance on requests they were never permitted
 * to make, and would turn an expired token into a 429.
 *
 * <p>The refusal is written here rather than thrown. A filter sits outside the dispatcher,
 * so an exception from one never reaches a controller's {@code @ExceptionHandler}; it
 * becomes a container error dispatch instead, which is a longer path to the same status
 * with less control over what comes back. The body is written explicitly for the same
 * reason {@code AskController} sets its own content type on a refusal: a client of the
 * streaming endpoint asks for {@code text/event-stream}, and a refusal is not the media
 * type the request asked for.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final AntPathMatcher PATHS = new AntPathMatcher();

    private final RequestLimits limits;

    RateLimitFilter(RequestLimits limits) {
        this.limits = limits;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String bucket = bucketFor(request);
        if (bucket == null) {
            chain.doFilter(request, response);
            return;
        }

        RequestLimits.Decision decision = limits.take(bucket, subject(request));
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        refuse(response, decision.retryAfterSeconds());
    }

    /**
     * Which limit this request counts against, or null for a path that has none.
     *
     * <p>Health probes and the discovery documents are deliberately absent. A probe that
     * is rate limited reports the instance unhealthy under load, which removes an instance
     * that is still answering; and a client that cannot read the discovery document cannot
     * find out how to authenticate, which turns a limit into a dead end.
     */
    private static String bucketFor(HttpServletRequest request) {
        String path = request.getRequestURI();

        if (PATHS.match("/auth/**", path) || "/login".equals(path)
                || PATHS.match("/oauth2/register", path)
                || PATHS.match("/connect/register", path)) {
            return RequestLimits.AUTH;
        }
        if (PATHS.match("/api/notes/**", path) && isWrite(request)) {
            return RequestLimits.INGEST;
        }
        if (PATHS.match("/api/**", path) || PATHS.match("/mcp/**", path)) {
            return RequestLimits.API;
        }
        return null;
    }

    private static boolean isWrite(HttpServletRequest request) {
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        return HttpMethod.POST.equals(method) || HttpMethod.PUT.equals(method);
    }

    /**
     * Whom to count this against.
     *
     * <p>The account id where there is one, so a caller's limit follows them rather than
     * their network: an agent and a browser signed in as the same person share an
     * allowance, and moving to another address does not grant a fresh one.
     *
     * <p>Falling back to the client address is what makes the unauthenticated endpoints
     * limitable at all, and it is a weaker key on purpose — callers behind one egress
     * share it. That is the right trade for registration and password reset, where the
     * alternative is no limit; it is why the allowance there is generous compared with
     * what one person needs.
     */
    private static String subject(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getName())) {
            return authentication.getName();
        }
        return request.getRemoteAddr();
    }

    /**
     * 429, with the wait in seconds in both the header and the body.
     *
     * <p>A generic HTTP client honours the header; an interface explains the body to a
     * person. Seconds rather than a timestamp, because a client comparing an absolute time
     * against its own clock is comparing two clocks.
     */
    private static void refuse(HttpServletResponse response, long retryAfterSeconds)
            throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"error\":\"too_many_requests\",\"retryAfterSeconds\":%d}"
                        .formatted(retryAfterSeconds));
    }
}
