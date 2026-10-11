package io.github.lumen.tv

import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AACS, the copy protection of Blu-ray: the part that needs only keys that are on the net in the
 * disc community's key database (KEYDB.cfg, the same file libaacs reads on the desktop): the disc is
 * recognised by the hash of its Unit_Key_RO.inf, the key database gives the volume unique key (or the
 * unit keys themselves), and the streams are decrypted in aligned units of 6144 bytes.
 *
 * What this cannot do: discs whose key is not in the database, BD+ (a program on the disc that
 * alters the stream), AACS 2 (Ultra HD discs), and anything that needs the drive's own
 * authentication. Such discs are reported as what they are.
 */
object Aacs {
    private val IV = byteArrayOf(0x0b, 0xa0.toByte(), 0xf8.toByte(), 0xdd.toByte(), 0xfe.toByte(), 0xa6.toByte(), 0x1f, 0xb3.toByte(),
        0xd8.toByte(), 0xdf.toByte(), 0x9f.toByte(), 0x56, 0x6a, 0x05, 0x0f, 0x78)

    class Result(val keys: List<ByteArray>, val discId: String, val title: String)

    /** The disc's identity for the key database (SHA-1 of AACS/Unit_Key_RO.inf), or null. */
    fun discId(udf: Udf): String? {
        val inf = try { readAll(udf.open("AACS/Unit_Key_RO.inf")) } catch (_: Exception) {
            try { readAll(udf.open("AACS/DUPLICATE/Unit_Key_RO.inf")) } catch (_: Exception) { return null }
        }
        return MessageDigest.getInstance("SHA-1").digest(inf).joinToString("") { "%02X".format(it) }
    }

    /** The unit keys of the disc in [udf], from the first key database in [dbs] that knows it; null if none. */
    fun unlock(udf: Udf, dbs: List<File>): Result? {
        val inf = try { readAll(udf.open("AACS/Unit_Key_RO.inf")) } catch (_: Exception) {
            try { readAll(udf.open("AACS/DUPLICATE/Unit_Key_RO.inf")) } catch (_: Exception) { return null }
        }
        if (inf.size < 100) return null
        val id = MessageDigest.getInstance("SHA-1").digest(inf).joinToString("") { "%02X".format(it) }
        for (db in dbs) {
            val entry = lookup(db, id) ?: continue
            // unit keys given outright …
            if (entry.unitKeys.isNotEmpty()) return Result(entry.unitKeys, id, entry.title)
            // … or derived from the volume unique key
            val vuk = entry.vuk ?: continue
            val ukPos = be32(inf, 0)
            if (ukPos + 2 > inf.size) continue
            val count = be16(inf, ukPos)
            val keys = ArrayList<ByteArray>()
            for (i in 0 until count) {
                val at = ukPos + 48 * (i + 1)
                if (at + 16 > inf.size) break
                keys.add(aes(Cipher.DECRYPT_MODE, vuk, "AES/ECB/NoPadding", null).doFinal(inf, at, 16))
            }
            if (keys.isNotEmpty()) return Result(keys, id, entry.title)
        }
        return null
    }

    private class Entry(val title: String, val vuk: ByteArray?, val unitKeys: List<ByteArray>)

    /** One line of a KEYDB.cfg: `0x<disc id> = title | D | date | M | 0x.. | I | 0x.. | V | 0x<vuk> | U | 1-0x<key> …` */
    private fun lookup(db: File, id: String): Entry? {
        if (!db.isFile) return null
        val prefix = "0x$id"
        db.bufferedReader(Charsets.ISO_8859_1).useLines { lines ->
            for (line in lines) {
                if (!line.startsWith(prefix, ignoreCase = true)) continue
                val body = line.substringBefore(';')
                val title = body.substringAfter('=', "").substringBefore('|').trim()
                val parts = body.split('|').map { it.trim() }
                var vuk: ByteArray? = null
                val keys = ArrayList<ByteArray>()
                var i = 0
                while (i < parts.size) {
                    when (parts[i]) {
                        "V" -> if (i + 1 < parts.size) vuk = hex(parts[i + 1].removePrefix("0x"))
                        "U" -> {
                            var j = i + 1
                            while (j < parts.size && Regex("\\d+-0x[0-9A-Fa-f]{32}").matches(parts[j])) {
                                hex(parts[j].substringAfter("0x"))?.let { keys.add(it) }
                                j++
                            }
                        }
                    }
                    i++
                }
                if (vuk != null || keys.isNotEmpty()) return Entry(title, vuk, keys)
            }
        }
        return null
    }

