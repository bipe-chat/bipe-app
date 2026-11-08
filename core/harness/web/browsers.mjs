// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Opens the page of browser.ts in Chromium, Firefox and WebKit (Safari's engine) and prints one metrics line per
// browser (bipe-chat/bipe#1277 item 6). Exit 1 when one browser fails.
// Usage: node browsers.mjs <page URL>
import { chromium, firefox, webkit } from 'playwright';

let failed = false;
for (const [name, type] of [['chromium', chromium], ['firefox', firefox], ['webkit', webkit]]) {
  const browser = await type.launch();
  try {
    const page = await browser.newPage();
    const logs = [];
    page.on('console', (message) => logs.push(`${message.type()}: ${message.text()}`));
    page.on('pageerror', (error) => logs.push(`pageerror: ${error.message}`));
    await page.goto(process.argv[2]);
    await page.waitForFunction(() => window.bipeCoreResult !== undefined, null, { timeout: 120_000 });
    const result = await page.evaluate(() => window.bipeCoreResult);
    if (result.ok) {
      console.log(`BIPE_CORE_METRICS ${name} ${browser.version()} ${JSON.stringify(result.metrics)}`);
    } else {
      failed = true;
      console.log(`BIPE_CORE_FAILED ${name} ${browser.version()} ${result.error}\n${logs.join('\n')}`);
    }
  } catch (error) {
    failed = true;
    console.log(`BIPE_CORE_FAILED ${name} ${error}`);
  } finally {
    await browser.close();
  }
}
process.exit(failed ? 1 : 0);
