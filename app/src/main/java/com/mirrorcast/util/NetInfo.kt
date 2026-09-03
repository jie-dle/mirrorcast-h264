package com.mirrorcast.util

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address

object NetInfo {

    /** 获取 Wi-Fi 局域网 IPv4 地址；未连接 Wi-Fi 时返回 null */
    fun wifiIpv4(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return null
        val links = cm.getLinkProperties(network) ?: return null
        return links.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }
}
