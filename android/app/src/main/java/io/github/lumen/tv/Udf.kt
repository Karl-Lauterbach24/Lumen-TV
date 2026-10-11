package io.github.lumen.tv

import java.io.Closeable
import java.io.IOException
import java.io.RandomAccessFile

/** Something that can be read at a position: a file of a disc, a file in storage. */
interface RandomReader {
    val size: Long
    /** Up to [len] bytes at [pos]; the count, or -1 at the end. */
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int
}

/** Sectors of 2048 bytes, wherever they come from: a drive on USB, a disc image. */
interface BlockSource : Closeable {
    val sectors: Long
    /** Reads [count] sectors from [lba] into [out] at [offset]. */
    fun read(lba: Long, count: Int, out: ByteArray, offset: Int)
}

class FileBlockSource(path: String) : BlockSource {
    private val file = RandomAccessFile(path, "r")
    override val sectors: Long = file.length() / 2048
    override fun read(lba: Long, count: Int, out: ByteArray, offset: Int) {
        synchronized(this) {
            file.seek(lba * 2048)
            file.readFully(out, offset, count * 2048)
        }
    }
    override fun close() = file.close()
}

/**
 * The file system of a data disc (UDF 1.02 … 2.60, which is what DVD-Video and Blu-ray use), read-only,
 * in the one way a player needs: list a folder, read a file at a position.
 *
 * Blu-ray discs are UDF 2.50, with a *metadata partition*: folders and file entries live in a file
 * of the disc's own that the partition map points to; that is what [Partition.metadata] is.
 */
class Udf(private val source: BlockSource) : Closeable {
    class Entry internal constructor(val name: String, val dir: Boolean, val size: Long, internal val icb: LongAd)

    internal class LongAd(val length: Long, val block: Long, val partition: Int, val type: Int)

    internal class Partition(val number: Int, var start: Long = 0, var length: Long = 0,
                            var metadataFile: List<Extent>? = null, var base: Int = -1)

    internal class Extent(val partition: Int, val block: Long, val length: Long, val recorded: Boolean)

    private val partitionsByNumber = HashMap<Int, Partition>()
    private val maps = ArrayList<Partition>()   // by partition reference of the logical volume
    private var rootIcb: LongAd? = null
    var label: String = ""
        private set

    init { mount() }

    override fun close() = source.close()

    // ---- low level ----
    private fun sector(lba: Long): ByteArray {
        val b = ByteArray(2048)
        source.read(lba, 1, b, 0)
        return b
    }
    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    private fun u32(b: ByteArray, o: Int) = (u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)
    private fun u64(b: ByteArray, o: Int) = u32(b, o) or (u32(b, o + 4) shl 32)

    private fun mount() {
        // Anchor volume descriptor pointer: sector 256 (or, on odd media, the last sectors)
        var avdp: ByteArray? = null
        for (candidate in longArrayOf(256, source.sectors - 256, source.sectors - 1)) {
            if (candidate < 0 || candidate >= source.sectors) continue
            val s = sector(candidate)
            if (u16(s, 0) == 2) { avdp = s; break }
        }
        avdp ?: throw IOException("no UDF volume (no anchor)")
        val vdsLength = u32(avdp, 16)
        val vdsStart = u32(avdp, 20)
        var lvd: ByteArray? = null
        var n = 0L
        loop@ while (n * 2048 < vdsLength) {
            val s = sector(vdsStart + n)
            when (u16(s, 0)) {
                5 -> {
                    val number = u16(s, 22)
                    val p = partitionsByNumber.getOrPut(number) { Partition(number) }
                    p.start = u32(s, 188); p.length = u32(s, 192)
                }
                6 -> lvd = s
                8 -> break@loop
            }
            n++
        }
        lvd ?: throw IOException("no logical volume descriptor")
        // label: dstring at 84 (128 bytes)
        label = dstring(lvd, 84, 128)
        val blockSize = u32(lvd, 212)
        if (blockSize != 2048L) throw IOException("logical block size $blockSize")
        val mapCount = u32(lvd, 268).toInt()
        var o = 440
        for (i in 0 until mapCount) {
            val type = lvd[o].toInt() and 0xff
            val len = lvd[o + 1].toInt() and 0xff
            if (type == 1) {
                val num = u16(lvd, o + 4)
                maps.add(partitionsByNumber.getOrPut(num) { Partition(num) })
            } else {
                val id = String(lvd, o + 5, 23, Charsets.ISO_8859_1)
                val num = u16(lvd, o + 38)
                val p = Partition(num + 100000 + i)
                if (id.startsWith("*UDF Metadata")) {
                    p.base = num
                    p.metadataFile = listOf(Extent(-1, u32(lvd, o + 40), 0, true)) // resolved below
                    p.start = u32(lvd, o + 40)       // location of the metadata file entry
                } else {
                    // virtual / sparable: not on a read-only disc
                    p.base = num
                }
                maps.add(p)
            }
            o += len
        }
        // resolve the metadata partitions: read their file entries, in the physical partition
        for (p in maps) {
            if (p.base >= 0 && p.metadataFile != null) {
                val physical = partitionsByNumber[p.base] ?: throw IOException("metadata partition without base")
                val fe = sector(physical.start + p.start)
                val extents = allocationDescriptors(fe, physicalPartitionIndex(p.base))
                p.metadataFile = extents
                p.length = extents.sumOf { it.length } / 2048
            }
        }
        // file set descriptor: logical volume contents use at 248 (a long_ad)
        val fsd = longAd(lvd, 248, 0)
        val fs = readLogical(fsd.partition, fsd.block, 1)
        if (u16(fs, 0) != 256) throw IOException("no file set descriptor")
        rootIcb = longAd(fs, 400, 0)
    }

