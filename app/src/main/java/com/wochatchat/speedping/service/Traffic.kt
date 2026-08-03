package com.wochatchat.speedping.service

import android.net.TrafficStats

/**
 * 跨设备稳定的全局上下行字节统计包装。
 * 优先使用 [TrafficStats.getTotalRxBytes] / [getTotalTxBytes] 在设备上的返回值，
 * 不支持时 [TrafficStats] 会返回 -1（[TrafficStats.UNSUPPORTED]），
 * 此处统一收缩为「< 0 视为不支持」并用 0 替代，避免类型比较歧义。
 */
object Traffic {
    /** 下行总字节；不支持时返回 0。 */
    fun bytes(): Long {
        val v = TrafficStats.getTotalRxBytes()
        return if (v < 0) 0L else v
    }

    /** 上行总字节；不支持时返回 0。 */
    fun txBytes(): Long {
        val v = TrafficStats.getTotalTxBytes()
        return if (v < 0) 0L else v
    }
}
