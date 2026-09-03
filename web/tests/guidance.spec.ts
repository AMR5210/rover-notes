import { expect, test } from "./fixtures";

/**
 * What each page says it is for.
 *
 * The interface was legible to whoever built it and opaque to everybody else: three text
 * boxes, no statement of what they did or which to use first. These hold the parts that
 * fixed that, because every one of them is the kind of copy that gets quietly dropped in
 * a refactor and is not missed by any other test.
 */
test.describe("guidance", () => {
  test("asking explains itself and offers questions to start from", async ({
    page,
    seeded,
  }) => {
    await page.goto("/ask");

    await expect(page.locator(".firstrun-lede")).toContainText("documents in your library");
    // The promise the whole product rests on has to be on the page somebody reads first.
    await expect(page.locator(".firstrun-lede")).toContainText("says so instead of guessing");

    const examples = page.locator(".examples-inline button");
    await expect(examples.first()).toBeVisible();

    // Suggestions are built from documents that are actually loaded, so a fixed example
    // naming a document nobody has cannot pass this.
    await expect(examples.first()).toContainText(/freight-scheduling|depot-inventory/);
  });

  test("a suggested question fills the box rather than submitting", async ({
    page,
    seeded,
  }) => {
    await page.goto("/ask");

    const first = page.locator(".examples-inline button").first();
    const text = (await first.textContent()) ?? "";
    await first.click();

    await expect(page.getByLabel("Question")).toHaveValue(text.trim());
    // Nothing was asked: pressing a suggestion should not spend a model call.
    await expect(page.locator(".answer")).toHaveCount(0);
    await expect(page.locator(".sources")).toHaveCount(0);
  });

  test("search says what it is, and how it differs from asking", async ({ page, seeded }) => {
    await page.goto("/search");

    await expect(page.getByRole("heading", { name: "Search your library" })).toBeVisible();
    await expect(page.locator(".page-head")).toContainText("no answer written over them");
    await expect(page.locator(".page-head a")).toHaveAttribute("href", "/ask");
  });

  test("the retrieval channel is available but not in the way", async ({ page, seeded }) => {
    await page.goto("/search");

    // Someone searching their own documents has no basis for choosing between three
    // retrieval strategies. It stays reachable for anyone who wants to know why a result
    // ranked where it did.
    await expect(page.getByLabel("Channel")).toBeHidden();
    await page.getByText("Advanced options").click();
    await expect(page.getByLabel("Channel")).toBeVisible();
  });

  test("the library is named for what it holds", async ({ page, seeded }) => {
    await page.goto("/notes");

    const link = page.locator(".masthead nav a", { hasText: "Library" });
    await expect(link).toHaveAttribute("href", "/notes");
    await expect(link).toHaveClass(/current/);
  });
});
