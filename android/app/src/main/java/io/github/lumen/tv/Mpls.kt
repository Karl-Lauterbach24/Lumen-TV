package io.github.lumen.tv

/**
 * A Blu-ray playlist (BDMV/PLAYLIST/xxxxx.mpls): which clips, in which order, for how long, and its chapters
 * ([marks]: the entry marks, in seconds of the playlist; [clipMarks]: the same per clip, in seconds of that clip).
 */
class Playlist(val name: String, val clips: List<String>, val seconds: Double,
               val marks: List<Double> = emptyList(), val clipMarks: Map<String, List<Double>> = emptyMap()) {
    companion object {
        fun parse(name: String, b: ByteArray): Playlist? {
            fun u16(o: Int) = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)
            fun u32(o: Long) = (u16(o.toInt()).toLong() shl 16) or u16(o.toInt() + 2).toLong()
            try {
                if (b.size < 40 || String(b, 0, 4, Charsets.ISO_8859_1) != "MPLS") return null
                var p = u32(8).toInt()
                val count = u16(p + 6)
                p += 10
                val clips = ArrayList<String>()
                val starts = ArrayList<Long>()
                val ins = ArrayList<Long>()
                var ticks = 0L
                for (i in 0 until count) {
                    val len = u16(p)
                    val clip = String(b, p + 2, 5, Charsets.ISO_8859_1)
                    val multiAngle = (b[p + 12].toInt() and 0x10) != 0
                    val tin = u32((p + 14).toLong())
                    val tout = u32((p + 18).toLong())
                    clips.add(clip)
                    starts.add(ticks); ins.add(tin)
                    ticks += tout - tin
                    p += len + 2
                    if (multiAngle) { /* the angles are alternates: the first is used */ }
                }
                if (clips.isEmpty()) return null
                // the marks: u32 length, u16 count, then 14 bytes each (type 1: an entry mark, a chapter)
                val marks = ArrayList<Double>()
                val clipMarks = HashMap<String, ArrayList<Double>>()
                try {
                    val m = u32(12).toInt()
                    val n = u16(m + 4)
                    for (k in 0 until n) {
                        val o = m + 6 + k * 14
                        if (o + 14 > b.size) break
                        if (b[o + 1].toInt() != 1) continue
                        val item = u16(o + 2)
                        if (item >= clips.size) continue
                        val at = u32((o + 4).toLong()) - ins[item]
                        if (at < 0) continue
                        marks.add((starts[item] + at) / 45000.0)
                        clipMarks.getOrPut(clips[item]) { ArrayList() }.add(at / 45000.0)
                    }
                } catch (_: Exception) { }
                fun tidy(l: List<Double>, end: Double) = l.sorted().fold(ArrayList<Double>()) { a, x ->
                    if ((a.isEmpty() || x - a.last() >= 1.0) && (end <= 0 || x < end - 2)) a.add(x); a }
                return Playlist(name, clips, ticks / 45000.0, tidy(marks, ticks / 45000.0), clipMarks.mapValues { tidy(it.value, 0.0) })
            } catch (_: Exception) { return null }
        }
    }
}

/**
 * What a playlist's stream table (STN) says about its audio and subtitle streams, straight from the disc
 * (unprotected, readable before MakeMKV has the drive): language, format and channels of each stream in the
 * order the disc's menus know them, and the secondary audio streams (where discs put a commentary).
 */
class StreamTable(val audio: List<Item>, val subtitles: List<Item>, val secondaryAudio: List<Item>) {
    class Item(val lang: String, val coding: Int, val channels: Int)

    companion object {
        fun parse(b: ByteArray): StreamTable? {
            fun u8(o: Int) = b[o].toInt() and 0xff
            fun u16(o: Int) = (u8(o) shl 8) or u8(o + 1)
            fun u32(o: Int) = (u16(o).toLong() shl 16) or u16(o + 2).toLong()
            try {
                if (b.size < 40 || String(b, 0, 4, Charsets.ISO_8859_1) != "MPLS") return null
                var p = u32(8).toInt() + 10                     // the first play item
                val multiAngle = (u8(p + 12) and 0x10) != 0
                var stn = p + 34
                if (multiAngle) stn += 2 + 10 * (u8(p + 34) - 1)
                val nVideo = u8(stn + 4); val nAudio = u8(stn + 5); val nPg = u8(stn + 6); val nIg = u8(stn + 7)
                val nSec = u8(stn + 8); val nSecVideo = u8(stn + 9); val nPip = u8(stn + 10)
                var o = stn + 16
                fun lang(at: Int) = String(b, at, 3, Charsets.ISO_8859_1)
                // a stream: its entry (length, bytes), its attributes (length, bytes)
                fun <T> stream(read: (attr: Int, len: Int) -> T): T {
                    o += 1 + u8(o)
                    val len = u8(o)
                    val r = read(o + 1, len)
                    o += 1 + len
                    return r
                }
                repeat(nVideo) { stream { _, _ -> } }
                val audio = (0 until nAudio).map { stream { a, _ -> StreamTable.Item(lang(a + 2), u8(a), u8(a + 1) shr 4) } }
                val pg = (0 until nPg).map { stream { a, _ -> StreamTable.Item(lang(a + 1), u8(a), 0) } }
                repeat(nIg) { stream { _, _ -> } }
                val sec = try {
                    (0 until nSec).map {
                        val item = stream { a, _ -> StreamTable.Item(lang(a + 2), u8(a), u8(a + 1) shr 4) }
                        // (then the primary audio streams it can go with: a count, a reserved byte, one byte each, padded to even)
                        val refs = u8(o); o += 2 + refs + (refs and 1)
                        item
                    }
                } catch (_: Exception) { emptyList() }
                return StreamTable(audio, pg, sec)
            } catch (_: Exception) { return null }
        }

        fun codingName(c: Int) = when (c) {
            0x80 -> "LPCM"; 0x81 -> "Dolby Digital"; 0x82 -> "DTS"; 0x83 -> "Dolby TrueHD"; 0x84, 0xA1 -> "Dolby Digital Plus"
            0x85, 0xA2 -> "DTS-HD HRA"; 0x86 -> "DTS-HD MA"; else -> "0x" + c.toString(16)
        }
    }

    override fun toString() = "audio " + audio.joinToString(",") { "${it.lang}/${codingName(it.coding)}/${it.channels}" } +
        " | subtitles " + subtitles.joinToString(",") { it.lang } + " | secondary " + secondaryAudio.joinToString(",") { it.lang }
}
