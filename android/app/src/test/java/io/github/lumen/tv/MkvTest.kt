package io.github.lumen.tv

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** MKV_TEST_IMAGE=<iso> MKV_TEST_CLIP=BDMV/STREAM/00000.m2ts MKV_TEST_OUT=<file.mkv> (the result is then checked with ffprobe/ffmpeg) */
class MkvTest {
    @Test
    fun ripsAClip() {
        val image = System.getenv("MKV_TEST_IMAGE")
        val out = System.getenv("MKV_TEST_OUT")
        assumeTrue(image != null && out != null && File(image).exists())
        val udf = Udf(FileBlockSource(image!!))
        val db = System.getenv("MKV_TEST_KEYDB")
        val readers = (System.getenv("MKV_TEST_CLIP") ?: "BDMV/STREAM/00000.m2ts").split(',').map { path ->
            val r: RandomReader = udf.open(path)
            if (db != null) AacsReader(r, Aacs.unlock(udf, listOf(File(db)))!!.keys) else r
        }
        val t0 = System.currentTimeMillis()
        val res = MkvRipper(readers, 192).rip(File(out!!), "test", { _, _ -> }, { false })
        println("ripped in ${System.currentTimeMillis() - t0} ms: ${res.tracks} ${res.warnings} ${res.seconds}")
        assertTrue(res.tracks.isNotEmpty())
        assertTrue(File(out).length() > 1_000_000)
    }
}
