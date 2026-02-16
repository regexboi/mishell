package ai.mishell.app.network

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class LlmStreamClient(
    private val httpClient: OkHttpClient,
    private val parser: SseFrameParser = SseFrameParser()
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    @Throws(IOException::class)
    fun streamText(
        url: String,
        apiKey: String,
        text: String,
        sessionId: String,
        onCallLifecycle: (Call?) -> Unit = {},
        onEvent: (SseEvent) -> Unit
    ) {
        if (url.isBlank()) {
            throw IOException("LLM stream URL is not configured.")
        }

        val payload = JSONObject()
            .put("text", text)
            .put("session_id", sessionId)
            .toString()

        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .addHeader("Accept", "text/event-stream")
            .post(payload.toRequestBody(jsonMediaType))
            .build()

        val call = httpClient.newCall(request)
        onCallLifecycle(call)

        try {
            call.execute().use { response ->
                val body = response.body ?: throw IOException("Empty stream response body.")
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code}: ${body.string().take(300)}")
                }

                val source = body.source()
                while (true) {
                    val event = parser.nextEvent(source) ?: break
                    onEvent(event)
                    if (event is SseEvent.Done || event is SseEvent.Error) {
                        return
                    }
                }
            }

            throw IOException("Stream ended before done event.")
        } finally {
            onCallLifecycle(null)
        }
    }
}
