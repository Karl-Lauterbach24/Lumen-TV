package io.github.lumen.tv

import java.io.File

/** What the authentication needs of a drive connection (a USB drive, or a stand-in in the tests). */
interface Scsi {
    /** why the last command failed (sense data), for messages */
    val lastSense: String get() = ""
    fun commandIn(cdb: ByteArray, length: Int, timeout: Int = 10000): ByteArray?
    fun commandOut(cdb: ByteArray, data: ByteArray, timeout: Int = 10000): Int
}

/** A host certificate of the key database: `| HC | HOST_PRIV_KEY 0x… | HOST_CERT 0x… |`. */
class HostCert(val privKey: ByteArray, val cert: ByteArray) {
    val id: String get() = cert.copyOfRange(4, 10).joinToString("") { "%02X".format(it) }
    /** the certificate says it can be used with a drive that encrypts the bus */
    val busCapable: Boolean get() = cert[1].toInt() and 1 != 0
}

/**
 * The AACS drive authentication of libaacs (mmc.c): the host proves itself with a certificate, both sides
 * agree on a bus key, and the drive hands out the key with which it scrambles the sectors of a disc that
 * asks for bus encryption.
 */
object AacsDrive {
    class Outcome(val readDataKey: ByteArray?, val message: String)

    /** The host certificates of the key database files, in file order. */
    fun hostCerts(dbs: List<File>): List<HostCert> {
        val out = ArrayList<HostCert>()
        for (db in dbs) {
            if (!db.isFile) continue
            try {
                db.bufferedReader(Charsets.ISO_8859_1).useLines { lines ->
                    for (line in lines) {
                        if (!line.startsWith("| HC")) continue
                        val priv = Regex("HOST_PRIV_KEY\\s+0x([0-9A-Fa-f]{40})").find(line)?.groupValues?.get(1) ?: continue
                        val cert = Regex("HOST_CERT\\s+0x([0-9A-Fa-f]{184})").find(line)?.groupValues?.get(1) ?: continue
                        out.add(HostCert(unhex(priv), unhex(cert)))
                    }
                }
            } catch (_: Exception) { }
        }
        return out
    }

    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun cdb12(vararg b: Int) = ByteArray(12).also { for (i in b.indices) it[i] = b[i].toByte() }

    private fun reportKey(usb: Scsi, agid: Int, format: Int, len: Int): ByteArray? =
        usb.commandIn(cdb12(0xa4, 0, 0, 0, 0, 0, 0, 0x02, len shr 8, len and 0xff, (agid shl 6) or (format and 0x3f), 0), len)

    private fun sendKey(usb: Scsi, agid: Int, format: Int, buf: ByteArray): Boolean = try {
        usb.commandOut(cdb12(0xa3, 0, 0, 0, 0, 0, 0, 0x02, buf.size shr 8, buf.size and 0xff, (agid shl 6) or (format and 0x3f), 0), buf) == 0
    } catch (_: java.io.IOException) { false }

    private fun invalidate(usb: Scsi, agid: Int) { reportKey(usb, agid, 0x3f, 2) }

    /** Tries the certificates one after the other until one is accepted; returns the drive's read data key. */
    fun readDataKey(usb: Scsi, certs: List<HostCert>, log: (String) -> Unit = {}): Outcome {
        if (certs.isEmpty()) return Outcome(null, "the key database has no host certificate (HC line)")
        var last = "no certificate accepted"
        for (c in certs) {
            log("host certificate ${c.id}")
            val r = authenticate(usb, c, log)
            if (r.readDataKey != null) return r
            last = r.message
            if (last.startsWith("step 1/6") || last.startsWith("step 3/6") || last.startsWith("step 4/6") ||
                last.startsWith("step 6/6")) break
        }
        return Outcome(null, last)
    }

    fun authenticate(usb: Scsi, c: HostCert, log: (String) -> Unit = {}): Outcome {
        var agidBuf = reportKey(usb, 0, 0, 8)
        if (agidBuf == null) {                                // maybe a session is left open: close them all and ask again
            for (a in 0 until 4) invalidate(usb, a)
            agidBuf = reportKey(usb, 0, 0, 8)
        }
        if (agidBuf == null) return Outcome(null, "step 1/6 AGID: the drive gave none (${usb.lastSense})")
        val agid = (agidBuf[7].toInt() and 0xff) ushr 6
        try {
            val hn = AacsEc.nonce()
            val (hk, hkp) = AacsEc.hostKeyPair()
            // host certificate and nonce
            val out = ByteArray(116)
            out[1] = 0x72
            System.arraycopy(hn, 0, out, 4, 20); System.arraycopy(c.cert, 0, out, 24, 92)
            if (!sendKey(usb, agid, 0x01, out)) return Outcome(null, "step 2/6: the drive refused the host certificate ${c.id} (revoked or not accepted; ${usb.lastSense})")
            // drive certificate and nonce
            val ch = reportKey(usb, agid, 0x01, 116) ?: return Outcome(null, "step 3/6: the drive gave no certificate (${usb.lastSense})")
            val dn = ch.copyOfRange(4, 24)
            val driveCert = ch.copyOfRange(24, 116)
            if (driveCert[0].toInt() == 0x11) return Outcome(null, "the drive uses an AACS 2.0 certificate")
            // drive key point and signature
            val dk = reportKey(usb, agid, 0x02, 84) ?: return Outcome(null, "step 4/6: the drive gave no key (${usb.lastSense})")
            val dkp = dk.copyOfRange(4, 44)
            val dks = dk.copyOfRange(44, 84)
            // (the drive certificate itself and its signature are not checked here: a wrong one gives a wrong key, nothing else)
            if (!AacsEc.verify(driveCert.copyOfRange(12, 52), dks, hn + dkp)) return Outcome(null, "step 4/6: the drive's signature is not valid")
            // our signature over the drive nonce and our key point
            val hks = AacsEc.sign(c.privKey, dn, hkp)
            val sendHk = ByteArray(84)
            sendHk[1] = 0x52
            System.arraycopy(hkp, 0, sendHk, 4, 40); System.arraycopy(hks, 0, sendHk, 44, 40)
            if (!sendKey(usb, agid, 0x02, sendHk)) return Outcome(null, "step 5/6: the drive did not accept the host key (${usb.lastSense})")
            val busKey = AacsEc.busKey(hk, dkp)
            // read data keys: READ DISC STRUCTURE, format 0x84
            val keys = usb.commandIn(cdb12(0xad, 0x01, 0, 0, 0, 0, 0, 0x84, 0, 36, agid shl 6, 0), 36)
                ?: return Outcome(null, "step 6/6: the drive did not give the data keys (${usb.lastSense})")
            val rdk = AacsEc.aesDecryptBlock(busKey, keys.copyOfRange(4, 20))
            return Outcome(rdk, "ok (certificate ${c.id}, drive certificate ${driveCert.copyOfRange(4, 10).joinToString("") { "%02X".format(it) }}, bus encryption ${if (driveCert[1].toInt() and 1 != 0) "capable" else "not capable"})")
        } catch (e: Exception) {
            return Outcome(null, "authentication failed: $e")
        } finally {
            invalidate(usb, agid)
        }
    }
}
