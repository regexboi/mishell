package ai.mishell.app.network

import java.net.URI
import java.util.Locale

data class ClawdiaEndpoint(
    val host: String,
    val port: Int,
    val tls: Boolean,
    val pathWithQuery: String,
    val stableId: String,
    val displayUrl: String
) {
    fun websocketUrl(): String {
        val scheme = if (tls) "wss" else "ws"
        val hostPart = if (host.contains(':')) "[$host]" else host
        val includePort = !((tls && port == 443) || (!tls && port == 80))
        val portPart = if (includePort) ":$port" else ""
        return "$scheme://$hostPart$portPart$pathWithQuery"
    }
}

fun parseClawdiaEndpoint(rawInput: String): ClawdiaEndpoint? {
    val raw = rawInput.trim()
    if (raw.isEmpty()) return null

    val normalized = if (raw.contains("://")) raw else "https://$raw"
    val uri = runCatching { URI(normalized) }.getOrNull() ?: return null

    val host = uri.host?.trim().orEmpty()
    if (host.isEmpty()) return null

    val scheme = uri.scheme?.trim()?.lowercase(Locale.US).orEmpty()
    val tls = when (scheme) {
        "http", "ws" -> false
        "https", "wss" -> true
        else -> true
    }

    val port = uri.port.takeIf { it in 1..65535 } ?: if (tls) 443 else 80
    val path = uri.rawPath?.trim().orEmpty().ifEmpty { "/" }
    val query = uri.rawQuery?.trim().orEmpty()
    val pathWithQuery = if (query.isEmpty()) path else "$path?$query"

    val hostPart = if (host.contains(':')) "[$host]" else host
    val includePort = !((tls && port == 443) || (!tls && port == 80))
    val displayPort = if (includePort) ":$port" else ""
    val displayScheme = if (tls) "https" else "http"
    val displayPath = if (path == "/" && query.isEmpty()) "" else pathWithQuery
    val displayUrl = "$displayScheme://$hostPart$displayPort$displayPath"

    val stableId = "manual|${host.lowercase(Locale.US)}|$port|${pathWithQuery.lowercase(Locale.US)}"
    return ClawdiaEndpoint(
        host = host,
        port = port,
        tls = tls,
        pathWithQuery = pathWithQuery,
        stableId = stableId,
        displayUrl = displayUrl
    )
}
