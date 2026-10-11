package io.github.lumen.tv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The drive authentication against a stand-in drive that does the drive's side of the protocol. */
class AacsDriveTest {
    private val iv = byteArrayOf(0x0b, 0xa0.toByte(), 0xf8.toByte(), 0xdd.toByte(), 0xfe.toByte(), 0xa6.toByte(), 0x1f, 0xb3.toByte(),
        0xd8.toByte(), 0xdf.toByte(), 0x9f.toByte(), 0x56, 0x6a, 0x05, 0x0f, 0x78)

    private class FakeDrive(val hostCertPub: ByteArray, val refuseCert: Boolean = false) : Scsi {
        val driveKey = AacsEc.hostKeyPair()
        val driveCert = ByteArray(92).also { it[1] = 1; System.arraycopy(driveKey.second, 0, it, 12, 40); it[0] = 1 }
        val dn = AacsEc.nonce()
        var hn = ByteArray(0)
        var busKey: ByteArray? = null
        val readDataKey = ByteArray(16) { (it * 7 + 3).toByte() }
        var signed = false

        override fun commandIn(cdb: ByteArray, length: Int, timeout: Int): ByteArray? {
            val format = cdb[10].toInt() and 0x3f
            val out = ByteArray(length)
            when (cdb[0].toInt() and 0xff) {
                0xa4 -> when (format) {
                    0x3f -> return out
                    0 -> { out[7] = 0; return out }
                    1 -> { System.arraycopy(dn, 0, out, 4, 20); System.arraycopy(driveCert, 0, out, 24, 92); return out }
                    2 -> {
                        System.arraycopy(driveKey.second, 0, out, 4, 40)
                        System.arraycopy(AacsEc.sign(driveKey.first, hn, driveKey.second), 0, out, 44, 40)
                        return out
                    }
                }
                0xad -> if ((cdb[7].toInt() and 0xff) == 0x84 && signed) {
                    System.arraycopy(AacsEc.aesEncryptBlock(busKey!!, readDataKey), 0, out, 4, 16)
                    return out
                }
            }
            return null
        }

        var hostPub = ByteArray(0)
        override fun commandOut(cdb: ByteArray, data: ByteArray, timeout: Int): Int {
            val format = cdb[10].toInt() and 0x3f
            if (format == 1) {
                if (refuseCert) return 1
                hn = data.copyOfRange(4, 24)
                hostPub = data.copyOfRange(24 + 12, 24 + 52)
                return 0
            }
            if (format == 2) {
                val hkp = data.copyOfRange(4, 44); val sig = data.copyOfRange(44, 84)
                if (!AacsEc.verify(hostPub, sig, dn + hkp)) return 1
                busKey = AacsEc.busKey(driveKey.first, hkp)
                signed = true
                return 0
            }
            return 1
        }
    }

    private fun hostCert(): HostCert {
        val (priv, q) = AacsEc.hostKeyPair()
        val cert = ByteArray(92).also { it[1] = 1; System.arraycopy(q, 0, it, 12, 40) }
        return HostCert(priv, cert)
    }

    @Test
    fun getsTheReadDataKey() {
        val c = hostCert()
        val drive = FakeDrive(c.cert)
        val r = AacsDrive.readDataKey(drive, listOf(c))
        assertNotNull(r.message, r.readDataKey)
        assertArrayEquals(drive.readDataKey, r.readDataKey)
    }

    @Test
    fun revokedCertificateIsReportedAndNextOneTried() {
        val bad = hostCert(); val good = hostCert()
        // the first certificate is refused, the second accepted
        val drive = object : Scsi {
            val inner = FakeDrive(good.cert)
            var first = true
            override fun commandIn(cdb: ByteArray, length: Int, timeout: Int) = inner.commandIn(cdb, length, timeout)
            override fun commandOut(cdb: ByteArray, data: ByteArray, timeout: Int): Int {
                if ((cdb[10].toInt() and 0x3f) == 1 && first) { first = false; return 1 }
                return inner.commandOut(cdb, data, timeout)
            }
        }
        val r = AacsDrive.readDataKey(drive, listOf(bad, good))
        assertNotNull(r.message, r.readDataKey)
        val refused = AacsDrive.readDataKey(FakeDrive(bad.cert, refuseCert = true), listOf(bad))
        assertNull(refused.readDataKey)
        assertEquals(true, refused.message.contains("revoked"))
    }

    @Test
    fun busDecryptionThenContentDecryption() {
        val image = System.getenv("AACS_TEST_IMAGE"); val keydb = System.getenv("AACS_TEST_KEYDB")
        assumeTrue(image != null && keydb != null && File(image).exists())
        val udf = Udf(FileBlockSource(image!!))
        val keys = Aacs.unlock(udf, listOf(File(keydb!!)))!!.keys
        val raw = udf.open("BDMV/STREAM/00000.m2ts")
        val n = 6144 * 8
        val plainRef = ByteArray(n)
        AacsReader(raw, keys).read(0, plainRef, 0, n)
        val enc = ByteArray(n); raw.read(0, enc, 0, n)
        // what a bus-encrypting drive hands out: every sector of an encrypted unit scrambled with the read data key
        val rdk = ByteArray(16) { (it * 5 + 1).toByte() }
        var u = 0
        while (u < n) {
            if (enc[u].toInt() and 0xc0 != 0) {
                var s = 0
                while (s < 6144) {
                    val c = Cipher.getInstance("AES/CBC/NoPadding")
                    c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(rdk, "AES"), IvParameterSpec(iv))
                    c.doFinal(enc, u + s + 16, 2032, enc, u + s + 16)
                    s += 2048
                }
            }
            u += 6144
        }
        val src = object : RandomReader {
            override val size = n.toLong()
            override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
                if (pos >= n) return -1
                val c = minOf(len.toLong(), n - pos).toInt(); System.arraycopy(enc, pos.toInt(), buf, off, c); return c
            }
        }
        val out = ByteArray(n)
        val rd = AacsReader(src, keys, rdk)
        var pos = 0
        while (pos < n) { val k = rd.read(pos.toLong(), out, pos, n - pos); if (k <= 0) break; pos += k }
        assertEquals(0, rd.failedUnits)
        assertArrayEquals(plainRef, out)
    }
}
