package it.davidealpegiani.skyunoextractor

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

internal enum class StreamCheckState(val label: String) {
    PLAYLIST_AND_SEGMENT("Playlist e segmento raggiungibili"),
    REACHABLE("Endpoint raggiungibile"),
    AUTH_REQUIRED("Autenticazione negata"),
    NOT_FOUND("Flusso non trovato"),
    RATE_LIMITED("Limite richieste"),
    SERVER_ERROR("Errore server"),
    EXTERNAL_REDIRECT("Reindirizzamento esterno non verificato"),
    INVALID_CONTENT("Risposta non multimediale"),
    TIMEOUT("Timeout"),
    NETWORK_ERROR("Errore di rete")
}

internal data class StreamCheckResult(
    val state: StreamCheckState,
    val detail: String
)

/**
 * Performs a bounded, sequential reachability check. Each HTTP connection is
 * closed before the next request; only a small byte range is read. For HLS it
 * checks the manifest and one media segment. It does not claim to decode video.
 */
internal object XtreamStreamScanner {
    private data class Probe(val url: URL, val code: Int, val contentType: String, val body: ByteArray, val redirect: String?)

    fun check(streamUrl: String): StreamCheckResult {
        return try {
            val initial = URI(streamUrl)
            require((initial.scheme.equals("http", true) || initial.scheme.equals("https", true)) &&
                !initial.host.isNullOrBlank() && initial.userInfo == null) {
                "URL non valido"
            }
            val probe = request(initial.toURL())
            val code = probe.code
            if (code == 401 || code == 403) return StreamCheckResult(StreamCheckState.AUTH_REQUIRED, "HTTP $code")
            if (code == 404 || code == 410) return StreamCheckResult(StreamCheckState.NOT_FOUND, "HTTP $code")
            if (code == 429) return StreamCheckResult(StreamCheckState.RATE_LIMITED, "HTTP 429")
            if (code >= 500) return StreamCheckResult(StreamCheckState.SERVER_ERROR, "HTTP $code")
            if (probe.redirect != null) {
                val target = initial.resolve(probe.redirect)
                if (!target.host.equals(initial.host, true)) {
                    return StreamCheckResult(StreamCheckState.EXTERNAL_REDIRECT, "Redirect verso host differente")
                }
                val redirected = request(target.toURL())
                return classify(redirected)
            }
            return classify(probe)
        } catch (_: java.net.SocketTimeoutException) {
            StreamCheckResult(StreamCheckState.TIMEOUT, "Timeout di connessione o lettura")
        } catch (error: Exception) {
            StreamCheckResult(StreamCheckState.NETWORK_ERROR, error.javaClass.simpleName)
        }
    }

    fun cacheKey(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun classify(probe: Probe): StreamCheckResult {
        val code = probe.code
        if (code == 401 || code == 403) return StreamCheckResult(StreamCheckState.AUTH_REQUIRED, "HTTP $code")
        if (code == 404 || code == 410) return StreamCheckResult(StreamCheckState.NOT_FOUND, "HTTP $code")
        if (code == 429) return StreamCheckResult(StreamCheckState.RATE_LIMITED, "HTTP 429")
        if (code >= 500) return StreamCheckResult(StreamCheckState.SERVER_ERROR, "HTTP $code")
        if (code !in 200..299) return StreamCheckResult(StreamCheckState.NETWORK_ERROR, "HTTP $code")

        val bodyText = String(probe.body, Charsets.UTF_8)
        if (bodyText.trimStart().startsWith("#EXTM3U")) {
            val firstResource = bodyText.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            if (firstResource.isNullOrBlank()) {
                return StreamCheckResult(StreamCheckState.REACHABLE, "Manifest HLS accessibile; nessun segmento nel campione")
            }
            return try {
                val segmentUrl = URL(probe.url, firstResource)
                if (!segmentUrl.host.equals(probe.url.host, true)) {
                    return StreamCheckResult(StreamCheckState.REACHABLE, "Manifest accessibile; segmento su host esterno non verificato")
                }
                val segment = request(segmentUrl)
                if (segment.code in 200..299) {
                    StreamCheckResult(StreamCheckState.PLAYLIST_AND_SEGMENT, "Manifest e primo segmento accessibili")
                } else {
                    StreamCheckResult(StreamCheckState.REACHABLE, "Manifest accessibile; segmento HTTP ${segment.code}")
                }
            } catch (_: Exception) {
                StreamCheckResult(StreamCheckState.REACHABLE, "Manifest accessibile; segmento non verificato")
            }
        }
        val type = probe.contentType.lowercase()
        if (type.contains("text/html") || type.contains("application/json") || bodyText.trimStart().startsWith("<html", true)) {
            return StreamCheckResult(StreamCheckState.INVALID_CONTENT, "Il server ha restituito una pagina o JSON")
        }
        return StreamCheckResult(StreamCheckState.REACHABLE, "HTTP $code · ${probe.contentType.ifBlank { "tipo non dichiarato" }}")
    }

    private fun request(url: URL): Probe {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 5_000
            instanceFollowRedirects = false
            setRequestProperty("Range", "bytes=0-4095")
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "DAVIX-Extractor/1.0")
        }
        try {
            val code = connection.responseCode
            val redirect = if (code in 300..399) connection.getHeaderField("Location") else null
            val stream = when {
                code in 300..399 -> null
                code >= 400 -> connection.errorStream
                else -> connection.inputStream
            }
            val body = if (stream == null) ByteArray(0) else stream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                var remaining = 4096
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    remaining -= read
                }
                output.toByteArray()
            }
            return Probe(url, code, connection.contentType.orEmpty(), body, redirect)
        } finally {
            connection.disconnect()
        }
    }
}
