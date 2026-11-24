package com.sepehr.ikev2.vpn

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.security.SecureRandom

enum class PingStatus {
    IDLE,
    PINGING,
    REACHABLE,
    UNREACHABLE
}

data class PingResultUi(
    val status: PingStatus = PingStatus.IDLE,
    val latencyMs: Int? = null
)

/**
 * Performs a real IKEv2 IKE_SA_INIT (RFC 7296) UDP handshake probe on port 500 and NAT-T port 4500.
 * Unlike ICMP ping, this verifies that the ISP allows UDP 500/4500 and the IKEv2 daemon is alive.
 */
object Ikev2RealPingProber {

    private val secureRandom = SecureRandom()

    suspend fun probe(serverIp: String): PingResultUi = withContext(Dispatchers.IO) {
        val rtt500 = probePort(serverIp, port = 500, timeoutMs = 1800)
        if (rtt500 != null) {
            return@withContext PingResultUi(status = PingStatus.REACHABLE, latencyMs = rtt500)
        }
        val rtt4500 = probePort(serverIp, port = 4500, timeoutMs = 1600)
        if (rtt4500 != null) {
            return@withContext PingResultUi(status = PingStatus.REACHABLE, latencyMs = rtt4500)
        }
        PingResultUi(status = PingStatus.UNREACHABLE, latencyMs = null)
    }

    private fun probePort(ip: String, port: Int, timeoutMs: Int): Int? {
        val spi = ByteArray(8).also { secureRandom.nextBytes(it) }
        val ikePacket = buildIkeSaInitPacket(spi)
        val wirePacket = if (port == 4500) {
            ByteArray(4 + ikePacket.size).also { out ->
                System.arraycopy(ikePacket, 0, out, 4, ikePacket.size)
            }
        } else {
            ikePacket
        }

        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                val address = InetAddress.getByName(ip)
                val outPacket = DatagramPacket(wirePacket, wirePacket.size, address, port)
                val recvBuffer = ByteArray(2048)
                val inPacket = DatagramPacket(recvBuffer, recvBuffer.size)

                val startMs = SystemClock.elapsedRealtime()
                socket.send(outPacket)
                socket.receive(inPacket)
                val rttMs = (SystemClock.elapsedRealtime() - startMs).toInt().coerceAtLeast(1)

                val len = inPacket.length
                val offset = if (
                    port == 4500 &&
                    len >= 32 &&
                    recvBuffer[0] == 0.toByte() &&
                    recvBuffer[1] == 0.toByte() &&
                    recvBuffer[2] == 0.toByte() &&
                    recvBuffer[3] == 0.toByte()
                ) 4 else 0

                if (len < offset + 28) return null

                for (i in 0 until 8) {
                    if (recvBuffer[offset + i] != spi[i]) return null
                }

                val versionByte = recvBuffer[offset + 17].toInt() and 0xFF
                val exchangeType = recvBuffer[offset + 18].toInt() and 0xFF
                val flagsByte = recvBuffer[offset + 19].toInt() and 0xFF

                val isIkeV2 = (versionByte and 0xF0) == 0x20
                val isIkeSaInit = exchangeType == 34
                val isResponse = (flagsByte and 0x20) == 0x20

                if (isIkeV2 && isIkeSaInit && isResponse) rttMs else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildIkeSaInitPacket(spi: ByteArray): ByteArray {
        val keData = ByteArray(256).also { secureRandom.nextBytes(it) } // DH Group 14 (2048-bit)
        val nonceData = ByteArray(32).also { secureRandom.nextBytes(it) }

        val nonceLen = 4 + nonceData.size
        val keLen = 8 + keData.size

        // Proposal #1: AES-CBC-256, PRF-HMAC-SHA256, AUTH-HMAC-SHA256-128, DH Group 14
        val t1 = byteArrayOf(3, 0, 0, 12, 1, 0, 0, 12, 0x80.toByte(), 0x0e, 0x01, 0x00)
        val t2 = byteArrayOf(3, 0, 0, 8, 2, 0, 0, 5)
        val t3 = byteArrayOf(3, 0, 0, 8, 3, 0, 0, 12)
        val t4 = byteArrayOf(0, 0, 0, 8, 4, 0, 0, 14)
        val propLen = 8 + t1.size + t2.size + t3.size + t4.size
        val saLen = 4 + propLen

        val totalLen = 28 + saLen + keLen + nonceLen
        val buf = ByteBuffer.allocate(totalLen)

        // IKEv2 Header (28 bytes)
        buf.put(spi) // Initiator SPI
        buf.put(ByteArray(8)) // Responder SPI = 0
        buf.put(33.toByte()) // Next Payload: SA
        buf.put(0x20.toByte()) // Version 2.0
        buf.put(34.toByte()) // Exchange Type: IKE_SA_INIT
        buf.put(0x08.toByte()) // Flags: Initiator
        buf.putInt(0) // Message ID
        buf.putInt(totalLen)

        // SA Payload
        buf.put(34.toByte()) // Next Payload: KE
        buf.put(0.toByte())
        buf.putShort(saLen.toShort())

        // Proposal Header
        buf.put(0.toByte()) // Last Proposal
        buf.put(0.toByte())
        buf.putShort(propLen.toShort())
        buf.put(1.toByte()) // Proposal #1
        buf.put(1.toByte()) // Protocol: IKE
        buf.put(0.toByte()) // SPI Size
        buf.put(4.toByte()) // Num Transforms
        buf.put(t1)
        buf.put(t2)
        buf.put(t3)
        buf.put(t4)

        // KE Payload
        buf.put(40.toByte()) // Next Payload: Nonce
        buf.put(0.toByte())
        buf.putShort(keLen.toShort())
        buf.putShort(14.toShort()) // DH Group 14
        buf.putShort(0.toShort())
        buf.put(keData)

        // Nonce Payload
        buf.put(0.toByte()) // Next Payload: None
        buf.put(0.toByte())
        buf.putShort(nonceLen.toShort())
        buf.put(nonceData)

        return buf.array()
    }
}
