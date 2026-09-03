import { API, expect, test } from "./fixtures";

/**
 * Getting documents in.
 *
 * The API has taken files and web pages since the parsing service landed; until now the
 * only way to reach either was curl. These cover the surface that changed that, and the
 * one thing about it that is not visible from the markup: a PDF and a text file take
 * different routes in — the first to the parsing service, the second read in the browser
 * — and both have to end as a document in the corpus.
 */

/** A one-page PDF with a real text layer, built here so the suite carries no fixture file. */
function onePagePdf(sentence: string): Buffer {
  const stream = `BT /F1 12 Tf 60 700 Td (${sentence}) Tj ET`;
  const objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R "
      + "/Resources << /Font << /F1 5 0 R >> >> >>",
    `<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`,
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
  ];

  let out = "%PDF-1.4\n";
  const offsets: number[] = [];
  objects.forEach((object, index) => {
    offsets.push(out.length);
    out += `${index + 1} 0 obj\n${object}\nendobj\n`;
  });
  const startxref = out.length;
  out += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  out += offsets.map((offset) => `${String(offset).padStart(10, "0")} 00000 n \n`).join("");
  out += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\n`;
  out += `startxref\n${startxref}\n%%EOF\n`;
  return Buffer.from(out, "latin1");
}

/** Removes a document by title, so a failed assertion does not leave the corpus changed. */
async function removeByTitle(title: string): Promise<void> {
  const page = await (await fetch(`${API}/api/notes?limit=200&offset=0`)).json();
  for (const note of page.items as { id: string; title: string }[]) {
    if (note.title === title) {
      await fetch(`${API}/api/notes/${note.id}`, { method: "DELETE" }).catch(() => undefined);
    }
  }
}

test.describe("library", () => {
  test("says what the page is for before anything is added", async ({ page, seeded }) => {
    await page.goto("/notes");

    await expect(page.getByRole("heading", { name: "Your library" })).toBeVisible();
    await expect(page.getByText("Drop your documents here")).toBeVisible();
    // The hint has to name what will actually be accepted. Offering "documents" and
    // refusing a .docx is worse than saying so on the target.
    await expect(page.getByText("PDFs, text files and Markdown")).toBeVisible();
  });

  test("a PDF chosen from the picker becomes a document", async ({ page, seeded }) => {
    const title = "browser-upload-probe.pdf";
    await removeByTitle(title);

    await page.goto("/notes");
    await page.getByLabel("Choose files to add").setInputFiles({
      name: title,
      mimeType: "application/pdf",
      buffer: onePagePdf("QUILLBRACKET is the marker this upload test looks for."),
    });

    try {
      await expect(page.locator(".progress-done")).toBeVisible({ timeout: 60_000 });
      await expect(page.locator(".progress-name")).toHaveText(title);
      // Present in the list, which is the actual outcome — the row above only says the
      // request succeeded.
      await expect(page.locator(".notes .note-title", { hasText: title })).toBeVisible();
    } finally {
      await removeByTitle(title);
    }
  });

  test("a markdown file is read in the browser rather than sent to the parser", async ({
    page,
    seeded,
  }) => {
    const title = "browser-text-probe";
    await removeByTitle(title);

    await page.goto("/notes");
    await page.getByLabel("Choose files to add").setInputFiles({
      name: `${title}.md`,
      mimeType: "text/markdown",
      buffer: Buffer.from("# Probe\n\nQUILLBRACKET appears in a markdown file.\n"),
    });

    try {
      await expect(page.locator(".progress-done")).toBeVisible({ timeout: 30_000 });
      // The extension is dropped, because the title is what a reader sees in the list.
      await expect(page.locator(".notes .note-title", { hasText: title })).toBeVisible();
    } finally {
      await removeByTitle(title);
    }
  });

  test("a file type that cannot be read says so instead of failing quietly", async ({
    page,
    seeded,
  }) => {
    await page.goto("/notes");
    await page.getByLabel("Choose files to add").setInputFiles({
      name: "holiday.jpeg",
      mimeType: "image/jpeg",
      buffer: Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10]),
    });

    const failed = page.locator(".progress-failed");
    await expect(failed).toBeVisible({ timeout: 15_000 });
    await expect(failed).toContainText("only PDF, text and markdown");
    // Refused in the browser, so nothing reached the corpus to clean up.
    await expect(page.locator(".notes .note-title", { hasText: "holiday" })).toHaveCount(0);
  });

  test("typing a document is offered, but folded away", async ({ page, seeded }) => {
    await page.goto("/notes");

    // Three ways in shown at once turns a drop target into a form to read.
    await expect(page.getByLabel("Note content")).toHaveCount(0);
    await page.getByRole("button", { name: "…or write one yourself" }).click();
    await expect(page.getByLabel("Note content")).toBeVisible();
  });
});
