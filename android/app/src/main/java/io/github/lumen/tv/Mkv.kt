package io.github.lumen.tv

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/** Writes a Matroska file: the tracks are given up front, blocks come in order of the source. */
class MkvWriter(file: File, private val title: String) {
    class Track(val number: Int, val type: Int, val codec: String, val lang: String, val priv: ByteArray?,
                val width: Int = 0, val height: Int = 0, val frameNs: Long = 0,
                val rate: Int = 0, val channels: Int = 0, val bits: Int = 0)

    private val f = RandomAccessFile(file, "rw")
    private var segment = 0L              // file offset of the first byte inside the Segment
    private var durationAt = 0L
    private var seekCuesAt = 0L
    private val cues = ArrayList<LongArray>()   // time ms, track, cluster position
    private var cluster = ByteArrayOutputStream()
    private var clusterTs = -1L
    private var clusterHasKey = false
    private var videoTrack = -1
    private var lastMs = 0L

    init { f.setLength(0) }

    // ---- EBML pieces ----
    private fun id(v: Int): ByteArray {
        var n = 4
        while (n > 1 && (v ushr ((n - 1) * 8)) == 0) n--
        return ByteArray(n) { (v ushr ((n - 1 - it) * 8)).toByte() }
    }
    private fun size(v: Long): ByteArray {
        var n = 1
        while (n < 8 && v >= (1L shl (7 * n)) - 1) n++
        val out = ByteArray(n)
        for (i in 0 until n) out[i] = (v ushr ((n - 1 - i) * 8)).toByte()
        out[0] = (out[0].toInt() or (0x80 shr (n - 1))).toByte()
        return out
    }
    private fun el(i: Int, body: ByteArray): ByteArray = id(i) + size(body.size.toLong()) + body
    private fun uint(i: Int, v: Long): ByteArray {
        var n = 1
        while (n < 8 && (v ushr (n * 8)) != 0L) n++
        return el(i, ByteArray(n) { (v ushr ((n - 1 - it) * 8)).toByte() })
    }
    private fun str(i: Int, s: String) = el(i, s.toByteArray(Charsets.UTF_8))
    private fun f64(i: Int, v: Double): ByteArray {
        val b = java.lang.Double.doubleToRawLongBits(v)
        return el(i, ByteArray(8) { (b ushr ((7 - it) * 8)).toByte() })
    }
    private fun f32(i: Int, v: Float): ByteArray {
        val b = java.lang.Float.floatToRawIntBits(v)
        return el(i, ByteArray(4) { (b ushr ((3 - it) * 8)).toByte() })
    }
    private fun be8(v: Long) = ByteArray(8) { (v ushr ((7 - it) * 8)).toByte() }

    // ---- the file ----
    fun begin(tracks: List<Track>) {
        f.write(el(0x1A45DFA3, uint(0x4286, 1) + uint(0x42F7, 1) + uint(0x42F2, 4) + uint(0x42F3, 8) +
            str(0x4282, "matroska") + uint(0x4287, 4) + uint(0x4285, 2)))
        // Segment with a fixed-width size, patched at the end
        f.write(id(0x18538067)); f.write(byteArrayOf(0x01, 0, 0, 0, 0, 0, 0, 0))
        segment = f.filePointer
        // SeekHead: Info, Tracks, Cues at fixed positions
        val seekLen = seekEntry(0x1549A966, 0).size * 3
        val seekHeadLen = id(0x114D9B74).size + size(seekLen.toLong()).size + seekLen
        val infoPos = seekHeadLen.toLong()
        val info = ByteArrayOutputStream()
        info.write(uint(0x2AD7B1, 1_000_000))
        info.write(str(0x4D80, "Lumen TV"))
        info.write(str(0x5741, "Lumen TV"))
        if (title.isNotEmpty()) info.write(str(0x7BA9, title))
        val durationEl = f64(0x4489, 0.0)
        info.write(durationEl)
        val infoBody = info.toByteArray()
        val infoEl = el(0x1549A966, infoBody)
        // where the Duration payload sits inside Info
        val durationOffset = id(0x1549A966).size + size(infoBody.size.toLong()).size + infoBody.size - 8
        val tr = ByteArrayOutputStream()
        for (t in tracks) tr.write(trackEntry(t))
        val tracksEl = el(0x1654AE6B, tr.toByteArray())
        val tracksPos = infoPos + infoEl.size
        val seek = ByteArrayOutputStream()
        seek.write(seekEntry(0x1549A966, infoPos))
        seek.write(seekEntry(0x1654AE6B, tracksPos))
        seek.write(seekEntry(0x1C53BB6B, 0))
        val seekHead = el(0x114D9B74, seek.toByteArray())
        // the Cues entry is the last one: its 8-byte position ends the SeekHead
        seekCuesAt = f.filePointer + seekHead.size - 8
        f.write(seekHead)
        durationAt = f.filePointer + durationOffset
        f.write(infoEl)
        f.write(tracksEl)
        videoTrack = tracks.firstOrNull { it.type == 1 }?.number ?: -1
    }

