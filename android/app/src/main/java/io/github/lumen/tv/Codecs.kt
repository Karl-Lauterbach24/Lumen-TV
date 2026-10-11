package io.github.lumen.tv

/** Bits of a parameter set (emulation prevention bytes removed). */
class Bits(private val b: ByteArray, private var pos: Int = 0) {
    var bit = 0
    private var eof = false
    fun u(n: Int): Int {
        var v = 0
        for (i in 0 until n) v = (v shl 1) or one()
        return v
    }
    fun one(): Int {
        val byte = pos + (bit shr 3)
        if (byte >= b.size) { eof = true; return 0 }
        val v = (b[byte].toInt() shr (7 - (bit and 7))) and 1
        bit++
        return v
    }
    fun ue(): Int {
        var zeros = 0
        while (one() == 0 && !eof && zeros < 32) zeros++
        if (zeros == 0) return 0
        return ((1L shl zeros) - 1 + u(zeros).toLong()).toInt()
    }
    fun se(): Int { val k = ue(); return if (k and 1 == 1) (k + 1) / 2 else -(k / 2) }
    fun skip(n: Int) { bit += n }
}

object Nal {
    /** Removes the 0x03 of every 00 00 03. */
    fun unescape(src: ByteArray, off: Int, len: Int): ByteArray {
        val out = ByteArray(len)
        var n = 0
        var zeros = 0
        for (i in off until off + len) {
            val c = src[i].toInt() and 0xff
            if (zeros >= 2 && c == 3) { zeros = 0; continue }
            out[n++] = src[i]
            zeros = if (c == 0) zeros + 1 else 0
        }
        return out.copyOf(n)
    }

    /** The NAL units of an Annex B stream as (offset, length) of each unit without its start code. */
    fun split(a: ByteArray, off: Int, len: Int): List<IntArray> {
        val out = ArrayList<IntArray>()
        val end = off + len
        var i = off
        var cur = -1
        while (i + 3 <= end) {
            if (a[i].toInt() == 0 && a[i + 1].toInt() == 0 && a[i + 2].toInt() == 1) {
                if (cur >= 0) {
                    var e = i
                    while (e > cur && a[e - 1].toInt() == 0) e--
                    out.add(intArrayOf(cur, e - cur))
                }
                cur = i + 3
                i += 3
            } else i++
        }
        if (cur >= 0 && cur < end) out.add(intArrayOf(cur, end - cur))
        return out
    }
}

class VideoInfo(val width: Int, val height: Int, val frameDurationNs: Long)

object H264 {
    fun parseSps(nal: ByteArray, off: Int, len: Int): VideoInfo? {
        return try {
            val r = Bits(Nal.unescape(nal, off + 1, len - 1))
            val profile = r.u(8); r.u(8); r.u(8)
            r.ue()
            var chroma = 1
            if (profile in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
                chroma = r.ue()
                if (chroma == 3) r.one()
                r.ue(); r.ue(); r.one()
                if (r.one() == 1) {
                    for (i in 0 until (if (chroma != 3) 8 else 12)) {
                        if (r.one() == 1) {
                            val size = if (i < 6) 16 else 64
                            var last = 8; var next = 8
                            for (j in 0 until size) {
                                if (next != 0) { next = (last + r.se() + 256) % 256 }
                                last = if (next == 0) last else next
                            }
                        }
                    }
                }
            }
            r.ue()
            val poc = r.ue()
            if (poc == 0) r.ue()
            else if (poc == 1) { r.one(); r.se(); r.se(); val n = r.ue(); for (i in 0 until n) r.se() }
            r.ue(); r.one()
            val w = r.ue() + 1
            val h = r.ue() + 1
            val frameMbsOnly = r.one()
            if (frameMbsOnly == 0) r.one()
            r.one()
            var cl = 0; var cr = 0; var ct = 0; var cb = 0
            if (r.one() == 1) { cl = r.ue(); cr = r.ue(); ct = r.ue(); cb = r.ue() }
            val unitX = if (chroma == 0 || chroma == 3) 1 else 2
            val unitY = (if (chroma == 1) 2 else 1) * (2 - frameMbsOnly)
            val width = w * 16 - unitX * (cl + cr)
            val height = (2 - frameMbsOnly) * h * 16 - unitY * (ct + cb)
            var frame = 0L
            if (r.one() == 1) {                                     // VUI
                if (r.one() == 1) { if (r.u(8) == 255) { r.u(16); r.u(16) } }
                if (r.one() == 1) r.one()
                if (r.one() == 1) { r.u(3); r.one(); if (r.one() == 1) r.u(24) }
                if (r.one() == 1) { r.ue(); r.ue() }
                if (r.one() == 1) {
                    val units = r.u(32).toLong() and 0xffffffffL
                    val scale = r.u(32).toLong() and 0xffffffffL
                    if (units > 0 && scale > 0) frame = 2 * units * 1_000_000_000L / scale
                }
            }
            if (width in 16..8192 && height in 16..8192) VideoInfo(width, height, frame) else null
        } catch (_: Exception) { null }
    }

