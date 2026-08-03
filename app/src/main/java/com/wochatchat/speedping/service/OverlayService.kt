package com.wochatchat.speedping.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.wochatchat.speedping.NetUtil
import com.wochatchat.speedping.PingResult
import com.wochatchat.speedping.R
import com.wochatchat.speedping.ui.GuideActivity
import java.util.concurrent.atomic.AtomicReference

/**
 * 悬浮窗 + 前台保活服务。
 *
 * 工作概览：
 * - 启动后注册悬浮窗；每 1s 采集一次网速并刷新；
 * - 每 30s 选择性探测网络可达性（百度 / Google），刷新图标；
 * - 探测失败时，图标与背景开始红色闪动，提示用户网络不可达。
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var handler: Handler
    private lateinit var wakeLock: PowerManager.WakeLock

    private lateinit var root: View
    private lateinit var ivStatus: ImageView
    private lateinit var tvUpload: TextView
    private lateinit var tvDownload: TextView

    private var rootView: LinearLayout? = null

    // ---- 网速采集 ----
    private var lastRxBytes = Traffic.bytes()
    private var lastTxBytes = Traffic.txBytes()
    private var lastTimestamp = 0L
    private val SPEED_PERIOD_MS = 1000L

    // ---- 可达性探测 ----
    private val PROBE_PERIOD_MS = 30_000L
    private val LONG_PRESS_MS = 500L
    private val speedTick = Runnable { tickSpeed() }
    private val probeTick = Runnable { tickProbe() }
    private val blinkTick = Runnable { tickBlink() }

    private val lastPing = AtomicReference<PingResult>(PingResult.ERROR)

    // 闪动状态
    private var blinking = false
    private var blinkOn = false
    private val BLINK_PERIOD_MS = 600L

    companion object {
        const val CHANNEL_ID = "speedping_keep_alive"
        const val NOTI_ID = 1001
        const val ACTION_STOP = "com.wochatchat.speedping.STOP"

        fun start(ctx: Context) {
            val i = Intent(ctx, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        handler = Handler(Looper.getMainLooper())
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpeedPing:Probe")
            .apply { setReferenceCounted(false) }

        ensureNotificationChannel()
        startForeground(NOTI_ID, buildNotification())
        inflateOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        scheduleLoops()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(speedTick)
        handler.removeCallbacks(probeTick)
        handler.removeCallbacks(blinkTick)
        if (::root.isInitialized) {
            try { wm.removeView(root) } catch (_: Throwable) {}
        }
        if (wakeLock.isHeld) wakeLock.release()
    }

    // ---------------- 通知 ----------------

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, GuideActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_speedping_logo)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notif_stop), stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .build()
    }

    // ---------------- 悬浮窗 ----------------

    private fun inflateOverlay() {
        val dp = { v: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), Resources.getSystem().displayMetrics
            ).toInt()
        }
        root = LayoutInflater.from(this).inflate(R.layout.view_overlay, null, false)
        rootView = root as? LinearLayout
        ivStatus = root.findViewById(R.id.ivStatus)
        tvUpload = root.findViewById(R.id.tvUpload)
        tvDownload = root.findViewById(R.id.tvDownload)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(64)
        }

        attachTouchListener(params)
        try { wm.addView(root, params) } catch (_: Throwable) {}
        // 首屏：先按当前环境固定图标（避免短时空白），随后由探测结果接管
        showInitialIcon()
        handler.post(probeTick)
        lastTimestamp = SystemClock.elapsedRealtime()
        lastRxBytes = Traffic.bytes()
        lastTxBytes = Traffic.txBytes()
    }

    private fun attachTouchListener(params: WindowManager.LayoutParams) {
        var startX = 0; var startY = 0; var rawX = 0f; rawY = 0f
        var moved = false
        var longPressHandled = false
        var lastClick = 0L

        // 长按 Runnable：500ms 不抬手且没拖动则触发重启
        val longPressTick = Runnable {
            if (!moved && !longPressHandled) {
                longPressHandled = true
                restartSelf()
            }
        }

        root.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    rawX = e.rawX; rawY = e.rawY; moved = false
                    longPressHandled = false
                    handler.postDelayed(longPressTick, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - rawX).toInt()
                    val dy = (e.rawY - rawY).toInt()
                    if (dx * dx + dy * dy > 25) {
                        moved = true
                        handler.removeCallbacks(longPressTick)
                        params.x = startX + dx; params.y = startY + dy
                        try { wm.updateViewLayout(root, params) } catch (_: Throwable) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressTick)
                    if (!moved && !longPressHandled) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastClick < 300) {
                            // 双击 -> 打开引导页
                            val i = Intent(this, GuideActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(i)
                        }
                        lastClick = now
                    }
                    false
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressTick)
                    false
                }
                else -> false
            }
        }
    }

    /** 长按图标触发：重启悬浮窗服务（立即重探测当前网络环境）。 */
    private fun restartSelf() {
        try {
            stopService(Intent(this, OverlayService::class.java))
        } catch (_: Throwable) {}
        OverlayService.start(this)
    }

    // ---------------- 周期任务 ----------------

    private fun scheduleLoops() {
        handler.postDelayed(speedTick, SPEED_PERIOD_MS)
        handler.postDelayed(probeTick, PROBE_PERIOD_MS)
    }

    private fun tickSpeed() {
        if (!::root.isInitialized) return
        val now = SystemClock.elapsedRealtime()
        val curRx = Traffic.bytes()
        val curTx = Traffic.txBytes()
        val dt = (now - lastTimestamp).coerceAtLeast(1L)
        val rxRate = ((curRx - lastRxBytes) * 1000L / dt).coerceAtLeast(0L)
        val txRate = ((curTx - lastTxBytes) * 1000L / dt).coerceAtLeast(0L)
        lastRxBytes = curRx; lastTxBytes = curTx; lastTimestamp = now
        tvDownload.text = humanReadable(rxRate)
        tvUpload.text = humanReadable(txRate)
        handler.postDelayed(speedTick, SPEED_PERIOD_MS)
    }

    private fun tickProbe() {
        // 标记后离开主线程执行探测
        val ctx = this
        Thread {
            val r = NetUtil.probe(ctx)
            lastPing.set(r)
            handler.post {
                applyPingResult(r)
                // 失败 -> 进入闪烁；成功 -> 停止并恢复
                if (!r.reachable) startBlinking() else stopBlinking()
            }
        }.start()
        handler.postDelayed(probeTick, PROBE_PERIOD_MS)
    }

    private fun applyPingResult(r: PingResult) {
        // 图标始终跟随当前网络环境：
        //   直连 → 百度 / VPN → Google / 失败也是当前环境对应那一个（变色由闪烁处理）
        val icon = when (NetUtil.expectedSource(this)) {
            PingResult.Source.BAIDU -> R.drawable.ic_baidu
            PingResult.Source.GOOGLE -> R.drawable.ic_google
            else -> R.drawable.ic_baidu
        }
        ivStatus.setImageResource(icon)
        // 让图标同时参与"闪动"：失败时让图标整体染红色
        if (r.reachable) {
            ivStatus.clearColorFilter()
        } else {
            ivStatus.colorFilter = android.graphics.PorterDuffColorFilter(
                Color.parseColor("#FFEF4444"), android.graphics.PorterDuff.Mode.SRC_IN
            )
        }
    }

    /** 启动首屏：先按当前环境显示正确图标，再异步探测结果回填。 */
    private fun showInitialIcon() {
        val icon = when (NetUtil.expectedSource(this)) {
            PingResult.Source.BAIDU -> R.drawable.ic_baidu
            PingResult.Source.GOOGLE -> R.drawable.ic_google
            else -> R.drawable.ic_baidu
        }
        ivStatus.setImageResource(icon)
    }

    // ---------------- 失败闪动 ----------------

    private fun startBlinking() {
        if (blinking) return
        blinking = true; blinkOn = false
        handler.post(blinkTick)
    }

    private fun stopBlinking() {
        blinking = false
        handler.removeCallbacks(blinkTick)
        // 复原：背景、文字颜色与图标滤镜
        androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_overlay)
            ?.let { rootView?.background = it }
        tvDownload.setTextColor(
            androidx.core.content.ContextCompat.getColor(this, R.color.float_download))
        tvUpload.setTextColor(
            androidx.core.content.ContextCompat.getColor(this, R.color.float_upload))
        ivStatus.clearColorFilter()
    }

    private fun tickBlink() {
        if (!blinking) return
        blinkOn = !blinkOn
        if (blinkOn) {
            rootView?.background = bgDrawable(Color.parseColor("#CC601010"))
            tvDownload.setTextColor(Color.parseColor("#FFEF4444"))
            tvUpload.setTextColor(Color.parseColor("#FFEF4444"))
        } else {
            androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_overlay)
                ?.let { rootView?.background = it }
            tvDownload.setTextColor(
                androidx.core.content.ContextCompat.getColor(this, R.color.float_download))
            tvUpload.setTextColor(
                androidx.core.content.ContextCompat.getColor(this, R.color.float_upload))
        }
        handler.postDelayed(blinkTick, BLINK_PERIOD_MS)
    }

    private fun bgDrawable(color: Int): android.graphics.drawable.Drawable {
        val d = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 16f, Resources.getSystem().displayMetrics
            )
            setColor(color)
        }
        return d
    }

    // ---------------- 单位换算 ----------------

    private fun humanReadable(bytesPerSec: Long): String {
        // 1 KB / 1 MB 口径；不足 0.1K 显示 Byte/s
        val kBps = bytesPerSec / 1024.0
        return when {
            bytesPerSec < 1024 -> String.format("%dB/s", bytesPerSec)
            kBps < 1024 -> String.format("%.1fK/s", kBps)
            else -> String.format("%.1fM/s", kBps / 1024.0)
        }
    }
}
