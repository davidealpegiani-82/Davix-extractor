import { readFileSync } from "node:fs";
import assert from "node:assert/strict";

const source = readFileSync("android/app/src/main/java/it/davidealpegiani/skyunoextractor/MainActivity.kt", "utf8");
const urlImportStart = source.indexOf("private fun importPlaylistFromUrl()");
const urlImportEnd = source.indexOf("private fun showCloudSyncSettings()", urlImportStart);
assert.ok(urlImportStart >= 0 && urlImportEnd > urlImportStart, "Remote URL import flow must remain available");
const urlImport = source.slice(urlImportStart, urlImportEnd);
assert.ok(urlImport.includes("inferUrlCategoryPath(channel)"), "Remote URL imports must use the hierarchical content classifier");
assert.ok(!urlImport.includes("inferCategoryPath(channel)"), "Remote URL imports must not use the legacy flat classifier");
assert.ok(urlImport.includes("legacyAutoCategoryPrefixes"), "Existing automatic categories must be identified for safe reorganization");
assert.ok(urlImport.includes("existing.category = channel.category"), "Reimporting a URL playlist must migrate existing automatically classified channels");
assert.ok(urlImport.includes("reorganized++"), "The import report must count channels reorganized");

const classifierStart = source.indexOf("private fun inferUrlCategoryPath(channel: TvChannel)");
const classifierEnd = source.indexOf("private fun inferCategoryPath(channel: TvChannel)", classifierStart);
assert.ok(classifierStart >= 0 && classifierEnd > classifierStart, "Dedicated URL classifier must exist");
const classifier = source.slice(classifierStart, classifierEnd);
for (const required of [
  'path.contains("/live/")',
  'path.contains("/movie/")',
  'path.contains("/series/")',
  'VOD > SERIE > $genre',
  'VOD > FILM > $genre',
  'LIVE > $liveCategory',
  '"Sport > Calcio"',
  '"Documentari"',
  '"Altri film"',
  '"Altre serie"'
]) {
  assert.ok(classifier.includes(required), `URL classifier is missing expected rule: ${required}`);
}
assert.ok(classifier.includes("token(\"vod\")"), "VOD marker must use token matching to avoid substring false positives");
assert.ok(classifier.includes("val isSeries = seriesHint"), "Generic VOD categories must not be guessed as TV series");
assert.ok(!classifier.includes('return normalizeCategoryPath(channel.category)'), "Provider group-title must not be copied blindly as a new folder");

const rendererStart = source.indexOf("fun addFolderGroups(");
const rendererEnd = source.indexOf("addFolderGroups(selected", rendererStart);
assert.ok(rendererStart >= 0 && rendererEnd > rendererStart, "Folder renderer must remain available");
const renderer = source.slice(rendererStart, rendererEnd);
assert.ok(renderer.includes("data class FolderNode"), "Category paths must be rendered as a folder tree");
assert.ok(renderer.includes("renderFolderNode(child, depth + 1)"), "Nested categories must expand as real subfolders");
assert.ok(renderer.includes("countChannels(node)"), "Folder counts must include nested descendants");
console.log("DAVIX hierarchical URL category checks passed.");
