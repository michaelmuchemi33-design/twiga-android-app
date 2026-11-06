#!/usr/bin/env node
"use strict";

const fs = require("node:fs");
const vm = require("node:vm");
const { spawnSync } = require("node:child_process");

const htmlFiles = [
  "dashboard-live/app.html",
  "dashboard-live/ai.html"
];

let checked = 0;

for (const file of htmlFiles) {
  const html = fs.readFileSync(file, "utf8");
  const blocks = [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)];

  for (let i = 0; i < blocks.length; i++) {
    const attrs = blocks[i][1] || "";
    const code = blocks[i][2] || "";
    if (!code.trim()) continue;

    if (/\btype\s*=\s*["']module["']/i.test(attrs)) {
      const result = spawnSync(process.execPath, ["--input-type=module", "--check"], {
        input: code,
        encoding: "utf8"
      });
      if (result.status !== 0) {
        process.stderr.write(result.stderr || result.stdout || "Module syntax check failed.\n");
        process.exit(result.status || 1);
      }
    } else {
      new vm.Script(code, { filename: file + "#script-" + (i + 1) });
    }
    checked++;
  }
}

const vapi = fs.readFileSync("dashboard-live/vapi-call.js", "utf8");
const moduleResult = spawnSync(process.execPath, ["--input-type=module", "--check"], {
  input: vapi,
  encoding: "utf8"
});
if (moduleResult.status !== 0) {
  process.stderr.write(moduleResult.stderr || moduleResult.stdout || "Vapi module syntax check failed.\n");
  process.exit(moduleResult.status || 1);
}
checked++;

JSON.parse(fs.readFileSync("dashboard-live/templates.json", "utf8"));
checked++;

console.log("TWIGA dashboard syntax checks passed (" + checked + " script/JSON inputs).");
