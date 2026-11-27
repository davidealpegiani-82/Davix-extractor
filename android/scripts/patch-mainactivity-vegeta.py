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

new_fn = '''        }, "DAVIX-Xtream-AutoImport").apply { isDaemon = true }.start()
    }

    private fun importVegetaItServers() {
        if (importInProgress) {
            toast("È già in corso un'importazione.")
            return
        }
        importInProgress = true
        status.text = "Connessione al catalogo Vegeta IT…"
        updateImportProgress("Scarico elenco server italiani Vegeta…", null)
        Thread({
            var temporaryCatalog: java.io.File? = null
            try {
                val result = VegetaItImporter.importAll(this) { message ->
                    updateImportProgress(message, null)
                }
                val vegetaChannels = VegetaItPlaylistStore.load(this)
                val m3u = StringBuilder("#EXTM3U\\n")
                for (ch in vegetaChannels) {
                    m3u.append("#EXTINF:-1")
                    if (ch.logo.startsWith("http://", true) || ch.logo.startsWith("https://", true)) {
                        m3u.append(" tvg-logo=\\"").append(ch.logo.replace("\\"", "&quot;")).append('"')
                    }
                    val group = ch.category.ifBlank { "Vegeta IT" }
                    m3u.append(" group-title=\\"").append(group.replace("\\"", "&quot;")).append("\\",")
                        .append(ch.name.replace('\\n', ' ').replace('\\r', ' '))
                        .append('\\n').append(ch.url).append('\\n')
                }
                cacheDir.listFiles()
                    ?.filter { it.isFile && it.name.startsWith("davix-vegeta-") && it.name.endsWith(".m3u") }
                    ?.forEach { it.delete() }
                val importedFile = java.io.File(cacheDir, "davix-vegeta-${System.currentTimeMillis()}.m3u")
                importedFile.writeBytes(m3u.toString().toByteArray(StandardCharsets.UTF_8))
                temporaryCatalog = importedFile
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        importInProgress = false
                        return@runOnUiThread
                    }
                    importInProgress = false
                    val errHint = if (result.errors.isEmpty()) "" else " · avvisi: ${result.errors.size}"
                    status.text = "Vegeta IT: ${result.channelsImported} canali da ${result.serversOk}/${result.serversTried} server$errHint. Inserimento in sezione separata…"
                    pendingImportCategory = "Vegeta IT"
                    loadSavedPlaylist(importedFile, deleteAfterImport = true)
                }
            } catch (error: Exception) {
                temporaryCatalog?.delete()
                runOnUiThread {
                    importInProgress = false
                    finishImportProgress()
                    if (!isFinishing && !isDestroyed) {
                        status.text = "Importazione Vegeta IT non riuscita: ${error.message ?: "errore di rete"}"
                        toast(status.text.toString())
                    }
                }
            }
        }, "DAVIX-Vegeta-Import").apply { isDaemon = true }.start()
    }

    private fun scanImportedStreams() {'''

if old_fn not in text:
    raise SystemExit("Ancora funzione Xtream non trovata")
text = text.replace(old_fn, new_fn, 1)

if "importVegetaItServers" not in text or "class MainActivity" not in text:
    raise SystemExit("Patch non valida")

path.write_text(text, encoding="utf-8")
print("MainActivity aggiornato:", path.stat().st_size, "bytes")
