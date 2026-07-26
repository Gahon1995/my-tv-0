package com.lizongying.mytv0.requests

import okhttp3.Dns
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap


class DnsCache : Dns {
    private data class Entry(val addresses: List<InetAddress>, val time: Long)

    private val dnsCache = ConcurrentHashMap<String, Entry>()

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname.isEmpty()) {
            return Dns.SYSTEM.lookup(hostname)
        }

        // TTL 过期后重新解析，避免 CDN 换 IP 后长时间连不上旧地址
        dnsCache[hostname]?.let {
            if (System.currentTimeMillis() - it.time < TTL_MILLIS) {
                return it.addresses
            }
            dnsCache.remove(hostname)
        }

        val ipv4Addresses = mutableListOf<InetAddress>()
        val ipv6Addresses = mutableListOf<InetAddress>()

        for (address in InetAddress.getAllByName(hostname)) {
            if (address is Inet4Address) {
                ipv4Addresses.add(address)
            } else if (address is Inet6Address) {
                ipv6Addresses.add(address)
            }
        }

        val addressesNew = ipv4Addresses + ipv6Addresses

        if (addressesNew.isNotEmpty()) {
            dnsCache[hostname] = Entry(addressesNew, System.currentTimeMillis())
        }

        return addressesNew
    }

    companion object {
        private const val TTL_MILLIS = 10 * 60 * 1000L
    }
}