    private fun seekEntry(target: Int, pos: Long): ByteArray {
        val tid = id(target)
        val body = el(0x53AB, tid) + id(0x53AC) + size(8) + be8(pos)
        return el(0x4DBB, body)
    }

    private fun trackEntry(t: Track): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(uint(0xD7, t.number.toLong()))
        o.write(uint(0x73C5, t.number.toLong()))
        o.write(uint(0x83, t.type.toLong()))
        o.write(uint(0x9C, 0))
        if (t.lang.isNotEmpty()) o.write(str(0x22B59C, t.lang))
        o.write(str(0x86, t.codec))
        if (t.priv != null) o.write(el(0x63A2, t.priv))
        if (t.frameNs > 0) o.write(uint(0x23E383, t.frameNs))
        if (t.type == 1) {
            o.write(el(0xE0, uint(0xB0, t.width.toLong()) + uint(0xBA, t.height.toLong()) +
                uint(0x54B0, t.width.toLong()) + uint(0x54BA, t.height.toLong())))
        } else if (t.type == 2) {
            val a = ByteArrayOutputStream()
            a.write(f32(0xB5, t.rate.toFloat()))
            a.write(uint(0x9F, maxOf(1, t.channels).toLong()))
            if (t.bits > 0) a.write(uint(0x6264, t.bits.toLong()))
            o.write(el(0xE1, a.toByteArray()))
        }
        return el(0xAE, o.toByteArray())
    }

    /** [ms] is the time on the file's clock; keyframes of the video track start clusters. */
    fun block(track: Int, ms: Long, key: Boolean, data: ByteArray, off: Int = 0, len: Int = data.size) {
        var t = ms
        if (t < 0) t = 0
        lastMs = maxOf(lastMs, t)
        val videoKey = key && track == videoTrack
        if (clusterTs < 0 || (videoKey && t - clusterTs >= 1000) || cluster.size() > (4 shl 20) ||
            t - clusterTs > 30000 || t < clusterTs - 30000) {
            flushCluster()
            clusterTs = t
            clusterHasKey = videoKey
        }
        val rel = (t - clusterTs).toInt()
        cluster.write(0xA3)
        val body = len + 4
        cluster.write(size(body.toLong()))
        cluster.write(0x80 or track)
        cluster.write(rel shr 8); cluster.write(rel and 0xff)
        cluster.write(if (key) 0x80 else 0)
        cluster.write(data, off, len)
    }

    private fun flushCluster() {
        if (clusterTs < 0 || cluster.size() == 0) { cluster.reset(); return }
        val body = uint(0xE7, clusterTs).let { it + cluster.toByteArray() }
        val pos = f.filePointer - segment
        f.write(id(0x1F43B675)); f.write(size(body.size.toLong())); f.write(body)
        if (clusterHasKey && videoTrack > 0) cues.add(longArrayOf(clusterTs, videoTrack.toLong(), pos))
        cluster.reset()
    }

    fun finish() {
        flushCluster()
        val cuesPos = f.filePointer - segment
        val c = ByteArrayOutputStream()
        for (q in cues) {
            c.write(el(0xBB, uint(0xB3, q[0]) + el(0xB7, uint(0xF7, q[1]) + uint(0xF1, q[2]))))
        }
        if (cues.isNotEmpty()) f.write(el(0x1C53BB6B, c.toByteArray()))
        val end = f.filePointer
        f.seek(seekCuesAt); f.write(be8(if (cues.isNotEmpty()) cuesPos else 0))
        f.seek(durationAt); f.write(be8(java.lang.Double.doubleToRawLongBits(lastMs.toDouble())))
        f.seek(segment - 8); f.write(byteArrayOf(0x01)); f.write(be8(end - segment).copyOfRange(1, 8))
        f.close()
    }

    fun abort() { try { f.close() } catch (_: Exception) { } }
}

