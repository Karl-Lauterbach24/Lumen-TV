package io.github.lumen.tv

import android.graphics.Bitmap
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import android.net.Uri
import java.io.IOException

/**
 * A disc played with its own menus: libbluray (built into liblumenbd.so) runs the disc's HDMV program,
 * reads the files through [Fs] (the app's UDF reader, decrypted streams), hands out the stream that is
 * to be played and the menu's pictures as ARGB planes.
 */
class Bd {
    interface Fs {
        fun openFile(path: String): Int
        fun size(h: Int): Long
        fun read(h: Int, pos: Long, buf: ByteArray, off: Int, len: Int): Int
        fun close(h: Int)
        fun list(path: String): Array<String>?
    }

    companion object {
        private var loaded = false
        fun available(): Boolean {
            if (!loaded) loaded = try { System.loadLibrary("lumenbd"); true } catch (_: Throwable) { false }
            return loaded
        }
        // events (libbluray's BD_EVENT_*)
        const val EV_ERROR = 1; const val EV_READ_ERROR = 2; const val EV_ENCRYPTED = 3
        const val EV_TITLE = 5; const val EV_PLAYLIST = 6; const val EV_PLAYITEM = 7; const val EV_CHAPTER = 8
        const val EV_END_OF_TITLE = 10; const val EV_STILL = 25; const val EV_STILL_TIME = 26; const val EV_SOUND = 27
        // keys (BD_VK_*)
        const val VK_ROOT_MENU = 10; const val VK_POPUP = 11; const val VK_UP = 12; const val VK_DOWN = 13
        const val VK_LEFT = 14; const val VK_RIGHT = 15; const val VK_ENTER = 16
        const val VK_RED = 403; const val VK_GREEN = 404; const val VK_YELLOW = 405; const val VK_BLUE = 406
    }

    /** Called with the changed rectangle of a plane (0 = subtitles, 1 = menu); [pixels] is null when the plane was cleared. */
    var overlay: ((plane: Int, x: Int, y: Int, w: Int, h: Int, pixels: IntArray?) -> Unit)? = null
    @Volatile var opened = false
        private set

    private external fun nOpen(fs: Fs): Boolean
    private external fun nClose()
    private external fun nInfo(): String
    private external fun nPlay(): Boolean
    private external fun nPlayTitle(t: Int): Boolean
    private external fun nMenu(): Boolean
    private external fun nKey(key: Int): Int
    private external fun nTime(): Long
    private external fun nSeekTime(t: Long): Long
    private external fun nSkipStill(): Int
    private external fun nEvent(): Long
    private external fun nSound(id: Int): ShortArray?
    private external fun nRead(buf: ByteArray, off: Int, len: Int): Int
    private external fun nOverlayPixels(plane: Int, x: Int, y: Int, w: Int, h: Int, out: IntArray): Boolean

