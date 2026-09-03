import { API, expect, test } from "./fixtures";

/**
 * Filing documents under topics, from the interface.
 *
 * The JVM suite covers what a topic guarantees; these cover the part only a browser sees.
 * Three things in particular: a topic can be created without leaving the page that needed
 * it, the filter narrows the list rather than the corpus, and a filed document says which
 * topic it is in.
 */

const TOPIC = "browser-test-topic";
const NOTE = "topic-probe";

/** Removes anything these tests created, so a failed assertion leaves the corpus as it was. */
async function clean(): Promise<void> {
  const notes = await (await fetch(`${API}/api/notes?limit=200&offset=0`)).json();
  for (const note of notes.items as { id: string; title: string }[]) {
    if (note.title === NOTE) {
      await fetch(`${API}/api/notes/${note.id}`, { method: "DELETE" }).catch(() => undefined);
    }
  }
  const topics = await (await fetch(`${API}/api/topics`)).json();
  for (const topic of topics as { id: string; name: string }[]) {
    if (topic.name === TOPIC) {
      await fetch(`${API}/api/topics/${topic.id}`, { method: "DELETE" }).catch(() => undefined);
    }
  }
}

test.describe("topics", () => {
  test.afterEach(clean);

  test("a topic is created from the page that needed it", async ({ page, seeded }) => {
    await page.goto("/notes");

    await page.getByLabel("File under").selectOption({ label: "+ New topic…" });
    await page.getByLabel("New topic name").fill(TOPIC);
    await page.getByRole("button", { name: "Add topic" }).click();

    // Selected as soon as it exists: the reason to create one is to file something in it.
    await expect(page.getByLabel("File under")).toHaveValue(/[0-9a-f-]{36}/);
    await expect(page.getByRole("button", { name: new RegExp(TOPIC) })).toBeVisible();
  });

  test("a filed document carries its topic and the filter narrows to it", async ({
    page,
    seeded,
  }) => {
    await page.goto("/notes");

    await page.getByLabel("File under").selectOption({ label: "+ New topic…" });
    await page.getByLabel("New topic name").fill(TOPIC);
    await page.getByRole("button", { name: "Add topic" }).click();

    await page.getByRole("button", { name: "…or write one yourself" }).click();
    await page.getByLabel("Note title").fill(NOTE);
    await page.getByLabel("Note content").fill("A note filed under a topic.");
    await page.getByRole("button", { name: "Add document" }).click();

    const row = page.locator(".notes li", { hasText: NOTE });
    await expect(row).toBeVisible();
    await expect(row.locator(".badge-topic")).toHaveText(TOPIC);

    // The seeded documents are unfiled, so the filter is doing something visible here.
    await page.getByRole("button", { name: new RegExp(`^${TOPIC}`) }).click();
    await expect(page.locator(".notes li", { hasText: NOTE })).toBeVisible();
    await expect(page.locator(".notes li", { hasText: "freight-scheduling" })).toHaveCount(0);

    await page.getByRole("button", { name: "All", exact: true }).click();
    await expect(page.locator(".notes li", { hasText: "freight-scheduling" })).toBeVisible();
  });

  test("a document with no topic is reachable through its own filter", async ({
    page,
    seeded,
  }) => {
    await page.goto("/notes");

    await page.getByLabel("File under").selectOption({ label: "+ New topic…" });
    await page.getByLabel("New topic name").fill(TOPIC);
    await page.getByRole("button", { name: "Add topic" }).click();

    await page.getByRole("button", { name: "No topic" }).click();

    // Seeded by the fixture without a topic, so this is the filter and not an empty list.
    await expect(page.locator(".notes li", { hasText: "freight-scheduling" })).toBeVisible();
  });
});