/** What came out of a rip. */
class RipResult(val tracks: List<String>, val warnings: List<String>, val seconds: Double)

/**
 * Turns a Blu-ray clip (plain or decrypted transport stream) into a Matroska file, the way MakeMKV
 * does: the streams are kept as they are (no re-encoding), only put into another container.
 * Video: H.264, H.265, MPEG-2, VC-1. Audio: AC-3, E-AC-3, DTS (with its HD extension), TrueHD
 * (with its AC-3 core as a second track), LPCM. Subtitles: PGS.
 */
class MkvRipper(private val readers: List<RandomReader>, private val packet: Int = 192) {
    constructor(reader: RandomReader, packet: Int = 192) : this(listOf(reader), packet)
    private val reader: RandomReader get() = readers[0]
    private var offsetMs = 0L

    companion object {
        /** The clip has a video stream a player can use by itself (the second view of a 3D disc has not). */
        fun hasPlayableVideo(r: RandomReader, packet: Int = 192): Boolean {
            val d = TsDemux(r, packet)
            d.run(4L shl 20) { _, _, _, _, _, _ -> false }
            return d.streams.values.any { it.type == 0x1b || it.type == 0x24 || it.type == 0x02 || it.type == 0xea }
        }
    }

    private class Stream(val es: TsDemux.Es) {
        var track = 0
        var core = 0                 // TrueHD: the track of the AC-3 core, 0 if none
        var sps: ByteArray? = null
        val spsList = ArrayList<ByteArray>()
        val pps = ArrayList<ByteArray>()
        val vps = ArrayList<ByteArray>()
        var info: VideoInfo? = null
        var hevc: Hevc.Sps? = null
        var seqHeader: ByteArray? = null
        var audio: AudioFrame? = null
        var coreAudio: AudioFrame? = null
        var pcm: IntArray? = null    // rate, channels, bits
        var firstPts = -1L
        var nextPts = -1L            // audio: where the next frame starts, 90 kHz ticks
        var nextCorePts = -1L
        var tail = Buf(4096)         // an audio frame that continues in the next PES
    }

    private val streams = LinkedHashMap<Int, Stream>()
    private val warnings = ArrayList<String>()
    private var base = 0L

    private fun kind(type: Int): String? = when (type) {
        0x1b -> "h264"; 0x24 -> "hevc"; 0x02 -> "mpeg2"; 0xea -> "vc1"
        0x81, 0xa1 -> "ac3"; 0x84 -> "eac3"; 0x82, 0x85, 0x86, 0xa2 -> "dts"; 0x83 -> "truehd"; 0x80 -> "lpcm"
        0x90 -> "pgs"
        else -> null
    }

