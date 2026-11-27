#!/usr/bin/env node
/**
 * DAVIX – Aggrega playlist canali dai server IT Vegeta (filtro italiano per il Web).
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, "..");
const SERVERS_PATH = path.join(ROOT, "catalogs", "it", "vegeta-it-servers.json");
const CHANNELS_PATH = path.join(ROOT, "catalogs", "it", "vegeta-it-channels.json");
const MAX_M3U_BYTES = 80 * 1024 * 1024;
const MAX_WEB_CHANNELS = 8000;

function isLikelyItalian(name, category) {
  const n = String(name || "").toLowerCase();
  const c = String(category || "").toLowerCase();
  const blob = n + " " + c;
  if (/[🇮🇹]/.test(name || "")) return true;
  if (/(^|[^a-z])(it|ita|italia|italy|italian|italiano|italiana)([^a-z]|$)/i.test(blob)) return true;
  if (/rai\b|canale\s*5|italia\s*[12]|rete\s*4|la\s*7|tv\s*8|mediaset|sky\s*(uno|cinema|sport|tg|arte|serie|atlantic)|boing|cartoonito|iris|focus|giallo|top\s*crime|cine\s*34|real\s*time|dmax|nove|cielo|tv\s*2000|telelombardia|telenova|tele\s*norba|rtl\s*102/i.test(n)) return true;
  if (/italia|italian|rai|mediaset|sky\s*it/i.test(c)) return true;
  return false;
}

function parseM3u(content, server) {
  const result = [];
  const lines = String(content || "").split(/\r?\n/);
  let pendingName = "Canale";
  let pendingLogo = "";
  let pendingGroup = "Vegeta IT";
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (!line) continue;
    if (/^#EXTINF/i.test(line)) {
      const comma = line.lastIndexOf(",");
      pendingName = comma >= 0 ? line.slice(comma + 1).trim() || "Canale" : "Canale";
      const logoMatch = line.match(/tvg-logo="([^"]*)"/i);
      pendingLogo = logoMatch ? logoMatch[1].trim() : "";
      const groupMatch = line.match(/group-title="([^"]*)"/i);
      pendingGroup = groupMatch && groupMatch[1].trim() ? groupMatch[1].trim() : "Vegeta IT";
    } else if (!line.startsWith("#") && /^https?:\/\//i.test(line)) {
      result.push({
        name: pendingName,
        url: line,
        logo: pendingLogo,
        category: pendingGroup,
        serverId: server.id,
        serverName: server.name
      });
      pendingName = "Canale";
      pendingLogo = "";
      pendingGroup = "Vegeta IT";
    }
  }
  return result;
}

async function main() {
  if (!fs.existsSync(SERVERS_PATH)) {
    console.error("Manca " + SERVERS_PATH);
    process.exit(1);
  }
  const catalog = JSON.parse(fs.readFileSync(SERVERS_PATH, "utf8"));
  const servers = (catalog.servers || []).filter((s) => s.status === "up" && s.url);
  const unique = new Map();
  const errors = [];
  let ok = 0;
  for (const server of servers) {
    try {
      console.log("Scarico M3U:", server.name);
      const res = await fetch(server.url, {
        headers: { Accept: "*/*", "User-Agent": "DAVIX-Catalog/1.0" },
        signal: AbortSignal.timeout(60000)
      });
      if (!res.ok) throw new Error("HTTP " + res.status);
      const buf = Buffer.from(await res.arrayBuffer());
      if (buf.length > MAX_M3U_BYTES) throw new Error("M3U troppo grande");
      const parsed = parseM3u(buf.toString("utf8"), server);
      console.log(" ", server.name + ":", parsed.length, "canali");
      for (const ch of parsed) {
        const key = String(ch.url || "").trim().toLowerCase();
        if (key && !unique.has(key)) unique.set(key, ch);
      }
      ok++;
    } catch (err) {
      const msg = server.name + ": " + (err && err.message ? err.message : String(err));
      console.warn(" ", msg);
      errors.push(msg);
    }
  }
  const all = Array.from(unique.values());
  const italian = all.filter((ch) => isLikelyItalian(ch.name, ch.category));
  const channels = (italian.length ? italian : all).slice(0, MAX_WEB_CHANNELS);
  const now = new Date().toISOString();
  const payload = {
    schemaVersion: 1,
    section: "Vegeta IT",
    updatedAt: now,
    catalogUpdatedAt: catalog.updatedAt || now,
    sourceGeneratedAt: catalog.sourceGeneratedAt || null,
    channelCount: channels.length,
    totalParsed: all.length,
    italianMatched: italian.length,
    serversTried: servers.length,
    serversOk: ok,
    errors,
    channels
  };
  fs.mkdirSync(path.dirname(CHANNELS_PATH), { recursive: true });
  fs.writeFileSync(CHANNELS_PATH, JSON.stringify(payload) + "\n", "utf8");
  console.log("Scritto", CHANNELS_PATH, "canali:", channels.length, "italian:", italian.length, "total:", all.length);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
