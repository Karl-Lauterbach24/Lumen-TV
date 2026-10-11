package io.github.lumen.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Reads a real Blu-ray image (UDF 2.50 with a metadata partition) and compares every file with the
 * checksums the Linux kernel's UDF driver gave for the same image:
 *   UDF_TEST_IMAGE=<iso> UDF_TEST_SUMS=<sha256sum list> ./gradlew :app:testDebugUnitTest
 */
class UdfTest {
    private val image = System.getenv("UDF_TEST_IMAGE")
    private val sums = System.getenv("UDF_TEST_SUMS")

    @Test
    fun everyFileMatchesTheKernel() {
        assumeTrue(image != null && sums != null && File(image).exists())
        val udf = Udf(FileBlockSource(image!!))
        var files = 0
        for (line in File(sums!!).readLines()) {
            if (line.isBlank()) continue
            val hash = line.substring(0, 64)
            val path = line.substring(66).removePrefix("./")
            val reader = udf.open(path)
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(77777) // not a multiple of the sector size, on purpose
            var pos = 0L
            while (true) {
                val n = reader.read(pos, buf, 0, buf.size)
                if (n < 0) break
                md.update(buf, 0, n)
                pos += n
            }
            assertEquals("size of $path", reader.size, pos)
            assertEquals("sha256 of $path", hash, md.digest().joinToString("") { "%02x".format(it) })
            files++
        }
        assertTrue(files > 5)
    }

    @Test
    fun listsFoldersAndFindsTheStreams() {
        assumeTrue(image != null && File(image).exists())
        val udf = Udf(FileBlockSource(image!!))
        val root = udf.list("").map { it.name }
        assertTrue(root.toString(), root.any { it.equals("BDMV", true) })
        val streams = udf.list("BDMV/STREAM").filter { !it.dir }.map { it.name }
        assertTrue(streams.toString(), streams.any { it.endsWith(".m2ts") })
        assertTrue(udf.label.isNotEmpty())
    }
}
