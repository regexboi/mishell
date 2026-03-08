package ai.mishell.app.codex

import android.content.Context
import android.util.Log
import ai.mishell.app.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.random.Random

class CodexWsClient(
    context: Context
) {
    companion object {
        private const val LOG_TAG = "CodexWsClient"
        private const val CONNECT_TIMEOUT_MS = 10_000L
    }
    data class Notification(
        val method: String,
        val params: JSONObject?
    )

    data class ServerRequest(
        val id: Any,
        val method: String,
        val params: JSONObject?
    )

    class RpcException(
        val code: Int,
        override val message: String
    ) : IOException(message)

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val connectMutex = Mutex()
    private val nextRequestId = AtomicLong(1)
    private val pendingResponses = ConcurrentHashMap<Long, CompletableDeferred<Any?>>()
    private val _connectionState = MutableStateFlow<CodexConnectionState>(CodexConnectionState.Disconnected)
    private val _notifications = MutableSharedFlow<Notification>(extraBufferCapacity = 256)
    private val _serverRequests = MutableSharedFlow<ServerRequest>(extraBufferCapacity = 64)

    private var webSocket: WebSocket? = null
    private var openDeferred: CompletableDeferred<Unit>? = null
    private var activeUrl: String? = null
    private var userAgent: String = ""
    private var initialized = false
    private var reconnectJobStarted = false
    private var shouldMaintainConnection = false

    val connectionState: StateFlow<CodexConnectionState> = _connectionState.asStateFlow()
    val notifications: SharedFlow<Notification> = _notifications.asSharedFlow()
    val serverRequests: SharedFlow<ServerRequest> = _serverRequests.asSharedFlow()

    suspend fun ensureConnected(serverUrl: String) {
        connectMutex.withLock {
            if (initialized && activeUrl == serverUrl && webSocket != null) {
                return
            }

            if (activeUrl != null && activeUrl != serverUrl) {
                closeSocket()
            }

            shouldMaintainConnection = true
            activeUrl = serverUrl
            _connectionState.value = CodexConnectionState.Connecting
            Log.d(LOG_TAG, "Connecting to $serverUrl")
            connectSocket(serverUrl)
            performInitialize()
        }
    }

    suspend fun request(serverUrl: String, method: String, params: Any? = null): Any? {
        ensureConnected(serverUrl)
        return sendRequestInternal(method = method, params = params)
    }

    suspend fun notify(serverUrl: String, method: String, params: Any? = null) {
        ensureConnected(serverUrl)
        sendNotificationInternal(method = method, params = params)
    }

    fun respondSuccess(requestId: Any, result: Any?) {
        sendFrame(
            jsonObjectOf(
                "jsonrpc" to "2.0",
                "id" to requestId,
                "result" to result
            )
        )
    }

    fun respondError(requestId: Any, code: Int, message: String) {
        sendFrame(
            jsonObjectOf(
                "jsonrpc" to "2.0",
                "id" to requestId,
                "error" to jsonObjectOf(
                    "code" to code,
                    "message" to message
                )
            )
        )
    }

    fun shutdown() {
        shouldMaintainConnection = false
        closeSocket()
        scope.cancel()
    }

    private suspend fun connectSocket(serverUrl: String) {
        val deferred = CompletableDeferred<Unit>()
        openDeferred = deferred
        val request = Request.Builder()
            .url(serverUrl)
            .build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(LOG_TAG, "WebSocket opened: ${response.request.url}")
                openDeferred?.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingFrame(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(LOG_TAG, "WebSocket closed code=$code reason=$reason")
                handleDisconnect(reason.ifBlank { "Socket closed ($code)" }, reconnect = shouldMaintainConnection)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(LOG_TAG, "WebSocket failure: ${t.message}", t)
                handleDisconnect(t.message ?: "WebSocket failure", reconnect = shouldMaintainConnection)
            }
        })
        try {
            withTimeout(CONNECT_TIMEOUT_MS) {
                deferred.await()
            }
        } finally {
            if (openDeferred === deferred) {
                openDeferred = null
            }
        }
    }

    private suspend fun performInitialize() {
        if (initialized) {
            _connectionState.value = CodexConnectionState.Connected(userAgent)
            return
        }
        val result = sendRequestInternal(
            method = "initialize",
            params = jsonObjectOf(
                "clientInfo" to jsonObjectOf(
                    "name" to "mishell-android",
                    "title" to "Mishell Android",
                    "version" to BuildConfig.VERSION_NAME
                ),
                "capabilities" to jsonObjectOf(
                    "experimentalApi" to false
                )
            ),
            requireInitialization = false
        )
        userAgent = (result as? JSONObject)?.optStringOrNull("userAgent").orEmpty()
        Log.d(LOG_TAG, "Initialize completed userAgent=$userAgent")
        sendNotificationInternal("initialized", JSONObject(), requireInitialization = false)
        initialized = true
        reconnectJobStarted = false
        _connectionState.value = CodexConnectionState.Connected(userAgent)
    }

    private suspend fun sendRequestInternal(
        method: String,
        params: Any? = null,
        requireInitialization: Boolean = true
    ): Any? {
        if (requireInitialization && !initialized) {
            error("Codex client request sent before initialize handshake completed")
        }
        val socket = webSocket ?: throw IOException("WebSocket is not connected")
        val requestId = nextRequestId.getAndIncrement()
        val deferred = CompletableDeferred<Any?>()
        pendingResponses[requestId] = deferred
        val frame = jsonObjectOf(
            "jsonrpc" to "2.0",
            "id" to requestId,
            "method" to method,
            "params" to (params ?: JSONObject())
        )
        if (!socket.send(frame.toString())) {
            pendingResponses.remove(requestId)
            throw IOException("Failed to send request: $method")
        }
        Log.d(LOG_TAG, "Sent request method=$method id=$requestId")
        return deferred.await()
    }

    private fun sendNotificationInternal(
        method: String,
        params: Any?,
        requireInitialization: Boolean = true
    ) {
        if (requireInitialization && !initialized) {
            error("Codex client notification sent before initialize handshake completed")
        }
        sendFrame(
            jsonObjectOf(
                "jsonrpc" to "2.0",
                "method" to method,
                "params" to (params ?: JSONObject())
            )
        )
        Log.d(LOG_TAG, "Sent notification method=$method")
    }

    private fun sendFrame(frame: JSONObject) {
        val socket = webSocket ?: throw IllegalStateException("WebSocket is not connected")
        if (!socket.send(frame.toString())) {
            throw IllegalStateException("Failed to send WebSocket frame")
        }
    }

    private fun handleIncomingFrame(text: String) {
        val json = JSONObject(text)
        val method = json.optStringOrNull("method")
        val id = json.opt("id").takeUnless { it == null || it == JSONObject.NULL }
        when {
            method != null && id != null -> {
                _serverRequests.tryEmit(
                    ServerRequest(
                        id = id,
                        method = method,
                        params = json.optJSONObjectOrNull("params")
                    )
                )
            }
            method != null -> {
                _notifications.tryEmit(
                    Notification(
                        method = method,
                        params = json.optJSONObjectOrNull("params")
                    )
                )
            }
            id is Number -> {
                val pending = pendingResponses.remove(id.toLong()) ?: return
                val error = json.optJSONObjectOrNull("error")
                if (error != null) {
                    Log.w(
                        LOG_TAG,
                        "JSON-RPC error for id=$id code=${error.optInt("code")} message=${error.optStringOrNull("message")}"
                    )
                    pending.completeExceptionally(
                        RpcException(
                            code = error.optInt("code"),
                            message = error.optStringOrNull("message") ?: "Unknown JSON-RPC error"
                        )
                    )
                } else {
                    Log.d(LOG_TAG, "Received response for id=$id")
                    pending.complete(json.opt("result").takeUnless { it == JSONObject.NULL })
                }
            }
        }
    }

    private fun handleDisconnect(reason: String, reconnect: Boolean) {
        initialized = false
        webSocket = null
        openDeferred?.completeExceptionally(IOException(reason))
        openDeferred = null
        pendingResponses.values.forEach { pending ->
            pending.completeExceptionally(IOException(reason))
        }
        pendingResponses.clear()
        _connectionState.value = if (reconnect) {
            CodexConnectionState.Reconnecting(attempt = 1, reason = reason)
        } else {
            CodexConnectionState.Failed(reason)
        }
        Log.w(LOG_TAG, "Disconnected reconnect=$reconnect reason=$reason")
        if (reconnect && !reconnectJobStarted) {
            reconnectJobStarted = true
            val url = activeUrl ?: return
            scope.launchReconnect(url, reason)
        }
    }

    private fun CoroutineScope.launchReconnect(serverUrl: String, initialReason: String) {
        launch {
            var attempt = 1
            while (isActive && shouldMaintainConnection && !initialized) {
                _connectionState.value = CodexConnectionState.Reconnecting(attempt, if (attempt == 1) initialReason else null)
                val delayMs = min(15_000L, 800L * (1 shl min(attempt, 4))) + Random.nextLong(150L, 650L)
                delay(delayMs)
                try {
                    Log.d(LOG_TAG, "Reconnect attempt=$attempt url=$serverUrl")
                    connectMutex.withLock {
                        if (initialized || !shouldMaintainConnection) {
                            return@withLock
                        }
                        connectSocket(serverUrl)
                        performInitialize()
                    }
                } catch (_: Throwable) {
                    attempt += 1
                }
            }
            reconnectJobStarted = false
        }
    }

    private fun closeSocket() {
        initialized = false
        openDeferred?.completeExceptionally(IOException("Client closing socket"))
        openDeferred = null
        webSocket?.close(1000, "Client closing socket")
        webSocket = null
    }
}
