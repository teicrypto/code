package com.teicrypto.syswidget

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tìm cổng của dịch vụ ADB không dây (mDNS) đang chạy trên CHÍNH máy này.
 *  - "_adb-tls-pairing._tcp": cổng ghép nối (chỉ hiện khi đang mở hộp thoại "Ghép nối bằng mã")
 *  - "_adb-tls-connect._tcp": cổng kết nối
 * Chạy blocking, gọi từ luồng nền. Trả về null nếu hết thời gian hoặc bị hủy.
 */
object NsdFinder {

    private fun isLocal(host: InetAddress?): Boolean {
        if (host == null || host.isLoopbackAddress) return true
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.any { ni ->
                ni.inetAddresses.toList().contains(host)
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    fun find(ctx: Context, type: String, timeoutMs: Long, cancelled: () -> Boolean): Int? {
        val nsd = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        val latch = CountDownLatch(1)
        val found = AtomicInteger(-1)
        val failed = AtomicBoolean(false)
        val resolving = AtomicBoolean(false)

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                failed.set(true)
                latch.countDown()
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                if (!resolving.compareAndSet(false, true)) return
                try {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(i: NsdServiceInfo, errorCode: Int) {
                            resolving.set(false)
                        }

                        @Suppress("DEPRECATION")
                        override fun onServiceResolved(i: NsdServiceInfo) {
                            // Bỏ qua dịch vụ ADB của máy khác trong mạng LAN
                            if (isLocal(i.host) && i.port > 0) {
                                found.set(i.port)
                                latch.countDown()
                            } else {
                                resolving.set(false)
                            }
                        }
                    })
                } catch (e: Throwable) {
                    resolving.set(false)
                }
            }
        }

        try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Throwable) {
            return null
        }

        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (found.get() < 0 && !failed.get() && !cancelled() &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            latch.await(300, TimeUnit.MILLISECONDS)
        }

        try {
            nsd.stopServiceDiscovery(listener)
        } catch (e: Throwable) {
        }
        return found.get().takeIf { it > 0 }
    }
}
