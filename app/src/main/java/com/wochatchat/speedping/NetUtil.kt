package com.wochatchat.speedping

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL

/**
 * 网络/可达性相关工具。
 */
object NetUtil {

    // 域名均使用 https，避免被运营商劫持/插页影响判断
    private const val BAIDU_URL = "https://www.baidu.com"
    private const val GOOGLE_URL = "https://www.google.com/generate_204"
    private const val CONNECT_TIMEOUT_MS = 4000
    private const val READ_TIMEOUT_MS = 5000

    /** 是否处于 VPN（疑似挂梯子）网络环境。 */
    fun isVpnActive(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false
        val cap = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    /**
     * 选择性 ping：
     * 1) 若疑似挂在 VPN（梯子）下，优先检测 Google；
     * 2) 否则检测百度；
     * 3) 第一个目标失败时，回退到另一个再试；
     * 4) 全部失败则 [Source.NONE]。
     */
    fun probe(context: Context): PingResult {
        val vpn = isVpnActive(context)
        val first = if (vpn) PingResult.Source.GOOGLE else PingResult.Source.BAIDU
        val second = if (vpn) PingResult.Source.BAIDU else PingResult.Source.GOOGLE

        val r1 = probeUrl(first)
        if (r1.reachable) return r1
        val r2 = probeUrl(second)
        if (r2.reachable) return r2
        return PingResult.ERROR
    }

    /** 对单个目标做 HTTP HEAD/GET，判断是否可达并测量延迟。 */
    private fun probeUrl(source: PingResult.Source): PingResult {
        val urlStr = when (source) {
            PingResult.Source.BAIDU -> BAIDU_URL
            PingResult.Source.GOOGLE -> GOOGLE_URL
            else -> return PingResult.ERROR
        }
        return try {
            val start = System.currentTimeMillis()
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = true
                useCaches = false
            }
            conn.connect()
            val code = conn.responseCode
            val latency = System.currentTimeMillis() - start
            conn.disconnect()
            // 2xx 与 3xx 均视为可达
            val ok = code in 200..399
            PingResult(ok, source, latency)
        } catch (e: Throwable) {
            PingResult(false, source, -1L)
        }
    }
}
