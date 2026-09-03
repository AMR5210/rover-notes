import { expect, test as base } from "@playwright/test";

/**
 * Documents the browser tests are written against.
 *
 * The tests own their corpus rather than reading whichever documents happen to be
 * present. Two properties are needed and neither is guaranteed by an arbitrary corpus:
 * a document long enough to be split into more than one chunk, so a citation can address
 * a span that is not the whole file, and vocabulary distinctive enough that a query
 * reaches the intended document.
 *
 * Every document is removed afterwards, including on failure. The same database backs
 * the evaluation harness, which refuses to score a corpus whose document count is not
 * the one it seeded.
 */
export const API = process.env.API_URL ?? "http://localhost:8080";

/**
 * Long enough to chunk, which the span tests depend on.
 *
 * The committed window is 1,600 characters with 200 of overlap, so this has to exceed
 * 1,600 to produce a chunk that starts partway through the document. An earlier version
 * was 1,350 and passed locally only because the evaluation corpus was also present and
 * supplied a multi-chunk document of its own; on a database holding nothing else, the
 * span test found no mid-document chunk and skipped.
 */
const LONG_DOCUMENT = [
  "# Freight scheduling",
  "",
  "How the dispatcher assigns loads, and what to check when it stops.",
  "",
  "## Assignment order",
  "",
  "Loads are assigned by deadline, then by depot distance. A load with no deadline is",
  "treated as due at the end of the planning window, so it never starves a dated load.",
  "The planner holds each candidate in memory and scores it once per cycle.",
  "",
  "The scoring pass is deliberately cheap. It is a linear scan rather than an index",
  "lookup, because the candidate set is bounded by the depot count and rebuilding an",
  "index each cycle costs more than scanning a short list.",
  "",
  "## Deadlock recovery",
  "",
  "PARROTVALVE is the sentinel load the recovery routine inserts when two depots each",
  "hold the other's only free vehicle. It carries no freight and no deadline, and it",
  "exists so the assignment loop has something to release. A dispatcher that reports",
  "PARROTVALVE in a manifest has recovered from a deadlock rather than scheduled cargo.",
  "",
  "Recovery runs at most once per cycle. Running it repeatedly would mask a genuine",
  "shortage of vehicles as a scheduling artefact, and the shortage is the thing worth",
  "reporting.",
  "",
  "## Depot reporting",
  "",
  "Each depot reports its vehicle count on a fixed interval, and the planner treats a",
  "depot that misses two consecutive reports as offline. Its vehicles leave the pool",
  "until it reports again, which is deliberately blunt: a depot that cannot be reached",
  "cannot be dispatched to either, so a stale count is worse than no count.",
  "",
  "Reports carry a sequence number rather than a timestamp. Clocks across depots drift",
  "by enough to reorder two reports a second apart, and the planner cares about which",
  "report is newer rather than when either was written.",
  "",
  "## Planning window",
  "",
  "The window is four hours and moves forward in fifteen-minute steps. A shorter window",
  "assigns less each cycle and reacts faster to a depot going offline; a longer one",
  "produces steadier routes at the cost of holding decisions that later prove wrong.",
  "",
  "Loads that fall outside the window are not discarded. They are held and reconsidered",
  "at the next step, which is why a load with a distant deadline can sit unassigned for",
  "several cycles without that indicating a fault.",
  "",
  "## What to check first",
  "",
  "An empty manifest usually means the planning window closed before any load became",
  "eligible. A manifest holding only sentinel loads means recovery ran every cycle,",
  "which points at vehicle supply rather than at the scheduler. A manifest that is",
  "correct but stale points at depot reporting rather than at either.",
].join("\n");

const SHORT_DOCUMENT = [
  "# Depot inventory",
  "",
  "Each depot reports vehicle counts on a fixed interval. A depot that misses two",
  "consecutive reports is treated as offline and its vehicles are excluded from",
  "assignment until it reports again.",
].join("\n");

export interface Seeded {
  longId: string;
  shortId: string;
}

async function create(title: string, content: string): Promise<string> {
  const response = await fetch(`${API}/api/notes`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ title, content }),
  });
  if (!response.ok) {
    throw new Error(`seeding ${title} failed: ${response.status} ${await response.text()}`);
  }
  return (await response.json()).id as string;
}

async function remove(id: string): Promise<void> {
  await fetch(`${API}/api/notes/${id}`, { method: "DELETE" }).catch(() => undefined);
}

/**
 * Blocks until the seeded documents are searchable, since indexing is asynchronous.
 *
 * The allowance is generous because the first embedding request of a run loads the model,
 * which is slow on a cold container and fast everywhere after.
 */
async function awaitIndexed(): Promise<void> {
  for (let attempt = 0; attempt < 240; attempt++) {
    const response = await fetch(`${API}/actuator/metrics/rover.ingestion.backlog`);
    const backlog = (await response.json()).measurements[0].value as number;
    if (backlog === 0) return;
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error("documents were still unindexed after 120s");
}

/**
 * Seeded once per worker rather than once per test.
 *
 * Per-test seeding writes, indexes and deletes the same two documents fourteen times.
 * That is unnoticeable where the embedding server is warm and dominates the run where it
 * is not, which is the difference between this suite taking half a minute locally and
 * several minutes on a cold runner. The tests only read these documents, so one copy for
 * the whole worker is sufficient.
 */
export const test = base.extend<object, { seeded: Seeded }>({
  seeded: [
    async ({}, use) => {
      const health = await fetch(`${API}/actuator/health`).catch(() => null);
      if (!health?.ok) {
        throw new Error(
          `the API is not reachable at ${API}. Start it with \`make up && make api\`, ` +
            `or point API_URL at a running instance.`,
        );
      }

      const longId = await create("freight-scheduling", LONG_DOCUMENT);
      const shortId = await create("depot-inventory", SHORT_DOCUMENT);
      await awaitIndexed();

      try {
        await use({ longId, shortId });
      } finally {
        await remove(longId);
        await remove(shortId);
      }
    },
    { scope: "worker" },
  ],
});

export { expect };
