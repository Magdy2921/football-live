package com.footballlive.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Point
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.max

class OverlayService : Service(), ChatSocket.Listener {

    companion object {
        const val ACTION_STOP = "com.footballlive.overlay.STOP"
        private const val CHANNEL_ID = "overlay_service"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, OverlayService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager
    private lateinit var themed: Context
    private val main = Handler(Looper.getMainLooper())
    private val adapter = MessageAdapter()

    private var socket: ChatSocket? = null
    private var initialized = false

    private var bubble: View? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var panelOpen = false

    private lateinit var bubbleButton: ImageView
    private lateinit var pill: View
    private lateinit var dot: View
    private lateinit var counterText: TextView
    private lateinit var liveDot: View
    private lateinit var statusText: TextView
    private lateinit var emptyText: TextView
    private lateinit var list: RecyclerView
    private lateinit var tuneRow: View

    private var status = ConnStatus.CONNECTING
    private var live = false
    private var members = 0

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != "bubble_x" && key != "bubble_y") applySettings()
    }

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            main.post { socket?.reconnectNow() }
        }
    }

    // ------------------------------------------------------------------ lifecycle
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        themed = ContextThemeWrapper(this, R.style.OverlayTheme)
        val ch = NotificationChannel(CHANNEL_ID, "Overlay service", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification("Starting…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } catch (e: Exception) {
            AppState.detail.value = "Android refused to start the foreground service: ${e.message}"
            stopSelf()
            return START_NOT_STICKY
        }
        if (initialized) return START_NOT_STICKY

        if (!Settings.canDrawOverlays(this) || prefs.serverUrl.isBlank() || prefs.clientToken.isBlank()) {
            AppState.detail.value = "Overlay permission or server configuration is missing"
            stopSelf()
            return START_NOT_STICKY
        }
        initialized = true
        try {
            buildOverlay()
        } catch (e: Exception) {
            AppState.status.value = ConnStatus.ERROR
            AppState.detail.value = "Could not create overlay: ${e.message}"
            stopSelf()
            return START_NOT_STICKY
        }
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
            .registerDefaultNetworkCallback(netCallback)
        socket = ChatSocket(prefs.serverUrl, prefs.clientToken, this).also { it.start() }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!initialized) return
        layoutPanel()
        val b = bubble ?: return
        val lp = bubbleLp ?: return
        val s = screenSize()
        lp.x = lp.x.coerceIn(0, max(0, s.x - b.width))
        lp.y = lp.y.coerceIn(0, max(0, s.y - b.height))
        safeUpdate(b, lp)
    }

    override fun onDestroy() {
        socket?.stop(); socket = null
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .unregisterNetworkCallback(netCallback)
        } catch (_: Exception) {}
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        panel?.animate()?.cancel()
        listOf(panel, bubble).forEach { v ->
            try { if (v != null && v.isAttachedToWindow) wm.removeView(v) } catch (_: Exception) {}
        }
        panel = null; bubble = null
        AppState.status.value = ConnStatus.STOPPED
        AppState.live.value = false
        AppState.members.value = 0
        super.onDestroy()
    }

    // ------------------------------------------------------------------ overlay views
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun screenSize(): Point {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            Point(dm.widthPixels, dm.heightPixels)
        }
    }

    private fun overlayParams(w: Int, h: Int, extraFlags: Int = 0) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or extraFlags,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun safeUpdate(v: View, lp: WindowManager.LayoutParams) {
        try { if (v.isAttachedToWindow) wm.updateViewLayout(v, lp) } catch (_: Exception) {}
    }

    private fun buildOverlay() {
        val inflater = LayoutInflater.from(themed)

        // 1) Chat panel (added first so the bubble always stays on top of it)
        val p = inflater.inflate(R.layout.overlay_chat, null)
        val plp = overlayParams(
            dp(300), dp(300),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        )
        p.visibility = View.GONE
        panel = p; panelLp = plp
        liveDot = p.findViewById(R.id.liveDot)
        statusText = p.findViewById(R.id.statusText)
        emptyText = p.findViewById(R.id.emptyText)
        tuneRow = p.findViewById(R.id.tuneRow)
        list = p.findViewById(R.id.messageList)
        list.layoutManager = LinearLayoutManager(themed)
        list.adapter = adapter
        list.itemAnimator = null
        p.findViewById<View>(R.id.closeBtn).setOnClickListener { hidePanel() }
        p.findViewById<View>(R.id.tuneBtn).setOnClickListener {
            tuneRow.visibility = if (tuneRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        bindTuneSliders(p)
        wm.addView(p, plp)

        // 2) Floating bubble + counter pill
        val b = inflater.inflate(R.layout.overlay_bubble, null)
        bubbleButton = b.findViewById(R.id.bubbleButton)
        pill = b.findViewById(R.id.pill)
        dot = b.findViewById(R.id.dot)
        counterText = b.findViewById(R.id.counterText)
        val blp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        val s = screenSize()
        blp.x = if (prefs.bubbleX >= 0) prefs.bubbleX else s.x - dp(100)
        blp.y = if (prefs.bubbleY >= 0) prefs.bubbleY else (s.y * 0.08f).toInt()
        bubble = b; bubbleLp = blp
        attachDrag(b, blp)
        wm.addView(b, blp)

        applySettings()
        refreshUi()
    }

    private fun bindTuneSliders(p: View) {
        val op = p.findViewById<SeekBar>(R.id.opacitySeek)
        val ht = p.findViewById<SeekBar>(R.id.heightSeek)
        op.progress = (prefs.opacity - 30).coerceIn(0, 65)
        ht.progress = (prefs.panelHeight - 20).coerceIn(0, 60)
        op.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) {
                if (fromUser) prefs.opacity = v + 30
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        ht.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) {
                if (fromUser) prefs.panelHeight = v + 20
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    private fun attachDrag(v: View, lp: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        var moved = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    touchX = e.rawX; touchY = e.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        val s = screenSize()
                        lp.x = (startX + dx).toInt().coerceIn(0, max(0, s.x - v.width))
                        lp.y = (startY + dy).toInt().coerceIn(0, max(0, s.y - v.height))
                        safeUpdate(v, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        prefs.bubbleX = lp.x
                        prefs.bubbleY = lp.y
                    } else {
                        if (panelOpen) hidePanel() else showPanel()
                    }
                }
            }
            true
        }
    }

    // ------------------------------------------------------------------ panel show/hide
    private fun layoutPanel() {
        val p = panel ?: return
        val lp = panelLp ?: return
        val s = screenSize()
        val margin = dp(8)
        val w = (s.x * 0.70f).toInt().coerceIn(dp(220), dp(340)).coerceAtMost(s.x - 2 * margin)
        val top = (s.y * 0.22f).toInt()
        val h = (s.y * prefs.panelHeight / 100f).toInt()
            .coerceAtMost(s.y - top - dp(24)).coerceAtLeast(dp(160))
        lp.width = w; lp.height = h; lp.y = top
        lp.x = if (prefs.panelSide == "left") margin else s.x - w - margin
        safeUpdate(p, lp)
    }

    private fun slideDir() = if (prefs.panelSide == "left") -1f else 1f

    private fun showPanel() {
        val p = panel ?: return
        val lp = panelLp ?: return
        panelOpen = true
        layoutPanel()
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        safeUpdate(p, lp)
        p.animate().cancel()
        p.visibility = View.VISIBLE
        p.alpha = 0f
        p.translationX = slideDir() * dp(40)
        p.scaleX = 0.96f; p.scaleY = 0.96f
        p.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f)
            .setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        list.post { if (adapter.size() > 0) list.scrollToPosition(adapter.size() - 1) }
    }

    private fun hidePanel() {
        val p = panel ?: return
        val lp = panelLp ?: return
        panelOpen = false
        p.animate().cancel()
        p.animate().alpha(0f).translationX(slideDir() * dp(40)).setDuration(180)
            .withEndAction {
                if (!panelOpen) {
                    p.visibility = View.GONE
                    lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    safeUpdate(p, lp)
                }
            }.start()
    }

    // ------------------------------------------------------------------ settings + UI refresh
    private fun applySettings() {
        val p = panel ?: return
        val size = prefs.buttonSize
        bubbleButton.layoutParams = bubbleButton.layoutParams.apply { width = dp(size); height = dp(size) }
        val pad = (dp(size) * 0.21f).toInt()
        bubbleButton.setPadding(pad, pad, pad, pad)
        pill.visibility = if (prefs.showCounter) View.VISIBLE else View.GONE

        p.background = GradientDrawable().apply {
            setColor(Color.argb(prefs.opacity * 255 / 100, 10, 10, 10))
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), Color.argb(90, 229, 9, 24))
        }
        adapter.fontSp = prefs.fontSize.toFloat()
        adapter.trimTo(prefs.maxMessages)
        layoutPanel()
        updateEmpty()
    }

    private fun updateEmpty() {
        if (panel == null) return
        emptyText.visibility = if (adapter.size() == 0) View.VISIBLE else View.GONE
    }

    private fun tint(v: View, color: Int) { v.backgroundTintList = ColorStateList.valueOf(color) }

    private fun refreshUi() {
        if (panel == null) return
        val connected = status == ConnStatus.CONNECTED
        counterText.text = if (connected) "$members connected" else "-- connected"
        val color = Color.parseColor(
            when {
                connected && live -> "#E50918"
                connected -> "#8A8A8A"
                else -> "#F5B82E"
            }
        )
        tint(dot, color); tint(liveDot, color)
        statusText.text = when (status) {
            ConnStatus.CONNECTED -> if (live) "" else "Stream offline"
            ConnStatus.CONNECTING -> "Connecting…"
            ConnStatus.RECONNECTING -> "Reconnecting…"
            ConnStatus.AUTH_FAILED -> "Auth failed"
            ConnStatus.ERROR -> "Config error"
            ConnStatus.STOPPED -> ""
        }
        AppState.live.value = live
        AppState.members.value = members
        val text = when (status) {
            ConnStatus.CONNECTED -> (if (live) "LIVE" else "Stream offline") + " · $members connected"
            ConnStatus.AUTH_FAILED -> "Authentication failed - check client token"
            else -> "Connecting to server…"
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_ball)
        .setContentTitle("Football Live Overlay")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        )
        .addAction(
            0, "Stop overlay",
            PendingIntent.getService(
                this, 1,
                Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .build()

    // ------------------------------------------------------------------ ChatSocket.Listener (main thread)
    override fun onStatus(status: ConnStatus, detail: String?) {
        this.status = status
        AppState.status.value = status
        AppState.detail.value = detail ?: ""
        refreshUi()
    }

    override fun onState(live: Boolean, members: Int, messages: List<ChatMessage>) {
        this.live = live; this.members = members
        adapter.setAll(messages.takeLast(prefs.maxMessages))
        updateEmpty()
        if (adapter.size() > 0) list.scrollToPosition(adapter.size() - 1)
        refreshUi()
    }

    override fun onMessage(message: ChatMessage) {
        val atBottom = !list.canScrollVertically(1)
        adapter.add(message)
        adapter.trimTo(prefs.maxMessages)
        updateEmpty()
        if (atBottom || !panelOpen) list.scrollToPosition(adapter.size() - 1)
    }

    override fun onDelete(id: String) { adapter.remove(id); updateEmpty() }
    override fun onClear() { adapter.clear(); updateEmpty() }
    override fun onMembers(count: Int) { members = count; refreshUi() }
    override fun onLive(live: Boolean) { this.live = live; refreshUi() }
}
