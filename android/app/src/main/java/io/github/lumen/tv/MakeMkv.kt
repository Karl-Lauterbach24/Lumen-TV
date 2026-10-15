package io.github.lumen.tv

import android.app.Activity
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * MakeMKV (the user's own copy of GuinpinSoft's program, Linux build) on the television:
 *
 * - its Linux files live in the native library folder (the only place Android lets an app run code from) and
 *   in [root]; proot (libproot.so) shows them at their usual places;
 * - the drive: Android hands the USB drive only to the app, so the app keeps the connection and gives its
 *   descriptor to libmmshim.so inside the Linux tree, which plays /dev/sg0 for makemkvcon (SCSI over USB
 *   Bulk-Only); a /sys entry lets makemkvcon find it;
 * - a title is "saved" into a pipe the player reads (libmmshim's stream mode puts MakeMKV's output in order
 *   and gives it a proper Matroska header).
 *
 * The registration key is MakeMKV's: typed in by the user, or the beta key its author posts in the forum.
 */
class MakeMkv(private val activity: Activity, private val log: (String) -> Unit) {
    private val nativeDir = activity.applicationInfo.nativeLibraryDir
    val abi = when {
        nativeDir.endsWith("/arm64") -> "arm64-v8a"
        nativeDir.endsWith("/arm") -> "armeabi-v7a"
        else -> ""
    }
    val available get() = abi.isNotEmpty() && File(nativeDir, "libproot.so").exists()
    private val root = File(activity.filesDir, "makemkv-$abi")
    private val prefs = activity.getSharedPreferences("makemkv", Activity.MODE_PRIVATE)
    private val usb = activity.getSystemService(UsbManager::class.java)

    companion object { var READ_BUFFER_MB = 16 }

    class Title(val id: Int, val name: String, val durationMs: Long, val size: String, val file: String) {
        override fun toString() = "#$id $name ${durationMs / 60000} min $size"
    }

    // ---------------------------------------------------------------- the Linux tree

    private fun libmap(): List<Pair<String, String>> =
        activity.assets.open("makemkv/libmap-$abi.txt").bufferedReader().readLines().mapNotNull { l ->
            val p = l.split('\t'); if (p.size == 2) p[0] to p[1] else null
        }

    private fun ensureTree() {
        if (!File(root, ".extracted").exists()) {
            log("unpacking MakeMKV's data files")
            deleteTree(root); root.mkdirs()
            val tin = org.apache.commons.compress.archivers.tar.TarArchiveInputStream(activity.assets.open("makemkv/rootrest-$abi.tar"))
            var e = tin.nextEntry
            while (e != null) {
                val f = File(root, e.name)
                when {
                    e.isDirectory -> f.mkdirs()
                    e.isFile -> { f.parentFile?.mkdirs(); FileOutputStream(f).use { tin.copyTo(it) } }
                }
                e = tin.nextEntry
            }
            File(root, ".extracted").writeText("1")
        }
        for (d in listOf("tmp", "root", "dev")) File(root, d).mkdirs()
        for (n in listOf("sg0", "sr0")) File(root, "dev/$n").apply { if (!exists()) createNewFile() }
    }

    /** Deletes [f] without following links (the /sys tree links in circles). */
    private fun deleteTree(f: File) {
        if (!java.nio.file.Files.isSymbolicLink(f.toPath()) && f.isDirectory) f.listFiles()?.forEach { deleteTree(it) }
        java.nio.file.Files.deleteIfExists(f.toPath())
    }

