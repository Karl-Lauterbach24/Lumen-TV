package io.github.lumen.tv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A disc played with its own menu through bdmenu (see makemkv-native/bdmenu), which runs libbluray with libmmbd's
 * decryption in the Linux tree: the stream arrives through a fifo ([fifo], for the player), the menu's pictures as files
 * announced on bdmenu's stdout (given to [overlay] as for [Bd]), keys go back on its stdin.
 */
class RemoteBd(private val makemkv: MakeMkv, private val log: (String) -> Unit) {
    var overlay: ((plane: Int, x: Int, y: Int, w: Int, h: Int, pixels: IntArray?) -> Unit)? = null
    /** A sound effect of the disc's menu: 16 bit samples, interleaved. */
    var onSound: ((samples: ShortArray, channels: Int) -> Unit)? = null
    @Volatile var opened = false
        private set

    private var process: Process? = null
    val fifo: File get() = File(makemkv.menuDir(), "ts.fifo")

    /** Starts the player of the disc and waits until it is ready (the disc is opened, decrypted: up to a few minutes). */
    fun start(lang: String, stop: () -> Boolean): Boolean {
        val dir = makemkv.menuDir().apply { deleteRecursively(); mkdirs() }
        android.system.Os.mkfifo(fifo.absolutePath, 0x1b6)
        val p = makemkv.startMenu(listOf("serve", "/tmp/menu/ts.fifo", lang))
        process = p
        val ready = CountDownLatch(1)
        kotlin.concurrent.thread(isDaemon = true) {
            val last = ArrayDeque<String>()
            try {
                p.inputStream.bufferedReader().forEachLine { l ->
                    if (!l.startsWith("FLUSH ") && !l.startsWith("CLEAR ")) { last.addLast(l.take(160)); if (last.size > 14) last.removeFirst(); if (l != "READY") log("bdmenu: ${l.take(200)}") }
                    when {
                        l == "READY" -> { opened = true; ready.countDown() }
                        l.startsWith("FLUSH ") -> flush(l, dir)
                        l.startsWith("CLEAR ") -> overlay?.invoke(l.substringAfter(' ').trim().toIntOrNull() ?: 1, 0, 0, 0, 0, null)
                        l.startsWith("SOUND ") -> sound(l)
                        l.startsWith("ERROR") || l.startsWith("DISC") -> log("bdmenu: $l")
                    }
                }
            } catch (_: java.io.IOException) { }
            log("bdmenu ended (exit ${try { p.waitFor(2, TimeUnit.SECONDS); p.exitValue() } catch (_: Exception) { -1 }}), last lines: ${last.joinToString(" | ")}")
            opened = false
            ready.countDown()
        }
        while (!ready.await(500, TimeUnit.MILLISECONDS)) { if (stop() || !p.isAlive) break }
        return opened
    }

    private fun flush(l: String, dir: File) {
        try {
            val f = l.split(' ')
            val seq = f[1].toInt(); val plane = f[2].toInt(); val x = f[3].toInt(); val y = f[4].toInt(); val w = f[5].toInt(); val h = f[6].toInt()
            val file = File(dir, "g%04d_%d.raw".format(seq, plane))
            val b = file.readBytes(); file.delete()
            val nl = b.indexOf('\n'.code.toByte())
            val px = IntArray(w * h)
            var o = nl + 1
            for (i in 0 until w * h) {
                val r = b[o].toInt() and 0xff; val g = b[o + 1].toInt() and 0xff; val bb = b[o + 2].toInt() and 0xff; val a = b[o + 3].toInt() and 0xff
                px[i] = (a shl 24) or (r shl 16) or (g shl 8) or bb
                o += 4
            }
            overlay?.invoke(plane, x, y, w, h, px)
        } catch (e: Exception) { log("bdmenu: a picture could not be read: $e") }
    }

    private fun sound(l: String) {
        try {
            val f = l.split(' ', limit = 5)
            val ch = f[2].toInt(); val frames = f[3].toInt()
            val file = File(f[4].trim().replace("/tmp/menu", makemkv.menuDir().absolutePath))
            val b = file.readBytes(); file.delete()
            val s = ShortArray(frames * ch) { i -> ((b[i * 2].toInt() and 0xff) or (b[i * 2 + 1].toInt() shl 8)).toShort() }
            onSound?.invoke(s, ch)
        } catch (_: Exception) { }
    }

    fun key(vk: Int) { try { process?.outputStream?.let { it.write("key $vk\n".toByteArray()); it.flush() } } catch (_: Exception) { } }
    fun menu() { try { process?.outputStream?.let { it.write("menu\n".toByteArray()); it.flush() } } catch (_: Exception) { } }

    fun close() {
        opened = false
        try { process?.outputStream?.let { it.write("quit\n".toByteArray()); it.flush() } } catch (_: Exception) { }
        makemkv.stop()
        fifo.delete()
    }
}

/** The stream of [RemoteBd] as the player reads it: plain transport packets, as they come. */
@UnstableApi
class FifoDataSource(private val file: File) : BaseDataSource(false) {
    private var input: FileInputStream? = null
    private var uri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        input = FileInputStream(file)           // (waits for bdmenu to open its end)
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val n = try { input?.read(buffer, offset, length) ?: -1 } catch (_: java.io.IOException) { -1 }
        if (n < 0) return C.RESULT_END_OF_INPUT
        total += n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri
    override fun close() { try { input?.close() } catch (_: Exception) { }; input = null; if (uri != null) { uri = null; transferEnded() } }

    companion object { @Volatile var total = 0L }

    class Factory(private val file: File) : DataSource.Factory {
        override fun createDataSource(): DataSource = FifoDataSource(file)
    }
}
