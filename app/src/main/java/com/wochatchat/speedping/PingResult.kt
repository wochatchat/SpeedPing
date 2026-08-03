package com.wochatchat.speedping

/**
 * 一次可达性检测的结果。
 *
 * @param reachable 网络是否可达（HTTP 请求是否拿到响应）
 * @param source    实际检测的目标：BAIDU / GOOGLE / NONE（全部失败）
 * @param latencyMs 延迟毫秒（不可用时为 -1）
 */
data class PingResult(
    val reachable: Boolean,
    val source: Source,
    val latencyMs: Long
) {
    enum class Source { BAIDU, GOOGLE, NONE }

    companion object {
        val ERROR = PingResult(false, Source.NONE, -1L)
    }
}
