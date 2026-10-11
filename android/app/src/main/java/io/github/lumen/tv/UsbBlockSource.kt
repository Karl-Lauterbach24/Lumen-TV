package io.github.lumen.tv

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.IOException

/**
 * An optical drive on USB, spoken to directly: the USB mass-storage protocol (bulk-only transport)
 * with SCSI commands inside. Most television kernels have no driver for optical drives, so the system
 * never shows one as a volume; this reads sectors itself and [Udf] makes files of them.
 */
class UsbBlockSource private constructor(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
) : BlockSource {
    override var sectors: Long = 0
        private set
    var vendor = ""
        private set
    var product = ""
        private set
    private var tag = 1
    private val cbw = ByteArray(31)
    private val csw = ByteArray(13)

    companion object {
        const val CHUNK = 16384          // what one bulk transfer may carry on Android
        const val PER_COMMAND = 64       // sectors per READ(10): 128 KiB

        /** The bulk-only mass-storage interface of [device] if it has one that looks like an optical drive. */
        fun find(device: UsbDevice): UsbInterface? {
            for (i in 0 until device.interfaceCount) {
                val f = device.getInterface(i)
                // class 8 = mass storage; subclass 2 (MMC-5, SFF-8020i) or 6 (transparent SCSI); protocol 0x50 = bulk-only
                if (f.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE && (f.interfaceSubclass == 2 || f.interfaceSubclass == 6) && f.interfaceProtocol == 0x50)
                    return f
            }
            return null
        }

        fun open(connection: UsbDeviceConnection, iface: UsbInterface): UsbBlockSource? {
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
            }
            if (inEp == null || outEp == null) return null
            if (!connection.claimInterface(iface, true)) return null
            return UsbBlockSource(connection, iface, inEp, outEp)
        }
    }

    // ---- bulk-only transport ----
    private fun clearHalt(ep: UsbEndpoint) {
        connection.controlTransfer(0x02, 0x01, 0, ep.address, null, 0, 1000) // CLEAR_FEATURE(ENDPOINT_HALT)
    }

    private fun reset() {
        connection.controlTransfer(0x21, 0xff, 0, iface.id, null, 0, 1000)  // bulk-only mass storage reset
        clearHalt(bulkIn); clearHalt(bulkOut)
    }

    /** Sends one command; data (if any) is received into [data]. Returns the CSW status (0 = good). */
    private fun command(cdb: ByteArray, data: ByteArray?, offset: Int, length: Int, timeout: Int = 20000): Int {
        val t = tag++
        cbw.fill(0)
        cbw[0] = 'U'.code.toByte(); cbw[1] = 'S'.code.toByte(); cbw[2] = 'B'.code.toByte(); cbw[3] = 'C'.code.toByte()
        putLe(cbw, 4, t); putLe(cbw, 8, length)
        cbw[12] = if (length > 0) 0x80.toByte() else 0
        cbw[13] = 0
        cbw[14] = cdb.size.toByte()
        System.arraycopy(cdb, 0, cbw, 15, cdb.size)
        if (connection.bulkTransfer(bulkOut, cbw, 31, 5000) != 31) { reset(); throw IOException("command not sent") }
        var got = 0
        if (length > 0 && data != null) {
            while (got < length) {
                val n = connection.bulkTransfer(bulkIn, data, offset + got, minOf(CHUNK, length - got), timeout)
                if (n < 0) { clearHalt(bulkIn); break }
                if (n == 0) break
                got += n
            }
        }
        var n = connection.bulkTransfer(bulkIn, csw, 13, 5000)
        if (n != 13) { clearHalt(bulkIn); n = connection.bulkTransfer(bulkIn, csw, 13, 5000) }
        if (n != 13 || csw[0] != 'U'.code.toByte() || csw[1] != 'S'.code.toByte() || getLe(csw, 4) != t) { reset(); throw IOException("bad status from the drive") }
        val status = csw[12].toInt() and 0xff
        if (status == 0 && got < length) throw IOException("short read ($got of $length)")
        return status
    }

    private fun putLe(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte(); b[o + 2] = (v shr 16).toByte(); b[o + 3] = (v shr 24).toByte() }
    private fun getLe(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
    private fun putBe32(b: ByteArray, o: Int, v: Long) { b[o] = (v shr 24).toByte(); b[o + 1] = (v shr 16).toByte(); b[o + 2] = (v shr 8).toByte(); b[o + 3] = v.toByte() }
    private fun getBe32(b: ByteArray, o: Int) = ((b[o].toLong() and 0xff) shl 24) or ((b[o + 1].toLong() and 0xff) shl 16) or ((b[o + 2].toLong() and 0xff) shl 8) or (b[o + 3].toLong() and 0xff)

    // ---- SCSI ----
    private fun cdb(vararg bytes: Int): ByteArray = ByteArray(bytes.size) { bytes[it].toByte() }

    /** (sense key, ASC, ASCQ) of the last failure. */
    private fun sense(): Triple<Int, Int, Int> {
        val b = ByteArray(18)
        return try {
            command(cdb(0x03, 0, 0, 0, 18, 0), b, 0, 18, 5000)
            Triple(b[2].toInt() and 0x0f, b[12].toInt() and 0xff, b[13].toInt() and 0xff)
        } catch (_: IOException) { Triple(0, 0, 0) }
    }

    /** Asks who it is; true if it is an optical drive (peripheral type 5). */
    fun inquire(): Boolean {
        val b = ByteArray(36)
        if (command(cdb(0x12, 0, 0, 0, 36, 0), b, 0, 36, 5000) != 0) return false
        vendor = String(b, 8, 8, Charsets.US_ASCII).trim()
        product = String(b, 16, 16, Charsets.US_ASCII).trim()
        return (b[0].toInt() and 0x1f) == 5
    }

    /** Waits for a disc: spins it up, until [timeoutMs]. Returns false for an empty tray. */
    fun waitReady(timeoutMs: Long = 25000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        var started = false
        while (System.currentTimeMillis() < end) {
            val st = try { command(cdb(0x00, 0, 0, 0, 0, 0), null, 0, 0, 5000) } catch (_: IOException) { 1 }
            if (st == 0) return true
            val (key, asc, ascq) = sense()
            when {
                asc == 0x3A -> return false                                   // medium not present
                asc == 0x04 && !started -> { started = true; try { command(cdb(0x1B, 0, 0, 0, 1, 0), null, 0, 0, 20000) } catch (_: IOException) { } }
                key == 6 -> { /* unit attention: the disc changed – ask again */ }
            }
            Thread.sleep(500)
        }
        return false
    }

    /** True if the disc was taken out or changed since the last look (TEST UNIT READY + sense). */
    fun mediaChanged(): Boolean {
        val st = try { command(cdb(0x00, 0, 0, 0, 0, 0), null, 0, 0, 5000) } catch (_: IOException) { 1 }
        if (st == 0) return false
        val (key, asc, _) = sense()
        return asc == 0x3A || key == 6 || key == 2
    }

    fun readCapacity(): Boolean {
        val b = ByteArray(8)
        if (command(cdb(0x25, 0, 0, 0, 0, 0, 0, 0, 0, 0), b, 0, 8, 10000) != 0) return false
        sectors = getBe32(b, 0) + 1
        val size = getBe32(b, 4)
        return size == 2048L && sectors > 1
    }

    override fun read(lba: Long, count: Int, out: ByteArray, offset: Int) {
        var done = 0
        while (done < count) {
            val n = minOf(PER_COMMAND, count - done)
            val c = ByteArray(10)
            c[0] = 0x28
            putBe32(c, 2, lba + done)
            c[7] = (n shr 8).toByte(); c[8] = n.toByte()
            var tries = 0
            while (true) {
                val st = command(c, out, offset + done * 2048, n * 2048)
                if (st == 0) break
                val (key, asc, _) = sense()
                if (++tries >= 3 || key == 3 && asc == 0x64) throw IOException("sector ${lba + done} cannot be read (sense $key/$asc)")
            }
            done += n
        }
    }

    override fun close() {
        try { connection.releaseInterface(iface) } catch (_: Exception) { }
        try { connection.close() } catch (_: Exception) { }
    }
}
