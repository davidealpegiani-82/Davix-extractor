import fs from "node:fs";

const file = new URL("../catalogs/it/free-services.json", import.meta.url);
const catalog = JSON.parse(fs.readFileSync(file, "utf8"));
const allowedAccess = new Set([
  "free",
  "free-with-account",
  "free-with-ads",
  "free-on-supported-devices",
  "mixed-free-and-subscription",
  "subscription",
]);
const allowedHosts = new Set([
  "www.raiplay.it",
  "raiplay.it",
  "mediasetinfinity.mediaset.it",
  "www.la7.it",
  "la7.it",
  "pluto.tv",
  "www.pluto.tv",
  "www.samsungtvplus.com",
  "samsungtvplus.com",
  "www.lg.com",
  "lg.com",
  "www.dazn.com",
  "dazn.com",
  "www.nowtv.it",
  "nowtv.it",
]);
const errors = [];
const ids = new Set();

if (catalog.schemaVersion !== 1) errors.push("schemaVersion deve essere 1");
if (catalog.country !== "IT" || catalog.language !== "it") errors.push("Il catalogo deve essere italiano");

for (const [index, service] of (catalog.services ?? []).entries()) {
  const label = `services[${index}]`;
  for (const field of ["id", "name", "provider", "access", "liveUrl", "homeUrl"]) {
    if (typeof service[field] !== "string" || !service[field].trim()) {
      errors.push(`${label}: campo ${field} mancante`);
    }
  }
  if (ids.has(service.id)) errors.push(`${label}: ID duplicato ${service.id}`);
  ids.add(service.id);
  if (!allowedAccess.has(service.access)) errors.push(`${label}: access non riconosciuto`);
  for (const field of ["liveUrl", "homeUrl"]) {
    try {
      const url = new URL(service[field]);
      if (url.protocol !== "https:") errors.push(`${label}: ${field} deve usare HTTPS`);
      if (!allowedHosts.has(url.hostname)) errors.push(`${label}: dominio non autorizzato ${url.hostname}`);
      if (url.username || url.password || url.search || url.hash) {
        errors.push(`${label}: ${field} non deve contenere credenziali, query o fragment`);
      }
      if (/\.(m3u8?|mpd|mp4)(?:$|\/)/i.test(url.pathname)) {
        errors.push(`${label}: non inserire URL di flussi video diretti nel catalogo dei servizi`);
      }
    } catch {
      errors.push(`${label}: ${field} non è un URL valido`);
    }
  }
  if (!Array.isArray(service.categories) || service.categories.length === 0) {
    errors.push(`${label}: categories deve contenere almeno una categoria`);
  }
}

if (errors.length) {
  console.error(errors.map((error) => `- ${error}`).join("\n"));
  process.exit(1);
}
console.log(`Catalogo italiano valido: ${catalog.services.length} servizi ufficiali.`);