    private fun physicalPartitionIndex(number: Int): Int {
        for ((i, p) in maps.withIndex()) if (p.number == number && p.base < 0) return i
        throw IOException("partition $number not mapped")
    }

    private fun dstring(b: ByteArray, off: Int, len: Int): String {
        val comp = b[off].toInt() and 0xff
        val used = b[off + len - 1].toInt() and 0xff
        if (used <= 1) return ""
        return try {
            when (comp) {
                8 -> String(b, off + 1, used - 1, Charsets.ISO_8859_1)
                16 -> String(b, off + 1, used - 1, Charsets.UTF_16BE)
                else -> ""
            }
        } catch (_: Exception) { "" }
    }

    private fun longAd(b: ByteArray, o: Int, defaultPartition: Int): LongAd {
        val l = u32(b, o)
        return LongAd(l and 0x3fffffffL, u32(b, o + 4), u16(b, o + 8), (l ushr 30).toInt())
    }

    // ---- logical blocks → sectors ----
    /** Reads [count] logical blocks of partition reference [ref] starting at [block]. */
    private fun readLogical(ref: Int, block: Long, count: Int): ByteArray {
        val out = ByteArray(count * 2048)
        var done = 0
        while (done < count) {
            done += readRun(ref, block + done, count - done, out, done * 2048)
        }
        return out
    }

    /** Reads one contiguous run; returns how many blocks it got. */
    private fun readRun(ref: Int, block: Long, count: Int, out: ByteArray, off: Int): Int {
        val p = maps.getOrNull(ref) ?: throw IOException("partition reference $ref")
        if (p.metadataFile == null) {
            val n = minOf(count.toLong(), maxOf(1L, p.length - block)).toInt().coerceAtLeast(1)
            source.read(p.start + block, n, out, off)
            return n
        }
        // inside the metadata file: find the extent holding this logical block
        var skip = block * 2048
        val base = physicalPartitionIndex(p.base)
        for (e in p.metadataFile!!) {
            if (skip < e.length) {
                val inExtent = ((e.length - skip) / 2048).toInt()
                val n = minOf(count, inExtent)
                val sectorStart = maps[base].start + e.block + skip / 2048
                source.read(sectorStart, n, out, off)
                return n
            }
            skip -= e.length
        }
        throw IOException("block $block beyond the metadata file")
    }

    // ---- file entries ----
    private fun allocationDescriptors(fe: ByteArray, partitionRef: Int): List<Extent> {
        val tag = u16(fe, 0)
        val (eaLen, adLen, adOff) = when (tag) {
            261 -> Triple(u32(fe, 168).toInt(), u32(fe, 172).toInt(), 176)
            266 -> Triple(u32(fe, 208).toInt(), u32(fe, 212).toInt(), 216)
            else -> throw IOException("not a file entry ($tag)")
        }
        val adType = u16(fe, 34) and 7
        val list = ArrayList<Extent>()
        if (adType == 3) { // data inside the entry
            return list
        }
        val step = if (adType == 0) 8 else 16
        var buf = fe
        var o = adOff + eaLen
        var remaining = adLen
        while (remaining >= step) {
            val raw = u32(buf, o)
            val length = raw and 0x3fffffffL
            val type = (raw ushr 30).toInt()
            val block = u32(buf, o + 4)
            val ref = if (adType == 0) partitionRef else u16(buf, o + 8)
            o += step; remaining -= step
            if (length == 0L) break
            if (type == 3) { // continuation: another block of descriptors
                val cont = readLogical(ref, block, ((length + 2047) / 2048).toInt())
                buf = cont; o = 24; remaining = u32(cont, 20).toInt()
                continue
            }
            list.add(Extent(ref, block, length, type == 0))
        }
        return list
    }

    internal class Node(val dir: Boolean, val size: Long, val extents: List<Extent>, val inline: ByteArray?)

