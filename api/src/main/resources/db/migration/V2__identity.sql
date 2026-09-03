-- Rover Notes 2.0 — identity
--
-- Brings authentication into this service. The API stops being only a resource server
-- and also becomes the authorization server that issues the tokens it validates.
-- See `docs/ARCHITECTURE.md`.
--
-- Two groups of tables, with different owners:
--
--   1. Identity: users, their authorities, and the single-use tokens behind email
--      verification and password reset. This project owns the shape.
--   2. OAuth2 protocol state: registered clients, authorizations, consents. Spring
--      Authorization Server owns the shape, and the DDL below is its shipped schema
--      with the adaptations its own header requires for PostgreSQL.
--
-- Owning the DDL here rather than letting a library create it keeps Flyway the single
-- source of truth, for the same reason the event_publication table is declared in V1.

create extension if not exists citext;

-- ============================================================ users
--
-- The subject of an issued token is this identifier, so it is the same value the
-- documents, chunks and llm_usage tables already carry as owner_id. Under docs/ARCHITECTURE.md
-- that was a foreign identifier from another system; it can now become a real foreign
-- key. That constraint is deliberately not added here — see the note at the end.

create table users (
    id                uuid        primary key default gen_random_uuid(),
    -- citext rather than text with a lower() index: an address differing only in case
    -- is the same address, and making the column say so means no call site can forget.
    email             citext      not null unique,
    -- Argon2id, encoded with its parameters, so the cost can be raised later without
    -- invalidating existing hashes. Never a plaintext or reversible value.
    password_hash     text        not null,
    display_name      text,
    -- Null until the address is proven. An unverified account can exist without being
    -- able to sign in, which is what makes a verification link meaningful.
    email_verified_at timestamptz,
    disabled_at       timestamptz,
    -- Counted on the row rather than derived from an attempt log, so the check on the
    -- login path is one indexed read rather than an aggregate over a growing table.
    failed_logins     int         not null default 0,
    locked_until      timestamptz,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now()
);

-- Sign-in reads by address, which is already covered by the unique constraint above.
-- This index serves the operational question instead: which accounts are locked now.
create index users_locked_idx on users (locked_until) where locked_until is not null;

create table user_authorities (
    user_id   uuid not null references users (id) on delete cascade,
    authority text not null,
    primary key (user_id, authority)
);

-- ============================================================ credential tokens
--
-- One table for both email verification and password reset. The two flows differ in
-- what they authorise, not in what they store: a single-use secret with an expiry,
-- addressed to one account.
--
-- The token itself is never stored. A SHA-256 digest of it is, so a database dump does
-- not hand over the ability to complete either flow. Verification hashes the presented
-- value and looks up the digest, which is also why the digest carries the unique
-- constraint rather than a separate identifier.

create table user_tokens (
    id          bigserial   primary key,
    user_id     uuid        not null references users (id) on delete cascade,
    purpose     text        not null,
    token_hash  bytea       not null unique,
    expires_at  timestamptz not null,
    -- Set when the token is redeemed. Presence, not deletion, records the redemption:
    -- a consumed token has to stay distinguishable from one that never existed, or a
    -- replayed link and a forged link report the same thing.
    consumed_at timestamptz,
    created_at  timestamptz not null default now(),

    constraint user_tokens_purpose_check
        check (purpose in ('email_verification', 'password_reset'))
);

-- Issuing a new token invalidates the outstanding ones for that purpose, which is a
-- lookup over exactly this partial index.
create index user_tokens_outstanding_idx
    on user_tokens (user_id, purpose) where consumed_at is null;

-- ============================================================ signing keys
--
-- The JWKS this server publishes and signs with. Held in the database rather than
-- generated at startup so that a restart does not invalidate every live token, and so
-- that two instances agree on which key signed what.
--
-- The private half is stored encrypted under a key supplied from the environment, via
-- pgcrypto, so a database dump on its own does not confer the ability to mint tokens.
-- Rotation is additive: a new row becomes the signing key while previous public keys
-- stay published until the tokens they signed have expired.

