package io.github.lumen.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * A synthetic AACS disc (encrypted with a random volume key, decrypted independently by libaacs):
 *   AACS_TEST_IMAGE=<img> AACS_TEST_KEYDB=<KEYDB.cfg> AACS_TEST_SHA256=<hash of the plain 00000.m2ts>
 */
class AacsTest {
    private val image = System.getenv("AACS_TEST_IMAGE")
    private val keydb = System.getenv("AACS_TEST_KEYDB")
    private val want = System.getenv("AACS_TEST_SHA256")

    @Test
    fun decryptsLikeLibaacs() {
        assumeTrue(image != null && keydb != null && want != null && File(image).exists())
        val udf = Udf(FileBlockSource(image!!))
        val keys = Aacs.unlock(udf, listOf(File(keydb!!)))
        assertNotNull("key database has no entry", keys)
        val reader = AacsReader(udf.open("BDMV/STREAM/00000.m2ts"), keys!!.keys)
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(100003)
        var pos = 0L
        while (true) {
            val n = reader.read(pos, buf, 0, buf.size)
            if (n < 0) break
            md.update(buf, 0, n); pos += n
        }
        assertEquals(0, reader.failedUnits)
        assertEquals(want, md.digest().joinToString("") { "%02x".format(it) })
    }

    @Test
    fun unknownDiscIsNotUnlocked() {
        assumeTrue(image != null && File(image).exists())
        val empty = File.createTempFile("keydb", ".cfg").apply { writeText("; nothing\n"); deleteOnExit() }
        assertEquals(null, Aacs.unlock(Udf(FileBlockSource(image!!)), listOf(empty)))
    }
}
