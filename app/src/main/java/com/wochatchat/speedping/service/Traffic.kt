package com.wochatchat.speedping.service

import android.net.TrafficStats

/**
 * 跨设备稳定的全局上下行字节统计包装。
 * 优先使用 [TrafficStats.getTotalRxBytes] / [getTotalTxBytes]，
 * 若不支持返回 0（行为：网速显示为 0，不会再报错）。
 */
object Traffic {
    /** 下行总字节；不支持时返回 0。 */
    fun bytes(): Long {
        val v = TrafficStats.getTotalRxBytes()
        if (v == TrafficStats.UNSUPPORTED || v < 0) return 0L
        return v
    }

    /** 上行总字节；不支持时返回 0。 */
    fun txBytes(): Long {
        val v = TrafficStats.getTotalTxBytes()
        if (v == TrafficStats.UNSUPPORTED || v < 0) return 0L
        return v
    }
}