    private fun hex(s: String): ByteArray? {
        if (s.length != 32) return null
        return try { ByteArray(16) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() } } catch (_: Exception) { null }
    }
    private fun be16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)
    private fun be32(b: ByteArray, o: Int) = (be16(b, o) shl 16) or be16(b, o + 2)
    private fun aes(mode: Int, key: ByteArray, transformation: String, iv: ByteArray?): Cipher =
        Cipher.getInstance(transformation).apply { init(mode, SecretKeySpec(key, "AES"), if (iv != null) IvParameterSpec(iv) else null) }

    private fun readAll(r: RandomReader): ByteArray {
        val out = ByteArray(r.size.toInt())
        var pos = 0
        while (pos < out.size) {
            val n = r.read(pos.toLong(), out, pos, out.size - pos)
            if (n <= 0) break
            pos += n
        }
        return out
    }

    /** Decrypts one aligned unit in place; false if no key makes it a transport stream. */
    class Unit(private val keys: List<ByteArray>, private val busKey: ByteArray? = null) {
        private var current = 0
        private val ecb = Cipher.getInstance("AES/ECB/NoPadding")
        private val cbc = Cipher.getInstance("AES/CBC/NoPadding")
        private val seed = ByteArray(16)
        private val out = ByteArray(6144)

        fun decrypt(buf: ByteArray, off: Int): Boolean {
            if (buf[off].toInt() and 0xc0 == 0) return true          // copy permission indicator 0: not encrypted
            if (busKey != null) {
                // the drive scrambled every sector of the unit with the read data key (bytes 16.. of each 2048)
                val bus = Cipher.getInstance("AES/CBC/NoPadding")
                var s = 0
                while (s < 6144) {
                    bus.init(Cipher.DECRYPT_MODE, SecretKeySpec(busKey, "AES"), IvParameterSpec(IV))
                    bus.doFinal(buf, off + s + 16, 2032, buf, off + s + 16)
                    s += 2048
                }
            }
            for (n in keys.indices) {
                val k = (current + n) % keys.size
                System.arraycopy(buf, off, seed, 0, 16)
                ecb.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys[k], "AES"))
                val bk = ecb.doFinal(seed)
                for (i in 0 until 16) bk[i] = (bk[i].toInt() xor seed[i].toInt()).toByte()
                cbc.init(Cipher.DECRYPT_MODE, SecretKeySpec(bk, "AES"), IvParameterSpec(IV))
                System.arraycopy(buf, off, out, 0, 16)
                cbc.doFinal(buf, off + 16, 6128, out, 16)
                var ok = true
                var i = 0
                while (i < 6144) { if (out[i + 4] != 0x47.toByte()) { ok = false; break }; i += 192 }
                if (ok) {
                    i = 0
                    while (i < 6144) { out[i] = (out[i].toInt() and 0x3f).toByte(); i += 192 }
                    System.arraycopy(out, 0, buf, off, 6144)
                    current = k
                    return true
                }
            }
            return false
        }
    }
}

/** The plain stream of an AACS-encrypted file: aligned units decrypted as they are read. */
class AacsReader(private val inner: RandomReader, keys: List<ByteArray>, busKey: ByteArray? = null) : RandomReader {
    override val size: Long get() = inner.size
    private val unit = Aacs.Unit(keys, busKey)
    private val chunk = ByteArray(6144 * 64)
    private var chunkAt = -1L
    private var chunkLen = 0
    var failedUnits = 0
        private set

    @Synchronized
    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos >= size) return -1
        var done = 0
        var want = minOf(len.toLong(), size - pos).toInt()
        while (want > 0) {
            val p = pos + done
            if (chunkAt < 0 || p < chunkAt || p >= chunkAt + chunkLen) {
                val start = p / 6144 * 6144
                val n = inner.read(start, chunk, 0, chunk.size)
                if (n <= 0) break
                // a short read ends at a unit boundary or at the end of the file
                var got = n
                while (got < chunk.size && start + got < size) {
                    val m = inner.read(start + got, chunk, got, chunk.size - got)
                    if (m <= 0) break
                    got += m
                }
                var u = 0
                while (u + 6144 <= got) { if (!unit.decrypt(chunk, u)) failedUnits++; u += 6144 }
                chunkAt = start; chunkLen = got
            }
            val inChunk = (p - chunkAt).toInt()
            val c = minOf(want, chunkLen - inChunk)
            if (c <= 0) break
            System.arraycopy(chunk, inChunk, buf, off + done, c)
            done += c; want -= c
        }
        return if (done == 0) -1 else done
    }
}
