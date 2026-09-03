"use client";

import Link from "next/link";
import { useEffect, useState } from "react";

import { account, signIn, signOut, silentSignIn, type Account } from "../lib/auth";

/**
 * Who the interface is acting as, and how to change it.
 *
 * Attempts a silent sign-in once on load. That is not a login prompt: it asks the
 * authorization server whether an existing session covers this browser and accepts a no.
 * The token itself lives only in memory, so this is also what restores a session after a
 * reload.
 *
 * Nothing here blocks the rest of the interface. Where the API permits unauthenticated
 * requests — the local development profile does — a signed-out state is a working state,
 * and presenting a wall would be reporting a problem the system does not have.
 */
export function AccountBadge() {
  const [who, setWho] = useState<Account | null>(null);
  const [checked, setChecked] = useState(false);

  useEffect(() => {
    let live = true;
    silentSignIn()
      .then(() => {
        if (!live) return;
        setWho(account());
        setChecked(true);
      })
      .catch(() => live && setChecked(true));
    return () => {
      live = false;
    };
  }, []);

  if (!checked) {
    // Deliberately empty rather than a spinner. The check is one redirect against a cookie
    // and usually finishes before it could be seen; a flash of loading state on every page
    // would be more noticeable than the thing it reports.
    return <span className="account" aria-live="polite" />;
  }

  if (!who) {
    // Registering is offered alongside signing in, because the sign-in form is served by
    // the authorization server and has nowhere to put a link of this application's. Left
    // out, the pages that create and recover an account would be reachable only from a
    // link in an email, which is no use to someone who does not have one yet.
    return (
      <span className="account">
        <button className="linklike" onClick={() => void signIn()} data-testid="sign-in">
          Sign in
        </button>
        <Link className="linklike" href="/account/register" data-testid="register-link">
          Create account
        </Link>
      </span>
    );
  }

  return (
    <span className="account">
      <span className="account-name" title={who.subject} data-testid="account">
        {who.email ?? who.subject}
      </span>
      <button className="linklike" onClick={() => signOut()} data-testid="sign-out">
        Sign out
      </button>
    </span>
  );
}
