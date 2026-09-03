import { expect, test } from "@playwright/test";

/**
 * The landing page.
 *
 * Served statically and reading nothing from the API, so this is the one suite that does
 * not take the seeded fixture — it would create, index and delete two documents to check
 * a page that never queries them.
 *
 * What is worth holding is the route split. Moving Ask off `/` to make room for this page
 * means the root has to reach it, the section links have to point at the new path, and
 * neither is visible from the page's own markup.
 */
test.describe("landing", () => {
  test("states what the product is and offers a way in", async ({ page }) => {
    await page.goto("/");

    await expect(page.getByRole("heading", { level: 1 })).toContainText("Make your documents");
    await expect(page.getByRole("link", { name: "Try it now" }).first()).toHaveAttribute(
      "href",
      "/ask",
    );
    await expect(page.getByRole("link", { name: "Create an account" }).first()).toHaveAttribute(
      "href",
      "/account/register",
    );
    await expect(page.getByRole("button", { name: "Sign in" }).first()).toBeVisible();
  });

  test("the primary call to action reaches the asking interface", async ({ page }) => {
    await page.goto("/");
    await page.getByRole("link", { name: "Try it now" }).first().click();

    await expect(page).toHaveURL(/\/ask$/);
    await expect(page.getByLabel("Question")).toBeVisible();
  });

  test("the demo and the sections describing it are present", async ({ page }) => {
    await page.goto("/");

    // The recording is the only asset on the page, and a broken path would still render a
    // page that looks complete. Checking that it decoded is what catches that.
    const demo = page.locator("img.demo");
    await expect(demo).toBeVisible();
    expect(await demo.evaluate((node: HTMLImageElement) => node.naturalWidth)).toBeGreaterThan(0);

    for (const heading of ["How it works", "The questions it is built for", "What is underneath"]) {
      await expect(page.getByRole("heading", { name: heading })).toBeVisible();
    }
  });

  test("the section links point at Ask rather than at the root", async ({ page }) => {
    await page.goto("/ask");

    const ask = page.locator(".masthead nav a", { hasText: "Ask" });
    await expect(ask).toHaveAttribute("href", "/ask");
    await expect(ask).toHaveClass(/current/);

    // The brand still returns to the landing page, which is the only way back to it.
    await expect(page.locator(".masthead .brand")).toHaveAttribute("href", "/");
  });
});
