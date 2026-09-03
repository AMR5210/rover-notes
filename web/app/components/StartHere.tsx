"use client";

import Link from "next/link";

import { signIn } from "../lib/auth";

/**
 * The landing page's call to action.
 *
 * Three routes in, in the order someone is likely to want them: read the corpus without
 * an account, sign in to an existing one, or create one. Signing in is a redirect to the
 * authorization server rather than a page of this application, so it has to be a control
 * on the client; the other two are ordinary links and stay links.
 */
export function StartHere({ tone = "solid" }: { tone?: "solid" | "quiet" }) {
  return (
    <div className={`cta cta-${tone}`}>
      <Link className="cta-primary" href="/ask">
        Try it now
      </Link>
      <button className="cta-secondary" type="button" onClick={() => void signIn()}>
        Sign in
      </button>
      <Link className="cta-secondary" href="/account/register">
        Create an account
      </Link>
    </div>
  );
}
