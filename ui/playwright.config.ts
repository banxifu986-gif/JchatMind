import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./tests",
  timeout: 120_000,
  outputDir: "../backend_v2/target/g1-playwright/test-results",
  reporter: [["list"], ["html", { outputFolder: "../backend_v2/target/g1-playwright/report", open: "never" }]],
  use: {
    baseURL: process.env.G1_UI_BASE_URL ?? "http://127.0.0.1:5173",
    channel: "msedge",
    headless: true,
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
    video: "off",
  },
  projects: [
    {
      name: "edge",
      use: { ...devices["Desktop Chrome"], channel: "msedge" },
    },
  ],
});