    /** The entries a USB optical drive has in Linux's /sys: makemkvcon looks for drives there. */
    private fun fakeSysfs(vendor: String, model: String, rev: String) {
        val sys = File(root, "sys"); deleteTree(sys)
        val dev = File(sys, "devices/platform/lumen-usb/host0/target0:0:0/0:0:0:0"); dev.mkdirs()
        fun w(name: String, v: String) = File(dev, name).writeText(v + "\n")
        w("type", "5"); w("vendor", vendor); w("model", model); w("rev", rev)
        w("scsi_level", "6"); w("state", "running"); w("device_blocked", "0"); w("queue_depth", "1")
        File(dev, "scsi_generic/sg0").mkdirs(); File(dev, "block/sr0").mkdirs()
        File(dev, "scsi_generic/sg0/dev").writeText("21:0\n"); File(dev, "block/sr0/dev").writeText("11:0\n")
        fun link(at: File, target: String) { at.parentFile?.mkdirs(); java.nio.file.Files.createSymbolicLink(at.toPath(), File(target).toPath()) }
        link(File(sys, "bus/scsi/devices/0:0:0:0"), "../../../devices/platform/lumen-usb/host0/target0:0:0/0:0:0:0")
        link(File(sys, "class/scsi_generic/sg0"), "../../devices/platform/lumen-usb/host0/target0:0:0/0:0:0:0/scsi_generic/sg0")
        link(File(sys, "block/sr0"), "../devices/platform/lumen-usb/host0/target0:0:0/0:0:0:0/block/sr0")
        link(File(dev, "scsi_generic/sg0/device"), "../../../0:0:0:0")
        link(File(dev, "block/sr0/device"), "../../../0:0:0:0")
        link(File(dev, "generic"), "scsi_generic/sg0")
    }

    private fun command(args: List<String>, extraEnv: Map<String, String>): ProcessBuilder {
        val cmd = arrayListOf("$nativeDir/libproot.so", "--kill-on-exit", "-0", "-r", root.absolutePath, "-b", "/proc")
        for (n in listOf("null", "zero", "random", "urandom")) { cmd += "-b"; cmd += "/dev/$n" }
        for ((lib, guest) in libmap()) { File(root, guest.trimStart('/')).parentFile?.mkdirs(); cmd += "-b"; cmd += "$nativeDir/$lib:$guest" }
        cmd += listOf("-b", "$nativeDir/libmmshim.so:/usr/lib/libmmshim.so")
        // through glibc's loader: the 32-bit build cannot be started directly under proot
        val ld = if (abi == "arm64-v8a") "/lib/ld-linux-aarch64.so.1" else "/lib/ld-linux-armhf.so.3"
        cmd += listOf(ld, "--preload", "/usr/lib/libmmshim.so", "/usr/bin/makemkvcon")
        cmd += args
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment().apply {
            put("LD_LIBRARY_PATH", nativeDir)
            put("PROOT_LOADER", "$nativeDir/libproot-loader.so")
            put("PROOT_TMP_DIR", File(activity.cacheDir, "prootTmp").apply { mkdirs() }.absolutePath)
            put("HOME", "/root")
            put("GLIBC_TUNABLES", "glibc.pthread.rseq=0")
            put("MMSHIM_LOG", "1")
            put("MMSHIM_EXE", "/usr/bin/makemkvcon")   // or makemkvcon takes the loader for itself and misses its data
            putAll(extraEnv)
        }
        return pb
    }

    // ---------------------------------------------------------------- the registration key

    private val keyPage = "https://forum.makemkv.com/forum/viewtopic.php?f=5&t=1053"
    val keyMode: String get() = prefs.getString("mode", "") ?: ""

    private fun writeKey(key: String) = setting("app_Key", key)

    /** One line of MakeMKV's settings.conf (the others stay). */
    @Synchronized private fun setting(name: String, value: String) {
        val f = File(root, "root/.MakeMKV/settings.conf")
        f.parentFile?.mkdirs()
        val keep = if (f.exists()) f.readLines().filter { it.substringBefore('=').trim() != name } else emptyList()
        f.writeText((keep + "$name = \"$value\"").joinToString("\n") + "\n")
    }

    fun setOwnKey(key: String) {
        ensureTree()
        prefs.edit().putString("mode", "manual").putString("manualKey", key).apply()
        writeKey(key)
        log("MakeMKV: your key is entered (the beta key is no longer fetched)")
    }

    fun ownKey(): String = prefs.getString("manualKey", "") ?: ""

