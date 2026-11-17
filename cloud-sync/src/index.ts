export interface Env {
  DB: D1Database;
  SKY_ADMIN_KEY: string;
}

type Channel = {
  id?: string;
  name: string;
  url: string;
  logo?: string;
  category?: string;
  tvgId?: string;
  tvgName?: string;
  headers?: Record<string, string>;
  expiry?: string | null;
};

type PublishedConfig = {
  schemaVersion: number;
  updateId: string;
  publishedAt?: string;
  categories?: string[];
  channels: Channel[];
  epgSourceUrl?: string;
};

const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store, max-age=0",
};

function response(data: unknown, status = 200, extra: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(data), { status, headers: { ...JSON_HEADERS, ...extra } });
}

function cors(request: Request): HeadersInit {
  return {
    "access-control-allow-origin": request.headers.get("origin") || "*",
    "access-control-allow-methods": "GET,POST,OPTIONS",
    "access-control-allow-headers": "authorization,content-type,x-sky-admin-key",
    "access-control-max-age": "86400",
    "vary": "Origin",
  };
}

function withCors(request: Request, result: Response): Response {
  const headers = new Headers(result.headers);
  for (const [key, value] of Object.entries(cors(request))) headers.set(key, value);
  return new Response(result.body, { status: result.status, statusText: result.statusText, headers });
}

function equalSecret(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let mismatch = 0;
  for (let i = 0; i < a.length; i++) mismatch |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return mismatch === 0;
}

async function sha256(value: string): Promise<string> {
  const bytes = new TextEncoder().encode(value);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map((v) => v.toString(16).padStart(2, "0")).join("");
}

function toBase64(bytes: Uint8Array): string {
  let binary = "";
  for (const value of bytes) binary += String.fromCharCode(value);
  return btoa(binary);
}

function fromBase64(value: string): Uint8Array {
  return Uint8Array.from(atob(value), (character) => character.charCodeAt(0));
}

async function epgEncryptionKey(secret: string): Promise<CryptoKey> {
  const material = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    "HKDF",
    false,
    ["deriveKey"],
  );
  return crypto.subtle.deriveKey(
    {
      name: "HKDF",
      hash: "SHA-256",
      salt: new TextEncoder().encode("DAVIX-CLOUD-SYNC"),
      info: new TextEncoder().encode("custom-xmltv-source-v1"),
    },
    material,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"],
  );
}

/** The credential-bearing XMLTV URL is encrypted before it is written to D1. */
async function encryptEpgSource(value: string, secret: string): Promise<string> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const encrypted = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv },
    await epgEncryptionKey(secret),
    new TextEncoder().encode(value),
  );
  return "v1." + toBase64(iv) + "." + toBase64(new Uint8Array(encrypted));
}

async function decryptEpgSource(value: string, secret: string): Promise<string> {
  const [version, ivValue, encryptedValue] = value.split(".");
  if (version !== "v1" || !ivValue || !encryptedValue) throw new Error("INVALID_EPG_CREDENTIAL");
  const decrypted = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: fromBase64(ivValue) },
    await epgEncryptionKey(secret),
    fromBase64(encryptedValue),
  );
  return new TextDecoder().decode(decrypted);
}

function bearer(request: Request): string {
  const value = request.headers.get("authorization") || "";
  return value.startsWith("Bearer ") ? value.slice(7).trim() : "";
}

async function readJson(request: Request, maxBytes = 1_000_000): Promise<any> {
  const declaredLength = Number(request.headers.get("content-length") || "0");
  if (Number.isFinite(declaredLength) && declaredLength > maxBytes) {
    throw new Error("PAYLOAD_TOO_LARGE");
  }

  const reader = request.body?.getReader();
  if (!reader) throw new Error("INVALID_JSON");
  const chunks: Uint8Array[] = [];
  let totalBytes = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      totalBytes += value.byteLength;
      if (totalBytes > maxBytes) {
        await reader.cancel().catch(() => undefined);
        throw new Error("PAYLOAD_TOO_LARGE");
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }

  const payload = new Uint8Array(totalBytes);
  let offset = 0;
  for (const chunk of chunks) {
    payload.set(chunk, offset);
    offset += chunk.byteLength;
  }
  try {
    return JSON.parse(new TextDecoder().decode(payload));
  } catch {
    throw new Error("INVALID_JSON");
  }
}

