import { API, expect, test } from "./fixtures";

/**
 * The answer path, which needs a model.
 *
 * These are skipped where no key is configured, which is the case in continuous
 * integration. The checks that protect the span contract do not depend on generation and
 * run everywhere; what is only covered here is the streaming order and the rendering of
 * citations as controls.
 */
/**
 * Probed once per run and reused.
 *
 * A successful status is not sufficient on its own. Where retrieval finds nothing the
 * service returns a fixed sentence and no citations without calling a model at all, so an
 * earlier version of this check read that 200 as a working model and ran four tests that
 * could only time out. The presence of citations is what distinguishes a real answer, and
 * the fixture is requested here so the probe runs against a corpus that has documents in
 * it.
 */
let generationAvailable: boolean | null = null;

test.describe("ask", () => {
  test.beforeEach(async ({ seeded }) => {
    if (generationAvailable === null) {
      const response = await fetch(`${API}/api/ask`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ question: "What is the sentinel load called?" }),
      }).catch(() => null);
      const body = response?.ok ? await response.json() : null;
      generationAvailable = (body?.citations?.length ?? 0) > 0;
    }
    test.skip(!generationAvailable, "no model configured for /api/ask");
  });

  test("citations render before the first word of the answer", async ({ page, seeded }) => {
    // The event order is the point of the streaming endpoint: passages are chosen before
    // generation starts, so a reference is followable as it appears rather than at the end.
    await page.goto("/ask");
    await page.getByLabel("Question").fill("What is the sentinel load called?");
    await page.getByRole("button", { name: "Ask" }).click();

    await expect(page.locator(".sources")).toBeVisible({ timeout: 30_000 });
    expect(await page.locator(".answer").count()).toBe(0);

    await expect(page.locator(".answer")).toBeVisible({ timeout: 60_000 });
  });

  test("the answer arrives in pieces rather than at once", async ({ page, seeded }) => {
    await page.goto("/ask");
    await page.getByLabel("Question").fill("How does deadlock recovery work?");
    await page.getByRole("button", { name: "Ask" }).click();
    await expect(page.locator(".answer")).toBeVisible({ timeout: 60_000 });

    const lengths = new Set<number>();
    for (let sample = 0; sample < 40; sample++) {
      lengths.add(((await page.locator(".answer").textContent()) ?? "").length);
      if ((await page.locator(".caret").count()) === 0) break;
      await page.waitForTimeout(150);
    }
    expect(lengths.size).toBeGreaterThan(1);
  });

  test("a citation opens the passage it refers to", async ({ page, seeded }) => {
    await page.goto("/ask");
    await page.getByLabel("Question").fill("What is PARROTVALVE?");
    await page.getByRole("button", { name: "Ask" }).click();
    await page.waitForFunction(() => document.querySelectorAll(".caret").length === 0, null, {
      timeout: 60_000,
    });

    await expect(page.locator(".cite-missing")).toHaveCount(0);
    const citations = page.locator("button.cite");
    await expect(citations.first()).toBeVisible();

    await citations.first().click();
    await expect(page.locator(".document mark")).toBeVisible();
  });

  test("no space is lost where the stream is split", async ({ page, seeded }) => {
    await page.goto("/ask");
    await page.getByLabel("Question").fill("How are loads assigned, and in what order?");
    await page.getByRole("button", { name: "Ask" }).click();
    await page.waitForFunction(() => document.querySelectorAll(".caret").length === 0, null, {
      timeout: 60_000,
    });

    // Trimming each chunk once removed the space at every seam, producing text like
    // "usingReciprocal RankFusion". Two words run together show as a lower-upper pair.
    const answer = (await page.locator(".answer").textContent()) ?? "";
    expect(answer.match(/[a-z][A-Z]/g) ?? []).toEqual([]);
  });
});