    private fun node(icb: LongAd): Node {
        val fe = readLogical(icb.partition, icb.block, 1)
        val tag = u16(fe, 0)
        val fileType = fe[16 + 11].toInt() and 0xff
        val flags = u16(fe, 34)
        val infoLength = u64(fe, 56)
        if (flags and 7 == 3) {
            val (eaLen, adOff) = if (tag == 261) u32(fe, 168).toInt() to 176 else u32(fe, 208).toInt() to 216
            val len = infoLength.toInt()
            return Node(fileType == 4, infoLength, emptyList(), fe.copyOfRange(adOff + eaLen, adOff + eaLen + len))
        }
        return Node(fileType == 4, infoLength, allocationDescriptors(fe, icb.partition), null)
    }

    private fun readAll(n: Node): ByteArray {
        n.inline?.let { return it }
        val out = ByteArray(n.size.toInt())
        var pos = 0
        for (e in n.extents) {
            var left = minOf(e.length, n.size - pos).toInt()
            if (left <= 0) break
            val blocks = ((left + 2047) / 2048)
            val data = readLogical(e.partition, e.block, blocks)
            System.arraycopy(data, 0, out, pos, left)
            pos += left
        }
        return out
    }

    // ---- directories ----
    fun list(path: String): List<Entry> {
        val n = resolve(path) ?: throw IOException("no such folder: $path")
        if (!n.dir) throw IOException("not a folder: $path")
        return entries(n)
    }

    private fun entries(dirNode: Node): List<Entry> {
        val data = readAll(dirNode)
        val out = ArrayList<Entry>()
        var o = 0
        while (o + 38 <= data.size) {
            if (u16(data, o) != 257) break
            val characteristics = data[o + 18].toInt() and 0xff
            val nameLen = data[o + 19].toInt() and 0xff
            val icb = longAd(data, o + 20, 0)
            val implLen = u16(data, o + 36)
            val nameOff = o + 38 + implLen
            val total = (38 + implLen + nameLen + 3) and 3.inv()
            if (characteristics and 0x08 == 0 && characteristics and 0x04 == 0 && nameLen > 0) {
                val comp = data[nameOff].toInt() and 0xff
                val name = if (comp == 16) String(data, nameOff + 1, nameLen - 1, Charsets.UTF_16BE)
                           else String(data, nameOff + 1, nameLen - 1, Charsets.ISO_8859_1)
                val child = try { node(icb) } catch (_: Exception) { null }
                out.add(Entry(name, characteristics and 0x02 != 0, child?.size ?: 0, icb))
            }
            o += total
        }
        return out
    }

    private fun resolve(path: String): Node? {
        var cur = node(rootIcb ?: return null)
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            if (!cur.dir) return null
            val e = entries(cur).firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return null
            cur = node(e.icb)
        }
        return cur
    }

    // ---- files ----
    inner class FileReader internal constructor(private val n: Node) : RandomReader {
        override val size: Long get() = n.size
        private var cache = ByteArray(0)
        private var cacheAt = -1L

        /** Reads up to [len] bytes at [pos]; returns the count, or -1 at the end. */
        override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
            if (pos >= n.size) return -1
            n.inline?.let {
                val c = minOf(len.toLong(), n.size - pos).toInt()
                System.arraycopy(it, pos.toInt(), buf, off, c)
                return c
            }
            var want = minOf(len.toLong(), n.size - pos).toInt()
            var done = 0
            while (want > 0) {
                val p = pos + done
                if (cacheAt < 0 || p < cacheAt || p >= cacheAt + cache.size) fill(p)
                val inCache = (p - cacheAt).toInt()
                val c = minOf(want, cache.size - inCache)
                if (c <= 0) break
                System.arraycopy(cache, inCache, buf, off + done, c)
                done += c; want -= c
            }
            return if (done == 0) -1 else done
        }

        private fun fill(pos: Long) {
            // the extent that holds pos
            var start = 0L
            for (e in n.extents) {
                if (pos < start + e.length) {
                    val within = pos - start
                    val alignedWithin = within / 2048 * 2048
                    val blocksLeft = ((e.length - alignedWithin + 2047) / 2048).toInt()
                    val blocks = minOf(blocksLeft, 512)      // one mebibyte ahead
                    val data = if (e.recorded) readLogical(e.partition, e.block + alignedWithin / 2048, blocks) else ByteArray(blocks * 2048)
                    cache = data
                    cacheAt = start + alignedWithin
                    return
                }
                start += e.length
            }
            cache = ByteArray(0); cacheAt = -1
        }
    }

    fun open(path: String): FileReader {
        val n = resolve(path) ?: throw IOException("no such file: $path")
        if (n.dir) throw IOException("is a folder: $path")
        return FileReader(n)
    }

    fun exists(path: String): Boolean = try { resolve(path) != null } catch (_: Exception) { false }
}
