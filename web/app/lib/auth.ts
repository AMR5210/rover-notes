/**
 * Signing in, from a browser that cannot keep a secret.
 *
 * Authorization code with PKCE. The API registers this interface as a public client, so
 * there is no client secret to send and the code verifier is the only thing proving that
 * whoever redeems the code is whoever asked for it.
 *
 * Two consequences shape everything here, and both come from the same fact: a public
 * client receives no refresh token. Spring Authorization Server withholds one when the
 * client authenticated with `none`, which is deliberate — a refresh token is a long-lived
 * credential and a browser has nowhere safe to put it.
 *
 * So the access token is held **in memory only**. Not `localStorage`, which any injected
 * script can read, and not a cookie, which would have to be readable to be attached. A
 * reload loses it, and that is fine: the sign-in cookie on the authorization server
 * outlives the token, so a new one is obtained by re-running the authorization silently.
 * That re-run is what a refresh token would otherwise have done.
 *
 * Authentication is treated as optional rather than required. Where the API permits
 * unauthenticated requests — the local development profile does — the interface works
 * without ever signing in, and `token()` simply returns null.
 */

const CLIENT_ID = "rover-web";
const REDIRECT_PATH = "/auth/callback";
const SCOPE = "openid profile";

/** Where the verifier waits between leaving for the authorization server and coming back. */
const VERIFIER_KEY = "rover.pkce.verifier";

/** Where the interface was when it sent the caller away, so it can put them back. */
const RETURN_KEY = "rover.pkce.return";

let accessToken: string | null = null;
let expiresAt = 0;

/**
 * A silent attempt in flight, so ten failing requests produce one authorization rather
 * than ten. Every caller awaits the same promise.
 */
let pending: Promise<string | null> | null = null;

export interface Account {
  subject: string;
  email?: string;
}

function base64url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function randomVerifier(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return base64url(bytes);
}

async function challengeFor(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier));
  return base64url(new Uint8Array(digest));
}

function redirectUri(): string {
  return `${window.location.origin}${REDIRECT_PATH}`;
}

async function authorizeUrl(silent: boolean): Promise<string> {
  const verifier = randomVerifier();
  sessionStorage.setItem(VERIFIER_KEY, verifier);

  const params = new URLSearchParams({
    response_type: "code",
    client_id: CLIENT_ID,
    redirect_uri: redirectUri(),
    scope: SCOPE,
    code_challenge: await challengeFor(verifier),
    code_challenge_method: "S256",
  });
  // `prompt=none` asks the authorization server to answer from its session or not at all.
  // Spring Authorization Server implements it, but the authorization endpoint requires
  // authentication first, so an unauthenticated request is sent to the sign-in form by the
  // entry point before the parameter is ever read. It is still sent, because it is correct
  // and because it prevents an interactive prompt for a caller who *is* signed in; the
  // silent attempt does not depend on it. See attemptSilently.
  if (silent) params.set("prompt", "none");
  return `/oauth2/authorize?${params}`;
}

/** Sends the caller to sign in, remembering where they were. */
export async function signIn(returnTo: string = window.location.pathname): Promise<void> {
  sessionStorage.setItem(RETURN_KEY, returnTo);
  window.location.assign(await authorizeUrl(false));
}

/**
 * Ends the session on the authorization server as well as here.
 *
 * Dropping the token alone would leave the sign-in cookie in place, so the next silent
 * attempt would succeed and the caller would appear to be signed in again.
 */
export function signOut(): void {
  accessToken = null;
  expiresAt = 0;
  window.location.assign("/logout");
}

/**
 * Exchanges the code the authorization server sent back.
 *
 * @returns where the caller was before they were sent away
 */
