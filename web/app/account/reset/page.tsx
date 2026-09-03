"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";

import { resetPassword } from "../../lib/account";

const MINIMUM_PASSWORD = 12;

/**
 * Choosing a new password, with the token from the link.
 *
 * Unlike confirmation, this one waits: the token alone is not enough, because a password
 * has to be typed. The token is never shown and never put anywhere but the request body —
 * it is a live credential for as long as it is valid, which is why the API gives it an
 * hour where a confirmation link gets a day.
 *
 * A successful reset also clears any lockout. Someone who forgot their password is the
 * same person a lockout after ten wrong guesses was protecting, and making them wait out
 * the lock after proving they hold the mailbox would be protecting them from themselves.
 */
function Reset() {
  const token = useSearchParams().get("token");
  const [password, setPassword] = useState("");
  const [sending, setSending] = useState(false);
  const [done, setDone] = useState<boolean | null>(null);
  const [error, setError] = useState<string | null>(null);

  const tooShort = password.length > 0 && password.length < MINIMUM_PASSWORD;

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (sending || !token || password.length < MINIMUM_PASSWORD) return;

    setSending(true);
    setError(null);
    try {
      setDone(await resetPassword(token, password));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setSending(false);
    }
  }

  if (!token) {
    return (
      <>
        <h1>This link is incomplete</h1>
        <p role="alert" data-testid="error">
          The link is missing the token that identifies it. Ask for a new one and follow it
          from the message rather than retyping it.
        </p>
        <p>
          <Link href="/account/reset-request">Send another link</Link>
        </p>
      </>
    );
  }

  if (done === true) {
    return (
      <>
        <h1>Password changed</h1>
        <p data-testid="reset-done">
          Your new password is in place and any lockout on the account has been cleared.
        </p>
        <p>
          <Link href="/">Go to the app</Link>
        </p>
      </>
    );
  }

  if (done === false) {
    return (
      <>
        <h1>This link no longer works</h1>
        <p data-testid="reset-spent">
          A reset link is good for an hour and can be used once, so this one has either
          expired or already been used. Your password has not changed.
        </p>
        <p>
          <Link href="/account/reset-request">Send another link</Link>
        </p>
      </>
    );
  }

  return (
    <>
      <h1>Choose a new password</h1>

      <form className="composer" onSubmit={submit}>
        <label htmlFor="password">New password</label>
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
            : `At least ${MINIMUM_PASSWORD} characters.`}
        </p>

        <div className="composer-foot">
          <span className="hint">The link is good for an hour and can be used once.</span>
          <button type="submit" disabled={sending} data-testid="reset">
            {sending ? "Changing…" : "Change password"}
          </button>
        </div>
      </form>

      {error && (
        <p className="error" role="alert" data-testid="error">
          {error}
        </p>
      )}
    </>
  );
}

export default function ResetPage() {
  return (
    <div className="column form-page">
      <Suspense fallback={<p>Loading…</p>}>
        <Reset />
      </Suspense>
    </div>
  );
}