    /** The slice of the access unit says it can be started from (IDR, recovery point, or an intra slice). */
    fun isKey(a: ByteArray, off: Int, len: Int): Boolean {
        for (u in Nal.split(a, off, len)) {
            val t = a[u[0]].toInt() and 0x1f
            if (t == 5) return true
            if (t == 6 && u[1] > 2 && (a[u[0] + 1].toInt() and 0xff) == 6) return true       // SEI recovery point
            if (t == 1 && u[1] > 2) {
                val r = Bits(Nal.unescape(a, u[0] + 1, minOf(u[1] - 1, 8)))
                r.ue()
                val st = r.ue() % 5
                return st == 2 || st == 4
            }
        }
        return false
    }

    fun avcC(sps: ByteArray, pps: List<ByteArray>): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        o.write(1); o.write(sps[1].toInt()); o.write(sps[2].toInt()); o.write(sps[3].toInt()); o.write(0xff)
        o.write(0xe1); o.write(sps.size shr 8); o.write(sps.size and 0xff); o.write(sps)
        o.write(pps.size)
        for (p in pps) { o.write(p.size shr 8); o.write(p.size and 0xff); o.write(p) }
        return o.toByteArray()
    }
}

object Hevc {
    class Sps(val info: VideoInfo, val ptl: ByteArray, val chroma: Int, val depthLuma: Int, val depthChroma: Int)

    fun parseSps(nal: ByteArray, off: Int, len: Int): Sps? {
        return try {
            val rb = Nal.unescape(nal, off + 2, len - 2)
            val r = Bits(rb)
            r.u(4)
            val sub = r.u(3)
            r.one()
            val ptl = ByteArray(12)
            for (i in 0 until 12) ptl[i] = r.u(8).toByte()
            val profilePresent = BooleanArray(8); val levelPresent = BooleanArray(8)
            for (i in 0 until sub) { profilePresent[i] = r.one() == 1; levelPresent[i] = r.one() == 1 }
            if (sub > 0) for (i in sub until 8) r.u(2)
            for (i in 0 until sub) { if (profilePresent[i]) r.skip(88); if (levelPresent[i]) r.skip(8) }
            r.ue()
            val chroma = r.ue()
            if (chroma == 3) r.one()
            val w = r.ue(); val h = r.ue()
            var cl = 0; var cr = 0; var ct = 0; var cb = 0
            if (r.one() == 1) { cl = r.ue(); cr = r.ue(); ct = r.ue(); cb = r.ue() }
            val dl = r.ue() + 8
            val dc = r.ue() + 8
            val unitX = if (chroma == 1 || chroma == 2) 2 else 1
            val unitY = if (chroma == 1) 2 else 1
            VideoInfo(w - unitX * (cl + cr), h - unitY * (ct + cb), 0).let { Sps(it, ptl, chroma, dl, dc) }
        } catch (_: Exception) { null }
    }

    fun isKey(a: ByteArray, off: Int, len: Int): Boolean {
        for (u in Nal.split(a, off, len)) {
            val t = (a[u[0]].toInt() shr 1) and 0x3f
            if (t in 16..21) return true
            if (t < 16) return false
        }
        return false
    }

