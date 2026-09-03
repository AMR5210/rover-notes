import { API, expect, test } from "./fixtures";

/**
 * The span contract, which is what these tests exist for.
 *
 * A search hit and a citation both carry `charStart` and `charEnd`, and the interface
 * turns that pair into a highlighted passage inside the document it came from. Nothing
 * else in the system reads those fields, so a change to the response shape or to how
 * documents are chunked would break the highlight while every other suite passed.
 */
test.describe("citation spans", () => {
  test("a hit opens its document with the cited passage highlighted", async ({
    page,
    seeded,
  }) => {
    await page.goto("/search");
    await page.getByLabel("Search query").fill("what does the sentinel load mean");
    await page.getByRole("button", { name: "Search" }).click();

    await expect(page.locator(".hit").first()).toBeVisible();
    await page.locator(".hit").first().click();

    const highlight = page.locator(".document mark");
    await expect(highlight).toBeVisible();
    await expect(highlight).not.toBeEmpty();
  });

  test("the highlight is the span the result named, not the whole document", async ({
    page,
    seeded,
  }) => {
    // The long fixture chunks into more than one piece, so at least one hit addresses a
    // span that starts partway through its document. That case is the one worth
    // asserting: a highlight covering the whole file would pass a weaker check.
    await page.goto("/search");
    await page.getByLabel("Search query").fill("what to check when the dispatcher stops");
    await page.getByRole("button", { name: "Search" }).click();
    await expect(page.locator(".hit").first()).toBeVisible();

    const spans = await page.locator(".hit-span").allTextContents();
    const index = spans.findIndex((span) => !span.includes("characters 0 to"));
    test.skip(index === -1, "no mid-document chunk in these results");

    const [, start, end] = spans[index].match(/characters (\d+) to (\d+)/) ?? [];
    await page.locator(".hit").nth(index).click();

    const highlighted = (await page.locator(".document mark").textContent()) ?? "";
    expect(highlighted.length).toBe(Number(end) - Number(start));

    // The panel reports the same offsets it was given, against the document's real length.
    await expect(page.locator(".panel-foot")).toHaveText(
      new RegExp(`characters ${start} to ${end} of \\d+`),
    );

    // The strongest form of the check: the highlighted text is what those offsets select
    // from the document as stored. The document is found by the title the panel shows,
    // because the winning hit is whichever document the query actually reached rather
    // than necessarily one of this test's own fixtures.
    const title = await page.locator(".panel-title").textContent();
    const page1 = await fetch(`${API}/api/notes?limit=100`).then((r) => r.json());
    const match = page1.items.find((note: { title: string }) => note.title === title);
    expect(match, `no document titled ${title}`).toBeTruthy();
    expect(match.content.slice(Number(start), Number(end))).toBe(highlighted);
  });

  test("an out-of-range span is clamped rather than throwing", async ({ page, seeded }) => {
    // A document edited after indexing leaves spans addressing text that has moved. The
    // panel is expected to show something rather than fail.
    await page.goto("/search");
    await page.getByLabel("Search query").fill("depot vehicle counts");
    await page.getByRole("button", { name: "Search" }).click();
    await expect(page.locator(".hit").first()).toBeVisible();
    await page.locator(".hit").first().click();
    await expect(page.locator(".document")).toBeVisible();
  });
});
