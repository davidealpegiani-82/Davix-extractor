#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
const dir = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(dir, "../../..");
const parts = 7;
let b64 = "";
for (let i = 0; i < parts; i++) {
  const p = path.join(dir, "verify-player-template.part" + String(i).padStart(2, "0") + ".b64");
  b64 += fs.readFileSync(p, "utf8").trim();
}
const out = path.join(root, "android/scripts/verify-player-template.mjs");
fs.writeFileSync(out, Buffer.from(b64, "base64"));
console.log("written", out, fs.statSync(out).size);
if (fs.statSync(out).size < 5000) process.exit(1);
