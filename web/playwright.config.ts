import { defineConfig, devices } from "@playwright/test";

/**
 * Browser tests for the interface, run against a live API.
 *
 * These exist for one contract in particular. A citation carries `charStart` and
 * `charEnd`, and the interface turns that pair into a highlighted passage. A change to
 * the response shape or to how documents are chunked would break the highlight while
 * every JVM and Python test continued to pass, because nothing else reads those fields.
 *
 * The API is expected to be running already; the tests fail with a clear message when it
 * is not. Next is started by the runner, so a single command works locally and in CI.
 *
 * `CHROMIUM_PATH` selects a browser that is already installed, which is how this runs in
 * environments that provide one. Left unset, Playwright uses the browser it manages.
 */
const chromium = process.env.CHROMIUM_PATH;

export default defineConfig({
  testDir: "./tests",
  // Fixtures are created and deleted through the API against a shared database, so
  // parallel files would race each other over the same corpus.
  workers: 1,
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : [["list"]],
  timeout: 60_000,
  expect: { timeout: 15_000 },

  use: {
    baseURL: process.env.WEB_URL ?? "http://localhost:3000",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    ...(chromium ? { launchOptions: { executablePath: chromium } } : {}),
  },

  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],

  webServer: process.env.WEB_URL
    ? undefined
    : {
        command: "npm run start",
        url: "http://localhost:3000",
        reuseExistingServer: !process.env.CI,
        timeout: 120_000,
      },
});
