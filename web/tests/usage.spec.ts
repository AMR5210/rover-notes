import { API, expect, test } from "./fixtures";

/**
 * The usage page exists so a caller can see where they stand before a refusal tells them.
 *
 * Two things are worth checking and they need different setups. That the page reads the
 * API at all is checked against the live service. What it does with a non-trivial answer
 * is checked against a supplied one: CI makes no model calls, so live spend is
 * legitimately zero, and a page that rendered a hard-coded zero would pass a live check
 * unnoticed.
 */

const SUMMARY = {
  windowHours: 24,
  capUsd: 5.0,
  spentUsd: 1.25,
  remainingUsd: 3.75,
  calls: 1420,
  inputTokens: 1234567,
  outputTokens: 89012,
  byModel: [
    {
      modelId: "anthropic/claude-sonnet-5",
      calls: 1200,
      costUsd: 1.2,
      inputTokens: 1000000,
      outputTokens: 80000,
    },
    {
      modelId: "anthropic/claude-haiku-4-5",
      calls: 220,
      costUsd: 0.05,
      inputTokens: 234567,
      outputTokens: 9012,
    },
  ],
  daily: [
    { day: "2026-08-10", calls: 200, costUsd: 0.4 },
    { day: "2026-08-11", calls: 1220, costUsd: 0.85 },
  ],
};

/** Answers `/api/usage` with `body`, leaving every other request alone. */
async function withUsage(page: import("@playwright/test").Page, body: unknown) {
  await page.route("**/api/usage", (route) =>
    route.fulfill({ contentType: "application/json", body: JSON.stringify(body) }),
  );
}

test.describe("usage", () => {
  test("reports the same window, cap and spend the API does", async ({ page }) => {
    const summary = await (await fetch(`${API}/api/usage`)).json();

    await page.goto("/usage");
    await expect(page.getByTestId("usage-calls")).toHaveText(
      summary.calls.toLocaleString("en-US"),
    );
    await expect(page.locator(".usage-head p")).toContainText(
      `over the last ${summary.windowHours}h`,
    );
  });

  test("shows spend against the cap, and what is left of it", async ({ page }) => {
    await withUsage(page, SUMMARY);
    await page.goto("/usage");

    await expect(page.getByTestId("usage-spent")).toHaveText("$1.25");
    await expect(page.getByTestId("usage-remaining")).toHaveText("$3.75");
    await expect(page.getByTestId("usage-calls")).toHaveText("1,420");
    // The cap is what the next request is checked against, so the page has to name it.
    await expect(page.locator(".usage-head p")).toContainText("of $5.00 over the last 24h");
    // A quarter of the cap spent, reported to assistive technology as well as drawn.
    await expect(page.locator(".meter")).toHaveAttribute("aria-valuenow", "25");
  });

  test("breaks the window down by model and by day", async ({ page }) => {
    await withUsage(page, SUMMARY);
    await page.goto("/usage");

    const models = page.locator(".figures").first().locator("tbody tr");
    await expect(models).toHaveCount(2);
    await expect(models.first()).toContainText("anthropic/claude-sonnet-5");
    await expect(models.first()).toContainText("1,000,000");
    // Below a cent, two decimals would report every small model as free.
    await expect(models.nth(1)).toContainText("$0.05");

    const days = page.locator(".figures").nth(1).locator("tbody tr");
    await expect(days).toHaveCount(2);
    await expect(days.first()).toContainText("2026-08-10");
  });

  test("reports spend alone where no cap is configured", async ({ page }) => {
    await withUsage(page, { ...SUMMARY, capUsd: null, remainingUsd: null });
    await page.goto("/usage");

    await expect(page.locator(".usage-head p")).toContainText("no cap configured");
    await expect(page.locator(".meter")).toHaveCount(0);
    await expect(page.getByTestId("usage-spent")).toHaveText("$1.25");
  });

  test("fills the meter rather than overflowing it once the cap is passed", async ({ page }) => {
    await withUsage(page, { ...SUMMARY, spentUsd: 7.5, remainingUsd: 0 });
    await page.goto("/usage");

    await expect(page.locator(".meter")).toHaveAttribute("aria-valuenow", "100");
    await expect(page.getByTestId("usage-remaining")).toHaveText("$0.00");
  });

  test("is reachable from the masthead", async ({ page }) => {
    await page.goto("/notes");
    await page.getByRole("link", { name: "Usage" }).click();
    await expect(page).toHaveURL(/\/usage$/);
    await expect(page.getByTestId("usage-spent")).toBeVisible();
  });
});
