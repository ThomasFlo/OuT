package com.homestock.data.remote

import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

data class SyncEvent(
    val entity: String,
    val action: String,
    val id: Long,
)

/**
 * Persistent WebSocket with automatic reconnection (exponential backoff).
 * Emits [SyncEvent]s that the repository uses to refresh the local cache.
 */
class RealtimeClient(
    private val scope: CoroutineScope,
    private val gson: Gson = Gson(),
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var currentUrl: String? = null
    private var shouldReconnect = false
    private var backoffMs = 1000L

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    val events = MutableSharedFlow<SyncEvent>(extraBufferCapacity = 32)

    fun connect(wsUrl: String) {
        if (currentUrl == wsUrl && webSocket != null) return
        currentUrl = wsUrl
        shouldReconnect = true
        openSocket()
    }

    fun disconnect() {
        shouldReconnect = false
        webSocket?.close(1000, "client closing")
        webSocket = null
        _connected.value = false
    }

    private fun openSocket() {
        val url = currentUrl ?: return
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connected.value = true
                backoffMs = 1000L
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { gson.fromJson(text, SyncEvent::class.java) }
                    .getOrNull()
                    ?.let { scope.launch { events.emit(it) } }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                _connected.value = false
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connected.value = false
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        scope.launch(Dispatchers.IO) {
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            if (shouldReconnect) openSocket()
        }
    }
}
