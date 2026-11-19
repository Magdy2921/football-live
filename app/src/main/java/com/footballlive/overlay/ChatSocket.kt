package com.footballlive.overlay

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket client with automatic reconnect (exponential backoff).
 * All Listener callbacks and internal state changes happen on the main thread.
 */
class ChatSocket(
    private val url: String,
    private val token: String,
    private val listener: Listener
) {
    interface Listener {
        fun onStatus(status: ConnStatus, detail: String?)
        fun onState(live: Boolean, members: Int, messages: List<ChatMessage>)
        fun onMessage(message: ChatMessage)
        fun onDelete(id: String)
        fun onClear()
        fun onMembers(count: Int)
        fun onLive(live: Boolean)
    }

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var attempt = 0
    private var stopped = true
    private var authFailed = false
    private var connected = false
    private val retryTask = Runnable { connect() }

    fun start() {
        stopped = false; authFailed = false; attempt = 0
        connect()
    }

    fun stop() {
        stopped = true
        main.removeCallbacks(retryTask)
        ws?.cancel(); ws = null; connected = false
    }

    /** Called when the network comes back so we don't wait for the backoff timer. */
    fun reconnectNow() {
        if (stopped || authFailed || connected) return
        main.removeCallbacks(retryTask)
        ws?.cancel(); ws = null
        attempt = 0
        connect()
    }

    private fun connect() {
        if (stopped) return
        val request = try {
            Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        } catch (e: IllegalArgumentException) {
            stopped = true
            listener.onStatus(ConnStatus.ERROR, "Invalid server URL")
            return
        }
        listener.onStatus(if (attempt == 0) ConnStatus.CONNECTING else ConnStatus.RECONNECTING, null)
        ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post {
                    if (webSocket !== ws) return@post
                    connected = true; attempt = 0
                    listener.onStatus(ConnStatus.CONNECTED, null)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text == "pong") return
                val json = try { JSONObject(text) } catch (e: Exception) { return }
                main.post { if (webSocket === ws) dispatch(json) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.post { if (webSocket === ws) dropped(code == 1008, "Closed ($code)") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val rejected = response?.code == 403 || response?.code == 401
                val msg = response?.let { "HTTP ${it.code}" } ?: (t.message ?: "Network error")
                main.post { if (webSocket === ws) dropped(rejected, msg) }
            }
        })
    }

    private fun dropped(authRejected: Boolean, reason: String) {
        ws = null; connected = false
        if (stopped) return
        if (authRejected) {
            authFailed = true
            listener.onStatus(ConnStatus.AUTH_FAILED, "Server rejected the client token ($reason)")
            return
        }
        attempt++
        val delay = minOf(30_000L, 1000L shl minOf(attempt, 5))
        listener.onStatus(ConnStatus.RECONNECTING, "$reason - retry in ${delay / 1000}s")
        main.postDelayed(retryTask, delay)
    }

    private fun parse(o: JSONObject) = ChatMessage(
        id = o.optString("id"),
        author = o.optString("author", "?"),
        avatar = o.optString("avatar", ""),
        content = o.optString("content", ""),
        ts = o.optLong("ts", System.currentTimeMillis())
    )

    private fun dispatch(o: JSONObject) {
        try {
            when (o.optString("type")) {
                "state" -> {
                    val arr = o.optJSONArray("messages")
                    val list = ArrayList<ChatMessage>()
                    if (arr != null) for (i in 0 until arr.length()) list.add(parse(arr.getJSONObject(i)))
                    listener.onState(o.optBoolean("live"), o.optInt("members"), list)
                }
                "message" -> listener.onMessage(parse(o))
                "delete" -> listener.onDelete(o.optString("id"))
                "clear" -> listener.onClear()
                "members" -> listener.onMembers(o.optInt("count"))
                "status" -> listener.onLive(o.optBoolean("live"))
            }
        } catch (e: Exception) {
            // ignore malformed payloads
        }
    }
}