export async function completeSignIn(code: string): Promise<string> {
  const verifier = sessionStorage.getItem(VERIFIER_KEY);
  sessionStorage.removeItem(VERIFIER_KEY);
  if (!verifier) {
    throw new Error("This sign-in did not start here, so it cannot be completed.");
  }

  const response = await fetch("/oauth2/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "authorization_code",
      code,
      redirect_uri: redirectUri(),
      client_id: CLIENT_ID,
      code_verifier: verifier,
    }),
  });
  if (!response.ok) {
    throw new Error((await response.text()) || `${response.status} ${response.statusText}`);
  }

  const issued = await response.json();
  accept(issued.access_token, issued.expires_in);

  const returnTo = sessionStorage.getItem(RETURN_KEY) ?? "/";
  sessionStorage.removeItem(RETURN_KEY);
  return returnTo;
}

function accept(token: string, expiresInSeconds: number): void {
  accessToken = token;
  // Treated as expired slightly early, so a token is not sent that will be rejected by
  // the time it arrives.
  expiresAt = Date.now() + Math.max(0, (expiresInSeconds - 30) * 1000);
}

/** The token to send, if one is held and still good. */
export function token(): string | null {
  return accessToken && Date.now() < expiresAt ? accessToken : null;
}

export function signedIn(): boolean {
  return token() !== null;
}

/**
 * Obtains a token without sending the caller anywhere, if the session allows it.
 *
 * Runs the authorization in a hidden frame. A session that covers this browser ends with
 * the frame on `/auth/callback`, which posts the code out rather than exchanging it there,
 * because a frame has no useful place to put a token. No session ends with the frame on the
 * sign-in form instead, and the frame is same-origin, so where it landed is readable from
 * here — which is the signal, rather than the `login_required` redirect that the entry
 * point pre-empts.
 *
 * Returns null rather than throwing when there is no session. Not being signed in is an
 * ordinary state here, not a failure.
 */
export async function silentSignIn(): Promise<string | null> {
  if (token()) return token();
  if (pending) return pending;

  pending = attemptSilently().finally(() => {
    pending = null;
  });
  return pending;
}

function attemptSilently(): Promise<string | null> {
  return new Promise((resolve) => {
    let settled = false;
    const finish = (value: string | null) => {
      if (settled) return;
      settled = true;
      window.removeEventListener("message", onMessage);
      clearTimeout(timer);
      frame.remove();
      resolve(value);
    };

    const onMessage = async (event: MessageEvent) => {
      if (event.origin !== window.location.origin) return;
      const data = event.data as { source?: string; code?: string };
      if (data?.source !== "rover-auth") return;
      if (!data.code) {
        finish(null);
        return;
      }
      try {
        await completeSignIn(data.code);
        finish(token());
      } catch {
        finish(null);
      }
    };

    const frame = document.createElement("iframe");
    frame.style.display = "none";
    // The frame is same-origin, so the callback page inside it can reach this window.
    frame.setAttribute("aria-hidden", "true");

    // Where the frame came to rest answers the question. Anything other than the callback
    // means there was no session to reuse: the sign-in form, or a location that cannot be
    // read at all because the response refused to be framed. Both are "not signed in".
    frame.addEventListener("load", () => {
      try {
        if (!frame.contentWindow?.location.pathname.startsWith(REDIRECT_PATH)) {
          finish(null);
        }
      } catch {
        finish(null);
      }
    });

    // A backstop for an attempt that neither loads nor errors, so a silent check can never
    // be what makes the interface feel slow.
    const timer = setTimeout(() => finish(null), 5_000);

    window.addEventListener("message", onMessage);
    void authorizeUrl(true).then((url) => {
      frame.src = url;
      document.body.appendChild(frame);
    });
  });
}

/** The account a token describes, read from its payload rather than from another request. */
export function account(): Account | null {
  const held = token();
  if (!held) return null;
  try {
    const payload = JSON.parse(
      atob(held.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")),
    ) as { sub: string; email?: string };
    return { subject: payload.sub, email: payload.email };
  } catch {
    // A token that cannot be read is still a token the API may accept; the interface just
    // has nothing to display about it. Claims are not trusted for anything but display.
    return null;
  }
}
