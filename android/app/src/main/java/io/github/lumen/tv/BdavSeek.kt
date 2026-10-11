package io.github.lumen.tv

import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.TsExtractor

/**
 * The player's transport-stream reader cannot tell a Blu-ray title's length (the 3D and VBR streams
 * of discs defeat its search for time stamps) and then does not seek. The length is read here from
 * the stream's own clock (PCR) at its start and its end; seeking goes by proportion of the file.
 */
object BdavSeek {
    /** Duration in microseconds of the stream [r] (packets of [packet] bytes: 192 on a disc, 188 plain), or -1. */
    fun duration(r: RandomReader, packet: Int = 192): Long {
        val window = packet * 5461                    // about a megabyte
        val first = pcr(r, 0L, window, false, -1, packet) ?: return -1
        val tailStart = maxOf(0L, (r.size - window) / packet * packet)
        val last = pcr(r, tailStart, window, true, first.first, packet) ?: return -1
        var d = last.second - first.second
        if (d < 0) d += (1L shl 33) * 300
        return d / 27
    }

    /** (pid, pcr in 27 MHz ticks) of the first (or last) PCR in the window. */
    private fun pcr(r: RandomReader, start: Long, len: Int, last: Boolean, pid: Int, packet: Int): Pair<Int, Long>? {
        val b = ByteArray(len)
        val n = r.read(start, b, 0, len)
        if (n < packet) return null
        val h = packet - 188                           // the 4-byte time stamp of a disc's packets
        var found: Pair<Int, Long>? = null
        var o = 0
        while (o + packet <= n) {
            if (b[o + h] == 0x47.toByte()) {
                val p = ((b[o + h + 1].toInt() and 0x1f) shl 8) or (b[o + h + 2].toInt() and 0xff)
                val afc = (b[o + h + 3].toInt() shr 4) and 3
                if (afc >= 2 && (b[o + h + 4].toInt() and 0xff) >= 7 && (b[o + h + 5].toInt() and 0x10) != 0 && (pid < 0 || p == pid)) {
                    val base = ((b[o + h + 6].toLong() and 0xff) shl 25) or ((b[o + h + 7].toLong() and 0xff) shl 17) or
                        ((b[o + h + 8].toLong() and 0xff) shl 9) or ((b[o + h + 9].toLong() and 0xff) shl 1) or ((b[o + h + 10].toLong() and 0xff) shr 7)
                    val ext = ((b[o + h + 10].toLong() and 1) shl 8) or (b[o + h + 11].toLong() and 0xff)
                    found = p to (base * 300 + ext)
                    if (!last) return found
                }
            }
            o += packet
        }
        return found
    }
}

/** A transport-stream extractor whose seek map is the proportion of the file, for a known length. */
@UnstableApi
class ProportionalTsExtractor(private val durationUs: Long, private val size: Long) : Extractor {
    private val inner = TsExtractor()

    override fun sniff(input: ExtractorInput): Boolean = inner.sniff(input)

    override fun init(output: ExtractorOutput) {
        inner.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = output.track(id, type)
            override fun endTracks() { output.endTracks() }
            override fun seekMap(seekMap: SeekMap) {
                val duration = durationUs
                val total = size
                output.seekMap(object : SeekMap {
                    override fun isSeekable(): Boolean = true
                    override fun getDurationUs(): Long = duration
                    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                        val t = timeUs.coerceIn(0L, duration)
                        val pos = (t.toDouble() / duration * total).toLong() / 188 * 188
                        return SeekMap.SeekPoints(SeekPoint(t, pos))
                    }
                })
            }
        })
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = inner.read(input, seekPosition)
    override fun seek(position: Long, timeUs: Long) { inner.seek(position, timeUs) }
    override fun release() { inner.release() }
}

@UnstableApi
class ProportionalTsFactory(private val durationUs: Long, private val size: Long) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = arrayOf(ProportionalTsExtractor(durationUs, size))
}

/** A file in storage, read at a position. */
class LocalFileReader(private val file: java.io.File) : RandomReader {
    override val size: Long = file.length()
    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int =
        java.io.RandomAccessFile(file, "r").use { f -> f.seek(pos); f.read(buf, off, len) }
}
