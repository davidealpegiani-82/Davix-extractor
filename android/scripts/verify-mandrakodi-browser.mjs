import { readFileSync } from "node:fs";
import assert from "node:assert/strict";

const root = new URL("../", import.meta.url);
const read = (path) => readFileSync(new URL(path, root), "utf8");
const client = read("app/src/main/java/it/davidealpegiani/skyunoextractor/MandraKodiCatalogClient.kt");
const settings = read("app/src/main/java/it/davidealpegiani/skyunoextractor/IptvSourceSettings.kt");
const activity = read("app/src/main/java/it/davidealpegiani/skyunoextractor/MainActivity.kt");

assert(client.includes("data/disclaimer.json"), "MandraKodi root menu URL missing");
assert(client.includes("connection.instanceFollowRedirects = false"), "Redirects must be checked before following them");
assert(client.includes("resolveSafeMenuUrl"), "Authenticated menu navigation must require HTTPS");
assert(client.includes('setRequestProperty("User-Agent", userAgent)'), "Authenticated User-Agent missing");
assert(client.includes("MAX_ENTRIES = 1500"), "Menu entry limit missing");
assert(client.includes("maxResponseBytes: Int = 12 * 1024 * 1024"), "Response size limit missing");
assert(client.includes('listOf("myresolve", "regexPage", "personal", "chrome", "yatse", "magnet", "f4m", "pvr", "apk").any(item::has)'), "Resolver actions must be excluded");
assert(client.includes('host == "127.0.0.1"') && client.includes('host.startsWith("192.168.")'), "Local/private URL guard missing");
assert(settings.includes('MandraKodi2@@${mandraKodiAddonVersion(context)}@@$password@@$deviceId'), "Dynamic add-on version missing from User-Agent");
assert(activity.includes("private fun browseMandraKodiCatalog()"), "MandraKodi browser UI missing");
assert(activity.includes("private fun importMandraKodiChannels"), "MandraKodi channel import path missing");
assert(activity.includes("Esplora catalogo MandraKodi"), "MandraKodi browser entry point missing");

console.log("MandraKodi authenticated menu browser checks passed.");
