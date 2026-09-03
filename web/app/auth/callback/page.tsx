"use client";

import { useEffect, useState } from "react";

import { completeSignIn } from "../../lib/auth";

/**
 * Where the authorization server sends the browser back to.
 *
 * Two arrivals land here and they are handled differently. A top-level one is a person who
 * has just signed in: the code is exchanged and they are returned to whatever page sent
 * them away. An arrival inside a hidden frame is a silent attempt, and the frame has
 * nowhere useful to keep a token, so it hands the code to the window that opened it and
 * lets that window do the exchange.
 *
 * `prompt=none` fails by redirecting here with `error=login_required` rather than by not
 * redirecting at all, so the failure case arrives the same way the success case does.
 */
export default function AuthCallbackPage() {
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const code = params.get("code");
    const failure = params.get("error");
    const framed = window.parent !== window;

    if (framed) {
      window.parent.postMessage(
        { source: "rover-auth", code: code ?? undefined, error: failure ?? undefined },
        window.location.origin,
      );
      return;
    }

    if (failure) {
      setError(
        failure === "login_required"
          ? "That sign-in did not complete. Try again from the sign-in link."
          : failure,
      );
      return;
    }
    if (!code) {
      setError("No authorization code came back, so there is nothing to complete.");
      return;
    }

    completeSignIn(code)
      .then((returnTo) => window.location.replace(returnTo))
      .catch((cause) => setError(cause instanceof Error ? cause.message : String(cause)));
  }, []);

  return (
    <div className="column">
      {error ? <p className="error">{error}</p> : <p className="muted">Completing sign-in…</p>}
    </div>
  );
}
