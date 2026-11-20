package kr.jamsuham.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.os.Build
import kr.jamsuham.core.net.AddressResolver
import kr.jamsuham.core.net.RawDnsQuery
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * SRV 레코드 조회. 안드로이드 10 이상은 시스템 DNS(사설 DNS 설정 포함)를 쓰고,
 * 그보다 낮거나 실패하면 현재 네트워크의 DNS 서버와 공용 DNS 에 직접 묻는다.
 */
class AndroidDns(context: Context) : RawDnsQuery {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun query(name: String, type: Int): ByteArray? {
        if (Build.VERSION.SDK_INT >= 29) {
            systemQuery(name, type)?.let { return it }
        }
        val servers = ArrayList<String>()
        try {
            cm.getLinkProperties(cm.activeNetwork)?.dnsServers?.forEach { addr ->
                addr.hostAddress?.let { servers.add(it) }
            }
        } catch (_: Throwable) {
        }
        servers.add("1.1.1.1")
        servers.add("8.8.8.8")
        return AddressResolver.UdpDns(servers.distinct()).query(name, type)
    }

    private fun systemQuery(name: String, type: Int): ByteArray? {
        if (Build.VERSION.SDK_INT < 29) return null
        val result = AtomicReference<ByteArray?>(null)
        val latch = CountDownLatch(1)
        return try {
            DnsResolver.getInstance().rawQuery(
                null, name, DnsResolver.CLASS_IN, type, DnsResolver.FLAG_EMPTY,
                Executor { it.run() }, null,
                object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(answer: ByteArray, rcode: Int) {
                        result.set(answer)
                        latch.countDown()
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        latch.countDown()
                    }
                },
            )
            latch.await(4, TimeUnit.SECONDS)
            result.get()
        } catch (_: Throwable) {
            null
        }
    }
}
