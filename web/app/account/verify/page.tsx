"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState } from "react";

import { verifyEmail } from "../../lib/account";

type State = "working" | "confirmed" | "spent" | "failed";

/**
 * Where a confirmation link lands.
 *
 * The link is in a message, so the token arrives in the query string and the page redeems
 * it without being asked. There is nothing for a person to decide here — they have already
 * decided by following the link — and a page that made them press a button would only be
 * adding a step to a flow whose whole purpose is proving they can read their own mail.
 *
 * A token that does not work is reported plainly. It is not a disclosure: whoever is
 * reading this page is holding the token, so the only thing being revealed is whether the
 * thing they already have is still good. Showing success for a link that did nothing would
 * leave someone believing their address was confirmed when it was not.
 */
function Verify() {
  const token = useSearchParams().get("token");
  const [state, setState] = useState<State>("working");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!token) {
      setState("failed");
      return;
    }
    let live = true;
    verifyEmail(token)
      .then((ok) => live && setState(ok ? "confirmed" : "spent"))
      .catch((cause: unknown) => {
        if (!live) return;
        setError(cause instanceof Error ? cause.message : String(cause));
        setState("failed");
      });
    return () => {
      live = false;
    };
  }, [token]);

  if (state === "working") {
    return <p data-testid="verifying">Confirming your address…</p>;
  }

  if (state === "confirmed") {
    return (
      <>
        <h1>Address confirmed</h1>
        <p data-testid="confirmed">Your account is ready. You can sign in now.</p>
        <p>
          <Link href="/">Go to the app</Link>
        </p>
      </>
    );
  }

  if (state === "spent") {
    return (
      <>
        <h1>This link no longer works</h1>
        <p data-testid="spent">
          A confirmation link is good for a day and can be used once, so this one has
          either expired or already been used. If your address is confirmed you can sign
          in; if not, registering again sends a new link.
        </p>
        <p>
          <Link href="/account/register">Register again</Link>
        </p>
      </>
    );
  }

  return (
    <>
      <h1>This link could not be checked</h1>
      <p role="alert" data-testid="error">
        {error ?? "The link is missing the token that identifies it."}
      </p>
    </>
  );
}

export default function VerifyPage() {
  return (
    <div className="column form-page">
      {/* useSearchParams reads a value that is only known in the browser, so this subtree
          cannot be rendered on the server. The boundary is what keeps the rest of the page
          static rather than pushing the whole route to the client. */}
      <Suspense fallback={<p>Confirming your address…</p>}>
        <Verify />
      </Suspense>
    </div>
  );
}
