#!/usr/bin/env python3
from pathlib import Path

path = Path("android/app/src/main/java/it/davidealpegiani/skyunoextractor/MainActivity.kt")
text = path.read_text(encoding="utf-8")

old_button = (
    '        button("🤖  Importa report Xtream · automatico", true) { showXtreamReportImportDialog() }\n'
    '        button("🔗  Configura URL M3U ed EPG", false) { showIptvSourceSettings() }'
)
new_button = (
    '        button("🤖  Importa report Xtream · automatico", true) { showXtreamReportImportDialog() }\n'
    '        button("🇮🇹  Importa server Vegeta IT", true) { importVegetaItServers() }\n'
    '        button("🔗  Configura URL M3U ed EPG", false) { showIptvSourceSettings() }'
)
if old_button not in text:
    raise SystemExit("Blocco bottone non trovato in MainActivity")
text = text.replace(old_button, new_button, 1)

old_fn = (
    '        }, "DAVIX-Xtream-AutoImport").apply { isDaemon = true }.start()\n'
    '    }\n'
    '\n'
    '    private fun scanImportedStreams() {'
)
new_fn = (
    '        }, "DAVIX-Xtream-AutoImport").apply { isDaemon = true }.start()\n'
    '    }\n'
    '\n'
    '    private fun importVegetaItServers() {\n'
    '        if (importInProgress) {\n'
    "            toast(\"È già in corso un'importazione.\")\n"
    '            return\n'
    '        }\n'
    '        importInProgress = true\n'
    '        status.text = "Connessione al catalogo Vegeta IT…"\n'
    '        updateImportProgress("Scarico elenco server italiani Vegeta…", null)\n'
    '        Thread({\n'
    '            var temporaryCatalog: java.io.File? = null\n'
    '            try {\n'
    '                val result = VegetaItImporter.importAll(this) { message ->\n'
    '                    updateImportProgress(message, null)\n'
    '                }\n'
    '                val vegetaChannels = VegetaItPlaylistStore.load(this)\n'
    '                val m3u = StringBuilder("#EXTM3U\\n")\n'
    '                for (ch in vegetaChannels) {\n'
    '                    m3u.append("#EXTINF:-1")\n'
    '                    if (ch.logo.startsWith("http://", true) || ch.logo.startsWith("https://", true)) {\n'
    '                        m3u.append(" tvg-logo=\\"").append(ch.logo.replace("\\"", "&quot;")).append(\'"\')\n'
    '                    }\n'
    '                    val group = ch.category.ifBlank { "Vegeta IT" }\n'
    '                    m3u.append(" group-title=\\"").append(group.replace("\\"", "&quot;")).append("\\",")\n'
    "                        .append(ch.name.replace('\\n', ' ').replace('\\r', ' '))\n"
    "                        .append('\\n').append(ch.url).append('\\n')\n"
    '                }\n'
    '                cacheDir.listFiles()\n'
    '                    ?.filter { it.isFile && it.name.startsWith("davix-vegeta-") && it.name.endsWith(".m3u") }\n'
    '                    ?.forEach { it.delete() }\n'
    '                val importedFile = java.io.File(cacheDir, "davix-vegeta-${System.currentTimeMillis()}.m3u")\n'
    '                importedFile.writeBytes(m3u.toString().toByteArray(StandardCharsets.UTF_8))\n'
    '                temporaryCatalog = importedFile\n'
    '                runOnUiThread {\n'
    '                    if (isFinishing || isDestroyed) {\n'
    '                        importInProgress = false\n'
    '                        return@runOnUiThread\n'
    '                    }\n'
    '                    importInProgress = false\n'
    '                    val errHint = if (result.errors.isEmpty()) "" else " · avvisi: ${result.errors.size}"\n'
    '                    status.text = "Vegeta IT: ${result.channelsImported} canali da ${result.serversOk}/${result.serversTried} server$errHint. Inserimento in sezione separata…"\n'
    '                    pendingImportCategory = "Vegeta IT"\n'
    '                    loadSavedPlaylist(importedFile, deleteAfterImport = true)\n'
    '                }\n'
    '            } catch (error: Exception) {\n'
    '                temporaryCatalog?.delete()\n'
    '                runOnUiThread {\n'
    '                    importInProgress = false\n'
    '                    finishImportProgress()\n'
    '                    if (!isFinishing && !isDestroyed) {\n'
    '                        status.text = "Importazione Vegeta IT non riuscita: ${error.message ?: \"errore di rete\"}"\n'
    '                        toast(status.text.toString())\n'
    '                    }\n'
    '                }\n'
    '            }\n'
    '        }, "DAVIX-Vegeta-Import").apply { isDaemon = true }.start()\n'
    '    }\n'
    '\n'
    '    private fun scanImportedStreams() {'
)

if old_fn not in text:
    raise SystemExit("Ancora funzione Xtream non trovata")
text = text.replace(old_fn, new_fn, 1)

if "importVegetaItServers" not in text or "class MainActivity" not in text:
    raise SystemExit("Patch non valida")

path.write_text(text, encoding="utf-8")
print("MainActivity aggiornato:", path.stat().st_size, "bytes")
