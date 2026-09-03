import { expect, test } from "./fixtures";

/**
 * Creating and recovering an account, from the interface.
 *
 * The flows themselves are covered on the API side, where a token can be issued and a
 * mailbox inspected. What only a browser can check is the part these pages exist for: that
 * the token in a link is read out of the query and submitted without being shown, that a
 * link which no longer works says so rather than reporting success, and — the property the
 * API is built around and an interface can easily give away — that the confirmation after
 * registering is the same whether or not the address already had an account.
 *
 * These run against the local profile, where mail is written to the log rather than sent.
 * Nothing here reads a message; the tokens are supplied directly, which is what lets the
 * spent-link and missing-token cases be exercised at all.
 */
test.describe("account", () => {
  /** A fresh address per run, so a re-run is not the already-registered case by accident. */
  const address = () => `browser-${Date.now()}-${Math.random().toString(36).slice(2)}@test.invalid`;

  test("registering says a message is on its way", async ({ page }) => {
    await page.goto("/account/register");

    await page.getByTestId("email").fill(address());
    await page.getByTestId("password").fill("a-long-enough-password");
    await page.getByTestId("display-name").fill("Browser test");
    await page.getByTestId("register").click();

    await expect(page.getByTestId("registered")).toBeVisible();
  });

  test("an address that already has an account gets the same answer", async ({ page }) => {
    // The property the endpoint is built around: 202 either way, with the difference
    // carried by the message rather than the response. An interface that reported "this
    // address is taken" would hand back the fact the API withholds, and it would do it
    // without any change on the API side — which is why this is asserted here.
    const taken = address();

    for (const attempt of [1, 2]) {
      await page.goto("/account/register");
      await page.getByTestId("email").fill(taken);
      await page.getByTestId("password").fill("a-long-enough-password");
      await page.getByTestId("register").click();

      await expect(page.getByTestId("registered"), `attempt ${attempt}`).toBeVisible();
      await expect(page.getByTestId("error")).toHaveCount(0);
    }
  });

  test("a password below the minimum is refused before it is sent", async ({ page }) => {
    let posted = false;
    await page.route("**/auth/register", async (route) => {
      posted = true;
      await route.abort();
    });

    await page.goto("/account/register");
    await page.getByTestId("email").fill(address());
    await page.getByTestId("password").fill("short");
    await page.getByTestId("register").click();

    await expect(page.getByTestId("registered")).toHaveCount(0);
    expect(posted).toBe(false);
  });

  test("a confirmation link that no longer works says so", async ({ page }) => {
    // Not a disclosure: whoever reads this page is holding the token, so the only thing
    // revealed is whether what they have still works. Reporting success for a link that
    // did nothing would leave someone believing their address was confirmed.
    await page.goto("/account/verify?token=not-a-token-that-was-ever-issued");

    await expect(page.getByTestId("spent")).toBeVisible();
    await expect(page.getByTestId("confirmed")).toHaveCount(0);
  });

  test("a confirmation link with no token is reported rather than submitted", async ({ page }) => {
    let posted = false;
    await page.route("**/auth/verify", async (route) => {
      posted = true;
      await route.abort();
    });

    await page.goto("/account/verify");

    await expect(page.getByTestId("error")).toBeVisible();
    expect(posted).toBe(false);
  });

  test("asking for a reset says a message is on its way", async ({ page }) => {
    await page.goto("/account/reset-request");

    await page.getByTestId("email").fill(address());
    await page.getByTestId("request-reset").click();

    // An address with no account takes the silent path on the API and reaches this same
    // page, which is the whole point: the confirmation cannot be read as an answer to
    // "is this address registered".
    await expect(page.getByTestId("reset-requested")).toBeVisible();
  });

  test("the reset page submits the token from the link without showing it", async ({ page }) => {
    const token = "a-token-from-a-link";
    const sent = page.waitForRequest((request) =>
      request.url().includes("/auth/reset") && request.method() === "POST");

    await page.goto(`/account/reset?token=${token}`);
    await page.getByTestId("password").fill("another-long-password");
    await page.getByTestId("reset").click();

    expect(JSON.parse((await sent).postData() ?? "{}")).toMatchObject({ token });
    // The token is a live credential for as long as it is valid, so it belongs in the
    // request body and nowhere a person or a screenshot can pick it up.
    await expect(page.locator("body")).not.toContainText(token);
  });

  test("a reset link that no longer works leaves the password alone", async ({ page }) => {
    await page.goto("/account/reset?token=not-a-token-that-was-ever-issued");
    await page.getByTestId("password").fill("another-long-password");
    await page.getByTestId("reset").click();

    await expect(page.getByTestId("reset-spent")).toBeVisible();
  });

  test("a reset link with no token offers a new one instead of a form", async ({ page }) => {
    await page.goto("/account/reset");

    await expect(page.getByTestId("error")).toBeVisible();
    await expect(page.getByTestId("password")).toHaveCount(0);
  });

  test("a signed-out interface offers a way to create an account", async ({ page }) => {
    // The sign-in form is served by the authorization server and has nowhere to put a link
    // of this application's, so without this the account pages are reachable only from an
    // email — which is no use to someone who does not have one yet.
    await page.goto("/notes");

    await page.getByTestId("register-link").click();

    await expect(page).toHaveURL(/\/account\/register$/);
    await expect(page.getByTestId("email")).toBeVisible();
  });
});
