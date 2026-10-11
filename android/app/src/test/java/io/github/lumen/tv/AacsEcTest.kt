package io.github.lumen.tv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Phase 1 writes vectors (AACS_EC_DIR), libgcrypt (libaacs's crypto.c) checks them, phase 3 reads its answers. */
class AacsEcTest {
    private val dir = System.getenv("AACS_EC_DIR")
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun selfConsistent() {
        val (priv, q) = AacsEc.hostKeyPair()
        val nonce = AacsEc.nonce(); val point = AacsEc.hostKeyPair().second
        val sig = AacsEc.sign(priv, nonce, point)
        assertTrue(AacsEc.verify(q, sig, nonce + point))
        val (d2, q2) = AacsEc.hostKeyPair()
        assertArrayEquals(AacsEc.busKey(priv, q2), AacsEc.busKey(d2, q))     // both sides get the same bus key
    }

    @Test
    fun writeVectors() {
        assumeTrue(dir != null && !File(dir, "answers.txt").exists())
        val (priv, q) = AacsEc.hostKeyPair()
        val (d2, q2) = AacsEc.hostKeyPair()
        val nonce = AacsEc.nonce(); val point = AacsEc.hostKeyPair().second
        val sig = AacsEc.sign(priv, nonce, point)
        File(dir, "vec.txt").writeText(listOf(priv, q, nonce, point, sig, q2, AacsEc.busKey(priv, q2)).joinToString("\n") { hex(it) })
    }

    @Test
    fun checkAnswers() {
        assumeTrue(dir != null && File(dir, "answers.txt").exists())
        val v = File(dir, "vec.txt").readLines().map { unhex(it) }
        val a = File(dir, "answers.txt").readLines()
        assertTrue("libgcrypt did not accept our signature", a[0] == "verify-ok")
        assertTrue("bus key differs from libgcrypt", a[1] == "buskey-ok")
        assertTrue("our verification rejects libgcrypt's signature", AacsEc.verify(v[1], unhex(a[2]), v[2] + v[3]))
    }
}
