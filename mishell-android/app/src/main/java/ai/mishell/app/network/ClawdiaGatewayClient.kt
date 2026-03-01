package ai.mishell.app.network

import android.content.Context
import android.os.Build
import ai.mishell.app.AppSettings
import ai.mishell.app.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ClawdiaGatewayClient(
    context: Context,
    private val baseHttpClient: OkHttpClient,
    private val identityStore: ClawdiaDeviceIdentityStore = ClawdiaDeviceIdentityStore(context)
) {
    private val appContext = context.applicationContext

    interface CancelableStream {
        fun cancel()
    }

    sealed interface StreamEvent {
        data class Status(val message: String) : StreamEvent
        data class AssistantDelta(val text: String) : StreamEvent
        data class Tool(
            val phase: String,
            val name: String,
            val toolCallId: String?,
            val isError: Boolean?,
            val summary: String?
        ) : StreamEvent
        data class Reasoning(val text: String, val delta: String) : StreamEvent
        data class Lifecycle(val phase: String, val detail: String?) : StreamEvent
        data object Done : StreamEvent
    }

    suspend fun testConnection(config: AppSettings.ClawdiaConnectionConfig): String {
        val connection = GatewayConnection(config)
        try {
            connection.connectAndAuthenticate()
            connection.request("health", null, timeoutMs = 10_000)
            return "Connected to ${config.endpoint.displayUrl}"
        } finally {
            connection.close()
        }
    }

    suspend fun streamText(
        config: AppSettings.ClawdiaConnectionConfig,
        text: String,
        sessionKey: String,
        thinkingLevel: String = "high",
        onCallLifecycle: (CancelableStream?) -> Unit = {},
        onEvent: (StreamEvent) -> Unit
    ) {
        if (text.isBlank()) {
            throw IOException("Prompt text is empty.")
        }

        val connection = GatewayConnection(config)
        val streamCancelled = AtomicBoolean(false)
        val streamHandle = object : CancelableStream {
            override fun cancel() {
                streamCancelled.set(true)
                connection.close()
            }
        }

        onCallLifecycle(streamHandle)
        try {
            onEvent(StreamEvent.Status("Connecting to Clawdia gateway..."))
            connection.connectAndAuthenticate()
            onEvent(StreamEvent.Status("Connected. Preparing streaming session..."))

            runCatching {
                connection.request(
                    method = "sessions.patch",
                    params = JSONObject()
                        .put("key", sessionKey)
                        .put("thinkingLevel", normalizeThinking(thinkingLevel))
                        .put("verboseLevel", "full")
                        .put("reasoningLevel", "stream"),
                    timeoutMs = 10_000
                )
            }.onFailure {
                onEvent(StreamEvent.Status("sessions.patch warning: ${it.message.orEmpty()}"))
            }

            val runId = UUID.randomUUID().toString()
            val sendResponse = connection.request(
                method = "chat.send",
                params = JSONObject()
                    .put("sessionKey", sessionKey)
                    .put("message", text)
                    .put("thinking", normalizeThinking(thinkingLevel))
                    .put("timeoutMs", 120_000)
                    .put("idempotencyKey", runId),
                timeoutMs = 15_000
            )

            val activeRunId = sendResponse.optString("runId").takeIf { it.isNotBlank() } ?: runId
            onEvent(StreamEvent.Status("Run $activeRunId started."))

            var lastAssistantText = ""
            while (true) {
                if (streamCancelled.get()) {
                    throw IOException("Clawdia stream cancelled")
                }

                val frame = connection.awaitEvent(timeoutMs = 180_000)
                if (frame == null) {
                    throw IOException("Clawdia event stream timeout")
                }

                when (frame.event) {
                    "agent" -> {
                        val payload = frame.payload ?: continue
                        if (payload.optString("runId") != activeRunId) {
                            continue
                        }

                        val streamName = payload.optString("stream")
                        val data = payload.optJSONObject("data") ?: JSONObject()
                        when (streamName) {
                            "assistant" -> {
                                val fullText = data.optString("text")
                                val delta = data.optString("delta").ifBlank {
                                    when {
                                        fullText.startsWith(lastAssistantText) ->
                                            fullText.substring(lastAssistantText.length)
                                        fullText.isNotBlank() -> fullText
                                        else -> ""
                                    }
                                }
                                if (delta.isNotBlank()) {
                                    onEvent(StreamEvent.AssistantDelta(delta))
                                }
                                if (fullText.isNotBlank()) {
                                    lastAssistantText = fullText
                                }
                            }

                            "tool" -> {
                                onEvent(
                                    StreamEvent.Tool(
                                        phase = data.optString("phase", "update"),
                                        name = data.optString("name", "tool"),
                                        toolCallId = data.optString("toolCallId").takeIf { it.isNotBlank() },
                                        isError = data.optBooleanOrNull("isError"),
                                        summary = summarizeToolData(data)
                                    )
                                )
                            }

                            "thinking" -> {
                                val full = data.optString("text")
                                val delta = data.optString("delta").ifBlank { full }
                                if (full.isNotBlank() || delta.isNotBlank()) {
                                    onEvent(StreamEvent.Reasoning(text = full, delta = delta))
                                }
                            }

                            "lifecycle" -> {
                                val phase = data.optString("phase", "unknown")
                                val detail = data.optString("error").takeIf { it.isNotBlank() }
                                onEvent(StreamEvent.Lifecycle(phase = phase, detail = detail))
                                when (phase) {
                                    "end" -> {
                                        onEvent(StreamEvent.Done)
                                        return
                                    }

                                    "error" -> {
                                        throw IOException(detail ?: "Clawdia run failed")
                                    }
                                }
                            }

                            "error" -> {
                                val detail = data.optString("message").takeIf { it.isNotBlank() }
                                throw IOException(detail ?: "Clawdia stream error")
                            }
                        }
                    }

                    "chat" -> {
                        val payload = frame.payload ?: continue
                        if (payload.optString("runId") != activeRunId) {
                            continue
                        }
                        val state = payload.optString("state")
                        when (state) {
                            "delta" -> {
                                val textDelta = extractChatDelta(payload)
                                if (textDelta.isNotBlank()) {
                                    onEvent(StreamEvent.AssistantDelta(textDelta))
                                }
                            }

                            "final", "aborted" -> {
                                onEvent(StreamEvent.Done)
                                return
                            }

                            "error" -> {
                                val message = payload.optString("errorMessage")
                                throw IOException(message.ifBlank { "Clawdia chat stream error" })
                            }
                        }
                    }

                    "seqGap" -> {
                        throw IOException("Event stream interrupted (seq gap)")
                    }
                }
            }
        } finally {
            connection.close()
            onCallLifecycle(null)
        }
    }

    private inner class GatewayConnection(
        private val config: AppSettings.ClawdiaConnectionConfig
    ) : Closeable {
        private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
        private val events = Channel<GatewayEvent>(capacity = Channel.UNLIMITED)
        private val openDeferred = CompletableDeferred<Unit>()
        private val challengeDeferred = CompletableDeferred<String>()
        private val closed = AtomicBoolean(false)

        private val client: OkHttpClient = buildClient(config)
        private var socket: WebSocket? = null

        suspend fun connectAndAuthenticate() {
            if (config.endpoint.tls && config.expectedFingerprint.isNullOrBlank()) {
                throw IOException("TLS not trusted yet. Open Config and trust the gateway fingerprint first.")
            }

            val request = Request.Builder().url(config.endpoint.websocketUrl()).build()
            socket = client.newWebSocket(request, Listener())

            withTimeout(8_000) { openDeferred.await() }
            val nonce = withTimeout(8_000) { challengeDeferred.await() }
            sendConnect(nonce)
        }

        suspend fun request(method: String, params: JSONObject?, timeoutMs: Long): JSONObject {
            if (closed.get()) throw IOException("WebSocket is closed")

            val id = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<JSONObject>()
            pending[id] = deferred

            val frame = JSONObject()
                .put("type", "req")
                .put("id", id)
                .put("method", method)
            if (params != null) {
                frame.put("params", params)
            }
            sendJson(frame)

            val response = try {
                withTimeout(timeoutMs) { deferred.await() }
            } finally {
                pending.remove(id)
            }

            if (!response.optBoolean("ok")) {
                val error = response.optJSONObject("error")
                val code = error?.optString("code").orEmpty().ifEmpty { "UNAVAILABLE" }
                val message = error?.optString("message").orEmpty().ifEmpty { "request failed" }
                throw IOException("$code: $message")
            }

            return when (val payload = response.opt("payload")) {
                null, JSONObject.NULL -> JSONObject()
                is JSONObject -> payload
                is JSONArray -> JSONObject().put("items", payload)
                else -> JSONObject().put("value", payload)
            }
        }

        suspend fun awaitEvent(timeoutMs: Long): GatewayEvent? {
            return withTimeout(timeoutMs) { events.receive() }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) {
                return
            }
            runCatching { socket?.close(1000, "bye") }
            socket = null
            events.close()
            val error = IOException("Gateway connection closed")
            pending.values.forEach { it.completeExceptionally(error) }
            pending.clear()
        }

        private suspend fun sendConnect(nonce: String) {
            val identity = identityStore.loadOrCreate()
            val role = "operator"
            val clientId = "openclaw-android"
            val clientMode = "ui"
            val scopes = listOf("operator.read", "operator.write", "operator.talk.secrets")

            val persistedRoleToken =
                AppSettings.getDeviceRoleToken(appContext, identity.deviceId, role).orEmpty()
            val authToken = config.token.ifBlank { persistedRoleToken }
            val authPassword = config.password

            val signedAtMs = System.currentTimeMillis()
            val payloadToSign = ClawdiaDeviceAuthPayload.buildV3(
                deviceId = identity.deviceId,
                clientId = clientId,
                clientMode = clientMode,
                role = role,
                scopes = scopes,
                signedAtMs = signedAtMs,
                token = authToken.takeIf { it.isNotBlank() },
                nonce = nonce,
                platform = "android",
                deviceFamily = "Android"
            )

            val signature = identityStore.signPayload(payloadToSign, identity)
            val publicKey = identityStore.publicKeyBase64Url(identity)

            val connectParams = JSONObject()
                .put("minProtocol", 3)
                .put("maxProtocol", 3)
                .put(
                    "client",
                    JSONObject()
                        .put("id", clientId)
                        .put("displayName", "Mishell Android")
                        .put("version", BuildConfig.VERSION_NAME.trim().ifEmpty { "dev" })
                        .put("platform", "android")
                        .put("mode", clientMode)
                        .put("instanceId", AppSettings.getOrCreateInstanceId(appContext))
                        .put("deviceFamily", "Android")
                        .put("modelIdentifier", resolveModelIdentifier())
                )
                .put("caps", JSONArray().put("tool-events"))
                .put("role", role)
                .put("scopes", JSONArray(scopes))
                .put("locale", Locale.getDefault().toLanguageTag())
                .put(
                    "userAgent",
                    "MishellAndroid/${BuildConfig.VERSION_NAME.trim().ifEmpty { "dev" }} " +
                        "(Android ${Build.VERSION.RELEASE ?: "unknown"}; SDK ${Build.VERSION.SDK_INT})"
                )

            val authJson = when {
                authToken.isNotBlank() -> JSONObject().put("token", authToken)
                authPassword.isNotBlank() -> JSONObject().put("password", authPassword)
                else -> null
            }
            if (authJson != null) {
                connectParams.put("auth", authJson)
            }

            if (!signature.isNullOrBlank() && !publicKey.isNullOrBlank()) {
                connectParams.put(
                    "device",
                    JSONObject()
                        .put("id", identity.deviceId)
                        .put("publicKey", publicKey)
                        .put("signature", signature)
                        .put("signedAt", signedAtMs)
                        .put("nonce", nonce)
                )
            }

            val response = request("connect", connectParams, timeoutMs = 12_000)
            val auth = response.optJSONObject("auth")
            val deviceToken = auth?.optString("deviceToken")?.trim().orEmpty()
            val authRole = auth?.optString("role")?.trim().orEmpty().ifEmpty { role }
            if (deviceToken.isNotEmpty()) {
                AppSettings.setDeviceRoleToken(appContext, identity.deviceId, authRole, deviceToken)
            }
        }

        private fun buildClient(config: AppSettings.ClawdiaConnectionConfig): OkHttpClient {
            val builder = baseHttpClient.newBuilder()
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .pingInterval(30, TimeUnit.SECONDS)

            if (config.endpoint.tls) {
                val tls = buildClawdiaTlsConfig(config.expectedFingerprint)
                builder.sslSocketFactory(tls.socketFactory, tls.trustManager)
                builder.hostnameVerifier(tls.hostnameVerifier)
            }
            return builder.build()
        }

        private fun sendJson(frame: JSONObject) {
            val ws = socket ?: throw IOException("WebSocket not connected")
            val sent = ws.send(frame.toString())
            if (!sent) {
                throw IOException("Failed to send WebSocket frame")
            }
        }

        private inner class Listener : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                openDeferred.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (frame.optString("type")) {
                    "res" -> {
                        val id = frame.optString("id")
                        if (id.isNotBlank()) {
                            pending.remove(id)?.complete(frame)
                        }
                    }

                    "event" -> {
                        val event = frame.optString("event")
                        val payload = when {
                            frame.has("payload") && frame.opt("payload") is JSONObject ->
                                frame.optJSONObject("payload")

                            frame.has("payloadJSON") ->
                                runCatching { JSONObject(frame.optString("payloadJSON")) }.getOrNull()

                            else -> null
                        }

                        if (event == "connect.challenge") {
                            val nonce = payload?.optString("nonce")?.trim().orEmpty()
                            if (nonce.isNotEmpty() && !challengeDeferred.isCompleted) {
                                challengeDeferred.complete(nonce)
                            }
                        }

                        events.trySend(GatewayEvent(event = event, payload = payload))
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val message = t.message ?: t::class.java.simpleName
                if (!openDeferred.isCompleted) {
                    openDeferred.completeExceptionally(IOException("Gateway connect failed: $message"))
                }
                if (!challengeDeferred.isCompleted) {
                    challengeDeferred.completeExceptionally(IOException("Gateway connect failed: $message"))
                }
                close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!openDeferred.isCompleted) {
                    openDeferred.completeExceptionally(IOException("Gateway closed before open: $reason"))
                }
                if (!challengeDeferred.isCompleted) {
                    challengeDeferred.completeExceptionally(IOException("Gateway closed before challenge: $reason"))
                }
                close()
            }
        }
    }

    private data class GatewayEvent(
        val event: String,
        val payload: JSONObject?
    )

    private fun summarizeToolData(data: JSONObject): String? {
        val summaryParts = mutableListOf<String>()

        data.optString("phase").takeIf { it.isNotBlank() }?.let { summaryParts += "phase=$it" }
        data.optString("name").takeIf { it.isNotBlank() }?.let { summaryParts += "name=$it" }
        data.optString("toolCallId").takeIf { it.isNotBlank() }?.let { summaryParts += "id=$it" }

        val args = data.optJSONObject("args")
        if (args != null) {
            summaryParts += "args=${truncate(args.toString(), 180)}"
        }
        val result = data.opt("result")
        if (result != null && result != JSONObject.NULL) {
            summaryParts += "result=${truncate(result.toString(), 220)}"
        }

        return if (summaryParts.isEmpty()) null else summaryParts.joinToString(" | ")
    }

    private fun truncate(value: String, maxChars: Int): String {
        return if (value.length <= maxChars) value else value.take(maxChars) + "…"
    }

    private fun extractChatDelta(payload: JSONObject): String {
        val message = payload.optJSONObject("message") ?: return ""
        val content = message.optJSONArray("content") ?: return ""
        for (index in 0 until content.length()) {
            val block = content.optJSONObject(index) ?: continue
            if (block.optString("type") == "text") {
                val text = block.optString("text")
                if (text.isNotBlank()) {
                    return text
                }
            }
        }
        return ""
    }

    private fun normalizeThinking(raw: String): String {
        return when (raw.trim().lowercase(Locale.US)) {
            "low" -> "low"
            "medium" -> "medium"
            "high" -> "high"
            else -> "high"
        }
    }

    private fun resolveModelIdentifier(): String {
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        return listOf(manufacturer, model)
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifEmpty { "Android" }
    }
}

private fun JSONObject.optBooleanOrNull(key: String): Boolean? {
    if (!has(key) || isNull(key)) {
        return null
    }
    return when (val value = opt(key)) {
        is Boolean -> value
        is String -> when (value.lowercase(Locale.US)) {
            "true" -> true
            "false" -> false
            else -> null
        }
        else -> null
    }
}
