package com.celzero.bravedns.net.manager

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.text.TextUtils
import androidx.annotation.RequiresApi
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Protocol
import com.celzero.bravedns.util.Utilities.isUnspecifiedIp
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import inet.ipaddr.IPAddressString
import java.net.InetSocketAddress
import java.net.SocketException
import java.time.Duration

class ConnectionTracer(ctx: Context) {

    companion object {
        private const val CACHE_BUILDER_WRITE_EXPIRE_SEC: Long = 300
        private const val CACHE_BUILDER_MAX_SIZE: Long = 1000
        // key format (Prot 17|Src 10.111.222.1| Dst 10.111.222.3| Dst port 53)
        private const val DNS_KEY = "17|10.111.222.1|10.111.222.3|53"
        private const val SEPARATOR = "|"
    }

    private val cm: ConnectivityManager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    // Cache the UID for the next 300 seconds.
    // the UID will expire after 300 seconds of to write.
    // Key for the cache is protocol, local, remote
    private val uidCache: Cache<String, Int> = CacheBuilder.newBuilder()
        .maximumSize(CACHE_BUILDER_MAX_SIZE)
        .expireAfterWrite(Duration.ofSeconds(CACHE_BUILDER_WRITE_EXPIRE_SEC))
        .build()

    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun getUidQ(
        protocol: Int,
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int,
        retryCount: Int = 0
    ): Int {
        var uid = Constants.INVALID_UID

        when (protocol) {
            Protocol.ICMP.protocolType,
            Protocol.ICMPV6.protocolType -> {
                // workaround for ICMP/ICMPv6
                // commenting this out as the workaround is not working as expected
                // revisit if a better workaround is found
                return uid
            }

            Protocol.TCP.protocolType,
            Protocol.UDP.protocolType -> {
            }

            else -> {
                // android.googlesource.com/platform/development/+/da84168fb/ndk/platforms/android-21/include/linux/in.h
                return uid
            }
        }

        val local: InetSocketAddress
        val remote: InetSocketAddress
        try {
            local =
                if (TextUtils.isEmpty(srcIp)) {
                    InetSocketAddress(srcPort)
                } else {
                    InetSocketAddress(srcIp, srcPort)
                }
            remote =
                if (TextUtils.isEmpty(dstIp)) {
                    InetSocketAddress(dstPort)
                } else {
                    InetSocketAddress(dstIp, dstPort)
                }
        } catch (e: IllegalArgumentException) {
            // InetSocketAddress throws IllegalArgumentException or SecurityException
            return uid
        } catch (e: SecurityException) {
            return uid
        }
        val key = makeCacheKey(protocol, local, remote)
        try {
            // must be called from io thread to avoid the NetworkOnMainThreadException issue#853
            uid = cm.getConnectionOwnerUid(protocol, local, remote)
            if (uid != Constants.INVALID_UID) {
                addUidToCache(key, uid)
                return uid
            }
        } catch (secEx: SecurityException) {
        } catch (ex: InterruptedException) { // InterruptedException is thrown by runBlocking
        } catch (ex: SocketException) {
            // socket closed or aborted while tracing, no need to log the stack trace
        } catch (ex: Exception) {
        }

        if (retryCount >= 2) return uid

        if (retryRequired(uid, protocol, dstIp, key)) {
            when (retryCount) {
                0 -> {
                    // change the destination port to 0 and try again
                    val dport = 0
                    val retryCount = 1
                    val res = getUidQ(protocol, srcIp, srcPort, dstIp, dport, retryCount)
                    return res
                }
                1 -> {
                    // change the destination IP to unspecified IP and try again for unconnected UDP
                    val dip = if (IPAddressString(dstIp).isIPv6) {
                        Constants.UNSPECIFIED_IP_IPV6
                    } else {
                        Constants.UNSPECIFIED_IP_IPV4
                    }
                    val dport = 0
                    val retryCount = 2
                    val res = getUidQ(protocol, srcIp, srcPort, dip, dport, retryCount)
                    return res
                }
                else -> {
                    return uid
                }
            }
        }

        // If the uid is not in connectivity manager, then return the uid from cache.
        uid = uidCache.getIfPresent(key) ?: Constants.INVALID_UID
        return uid
    }

    // handle unconnected UDP requests
    private fun retryRequired(uid: Int, protocol: Int, destIp: String, key: String): Boolean {
        if (uid != Constants.INVALID_UID) { // already got the uid, no need to retry
            return false
        }
        // if uid is already cached, no need to retry
        if (uidCache.getIfPresent(key) != null) {
            return false
        }
        // no need to retry for protocols other than UDP
        if (protocol != Protocol.UDP.protocolType) {
            return false
        }

        // no need to retry for unspecified IP, as it is already tried twice
        return !isUnspecifiedIp(destIp)
    }

    private fun addUidToCache(key: String, uid: Int) {
        // do not cache the DNS request (key: 17|10.111.222.1|10.111.222.3|53)
        if (key == DNS_KEY) return

        try {
            uidCache.put(key, uid)
        } catch (t: Throwable) {
        }
    }

    private fun makeCacheKey(
        protocol: Int,
        local: InetSocketAddress,
        remote: InetSocketAddress
    ): String {
        return protocol.toString() +
            SEPARATOR +
            local.address.hostAddress +
            SEPARATOR +
            remote.address.hostAddress +
            SEPARATOR +
            remote.port
    }
}