    fun hvcC(sps: Sps, vps: List<ByteArray>, spsList: List<ByteArray>, pps: List<ByteArray>): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        o.write(1)
        o.write(sps.ptl, 0, 12)                                       // profile/tier, compatibility, constraints, level
        o.write(0xf0); o.write(0)                                     // min spatial segmentation
        o.write(0xfc)
        o.write(0xfc or sps.chroma)
        o.write(0xf8 or (sps.depthLuma - 8))
        o.write(0xf8 or (sps.depthChroma - 8))
        o.write(0); o.write(0)                                        // average frame rate
        o.write((1 shl 3) or 3)                                       // one temporal layer, 4-byte lengths
        val arrays = listOf(32 to vps, 33 to spsList, 34 to pps).filter { it.second.isNotEmpty() }
        o.write(arrays.size)
        for ((type, list) in arrays) {
            o.write(0x80 or type)
            o.write(list.size shr 8); o.write(list.size and 0xff)
            for (n in list) { o.write(n.size shr 8); o.write(n.size and 0xff); o.write(n) }
        }
        return o.toByteArray()
    }
}

object Mpeg2 {
    private val rates = longArrayOf(0, 1_001_000_000L / 24, 1_000_000_000L / 24, 1_000_000_000L / 25, 1_001_000_000L / 30,
        1_000_000_000L / 30, 1_000_000_000L / 50, 1_001_000_000L / 60, 1_000_000_000L / 60)

    fun parseSequence(a: ByteArray, off: Int, len: Int): VideoInfo? {
        var i = off
        while (i + 12 <= off + len) {
            if (a[i].toInt() == 0 && a[i + 1].toInt() == 0 && a[i + 2].toInt() == 1 && (a[i + 3].toInt() and 0xff) == 0xb3) {
                val w = ((a[i + 4].toInt() and 0xff) shl 4) or ((a[i + 5].toInt() and 0xff) shr 4)
                val h = ((a[i + 5].toInt() and 0x0f) shl 8) or (a[i + 6].toInt() and 0xff)
                val rate = a[i + 7].toInt() and 0x0f
                return VideoInfo(w, h, if (rate in 1..8) rates[rate] else 0)
            }
            i++
        }
        return null
    }

    fun isKey(a: ByteArray, off: Int, len: Int): Boolean {
        var i = off
        while (i + 6 <= off + len) {
            if (a[i].toInt() == 0 && a[i + 1].toInt() == 0 && a[i + 2].toInt() == 1 && a[i + 3].toInt() == 0) {
                return ((a[i + 5].toInt() shr 3) and 7) == 1
            }
            i++
        }
        return false
    }
}

/** What an audio frame header says; [bytes] is the size of the frame, [samples] its length. */
class AudioFrame(val bytes: Int, val samples: Int, val rate: Int, val channels: Int, val dependent: Boolean = false)

object AudioHeaders {
    private val ac3Rate = intArrayOf(48000, 44100, 32000)
    private val ac3Words = arrayOf(
        intArrayOf(64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 448, 512, 640, 768, 896, 1024, 1152, 1280),
        intArrayOf(69, 87, 104, 122, 139, 174, 208, 243, 278, 348, 417, 487, 557, 696, 835, 975, 1114, 1253, 1393),
        intArrayOf(96, 120, 144, 168, 192, 240, 288, 336, 384, 480, 576, 672, 768, 960, 1152, 1344, 1536, 1728, 1920))
    private val acmodChannels = intArrayOf(2, 1, 2, 3, 3, 4, 4, 5)

