package com.footballlive.overlay

import android.content.Context
import android.content.SharedPreferences

class Prefs(ctx: Context) {
    val sp: SharedPreferences = ctx.applicationContext.getSharedPreferences("overlay", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("server_url", "") ?: ""
        set(v) = sp.edit().putString("server_url", v).apply()
    var clientToken: String
        get() = sp.getString("client_token", "") ?: ""
        set(v) = sp.edit().putString("client_token", v).apply()
    var panelSide: String // "right" | "left"
        get() = sp.getString("panel_side", "right") ?: "right"
        set(v) = sp.edit().putString("panel_side", v).apply()
    var opacity: Int // 30..95 (%)
        get() = sp.getInt("opacity", 80)
        set(v) = sp.edit().putInt("opacity", v).apply()
    var fontSize: Int // sp
        get() = sp.getInt("font_size", 13)
        set(v) = sp.edit().putInt("font_size", v).apply()
    var maxMessages: Int
        get() = sp.getInt("max_messages", 50)
        set(v) = sp.edit().putInt("max_messages", v).apply()
    var showCounter: Boolean
        get() = sp.getBoolean("show_counter", true)
        set(v) = sp.edit().putBoolean("show_counter", v).apply()
    var buttonSize: Int // dp
        get() = sp.getInt("button_size", 52)
        set(v) = sp.edit().putInt("button_size", v).apply()
    var panelHeight: Int // % of screen height
        get() = sp.getInt("panel_height", 45)
        set(v) = sp.edit().putInt("panel_height", v).apply()
    var bubbleX: Int
        get() = sp.getInt("bubble_x", -1)
        set(v) = sp.edit().putInt("bubble_x", v).apply()
    var bubbleY: Int
        get() = sp.getInt("bubble_y", -1)
        set(v) = sp.edit().putInt("bubble_y", v).apply()
}
