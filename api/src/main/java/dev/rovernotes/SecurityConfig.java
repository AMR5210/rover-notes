package dev.rovernotes;

import jakarta.servlet.DispatcherType;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * The API is a stateless OAuth2 resource server: every {@code /api/**} and {@code /mcp/**}
 * request carries a bearer token and nothing else.
 *
 * <p>The tokens are issued by this service rather than by a managed provider — see
 * {@code docs/ARCHITECTURE.md} — but nothing on this chain knows that. It validates
 * a standards-compliant JWT and holds no session, which is what keeps these paths free of
 * cookies and therefore of CSRF concerns. The issuing side lives in the identity module,
 * on its own chains.
 */
@Configuration
public class SecurityConfig {

    /**
     * Default (deployed) chain: every {@code /api/**} request must carry a valid JWT
     * from the configured issuer.
     */
    @Bean
    @Profile("!local")
    SecurityFilterChain apiSecurity(HttpSecurity http, RateLimitFilter rateLimit,
                                    @Value("${rover.identity.issuer}") String issuer)
            throws Exception {
        return http
                .csrf(csrf -> csrf.disable())   // stateless bearer-token API, no cookies
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Response headers, which Spring Security does not set on its own beyond
                // a small default set. This service returns the text of documents its
                // callers uploaded, and the one endpoint that serves bytes serves them
                // inline, so a browser can be asked to render caller-supplied content.
                .headers(headers -> headers
                        // Nothing here is a web page. A policy permitting no script, no
                        // frame and no plugin costs an API nothing and means a stored
                        // payload has no way to execute even if a response is opened
                        // directly. `frame-ancestors` is the header-only form of
                        // X-Frame-Options and covers the PDF endpoint too.
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'none'; frame-ancestors 'none'; "
                                        + "base-uri 'none'; form-action 'none'; sandbox"))
                        .frameOptions(frame -> frame.deny())
                        // Document titles and identifiers appear in paths, and a full URL
                        // sent as a Referer would carry them off-origin.
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000)))
                .authorizeHttpRequests(auth -> auth
                        // The container re-enters the filter chain to render an error, and
                        // that dispatch targets /error, which matches denyAll below. Left
                        // denied, a request that was authorised and then failed comes back
                        // as 401 with an empty body rather than its actual status, so a
                        // fault in the service is indistinguishable from an expired token.
                        // Measured: with /api/** deliberately opened, a request that throws
                        // returns 500 with a problem body here and an empty 401 without this
                        // line. Only the error dispatch is permitted; the original request
                        // has already been through the rules below by the time it reaches
                        // one.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").authenticated()
                        .requestMatchers("/api/**").authenticated()
                        // The MCP server is not under /api, so without this it falls to
                        // denyAll below and the tool surface is unreachable in every
                        // deployed environment. Authenticated like any other caller: an
                        // agent reaches exactly what the person it acts for can reach.
                        .requestMatchers("/mcp/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> {})
                        // RFC 9728. A refusal already carries WWW-Authenticate pointing at
                        // this document; serving it is what lets an agent go from "I was
                        // refused" to "here is where I get a token" without being told.
                        // That is the whole of the MCP authorization discovery chain, and
                        // it is why /mcp needs no configuration on the client beyond a URL.
                        .protectedResourceMetadata(metadata -> metadata
                                .protectedResourceMetadataCustomizer(document -> document
                                        .authorizationServer(issuer)
                                        .scope("openid")
                                        .scope("profile"))))
                // After authorization, so the limit applies to requests that were going to
                // be served. In front of it, a caller's allowance would be spent on
                // requests they were never permitted to make, and an expired token would
                // come back as 429.
                .addFilterAfter(rateLimit, AuthorizationFilter.class)
                .build();
    }

    /**
     * Local-only chain: no JWT validation, so the stack runs without Supabase
     * credentials during early development. Never active in a deployed environment —
     * see {@code application-local.yml}.
     */
    @Bean
    @Profile("local")
    SecurityFilterChain localSecurity(HttpSecurity http, RateLimitFilter rateLimit)
            throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                // Wired here as well, so the two chains differ only in what they
                // authenticate. The limit itself is switched off for this profile in
                // application-local.yml — the eval and load harnesses drive hundreds of
                // requests a minute from one address, and with no authentication to key
                // on they would all count as one caller. Wiring it here anyway is what
                // lets a test turn it back on with a property rather than by building a
                // chain the deployed profile does not use.
                .addFilterAfter(rateLimit, AuthorizationFilter.class)
                .build();
    }
}
