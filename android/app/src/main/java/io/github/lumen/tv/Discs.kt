package io.github.lumen.tv

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The discs this television can read by itself: optical drives on USB (read with [UsbBlockSource]) and
 * disc images (`.iso`) in the app's own folder – which is also how a drive is emulated for tests.
 * A Blu-ray with AACS is read when the key database (KEYDB.cfg, the same file libaacs uses) knows the
 * disc; BD+, Ultra HD discs and DVD CSS are not handled.
 */
class Discs(private val activity: Activity, private val changed: () -> Unit) {
    class Disc(val key: String, val label: String, val kind: String, val udf: Udf?, val source: BlockSource?,
               val encrypted: Boolean, val note: String) {
        /** The AACS keys of the disc, once the key database knows it. */
        @Volatile var keys: Aacs.Result? = null
        @Volatile var keysChecked = 0L
        var discId: String? = null
        @Volatile var lastLook = System.currentTimeMillis()
        /** AACS bus encryption: the disc asks for it / the drive can do it (null: not known) */
        var busDisc: Boolean? = null
        var busDrive: Boolean? = null
        /** the drive's read data key (bus decryption) and what happened when it was asked for */
        @Volatile var busKey: ByteArray? = null
        @Volatile var busStatus: String? = null
        /** BD+ is a program on the disc that alters the stream: its presence is reported, the stream not repaired. */
        var bdplus = false
    }

