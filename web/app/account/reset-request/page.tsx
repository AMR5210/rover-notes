"use client";

import Link from "next/link";
import { useState } from "react";

import { requestReset } from "../../lib/account";

/**
 * Asking for a reset link.
 *
 * The same confirmation for an address with an account and one without, because the API
 * answers the same way for both and the point of that is lost if the interface does not.
 * An unknown address, a disabled account and a locked one all take the silent path.
 *
 * Requesting a reset does not change anything on its own. Nobody is locked out by someone
 * else asking for a link to their address; what arrives is a link, and only the mailbox's
 * owner can follow it.
 */
export default function ResetRequestPage() {
  const [email, setEmail] = useState("");
  const [sending, setSending] = useState(false);
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (sending || !email.trim()) return;

    setSending(true);
    setError(null);
    try {
      await requestReset(email.trim());
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
        <p data-testid="reset-requested">
          If that address has an account, a reset link is on its way to it. The link is
          good for an hour and can be used once.
        </p>
        <p className="hint">
          Your current password still works until you use the link, so nothing has changed
          if this was not you.
        </p>
      </div>
    );
  }

  return (
    <div className="column form-page">
      <h1>Reset your password</h1>
      <p>Tell us the address on the account and we will send a link to choose a new one.</p>

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

        <div className="composer-foot">
          <span className="hint">
            No account yet? <Link href="/account/register">Create one</Link>.
          </span>
          <button type="submit" disabled={sending} data-testid="request-reset">
            {sending ? "Sending…" : "Send the link"}
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