create table signing_keys (
    kid                   text        primary key,
    algorithm             text        not null default 'RS256',
    public_key            text        not null,
    private_key_encrypted bytea       not null,
    created_at            timestamptz not null default now(),
    -- Set when the key stops signing. It remains published until expires_at so that
    -- tokens already issued under it continue to validate.
    retired_at            timestamptz,
    expires_at            timestamptz not null
);

create index signing_keys_active_idx on signing_keys (created_at desc) where retired_at is null;

-- ============================================================ OAuth2 protocol state
--
-- Spring Authorization Server 7.1.0's shipped schema, from
-- org/springframework/security/oauth2/server/authorization/*.sql inside the jar, with
-- the two changes its own header calls for on PostgreSQL: blob becomes text, and
-- timestamp becomes timestamptz so instants are stored accurately.
--
-- An upgrade that changes this schema needs a matching migration, the same rule the
-- Modulith event_publication table follows.

create table oauth2_registered_client (
    id                            varchar(100)  not null,
    client_id                     varchar(100)  not null,
    client_id_issued_at           timestamptz   not null default current_timestamp,
    client_secret                 varchar(200)  default null,
    client_secret_expires_at      timestamptz   default null,
    client_name                   varchar(200)  not null,
    client_authentication_methods varchar(1000) not null,
    authorization_grant_types     varchar(1000) not null,
    redirect_uris                 varchar(1000) default null,
    post_logout_redirect_uris     varchar(1000) default null,
    scopes                        varchar(1000) not null,
    client_settings               varchar(2000) not null,
    token_settings                varchar(2000) not null,
    primary key (id)
);

create table oauth2_authorization (
    id                            varchar(100)  not null,
    registered_client_id          varchar(100)  not null,
    principal_name                varchar(200)  not null,
    authorization_grant_type      varchar(100)  not null,
    authorized_scopes             varchar(1000) default null,
    attributes                    text          default null,
    state                         varchar(500)  default null,
    authorization_code_value      text          default null,
    authorization_code_issued_at  timestamptz   default null,
    authorization_code_expires_at timestamptz   default null,
    authorization_code_metadata   text          default null,
    access_token_value            text          default null,
    access_token_issued_at        timestamptz   default null,
    access_token_expires_at       timestamptz   default null,
    access_token_metadata         text          default null,
    access_token_type             varchar(100)  default null,
    access_token_scopes           varchar(1000) default null,
    oidc_id_token_value           text          default null,
    oidc_id_token_issued_at       timestamptz   default null,
    oidc_id_token_expires_at      timestamptz   default null,
    oidc_id_token_metadata        text          default null,
    refresh_token_value           text          default null,
    refresh_token_issued_at       timestamptz   default null,
    refresh_token_expires_at      timestamptz   default null,
    refresh_token_metadata        text          default null,
    user_code_value               text          default null,
    user_code_issued_at           timestamptz   default null,
    user_code_expires_at          timestamptz   default null,
    user_code_metadata            text          default null,
    device_code_value             text          default null,
    device_code_issued_at         timestamptz   default null,
    device_code_expires_at        timestamptz   default null,
    device_code_metadata          text          default null,
    primary key (id)
);

-- Not in the shipped schema. Expired authorizations accumulate at one row per sign-in,
-- and reclaiming them is a scan without this.
create index oauth2_authorization_expiry_idx
    on oauth2_authorization (access_token_expires_at);

create index oauth2_authorization_principal_idx
    on oauth2_authorization (principal_name);

create table oauth2_authorization_consent (
    registered_client_id varchar(100)  not null,
    principal_name       varchar(200)  not null,
    authorities          varchar(1000) not null,
    primary key (registered_client_id, principal_name)
);

-- ============================================================ what is not here yet
--
-- owner_id on documents, chunks, entities, llm_usage and user_settings is not yet a
-- foreign key to users (id). The constraint is the point of holding identity locally
-- and it is deliberately deferred by one migration: the local profile attributes every
-- request to a fixed development owner that has no users row, so adding the constraint
-- before that owner is seeded would fail every integration test at startup.
--
-- It lands with the migration that adds the sign-in flow, alongside a local-only seed
-- supplied from a second Flyway location rather than from this file, so a development
-- account is never created in a deployed environment.
