package com.abdallahmaher.adshield

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * أدوات مساعدة لتحليل حزم IPv4/UDP القادمة من واجهة الـ VPN،
 * واستخراج اسم النطاق من طلب DNS، وبناء حزم رد (محجوب أو ممرَّر من سيرفر حقيقي).
 * كل الحزم المعالجة هنا هي حزم DNS فقط (بورت 53)، باقي الحركة لا تدخل النفق أصلاً
 * لأن الراوت المضاف في الـ VpnService يقتصر على عنوان الـ DNS الوهمي فقط.
 */
object DnsPacketUtils {

    data class IPv4UdpPacket(
        val sourceAddress: ByteArray,
        val destAddress: ByteArray,
        val sourcePort: Int,
        val destPort: Int,
        val payload: ByteArray
    )

    fun parseIPv4Udp(packet: ByteArray): IPv4UdpPacket? {
        if (packet.isEmpty()) return null
        val version = (packet[0].toInt() shr 4) and 0xF
        if (version != 4) return null

        val ihl = (packet[0].toInt() and 0xF) * 4
        if (packet.size < ihl + 8) return null

        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return null // UDP فقط

        val sourceAddress = packet.copyOfRange(12, 16)
        val destAddress = packet.copyOfRange(16, 20)

        val udpStart = ihl
        val sourcePort = ((packet[udpStart].toInt() and 0xFF) shl 8) or (packet[udpStart + 1].toInt() and 0xFF)
        val destPort = ((packet[udpStart + 2].toInt() and 0xFF) shl 8) or (packet[udpStart + 3].toInt() and 0xFF)

        if (destPort != 53) return null // استعلامات DNS فقط

        val udpLength = ((packet[udpStart + 4].toInt() and 0xFF) shl 8) or (packet[udpStart + 5].toInt() and 0xFF)
        val payloadStart = udpStart + 8
        val payloadEnd = (udpStart + udpLength).coerceAtMost(packet.size)
        if (payloadStart >= payloadEnd) return null

        val payload = packet.copyOfRange(payloadStart, payloadEnd)
        return IPv4UdpPacket(sourceAddress, destAddress, sourcePort, destPort, payload)
    }

    fun extractQueryDomain(dnsPayload: ByteArray): String? {
        return try {
            if (dnsPayload.size < 13) return null
            var pos = 12
            val sb = StringBuilder()
            while (pos < dnsPayload.size) {
                val len = dnsPayload[pos].toInt() and 0xFF
                if (len == 0) break
                pos++
                if (pos + len > dnsPayload.size) return null
                if (sb.isNotEmpty()) sb.append('.')
                sb.append(String(dnsPayload, pos, len, Charsets.US_ASCII))
                pos += len
            }
            if (sb.isEmpty()) null else sb.toString()
        } catch (e: Exception) {
            null
        }
    }

    fun buildBlockedResponse(request: IPv4UdpPacket): ByteArray? {
        val dnsResponse = buildBlockedDnsAnswer(request.payload) ?: return null
        return buildIPv4UdpPacket(
            sourceAddress = request.destAddress,
            destAddress = request.sourceAddress,
            sourcePort = request.destPort,
            destPort = request.sourcePort,
            payload = dnsResponse
        )
    }

    fun buildResponsePacket(request: IPv4UdpPacket, dnsResponse: ByteArray): ByteArray {
        return buildIPv4UdpPacket(
            sourceAddress = request.destAddress,
            destAddress = request.sourceAddress,
            sourcePort = request.destPort,
            destPort = request.sourcePort,
            payload = dnsResponse
        )
    }

    /** يبني رد DNS بعنوان IP محدد بدل عنوان اللعبة الأصلي (لخاصية eFootball) */
    fun buildRedirectResponse(request: IPv4UdpPacket, ip: String): ByteArray? {
        val dnsResponse = buildRedirectDnsAnswer(request.payload, ip) ?: return null
        return buildIPv4UdpPacket(
            sourceAddress = request.destAddress,
            destAddress = request.sourceAddress,
            sourcePort = request.destPort,
            destPort = request.sourcePort,
            payload = dnsResponse
        )
    }