    fun rip(out: File, title: String, progress: (Long, Long) -> Unit, cancelled: () -> Boolean): RipResult {
        // ---- look at the beginning: tables, parameter sets, audio headers ----
        val probe = TsDemux(reader, packet)
        var pesSeen = 0
        probe.run(48L shl 20, cancelled) { pid, pts, dts, a, off, len ->
            val es = probe.streams[pid] ?: return@run true
            val s = streams.getOrPut(pid) { Stream(es) }
            if (s.firstPts < 0 && pts >= 0) s.firstPts = if (dts >= 0) minOf(pts, dts) else pts
            look(s, a, off, len)
            pesSeen++
            true
        }
        if (cancelled()) throw java.io.InterruptedIOException("stopped")
        for (k in streams.keys.toList()) if (kind(streams[k]!!.es.type) == null) streams.remove(k)
        if (streams.isEmpty()) throw java.io.IOException("no streams of a known kind in the title")
        val tracks = ArrayList<MkvWriter.Track>()
        var number = 0
        val names = ArrayList<String>()
        // the video first, then audio and subtitles in the order of the table
        val ordered = streams.values.sortedBy { if (kind(it.es.type)!!.let { k -> k in listOf("h264", "hevc", "mpeg2", "vc1") }) 0 else 1 }
        for (s in ordered) {
            val k = kind(s.es.type)!!
            val lang = s.es.lang.ifEmpty { "und" }
            when (k) {
                "h264" -> {
                    val sps = s.sps ?: continue
                    val v = s.info ?: continue
                    s.track = ++number
                    tracks.add(MkvWriter.Track(s.track, 1, "V_MPEG4/ISO/AVC", "", H264.avcC(sps, s.pps), v.width, v.height, v.frameDurationNs))
                    names.add("video H.264 ${v.width}x${v.height}")
                }
                "hevc" -> {
                    val h = s.hevc ?: continue
                    s.track = ++number
                    tracks.add(MkvWriter.Track(s.track, 1, "V_MPEGH/ISO/HEVC", "", Hevc.hvcC(h, s.vps, s.spsList, s.pps), h.info.width, h.info.height))
                    names.add("video H.265 ${h.info.width}x${h.info.height}")
                }
                "mpeg2" -> {
                    val v = s.info ?: continue
                    s.track = ++number
                    tracks.add(MkvWriter.Track(s.track, 1, "V_MPEG2", "", null, v.width, v.height, v.frameDurationNs))
                    names.add("video MPEG-2 ${v.width}x${v.height}")
                }
                "vc1" -> {
                    val v = s.info ?: continue
                    val seq = s.seqHeader ?: continue
                    s.track = ++number
                    val bih = ByteArray(40 + seq.size)
                    fun le32(o: Int, x: Int) { for (i in 0 until 4) bih[o + i] = (x ushr (8 * i)).toByte() }
                    le32(0, 40 + seq.size); le32(4, v.width); le32(8, v.height); bih[12] = 1; bih[14] = 24
                    bih[16] = 'W'.code.toByte(); bih[17] = 'V'.code.toByte(); bih[18] = 'C'.code.toByte(); bih[19] = '1'.code.toByte()
                    System.arraycopy(seq, 0, bih, 40, seq.size)
                    tracks.add(MkvWriter.Track(s.track, 1, "V_MS/VFW/FOURCC", "", bih, v.width, v.height, v.frameDurationNs))
                    names.add("video VC-1 ${v.width}x${v.height}")
                }
                "ac3", "eac3" -> {
                    val a = s.audio ?: continue
                    s.track = ++number
                    tracks.add(MkvWriter.Track(s.track, 2, if (k == "ac3") "A_AC3" else "A_EAC3", lang, null, rate = a.rate, channels = a.channels))
                    names.add("audio ${if (k == "ac3") "AC-3" else "E-AC-3"} ${a.channels}ch $lang")
                }
                "dts" -> {
                    val a = s.audio ?: continue
                    s.track = ++number
                    tracks.add(MkvWriter.Track(s.track, 2, "A_DTS", lang, null, rate = a.rate, channels = a.channels))
                    names.add("audio DTS ${a.channels}ch $lang")
                }
                "truehd" -> {
                    val a = s.audio
                    if (a != null) {
                        s.track = ++number
                        tracks.add(MkvWriter.Track(s.track, 2, "A_TRUEHD", lang, null, rate = a.rate, channels = a.channels))
                        names.add("audio TrueHD ${a.channels}ch $lang")
                    }
                    val c = s.coreAudio
                    if (c != null) {
                        s.core = ++number
                        tracks.add(MkvWriter.Track(s.core, 2, "A_AC3", lang, null, rate = c.rate, channels = c.channels))
                        names.add("audio AC-3 core ${c.channels}ch $lang")
                    }
                }
                "lpcm" -> {
                    val p = s.pcm ?: continue
                    s.track = ++number
                    tracks.add(MkvWriter.Track(s.track, 2, "A_PCM/INT/BIG", lang, null, rate = p[0], channels = p[1], bits = p[2]))
                    names.add("audio PCM ${p[1]}ch ${p[2]} bit $lang")
                }
                "pgs" -> {
                    s.track = ++number
                    tracks.add(MkvWriter.Track(s.track, 17, "S_HDMV/PGS", lang, null))
                    names.add("subtitles PGS $lang")
                }
            }
        }
        val usable = streams.values.filter { it.track > 0 || it.core > 0 }
        if (usable.none { kind(it.es.type)!! in listOf("h264", "hevc", "mpeg2", "vc1") })
            throw java.io.IOException("no video stream could be read from the title")
        // the clock starts at the earliest stream
        base = usable.filter { it.firstPts >= 0 }.minOfOrNull { it.firstPts } ?: 0L

        // ---- the whole stream ----
        val w = MkvWriter(out, title)
        try {
            w.begin(tracks)
            val total = readers.sumOf { it.size }
            var before = 0L
            var lastReport = 0L
            for ((n, clip) in readers.withIndex()) {
                if (n > 0) {
                    // the next clip carries on where the last one ended: its own clock starts at its first time stamp
                    val first = TsDemux(clip, packet)
                    var min = Long.MAX_VALUE
                    first.run(8L shl 20, cancelled) { _, pts, dts, _, _, _ ->
                        val t = if (dts >= 0) minOf(pts, dts) else pts
                        if (t in 0 until min) min = t
                        true
                    }
                    if (min != Long.MAX_VALUE) base = min
                    for (s in streams.values) { s.tail.clear(); s.nextPts = -1; s.nextCorePts = -1 }
                }
                val demux = TsDemux(clip, packet)
                demux.run(Long.MAX_VALUE, cancelled) { pid, pts, dts, a, off, len ->
                    val s = streams[pid]
                    if (s != null && (s.track > 0 || s.core > 0)) emit(w, s, pts, dts, a, off, len)
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 1000) { lastReport = now; progress(before + demux.bytesRead, total) }
                    true
                }
                before += clip.size
                val d = try { BdavSeek.duration(clip, packet) } catch (_: Exception) { -1L }
                if (d > 0) offsetMs += d / 1000
            }
            if (cancelled()) throw java.io.InterruptedIOException("stopped")
            w.finish()
        } catch (e: Exception) {
            w.abort()
            out.delete()
            throw e
        }
        progress(1, 1)
        return RipResult(names, warnings, offsetMs / 1000.0)
    }

    /** Collects what the probe needs from one PES payload. */
    private fun look(s: Stream, a: ByteArray, off: Int, len: Int) {
        when (kind(s.es.type)) {
            "h264" -> for (u in Nal.split(a, off, len)) {
                val t = a[u[0]].toInt() and 0x1f
                if (t == 7 && s.sps == null) { s.sps = a.copyOfRange(u[0], u[0] + u[1]); s.info = H264.parseSps(a, u[0], u[1]) }
                else if (t == 8 && s.pps.size < 4 && s.pps.none { it.contentEquals(a.copyOfRange(u[0], u[0] + u[1])) }) s.pps.add(a.copyOfRange(u[0], u[0] + u[1]))
            }
            "hevc" -> for (u in Nal.split(a, off, len)) {
                val t = (a[u[0]].toInt() shr 1) and 0x3f
                val n = a.copyOfRange(u[0], u[0] + u[1])
                if (t == 32 && s.vps.isEmpty()) s.vps.add(n)
                if (t == 33 && s.spsList.isEmpty()) { s.spsList.add(n); s.hevc = Hevc.parseSps(a, u[0], u[1]) }
                if (t == 34 && s.pps.isEmpty()) s.pps.add(n)
            }
            "mpeg2" -> if (s.info == null) s.info = Mpeg2.parseSequence(a, off, len)
            "vc1" -> if (s.seqHeader == null) {
                var i = off
                while (i + 4 <= off + len) {
                    if (a[i].toInt() == 0 && a[i + 1].toInt() == 0 && a[i + 2].toInt() == 1 && (a[i + 3].toInt() and 0xff) == 0x0f) {
                        var e = i + 4
                        while (e + 3 <= off + len && !(a[e].toInt() == 0 && a[e + 1].toInt() == 0 && a[e + 2].toInt() == 1)) e++
                        s.seqHeader = a.copyOfRange(i, minOf(e, off + len))
                        // advanced profile sequence header: profile(2) level(3) chroma(3) frmrtq(3) bitrtq(5) postproc(1), then max_coded_width (12), height (12)
                        val r = Bits(s.seqHeader!!, 4)
                        r.u(2); r.u(3); r.u(3); r.u(3); r.u(5); r.u(1)
                        val cw = r.u(12); val ch = r.u(12)
                        s.info = VideoInfo((cw + 1) * 2, (ch + 1) * 2, 0)
                        break
                    }
                    i++
                }
            }
            "ac3", "eac3" -> if (s.audio == null) s.audio = AudioHeaders.ac3(a, off, off + len)
            "dts" -> if (s.audio == null) {
                var i = off
                while (i + 12 < off + len && s.audio == null) { s.audio = AudioHeaders.dts(a, i, off + len); i++ }
            }
            "truehd" -> {
                var o = off
                while (o + 8 <= off + len) {
                    if (a[o].toInt() == 0x0b && a[o + 1].toInt() == 0x77) {
                        val f = AudioHeaders.ac3(a, o, off + len) ?: break
                        if (s.coreAudio == null) s.coreAudio = f
                        o += f.bytes
                    } else {
                        val f = AudioHeaders.truehd(a, o, off + len) ?: break
                        if (f.rate > 0 && s.audio == null) s.audio = f
                        o += f.bytes
                    }
                }
            }
            "lpcm" -> if (s.pcm == null && len >= 4) {
                val b2 = a[off + 2].toInt() and 0xff
                val b3 = a[off + 3].toInt() and 0xff
                val channels = intArrayOf(0, 1, 0, 2, 3, 3, 4, 4, 5, 6, 7, 8, 0, 0, 0, 0)[b2 shr 4]
                val rate = intArrayOf(0, 48000, 0, 0, 96000, 192000, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)[b2 and 0x0f]
                val bits = intArrayOf(0, 16, 20, 24)[b3 shr 6]
                if (channels > 0 && rate > 0 && bits > 0) s.pcm = intArrayOf(rate, channels, if (bits == 20) 24 else bits)
            }
        }
    }

    private fun ms(pts: Long): Long = Math.floorDiv((pts - base) * 2 + 90, 180) + offsetMs

    private fun emit(w: MkvWriter, s: Stream, pts: Long, dts: Long, a: ByteArray, off: Int, len: Int) {
        val k = kind(s.es.type)
        when (k) {
            "h264", "hevc" -> {
                if (pts < 0) return
                val key = if (k == "h264") H264.isKey(a, off, len) else Hevc.isKey(a, off, len)
                val units = Nal.split(a, off, len)
                var total = 0
                val keep = ArrayList<IntArray>()
                for (u in units) {
                    val t = if (k == "h264") a[u[0]].toInt() and 0x1f else (a[u[0]].toInt() shr 1) and 0x3f
                    if (k == "h264" && (t == 9 || t == 12)) continue
                    if (k == "hevc" && (t == 35 || t == 38)) continue
                    keep.add(u); total += 4 + u[1]
                }
                if (keep.isEmpty()) return
                val out = ByteArray(total)
                var o = 0
                for (u in keep) {
                    out[o++] = (u[1] ushr 24).toByte(); out[o++] = (u[1] ushr 16).toByte()
                    out[o++] = (u[1] ushr 8).toByte(); out[o++] = u[1].toByte()
                    System.arraycopy(a, u[0], out, o, u[1]); o += u[1]
                }
                w.block(s.track, ms(pts), key, out)
            }
            "mpeg2" -> if (pts >= 0) w.block(s.track, ms(pts), Mpeg2.isKey(a, off, len), a, off, len)
            "vc1" -> if (pts >= 0) w.block(s.track, ms(pts), true, a, off, len)
            "pgs" -> if (pts >= 0) w.block(s.track, ms(pts), true, a, off, len)
            "lpcm" -> if (pts >= 0 && len > 4) w.block(s.track, ms(pts), true, a, off + 4, len - 4)
            "ac3", "eac3" -> frames(w, s, pts, a, off, len, s.track, k == "eac3")
            "dts" -> frames(w, s, pts, a, off, len, s.track, false)
            "truehd" -> frames(w, s, pts, a, off, len, s.track, false)
        }
    }

    /** Cuts the audio of a PES payload into frames; a frame that straddles two PES packets is completed. */
    private fun frames(w: MkvWriter, s: Stream, pts: Long, a: ByteArray, off: Int, len: Int, track: Int, eac3: Boolean) {
        val type = kind(s.es.type)
        if (s.tail.n > 65536) s.tail.clear()
        // join with the unfinished frame of the previous PES
        val data: ByteArray; val dOff: Int; val dLen: Int
        if (s.tail.n > 0) {
            val j = Buf(s.tail.n + len); j.add(s.tail.a, 0, s.tail.n); j.add(a, off, len)
            data = j.a; dOff = 0; dLen = j.n; s.tail.clear()
        } else { data = a; dOff = off; dLen = len }
        if (pts >= 0) { s.nextPts = pts; if (type == "truehd") s.nextCorePts = pts }
        var o = dOff
        val end = dOff + dLen
        var pending: ByteArray? = null          // E-AC-3: an independent frame waits for its dependent ones
        var pendingPts = 0L
        fun put(tr: Int, at: Long, d: ByteArray, from: Int, n: Int) { if (tr > 0 && at >= 0) w.block(tr, ms(at), true, d, from, n) }
        while (o < end) {
            when (type) {
                "ac3", "eac3" -> {
                    val f = AudioHeaders.ac3(data, o, end)
                    if (f == null) {
                        if (o + 8 > end) break
                        o++; continue
                    }
                    if (o + f.bytes > end) break
                    if (eac3) {
                        if (f.dependent && pending != null) {
                            pending = pending + data.copyOfRange(o, o + f.bytes)
                        } else {
                            if (pending != null) put(track, pendingPts, pending, 0, pending.size)
                            pending = data.copyOfRange(o, o + f.bytes)
                            pendingPts = s.nextPts
                        }
                        if (!f.dependent) s.nextPts += f.samples * 90000L / f.rate
                    } else {
                        put(track, s.nextPts, data, o, f.bytes)
                        s.nextPts += f.samples * 90000L / f.rate
                    }
                    o += f.bytes
                }
                "dts" -> {
                    val f = AudioHeaders.dts(data, o, end)
                    if (f == null) { o++; continue }
                    // the core frame and its HD extension run to the next core sync word
                    var e = o + f.bytes
                    while (e + 4 <= end && !(data[e].toInt() == 0x7f && data[e + 1].toInt() == 0xfe && (data[e + 2].toInt() and 0xff) == 0x80 && data[e + 3].toInt() == 0x01)) e++
                    if (e + 4 > end) { e = end; if (!isLastFrameComplete(data, o, end, f.bytes)) break }
                    put(track, s.nextPts, data, o, e - o)
                    s.nextPts += f.samples * 90000L / f.rate
                    o = e
                }
                "truehd" -> {
                    if (data[o].toInt() == 0x0b && o + 1 < end && data[o + 1].toInt() == 0x77) {
                        val f = AudioHeaders.ac3(data, o, end) ?: break
                        if (o + f.bytes > end) break
                        put(s.core, s.nextCorePts, data, o, f.bytes)
                        s.nextCorePts += f.samples * 90000L / f.rate
                        o += f.bytes
                    } else {
                        val f = AudioHeaders.truehd(data, o, end) ?: break
                        if (o + f.bytes > end) break
                        put(track, s.nextPts, data, o, f.bytes)
                        val rate = s.audio?.rate ?: 48000
                        val per = 40 shl (rate / (if (rate % 44100 == 0) 44100 else 48000)).countTrailingZeroBits()
                        s.nextPts += per * 90000L / rate
                        o += f.bytes
                    }
                }
                else -> o = end
            }
        }
        if (pending != null) put(track, pendingPts, pending, 0, pending.size)
        if (o < end) s.tail.add(data, o, end - o)
    }

    private fun isLastFrameComplete(d: ByteArray, o: Int, end: Int, size: Int): Boolean = end - o >= size
}
