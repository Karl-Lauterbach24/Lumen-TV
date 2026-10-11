package io.github.lumen.tv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

/** Gives the player a file of a disc: `disc://<disc>/<path>`. */
@UnstableApi
class DiscDataSource(private val find: (String, String) -> RandomReader?) : BaseDataSource(/* isNetwork = */ false) {
    private var uri: Uri? = null
    private var reader: RandomReader? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false
    // A Blu-ray stream (.m2ts) has a 4-byte time stamp before each 188-byte transport packet; the player's
    // extractor wants the plain 188-byte packets. Position and size are then those of the plain stream.
    private var bdav = false
    private val tmp = ByteArray(192 * 64)

    override fun open(dataSpec: DataSpec): Long {
        val u = dataSpec.uri
        uri = u
        transferInitializing(dataSpec)
        val path = u.path?.removePrefix("/") ?: ""
        val r = find(u.host ?: "", path) ?: throw IOException("the disc is gone")
        reader = r
        position = dataSpec.position
        bdav = path.endsWith(".m2ts", ignoreCase = true) && looksLikeBdav(r)
        val size = if (bdav) r.size / 192 * 188 else r.size
        if (position > size) throw IOException("position beyond the file")
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, size - position) else size - position
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val want = minOf(length.toLong(), remaining).toInt()
        val n = if (bdav) readPlain(buffer, offset, want) else reader!!.read(position, buffer, offset, want)
        if (n < 0) return C.RESULT_END_OF_INPUT
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    private fun looksLikeBdav(r: RandomReader): Boolean {
        val b = ByteArray(192 * 3)
        if (r.read(0, b, 0, b.size) < b.size) return false
        return b[4] == 0x47.toByte() && b[196] == 0x47.toByte() && b[388] == 0x47.toByte()
    }

    /** Reads plain transport packets out of the disc's 192-byte ones. */
    private fun readPlain(buffer: ByteArray, offset: Int, want: Int): Int {
        val first = position / 188
        val skip = (position % 188).toInt()
        val packets = minOf(64, (skip + want + 187) / 188)
        val got = reader!!.read(first * 192, tmp, 0, packets * 192)
        if (got < 0) return -1
        val whole = got / 192
        var out = 0
        for (p in 0 until whole) {
            val from = p * 192 + 4 + (if (p == 0) skip else 0)
            val len = minOf(if (p == 0) 188 - skip else 188, want - out)
            if (len <= 0) break
            System.arraycopy(tmp, from, buffer, offset + out, len)
            out += len
        }
        return if (out == 0) -1 else out
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        reader = null
        if (opened) { opened = false; transferEnded() }
    }

    class Factory(private val find: (String, String) -> RandomReader?) : DataSource.Factory {
        override fun createDataSource(): DataSource = DiscDataSource(find)
    }
}