    fun useBetaKey() {
        prefs.edit().putString("mode", "beta").apply()
        kotlin.concurrent.thread { fetchBetaKey() }
    }

    fun refreshBetaKeyIfDue() {
        if (keyMode != "beta") return
        if (System.currentTimeMillis() - prefs.getLong("betaChecked", 0) < 24L * 3600 * 1000) return
        kotlin.concurrent.thread { fetchBetaKey() }
    }

    private fun fetchBetaKey() {
        try {
            ensureTree()
            val c = java.net.URL(keyPage).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 20000; c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Lumen TV)")
            val page = c.inputStream.bufferedReader().use { it.readText() }
            val key = Regex("T-[A-Za-z0-9@_]{60,70}").find(page)?.value
            if (key == null) { log("MakeMKV: the forum page shows no beta key right now"); return }
            prefs.edit().putLong("betaChecked", System.currentTimeMillis()).apply()
            writeKey(key)
            log("MakeMKV: beta key from the forum entered (${key.take(6)}…, checked again every day)")
        } catch (e: Exception) {
            log("MakeMKV: the forum cannot be reached ($e)")
        }
    }

    // ---------------------------------------------------------------- the drive

    private var conn: UsbDeviceConnection? = null
    private var pfd: ParcelFileDescriptor? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var bridgeInfo = ""
    private var server: Thread? = null

    fun drive(): UsbDevice? = usb.deviceList.values.firstOrNull { d ->
        (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE }
    }

    /** Takes the drive (the app must have its permission) and serves its descriptor to libmmshim. */
    fun openBridge(device: UsbDevice): Boolean {
        closeBridge()
        val intf = (0 until device.interfaceCount).map { device.getInterface(it) }
            .first { it.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE }
        for (i in 0 until intf.endpointCount) {
            val e = intf.getEndpoint(i)
            if (e.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (e.direction == UsbConstants.USB_DIR_IN) epIn = e else epOut = e
        }
        val c = usb.openDevice(device)
        val why = when {
            !usb.hasPermission(device) -> "no permission for the drive"
            c == null -> "openDevice failed"
            epIn == null || epOut == null -> "no bulk endpoints"
            !c.claimInterface(intf, true) -> "claimInterface failed"
            else -> null
        }
        if (why != null || c == null) { log("bridge: the drive could not be opened: $why"); c?.close(); return false }
        conn = c
        pfd = ParcelFileDescriptor.fromFd(c.fileDescriptor)
        bridgeInfo = "${epIn!!.address} ${epOut!!.address} ${intf.id}"
        log("bridge: ${device.productName} at ${device.deviceName}, endpoints $bridgeInfo")
        if (server == null) server = kotlin.concurrent.thread(isDaemon = true) {
            try {
                val s = android.net.LocalServerSocket("lumen.mmshim.usb")
                while (true) {
                    val a = s.accept()
                    try {
                        val fd = pfd ?: continue
                        a.setFileDescriptorsForSend(arrayOf(fd.fileDescriptor))
                        a.outputStream.write((bridgeInfo + "\n").toByteArray()); a.outputStream.flush()
                    } finally { a.close() }
                }
            } catch (e: Exception) { log("bridge server stopped: $e") }
        }
        return true
    }

    /** Asks the drive who it is (after a USB reset if it does not answer) and makes its /sys entry. */
    fun identify(): Boolean {
        var id = inquiry()
        if (id == null) {
            log("drive: no answer to INQUIRY, USB mass storage reset")
            resetRecovery()
            id = inquiry()
        }
        log("drive: ${id?.let { "${it.first.trim()} ${it.second.trim()} ${it.third.trim()}" } ?: "no answer to INQUIRY"}")
        if (id == null) return false
        ensureTree()
        fakeSysfs(id.first, id.second, id.third)
        return true
    }

    fun closeBridge() {
        pfd?.close(); pfd = null
        conn?.close(); conn = null
    }

    private fun inquiry(): Triple<String, String, String>? {
        val c = conn ?: return null
        val cbw = java.nio.ByteBuffer.allocate(31).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        cbw.putInt(0x43425355).putInt(0x7001).putInt(36).put(0x80.toByte()).put(0).put(6)
        cbw.put(byteArrayOf(0x12, 0, 0, 0, 36, 0))
        if (c.bulkTransfer(epOut, cbw.array(), 31, 5000) != 31) return null
        val d = ByteArray(36)
        val n = c.bulkTransfer(epIn, d, 36, 5000)
        c.bulkTransfer(epIn, ByteArray(13), 13, 5000)
        if (n < 36) return null
        return Triple(String(d, 8, 8, Charsets.ISO_8859_1), String(d, 16, 16, Charsets.ISO_8859_1), String(d, 32, 4, Charsets.ISO_8859_1))
    }

    /** One SCSI command over Bulk-Only from the app itself (makemkvcon must not be running); the CSW status or -1. */
    private fun scsi(cdb: ByteArray, inLen: Int = 0, timeout: Int = 10000): Pair<Int, ByteArray> {
        val c = conn ?: return -1 to ByteArray(0)
        val cbw = java.nio.ByteBuffer.allocate(31).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        cbw.putInt(0x43425355).putInt(0x7100 + (tag++ and 0xff)).putInt(inLen).put(if (inLen > 0) 0x80.toByte() else 0).put(0).put(cdb.size.toByte())
        cbw.put(cdb)
        if (c.bulkTransfer(epOut, cbw.array(), 31, 5000) != 31) { resetRecovery(); return -1 to ByteArray(0) }
        val d = ByteArray(inLen)
        if (inLen > 0 && c.bulkTransfer(epIn, d, inLen, timeout) < 0) clearHalt(epIn!!)
        val csw = ByteArray(13)
        var n = c.bulkTransfer(epIn, csw, 13, timeout)
        if (n != 13) { clearHalt(epIn!!); n = c.bulkTransfer(epIn, csw, 13, timeout) }
        if (n != 13 || csw[0] != 'U'.code.toByte() || csw[3] != 'S'.code.toByte()) { resetRecovery(); return -1 to d }
        return csw[12].toInt() to d
    }
    private var tag = 0

    private fun clearHalt(ep: UsbEndpoint) { conn?.controlTransfer(0x02, 0x01, 0, ep.address, null, 0, 5000) }

    private fun resetRecovery() {
        val c = conn ?: return
        c.controlTransfer(0x21, 0xff, 0, 0, null, 0, 5000)
        epIn?.let { clearHalt(it) }; epOut?.let { clearHalt(it) }
    }

    private fun sense(): String {
        val (st, d) = scsi(byteArrayOf(0x03, 0, 0, 0, 18, 0), 18, 5000)
        return if (st == 0) "%x/%02x/%02x".format(d[2].toInt() and 0x0f, d[12].toInt() and 0xff, d[13].toInt() and 0xff) else "none ($st)"
    }

    /**
     * What helps a drive that answers reads with "TIMEOUT ON LOGICAL UNIT": the disc out and in again.
     * True when the drive is ready again afterwards.
     */
    fun ejectAndReload(): Boolean {
        proc?.let { it.destroy(); it.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) }
        proc = null
        Thread.sleep(500)
        log("drive: unlocking the tray: " + scsi(byteArrayOf(0x1e, 0, 0, 0, 0, 0)).first)
        val ej = scsi(byteArrayOf(0x1b, 0, 0, 0, 0x02, 0), 0, 30000).first
        log("drive: eject: $ej" + if (ej != 0) ", sense ${sense()}" else "")
        Thread.sleep(3000)
        val ld = scsi(byteArrayOf(0x1b, 0, 0, 0, 0x03, 0), 0, 30000).first
        log("drive: load: $ld" + if (ld != 0) ", sense ${sense()}" else "")
        val until = System.currentTimeMillis() + 90_000
        var last = ""
        while (System.currentTimeMillis() < until) {
            val st = scsi(byteArrayOf(0, 0, 0, 0, 0, 0)).first
            if (st == 0) { log("drive: ready again after the reload"); return true }
            val sn = sense()
            if (sn != last) { log("drive: not ready: $st, sense $sn"); last = sn }
            Thread.sleep(1000)
        }
        log("drive: not ready 90 s after the reload")
        return false
    }

    // ---------------------------------------------------------------- running makemkvcon

    @Volatile private var proc: Process? = null
    /** The drive answered reads with timeouts (makemkvcon was stopped): see [ejectAndReload]. */
    @Volatile var driveStuck = false
        private set
    /** Called (on makemkvcon's output thread) when the drive got stuck. */
    var onStuck: (() -> Unit)? = null

    private fun pipeOutput(p: Process, onLine: (String) -> Unit = {}) {
        var prg = 0
        var timeouts = 0
        driveStuck = false
        p.inputStream.bufferedReader().forEachLine { l ->
            onLine(l)
            if (l.startsWith("MSG:2003") && l.contains("TIMEOUT ON LOGICAL UNIT") && ++timeouts == 3) {
                log("drive: 3 read timeouts: makemkvcon is stopped")
                driveStuck = true
                p.destroy()
                onStuck?.invoke()
            }
            when {
                l.startsWith("PRGV") || l.startsWith("PRGC") || l.startsWith("PRGT") -> if (prg++ % 50 == 0) log(l)
                l.startsWith("MSG:3025") || l.startsWith("MSG:3307") || l.startsWith("MSG:3309") -> { }   // (title list details)
                l.startsWith("TINFO") || l.startsWith("SINFO") || l.startsWith("CINFO") || l.startsWith("DRV:") && l.contains(",256,") -> { }
                else -> log(l.take(200))
            }
        }
    }

    /** The disc's titles (makemkvcon info). */
    fun titles(): List<Title> {
        val t0 = System.currentTimeMillis()
        val p = command(listOf("-r", "--cache=16", "info", "disc:0"), emptyMap()).start()
        proc = p
        val info = HashMap<Int, HashMap<Int, String>>()
        pipeOutput(p) { l ->
            val m = Regex("""TINFO:(\d+),(\d+),\d+,"(.*)"""").find(l) ?: return@pipeOutput
            info.getOrPut(m.groupValues[1].toInt()) { HashMap() }[m.groupValues[2].toInt()] = m.groupValues[3]
        }
        log("info: exit ${p.waitFor()} after ${(System.currentTimeMillis() - t0) / 1000} s")
        return info.toSortedMap().map { (id, a) ->
            val parts = (a[9] ?: "0:0:0").split(':').map { it.toLongOrNull() ?: 0 }
            val ms = ((parts.getOrElse(0) { 0 } * 60 + parts.getOrElse(1) { 0 }) * 60 + parts.getOrElse(2) { 0 }) * 1000
            Title(id, a[2] ?: "Title $id", ms, a[10] ?: "", a[27] ?: "title_t%02d.mkv".format(id))
        }
    }

    /** Starts saving [t] into a pipe and returns the pipe; the stream starts with a playable Matroska header. */
    fun stream(t: Title): File {
        val out = File(root, "tmp/out"); out.deleteRecursively(); out.mkdirs()
        val fifo = File(out, t.file)
        android.system.Os.mkfifo(fifo.absolutePath, 0x1b6)
        // a small read buffer: the television has little memory, and swapping makes the picture stutter
        setting("io_RBufSizeMB", READ_BUFFER_MB.toString())
        val p = command(listOf("-r", "--cache=$READ_BUFFER_MB", "mkv", "disc:0", t.id.toString(), "/tmp/out"),
            mapOf("MMSHIM_STREAM" to "/tmp/out/", "MMSHIM_DURATION_MS" to t.durationMs.toString())).start()
        proc = p
        kotlin.concurrent.thread(isDaemon = true) {
            // memory of the television and of makemkvcon, every 15 s (debug)
            while (p.isAlive) {
                try {
                    val mi = File("/proc/meminfo").readLines().associate { l -> l.substringBefore(':') to (l.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0) }
                    val rt = Runtime.getRuntime()
                    val rss = try { File("/proc/${pidOf(p)}/status").readLines().firstOrNull { it.startsWith("VmRSS") }?.substringAfter(':')?.trim() } catch (_: Exception) { null }
                    log("memory: available ${mi["MemAvailable"]?.div(1024)} MB, swap free ${mi["SwapFree"]?.div(1024)} MB, proot ${rss ?: "?"}, app heap ${(rt.totalMemory() - rt.freeMemory()) shr 20} MB, native ${android.os.Debug.getNativeHeapAllocatedSize() shr 20} MB")
                } catch (_: Exception) { }
                Thread.sleep(15000)
            }
        }
        kotlin.concurrent.thread(isDaemon = true) {
            pipeOutput(p)
            log("makemkvcon finished: exit ${p.waitFor()}")
            // a reader still waiting for the pipe to be opened gets its end
            try {
                val fd = android.system.Os.open(fifo.absolutePath, android.system.OsConstants.O_WRONLY or android.system.OsConstants.O_NONBLOCK, 0)
                android.system.Os.close(fd)
            } catch (_: Exception) { }
        }
        log("streaming $t")
        return fifo
    }

    private fun pidOf(p: Process): Int = try {
        p.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(p)
    } catch (_: Exception) { -1 }

    fun stop() {
        proc?.destroy(); proc = null
    }
}

