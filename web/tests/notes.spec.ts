import { expect, test } from "./fixtures";

test.describe("notes", () => {
  test("lists the corpus and expands a document in place", async ({ page, seeded }) => {
    await page.goto("/notes");

    const row = page.locator(".notes li", { hasText: "freight-scheduling" });
    await expect(row).toBeVisible();
    // Exact, because the row's edit and delete controls are labelled with the title too.
    await row.getByRole("button", { name: "freight-scheduling", exact: true }).click();
    await expect(page.locator(".document")).toContainText("Freight scheduling");
  });

  test("a note written here is added to the corpus and can be removed", async ({
    page,
    seeded,
  }) => {
    // Asserted on the row rather than on a total. The corpus is shared, so a count is a
    // statement about everything in the database rather than about this note, and it
    // fails for reasons that have nothing to do with the behaviour under test.
    await page.goto("/notes");
    const row = page.locator(".notes li", { hasText: "browser-test-probe" });
    await expect(row).toHaveCount(0);

    await page.getByRole("button", { name: "…or write one yourself" }).click();
    await page.getByLabel("Note title").fill("browser-test-probe");
    await page.getByLabel("Note content").fill("Written through the interface.");
    await page.getByRole("button", { name: "Add document" }).click();
    await expect(row).toHaveCount(1);

    await row.getByRole("button", { name: "Delete browser-test-probe" }).click();
    await expect(row).toHaveCount(0);
  });

  test("a note can be corrected in place", async ({ page, seeded }) => {
    await page.goto("/notes");
    await page.getByRole("button", { name: "…or write one yourself" }).click();
    await page.getByLabel("Note title").fill("edit-probe");
    await page.getByLabel("Note content").fill("First wording.");
    await page.getByRole("button", { name: "Add document" }).click();

    const row = page.locator(".notes li", { hasText: "edit-probe" });
    await expect(row).toHaveCount(1);

    await row.getByRole("button", { name: "Edit edit-probe" }).click();
    await page.getByLabel("Edit content").fill("Second wording, corrected.");
    await page.getByRole("button", { name: "Save", exact: true }).click();

    // The edited text has to come back from the API, not from the field it was typed
    // into, so the row is reopened after the list has been reloaded.
    const edited = page.locator(".notes li", { hasText: "edit-probe" });
    await edited.getByRole("button", { name: "edit-probe", exact: true }).click();
    await expect(page.locator(".document")).toContainText("Second wording, corrected.");

    await edited.getByRole("button", { name: "Delete edit-probe" }).click();
    await expect(page.locator(".notes li", { hasText: "edit-probe" })).toHaveCount(0);
  });

  test("cancelling an edit leaves the note as it was", async ({ page, seeded }) => {
    await page.goto("/notes");
    const row = page.locator(".notes li", { hasText: "freight-scheduling" });

    await row.getByRole("button", { name: "Edit freight-scheduling" }).click();
    await page.getByLabel("Edit title").fill("renamed-in-a-draft");
    await page.getByRole("button", { name: "Cancel" }).click();

    await expect(page.locator(".notes li", { hasText: "renamed-in-a-draft" })).toHaveCount(0);
    await expect(row).toHaveCount(1);
  });

  test("the composer refuses an empty note", async ({ page, seeded }) => {
    await page.goto("/notes");
    await page.getByRole("button", { name: "…or write one yourself" }).click();

    // Disabled from the moment the composer opens, rather than only after a partial fill.
    await expect(page.getByRole("button", { name: "Add document" })).toBeDisabled();
    await page.getByLabel("Note title").fill("title only");
    await expect(page.getByRole("button", { name: "Add document" })).toBeDisabled();
  });
});
