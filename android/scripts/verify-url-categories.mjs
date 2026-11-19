import { readFileSync } from "node:fs";
import assert from "node:assert/strict";

const source = readFileSync("android/app/src/main/java/it/davidealpegiani/skyunoextractor/MainActivity.kt", "utf8");
const urlImportStart = source.indexOf("private fun importPlaylistFromUrl()");
const urlImportEnd = source.indexOf("private fun showCloudSyncSettings()", urlImportStart);
assert.ok(urlImportStart >= 0 && urlImportEnd > urlImportStart, "Remote URL import flow must remain available");
const urlImport = source.slice(urlImportStart, urlImportEnd);
assert.ok(urlImport.includes("inferUrlCategoryPath(channel.copy(category = sourceGroup))"), "URL imports must classify using the original provider group-title");
assert.ok(urlImport.includes("isLegacyProviderGroup"), "Legacy provider group-title folders must migrate into the LIVE/VOD hierarchy on URL reimport");
assert.ok(urlImport.includes("savedPath.equals(sourcePath, ignoreCase = true)"), "Manual category overrides that differ from the provider group-title must remain preserved");
assert.ok(urlImport.includes("isAutomaticallyClassifiedUrlCategory(savedCategory)"), "Previously generated categories must be recalculated");
assert.ok(urlImport.includes("val preserveManualCategory = savedCategory.isNotBlank() &&") && urlImport.includes("if (preserveManualCategory && !isLegacyProviderGroup)"), "User-defined categories must be preserved unless they are unchanged legacy provider group-titles");
assert.ok(urlImport.includes("existing.category = channel.category"), "Reimporting a URL playlist must migrate existing automatically classified channels");
assert.ok(urlImport.includes("reorganized++"), "The import report must count channels reorganized");
assert.ok(urlImport.includes("categories.removeAll"), "Empty obsolete automatic folders must be pruned after reimport");

const classifierStart = source.indexOf("private fun inferUrlCategoryPath(channel: TvChannel)");
const classifierEnd = source.indexOf("private fun isAutomaticallyClassifiedUrlCategory(raw: String)", classifierStart);
assert.ok(classifierStart >= 0 && classifierEnd > classifierStart, "Dedicated URL classifier must exist");
const classifier = source.slice(classifierStart, classifierEnd);
for (const required of [
  'path.contains("/live/")',
  'path.contains("/movie/")',
  'path.contains("/series/")',
  'episodePattern.containsMatchIn(name)',
  'episodePattern.containsMatchIn(url)',
  'val strongVod = vodPath || seriesEvidence || movieEvidence',
  'val isVod = strongVod || (liveStreamExtension && (seriesEvidence || movieEvidence))',
  'VOD > SERIE > $genre',
  'VOD > FILM > $genre',
  'LIVE > $liveCategory',
  '"Sport > Calcio"',
  '"Sport > Altri sport"',
  '"Altri film"',
  '"Altre serie"',
  '"Crime e Thriller"',
  '"Animazione e Famiglia"'
]) {
  assert.ok(classifier.includes(required), `URL classifier is missing expected rule: ${required}`);
}
assert.ok(classifier.includes('token("vod")'), "VOD marker must use token matching to avoid substring false positives");
assert.ok(classifier.includes("val isSeries = seriesEvidence && !moviePath"), "A movie path must not be mistaken for a series");
assert.ok(classifier.includes("val isVod = strongVod || (liveStreamExtension && (seriesEvidence || movieEvidence))"), "Episode and VOD evidence must override generic LIVE labels and stream extensions");
assert.ok(classifier.includes("explicitLiveLabel || liveStreamExtension") === false, "Generic LIVE labels must not override strong VOD evidence");
assert.ok(classifier.includes("!vodPath"), "HLS VOD paths must not be mistaken for LIVE merely because they use .m3u8");
assert.ok(!classifier.includes('return normalizeCategoryPath(channel.category)'), "Provider group-title must not be copied blindly as a new folder");

const autoStart = source.indexOf("private fun isAutomaticallyClassifiedUrlCategory(raw: String)");
const autoEnd = source.indexOf("private fun inferCategoryPath(channel: TvChannel)", autoStart);
assert.ok(autoStart >= 0 && autoEnd > autoStart, "Automatic category detection must exist");
const autoClassifier = source.slice(autoStart, autoEnd);
assert.ok(autoClassifier.includes('"LIVE > Sport > Altri sport"'), "The consolidated live sport folder must be recognized during migration");
assert.ok(autoClassifier.includes('"VOD > FILM > Altri film"'), "The new film taxonomy must be recognized during migration");
assert.ok(autoClassifier.includes('"VOD > SERIE > Altre serie"'), "The new series taxonomy must be recognized during migration");
assert.ok(autoClassifier.includes('path.startsWith("$it > ")'), "Legacy auto-generated folders must be migrated too");

const rendererStart = source.indexOf("fun addFolderGroups(");
const rendererEnd = source.indexOf("addFolderGroups(selected", rendererStart);
assert.ok(rendererStart >= 0 && rendererEnd > rendererStart, "Folder renderer must remain available");
const renderer = source.slice(rendererStart, rendererEnd);
assert.ok(renderer.includes("data class FolderNode"), "Category paths must be rendered as a folder tree");
assert.ok(renderer.includes("renderFolderNode(child, depth + 1)"), "Nested categories must expand as real subfolders");
assert.ok(renderer.includes("countChannels(node)"), "Folder counts must include nested descendants");
console.log("DAVIX hierarchical URL category checks passed.");
