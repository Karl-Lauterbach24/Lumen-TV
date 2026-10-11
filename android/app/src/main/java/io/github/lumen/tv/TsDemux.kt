package io.github.lumen.tv

/** A growing byte buffer. */
class Buf(cap: Int = 1 shl 16) {
    var a = ByteArray(cap)
    var n = 0
    fun add(src: ByteArray, off: Int, len: Int) {
        if (n + len > a.size) a = a.copyOf(maxOf(a.size * 2, n + len))
        System.arraycopy(src, off, a, n, len)
        n += len
    }
    fun clear() { n = 0 }
}

/**
 * Reads the packets of an MPEG transport stream (a Blu-ray clip: 192-byte packets with a 4-byte time
 * stamp in front, or plain 188-byte ones) and hands out the PES packets of every elementary stream.
 */
class TsDemux(private val r: RandomReader, private val packet: Int = 192) {
    class Es(val pid: Int, val type: Int, val lang: String)

    /** From the PMT, in the order of the table. */
    val streams = LinkedHashMap<Int, Es>()
    var pmtPid = -1
        private set
    var bytesRead = 0L
        private set

    private val psi = HashMap<Int, Buf>()
    private val pes = HashMap<Int, Buf>()

    /** [onPes] gets the PES payload (without header) with its PTS and DTS in 90 kHz ticks (-1 if absent).
     *  Returns when the stream ends, [limit] bytes were read or [onPes] returned false. */
    fun run(limit: Long = Long.MAX_VALUE, cancelled: () -> Boolean = { false },
            onPes: (pid: Int, pts: Long, dts: Long, data: ByteArray, off: Int, len: Int) -> Boolean) {
        val chunk = packet * 2048
        val buf = ByteArray(chunk)
        val h = packet - 188
        var pos = 0L
        var go = true
        fun deliver(pid: Int): Boolean {
            val b = pes[pid] ?: return true
            if (b.n < 9) { b.clear(); return true }
            val a = b.a
            if (a[0].toInt() != 0 || a[1].toInt() != 0 || a[2].toInt() != 1) { b.clear(); return true }
            val hdl = a[8].toInt() and 0xff
            val flags = (a[7].toInt() shr 6) and 3
            var pts = -1L; var dts = -1L
            if (flags >= 2 && b.n >= 14) pts = ts5(a, 9)
            if (flags == 3 && b.n >= 19) dts = ts5(a, 14)
            val start = 9 + hdl
            val ok = if (start <= b.n) onPes(pid, pts, dts, a, start, b.n - start) else true
            b.clear()
            return ok
        }
        loop@ while (go && pos < r.size && pos < limit) {
            if (cancelled()) break
            val n = r.read(pos, buf, 0, chunk)
            if (n < packet) break
            var o = 0
            while (o + packet <= n) {
                val p = o + h
                o += packet
                if (buf[p] != 0x47.toByte()) continue
                val pid = ((buf[p + 1].toInt() and 0x1f) shl 8) or (buf[p + 2].toInt() and 0xff)
                val start = (buf[p + 1].toInt() and 0x40) != 0
                val afc = (buf[p + 3].toInt() shr 4) and 3
                if (afc and 1 == 0) continue
                var at = p + 4
                if (afc == 3) at += 1 + (buf[at].toInt() and 0xff)
                val len = p + 188 - at
                if (len <= 0) continue
                if (pid == 0 || pid == pmtPid) {
                    psiPacket(pid, start, buf, at, len)
                } else if (streams.containsKey(pid)) {
                    if (start && !deliver(pid)) { go = false; break }
                    pes.getOrPut(pid) { Buf() }.add(buf, at, len)
                }
            }
            pos += (n / packet) * packet
            bytesRead = pos
        }
        if (go && !cancelled()) for (pid in pes.keys.toList()) if (!deliver(pid)) break
    }

    private fun ts5(a: ByteArray, o: Int): Long =
        ((a[o].toLong() and 0x0e) shl 29) or ((a[o + 1].toLong() and 0xff) shl 22) or ((a[o + 2].toLong() and 0xfe) shl 14) or
            ((a[o + 3].toLong() and 0xff) shl 7) or ((a[o + 4].toLong() and 0xff) shr 1)

    private fun psiPacket(pid: Int, start: Boolean, b: ByteArray, off: Int, len: Int) {
        val s = psi.getOrPut(pid) { Buf(1024) }
        if (start) s.clear()
        s.add(b, off, len)
        val a = s.a
        if (s.n < 4) return
        val ptr = a[0].toInt() and 0xff
        if (s.n < ptr + 4) return
        val t = ptr + 1
        val secLen = ((a[t + 1].toInt() and 0x0f) shl 8) or (a[t + 2].toInt() and 0xff)
        if (s.n < t + 3 + secLen) return
        val table = a[t].toInt() and 0xff
        val end = t + 3 + secLen - 4
        if (pid == 0 && table == 0 && pmtPid < 0) {
            var i = t + 8
            while (i + 4 <= end) {
                val program = ((a[i].toInt() and 0xff) shl 8) or (a[i + 1].toInt() and 0xff)
                val p = ((a[i + 2].toInt() and 0x1f) shl 8) or (a[i + 3].toInt() and 0xff)
                if (program != 0) { pmtPid = p; break }
                i += 4
            }
        } else if (pid == pmtPid && table == 2 && streams.isEmpty()) {
            val pil = ((a[t + 10].toInt() and 0x0f) shl 8) or (a[t + 11].toInt() and 0xff)
            var i = t + 12 + pil
            while (i + 5 <= end) {
                val type = a[i].toInt() and 0xff
                val p = ((a[i + 1].toInt() and 0x1f) shl 8) or (a[i + 2].toInt() and 0xff)
                val il = ((a[i + 3].toInt() and 0x0f) shl 8) or (a[i + 4].toInt() and 0xff)
                var lang = ""
                var d = i + 5
                while (d + 2 <= i + 5 + il && d + 2 <= end) {
                    val tag = a[d].toInt() and 0xff
                    val dl = a[d + 1].toInt() and 0xff
                    if (tag == 0x0a && dl >= 3) lang = String(a, d + 2, 3, Charsets.ISO_8859_1)
                    d += 2 + dl
                }
                streams[p] = Es(p, type, lang)
                i += 5 + il
            }
        }
    }
}
