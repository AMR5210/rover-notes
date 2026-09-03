import { expect, test } from "./fixtures";

/**
 * Signing in from the interface.
 *
 * The grant itself is covered on the API side, where a token can be obtained and used
 * against the chain that validates one. What can only be checked here is the browser's
 * half: that a signed-out interface is a working interface, that the silent check settles
 * quickly rather than hanging, that the authorization request is formed with PKCE, and
 * that the redirect chain survives the proxy in front of it.
 *
 * These run against the local profile, where the API attributes every request to a fixed
 * owner and permits it. That is the condition the interface has to tolerate: authentication
 * is optional, and a signed-out state is not an error state.
 */
test.describe("sign-in", () => {
  test("reports a signed-out session rather than blocking the interface", async ({ page }) => {
    await page.goto("/notes");

    await expect(page.getByTestId("sign-in")).toBeVisible();
    await expect(page.getByTestId("account")).toHaveCount(0);
  });

  test("the silent check settles quickly instead of holding the page", async ({ page }) => {
    // The check runs in a hidden frame that lands on the sign-in form when there is no
    // session. Nothing posts a message back in that case, so if the interface waited for
    // one it would wait for the timeout on every load.
    await page.goto("/notes");

    const started = Date.now();
    await expect(page.getByTestId("sign-in")).toBeVisible({ timeout: 4000 });
    expect(Date.now() - started).toBeLessThan(4000);
  });

  test("the corpus is usable while signed out", async ({ page, seeded }) => {
    // The API permits unauthenticated requests under this profile, so a signed-out
    // interface is fully working. Presenting a wall here would report a problem the system
    // does not have.
    await page.goto("/notes");

    await expect(page.getByTestId("sign-in")).toBeVisible();
    await expect(page.locator(".notes li", { hasText: "freight-scheduling" })).toBeVisible();
  });

  test("sign in asks for an authorization code with PKCE", async ({ page }) => {
    await page.goto("/notes");
    await expect(page.getByTestId("sign-in")).toBeVisible();

    const authorize = page.waitForRequest((request) =>
      request.url().includes("/oauth2/authorize") && !request.url().includes("prompt=none"),
    );
    await page.getByTestId("sign-in").click();
    const url = new URL((await authorize).url());

    expect(url.searchParams.get("response_type")).toBe("code");
    expect(url.searchParams.get("client_id")).toBe("rover-web");
    // The challenge is what makes an intercepted code useless on its own, and S256 rather
    // than plain is what makes the challenge worth sending.
    expect(url.searchParams.get("code_challenge_method")).toBe("S256");
    expect(url.searchParams.get("code_challenge")).toBeTruthy();
    expect(url.searchParams.get("redirect_uri")).toContain("/auth/callback");
  });

  test("sign in reaches a password form through the proxy", async ({ page }) => {
    // The whole redirect chain in one assertion: the interface's origin proxies
    // /oauth2/authorize to the API, the API sends an unauthenticated caller to /login, and
    // that is proxied too. A missing rewrite breaks this and nothing else notices.
    await page.goto("/notes");
    await expect(page.getByTestId("sign-in")).toBeVisible();

    await page.getByTestId("sign-in").click();
    await page.waitForURL(/\/login/);

    await expect(page.locator('input[name="username"]')).toBeVisible();
    await expect(page.locator('input[name="password"]')).toBeVisible();
  });

  test("the callback page explains an arrival it cannot complete", async ({ page }) => {
    // Reached directly, with no verifier in this session. Better to say so than to show a
    // spinner that never resolves.
    await page.goto("/auth/callback?code=not-a-real-code");

    await expect(page.locator(".error")).toBeVisible();
  });

  test("the callback page reports a failed sign-in rather than a blank screen", async ({ page }) => {
    await page.goto("/auth/callback?error=login_required");

    await expect(page.locator(".error")).toContainText("did not complete");
  });
});
