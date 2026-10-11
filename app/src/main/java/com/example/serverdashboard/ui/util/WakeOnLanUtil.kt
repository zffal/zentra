package com.example.serverdashboard.ui.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object WakeOnLanUtil {

    suspend fun sendMagicPacket(
        macAddress: String,
        broadcastIp: String = "192.168.1.255",
        port: Int = 9
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val cleanMac = macAddress.trim().replace(":", "").replace("-", "")
            if (cleanMac.length != 12) {
                return@withContext Result.failure(IllegalArgumentException("Invalid MAC address length (expected 12 hex digits)"))
            }

            val macBytes = ByteArray(6)
            for (i in 0 until 6) {
                val byteStr = cleanMac.substring(i * 2, i * 2 + 2)
                macBytes[i] = byteStr.toInt(16).toByte()
            }

            // Magic packet = 6 bytes of 0xFF followed by 16 repetitions of target MAC (102 bytes total)
            val packetData = ByteArray(6 + 16 * 6)
            for (i in 0 until 6) {
                packetData[i] = 0xFF.toByte()
            }
            for (i in 0 until 16) {
                System.arraycopy(macBytes, 0, packetData, 6 + i * 6, 6)
            }

            val address = InetAddress.getByName(broadcastIp.trim())
            val socket = DatagramSocket()
            socket.broadcast = true

            val packet = DatagramPacket(packetData, packetData.size, address, port)
            socket.send(packet)
            socket.close()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
