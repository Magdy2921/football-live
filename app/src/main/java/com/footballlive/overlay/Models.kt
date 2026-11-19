package com.footballlive.overlay

import androidx.lifecycle.MutableLiveData

data class ChatMessage(
    val id: String,
    val author: String,
    val avatar: String,
    val content: String,
    val ts: Long
)

enum class ConnStatus { STOPPED, CONNECTING, CONNECTED, RECONNECTING, AUTH_FAILED, ERROR }

/** Shared, main-thread-only state so the Home screen can show what the service is doing. */
object AppState {
    val status = MutableLiveData(ConnStatus.STOPPED)
    val detail = MutableLiveData("")
    val live = MutableLiveData(false)
    val members = MutableLiveData(0)
}
