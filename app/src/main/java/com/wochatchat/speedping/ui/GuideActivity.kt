package com.wochatchat.speedping.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.wochatchat.speedping.R
import com.wochatchat.speedping.service.OverlayService

/**
 * 引导用户授予保活相关权限后启动悬浮窗。
 *
 * 步骤顺序（按系统要求 + 风险程度排序）：
 * 1. 显示在其它应用上层 (SYSTEM_ALERT_WINDOW) —— 悬浮窗必需
 * 2. 关闭电池优化 —— 保活必需
 * 3. 通知权限 (Android 13+ POST_NOTIFICATIONS)
 * 4. 第三方厂商自启动 —— 厂商定制 ROM 必需
 */
class GuideActivity : AppCompatActivity() {

    private lateinit var btnAlert: Button
    private lateinit var btnBattery: Button
    private lateinit var btnNotif: Button
    private lateinit var btnAutostart: Button
    private lateinit var btnStartStop: Button
    private lateinit var tvTips: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)
        bindViews()
        refreshState()
        setListeners()
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun bindViews() {
        btnAlert = findViewById(R.id.btnAlert)
        btnBattery = findViewById(R.id.btnBattery)
        btnNotif = findViewById(R.id.btnNotif)
        btnAutostart = findViewById(R.id.btnAutostart)
        btnStartStop = findViewById(R.id.btnStartStop)
        tvTips = findViewById(R.id.tvTips)
    }

    private fun setListeners() {
        btnAlert.setOnClickListener {
            if (!canOverlay()) startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
        }
        btnBattery.setOnClickListener {
            // 仅当未加入白名单才跳转
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName")))
                } catch (e: Throwable) {
                    // 某些 ROM 不支持该 action，退到电池优化总设置
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }
        btnNotif.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotif.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        btnAutostart.setOnClickListener {
            tryVendorAutostart()
        }
        btnStartStop.setOnClickListener {
            if (OverlayServiceHelper.isRunning) {
                OverlayService.stop(this)
                OverlayServiceHelper.isRunning = false
            } else {
                OverlayService.start(this)
                OverlayServiceHelper.isRunning = true
            }
            finish()
        }
    }

    private fun refreshState() {
        updateBtn(btnAlert, canOverlay(), R.string.step_action_done_alert)
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        updateBtn(
            btnBattery,
            pm.isIgnoringBatteryOptimizations(packageName),
            R.string.step_action_done_battery
        )
        // 通知权限较难精确判断，简化：TIRAMISU 以后用 areNotificationsEnabled
        val notifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.areNotificationsEnabled()
        } else true
        updateBtn(btnNotif, notifOk, "已允许通知")
    }

    private fun updateBtn(btn: Button, done: Boolean, textDoneStr: String) {
        btn.text = if (done) textDoneStr else btn.text.toString().takeIf { it.startsWith("已") } ?: btn.text
        btn.isEnabled = !done
    }

    private fun updateBtn(btn: Button, done: Boolean, textDoneRes: Int) {
        if (done) {
            btn.text = getString(textDoneRes)
            btn.isEnabled = false
        }
    }

    private fun canOverlay(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this)
        else true

    private val requestNotif =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            refreshState()
        }

    /** 尝试跳转到常见厂商品牌自启动管理页。 */
    private fun tryVendorAutostart() {
        val pkgs = resources.getStringArray(R.array.vendor_autostart_pkgs)
        val acts = resources.getStringArray(R.array.vendor_autostart_acts)
        for (i in pkgs.indices) {
            try {
                val intent = Intent().apply {
                    setClassName(pkgs[i], acts[i])
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                return
            } catch (_: Throwable) {
                // 继续尝试下一个
            }
        }
        // 全部失败 -> 退到应用详情页让用户手动找
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName")))
        } catch (_: Throwable) {}
    }
}

/** 进程内轻量状态：服务是否在运行。简化为标志位，避免额外 IPC 复杂度。 */
object OverlayServiceHelper {
    @Volatile var isRunning: Boolean = false
}