function validHttpUrl(value: unknown): value is string {
  if (typeof value !== "string" || !value.trim() || value.length > 8192) return false;
  try {
    const parsed = new URL(value);
    return parsed.protocol === "http:" || parsed.protocol === "https:";
  } catch {
    return false;
  }
}

function validChannel(channel: any): channel is Channel {
  if (!channel || typeof channel !== "object") return false;
  if (typeof channel.name !== "string" || !channel.name.trim() || channel.name.length > 160) return false;
  if (typeof channel.url !== "string" || channel.url.length > 8192) return false;
  try {
    const url = new URL(channel.url);
    if (url.protocol !== "http:" && url.protocol !== "https:") return false;
  } catch { return false; }
  if (channel.logo != null && (typeof channel.logo !== "string" || channel.logo.length > 4096)) return false;
  if (channel.category != null && (typeof channel.category !== "string" || channel.category.trim().length === 0 || channel.category.length > 80)) return false;
  if (channel.tvgId != null && (typeof channel.tvgId !== "string" || channel.tvgId.length > 256)) return false;
  if (channel.tvgName != null && (typeof channel.tvgName !== "string" || channel.tvgName.length > 160)) return false;
  if (channel.headers != null && (typeof channel.headers !== "object" || Array.isArray(channel.headers))) return false;
  return true;
}

async function requireAdmin(request: Request, env: Env): Promise<boolean> {
  const supplied = request.headers.get("x-sky-admin-key") || "";
  return supplied.length > 0 && equalSecret(supplied, env.SKY_ADMIN_KEY);
}

async function requireDevice(request: Request, env: Env): Promise<{ id: string } | null> {
  const token = bearer(request);
  if (!token || token.length < 32 || token.length > 256) return null;
  const tokenHash = await sha256(token);
  const now = Date.now();
  const row = await env.DB.prepare(
    "SELECT device_id FROM devices WHERE token_hash = ? AND status = 'approved' AND lease_expires_at > ? LIMIT 1"
  ).bind(tokenHash, now).first<{ device_id: string }>();
  return row ? { id: row.device_id } : null;
}

