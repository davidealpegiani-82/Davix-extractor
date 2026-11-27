#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
const dir = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(dir, "../../..");
const manifest = [{"path": "index.html", "parts": 4, "safe": "index_html", "bytes": 20397}, {"path": "scripts/update-vegeta-it.mjs", "parts": 2, "safe": "scripts__update-vegeta-it_mjs", "bytes": 11121}];
for (const item of manifest) {
  let b64 = "";
  for (let i = 0; i < item.parts; i++) {
    const partPath = path.join(dir, item.safe + ".part" + String(i).padStart(2, "0") + ".b64");
    b64 += fs.readFileSync(partPath, "utf8").trim();
  }
  const out = path.join(root, item.path);
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(out, Buffer.from(b64, "base64"));
  console.log("written", item.path, fs.statSync(out).size);
}