    private fun buildRedirectDnsAnswer(query: ByteArray, ip: String): ByteArray? {
        if (query.size < 12) return null
        val parts = ip.trim().split(".")
        if (parts.size != 4) return null
        val addrBytes = try {
            ByteArray(4) { i -> parts[i].toInt().toByte() }
        } catch (e: Exception) {
            return null
        }

        val header = ByteArray(12)
        header[0] = query[0]; header[1] = query[1]
        header[2] = 0x81.toByte()
        header[3] = 0x80.toByte()
        header[4] = 0x00; header[5] = 0x01
        header[6] = 0x00; header[7] = 0x01
        header[8] = 0x00; header[9] = 0x00
        header[10] = 0x00; header[11] = 0x00

        val question = query.copyOfRange(12, query.size)

        val answer = ByteArray(16)
        answer[0] = 0xC0.toByte(); answer[1] = 0x0C
        answer[2] = 0x00; answer[3] = 0x01
        answer[4] = 0x00; answer[5] = 0x01
        answer[6] = 0x00; answer[7] = 0x00; answer[8] = 0x00; answer[9] = 0x3C
        answer[10] = 0x00; answer[11] = 0x04
        addrBytes.copyInto(answer, 12)

        return header + question + answer
    }

    /** يبني رد DNS يقول إن النطاق موجود لكن عنوانه 0.0.0.0 (يوقف تحميل الإعلان فورًا) */
    private fun buildBlockedDnsAnswer(query: ByteArray): ByteArray? {
        if (query.size < 12) return null
        val id0 = query[0]
        val id1 = query[1]

        val header = ByteArray(12)
        header[0] = id0; header[1] = id1
        header[2] = 0x81.toByte() // QR=1 (رد), Opcode=0, RD=1
        header[3] = 0x80.toByte() // RA=1, RCODE=0 (بدون خطأ)
        header[4] = 0x00; header[5] = 0x01 // QDCOUNT = 1
        header[6] = 0x00; header[7] = 0x01 // ANCOUNT = 1
        header[8] = 0x00; header[9] = 0x00 // NSCOUNT = 0
        header[10] = 0x00; header[11] = 0x00 // ARCOUNT = 0

        val question = query.copyOfRange(12, query.size)

        val answer = ByteArray(16)
        answer[0] = 0xC0.toByte(); answer[1] = 0x0C // اسم = مؤشر لبداية السؤال
        answer[2] = 0x00; answer[3] = 0x01 // TYPE A
        answer[4] = 0x00; answer[5] = 0x01 // CLASS IN
        answer[6] = 0x00; answer[7] = 0x00; answer[8] = 0x00; answer[9] = 0x3C // TTL = 60 ثانية
        answer[10] = 0x00; answer[11] = 0x04 // RDLENGTH = 4 بايت
        answer[12] = 0; answer[13] = 0; answer[14] = 0; answer[15] = 0 // 0.0.0.0

        return header + question + answer
    }

    private fun buildIPv4UdpPacket(
        sourceAddress: ByteArray,
        destAddress: ByteArray,
        sourcePort: Int,
        destPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLength = 8 + payload.size
        val totalLength = 20 + udpLength

        val buffer = ByteBuffer.allocate(totalLength)
        buffer.order(ByteOrder.BIG_ENDIAN)

        // ترويسة IPv4
        buffer.put(0x45.toByte()) // Version=4, IHL=5
        buffer.put(0x00) // DSCP/ECN
        buffer.putShort(totalLength.toShort())
        buffer.putShort(0) // Identification
        buffer.putShort(0x4000.toShort()) // Flags: Don't Fragment
        buffer.put(64.toByte()) // TTL
        buffer.put(17.toByte()) // Protocol = UDP
        buffer.putShort(0) // Checksum placeholder
        buffer.put(sourceAddress)
        buffer.put(destAddress)

        val ipHeaderBytes = buffer.array().copyOfRange(0, 20)
        val ipChecksum = checksum(ipHeaderBytes)
        buffer.putShort(10, ipChecksum.toShort())

        // ترويسة UDP
        buffer.putShort(sourcePort.toShort())
        buffer.putShort(destPort.toShort())
        buffer.putShort(udpLength.toShort())
        buffer.putShort(0) // UDP checksum اختياري فى IPv4 - نتركه صفر

        buffer.put(payload)

        return buffer.array()
    }

    private fun checksum(data: ByteArray): Int {
        var sum = 0
        var i = 0
        while (i < data.size - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < data.size) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }
}
