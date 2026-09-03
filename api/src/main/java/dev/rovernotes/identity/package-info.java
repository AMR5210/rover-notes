/**
 * Accounts, credentials, and the tokens this service issues for them.
 *
 * <p>Its own module, and the only one that reaches the {@code users} and
 * {@code signing_keys} tables. That boundary is the reason an authorization server can
 * live in the same deployable as the resource server it protects: {@code ModularityTests}
 * fails the build if another module reaches the signing keys, which is a checked barrier
 * rather than a convention. See {@code docs/ARCHITECTURE.md} for the module boundaries and
 * for why identity is issued here.
 *
 * <p>The protocol itself is not implemented here. Authorization code with PKCE, token
 * issuance, refresh, JWKS publication and discovery metadata come from Spring
 * Authorization Server. What this module owns is the user lifecycle and the key material.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Identity")
package dev.rovernotes.identity;
