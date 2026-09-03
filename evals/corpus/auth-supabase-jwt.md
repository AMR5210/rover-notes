# Authentication

Supabase Auth issues the tokens. The Spring Boot API validates them as an OAuth2
resource server and stores no credentials of its own.

Validation is configured from a single issuer URI. Spring Security fetches the JWKS from
the issuer's discovery document, caches the keys for 5 minutes, and on every request
checks the RS256 signature, the exp and nbf claims, and the audience. No token is issued,
refreshed, or revoked in application code.

The sub claim becomes owner_id on every row a request touches. Because identity lives in
a separate system from the data, owner_id is a plain UUID column rather than a foreign
key, and removing a user is an application-level cascade rather than a database one.

Changing provider is an issuer-URI change, since the API only ever sees a
standards-compliant JWT. A provider outage blocks new sign-ins while tokens already
issued keep working until they expire, which is one hour by default; that window is the
practical bound on how long an outage stays invisible to active sessions.
