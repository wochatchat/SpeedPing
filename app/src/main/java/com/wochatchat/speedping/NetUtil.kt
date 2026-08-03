package com.wochatchat.speedping

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL

/**
 * 网络可达性探测。
 *
 * 关键约定（产品语义）：
 * - 直连网络  → 只探测百度，可达则显示百度图标。
 * - VPN/梯子  → 只探测 Google，可达则显示 Google 图标。
 * - 二者互斥：一次探测只针对当前环境选定的唯一目标；
 *   探测失败 → 返回 [PingResult.Source.NONE]，
 *   由上层驱动「闪动」提醒，不再显示任何站点图标。
 */
object NetUtil {

    // 均使用 https，避免运营商劫持/插页影响判断
    private const val BAIDU_URL = "https://www.baidu.com"
    private const val GOOGLE_URL = "https://www.google.com/generate_204"
    private const val CONNECT_TIMEOUT_MS = 4000
    private const val READ_TIMEOUT_MS = 5000

    /**
     * 当前是否处于 VPN（疑似挂梯子）环境。
     *
     * 注意：[NetworkCapabilities.TRANSPORT_VPN] 在系统层是「当前活动网络
     * 通过 VPN 承载」的判定，对绝大多数主流 Android 内置 VPN 客户端都成立。
     */
    fun isVpnActive(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false
        val cap = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    /**
     * 选择与本轮网络环境匹配的探测目标：
     * - VPN 激活   → [PingResult.Source.GOOGLE]
     * - 否则       → [PingResult.Source.BAIDU]
     */
    fun expectedSource(context: Context): PingResult.Source =
        if (isVpnActive(context)) PingResult.Source.GOOGLE else PingResult.Source.BAIDU

    /**
     * 探测当前网络环境下的「唯一目标」是否可达。
     *
     * 失败不会回退到另一个目标 —— 因为：
     *   - 直连下访问 Google 必然失败，回退只会制造假阳性「失败」；
     *   - 梯子下访问百度也可能被 GFW 阻断，回退同理。
     * 因此失败直接返回 [PingResult.ERROR]（NONE），由上层提醒用户网络异常。
     */
    fun probe(context: Context): PingResult {
        val target = expectedSource(context)
        val r = probeUrl(target)
        return if (r.reachable) r else PingResult.ERROR
    }

    /** 对单个目标做 HTTP GET，判断是否可达并测量延迟。 */
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
