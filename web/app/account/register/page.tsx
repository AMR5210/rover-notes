"use client";

import Link from "next/link";
import { useState } from "react";

import { register } from "../../lib/account";

/** The API's own minimum, repeated here so the rule is visible before it is broken. */
const MINIMUM_PASSWORD = 12;

/**
 * Creating an account.
 *
 * The confirmation deliberately says the same thing whether or not an account was created.
 * The API answers 202 for an address that is already registered and sends that address a
 * message saying someone tried; a page that reported "this address is taken" would hand
 * back exactly the fact the endpoint is built not to disclose.
 *
 * Length is the only password rule. Composition requirements push people towards short
 * passwords built to satisfy a checker, which is why NIST dropped them, and a long
 * password costs no more to store under Argon2 than a short one.
 */
export default function RegisterPage() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [sending, setSending] = useState(false);
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const tooShort = password.length > 0 && password.length < MINIMUM_PASSWORD;

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (sending || !email.trim() || password.length < MINIMUM_PASSWORD) return;

    setSending(true);
    setError(null);
    try {
      await register(email.trim(), password, displayName.trim());
      setSent(true);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setSending(false);
    }
  }

  if (sent) {
    return (
      <div className="column form-page">
        <h1>Check your email</h1>
        <p data-testid="registered">
          If that address can have an account, a message is on its way to it. The link
          inside is good for a day and can be used once.
        </p>
        <p className="hint">
          Nothing on this page can tell you whether the address was already registered.
          That is deliberate: an interface that could would answer the same question for
          anyone who asked.
        </p>
      </div>
    );
  }

  return (
    <div className="column form-page">
      <h1>Create an account</h1>

      <form className="composer" onSubmit={submit}>
        <label htmlFor="email">Email</label>
        <input
          id="email"
          type="email"
          required
          autoComplete="email"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          data-testid="email"
        />

        <label htmlFor="password">Password</label>
        <input
          id="password"
          type="password"
          required
          minLength={MINIMUM_PASSWORD}
          autoComplete="new-password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          aria-describedby="password-hint"
          data-testid="password"
        />
        <p className="hint" id="password-hint">
          {tooShort
            ? `${MINIMUM_PASSWORD - password.length} more characters needed.`
            : `At least ${MINIMUM_PASSWORD} characters. Length is the only rule; a phrase you can remember beats a short one you cannot.`}
        </p>

        <label htmlFor="display-name">Display name</label>
        <input
          id="display-name"
          type="text"
          maxLength={100}
          autoComplete="name"
          value={displayName}
          onChange={(event) => setDisplayName(event.target.value)}
          data-testid="display-name"
        />

        <div className="composer-foot">
          <span className="hint">
            Already have one? <Link href="/account/reset-request">Reset your password</Link>.
          </span>
          <button type="submit" disabled={sending} data-testid="register">
            {sending ? "Creating…" : "Create account"}
          </button>
        </div>
      </form>

      {error && (
        <p className="error" role="alert" data-testid="error">
          {error}
        </p>
      )}
    </div>
  );
}