async function route(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const path = url.pathname.replace(/\/+$/, "") || "/";

  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors(request) });
  if (request.method === "GET" && path === "/health") {
    try {
      const probe = await env.DB.prepare("SELECT 1 AS ok").first<{ ok: number }>();
      const tableResult = await env.DB.prepare(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('devices', 'config', 'pair_attempts', 'activation_codes') ORDER BY name"
      ).all<{ name: string }>();
      const found = new Set((tableResult.results || []).map((row) => row.name));
      const required = ["config", "devices", "pair_attempts", "activation_codes"];
      const missingTables = required.filter((name) => !found.has(name));
      const databaseOk = probe?.ok === 1;
      const schemaReady = missingTables.length === 0;
      if (!databaseOk || !schemaReady) {
        return response({
          ok: false,
          service: "sky-cloud-sync",
          apiVersion: 1,
          database: { ok: databaseOk, schemaReady, missingTables },
        }, 503);
      }
      return response({
        ok: true,
        service: "sky-cloud-sync",
        apiVersion: 1,
        database: { ok: true, schemaReady: true },
      });
    } catch (error) {
      console.error("Sky Cloud Sync health check failed:", error instanceof Error ? error.message : "unknown");
      return response({
        ok: false,
        service: "sky-cloud-sync",
        apiVersion: 1,
        database: { ok: false, schemaReady: false },
      }, 503);
    }
  }

  // The old player-generated pairing flow is intentionally disabled.
  if (path === "/v1/pair/start" || path === "/v1/pair/status" || path === "/v1/admin/pair/approve") {
    return response({ error: "legacy_activation_disabled", message: "Aggiorna Extractor e Player Lite per usare i codici monouso." }, 410);
  }

  if (request.method === "POST" && path === "/v2/admin/activation-codes") {
    if (!(await requireAdmin(request, env))) return response({ error: "unauthorized" }, 401);
    const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    const random = new Uint8Array(10);
    crypto.getRandomValues(random);
    const code = Array.from(random, (value) => alphabet[value % alphabet.length]).join("");
    await env.DB.prepare("DELETE FROM activation_codes WHERE expires_at < ? OR (consumed_at IS NOT NULL AND consumed_at < ?)")
      .bind(Date.now() - 24 * 60 * 60 * 1000, Date.now() - 7 * 24 * 60 * 60 * 1000).run();
    const codeHash = await sha256(code);
    const now = Date.now();
    const expiresAt = now + 10 * 60 * 1000;
    await env.DB.prepare(
      "INSERT INTO activation_codes (code_hash, created_at, expires_at) VALUES (?, ?, ?)"
    ).bind(codeHash, now, expiresAt).run();
    return response({ code, expiresAt, expiresInSeconds: 600 }, 201);
  }

  if (request.method === "POST" && path === "/v2/activate") {
    let body: any;
    try { body = await readJson(request, 16_384); } catch {
      return response({ error: "invalid_json" }, 400);
    }
    const code = typeof body.code === "string" ? body.code.toUpperCase().replace(/[^A-Z0-9]/g, "") : "";
    const installId = typeof body.installId === "string" ? body.installId.slice(0, 100) : "";
    const deviceSecret = typeof body.deviceSecret === "string" ? body.deviceSecret : "";
    const technicalName = typeof body.deviceName === "string" ? body.deviceName.trim().slice(0, 100) : "";
    const platform = typeof body.platform === "string" ? body.platform.trim().slice(0, 40) : "unknown";
    if (!/^[A-HJ-NP-Z2-9]{10}$/.test(code) || !installId || deviceSecret.length < 32 || deviceSecret.length > 256 || !technicalName) {
      return response({ error: "invalid_activation_request" }, 400);
    }
    const clientIp = request.headers.get("CF-Connecting-IP") || "unknown";
    const ipHash = await sha256(clientIp);
    const since = Date.now() - 60 * 60 * 1000;
    await env.DB.prepare("DELETE FROM pair_attempts WHERE created_at < ?").bind(Date.now() - 24 * 60 * 60 * 1000).run();
    const attempts = await env.DB.prepare(
      "SELECT COUNT(*) AS total FROM pair_attempts WHERE ip_hash = ? AND created_at > ?"
    ).bind(ipHash, since).first<{ total: number }>();
    if ((attempts?.total || 0) >= 8) return response({ error: "activation_rate_limited" }, 429);
    await env.DB.prepare("INSERT INTO pair_attempts (ip_hash, created_at) VALUES (?, ?)").bind(ipHash, Date.now()).run();

    const codeHash = await sha256(code);
    const now = Date.now();
    const consumed = await env.DB.prepare(
      "UPDATE activation_codes SET consumed_at = ?, consumed_install_id = ? WHERE code_hash = ? AND consumed_at IS NULL AND expires_at > ? RETURNING code_hash"
    ).bind(now, installId, codeHash, now).first<{ code_hash: string }>();
    if (!consumed) return response({ error: "activation_code_invalid_or_used" }, 403);

    const deviceId = crypto.randomUUID();
    const tokenHash = await sha256(deviceSecret);
    const leaseExpiresAt = now + 72 * 60 * 60 * 1000;
    const deviceName = technicalName;
    await env.DB.prepare(
      "INSERT INTO devices (device_id, install_id, device_name, display_name, platform, token_hash, status, created_at, expires_at, approved_at, activated_at, last_seen_at, lease_expires_at) VALUES (?, ?, ?, ?, ?, ?, 'approved', ?, ?, ?, ?, ?, ?)"
    ).bind(deviceId, installId, deviceName, deviceName, platform, tokenHash, now, now, now, now, now, leaseExpiresAt).run();
    return response({ activated: true, deviceId, displayName: deviceName, leaseExpiresAt, offlineLeaseHours: 72 }, 201);
  }

  if (request.method === "GET" && path === "/v1/config") {
    const device = await requireDevice(request, env);
    if (!device) return response({ error: "device_not_authorized" }, 403);
    const row = await env.DB.prepare(
      "SELECT version, update_id, payload, checksum, published_at FROM config WHERE config_id = 1"
    ).first<{ version: number; update_id: string; payload: string; checksum: string; published_at: number }>();
    await env.DB.prepare("UPDATE devices SET last_seen_at = ? WHERE device_id = ?").bind(Date.now(), device.id).run();
    if (!row) return response({ error: "no_configuration_published" }, 404);
    const sinceVersion = Number(url.searchParams.get("sinceVersion") || "0");
    const leaseExpiresAt = Date.now() + 72 * 60 * 60 * 1000;
    await env.DB.prepare("UPDATE devices SET last_seen_at = ?, lease_expires_at = ? WHERE device_id = ? AND status = 'approved'")
      .bind(Date.now(), leaseExpiresAt, device.id).run();
    const metadata = {
      schemaVersion: 1,
      leaseExpiresAt,
      version: row.version,
      updateId: row.update_id,
      checksum: row.checksum,
      publishedAt: row.published_at,
    };
    // Il Player controlla ad ogni avvio, ma scarica la lista solo se la versione è cambiata.
    if (Number.isSafeInteger(sinceVersion) && sinceVersion >= row.version) {
      return response({ ...metadata, unchanged: true });
    }
    const config = JSON.parse(row.payload) as Record<string, unknown>;
    if (typeof config.epgSourceEncrypted === "string") {
      config.epgSourceUrl = await decryptEpgSource(config.epgSourceEncrypted, env.SKY_ADMIN_KEY);
      delete config.epgSourceEncrypted;
    }
    return response({ ...metadata, unchanged: false, config });
  }

  if (request.method === "GET" && path === "/v1/epg") {
    const device = await requireDevice(request, env);
    if (!device) return response({ error: "device_not_authorized" }, 403);
    const source = url.searchParams.get("source") || "";
    const sources: Record<string, string> = {
      sky: "https://iptv-org.github.io/epg/guides/it/guidatv.sky.it.xml",
      iptv: "https://iptv-epg.org/files/epg-it.xml.gz",
      epgshare: "https://epgshare01.online/epgshare01/epg_ripper_IT1.xml.gz",
      open3: "https://www.open-epg.com/files/italy3.xml.gz",
      open8: "https://www.open-epg.com/files/italy8.xml.gz",
    };
    const upstreamUrl = sources[source];
    if (!upstreamUrl) return response({ error: "invalid_epg_source" }, 400);
    const upstream = await fetch(upstreamUrl, {
      headers: { accept: "application/xml,text/xml,application/gzip,*/*" },
      cf: { cacheTtl: 1800, cacheEverything: true },
    });
    if (!upstream.ok) {
      return response({ error: "epg_source_unavailable", upstreamStatus: upstream.status }, 502);
    }
    return new Response(upstream.body, {
      status: 200,
      headers: {
        "content-type": upstream.headers.get("content-type") || "application/xml; charset=utf-8",
        "cache-control": "private, max-age=1800",
      },
    });
  }

  if (request.method === "GET" && path === "/v2/config/manifest") {
    const device = await requireDevice(request, env);
    if (!device) return response({ error: "device_not_authorized" }, 403);
    const row = await env.DB.prepare(
      "SELECT version, update_id, payload, checksum, published_at FROM config WHERE config_id = 1"
    ).first<{ version: number; update_id: string; payload: string; checksum: string; published_at: number }>();
    if (!row) return response({ error: "no_configuration_published" }, 404);
    const now = Date.now();
    const leaseExpiresAt = now + 72 * 60 * 60 * 1000;
    await env.DB.prepare("UPDATE devices SET last_seen_at = ?, lease_expires_at = ? WHERE device_id = ? AND status = 'approved'")
      .bind(now, leaseExpiresAt, device.id).run();
    const sinceVersion = Number(url.searchParams.get("sinceVersion") || "0");
    const metadata = {
      schemaVersion: 2,
      leaseExpiresAt,
      version: row.version,
      updateId: row.update_id,
      checksum: row.checksum,
      publishedAt: row.published_at,
    };
    if (Number.isSafeInteger(sinceVersion) && sinceVersion >= row.version) {
      return response({ ...metadata, unchanged: true });
    }
    const config = JSON.parse(row.payload) as Record<string, unknown>;
    const channels = Array.isArray(config.channels) ? config.channels : [];
    if (typeof config.epgSourceEncrypted === "string") {
      config.epgSourceUrl = await decryptEpgSource(config.epgSourceEncrypted, env.SKY_ADMIN_KEY);
      delete config.epgSourceEncrypted;
    }
    return response({
      ...metadata,
      unchanged: false,
      channelCount: channels.length,
      chunkSize: 80,
      chunkCount: Math.ceil(channels.length / 80),
      categories: Array.isArray(config.categories) ? config.categories : [],
      ...(typeof config.epgSourceUrl === "string" ? { epgSourceUrl: config.epgSourceUrl } : {}),
    });
  }

  if (request.method === "GET" && path === "/v2/config/chunk") {
    const device = await requireDevice(request, env);
    if (!device) return response({ error: "device_not_authorized" }, 403);
    const version = Number(url.searchParams.get("version") || "0");
    const index = Number(url.searchParams.get("index") || "-1");
    if (!Number.isSafeInteger(version) || version < 1 || !Number.isSafeInteger(index) || index < 0 || index > 124) {
      return response({ error: "invalid_chunk_request" }, 400);
    }
    const row = await env.DB.prepare("SELECT version, payload FROM config WHERE config_id = 1")
      .first<{ version: number; payload: string }>();
    if (!row) return response({ error: "no_configuration_published" }, 404);
    if (row.version !== version) return response({ error: "configuration_changed", version: row.version }, 409);
    const config = JSON.parse(row.payload) as Record<string, unknown>;
    const channels = Array.isArray(config.channels) ? config.channels : [];
    const start = index * 80;
    if (start >= channels.length && !(channels.length === 0 && index === 0)) {
      return response({ error: "chunk_not_found" }, 404);
    }
    return response({
      version,
      index,
      chunkCount: Math.ceil(channels.length / 80),
      channels: channels.slice(start, start + 80),
    });
  }

  if (request.method === "POST" && path === "/v2/admin/config/uploads/start") {
    if (!(await requireAdmin(request, env))) return response({ error: "unauthorized" }, 401);
    let body: any;
    try { body = await readJson(request, 64_000); } catch { return response({ error: "invalid_json" }, 400); }
    const uploadId = typeof body.uploadId === "string" ? body.uploadId : "";
    const updateId = typeof body.updateId === "string" ? body.updateId : "";
    const expectedChannels = Number(body.expectedChannels);
    const categories = body.categories == null ? [] : body.categories;
    if (!/^[A-Za-z0-9-]{8,100}$/.test(uploadId) ||
        typeof updateId !== "string" || updateId.length < 8 || updateId.length > 100 ||
        !Number.isSafeInteger(expectedChannels) || expectedChannels < 1 || expectedChannels > 10000 ||
        !Array.isArray(categories) || categories.length > 100 ||
        !categories.every((category: unknown) => typeof category === "string" && category.trim().length > 0 && category.length <= 80) ||
        (body.epgSourceUrl != null && !validHttpUrl(body.epgSourceUrl))) {
      return response({ error: "invalid_upload_manifest" }, 400);
    }
    const epgSourceEncrypted = typeof body.epgSourceUrl === "string" && body.epgSourceUrl.trim()
      ? await encryptEpgSource(body.epgSourceUrl.trim(), env.SKY_ADMIN_KEY)
      : null;
    const now = Date.now();
    await env.DB.prepare("DELETE FROM config_uploads WHERE created_at < ?").bind(now - 6 * 60 * 60 * 1000).run();
    await env.DB.prepare("INSERT OR REPLACE INTO config_uploads (upload_id, update_id, expected_channels, categories_json, epg_source_encrypted, created_at) VALUES (?, ?, ?, ?, ?, ?)")
      .bind(uploadId, updateId, expectedChannels, JSON.stringify([...new Set(categories.map((value: string) => value.trim()))]), epgSourceEncrypted, now).run();
    return response({ started: true, uploadId, expectedChannels, chunkSize: 80 });
  }

  if (request.method === "POST" && path === "/v2/admin/config/uploads/chunk") {
    if (!(await requireAdmin(request, env))) return response({ error: "unauthorized" }, 401);
    let body: any;
    try { body = await readJson(request, 900_000); } catch (error) {
      return response({ error: String(error).includes("PAYLOAD_TOO_LARGE") ? "chunk_too_large" : "invalid_json" }, 400);
    }
    const uploadId = typeof body.uploadId === "string" ? body.uploadId : "";
    const index = Number(body.index);
    const channels = body.channels;
    if (!/^[A-Za-z0-9-]{8,100}$/.test(uploadId) || !Number.isSafeInteger(index) || index < 0 || index > 124 ||
        !Array.isArray(channels) || channels.length < 1 || channels.length > 80 || !channels.every(validChannel)) {
      return response({ error: "invalid_upload_chunk" }, 400);
    }
    const upload = await env.DB.prepare("SELECT upload_id FROM config_uploads WHERE upload_id = ? AND created_at > ?")
      .bind(uploadId, Date.now() - 6 * 60 * 60 * 1000).first<{ upload_id: string }>();
    if (!upload) return response({ error: "upload_not_found_or_expired" }, 404);
    const channelsJson = JSON.stringify(channels);
    if (new TextEncoder().encode(channelsJson).byteLength > 800_000) return response({ error: "chunk_too_large" }, 413);
    const checksum = await sha256(channelsJson);
    await env.DB.prepare("INSERT OR REPLACE INTO config_upload_chunks (upload_id, chunk_index, channels_json, channel_count, checksum) VALUES (?, ?, ?, ?, ?)")
      .bind(uploadId, index, channelsJson, channels.length, checksum).run();
    return response({ stored: true, uploadId, index, channelCount: channels.length, checksum });
  }

  if (request.method === "POST" && path === "/v2/admin/config/uploads/commit") {
    if (!(await requireAdmin(request, env))) return response({ error: "unauthorized" }, 401);
    let body: any;
    try { body = await readJson(request, 16_384); } catch { return response({ error: "invalid_json" }, 400); }
    const uploadId = typeof body.uploadId === "string" ? body.uploadId : "";
    if (!/^[A-Za-z0-9-]{8,100}$/.test(uploadId)) return response({ error: "invalid_upload_id" }, 400);
    const upload = await env.DB.prepare(
      "SELECT upload_id, update_id, expected_channels, categories_json, epg_source_encrypted FROM config_uploads WHERE upload_id = ? AND created_at > ?"
    ).bind(uploadId, Date.now() - 6 * 60 * 60 * 1000)
      .first<{ upload_id: string; update_id: string; expected_channels: number; categories_json: string; epg_source_encrypted: string | null }>();
    if (!upload) return response({ error: "upload_not_found_or_expired" }, 404);
    const stored = await env.DB.prepare(
      "SELECT chunk_index, channels_json, channel_count, checksum FROM config_upload_chunks WHERE upload_id = ? ORDER BY chunk_index ASC"
    ).bind(uploadId).all<{ chunk_index: number; channels_json: string; channel_count: number; checksum: string }>();
    const chunks = stored.results || [];
    if (chunks.length === 0 || chunks.some((chunk, index) => chunk.chunk_index !== index)) {
      return response({ error: "upload_chunks_incomplete", receivedChunks: chunks.length });
    }
    const channels: Channel[] = [];
    for (const chunk of chunks) {
      if (await sha256(chunk.channels_json) !== chunk.checksum) return response({ error: "upload_chunk_checksum_mismatch", index: chunk.chunk_index }, 409);
      let parsed: unknown;
      try { parsed = JSON.parse(chunk.channels_json); } catch { return response({ error: "upload_chunk_invalid", index: chunk.chunk_index }, 400); }
      if (!Array.isArray(parsed) || parsed.length !== chunk.channel_count || !parsed.every(validChannel)) {
        return response({ error: "upload_chunk_invalid", index: chunk.chunk_index }, 400);
      }
      channels.push(...parsed);
    }
    if (channels.length !== upload.expected_channels || channels.length > 10000) {
      return response({ error: "upload_channel_count_mismatch", expected: upload.expected_channels, received: channels.length }, 409);
    }
    const categories = JSON.parse(upload.categories_json) as string[];
    const payload = JSON.stringify({
      schemaVersion: 1,
      updateId: upload.update_id,
      categories,
      channels,
      ...(upload.epg_source_encrypted ? { epgSourceEncrypted: upload.epg_source_encrypted } : {}),
    });
    if (new TextEncoder().encode(payload).byteLength > 1_500_000) {
      return response({ error: "configuration_too_large", maxBytes: 1_500_000 }, 413);
    }
    const checksum = await sha256(payload);
    const publishedAt = Date.now();
    const results = await env.DB.batch([
      env.DB.prepare(
        "INSERT INTO config (config_id, version, update_id, payload, checksum, published_at) VALUES (1, 1, ?, ?, ?, ?) ON CONFLICT(config_id) DO UPDATE SET version = config.version + 1, update_id = excluded.update_id, payload = excluded.payload, checksum = excluded.checksum, published_at = excluded.published_at RETURNING version"
      ).bind(upload.update_id, payload, checksum, publishedAt),
      env.DB.prepare("DELETE FROM config_upload_chunks WHERE upload_id = ?").bind(uploadId),
      env.DB.prepare("DELETE FROM config_uploads WHERE upload_id = ?").bind(uploadId),
    ]);
    const publishedResult = results[0] as D1Result<{ version: number }>;
    const version = Number(publishedResult.results?.[0]?.version || 0);
    if (!version) throw new Error("CONFIG_PUBLISH_FAILED");
    return response({ published: true, version, updateId: upload.update_id, checksum, publishedAt, channelCount: channels.length, chunkCount: chunks.length });
  }

  if (request.method === "POST" && path === "/v1/admin/config/publish") {
    if (!(await requireAdmin(request, env))) return response({ error: "unauthorized" }, 401);
    let body: PublishedConfig;
    try { body = await readJson(request); } catch (e) {
      return response({ error: String(e) === "Error: PAYLOAD_TOO_LARGE" ? "payload_too_large" : "invalid_json" }, 400);
    }
    if (!body || body.schemaVersion !== 1 || !Array.isArray(body.channels) || body.channels.length > 1000 ||
        typeof body.updateId !== "string" || body.updateId.length < 8 || body.updateId.length > 100 ||
        !body.channels.every(validChannel) ||
        (body.epgSourceUrl != null && !validHttpUrl(body.epgSourceUrl)) ||
        (body.categories != null && (!Array.isArray(body.categories) || body.categories.length > 100 ||
          !body.categories.every((category: unknown) => typeof category === "string" && category.trim().length > 0 && category.length <= 80)))) {
      return response({ error: "invalid_configuration" }, 400);
    }
    const categories = Array.isArray(body.categories)
      ? [...new Set(body.categories.map((category: string) => category.trim()))]
      : [];
    const epgSourceEncrypted = body.epgSourceUrl?.trim()
      ? await encryptEpgSource(body.epgSourceUrl.trim(), env.SKY_ADMIN_KEY)
      : undefined;
    const payload = JSON.stringify({
      schemaVersion: 1,
      updateId: body.updateId,
      categories,
      channels: body.channels,
      ...(epgSourceEncrypted ? { epgSourceEncrypted } : {}),
    });
    const checksum = await sha256(payload);
    const publishedAt = Date.now();
    const published = await env.DB.prepare(
      "INSERT INTO config (config_id, version, update_id, payload, checksum, published_at) VALUES (1, 1, ?, ?, ?, ?) ON CONFLICT(config_id) DO UPDATE SET version = config.version + 1, update_id = excluded.update_id, payload = excluded.payload, checksum = excluded.checksum, published_at = excluded.published_at RETURNING version"
    ).bind(body.updateId, payload, checksum, publishedAt).first<{ version: number }>();
    if (!published) throw new Error("CONFIG_PUBLISH_FAILED");
    const version = published.version;
    return response({ published: true, version, updateId: body.updateId, checksum, publishedAt, channelCount: body.channels.length });
  }

  if (request.method === "GET" && path === "/v1/admin/devices") {
    if (!(await requireAdmin(request, env))) return response({ error: "unauthorized" }, 401);
    const result = await env.DB.prepare(
      "SELECT device_id AS deviceId, device_name AS deviceName, COALESCE(display_name, device_name) AS displayName, platform, status, created_at AS createdAt, approved_at AS approvedAt, activated_at AS activatedAt, revoked_at AS revokedAt, last_seen_at AS lastSeenAt, lease_expires_at AS leaseExpiresAt FROM devices ORDER BY created_at DESC LIMIT 500"
    ).all();
    const devices = result.results || [];
    return response({
      devices,
      authorizedDevices: devices.filter((device: any) => device.status === "approved"),
      revokedDevices: devices.filter((device: any) => device.status === "revoked"),
    });
  }

  if (request.method === "POST" && path === "/v2/admin/devices/rename") {
    if (!(await requireAdmin(request, env))) return response({ error: "unauthorized" }, 401);
    let body: any;
    try { body = await readJson(request, 8_192); } catch { return response({ error: "invalid_json" }, 400); }
    const deviceId = typeof body.deviceId === "string" ? body.deviceId.trim() : "";
    const displayName = typeof body.displayName === "string" ? body.displayName.trim().replace(/\s+/g, " ").slice(0, 60) : "";
    if (!deviceId || !displayName) return response({ error: "invalid_device_name" }, 400);
    const result = await env.DB.prepare(
      "UPDATE devices SET display_name = ? WHERE device_id = ? AND status IN ('approved', 'revoked')"
    ).bind(displayName, deviceId).run();
    if (!result.meta.changes) return response({ error: "device_not_found" }, 404);
    return response({ renamed: true, deviceId, displayName });
  }

  if (request.method === "POST" && path === "/v1/admin/devices/revoke") {
    if (!(await requireAdmin(request, env))) return response({ error: "unauthorized" }, 401);
    let body: any;
    try { body = await readJson(request, 8_192); } catch { return response({ error: "invalid_json" }, 400); }
    const deviceId = typeof body.deviceId === "string" ? body.deviceId : "";
    if (!deviceId) return response({ error: "invalid_device_id" }, 400);
    const result = await env.DB.prepare(
      "UPDATE devices SET status = 'revoked', pairing_code = NULL, revoked_at = ? WHERE device_id = ? AND status = 'approved'"
    ).bind(Date.now(), deviceId).run();
    if (!result.meta.changes) return response({ error: "device_not_found" }, 404);
    return response({ revoked: true, deviceId });
  }

  return response({ error: "not_found" }, 404);
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      return withCors(request, await route(request, env));
    } catch (error) {
      console.error("Sky Cloud Sync request failed:", error instanceof Error ? error.message : "unknown");
      return withCors(request, response({ error: "internal_error" }, 500));
    }
  },
};
