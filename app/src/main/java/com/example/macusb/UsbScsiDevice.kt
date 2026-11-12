package com.example.macusb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Raw SCSI over USB Bulk-Only Transport. Needs USB permission granted first
 * (UsbManager.requestPermission). No root required.
 */
class UsbScsiDevice(
    private val usbManager: UsbManager,
    private val device: UsbDevice
) : AutoCloseable {

    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var tagCounter = 1

    var blockSize: Int = 512
        private set
    var totalBlocks: Long = 0
        private set

    val capacityBytes: Long get() = totalBlocks * blockSize

    fun open() {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass != UsbConstants.USB_CLASS_MASS_STORAGE ||
                intf.interfaceSubclass != 6 || intf.interfaceProtocol != 80
            ) continue

            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
                }
            }
            if (inEp == null || outEp == null) continue

            val conn = usbManager.openDevice(device)
                ?: throw IOException("openDevice failed (USB permission not granted?)")
            if (!conn.claimInterface(intf, true)) { // force = detach kernel driver
                conn.close()
                throw IOException("Could not claim USB interface")
            }
            connection = conn; usbInterface = intf; epIn = inEp; epOut = outEp
            waitUntilReady()
            readCapacity()
            return
        }
        throw IOException("No USB mass-storage (SCSI/BOT) interface found")
    }

    override fun close() {
        usbInterface?.let { connection?.releaseInterface(it) }
        connection?.close()
        connection = null
    }

    // ---- SCSI commands -------------------------------------------------

    private fun waitUntilReady() {
        repeat(15) {
            try {
                exec(ByteArray(6), null, 0, 0, dataIn = false) // TEST UNIT READY
                return
            } catch (e: IOException) {
                Thread.sleep(400)
            }
        }
        throw IOException("Drive not ready")
    }

    private fun readCapacity() {
        val cdb = ByteArray(10).also { it[0] = 0x25 }
        val data = ByteArray(8)
        exec(cdb, data, 0, 8, dataIn = true)
        val b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val lastLba = b.int.toLong() and 0xFFFFFFFFL
        blockSize = b.int
        totalBlocks = lastLba + 1
        if (blockSize <= 0) throw IOException("Bad block size")
    }

    fun writeBlocks(startLba: Long, data: ByteArray, offset: Int, blocks: Int) {
        require(blocks in 1..0xFFFF)
        require(startLba + blocks <= totalBlocks) { "Write beyond end of device" }
        val cdb = ByteArray(10)
        cdb[0] = 0x2A // WRITE(10)
        cdb[2] = (startLba shr 24).toByte(); cdb[3] = (startLba shr 16).toByte()
        cdb[4] = (startLba shr 8).toByte();  cdb[5] = startLba.toByte()
        cdb[7] = (blocks shr 8).toByte();    cdb[8] = blocks.toByte()
        exec(cdb, data, offset, blocks * blockSize, dataIn = false)
    }

    fun syncCache() {
        val cdb = ByteArray(10).also { it[0] = 0x35 } // SYNCHRONIZE CACHE(10)
        try { exec(cdb, null, 0, 0, dataIn = false, timeoutMs = 60_000) } catch (_: IOException) {}
    }

    /** Streams a raw image to LBA 0. Zero-pads the final partial block. */
    fun flashImage(
        input: InputStream,
        totalBytes: Long,
        onProgress: (written: Long, total: Long) -> Unit
    ) {
        if (totalBytes > capacityBytes) {
            throw IOException("Image (${totalBytes / 1_000_000} MB) is larger than the drive (${capacityBytes / 1_000_000} MB)")
        }
        val blocksPerCmd = (65536 / blockSize).coerceAtLeast(1) // 64 KB per SCSI command
        val buf = ByteArray(blocksPerCmd * blockSize)
        var lba = 0L
        var written = 0L
        while (true) {
            var filled = 0
            while (filled < buf.size) {
                val n = input.read(buf, filled, buf.size - filled)
                if (n < 0) break
                filled += n
            }
            if (filled == 0) break
            val blocks = (filled + blockSize - 1) / blockSize
            if (filled < blocks * blockSize) java.util.Arrays.fill(buf, filled, blocks * blockSize, 0)
            writeBlocks(lba, buf, 0, blocks)
            lba += blocks
            written += filled
            onProgress(written, totalBytes)
            if (filled < buf.size) break
        }
        syncCache()
    }

    // ---- Bulk-Only Transport -------------------------------------------

    private fun exec(
        cdb: ByteArray, data: ByteArray?, offset: Int, length: Int,
        dataIn: Boolean, timeoutMs: Int = 15_000, allowSense: Boolean = true
    ) {
        val conn = connection ?: throw IOException("Device not open")
        val out = epOut!!; val inn = epIn!!
        val tag = tagCounter++

        val cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x43425355); putInt(tag); putInt(length)
            put(if (dataIn) 0x80.toByte() else 0); put(0); put(cdb.size.toByte()); put(cdb)
        }.array()
        if (conn.bulkTransfer(out, cbw, 31, timeoutMs) != 31) {
            resetRecovery(); throw IOException("CBW send failed")
        }

        if (length > 0 && data != null) {
            val ep = if (dataIn) inn else out
            var done = 0
            while (done < length) {
                val chunk = minOf(MAX_XFER, length - done)
                val n = conn.bulkTransfer(ep, data, offset + done, chunk, timeoutMs)
                if (n < 0) { resetRecovery(); throw IOException("Data phase failed at $done/$length") }
                done += n
            }
        }

        val csw = ByteArray(13)
        var n = conn.bulkTransfer(inn, csw, 13, timeoutMs)
        if (n != 13) { // possible STALL: clear halt and retry once
            clearHalt(inn)
            n = conn.bulkTransfer(inn, csw, 13, timeoutMs)
        }
        if (n != 13) { resetRecovery(); throw IOException("CSW read failed") }

        val b = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN)
        val sig = b.int; val rTag = b.int; b.int
        val status = b.get().toInt() and 0xFF
        if (sig != 0x53425355 || rTag != tag) { resetRecovery(); throw IOException("Bad CSW") }
        when (status) {
            0 -> return
            1 -> {
                val sense = if (allowSense) requestSense() else "n/a"
                throw IOException("SCSI command failed ($sense)")
            }
            else -> { resetRecovery(); throw IOException("Phase error") }
        }
    }

    private fun requestSense(): String = try {
        val cdb = ByteArray(6).also { it[0] = 0x03; it[4] = 18 }
        val d = ByteArray(18)
        exec(cdb, d, 0, 18, dataIn = true, allowSense = false)
        "sense key=%d asc=%02X ascq=%02X".format(d[2].toInt() and 0xF, d[12], d[13])
    } catch (e: IOException) { "sense unavailable" }

    private fun resetRecovery() {
        val conn = connection ?: return
        val id = usbInterface?.id ?: 0
        conn.controlTransfer(0x21, 0xFF, 0, id, null, 0, 5000) // Bulk-Only Mass Storage Reset
        epIn?.let { clearHalt(it) }
        epOut?.let { clearHalt(it) }
    }

    private fun clearHalt(ep: UsbEndpoint) {
        connection?.controlTransfer(0x02, 0x01, 0, ep.address, null, 0, 5000) // CLEAR_FEATURE(ENDPOINT_HALT)
    }

    private companion object {
        const val MAX_XFER = 16384 // conservative per-bulkTransfer size for hubs
    }
}
