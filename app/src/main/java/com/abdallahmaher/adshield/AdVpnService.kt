package com.abdallahmaher.adshield

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * خدمة VPN خفيفة: بتحجز بس عنوان DNS وهمي وتوجّه إليه استعلامات DNS بتاعة النظام كله.
 * أي طلب لنطاق موجود في القائمة السوداء بيترد عليه بـ 0.0.0.0 فورًا (يمنع تحميل الإعلان).
 * أي طلب تاني بيتمرر لسيرفر DNS حقيقي (Cloudflare) ويترجع رده زي ما هو.
 * باقي حركة الإنترنت (غير DNS) مالهاش علاقة بالـ VPN خالص وبتمشي عادي، عشان كده الحل خفيف وسريع.
 */
class AdVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.abdallahmaher.adshield.START"
        const val ACTION_STOP = "com.abdallahmaher.adshield.STOP"
        private const val TAG = "AdShieldVpn"
        private const val CHANNEL_ID = "adshield_channel"
        private const val NOTIF_ID = 1
        private const val VPN_ADDRESS = "10.111.222.1"
        private const val DNS_ADDRESS = "10.111.222.2"
        private const val UPSTREAM_DNS = "1.1.1.1"

        @Volatile var isRunning: Boolean = false
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    private var workerThread: Thread? = null
    private var blocklist: Set<String> = emptySet()

    // إعدادات خاصية "خاص بـ eFootball™" - بتتحمل من جديد كل مرة يتشغل فيها الحجب
    private var efootballEnabled: Boolean = false
    private var efootballTargets: Set<String> = emptySet()
    private var efootballRules: EFootballRules = EFootballRules(emptySet(), emptyMap())

    override fun onCreate() {
        super.onCreate()
        blocklist = BlocklistManager.load(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (running.get()) return

        // نحمّل إعدادات eFootball في كل مرة يتشغل فيها الحجب، عشان أي تغيير في الإعدادات ياخد مفعوله فورًا
        efootballEnabled = PreferencesRepository.isEFootballEnabled(this)
        efootballTargets = PreferencesRepository.getEFootballTargetApps(this)
        efootballRules = if (efootballEnabled) EFootballRuleManager.load(this) else EFootballRules(emptySet(), emptyMap())

        val builder = Builder()
            .setSession("AdShield Lite")
            .addAddress(VPN_ADDRESS, 24)
            .addDnsServer(DNS_ADDRESS)
            .addRoute(DNS_ADDRESS, 32)
            .setMtu(1500)

        val iface = builder.establish()
        if (iface == null) {
            Log.e(TAG, "فشل إنشاء واجهة الـ VPN")
            return
        }
        vpnInterface = iface

        running.set(true)
        isRunning = true
        startForeground(NOTIF_ID, buildNotification())

        workerThread = thread(start = true, name = "AdShieldWorker") { runLoop() }
    }

    private fun stopVpn() {
        running.set(false)
        isRunning = false
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "خطأ عند إغلاق الواجهة", e)
        }
        vpnInterface = null
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun runLoop() {
        val iface = vpnInterface ?: return
        val input = FileInputStream(iface.fileDescriptor)
        val output = FileOutputStream(iface.fileDescriptor)
        val buffer = ByteArray(32767)

        while (running.get()) {
            try {
                val length = input.read(buffer)
                if (length <= 0) continue
                val packet = buffer.copyOf(length)
                handlePacket(packet, output)
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "خطأ أثناء قراءة الحزم", e)
            }
        }
    }

    private fun handlePacket(packet: ByteArray, output: FileOutputStream) {
        val ipPacket = DnsPacketUtils.parseIPv4Udp(packet) ?: return
        val domain = DnsPacketUtils.extractQueryDomain(ipPacket.payload)?.lowercase() ?: return

        // خاصية "خاص بـ eFootball™": ليها أولوية، وبتتطبق بس على التطبيقات المحددة
        if (efootballEnabled && efootballTargets.isNotEmpty() && appliesToEFootballTarget(ipPacket)) {
            efootballRules.redirectedHosts[domain]?.let { ip ->
                val response = DnsPacketUtils.buildRedirectResponse(ipPacket, ip)
                if (response != null) {
                    output.write(response)
                    return
                }
            }
            if (efootballRules.blockedHosts.contains(domain)) {
                val response = DnsPacketUtils.buildBlockedResponse(ipPacket)
                if (response != null) {
                    output.write(response)
                    return
                }
            }
        }

        if (isBlocked(domain)) {
            val response = DnsPacketUtils.buildBlockedResponse(ipPacket) ?: return
            output.write(response)
        } else {
            forwardToUpstream(ipPacket, output)
        }
    }

    /**
     * بيتحقق إن طلب الـ DNS ده جاي من واحد من التطبيقات اللي المستخدم حددها لخاصية eFootball.
     * تحديد التطبيق المصدر متاح من أندرويد 10 (API 29) فأعلى فقط؛ قبل كده بنطبق القاعدة بشكل عام
     * كحل بديل لأن مافيش وسيلة رسمية لمعرفة صاحب الاتصال.
     */
    private fun appliesToEFootballTarget(ipPacket: DnsPacketUtils.IPv4UdpPacket): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true

        return try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val local = InetSocketAddress(InetAddress.getByAddress(ipPacket.sourceAddress), ipPacket.sourcePort)
            val remote = InetSocketAddress(InetAddress.getByAddress(ipPacket.destAddress), ipPacket.destPort)
            val uid = cm.getConnectionOwnerUid(OsConstants.IPPROTO_UDP, local, remote)
            if (uid < 0) return false
            val owners = packageManager.getPackagesForUid(uid) ?: return false
            owners.any { efootballTargets.contains(it) }
        } catch (e: Exception) {
            false
        }
    }

    private fun isBlocked(domain: String): Boolean {
        var d = domain.lowercase()
        while (true) {
            if (blocklist.contains(d)) return true
            val dot = d.indexOf('.')
            if (dot < 0) return false
            d = d.substring(dot + 1)
        }
    }

    private fun forwardToUpstream(ipPacket: DnsPacketUtils.IPv4UdpPacket, output: FileOutputStream) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            protect(socket) // مهم جدًا: يمنع حلقة لا نهائية بتمرير حركة السوكت ده برا النفق
            socket.soTimeout = 4000

            val outPacket = DatagramPacket(
                ipPacket.payload, ipPacket.payload.size,
                InetSocketAddress(UPSTREAM_DNS, 53)
            )
            socket.send(outPacket)

            val replyBuffer = ByteArray(4096)
            val replyPacket = DatagramPacket(replyBuffer, replyBuffer.size)
            socket.receive(replyPacket)

            val replyData = replyBuffer.copyOf(replyPacket.length)
            val responsePacket = DnsPacketUtils.buildResponsePacket(ipPacket, replyData)
            output.write(responsePacket)
        } catch (e: Exception) {
            Log.e(TAG, "فشل تمرير استعلام DNS لسيرفر حقيقي", e)
        } finally {
            socket?.close()
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID, "حجب الإعلانات",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("حاجب الإعلانات شغال")
            .setContentText("جاري فلترة إعلانات كل التطبيقات على مستوى الـ DNS")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
