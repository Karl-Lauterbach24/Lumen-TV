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

    /** A stream of a title, from MakeMKV's SINFO: [type] "audio" / "text" / "video", [lang] ISO 639-1 where known. */
    class Stream(val type: String, val lang: String, val langName: String, val codec: String, val channels: Int,
                 val desc: String = "", val default: Boolean = false, val bitrate: String = "") {
        /** (MakeMKV calls a forced subtitle track so in its description) */
        val forced get() = desc.contains("forced", true)
        val commentary get() = desc.contains("comment", true)
    }

    class Title(val id: Int, val name: String, val durationMs: Long, val size: String, val file: String, val chapters: Int = 0,
                val streams: List<Stream> = emptyList(), val source: String = "", val segments: List<Int> = emptyList()) {
        override fun toString() = "#$id $name ${durationMs / 60000} min $size"
        val minutes get() = (durationMs / 60000).toInt()
    }

    /** What the disc is, from its titles and name. [main]: the film, or the episodes in order; [all]: a "play all" title. */
    class Disc(val name: String, val kind: String, val season: Int, val discNo: Int,
               val main: List<Title>, val all: Title?, val extras: List<Title>) {
        /** "Doctor Who · Season 1 · Disc 3" */
        fun label(de: Boolean): String = listOfNotNull(name.ifEmpty { null },
            if (season > 0) (if (de) "Staffel $season" else "Season $season") else null,
            if (discNo > 0) "Disc $discNo" else null).joinToString(" · ")
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

    private fun command(args: List<String>, extraEnv: Map<String, String>, program: String = "/usr/bin/makemkvcon"): ProcessBuilder {
        val cmd = arrayListOf("$nativeDir/libproot.so", "--kill-on-exit", "-0", "-r", root.absolutePath, "-b", "/proc")
        for (n in listOf("null", "zero", "random", "urandom")) { cmd += "-b"; cmd += "/dev/$n" }
        for ((lib, guest) in libmap()) { File(root, guest.trimStart('/')).parentFile?.mkdirs(); cmd += "-b"; cmd += "$nativeDir/$lib:$guest" }
        cmd += listOf("-b", "$nativeDir/libmmshim.so:/usr/lib/libmmshim.so")
        // through glibc's loader: the 32-bit build cannot be started directly under proot
        val ld = if (abi == "arm64-v8a") "/lib/ld-linux-aarch64.so.1" else "/lib/ld-linux-armhf.so.3"
        cmd += listOf(ld, "--preload", "/usr/lib/libmmshim.so", program)
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

    // ---------------------------------------------------------------- the picture of a disc
    fun cover(label: String): String = prefs.getString("cover:$label", "") ?: ""
    fun setCover(label: String, url: String) { prefs.edit().putString("cover:$label", url).apply() }
    var tmdbKey: String
        get() = prefs.getString("tmdbKey", "") ?: ""
        set(v) { prefs.edit().putString("tmdbKey", v.trim()).apply() }

    // ---------------------------------------------------------------- the tracks chosen for one disc

    /** The index of the audio / subtitle stream chosen for a disc ("" = by language, "off" = no subtitles). */
    fun pick(label: String, type: String): String = prefs.getString("pick:$label:$type", "") ?: ""
    fun setPick(label: String, type: String, v: String) { prefs.edit().putString("pick:$label:$type", v).apply() }

    // ---------------------------------------------------------------- audio and subtitles chosen before playing

    /** "" = automatic (the television's language), else an ISO 639-1 code */
    var audioLang: String
        get() = prefs.getString("audioLang", "") ?: ""
        set(v) { prefs.edit().putString("audioLang", v).apply() }
    /** "" = automatic, "off", or an ISO 639-1 code */
    var subLang: String
        get() = prefs.getString("subLang", "") ?: ""
        set(v) { prefs.edit().putString("subLang", v).apply() }

    /** The languages of [type] streams in [titles], in the order they first appear. */
    fun languages(titles: List<Title>, type: String): List<String> =
        titles.flatMap { t -> t.streams.filter { it.type == type && it.lang.isNotEmpty() }.map { it.lang } }.distinct()

    // ---------------------------------------------------------------- the cache between MakeMKV and the player

    /** "auto", "ram" or "storage" */
    var cacheMode: String
        get() = prefs.getString("cacheMode", "auto") ?: "auto"
        set(v) { prefs.edit().putString("cacheMode", v).apply() }
    /** MB; 0 = chosen by the app */
    var cacheMb: Int
        get() = prefs.getInt("cacheMb", 0)
        set(v) { prefs.edit().putInt("cacheMb", v).apply() }

    class CachePlan(val file: File?, val bytes: Long, val why: String) {
        override fun toString() = "${if (file == null) "memory" else "storage"}, ${bytes shr 20} MB"
    }

    private val cacheDir get() = File(activity.cacheDir, "makemkv-stream")

    /** Removes the cache file of an earlier film (one that ended with the app). */
    fun clearCache() { cacheDir.listFiles()?.forEach { it.delete() } }

    /**
     * Automatic: memory, a fifth of what is free (32 to 512 MB). The storage only where memory is short and
     * the storage is fast enough: measured on a small television (929 MB, swapping onto its eMMC), a cache on
     * its storage stalled the picture for a minute, while 48 MB of memory were enough once the drive reads
     * at 10+ MB/s. The user's choice of place and size first.
     */
    fun cachePlan(): CachePlan {
        val am = activity.getSystemService(android.app.ActivityManager::class.java)
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        cacheDir.mkdirs()
        val freeDisk = cacheDir.usableSpace
        val mb = 1L shl 20
        fun round(b: Long) = (b / (16 * mb)) * 16 * mb
        // (a television with about 1 GB has no memory to spare: MakeMKV, the player and the page need it)
        val ramAuto = if (mi.totalMem < 1536 * mb) 32 * mb else round((mi.availMem / 5).coerceIn(32 * mb, 512 * mb))
        val diskAuto = round((freeDisk - 300 * mb).coerceIn(0, 1024 * mb))
        val bigRam = mi.totalMem >= 2560 * mb && !mi.lowMemory
        val mode = when (cacheMode) {
            "ram" -> "ram"
            "storage" -> if (diskAuto >= 64 * mb || (cacheMb > 0 && freeDisk - cacheMb * mb > 100 * mb)) "storage" else "ram"
            else -> if (bigRam || mi.availMem >= 160 * mb || diskAuto < 128 * mb || isSwapping()) "ram" else "storage"
        }
        val why = "television: ${mi.totalMem / mb} MB memory (${mi.availMem / mb} free), ${freeDisk / mb} MB storage free; setting ${cacheMode}/${if (cacheMb > 0) "$cacheMb MB" else "auto"}"
        return if (mode == "ram") {
            val want = if (cacheMb > 0) cacheMb * mb else ramAuto
            CachePlan(null, want.coerceIn(16 * mb, 1024 * mb), why)
        } else {
            val want = if (cacheMb > 0) minOf(cacheMb * mb, freeDisk - 100 * mb) else diskAuto
            CachePlan(File(cacheDir, "stream.cache"), want.coerceAtLeast(32 * mb), why)
        }
    }

    /** The television swaps (its storage is then busy enough) */
    private fun isSwapping(): Boolean = try {
        val m = File("/proc/meminfo").readLines().associate { l -> l.substringBefore(':') to (l.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0) }
        (m["SwapTotal"] ?: 0) - (m["SwapFree"] ?: 0) > 64 * 1024
    } catch (_: Exception) { false }

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
        keyUnknown = false
        // (stopping makemkvcon interrupts the read: that ends the output, it is no error)
        try {
            p.inputStream.bufferedReader().forEachLine { l ->
                onLine(l)
                if (l.startsWith("MSG:3303")) keyUnknown = true       // "The volume key is unknown for this disc"
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
        } catch (_: java.io.IOException) { }
    }

    /** The last run of makemkvcon said it does not know the disc's key (a disc newer than MakeMKV's list). */
    @Volatile var keyUnknown = false
    fun noKey(label: String) = prefs.getBoolean("nokey:$label", false)
    fun setNoKey(label: String, v: Boolean) { prefs.edit().putBoolean("nokey:$label", v).apply() }

    private var lastIdentify = ""

    // the titles of a disc are kept (by its label) so that the disc is known at once the next time
    fun saveTitles(label: String, titles: List<Title>) {
        if (label.isEmpty() || titles.isEmpty()) return
        val o = org.json.JSONObject().put("info", org.json.JSONObject(discInfo.mapKeys { it.key.toString() }))
        o.put("titles", org.json.JSONArray(titles.map { t ->
            org.json.JSONObject().put("id", t.id).put("name", t.name).put("ms", t.durationMs).put("size", t.size).put("file", t.file)
                .put("ch", t.chapters).put("src", t.source).put("segs", org.json.JSONArray(t.segments))
                .put("streams", org.json.JSONArray(t.streams.map { x -> org.json.JSONObject().put("t", x.type).put("l", x.lang).put("n", x.langName).put("c", x.codec).put("ch", x.channels).put("d", x.desc).put("df", x.default).put("b", x.bitrate) }))
        }))
        prefs.edit().putString("disc:$label", o.toString()).apply()
    }

    // the names a disc's menu gives its tracks (JSON, see MenuReader.Names); "tried": the menu has been looked at (found or not)
    fun menuNames(label: String): String? = prefs.getString("menu:$label", null)
    fun saveMenuNames(label: String, json: String) { prefs.edit().putString("menu:$label", json).apply() }
    fun menuTried(label: String) = prefs.getBoolean("menutried:$label", false)
    fun setMenuTried(label: String) { prefs.edit().putBoolean("menutried:$label", true).apply() }

    fun forgetTitles(label: String) { prefs.edit().remove("disc:$label").remove("menu:$label").remove("menutried:$label").apply() }

    fun hasTitles(label: String) = prefs.contains("disc:$label")

    fun loadTitles(label: String): List<Title>? = try {
        val o = org.json.JSONObject(prefs.getString("disc:$label", null) ?: return null)
        discInfo.clear()
        o.getJSONObject("info").let { i -> i.keys().forEach { k -> discInfo[k.toInt()] = i.getString(k) } }
        val a = o.getJSONArray("titles")
        (0 until a.length()).map { i ->
            val t = a.getJSONObject(i)
            Title(t.getInt("id"), t.getString("name"), t.getLong("ms"), t.getString("size"), t.getString("file"), t.getInt("ch"),
                t.getJSONArray("streams").let { sa -> (0 until sa.length()).map { j -> sa.getJSONObject(j).let { x -> Stream(x.getString("t"), x.getString("l"), x.getString("n"), x.getString("c"), x.getInt("ch"), x.optString("d"), x.optBoolean("df"), x.optString("b")) } } },
                t.getString("src"), t.getJSONArray("segs").let { ga -> (0 until ga.length()).map { ga.getInt(it) } })
        }
    } catch (_: Exception) { null }

    /** MakeMKV's CINFO of the last disc read: 2 = name, 32 = volume name. */
    val discInfo = HashMap<Int, String>()

    /**
     * Film or episodes? Episodes: two or more titles of 15–75 minutes of similar length (within a third of each
     * other); a longer title that is about their sum is the "play all". A film: the longest title otherwise.
     * Short titles are extras.
     */
    fun identify(titles: List<Title>): Disc {
        val volume = discInfo[32].orEmpty()
        val raw = (discInfo[2]?.takeIf { it.isNotBlank() } ?: volume).replace('_', ' ').trim()
        val season = Regex("""(?i)(?:\bST|\bS|season|staffel|series)\s*0*(\d{1,2})\b""").find(raw)?.groupValues?.get(1)?.toInt() ?: 0
        val discNo = Regex("""(?i)(?:\bBD|\bdisc|\bdisk|\bD)\s*0*(\d{1,2})\b""").find(raw)?.groupValues?.get(1)?.toInt() ?: 0
        // the name without season / disc markers: "Doctor Who - Staffel 1 - Disc 3" -> "Doctor Who"
        val name = raw.replace(Regex("""(?i)[-–\s]*(?:\bST|\bS|season|staffel|series|\bBD|\bdisc|\bdisk)\s*0*\d{1,2}\b.*$"""), "").trim(' ', '-', '–')
            .split(' ').joinToString(" ") { w -> if (w.length >= 3 && w == w.uppercase()) w.lowercase().replaceFirstChar { it.uppercase() } else w }
        val real = titles.filter { it.minutes >= 1 }
        val eps = real.filter { it.minutes in 15..75 }
        var group: List<Title> = emptyList()
        if (eps.size >= 2) {
            // the largest set of similar lengths
            val sorted = eps.sortedBy { it.durationMs }
            var best = emptyList<Title>()
            for (i in sorted.indices) {
                val set = sorted.filter { it.durationMs >= sorted[i].durationMs && it.durationMs <= sorted[i].durationMs * 4 / 3 }
                if (set.size > best.size) best = set
            }
            if (best.size >= 2) group = best.sortedBy { it.id }
        }
        if (group.isNotEmpty()) {
            val sum = group.sumOf { it.durationMs }
            val all = real.firstOrNull { it !in group && it.durationMs >= sum * 92 / 100 && it.durationMs <= sum * 108 / 100 }
            // the order of the episodes: the "play all" title runs through them in order (its clips), else the
            // order of the playlists' file names; MakeMKV's title numbers say nothing about it
            val pos = { t: Title -> val f = t.segments.firstOrNull(); if (all != null && f != null) all.segments.indexOf(f).takeIf { it >= 0 } else null }
            if (group.all { pos(it) != null }) group = group.sortedBy { pos(it) }
            else if (group.all { it.source.isNotEmpty() }) group = group.sortedWith(compareBy({ it.source }, { it.id }))
            val extras = real.filter { it !in group && it != all && it.minutes >= 2 }.sortedByDescending { it.durationMs }
            val msg = "identify: episodes " + group.joinToString(" | ") { "#${it.id} ${it.source} segs ${it.segments}" } + (all?.let { " ; all #${it.id} segs ${it.segments}" } ?: " ; no play-all")
            if (msg != lastIdentify) { lastIdentify = msg; log(msg) }
            return Disc(name, "episodes", season, discNo, group, all, extras)
        }
        val film = real.maxByOrNull { it.durationMs }
        val extras = real.filter { it != film && it.minutes >= 2 }.sortedByDescending { it.durationMs }
        return Disc(name, "movie", season, discNo, listOfNotNull(film), null, extras)
    }

    /** The disc's titles (makemkvcon info). */
    fun titles(): List<Title> {
        val t0 = System.currentTimeMillis()
        val p = command(listOf("-r", "--cache=16", "info", "disc:0"), emptyMap()).start()
        proc = p
        val info = HashMap<Int, HashMap<Int, String>>()
        val sinfo = HashMap<Int, java.util.TreeMap<Int, HashMap<Int, String>>>()
        discInfo.clear()
        pipeOutput(p) { l ->
            if (l.startsWith("SINFO:2,")) log("sinfo $l")   // (the streams of one title, to see what MakeMKV says about them)
            Regex("""SINFO:(\d+),(\d+),(\d+),\d+,"(.*)"""").find(l)?.let {
                sinfo.getOrPut(it.groupValues[1].toInt()) { java.util.TreeMap() }.getOrPut(it.groupValues[2].toInt()) { HashMap() }[it.groupValues[3].toInt()] = it.groupValues[4]
            }
            Regex("""CINFO:(\d+),\d+,"(.*)"""").find(l)?.let { discInfo[it.groupValues[1].toInt()] = it.groupValues[2] }
            val m = Regex("""TINFO:(\d+),(\d+),\d+,"(.*)"""").find(l) ?: return@pipeOutput
            info.getOrPut(m.groupValues[1].toInt()) { HashMap() }[m.groupValues[2].toInt()] = m.groupValues[3]
        }
        log("info: exit ${p.waitFor()} after ${(System.currentTimeMillis() - t0) / 1000} s")
        return info.toSortedMap().map { (id, a) ->
            val parts = (a[9] ?: "0:0:0").split(':').map { it.toLongOrNull() ?: 0 }
            val ms = ((parts.getOrElse(0) { 0 } * 60 + parts.getOrElse(1) { 0 }) * 60 + parts.getOrElse(2) { 0 }) * 1000
            Title(id, a[2] ?: "Title $id", ms, a[10] ?: "", a[27] ?: "title_t%02d.mkv".format(id), a[8]?.toIntOrNull() ?: 0,
                sinfo[id]?.values?.map { v ->
                    val type = when (v[1]) { "Audio" -> "audio"; "Subtitles" -> "text"; "Video" -> "video"; else -> v[1].orEmpty().lowercase() }
                    val code = v[3].orEmpty()
                    Stream(type, if (code.isEmpty()) "" else androidx.media3.common.util.Util.normalizeLanguageCode(code) ?: code,
                        v[4].orEmpty(), v[6] ?: v[7].orEmpty(), v[14]?.toIntOrNull() ?: 0, v[30].orEmpty(), v[38] == "d", v[13].orEmpty())
                }.orEmpty(), a[16].orEmpty(), a[26].orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() })
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

    /**
     * makemkvcon's own process: a child of proot, so a little above proot's pid. (Not by listing /proc: this
     * television has an entry there whose name is not valid UTF-8, and listing it aborts a debug build.)
     */
    @Volatile private var mkPid = -1
    private fun makemkvconPid(): Int {
        val known = mkPid
        if (known > 0 && cmdline(known).contains("/usr/bin/makemkvcon")) return known
        val base = proc?.let { pidOf(it) } ?: return -1
        if (base <= 0) return -1
        for (pid in base + 1..base + 400) {
            val c = cmdline(pid)
            if (c.contains("/usr/bin/makemkvcon") && !c.startsWith(nativeDir)) { mkPid = pid; return pid }
        }
        return -1
    }
    private fun cmdline(pid: Int) = try { File("/proc/$pid/cmdline").readText() } catch (_: Exception) { "" }

    /**
     * Holds makemkvcon while the cache is full: waiting for room it keeps a core busy in the kernel, which
     * the picture of a small television needs. (SIGSTOP / SIGCONT; proot passes them on.)
     */
    fun hold(on: Boolean) {
        val pid = makemkvconPid()
        if (pid <= 0) return
        android.os.Process.sendSignal(pid, if (on) 19 else 18)
    }

    private fun pidOf(p: Process): Int = try {
        p.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(p)
    } catch (_: Exception) { -1 }

    /** bdmenu (see makemkv-native/bdmenu): plays the disc's menu through libbluray and libmmbd; its stdout is the menu's events. */
    fun startMenu(more: List<String> = emptyList()): Process {
        File(root, "tmp/menu").apply { if (more.isEmpty()) listFiles()?.forEach { it.delete() }; mkdirs() }
        val p = command(listOf("/dev/sg0", "/tmp/menu") + more, mapOf("LIBAACS_PATH" to "libmmbd", "LIBBDPLUS_PATH" to "libmmbd", "MAKEMKVCON" to "/usr/bin/makemkvcon", "BDM_PRELOAD" to "/usr/lib/libmmshim.so", "MMBD_TRACE" to "1"), "/usr/bin/bdmenu").start()
        proc = p
        return p
    }

    fun menuDir(): File = File(root, "tmp/menu")

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
        started = true
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
    override fun close() { uri = null; if (started) { started = false; transferEnded() } }
    private var started = false
}

/**
 * Where MakeMKV's stream waits for the player: a ring of [capacity] bytes in memory (outside the Java heap) or
 * in a file on the television's storage ([file]). A thread fills it from the pipe as fast as MakeMKV and the
 * drive deliver, so a drive that pauses for a few seconds is not seen by the player. A quarter of the ring
 * keeps what was played (the player may go back that far); the rest is read ahead.
 */
private const val PIN_MAX = 48L shl 20
private const val HEAD_START = 48L shl 20
private const val STORAGE_RATE = 4L shl 20

class PipeStream(private val fifo: File, private val capacity: Long, private val file: File?, private val stats: (String) -> Unit,
                 private val hold: (Boolean) -> Unit = {}) {
    private val lock = Object()
    private val mem: java.nio.ByteBuffer? = if (file == null) java.nio.ByteBuffer.allocateDirect(capacity.toInt()) else null
    private val disk: java.nio.channels.FileChannel? = file?.let { java.io.RandomAccessFile(it, "rw").channel }
    private val keepBehind = capacity * 2 / 5
    @Volatile private var written = 0L
    @Volatile private var eof = false
    @Volatile private var closed = false
    @Volatile private var input: InputStream? = null
    /** where the player reads */
    @Volatile var position = 0L; private set
    private var t0 = 0L; private var tl = 0L; private var lastPos = 0L; private var lastWritten = 0L
    private var minAhead = Long.MAX_VALUE

    fun ahead(): Long = written - position

    /** The film has only just begun: all of it is still in the cache, so the player can start again from the beginning. */
    val pinned get() = position < pin - (1L shl 20)
    private var started = false
    private var fillT0 = System.currentTimeMillis()
    private var fillB0 = 0L
    private var holds = 0
    /**
     * How far the fill may write. While the film has just begun (the first 48 MB) nothing is overwritten:
     * the player starts again from the beginning when a track is chosen once the tracks are known, and a stream
     * cannot be asked for twice.
     */
    private val pin = minOf(PIN_MAX, capacity * 3 / 4)
    private fun limit() = if (position < pin) capacity else position - keepBehind.coerceAtMost(position) + capacity

    val where get() = if (file == null) "memory" else "storage"

    /** Starts filling and waits until MakeMKV has written something; false: it ended without. */
    fun awaitStart(): Boolean {
        val i = java.io.FileInputStream(fifo)       // (waits for MakeMKV to open the pipe)
        input = i
        t0 = System.currentTimeMillis(); tl = t0
        kotlin.concurrent.thread(isDaemon = true, name = "makemkv-cache") { fill(i) }
        synchronized(lock) { while (written == 0L && !eof && !closed) lock.wait(1000) }
        return written > 0
    }

    private fun fill(i: InputStream) {
        val buf = ByteArray(256 * 1024)
        try {
            while (!closed) {
                // room: up to capacity, minus what is kept behind the player. Full: MakeMKV is held
                // until half of the read-ahead has been played
                var held = false
                synchronized(lock) {
                    if (written + buf.size > limit()) {
                        held = true; holds++; hold(true)
                        while (!closed && written - position > (limit() - position) / 2) lock.wait(500)
                    }
                }
                if (held) hold(false)
                // on storage: no faster than the flash can write beside the player's reading (and the
                // television's swapping), and only a head start until the player has begun
                if (file != null) {
                    synchronized(lock) { while (!closed && !started && written >= HEAD_START) lock.wait(200) }
                    val due = fillT0 + (written - fillB0) * 1000 / STORAGE_RATE
                    val now = System.currentTimeMillis()
                    if (due > now) Thread.sleep(minOf(due - now, 1000))
                    if (now - fillT0 > 10_000) { fillT0 = now; fillB0 = written }
                }
                if (closed) break
                val n = i.read(buf)
                if (n < 0) break
                put(written, buf, n)
                indexClusters(buf, n)
                synchronized(lock) { written += n; lock.notifyAll() }
            }
        } catch (e: Exception) {
            if (!closed) stats("cache: filling stopped: $e")
        } finally {
            synchronized(lock) { eof = true; lock.notifyAll() }
        }
    }

    // ---- an index of the stream's Matroska clusters (time -> position), so that the player can go back to the one
    // before the picture it shows (to change a track, or to skip back a few seconds) as long as it is still in the cache
    private val idxMs = ArrayList<Long>()
    private val idxPos = ArrayList<Long>()
    private var tail = ByteArray(0)
    private var lastCluster = -1L
    private val CLUSTER = byteArrayOf(0x1f, 0x43, 0xb6.toByte(), 0x75)

    /** Looks for clusters in the [n] bytes just read (written is still the position of their first byte). */
    private fun indexClusters(buf: ByteArray, n: Int) {
        val w = ByteArray(tail.size + n)
        System.arraycopy(tail, 0, w, 0, tail.size)
        System.arraycopy(buf, 0, w, tail.size, n)
        val base = written - tail.size
        var i = 0
        val last = w.size - 4
        while (i <= last) {
            if (w[i] == CLUSTER[0] && w[i + 1] == CLUSTER[1] && w[i + 2] == CLUSTER[2] && w[i + 3] == CLUSTER[3]) {
                val pos = base + i
                if (pos > lastCluster) {
                    if (i + 24 > w.size) break                        // (the rest follows with the next read)
                    var o = i + 4
                    val sizeLen = Integer.numberOfLeadingZeros(w[o].toInt() and 0xff) - 24 + 1
                    o += sizeLen
                    if (sizeLen in 1..8 && (w[o].toInt() and 0xff) == 0xe7) {            // the timecode, the first element
                        o += 1
                        val vLen = Integer.numberOfLeadingZeros(w[o].toInt() and 0xff) - 24 + 1
                        o += 1
                        if (vLen == 1) {
                            var v = 0L
                            val len = (w[o - 1].toInt() and 0x7f)
                            for (k in 0 until len.coerceAtMost(8)) v = (v shl 8) or (w[o + k].toLong() and 0xff)
                            synchronized(lock) { idxMs.add(v); idxPos.add(pos) }
                        }
                    }
                    lastCluster = pos
                }
                i += 4
            } else i++
        }
        tail = w.copyOfRange(maxOf(0, w.size - 30), w.size)
    }

    /** The cluster to start again from for the picture at [timeUs]: the last one before it still in the cache (else the first there is). */
    fun clusterAtOrBefore(timeUs: Long): Pair<Long, Long>? = synchronized(lock) {
        if (idxMs.isEmpty()) return null
        val oldest = maxOf(0L, written - capacity)
        var best = -1
        var first = -1
        for (k in idxMs.indices) {
            if (idxPos[k] < oldest) continue
            if (first < 0) first = k
            if (idxMs[k] * 1000 <= timeUs) best = k else break
        }
        val k = if (best >= 0) best else first
        if (k < 0) null else idxMs[k] * 1000 to idxPos[k]
    }

    private fun put(at: Long, b: ByteArray, n: Int) {
        var done = 0
        while (done < n) {
            val ring = ((at + done) % capacity)
            val k = minOf(n - done, (capacity - ring).toInt())
            if (mem != null) { val d = mem.duplicate(); d.position(ring.toInt()); d.put(b, done, k) }
            else disk!!.write(java.nio.ByteBuffer.wrap(b, done, k), ring)
            done += k
        }
    }

    private fun get(at: Long, b: ByteArray, off: Int, n: Int): Int {
        val ring = at % capacity
        val k = minOf(n, (capacity - ring).toInt())
        if (mem != null) { val d = mem.duplicate(); d.position(ring.toInt()); d.get(b, off, k); return k }
        return disk!!.read(java.nio.ByteBuffer.wrap(b, off, k), ring).coerceAtLeast(0)
    }

    fun seekTo(pos: Long) {
        synchronized(lock) {
            val oldest = maxOf(0L, written - capacity)
            if (pos < oldest) throw java.io.IOException("the MakeMKV stream is no longer in the cache (asked for $pos, oldest $oldest)")
            position = pos
            lock.notifyAll()
        }
    }

    fun read(b: ByteArray, off: Int, len: Int): Int {
        synchronized(lock) {
            while (position >= written && !eof && !closed) lock.wait(500)
            if (closed || position >= written) return -1
        }
        val n = get(position, b, off, minOf(len.toLong(), written - position).toInt())
        synchronized(lock) {
            position += n
            if (!started && position > 1_000_000) started = true
            minAhead = minOf(minAhead, written - position)
            lock.notifyAll()
        }
        val now = System.currentTimeMillis()
        if (now - tl >= 5000) {
            stats("stream: played %.1f MB, cache %s %.0f MB ahead (least %.0f MB), MakeMKV held %d×, %d clusters, in %.2f MB/s, out %.2f MB/s".format(java.util.Locale.ROOT,
                position / 1e6, where, (written - position) / 1e6, minAhead / 1e6, holds, idxMs.size,
                (written - lastWritten) / 1e3 / (now - tl), (position - lastPos) / 1e3 / (now - tl)))
            tl = now; lastPos = position; lastWritten = written; minAhead = Long.MAX_VALUE
        }
        return n
    }

    /** (not synchronized: a read may be waiting for MakeMKV) */
    fun close() {
        closed = true
        try { hold(false) } catch (_: Exception) { }
        synchronized(lock) { lock.notifyAll() }
        try { input?.close() } catch (_: Exception) { }
        try { disk?.close() } catch (_: Exception) { }
        file?.delete()
    }
}


/**
 * The player's view of MakeMKV's stream: Matroska, and seekable within what the cache holds (see [PipeStream.clusterAtOrBefore]).
 * Without it the player starts from byte 0 again to change a track, which a stream cannot do.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LiveMatroska(private val delegate: androidx.media3.extractor.Extractor, private val pipe: PipeStream) : androidx.media3.extractor.Extractor {
    override fun sniff(input: androidx.media3.extractor.ExtractorInput) = delegate.sniff(input)
    override fun init(output: androidx.media3.extractor.ExtractorOutput) {
        delegate.init(object : androidx.media3.extractor.ExtractorOutput {
            override fun track(id: Int, type: Int) = output.track(id, type)
            override fun endTracks() = output.endTracks()
            override fun seekMap(map: androidx.media3.extractor.SeekMap) {
                output.seekMap(object : androidx.media3.extractor.SeekMap {
                    override fun isSeekable() = true
                    override fun getDurationUs() = map.durationUs
                    override fun getSeekPoints(timeUs: Long): androidx.media3.extractor.SeekMap.SeekPoints {
                        val c = pipe.clusterAtOrBefore(timeUs)
                        return androidx.media3.extractor.SeekMap.SeekPoints(
                            if (c != null) androidx.media3.extractor.SeekPoint(c.first, c.second) else androidx.media3.extractor.SeekPoint.START)
                    }
                })
            }
        })
    }
    override fun read(input: androidx.media3.extractor.ExtractorInput, seekPosition: androidx.media3.extractor.PositionHolder) = delegate.read(input, seekPosition)
    override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)
    override fun release() = delegate.release()
}