/** The pipe MakeMKV writes into, read in order: the player gets it as a stream of unknown length. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PipeDataSource(private val pipe: PipeStream) : androidx.media3.datasource.BaseDataSource(true) {
    private var uri: android.net.Uri? = null

    override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        pipe.seekTo(dataSpec.position)
        transferStarted(dataSpec)
        return androidx.media3.common.C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val n = pipe.read(buffer, offset, length)
        if (n < 0) return androidx.media3.common.C.RESULT_END_OF_INPUT
        bytesTransferred(n)
        return n
    }

    override fun getUri() = uri
    override fun close() { if (uri != null) { uri = null; transferEnded() } }
}

/** One pass over the pipe; the player may reopen at the position it got to (or ahead of it), not behind. */
class PipeStream(private val file: File, private val stats: (String) -> Unit) {
    @Volatile private var input: InputStream? = null
    var position = 0L; private set
    private var t0 = 0L; private var tl = 0L; private var lastPos = 0L

    /** Waits until MakeMKV has written something (it reads the disc's structure first); false: it ended without. */
    fun awaitStart(): Boolean {
        val i = java.io.BufferedInputStream(java.io.FileInputStream(file), 1 shl 20)
        i.mark(1)
        val first = i.read()
        i.reset()
        synchronized(this) { input = i; t0 = System.currentTimeMillis(); tl = t0 }
        return first >= 0
    }

    @Synchronized fun seekTo(pos: Long) {
        if (input == null) { input = java.io.FileInputStream(file); t0 = System.currentTimeMillis(); tl = t0 }
        if (pos < position) throw java.io.IOException("the MakeMKV stream cannot go back (asked for $pos, at $position)")
        while (position < pos) {
            val s = input!!.skip(pos - position)
            if (s <= 0) throw java.io.EOFException()
            position += s
        }
    }

    @Synchronized fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = input?.read(b, off, len) ?: -1
        if (n > 0) position += n
        val now = System.currentTimeMillis()
        if (now - tl >= 5000) {
            stats("stream: %.1f MB, now %.2f MB/s, average %.2f MB/s".format(java.util.Locale.ROOT, position / 1e6, (position - lastPos) / 1e3 / (now - tl), position / 1e3 / (now - t0)))
            tl = now; lastPos = position
        }
        return n
    }

    /** (not synchronized: a read may be waiting for MakeMKV) */
    fun close() { try { input?.close() } catch (_: Exception) { }; input = null }
}
