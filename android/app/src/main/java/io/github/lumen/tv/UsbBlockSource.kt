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
) : BlockSource, Scsi {
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
                // (some bridges name another subclass; bulk-only is what this speaks)
                if (f.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE && f.interfaceProtocol == 0x50 && f.endpointCount >= 2)
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
            // a UAS bridge sits in its other alternate setting while the system's driver has it: select the bulk-only one
            try { connection.setInterface(iface) } catch (_: Exception) { }
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
    /** Sends [data] to the drive with [cdb] (SEND KEY and the like); returns the CSW status (0 = good). */
    @Synchronized override fun commandOut(cdb: ByteArray, data: ByteArray, timeout: Int): Int {
        val t = tag++
        cbw.fill(0)
        cbw[0] = 'U'.code.toByte(); cbw[1] = 'S'.code.toByte(); cbw[2] = 'B'.code.toByte(); cbw[3] = 'C'.code.toByte()
        putLe(cbw, 4, t); putLe(cbw, 8, data.size)
        cbw[12] = 0
        cbw[14] = cdb.size.toByte()
        System.arraycopy(cdb, 0, cbw, 15, cdb.size)
        if (connection.bulkTransfer(bulkOut, cbw, 31, 5000) != 31) { reset(); throw IOException("command not sent") }
        var sent = 0
        while (sent < data.size) {
            val n = connection.bulkTransfer(bulkOut, data, sent, minOf(CHUNK, data.size - sent), timeout)
            if (n <= 0) { clearHalt(bulkOut); break }
            sent += n
        }
        var n = connection.bulkTransfer(bulkIn, csw, 13, 5000)
        if (n != 13) { clearHalt(bulkIn); n = connection.bulkTransfer(bulkIn, csw, 13, 5000) }
        if (n != 13 || csw[0] != 'U'.code.toByte() || getLe(csw, 4) != t) { reset(); throw IOException("bad status from the drive") }
        val st = csw[12].toInt() and 0xff
        lastSense = if (st != 0) "status $st, " + senseText() else ""
        return st
    }

    /** Reads [length] bytes with [cdb] (REPORT KEY, READ DISC STRUCTURE …); null if the drive says no. */
    @Volatile override var lastSense: String = ""
        private set

    private fun senseText(): String {
        val (k, asc, q) = sense()
        return "sense %X/%02X/%02X".format(k, asc, q)
    }

    override fun commandIn(cdb: ByteArray, length: Int, timeout: Int): ByteArray? {
        val b = ByteArray(length)
        lastSense = ""
        return try {
            val st = command(cdb, b, 0, length, timeout)
            if (st == 0) b else { lastSense = "status $st, " + senseText(); null }
        } catch (e: IOException) { lastSense = "transfer error: ${e.message}"; null }
    }

    @Synchronized private fun command(cdb: ByteArray, data: ByteArray?, offset: Int, length: Int, timeout: Int = 20000): Int {
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
        var cswRead = false
        if (length > 0 && data != null) {
            val mps = maxOf(64, bulkIn.maxPacketSize)
            val small = ByteArray(maxOf(mps, 512))
            while (got < length) {
                val want = minOf(CHUNK, length - got)
                if (want >= mps) {
                    val n = connection.bulkTransfer(bulkIn, data, offset + got, want, timeout)
                    if (n < 0) { clearHalt(bulkIn); break }
                    if (n == 0) break
                    got += n
                    if (n < want && n % mps != 0) break              // a short packet ends the data phase
                } else {
                    // a short transfer: read a whole packet's worth, so a drive that sends its status instead of data
                    // (a command that moves nothing, or fails) does not overflow the buffer and get lost
                    val n = connection.bulkTransfer(bulkIn, small, mps, timeout)
                    if (n < 0) { clearHalt(bulkIn); break }
                    if (n == 0) break
                    if (n == 13 && small[0] == 'U'.code.toByte() && small[1] == 'S'.code.toByte() && small[2] == 'B'.code.toByte() &&
                        small[3] == 'S'.code.toByte() && getLe(small, 4) == t) {
                        System.arraycopy(small, 0, csw, 0, 13); cswRead = true
                        break
                    }
                    val c = minOf(n, want)
                    System.arraycopy(small, 0, data, offset + got, c)
                    got += c
                    break
                }
            }
        }
        var n = 13
        if (!cswRead) {
            n = connection.bulkTransfer(bulkIn, csw, 13, 5000)
            if (n != 13) { clearHalt(bulkIn); n = connection.bulkTransfer(bulkIn, csw, 13, 5000) }
        }
        if (n != 13 || csw[0] != 'U'.code.toByte() || csw[1] != 'S'.code.toByte() || getLe(csw, 4) != t) { reset(); throw IOException("bad status from the drive") }
        val status = csw[12].toInt() and 0xff
        if (status == 0 && got < length && !(cswRead && got == 0 && length <= 36)) throw IOException("short read ($got of $length)")
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

    /** (AACS active, bus encryption supported) from the drive's AACS feature (GET CONFIGURATION 0x010d), or null. */
    fun aacsFeature(): Pair<Boolean, Boolean>? {
        val b = ByteArray(16)
        val c = cdb(0x46, 0x01, 0x01, 0x0d, 0, 0, 0, 0, 16, 0)
        return try {
            if (command(c, b, 0, 16, 5000) != 0) return null
            if ((((b[8].toInt() and 0xff) shl 8) or (b[9].toInt() and 0xff)) != 0x010d) return null
            Pair((b[2 + 8].toInt() and 1) != 0, (b[4 + 8].toInt() and 2) != 0)
        } catch (_: IOException) { null }
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
