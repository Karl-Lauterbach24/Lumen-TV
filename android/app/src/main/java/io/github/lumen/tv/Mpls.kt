package io.github.lumen.tv

/** A Blu-ray playlist (BDMV/PLAYLIST/xxxxx.mpls): which clips, in which order, for how long. */
class Playlist(val name: String, val clips: List<String>, val seconds: Double) {
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
                var ticks = 0L
                for (i in 0 until count) {
                    val len = u16(p)
                    val clip = String(b, p + 2, 5, Charsets.ISO_8859_1)
                    val multiAngle = (b[p + 12].toInt() and 0x10) != 0
                    val tin = u32((p + 14).toLong())
                    val tout = u32((p + 18).toLong())
                    clips.add(clip)
                    ticks += tout - tin
                    p += len + 2
                    if (multiAngle) { /* the angles are alternates: the first is used */ }
                }
                if (clips.isEmpty()) return null
                return Playlist(name, clips, ticks / 45000.0)
            } catch (_: Exception) { return null }
        }
    }
}
