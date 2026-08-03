package com.wochatchat.speedping.service

import android.net.TrafficStats

/**
 * 跨设备稳定的全局上下行字节统计包装。
 * 优先使用 [TrafficStats.getTotalRxBytes] / [getTotalTxBytes]，
 * 若不支持返回 0（行为：网速显示为 0，不会再报错）。
 */
object Traffic {
    fun bytes(): Long {
        if (TrafficStats.UNSUPPORTED == TrafficStats.getTotalRxBytes()) return 0L
        return TrafficStats.getTotalRxBytes()
    }

    fun txBytes(): Long {
        if (TrafficStats.UNSUPPORTED == TrafficStats.getTotalTxBytes()) return 0L
        return TrafficStats.getTotalTxBytes()
    }
}
