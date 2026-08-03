package com.wochatchat.speedping.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.wochatchat.speedping.service.OverlayService

/**
 * 开机自启：Android 会延迟一段时间才允许 SYSTEM_ALERT_WINDOW，
 * 这里只负责在解锁后检查权限足够时唤起 [OverlayService]。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(context)
        ) return
        OverlayService.start(context)
    }
}
