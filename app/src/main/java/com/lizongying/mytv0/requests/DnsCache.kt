package com.lizongying.mytv0.requests

import okhttp3.Dns
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap


/**
 * 带 TTL 的 DNS 缓存。
 *
 * 直播源域名大多是 CDN，同一域名下多个 A 记录 / 按地区解析到不同 IP，永久缓存会锁死一个 IP，
 * 一旦失效会导致反复切台失败。这里按 TTL 过期（默认 10 分钟），并支持外力触发刷新。
 */
class DnsCache(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
) : Dns {

    private data class Entry(
        val addresses: List<InetAddress>,
        val expireAt: Long,
    )

    private val dnsCache = ConcurrentHashMap<String, Entry>()

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname.isEmpty()) {
            return Dns.SYSTEM.lookup(hostname)
        }

        val now = System.currentTimeMillis()
        dnsCache[hostname]?.let { entry ->
            if (entry.expireAt > now) {
                return entry.addresses
            }
            // 过期：移除后重新解析
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
            dnsCache[hostname] = Entry(addressesNew, now + ttlMillis)
        }

        return addressesNew
    }

    /** 强制清除某域名缓存，便于在连接失败后立即触发重解析。 */
    fun invalidate(hostname: String) {
        dnsCache.remove(hostname)
    }

    /** 清理全部缓存。 */
    fun clear() {
        dnsCache.clear()
    }

    companion object {
        const val DEFAULT_TTL_MILLIS = 10 * 60 * 1000L // 10 分钟
    }
}
