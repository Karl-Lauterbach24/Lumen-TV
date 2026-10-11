package io.github.lumen.tv

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * The cryptography of the AACS drive authentication (as specified for AACS 1.0 and implemented by libaacs):
 * the 160 bit elliptic curve of the specification, ECDSA with SHA-1, the key agreement that gives the bus
 * key, and the one-block CMAC used for the drive's answers.
 */
object AacsEc {
    private val p = BigInteger("9DC9D81355ECCEB560BDB09EF9EAE7C479A7D7DF", 16)
    private val a = BigInteger("9DC9D81355ECCEB560BDB09EF9EAE7C479A7D7DC", 16)
    private val b = BigInteger("402DAD3EC1CBCD165248D68E1245E0C4DAACB1D8", 16)
    val n = BigInteger("9DC9D81355ECCEB560BDC44F54817B2C7F5AB017", 16)
    private val gx = BigInteger("2E64FC22578351E6F4CCA7EB81D0A4BDC54CCEC6", 16)
    private val gy = BigInteger("0914A25DD05442889DB455C7F23C9A0707F5CBB9", 16)
    private val rnd = SecureRandom()

    class Point(val x: BigInteger, val y: BigInteger)   // null stands for the point at infinity

    val G = Point(gx, gy)

    private fun add(p1: Point?, p2: Point?): Point? {
        if (p1 == null) return p2
        if (p2 == null) return p1
        val l: BigInteger
        if (p1.x == p2.x) {
            if (p1.y.add(p2.y).mod(p) == BigInteger.ZERO) return null
            l = p1.x.pow(2).multiply(BigInteger.valueOf(3)).add(a).multiply(p1.y.shiftLeft(1).modInverse(p)).mod(p)
        } else {
            l = p2.y.subtract(p1.y).multiply(p2.x.subtract(p1.x).mod(p).modInverse(p)).mod(p)
        }
        val x = l.pow(2).subtract(p1.x).subtract(p2.x).mod(p)
        val y = l.multiply(p1.x.subtract(x)).subtract(p1.y).mod(p)
        return Point(x, y)
    }

    fun mul(k: BigInteger, pt: Point): Point? {
        var r: Point? = null
        var q: Point? = pt
        var e = k
        while (e.signum() > 0) {
            if (e.testBit(0)) r = add(r, q)
            q = add(q, q)
            e = e.shiftRight(1)
        }
        return r
    }

    private fun fixed(v: BigInteger, len: Int): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(len)
        val src = if (raw.size > len) raw.copyOfRange(raw.size - len, raw.size) else raw
        System.arraycopy(src, 0, out, len - src.size, src.size)
        return out
    }
    private fun big(b: ByteArray, off: Int = 0, len: Int = 20) = BigInteger(1, b.copyOfRange(off, off + len))

    /** A random private key and its public point (40 bytes: x then y). */
    fun hostKeyPair(): Pair<ByteArray, ByteArray> {
        var d: BigInteger
        do { d = BigInteger(160, rnd) } while (d.signum() == 0 || d >= n)
        val q = mul(d, G)!!
        return fixed(d, 20) to (fixed(q.x, 20) + fixed(q.y, 20))
    }

    /** ECDSA over SHA-1(nonce || point) with the host certificate's private key: r || s, 40 bytes. */
    fun sign(privKey: ByteArray, nonce: ByteArray, point: ByteArray): ByteArray {
        val h = MessageDigest.getInstance("SHA-1").digest(nonce + point)
        val e = BigInteger(1, h)
        val d = big(privKey)
        while (true) {
            var k: BigInteger
            do { k = BigInteger(160, rnd) } while (k.signum() == 0 || k >= n)
            val r = mul(k, G)!!.x.mod(n)
            if (r.signum() == 0) continue
            val s = k.modInverse(n).multiply(e.add(d.multiply(r))).mod(n)
            if (s.signum() == 0) continue
            return fixed(r, 20) + fixed(s, 20)
        }
    }

    /** ECDSA check; [q] is the signer's public point (40 bytes). Used to test [sign]. */
    fun verify(q: ByteArray, signature: ByteArray, data: ByteArray): Boolean {
        val r = big(signature, 0); val s = big(signature, 20)
        if (r.signum() <= 0 || r >= n || s.signum() <= 0 || s >= n) return false
        val e = BigInteger(1, MessageDigest.getInstance("SHA-1").digest(data))
        val w = s.modInverse(n)
        val u1 = e.multiply(w).mod(n); val u2 = r.multiply(w).mod(n)
        val pub = Point(big(q, 0), big(q, 20))
        val pt = add(mul(u1, G), mul(u2, pub)) ?: return false
        return pt.x.mod(n) == r
    }

    /** The bus key: the lowest 128 bits of the x coordinate of (private key × the drive's key point). */
    fun busKey(hostPriv: ByteArray, driveKeyPoint: ByteArray): ByteArray {
        val pt = mul(big(hostPriv), Point(big(driveKeyPoint, 0), big(driveKeyPoint, 20)))!!
        val x = fixed(pt.x, 20)
        return x.copyOfRange(4, 20)
    }

    fun nonce(): ByteArray = ByteArray(20).also { rnd.nextBytes(it) }

    fun aesDecryptBlock(key: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/NoPadding").run { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES")); doFinal(data) }
    fun aesEncryptBlock(key: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/NoPadding").run { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")); doFinal(data) }

    /** CMAC of exactly one 16-byte block, as the drive computes it over the volume ID and similar. */
    fun cmac16(data: ByteArray, key: ByteArray): ByteArray {
        val l = aesEncryptBlock(key, ByteArray(16))
        fun shl(x: ByteArray): ByteArray {
            val o = ByteArray(16); var carry = 0
            for (i in 15 downTo 0) { o[i] = ((x[i].toInt() shl 1) or carry).toByte(); carry = (x[i].toInt() ushr 7) and 1 }
            return o
        }
        val k1 = shl(l)
        if (l[0].toInt() and 0x80 != 0) k1[15] = (k1[15].toInt() xor 0x87).toByte()
        val x = ByteArray(16) { (data[it].toInt() xor k1[it].toInt()).toByte() }
        return aesEncryptBlock(key, x)
    }
}
