#!/usr/bin/env node
/**
 * DAVIX – Aggiornamento automatico catalogo server italiani da Vegeta TV
 *
 * - Scarica server_status.json di Vegeta
 * - Valida la struttura
 * - Tiene solo i server con region === "IT" (sia up che down)
 * - Genera ID stabili basati su nome + host della sorgente
 * - Confronta con il catalogo precedente e registra le variazioni
 * - Scrive catalogs/it/vegeta-it-servers.json
 *
 * Nota: region=IT è la classificazione del *server* da parte di Vegeta.
 *       La nazionalità reale dei canali andrà verificata in seguito
 *       analizzando i metadati delle playlist M3U.
 */

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, "..");
const CATALOG_PATH = path.join(ROOT, "catalogs", "it", "vegeta-it-servers.json");
const SOURCE_URL = "http://vegetatvoficial.duckdns.org/data/server_status.json";
const SCHEMA_VERSION = 1;
const MAX_HISTORY_ENTRIES = 40;

function die(msg) {
  console.error(`❌ ${msg}`);
  process.exit(1);
}

function stableId(name, url) {
  try {
    const u = new URL(url);
    const host = (u.hostname || "unknown").toLowerCase();
    const num = String(name || "").replace(/\D+/g, "") || "x";
    return `vegeta-it-\( {num}- \){host}`.replace(/[^a-z0-9.-]/gi, "-").toLowerCase();
  } catch {
    return `vegeta-it-${String(name || "unknown").replace(/\s+/g, "-").toLowerCase()}`;
  }
}

function normalizeServer(raw, generatedAtIso) {
  if (!raw || typeof raw !== "object") return null;
  if (raw.region !== "IT") return null;

  const url = typeof raw.url === "string" ? raw.url.trim() : "";
  if (!url || !/^https?:\/\//i.test(url)) return null;

  const name = String(raw.name || "").trim() || "Server";
  const status = raw.status === "up" || raw.up === true ? "up" : "down";

  return {
    id: stableId(name, url),
    name,
    status,
    region: "IT",
    detectedRegion: raw.detected_region || raw.region || "IT",
    kind: raw.kind || "xtream",
    url,
    apiUp: Boolean(raw.api_up),
    mediaState: raw.media_state || (status === "up" ? "up" : "down"),
    lastChecked: raw.checked_at_iso || generatedAtIso || new Date().toISOString(),
    responseTimeMs: typeof raw.response_time_ms === "number" ? raw.response_time_ms : null,
    workingExample: {
      category: raw.stream_audit_working_category || null,
      stream: raw.stream_audit_working_stream || null,
    },
    source: "vegeta",
    sourceUrl: SOURCE_URL,
  };
}

function computeChanges(prevServers, nextServers) {
  const prevMap = new Map((prevServers || []).map((s) => [s.id, s]));
  const nextMap = new Map((nextServers || []).map((s) => [s.id, s]));
  const changes = [];

  for (const [id, next] of nextMap) {
    const prev = prevMap.get(id);
    if (!prev) {
      changes.push({ type: "added", id, name: next.name, status: next.status });
    } else if (prev.status !== next.status) {
      changes.push({
        type: "status_changed",
        id,
        name: next.name,
        from: prev.status,
        to: next.status,
      });
    } else if (prev.url !== next.url) {
      changes.push({ type: "url_changed", id, name: next.name });
    }
  }

  for (const [id, prev] of prevMap) {
    if (!nextMap.has(id)) {
      changes.push({ type: "removed", id, name: prev.name, status: prev.status });
    }
  }

  return changes;
}

async function main() {
  console.log("→ Scarico status Vegeta…");
  const res = await fetch(SOURCE_URL, {
    headers: { "User-Agent": "DAVIX-Catalog-Updater/1.0" },
    signal: AbortSignal.timeout(25000),
  });

  if (!res.ok) die(`HTTP ${res.status} da ${SOURCE_URL}`);

  let payload;
  try {
    payload = await res.json();
  } catch {
    die("JSON non valido ricevuto da Vegeta");
  }

  if (!payload || !Array.isArray(payload.list)) {
    die("Struttura server_status.json non riconosciuta (manca list[])");
  }

  const generatedAt = payload.generated_at_iso || new Date().toISOString();
  const servers = [];
  const seenIds = new Set();

  for (const raw of payload.list) {
    const s = normalizeServer(raw, generatedAt);
    if (!s) continue;
    if (seenIds.has(s.id)) continue;
    seenIds.add(s.id);
    servers.push(s);
  }

  servers.sort((a, b) => a.name.localeCompare(b.name, "it"));

  let previous = null;
  if (fs.existsSync(CATALOG_PATH)) {
    try {
      previous = JSON.parse(fs.readFileSync(CATALOG_PATH, "utf8"));
    } catch {
      console.warn("⚠ Catalogo precedente non leggibile, parto da zero");
    }
  }

  const changes = computeChanges(previous?.servers, servers);
  const now = new Date().toISOString();

  const history = Array.isArray(previous?.history) ? [...previous.history] : [];
  if (changes.length > 0) {
    history.unshift({
      at: now,
      sourceGeneratedAt: generatedAt,
      changes,
    });
  }
  while (history.length > MAX_HISTORY_ENTRIES) history.pop();

  const catalog = {
    schemaVersion: SCHEMA_VERSION,
    source: "vegeta-tv-server-status",
    sourceUrl: SOURCE_URL,
    countryHint: "IT",
    note: "region=IT indica la classificazione del server da parte di Vegeta, non garantisce che tutti i canali siano italiani. La classificazione a livello di singolo canale verrà fatta in seguito.",
    updatedAt: now,
    sourceGeneratedAt: generatedAt,
    serverCount: servers.length,
    upCount: servers.filter((s) => s.status === "up").length,
    downCount: servers.filter((s) => s.status === "down").length,
    servers,
    history,
  };

  if (catalog.schemaVersion !== 1) die("schemaVersion errata");
  if (!Array.isArray(catalog.servers)) die("servers non è un array");

  fs.mkdirSync(path.dirname(CATALOG_PATH), { recursive: true });
  fs.writeFileSync(CATALOG_PATH, JSON.stringify(catalog, null, 2) + "\n", "utf8");

  console.log(`✓ Catalogo scritto: ${CATALOG_PATH}`);
  console.log(`  Server IT: ${catalog.serverCount} (up: ${catalog.upCount}, down: ${catalog.downCount})`);
  if (changes.length) {
    console.log(`  Variazioni rilevate: ${changes.length}`);
    for (const c of changes) {
      console.log(`    - ${c.type}: \( {c.name || c.id} \){c.from ? ` (${c.from} → ${c.to})` : ""}`);
    }
  } else {
    console.log("  Nessuna variazione rispetto al catalogo precedente");
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
