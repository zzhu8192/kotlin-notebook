// End-to-end test: starts the server on a temporary notebook directory and drives the UI in Chrome.
// Run `./gradlew installDist` first, then `npm run e2e` from this directory.
// To test the fat jar instead, run `./gradlew shadowJar` and set KOTNB_JAR to its path.
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { spawn, execSync } from "node:child_process";
import { mkdtempSync, readFileSync, existsSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const windows = process.platform === "win32";
const launcher = path.join(root, "build/install/kotlin-notebook/bin", windows ? "kotlin-notebook.bat" : "kotlin-notebook");
const port = 18000 + Math.floor(Math.random() * 1000);
const url = `http://localhost:${port}`;
const notebooks = mkdtempSync(path.join(tmpdir(), "kotnb-e2e-"));

let server;
let browser;
let page;
const pageErrors = [];

before(async () => {
  const args = ["serve", "--port", String(port), "--notebooks", notebooks];
  const jar = process.env.KOTNB_JAR;
  if (jar) {
    assert.ok(existsSync(jar), `Missing ${jar}`);
    server = spawn("java", ["-jar", jar, ...args]);
  } else {
    assert.ok(existsSync(launcher), `Missing ${launcher}; run ./gradlew installDist first`);
    server = windows ? spawn("cmd.exe", ["/c", launcher, ...args]) : spawn(launcher, args);
  }
  let serverLog = "";
  server.stdout.on("data", (data) => (serverLog += data));
  server.stderr.on("data", (data) => (serverLog += data));
  for (let i = 0; ; i++) {
    try {
      if ((await fetch(`${url}/api/info`)).ok) break;
    } catch {
      // Not up yet.
    }
    if (i > 120) throw new Error(`Server did not start:
${serverLog}`);
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  browser = await chromium.launch({ channel: process.env.KOTNB_BROWSER ?? "chrome" });
  page = await browser.newPage({ viewport: { width: 1400, height: 900 } });
  page.on("pageerror", (error) => pageErrors.push(error.message));
  page.on("console", (message) => message.type() === "error" && pageErrors.push(message.text()));
});

after(async () => {
  await browser?.close();
  if (server) {
    if (windows) execSync(`taskkill /pid ${server.pid} /T /F`, { stdio: "ignore" });
    else server.kill();
  }
  rmSync(notebooks, { recursive: true, force: true });
});

const cell = (index) => page.locator(".cell").nth(index);
const editorText = (index) => cell(index).locator(".cm-content").innerText();

async function waitForStatus(index, status) {
  await page.waitForFunction(
    ([i, s]) => document.querySelectorAll(".cell")[i]?.dataset.status === s,
    [index, status],
    { timeout: 60_000 },
  );
}

test("notebook workflow", async () => {
  await page.goto(url);
  await page.click("#empty-new");
  await page.fill(".dialog-input", "demo");
  await page.keyboard.press("Enter");
  await page.waitForSelector("#kernel-state[data-state=idle]", { timeout: 60_000 });

  // Completion pops up while typing and inserts calls the way IntelliJ does.
  await cell(0).locator(".cm-content").click();
  await page.keyboard.type('val words = listOf("kotlin", "notebook")\nwords.filt');
  await page.waitForSelector(".cm-tooltip-autocomplete", { timeout: 60_000 });
  assert.match(await page.locator(".cm-completionLabel").first().innerText(), /^filter\(/);
  await page.waitForTimeout(200); // CodeMirror ignores Enter for a moment after the popup opens.
  await page.keyboard.press("Enter");
  await page.keyboard.type("it.length > 6");
  assert.equal(await editorText(0), 'val words = listOf("kotlin", "notebook")\nwords.filter { it.length > 6 }');

  // Shift+Enter runs the cell, shows its value and type, and moves to a new cell.
  await page.keyboard.press("Shift+Enter");
  await waitForStatus(0, "ok");
  assert.equal(await cell(0).locator(".result-value").innerText(), "[notebook]");
  assert.equal(await cell(0).locator(".result-type").innerText(), "List<String>");

  // State carries over, output streams in, and compile errors point at the code.
  await page.keyboard.type('println("count: ${words.size}")\nwords.size +');
  await page.keyboard.press("Control+Enter");
  await waitForStatus(1, "error");
  assert.ok((await cell(1).locator(".diagnostic").count()) > 0);
  await page.keyboard.press("Backspace");
  await page.keyboard.press("Backspace");
  await page.keyboard.press("Control+Enter");
  await waitForStatus(1, "ok");
  assert.equal(await cell(1).locator(".stream.stdout").innerText(), "count: 2\n");
  assert.equal(await cell(1).locator(".result-value").innerText(), "2");

  // Runtime errors show the exception.
  await cell(1).locator(".cm-content").click();
  await page.keyboard.press("Alt+Enter");
  await page.keyboard.type('error("boom")');
  await page.keyboard.press("Control+Enter");
  await waitForStatus(2, "error");
  assert.match(await cell(2).locator(".runtime-error").innerText(), /IllegalStateException: boom/);

  // Markdown cells render.
  await cell(2).hover();
  await cell(2).locator(".type-select").selectOption("markdown");
  await page.keyboard.press("Control+a");
  await page.keyboard.type("## Notes");
  await page.keyboard.press("Control+Enter");
  assert.equal(await cell(2).locator(".markdown-body h2").innerText(), "Notes");

  // Edits are saved as a .kot file.
  await page.waitForSelector("#save-state[data-state=saved]");
  const saved = readFileSync(path.join(notebooks, "demo.kot"), "utf8");
  assert.equal(
    saved,
    '// Kotlin notebook source\nval words = listOf("kotlin", "notebook")\nwords.filter { it.length > 6 }\n\n' +
      '// COMMAND ----------\n\nprintln("count: ${words.size}")\nwords.size\n\n' +
      "// COMMAND ----------\n\n// MAGIC %md\n// MAGIC ## Notes\n",
  );

  // Rename, reload, then delete.
  await page.click("#notebook-title");
  await page.keyboard.press("Control+a");
  await page.keyboard.type("team/report");
  await page.keyboard.press("Enter");
  await page.waitForFunction(() => location.hash === "#/team/report");
  assert.ok(existsSync(path.join(notebooks, "team/report.kot")));
  await page.reload();
  await page.waitForSelector(".cell .cm-content");
  assert.equal(await page.locator(".cell").count(), 3);
  await page.click("#delete-notebook");
  await page.click(".dialog .button.primary");
  await page.waitForSelector("#empty-state:not([hidden])");
  assert.ok(!existsSync(path.join(notebooks, "team/report.kot")));

  assert.deepEqual(pageErrors, []);
});