    private val usb = activity.getSystemService(Context.USB_SERVICE) as UsbManager
    /** The device name is a path (/dev/bus/usb/001/005); the key goes into addresses (disc://<key>/…), so no slashes. */
    private fun usbKey(deviceName: String) = "usb" + deviceName.replace(Regex("[^A-Za-z0-9]"), "_")
    private val open = HashMap<String, Disc>()
    private val asked = HashSet<String>()
    private val action = "io.github.lumen.tv.USB_PERMISSION"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                val d: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                synchronized(open) { open.remove(usbKey(d?.deviceName ?: ""))?.source?.close() }
            }
            changed()
        }
    }

    init {
        val f = IntentFilter().apply {
            addAction(action); addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        else activity.registerReceiver(receiver, f)
    }

    fun close() {
        try { activity.unregisterReceiver(receiver) } catch (_: Exception) { }
        synchronized(open) { open.values.forEach { it.source?.close() }; open.clear() }
    }

    fun udf(key: String): Udf? = synchronized(open) { open[key]?.udf }

    /** A file of the disc as plain bytes: the streams of an AACS disc are decrypted as they are read. */
    fun reader(key: String, path: String): RandomReader? {
        val d = synchronized(open) { open[key] } ?: return null
        val r = d.udf?.open(path) ?: return null
        val keys = d.keys
        if (d.encrypted && keys != null && path.startsWith("BDMV/STREAM/", true)) { ensureBus(d); return AacsReader(r, keys.keys, d.busKey) }
        return r
    }

    /** A disc that asks for bus encryption on a drive that does it: authenticate to the drive once (needs a host certificate). */
    @Synchronized private fun ensureBus(d: Disc) {
        if (d.busKey != null || d.busStatus != null) return
        if (d.busDisc != true || d.busDrive == false) return
        val usbSrc = d.source as? UsbBlockSource ?: return
        // the user's own certificates first (hostcerts.cfg in the app's folder is never overwritten by an update of the database)
        val own = listOfNotNull(File(activity.filesDir, "hostcerts.cfg"), activity.getExternalFilesDir(null)?.let { File(it, "hostcerts.cfg") })
        val certs = AacsDrive.hostCerts(own + keyFiles())
        val r = AacsDrive.readDataKey(usbSrc, certs) { android.util.Log.i("LumenTV", "AACS: $it") }
        d.busKey = r.readDataKey
        d.busStatus = r.message
        android.util.Log.i("LumenTV", "AACS drive authentication: ${r.message}")
    }

    /** The disc's files for libbluray (menus), with the AACS keys if they are known. */
    fun fs(key: String): DiscFs? {
        val d = synchronized(open) { open[key] } ?: return null
        val udf = d.udf ?: return null
        if (d.encrypted && d.keys == null) return null
        if (d.encrypted) ensureBus(d)
        return DiscFs(udf, if (d.encrypted) d.keys?.keys else null, d.busKey)
    }

    /** Why a disc is not played: its AACS identity and what the key database has. */
    fun keyInfo(key: String, authenticate: Boolean = false): String {
        val d = synchronized(open) { open[key] } ?: return "The disc is not open any more: open it again from the list."
        if (!d.encrypted) return "The disc is not encrypted."
        if (authenticate && d.keys != null) ensureBus(d)
        val files = keyFiles().filter { it.isFile }
        val entries = files.sumOf { f -> try { f.useLines(Charsets.ISO_8859_1) { l -> l.count { it.startsWith("0x") } } } catch (_: Exception) { 0 } }
        val bus = when {
            d.busDisc == true && d.busDrive == true ->
                " Bus encryption: the disc asks for it and the drive does it."
            d.busDisc == true && d.busDrive == false -> " Bus encryption: the disc asks for it, the drive does not do it."
            d.busDisc == false -> " Bus encryption: not used by this disc."
            else -> ""
        }
        val auth = if (d.busDisc == true && d.busDrive != false) "Drive authentication: " + (d.busStatus ?: "not tried") + ". " else ""
        return auth + "AACS disc ID ${d.discId ?: "unknown"}. Key database: " +
            (if (files.isEmpty()) "none loaded." else "$entries entries${if (d.keys != null) ", this disc is in it" else ", this disc is not in it"}.") + bus
    }

    fun hasMenu(key: String): Boolean = synchronized(open) { open[key] }?.kind == "Blu-ray" && Bd.available()

    // ---- the key database ----
    private fun keyFiles(): List<File> = listOfNotNull(
        File(activity.filesDir, "KEYDB.cfg"), activity.getExternalFilesDir(null)?.let { File(it, "KEYDB.cfg") })

    private fun keyStamp(): Long = keyFiles().sumOf { if (it.isFile) it.lastModified() + it.length() else 0L }

    private fun lookupKeys(d: Disc) {
        val stamp = keyStamp()
        if (d.keysChecked == stamp && d.keysChecked != 0L) return
        d.keysChecked = stamp
        val udf = d.udf ?: return
        d.keys = try { Aacs.unlock(udf, keyFiles()) } catch (_: Exception) { null }
    }

    /** Downloads the community key database (the file libaacs reads), after the user chose to. */
    fun loadKeyDatabase(progress: (String) -> Unit) {
        progress("Loading the key database …")
        try {
            val c = java.net.URL("http://fvonline-db.bplaced.net/export/keydb_eng.zip").openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 60000
            val tmp = File(activity.filesDir, "keydb.zip.part")
            c.inputStream.use { i -> FileOutputStream(tmp).use { o -> i.copyTo(o) } }
            java.util.zip.ZipInputStream(tmp.inputStream()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.name.endsWith(".cfg", true)) {
                        val out = File(activity.filesDir, "KEYDB.cfg.part")
                        FileOutputStream(out).use { z.copyTo(it) }
                        out.renameTo(File(activity.filesDir, "KEYDB.cfg"))
                        break
                    }
                }
            }
            tmp.delete()
            changed()
            progress(if (File(activity.filesDir, "KEYDB.cfg").isFile) "Key database loaded." else "The download held no key database.")
        } catch (e: Exception) {
            progress("Could not load the key database: ${e.message}")
        }
    }

    // ---- what is there ----
    private fun imageFolder(): File? = activity.getExternalFilesDir(null)

    private val refreshLock = java.util.concurrent.locks.ReentrantLock()
    @Volatile private var last: List<Disc> = emptyList()
    private val opening = HashSet<String>()
    private var lastAsked = HashMap<String, Long>()
    /** USB devices that are not taken for an optical drive, as short descriptions (for the list, so a user can see what the television sees). */
    @Volatile var unsupported: List<String> = emptyList()
        private set

    private fun describeDevice(device: UsbDevice): String {
        val ifs = (0 until device.interfaceCount).map { device.getInterface(it) }
            .joinToString(" ") { "%d/%d/%02x".format(it.interfaceClass, it.interfaceSubclass, it.interfaceProtocol) }
        val vp = "%04x:%04x".format(device.vendorId, device.productId)
        return "${device.productName ?: device.manufacturerName ?: "USB device"} ($vp, interfaces $ifs)"
    }

    /** What the kernel says about USB (when the system lets an app read /sys): helps when Android's own list is empty. */
    private fun kernelUsb(): List<String> {
        val out = ArrayList<String>()
        fun rd(f: File): String = try { f.readText().trim() } catch (_: Exception) { "" }
        try {
            val root = File("/sys/bus/usb/devices")
            val devs = root.listFiles().orEmpty().filter { Regex("\\d+-[\\d.]+").matches(it.name) }.sortedBy { it.name }
            for (dev in devs) {
                val ifs = dev.listFiles().orEmpty().filter { it.name.startsWith(dev.name + ":") }.sortedBy { it.name }
                    .joinToString(" ") { rd(File(it, "bInterfaceClass")) + "/" + rd(File(it, "bInterfaceSubClass")) + "/" + rd(File(it, "bInterfaceProtocol")) + (rd(File(it, "driver")).ifEmpty { "" }) }
                val name = rd(File(dev, "product")).ifEmpty { rd(File(dev, "manufacturer")) }.ifEmpty { "USB device" }
                out.add("kernel: $name (${rd(File(dev, "idVendor"))}:${rd(File(dev, "idProduct"))}, ${rd(File(dev, "speed"))} Mb/s, interfaces $ifs)")
            }
            if (devs.isEmpty() && root.exists()) out.add("kernel: no USB device on any port")
            if (!root.exists() || root.listFiles() == null) out.add("kernel: the list of USB devices cannot be read by apps on this television")
        } catch (e: Exception) { out.add("kernel: USB list failed: $e") }
        try {
            val blocks = File("/sys/block").list().orEmpty().filter { it.startsWith("sd") || it.startsWith("sr") || it.startsWith("sg") }
            if (blocks.isNotEmpty()) out.add("kernel: block devices " + blocks.sorted().joinToString(" "))
        } catch (_: Exception) { }
        return out
    }

    /** All discs now present. Never waits for a drive: a drive that is still being read is listed as "reading". */
    fun refresh(): List<Disc> {
        if (!refreshLock.tryLock()) return last
        try { return refreshLocked().also { last = it } } finally { refreshLock.unlock() }
    }

    private fun refreshLocked(): List<Disc> {
        val out = ArrayList<Disc>()
        val present = HashSet<String>()
        val other = ArrayList<String>()
        for ((name, device) in usb.deviceList) {
            val iface = UsbBlockSource.find(device)
            if (iface == null) {
                // hubs, keyboards, ... are not interesting; storage and unknown things are shown, so one can see what arrives
                val classes = (0 until device.interfaceCount).map { device.getInterface(it).interfaceClass }
                val hub = device.deviceClass == 9 && classes.all { it == 9 }
                if (!hub) other.add(describeDevice(device))
                continue
            }
            val key = usbKey(name)
            present.add(key)
            val cached = synchronized(open) { open[key] }
            if (cached != null) {
                // a disc that was taken out or changed, or a drive that had none: look again
                val now = System.currentTimeMillis()
                // (looked at no more often than every 8 seconds: every question wakes the drive)
                val ask = now - cached.lastLook > 8000
                if (ask) cached.lastLook = now
                val stale = cached.note == "empty" || cached.note == "unreadable" ||
                    (ask && (cached.source as? UsbBlockSource)?.mediaChanged() == true)
                if (!stale) { if (cached.encrypted) lookupKeys(cached); out.add(cached); continue }
                synchronized(open) { open.remove(key)?.source?.close() }
            }
            if (!usb.hasPermission(device)) {
                val now = System.currentTimeMillis()
                if (now - (lastAsked[key] ?: 0L) > 30_000) {         // (asks again if the dialog was dismissed)
                    lastAsked[key] = now
                    val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                    try { usb.requestPermission(device, PendingIntent.getBroadcast(activity, 0, Intent(action).setPackage(activity.packageName), flags)) }
                    catch (e: Exception) { android.util.Log.w("LumenTV", "USB permission request failed: $e") }
                }
                out.add(Disc(key, device.productName ?: "Disc drive", "", null, null, false, "permission"))
                continue
            }
            // the drive is read on its own thread (spinning up takes up to half a minute); the page hears of it when done
            out.add(Disc(key, device.productName ?: "Disc drive", "", null, null, false, "reading"))
            val start = synchronized(opening) { opening.add(key) }
            if (start) kotlin.concurrent.thread {
                try {
                    val disc = openDrive(key, device, iface)
                    if (disc != null) synchronized(open) { open[key] = disc }
                    else other.add(describeDevice(device) + " – not an optical drive or not readable")
                } catch (e: Exception) {
                    android.util.Log.w("LumenTV", "opening the drive failed: $e")
                } finally {
                    synchronized(opening) { opening.remove(key) }
                    changed()
                }
            }
        }
        if (usb.deviceList.isEmpty()) {
            other.add(if (!activity.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_USB_HOST))
                "This television does not offer USB host access to apps (no USB device is visible)"
                else "No USB device is visible to apps (check the cable and the drive's power)")
        }
        other.addAll(kernelUsb())
        unsupported = other
        synchronized(open) {
            val gone = open.keys.filter { it.startsWith("usb") && it !in present }
            gone.forEach { open.remove(it)?.source?.close() }
        }
        // disc images in the app's folder
        for (f in imageFolder()?.listFiles().orEmpty().sortedBy { it.name }) {
            if (!f.isFile || !Regex(".*\\.(iso|img|udf)$", RegexOption.IGNORE_CASE).matches(f.name)) continue
            val key = "img" + f.name.hashCode().toUInt().toString(16)
            val cached = synchronized(open) { open[key] }
            if (cached != null) { if (cached.encrypted) lookupKeys(cached); out.add(cached); continue }
            val disc = try {
                val udf = Udf(FileBlockSource(f.path))
                describe(key, udf.label.ifEmpty { f.nameWithoutExtension }, udf, null)
            } catch (e: Exception) {
                Disc(key, f.nameWithoutExtension, "", null, null, false, "unreadable")
            }
            synchronized(open) { open[key] = disc }
            out.add(disc)
        }
        return out
    }

    private fun openDrive(key: String, device: UsbDevice, iface: android.hardware.usb.UsbInterface): Disc? {
        val name = device.productName ?: "Disc drive"
        val conn = usb.openDevice(device) ?: return Disc(key, name, "", null, null, false, "permission")
        val src = UsbBlockSource.open(conn, iface) ?: run { conn.close(); return null }
        try {
            if (!src.inquire()) { src.close(); return null }                // a USB stick or disk: the system handles those
            val label = (src.vendor + " " + src.product).trim().ifEmpty { name }
            if (!src.waitReady()) return Disc(key, label, "", null, src, false, "empty")
            if (!src.readCapacity()) return Disc(key, label, "", null, src, false, "unreadable")
            return describe(key, label, Udf(src), src)
        } catch (e: Exception) {
            src.close()
            return Disc(key, name, "", null, null, false, "unreadable")
        }
    }

    private fun describe(key: String, fallbackLabel: String, udf: Udf, src: BlockSource?): Disc {
        val bd = udf.exists("BDMV/index.bdmv")
        val dvd = udf.exists("VIDEO_TS/VIDEO_TS.IFO")
        val kind = if (bd) "Blu-ray" else if (dvd) "DVD-Video" else ""
        val label = udf.label.ifEmpty { fallbackLabel }
        val encrypted = udf.exists("AACS") || udf.exists("BDMV/AACS")
        val d = Disc(key, label, kind, udf, src, encrypted, "")
        d.bdplus = udf.exists("BDSVM") || udf.exists("BDMV/BDSVM") || udf.exists("BDMV/STREAM/SSIF/BDSVM") ||
            try { udf.list("BDSVM").isNotEmpty() } catch (_: Exception) { false }
        if (encrypted) {
            d.discId = try { Aacs.discId(udf) } catch (_: Exception) { null }
            d.busDisc = try {
                val r = try { udf.open("AACS/Content000.cer") } catch (_: Exception) { udf.open("AACS/Content001.cer") }
                val b = ByteArray(2); r.read(0, b, 0, 2); (b[1].toInt() and 0x80) != 0
            } catch (_: Exception) { null }
            d.busDrive = (src as? UsbBlockSource)?.aacsFeature()?.second
            lookupKeys(d)
        }
        return d
    }

    // ---- what the page shows ----
    fun volumes(discs: List<Disc>): JSONArray {
        val a = JSONArray()
        for (d in discs) {
            val detail = when (d.note) {
                "permission" -> "waiting for permission (answer the dialog of the television)"
                "reading" -> "reading the disc …"
                "empty" -> "no disc"
                "unreadable" -> "cannot be read"
                else -> listOf(d.kind, if (d.encrypted) (if (d.keys != null) "AACS, key known" else "AACS") else "").filter { it.isNotEmpty() }.joinToString(" · ")
            }
            a.put(JSONObject().put("name", d.label).put("path", "disc:${d.key}|").put("detail", detail))
        }
        for (u in unsupported) a.put(JSONObject().put("name", u).put("path", "").put("detail", "").put("off", true))
        return a
    }

    fun folder(key: String, rel: String): JSONArray {
        val d = synchronized(open) { open[key] } ?: return JSONArray()
        val udf = d.udf ?: return JSONArray()
        val out = JSONArray()
        val base = "disc://$key/"
        if (d.encrypted && d.keys == null && rel.isEmpty()) {
            out.put(JSONObject().put("name", if (d.bdplus) "AACS and BD+ disc: the key is not in the key database, BD+ cannot be handled"
                else "Encrypted disc (AACS): its key is not in the key database").put("path", "").put("dir", false).put("off", true))
            val have = keyFiles().any { it.isFile }
            out.put(JSONObject().put("name", if (have) "Update the key database from the net" else "Load the key database from the net")
                .put("path", "disc:$key|#keys").put("dir", false).put("action", "keys"))
            out.put(JSONObject().put("name", "Why? (disc ID and key database)").put("path", "disc:$key|#info").put("dir", false).put("action", "info"))
            return out
        }
        if (rel.isEmpty() && d.kind == "Blu-ray") {
            val streams = try { udf.list("BDMV/STREAM") } catch (_: IOException) { emptyList() }
            if (Bd.available())
                out.put(JSONObject().put("name", "Play the disc with its menu").put("path", "disc:$key|#menu")
                    .put("dir", false).put("uri", "bdnav://$key/").put("video", true))
            for (e in streams.filter { !it.dir && it.name.endsWith(".m2ts", true) && playable(key, "BDMV/STREAM/${it.name}") }.sortedByDescending { it.size }) {
                out.put(JSONObject().put("name", "Title " + e.name.substringBeforeLast('.')).put("path", "disc:$key|BDMV/STREAM/${e.name}")
                    .put("dir", false).put("size", e.size).put("uri", base + "BDMV/STREAM/" + e.name).put("video", true))
            }
            out.put(JSONObject().put("name", "Copy the titles to storage").put("path", "disc:$key|#copy").put("dir", false).put("action", "copy"))
            out.put(JSONObject().put("name", "Make MKV files of the titles").put("path", "disc:$key|#rip").put("dir", false).put("action", "rip"))
            if (d.encrypted) out.put(JSONObject().put("name", "Protection of this disc (AACS)").put("path", "disc:$key|#info").put("dir", false).put("action", "info"))
            return out
        }
        if (rel.isEmpty() && d.kind == "DVD-Video") {
            val vobs = try { udf.list("VIDEO_TS") } catch (_: IOException) { emptyList() }
            for (e in vobs.filter { !it.dir && it.name.endsWith(".VOB", true) && it.size > 5_000_000 }.sortedBy { it.name }) {
                out.put(JSONObject().put("name", e.name).put("path", "disc:$key|VIDEO_TS/${e.name}").put("dir", false)
                    .put("size", e.size).put("uri", base + "VIDEO_TS/" + e.name).put("video", true))
            }
            out.put(JSONObject().put("name", "Copy the titles to storage").put("path", "disc:$key|#copy").put("dir", false).put("action", "copy"))
            return out
        }
        // any other disc: its folders and files
        val list = try { udf.list(rel.trimEnd('/')) } catch (_: IOException) { emptyList() }
        for (e in list) {
            val p = rel + e.name
            if (e.dir) out.put(JSONObject().put("name", e.name).put("path", "disc:$key|$p/").put("dir", true).put("size", 0))
            else out.put(JSONObject().put("name", e.name).put("path", "disc:$key|$p").put("dir", false).put("size", e.size).put("uri", base + p))
        }
        return out
    }

    private fun playable(key: String, path: String): Boolean = try {
        val r = reader(key, path)
        r != null && MkvRipper.hasPlayableVideo(r, 192)
    } catch (_: Exception) { true }

    /** Copies what [folder] lists as titles into the app's movie folder. [progress] gets short messages. */
    fun copy(key: String, progress: (String) -> Unit) {
        val d = synchronized(open) { open[key] }
        val udf = d?.udf
        if (d == null || udf == null) { progress("The disc is gone."); return }
        if (d.encrypted && d.keys == null) { progress("The disc is encrypted and its key is not known."); return }
        val folder = if (d.kind == "Blu-ray") "BDMV/STREAM" else if (d.kind == "DVD-Video") "VIDEO_TS" else { progress("Nothing to copy."); return }
        val titles = udf.list(folder).filter { !it.dir && (it.name.endsWith(".m2ts", true) || it.name.endsWith(".VOB", true)) && it.size > 5_000_000 &&
            (!it.name.endsWith(".m2ts", true) || playable(key, "$folder/${it.name}")) }
        val target = File(activity.getExternalFilesDir(Environment.DIRECTORY_MOVIES), d.label.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").ifEmpty { "Disc" })
        target.mkdirs()
        val total = titles.sumOf { it.size }
        if (target.usableSpace < total + 100_000_000) { progress("Not enough space on the storage."); return }
        var done = 0L
        var last = 0L
        val buf = ByteArray(1 shl 20)
        try {
            for (t in titles) {
                val r = reader(key, "$folder/${t.name}") ?: throw IOException("the disc is gone")
                val bd = t.name.endsWith(".m2ts", true)
                // a Blu-ray stream is written as a plain transport stream (.ts): the time stamps of the disc's
                // 192-byte packets are of no use outside it, and every player reads the result
                val outName = if (bd) t.name.substringBeforeLast('.') + ".ts" else t.name
                FileOutputStream(File(target, outName)).use { out ->
                    var pos = 0L
                    val plain = ByteArray(192 * 1024 / 192 * 188 + 188)
                    while (true) {
                        val n = r.read(pos, buf, 0, if (bd) 192 * 5461 else buf.size)
                        if (n < 0) break
                        if (bd) {
                            var o = 0; var w = 0
                            while (o + 192 <= n) { System.arraycopy(buf, o + 4, buf, w, 188); o += 192; w += 188 }
                            out.write(buf, 0, w)
                        } else out.write(buf, 0, n)
                        pos += n; done += n
                        val now = System.currentTimeMillis()
                        if (now - last > 2000) { last = now; progress("Copying … ${if (total > 0) (done * 100 / total) else 0} %") }
                    }
                }
            }
            progress("Copied ${titles.size} titles: Copied discs › ${target.name}")
        } catch (e: Exception) {
            progress("Copying stopped: ${e.message}")
        }
    }

    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun keepAwake(on: Boolean) = activity.runOnUiThread {
        if (on) activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Makes a Matroska file of every title of a Blu-ray (see [MkvRipper]), into the movie folder. */
    fun rip(key: String, progress: (String) -> Unit) {
        val d = synchronized(open) { open[key] }
        val udf = d?.udf
        if (d == null || udf == null) { progress("The disc is gone."); return }
        if (d.kind != "Blu-ray") { progress("MKV files can be made of Blu-rays; copy the titles of a DVD instead."); return }
        if (d.encrypted && d.keys == null) { progress("The disc is encrypted and its key is not known."); return }
        if (!busy.compareAndSet(false, true)) { progress("Another disc is being copied."); return }
        keepAwake(true)
        try {
            // the titles of a disc are its playlists (as in MakeMKV): a movie may run through several clips
            val clipSizes = udf.list("BDMV/STREAM").filter { !it.dir && it.name.endsWith(".m2ts", true) }.associate { it.name.substringBeforeLast('.') to it.size }
            val lists = try {
                udf.list("BDMV/PLAYLIST").filter { !it.dir && it.name.endsWith(".mpls", true) }.sortedBy { it.name }.mapNotNull { e ->
                    val r = udf.open("BDMV/PLAYLIST/${e.name}")
                    val b = ByteArray(r.size.toInt())
                    var n = 0
                    while (n < b.size) { val k = r.read(n.toLong(), b, n, b.size - n); if (k <= 0) break; n += k }
                    Playlist.parse(e.name.substringBeforeLast('.'), b)
                }.filter { p -> p.clips.all { clipSizes.containsKey(it) } }
            } catch (_: Exception) { emptyList() }
            var picks = lists.filter { it.seconds >= 120 }.ifEmpty { lists.filter { it.seconds >= 5 } }
            picks = picks.distinctBy { it.clips.joinToString(",") }.filter { p -> playable(key, "BDMV/STREAM/${p.clips[0]}.m2ts") }
            if (picks.isEmpty()) {
                picks = clipSizes.filter { it.value > 5_000_000 && playable(key, "BDMV/STREAM/${it.key}.m2ts") }.keys.sorted()
                    .map { Playlist(it, listOf(it), 0.0) }
            }
            if (picks.isEmpty()) { progress("No titles found."); return }
            val target = File(activity.getExternalFilesDir(Environment.DIRECTORY_MOVIES), d.label.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").ifEmpty { "Disc" })
            target.mkdirs()
            val total = picks.sumOf { p -> p.clips.sumOf { clipSizes[it] ?: 0L } }
            // the files are about as large as the streams
            if (target.usableSpace < total + 200_000_000) { progress("Not enough space on the storage."); return }
            var before = 0L
            var made = 0
            val problems = ArrayList<String>()
            var last = 0L
            for (t in picks) {
                val size = t.clips.sumOf { clipSizes[it] ?: 0L }
                val readers = t.clips.map { reader(key, "BDMV/STREAM/$it.m2ts") ?: throw IOException("the disc is gone") }
                val name = "Title " + t.name
                try {
                    val res = MkvRipper(readers, 192).rip(File(target, "$name.mkv"), d.label, { done, all ->
                        val now = System.currentTimeMillis()
                        if (now - last > 2000) {
                            last = now
                            val sum = before + (if (all > 0) done * size / all else 0)
                            progress("Making MKV … ${sum * 100 / total} %  ($name)")
                        }
                    }, { false })
                    made++
                    val failed = readers.filterIsInstance<AacsReader>().sumOf { it.failedUnits }
                    if (failed > 0) problems.add("$name: $failed units could not be decrypted (BD+?)")
                    problems.addAll(res.warnings.map { "$name: $it" })
                } catch (e: Exception) {
                    problems.add("$name: ${e.message}")
                }
                before += size
            }
            progress(if (made == 0) "No MKV could be made: ${problems.firstOrNull() ?: ""}"
                else "Made $made MKV ${if (made == 1) "file" else "files"}: Copied discs › ${target.name}" + (if (problems.isNotEmpty()) " (${problems.size} problems: ${problems.first()})" else ""))
        } catch (e: Exception) {
            progress("Stopped: ${e.message}")
        } finally {
            keepAwake(false)
            busy.set(false)
        }
    }

    fun copiedFolder(): File = File(activity.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "")
}
