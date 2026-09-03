/**
 * The four calls a caller makes before they have a token.
 *
 * Separate from `api.ts` because nothing here is authorized: there is no bearer token to
 * attach and no 401 to retry with a fresh one, which is the whole of what that module's
 * request helper does. Sharing it would mean a silent sign-in attempt on the very requests
 * made by someone who has no account yet.
 *
 * The paths are same-origin and proxied to the Spring service by `next.config.ts`, for the
 * same reason the OAuth endpoints are: one origin keeps the sign-in cookie first-party.
 */

/** What the API said when it refused, or a description of a refusal with no body. */
async function post(path: string, body: unknown): Promise<Response> {
  const response = await fetch(path, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    // 429 is the one refusal a person is expected to meet here. Registration and reset
    // are limited by client address, so the message says to wait rather than implying the
    // details were wrong.
    if (response.status === 429) {
      const seconds = Number(response.headers.get("retry-after") ?? 60);
      throw new Error(
        `Too many attempts from this connection. Try again in ${seconds} second${
          seconds === 1 ? "" : "s"
        }.`,
      );
    }
    throw new Error((await response.text()) || `${response.status} ${response.statusText}`);
  }
  return response;
}

/**
 * Creates an account, as far as anyone outside can tell.
 *
 * Resolves the same way for an address that is already registered as for one that is not:
 * the API answers 202 either way and puts the difference in the message it sends. The
 * interface has to say the same thing in both cases or it would report what the API is
 * careful not to.
 */
export async function register(
  email: string,
  password: string,
  displayName: string,
): Promise<void> {
  await post("/auth/register", { email, password, displayName });
}

/** Asks for a reset link. Also deliberately identical for an address with no account. */
export async function requestReset(email: string): Promise<void> {
  await post("/auth/reset-request", { email });
}

/**
 * Redeems a link.
 *
 * Reports whether the token was good, which is not a disclosure — the caller is holding
 * it, so the only thing revealed is whether what they already have still works. A page
 * that showed success for a link that did nothing would be worse.
 */
export async function verifyEmail(token: string): Promise<boolean> {
  return ((await (await post("/auth/verify", { token })).json()) as { ok: boolean }).ok;
}

export async function resetPassword(token: string, password: string): Promise<boolean> {
  return ((await (await post("/auth/reset", { token, password })).json()) as { ok: boolean }).ok;
}
