package com.lobo.app.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

/** 一个可用的局域网 IPv4 地址。 */
data class LanAddress(val interfaceName: String, val ip: String, val isWifi: Boolean)

/**
 * 局域网地址工具：枚举站点本地 IPv4，过滤掉蜂窝 / VPN / 隧道等，优先返回 WiFi（热点）地址。
 * 所有设备（手机、PC、Linux、py 脚本）都用 `http://<ip>:<port>` 访问主机。
 */
object NetworkUtils {

    private val _addresses = MutableStateFlow<List<LanAddress>>(emptyList())
    val addresses: StateFlow<List<LanAddress>> = _addresses.asStateFlow()

    /** 重新枚举网卡地址并更新 [addresses]。 */
    fun refresh(): List<LanAddress> {
        val list = lanIpv4Addresses()
        _addresses.value = list
        return list
    }

    /** 优先返回 WiFi / 热点地址。 */
    fun preferred(): LanAddress? =
        _addresses.value.firstOrNull { it.isWifi } ?: _addresses.value.firstOrNull()

    fun urlFor(port: Int): String? = preferred()?.let { "http://${it.ip}:$port" }

    fun urlsFor(port: Int): List<String> =
        _addresses.value.map { "http://${it.ip}:$port" }.distinct()

    fun lanIpv4Addresses(): List<LanAddress> {
        val result = ArrayList<LanAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (networkInterface in interfaces) {
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                val name = networkInterface.name.lowercase()
                if (isIgnoredInterface(name)) continue
                val isWifi = name.startsWith("wlan") || name.startsWith("ap") ||
                    name.startsWith("swlan") || name.contains("wifi") || name.startsWith("softap")
                for (address in networkInterface.inetAddresses) {
                    if (address is Inet4Address && !address.isLoopbackAddress && address.isSiteLocalAddress) {
                        result.add(LanAddress(networkInterface.name, address.hostAddress ?: continue, isWifi))
                    }
                }
            }
        } catch (t: Throwable) {
            LogUtils.w("NetworkUtils", "枚举网卡失败: ${t.message}")
        }
        // WiFi 在前，其余保持原顺序
        return result.sortedByDescending { it.isWifi }
    }

    private fun isIgnoredInterface(name: String): Boolean {
        val ignored = listOf(
            "rmnet", "ccmni", "pdp", "tun", "tap", "ppp", "dummy", "ifb", "clat", "p2p", "v4-", "sit"
        )
        return ignored.any { name.startsWith(it) }
    }
}