    fun open(fs: Fs): Boolean { opened = available() && nOpen(fs); return opened }
    fun close() { if (opened) { opened = false; nClose() } }
    fun info(): String = if (opened) nInfo() else "{}"
    fun play(): Boolean = opened && nPlay()
    fun playTitle(n: Int): Boolean = opened && nPlayTitle(n)
    fun menu(): Boolean = opened && nMenu()
    fun key(vk: Int) { if (opened) nKey(vk) }
    fun timeTicks(): Long = if (opened) nTime() else 0
    fun skipStill() { if (opened) nSkipStill() }
    fun seekTicks(t: Long): Long = if (opened) nSeekTime(t) else -1
    /** A sound effect of the disc (sound.bdmv) played once, on its own thread. */
    fun playSound(id: Int) {
        val data = if (opened) nSound(id) else null
        if (data == null || data.size < 2) return
        val ch = data[0].toInt().coerceIn(1, 2)
        kotlin.concurrent.thread {
            try {
                val bytes = (data.size - 1) * 2
                val track = android.media.AudioTrack.Builder()
                    .setAudioAttributes(android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(48000).setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(if (ch == 1) android.media.AudioFormat.CHANNEL_OUT_MONO else android.media.AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(bytes).setTransferMode(android.media.AudioTrack.MODE_STATIC).build()
                track.write(data, 1, data.size - 1)
                track.play()
                android.util.Log.i("LumenTV", "disc sound ${(data.size - 1) / ch} frames, ${ch} ch, state ${track.state}")
                Thread.sleep((data.size - 1) / ch * 1000L / 48000 + 200)
                track.release()
            } catch (e: Exception) { android.util.Log.w("LumenTV", "disc sound failed: $e") }
        }
    }

    /** (event << 32 | value) or 0 */
    fun event(): Long = if (opened) nEvent() else 0
    fun read(buf: ByteArray, off: Int, len: Int): Int = if (opened) nRead(buf, off, len) else -1

    /** from native: a plane changed (x0 < 0: cleared) */
    @Suppress("unused")
    fun onOverlay(plane: Int, x0: Int, y0: Int, x1: Int, y1: Int) {
        val cb = overlay ?: return
        if (x0 < 0) { cb(plane, 0, 0, 0, 0, null); return }
        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        if (w <= 0 || h <= 0) return
        val px = IntArray(w * h)
        if (nOverlayPixels(plane, x0, y0, w, h, px)) cb(plane, x0, y0, w, h, px)
    }
}

/** The files of a disc for libbluray; streams of an AACS disc are decrypted as they are read. */
class DiscFs(private val udf: Udf, private val keys: List<ByteArray>?) : Bd.Fs {
    private val files = HashMap<Int, RandomReader>()
    private var next = 1

    @Synchronized override fun openFile(path: String): Int {
        return try {
            val p = path.trimStart('/')
            var r: RandomReader = udf.open(p)
            if (keys != null && p.startsWith("BDMV/STREAM/", true) && p.endsWith(".m2ts", true)) r = AacsReader(r, keys)
            val h = next++
            files[h] = r
            h
        } catch (_: Exception) { -1 }
    }
    @Synchronized override fun size(h: Int): Long = files[h]?.size ?: 0
    override fun read(h: Int, pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        val r = synchronized(this) { files[h] } ?: return -1
        return try { r.read(pos, buf, off, len) } catch (_: Exception) { -1 }
    }
    @Synchronized override fun close(h: Int) { files.remove(h) }
    override fun list(path: String): Array<String>? = try {
        udf.list(path.trim('/')).map { it.name }.toTypedArray()
    } catch (_: Exception) { null }
}

/** The stream of a menu-driven disc as one endless source for the player. */
@UnstableApi
class BdDataSource(private val bd: Bd, private val events: (Int, Int) -> Unit) : BaseDataSource(false) {
    private var uri: Uri? = null
    private val chunk = ByteArray(188 * 32)
    private var have = 0
    private var at = 0

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (have - at == 0) {
            if (!bd.opened) return C.RESULT_END_OF_INPUT
            val n = bd.read(chunk, 0, chunk.size)
            while (true) {
                val e = bd.event()
                if (e == 0L) break
                events((e ushr 32).toInt(), e.toInt())
            }
            if (n < 0) return C.RESULT_END_OF_INPUT
            if (n == 0) { try { Thread.sleep(15) } catch (_: InterruptedException) { return C.RESULT_END_OF_INPUT }; continue }
            have = n; at = 0
        }
        val c = minOf(length, have - at)
        System.arraycopy(chunk, at, buffer, offset, c)
        at += c
        bytesTransferred(c)
        return c
    }

    override fun getUri(): Uri? = uri
    override fun close() { uri = null; transferEnded() }

    class Factory(private val bd: Bd, private val events: (Int, Int) -> Unit) : DataSource.Factory {
        override fun createDataSource(): DataSource = BdDataSource(bd, events)
    }
}
