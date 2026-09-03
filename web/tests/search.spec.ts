import { expect, test } from "./fixtures";

test.describe("search", () => {
  test("reports the channel that answered", async ({ page, seeded }) => {
    await page.goto("/search");
    await page.getByLabel("Search query").fill("how are loads assigned");
    await page.getByRole("button", { name: "Search" }).click();

    // The badge is not decoration. A degraded request answers from the lexical channel
    // and says so, which is the difference between a thin result and a silent failure.
    await expect(page.locator(".badge").first()).toHaveText(/HYBRID|DENSE|LEXICAL/);
    await expect(page.locator(".hit")).not.toHaveCount(0);
  });

  test("a named channel is honoured", async ({ page, seeded }) => {
    await page.goto("/search");
    await page.getByLabel("Search query").fill("how are loads assigned");
    await page.getByText("Advanced options").click();
    await page.getByLabel("Channel").selectOption("lexical");
    await page.getByRole("button", { name: "Search" }).click();

    await expect(page.locator(".badge").first()).toHaveText("LEXICAL");
  });

  test("results carry a rank, a score and a span", async ({ page, seeded }) => {
    await page.goto("/search");
    await page.getByLabel("Search query").fill("deadlock recovery");
    await page.getByRole("button", { name: "Search" }).click();

    const first = page.locator(".hit").first();
    await expect(first.locator(".rank")).toHaveText("1");
    await expect(first.locator(".score")).toHaveText(/^\d+\.\d{4}$/);
    await expect(first.locator(".hit-span")).toHaveText(/characters \d+ to \d+/);
  });

  test("a query matching nothing says so rather than showing an empty list", async ({
    page,
    seeded,
  }) => {
    // Scoped to the lexical channel deliberately. The dense channel ranks by distance and
    // always returns its nearest neighbours, so a fused search answers every query with
    // something and this state is unreachable there. Only a channel that matches terms
    // can return nothing at all.
    await page.goto("/search");
    await page.getByLabel("Search query").fill("zzzqqxunlikelytoken");
    await page.getByText("Advanced options").click();
    await page.getByLabel("Channel").selectOption("lexical");
    await page.getByRole("button", { name: "Search" }).click();

    await expect(page.getByText(/Nothing matched/)).toBeVisible();
  });
});
