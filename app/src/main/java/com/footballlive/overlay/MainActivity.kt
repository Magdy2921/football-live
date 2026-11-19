package com.footballlive.overlay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var urlInput: TextInputEditText
    private lateinit var tokenInput: TextInputEditText
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var discordText: TextView
    private lateinit var membersText: TextView
    private lateinit var permText: TextView
    private lateinit var permBtn: MaterialButton

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Start regardless: without notification permission the service still runs.
            OverlayService.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        urlInput = findViewById(R.id.urlInput)
        tokenInput = findViewById(R.id.tokenInput)
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        discordText = findViewById(R.id.discordText)
        membersText = findViewById(R.id.membersText)
        permText = findViewById(R.id.permText)
        permBtn = findViewById(R.id.permBtn)

        urlInput.setText(prefs.serverUrl)
        tokenInput.setText(prefs.clientToken)

        findViewById<View>(R.id.startBtn).setOnClickListener { onStartClicked() }
        findViewById<View>(R.id.stopBtn).setOnClickListener { OverlayService.stop(this) }
        findViewById<View>(R.id.saveBtn).setOnClickListener {
            if (saveConfig()) Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.settingsBtn).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.batteryBtn).setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        permBtn.setOnClickListener { openOverlaySettings() }

        val observer = androidx.lifecycle.Observer<Any> { render() }
        AppState.status.observe(this, observer)
        AppState.detail.observe(this, observer)
        AppState.live.observe(this, observer)
        AppState.members.observe(this, observer)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun saveConfig(): Boolean {
        val url = urlInput.text.toString().trim()
        val token = tokenInput.text.toString().trim()
        if (!url.startsWith("wss://")) {
            urlInput.error = "Must start with wss:// (secure WebSocket)"
            return false
        }
        if (token.length < 16) {
            tokenInput.error = "Token looks too short"
            return false
        }
        prefs.serverUrl = url
        prefs.clientToken = token
        return true
    }

    private fun onStartClicked() {
        if (!saveConfig()) return
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow “Display over other apps” first", Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            OverlayService.start(this)
        }
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun render() {
        val canDraw = Settings.canDrawOverlays(this)
        permBtn.visibility = if (canDraw) View.GONE else View.VISIBLE
        permText.text = if (canDraw) "Overlay permission: granted" else "Overlay permission: NOT granted"

        val st = AppState.status.value ?: ConnStatus.STOPPED
        val detail = AppState.detail.value.orEmpty()
        val (label, color) = when (st) {
            ConnStatus.STOPPED -> "Overlay stopped" to "#8A8A8A"
            ConnStatus.CONNECTING -> "Connecting…" to "#F5B82E"
            ConnStatus.CONNECTED -> "Overlay running · connected" to "#2ECC71"
            ConnStatus.RECONNECTING -> "Reconnecting…" to "#F5B82E"
            ConnStatus.AUTH_FAILED -> "Authentication failed" to "#E50918"
            ConnStatus.ERROR -> "Configuration error" to "#E50918"
        }
        statusText.text = if (detail.isNotEmpty() && st != ConnStatus.CONNECTED && st != ConnStatus.STOPPED)
            "$label\n$detail" else label
        statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(color))

        if (st == ConnStatus.CONNECTED) {
            val live = AppState.live.value == true
            discordText.text = "Discord server: connected · stream " + if (live) "LIVE" else "offline"
            membersText.text = "Connected to voice channel: ${AppState.members.value ?: 0} (not verified viewers)"
        } else {
            discordText.text = "Discord server: not connected"
            membersText.text = "Connected to voice channel: --"
        }
    }
}