    /** AC-3 or E-AC-3 syncframe at [o]; null if there is none. */
    fun ac3(a: ByteArray, o: Int, end: Int): AudioFrame? {
        if (o + 8 > end || a[o].toInt() != 0x0b || a[o + 1].toInt() != 0x77) return null
        val bsid = (a[o + 5].toInt() and 0xff) shr 3
        if (bsid <= 10) {
            val fscod = (a[o + 4].toInt() and 0xff) shr 6
            val code = a[o + 4].toInt() and 0x3f
            if (fscod == 3 || code > 37) return null
            var words = ac3Words[fscod][code / 2]
            if (fscod == 1 && code and 1 == 1) words++
            val acmod = (a[o + 6].toInt() and 0xff) shr 5
            var p = 3                                                  // bits consumed after acmod
            val b = a[o + 6].toInt() and 0xff
            var lfeBit = 0
            // after acmod: cmixlev (2 bits if 3 front channels with centre), surmixlev (2 if surround), dsurmod (2 if stereo), lfeon
            var bits = 3
            if (acmod and 1 != 0 && acmod != 1) bits += 2
            if (acmod and 4 != 0) bits += 2
            if (acmod == 2) bits += 2
            val whole = (b shl 8) or (a[o + 7].toInt() and 0xff)
            lfeBit = (whole shr (15 - bits)) and 1
            return AudioFrame(words * 2, 1536, ac3Rate[fscod], acmodChannels[acmod] + lfeBit)
        }
        // E-AC-3
        val strmtyp = (a[o + 2].toInt() and 0xff) shr 6
        val frmsiz = (((a[o + 2].toInt() and 0x07) shl 8) or (a[o + 3].toInt() and 0xff))
        val fscod = (a[o + 4].toInt() and 0xff) shr 6
        var blocks = 6
        var rate = 48000
        if (fscod == 3) {
            val fscod2 = ((a[o + 4].toInt() and 0xff) shr 4) and 3
            rate = intArrayOf(24000, 22050, 16000, 0)[fscod2]
        } else {
            rate = ac3Rate[fscod]
            blocks = intArrayOf(1, 2, 3, 6)[((a[o + 4].toInt() and 0xff) shr 4) and 3]
        }
        val acmod = ((a[o + 4].toInt() and 0xff) shr 1) and 7
        val lfe = a[o + 4].toInt() and 1
        if (rate == 0) return null
        return AudioFrame((frmsiz + 1) * 2, blocks * 256, rate, acmodChannels[acmod] + lfe, strmtyp == 1)
    }

    private val dtsRate = intArrayOf(0, 8000, 16000, 32000, 0, 0, 11025, 22050, 44100, 0, 0, 12000, 24000, 48000, 0, 0)
    private val dtsChannels = intArrayOf(1, 2, 2, 2, 2, 3, 3, 4, 4, 5, 6, 6, 6, 7, 8, 8)

    /** DTS core frame header at [o] (size is the core's only). */
    fun dts(a: ByteArray, o: Int, end: Int): AudioFrame? {
        if (o + 12 > end || a[o].toInt() != 0x7f || a[o + 1].toInt() != 0xfe || (a[o + 2].toInt() and 0xff) != 0x80 || a[o + 3].toInt() != 0x01) return null
        val r = Bits(a, o + 4)
        r.one()                                                         // frame type
        r.u(5); r.one()
        val nblks = r.u(7)
        val fsize = r.u(14)
        val amode = r.u(6)
        val sfreq = r.u(4)
        r.u(5); r.u(5); r.u(3); r.u(1); r.u(1)
        val lff = r.u(2)
        val rate = dtsRate[sfreq]
        if (rate == 0) return null
        val ch = (if (amode < 16) dtsChannels[amode] else 2) + (if (lff != 0) 1 else 0)
        return AudioFrame(fsize + 1, (nblks + 1) * 32, rate, ch)
    }

    /** TrueHD access unit at [o]: its size in bytes, and, in a major sync, rate and channels. */
    fun truehd(a: ByteArray, o: Int, end: Int): AudioFrame? {
        if (o + 4 > end) return null
        val size = (((a[o].toInt() and 0x0f) shl 8) or (a[o + 1].toInt() and 0xff)) * 2
        if (size < 8) return null
        var rate = 0; var ch = 0
        if (o + 16 <= end && (a[o + 4].toInt() and 0xff) == 0xf8 && (a[o + 5].toInt() and 0xff) == 0x72 &&
            (a[o + 6].toInt() and 0xff) == 0x6f && (a[o + 7].toInt() and 0xff) == 0xba) {
            val f = ((a[o + 8].toInt() and 0xff) shl 24) or ((a[o + 9].toInt() and 0xff) shl 16) or
                ((a[o + 10].toInt() and 0xff) shl 8) or (a[o + 11].toInt() and 0xff)
            val code = f ushr 28
            rate = (if (code and 8 != 0) 44100 else 48000) shl (code and 7)
            val assign = f and 0x1fff                                    // 8-channel presentation
            val widths = intArrayOf(2, 1, 1, 2, 2, 2, 2, 1, 1, 2, 2, 1, 1)
            for (i in 0 until 13) if (assign shr i and 1 != 0) ch += widths[i]
        }
        val per = if (rate == 0) 0 else 40 shl (if (rate % 44100 == 0) (rate / 44100) else (rate / 48000)).countTrailingZeroBits()
        return AudioFrame(size, per, rate, ch)
    }
}
