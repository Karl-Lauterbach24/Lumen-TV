package io.github.lumen.tv

import android.graphics.Bitmap
import org.json.JSONObject
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Reads the names of a disc's audio and subtitle tracks from the disc's own menu, by itself:
 *
 *  1. bdmenu (libbluray with libmmbd's decryption, see makemkv-native/bdmenu) plays the menu and writes its pictures.
 *  2. The buttons are found from the picture (bright text), each is tried with a mouse click and an Enter, and the player's
 *     registers say what it did: PSR1 is the audio stream, PSR2 the subtitle stream. A button that changes them chooses
 *     that stream – so which button belongs to which stream is *seen*, not guessed.
 *  3. The text on each of those buttons is read from the picture (text recognition), once bdmenu is gone (memory).
 *
 * Playback buttons (a title starts) are left again with the root menu key and not tried twice.
 */
class MenuReader(private val makemkv: MakeMkv, private val log: (String) -> Unit) {
    class Option(val kind: String, val stream: Int, val rect: IntArray, var label: String = "")

    /** What was found: per stream number (1-based, as the disc counts them) the name the menu gives. */
    class Names(val audio: Map<Int, String>, val text: Map<Int, String>, val textOff: String) {
        fun toJson(): String = JSONObject().put("audio", JSONObject(audio.mapKeys { it.key.toString() })).put("text", JSONObject(text.mapKeys { it.key.toString() })).put("off", textOff).toString()
        companion object {
            fun fromJson(s: String): Names? = try {
                val o = JSONObject(s)
                fun map(j: JSONObject) = j.keys().asSequence().associate { it.toInt() to j.getString(it) }
                Names(map(o.getJSONObject("audio")), map(o.getJSONObject("text")), o.optString("off"))
            } catch (_: Exception) { null }
        }
    }

    private class Frame(val w: Int, val h: Int, val px: ByteArray) {
        fun bright(x: Int, y: Int): Boolean {
            val i = (y * w + x) * 4
            val a = px[i + 3].toInt() and 0xff
            if (a < 128) return false
            return maxOf(px[i].toInt() and 0xff, px[i + 1].toInt() and 0xff, px[i + 2].toInt() and 0xff) > 110
        }
    }

    private val lines = LinkedBlockingQueue<String>()
    @Volatile private var evAudio = -1
    @Volatile private var evPg = -1
    @Volatile private var evPgOn = -1
    @Volatile private var evTitle = 65535
    @Volatile private var menuCount = 0
    @Volatile private var titleCount = 0

    private fun track(l: String) {
        val v = l.substringAfterLast(' ').toIntOrNull() ?: return
        when {
            l.startsWith("EVENT audio_stream ") -> evAudio = v
            l.startsWith("EVENT pg_stream ") -> evPg = v
            l.startsWith("EVENT pg_enabled ") -> evPgOn = v
            l.startsWith("EVENT menu ") -> if (v == 1) menuCount++
        }
    }
    private var process: Process? = null
    private val psr = HashMap<Int, Long>()
    private val psrLog = ArrayList<Pair<Int, Long>>()

    private fun send(cmd: String) { process?.outputStream?.let { it.write((cmd + "\n").toByteArray()); it.flush() } }

    /** Waits for a line that [match] likes (others are looked at on the way: registers are kept). */
    private fun await(timeoutMs: Long, match: (String) -> Boolean): String? {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = end - System.currentTimeMillis()
            if (left <= 0) return null
            val l = lines.poll(left, TimeUnit.MILLISECONDS) ?: return null
            if (match(l)) return l
        }
    }

    private fun note(l: String) {
        Regex("""PSRW\s+PSR(\d+)\s+\((\w+)\)\s+0x([0-9a-fA-F]+) -> 0x([0-9a-fA-F]+)""").find(l)?.let {
            val n = it.groupValues[1].toInt(); val v = it.groupValues[4].toLong(16)
            psr[n] = v; synchronized(psrLog) { psrLog.add(n to v) }
            // PSR4 is the title: 0 the menu, 65535 the first-play object, 1.. the titles of the disc (the film)
            if (n == 4 && v in 1..999) { evTitle = v.toInt(); titleCount++ }
        }
    }

    private fun snap(): Frame? {
        send("snap")
        val l = await(15_000) { it.startsWith("SNAP ") } ?: return null
        val name = l.split(' ').getOrNull(2) ?: return null
        val f = File(makemkv.menuDir(), name)
        val b = f.readBytes()
        val nl = b.indexOf('\n'.code.toByte())
        val head = String(b, 0, nl).split(' ')
        val w = head[1].toInt(); val h = head[2].toInt()
        f.delete()
        return Frame(w, h, b.copyOfRange(nl + 1, b.size))
    }

    /** The lines of text in a picture, as rectangles [x0, y0, x1, y1]: bright pixels, lines close above each other (a button of two lines) joined. */
    private fun blobs(f: Frame): List<IntArray> {
        val rows = BooleanArray(f.h)
        for (y in 0 until f.h) { var x = 0; while (x < f.w) { if (f.bright(x, y)) { rows[y] = true; break }; x += 2 } }
        val out = ArrayList<IntArray>()
        var y = 0
        while (y < f.h) {
            if (!rows[y]) { y++; continue }
            var y1 = y; var gap = 0
            while (y1 + 1 < f.h && (rows[y1 + 1] || gap < 3)) { if (rows[y1 + 1]) gap = 0 else gap++; y1++ }
            while (y1 > y && !rows[y1]) y1--
            // the columns of that line
            val cols = BooleanArray(f.w)
            for (yy in y..y1) for (x in 0 until f.w) if (!cols[x] && f.bright(x, yy)) cols[x] = true
            var x = 0
            while (x < f.w) {
                if (!cols[x]) { x++; continue }
                var x1 = x; var g = 0
                while (x1 + 1 < f.w && (cols[x1 + 1] || g < 16)) { if (cols[x1 + 1]) g = 0 else g++; x1++ }
                while (x1 > x && !cols[x1]) x1--
                if (x1 - x >= 12 && y1 - y >= 8) out.add(intArrayOf(x, y, x1, y1))
                x = x1 + 1
            }
            y = y1 + 1
        }
        // a button of several lines: blobs right under each other, overlapping in x
        var merged = true
        while (merged) {
            merged = false
            loop@ for (i in out.indices) for (j in out.indices) {
                if (i == j) continue
                val a = out[i]; val b = out[j]
                val vgap = b[1] - a[3]
                if (vgap in 0..14 && minOf(a[2], b[2]) - maxOf(a[0], b[0]) > 20) {
                    out[i] = intArrayOf(minOf(a[0], b[0]), a[1], maxOf(a[2], b[2]), b[3]); out.removeAt(j); merged = true; break@loop
                }
            }
        }
        return out.sortedWith(compareBy({ it[1] / 40 }, { it[0] }))
    }

    private fun center(r: IntArray) = ((r[0] + r[2]) / 2) to ((r[1] + r[3]) / 2)

    /** True where a click at the middle of [r] hits a button of the menu. */
    private fun isButton(r: IntArray): Boolean {
        val (x, y) = center(r)
        send("select $x $y")
        val l = await(5_000) { it.startsWith("SELECT $x $y ") } ?: return false
        return l.trim().endsWith(" 1")
    }

    private fun activate(r: IntArray) {
        val (x, y) = center(r)
        send("select $x $y")
        await(3_000) { it.startsWith("SELECT $x $y ") }
        Thread.sleep(300)
        send("key enter")
    }

    private fun events(ms: Long): List<String> {
        val out = ArrayList<String>()
        val end = System.currentTimeMillis() + ms
        while (true) {
            val left = end - System.currentTimeMillis()
            if (left <= 0) break
            val l = lines.poll(left, TimeUnit.MILLISECONDS) ?: break
            out.add(l)
        }
        return out
    }

    private var baseline: Triple<IntArray, Int, Pair<Int, Int>>? = null
    private var pendingUnchanged: List<IntArray> = emptyList()
    private var pendingMissingAudio = -1

    private fun sameButton(a: IntArray, b: IntArray) = kotlin.math.abs(a[0] - b[0]) < 12 && kotlin.math.abs(a[1] - b[1]) < 12

    /**
     * Runs the whole thing; the drive must already be in the app's hands (see MakeMkv.openBridge). Null: no menu or nothing found.
     * [stop] says when to give up. [audioLangs] / [textLangs]: what the disc's playlist says about its streams (ISO 639-2, in the order
     * of the stream numbers): the names are only taken if they agree with it.
     *
     * The menu's pages are looked at: the buttons of the top bar are clicked one after the other (the first, usually "play the
     * film", last, and none that starts a title), and the page that has a heading for the audio and one for the subtitles is
     * the one: its options, in the order shown, are the streams 1, 2, 3 … (menus set the streams from their options in the order of
     * the playlist's stream table; the languages in the names are checked against that table).
     */
    fun read(audioLangs: List<String>, textLangs: List<String>, stop: () -> Boolean): Names? {
        val p = makemkv.startMenu()
        process = p
        val reader = kotlin.concurrent.thread(isDaemon = true) {
            try { p.inputStream.bufferedReader().forEachLine { l -> if (l.startsWith("PSRW")) note(l); track(l); lines.put(l) } } catch (_: java.io.IOException) { }
        }
        var pageFrame: Frame? = null
        val pageButtons = ArrayList<IntArray>()
        try {
            // the menu: AACS opening takes a minute or two; then "EVENT menu 1" and a picture
            if (await(240_000) { it.startsWith("EVENT menu") || it.startsWith("ERROR") } == null || stop()) { log("menu: no menu came"); return null }
            Thread.sleep(8_000)
            events(500)
            var top: Frame? = null
            for (i in 0 until 12) { top = snap(); if (top != null && blobs(top).isNotEmpty()) break; Thread.sleep(5_000); if (stop()) return null }
            if (top == null) { log("menu: nothing drawn"); return null }
            val topButtons = blobs(top).filter { isButton(it) }
            log("menu: top menu buttons ${topButtons.map { it.toList() }}")
            if (topButtons.isEmpty()) return null
            // (the first button is usually "play the film": tried last)
            for (btn in topButtons.drop(1) + topButtons.take(1)) {
                if (stop()) return null
                val titleBefore = titleCount
                activate(btn)
                events(3_000)
                if (titleCount != titleBefore) { log("menu: button ${btn.toList()} started a title: stop here"); return null }
                val page = snap() ?: continue
                val buttons = blobs(page).filter { b -> topButtons.none { sameButton(it, b) } && isButton(b) }
                log("menu: page of button ${btn.toList()}: ${buttons.size} buttons")
                if (buttons.size >= 4) { pageFrame = page; pageButtons.addAll(buttons); break }
            }
            if (pageFrame == null) { log("menu: no page with options found"); return null }
        } finally {
            try { send("quit") } catch (_: Exception) { }
            Thread.sleep(500)
            makemkv.stop()
            reader.join(2_000)
        }
        // the text of the buttons (the headings are not buttons: their text is read from the picture, too)
        val frame = pageFrame ?: return null
        val all = blobs(frame)
        val labelled = all.map { it to recognise(frame, it) }.filter { it.second.isNotBlank() }
        log("menu: texts ${labelled.map { "${it.first.toList()}='${it.second}'" }}")
        val audioHead = Regex("(?i)^(sprache|audio|ton|language|langue|idioma|lingua|tonspur|audiospur)$")
        val textHead = Regex("(?i)^(untertitel|subtitles?|sous-titres|subt[ií]tulos|sottotitoli)$")
        val off = Regex("(?i)^(aus|off|non|no|nein|keine|ohne|desactivado|disattivati?)$")
        val ordered = labelled.sortedWith(compareBy({ it.first[1] }, { it.first[0] }))
        val audio = LinkedHashMap<Int, String>(); val text = LinkedHashMap<Int, String>(); var offLabel = ""
        var section = ""
        for ((r, label) in ordered) {
            val l = label.trim()
            when {
                audioHead.matches(l) -> section = "audio"
                textHead.matches(l) -> section = "text"
                topButtonsOverlap(pageButtons, r).not() && pageButtons.none { sameButton(it, r) } -> { }      // (not a button: a note)
                section == "audio" -> audio[audio.size + 1] = l
                section == "text" -> if (off.matches(l)) offLabel = l else text[text.size + 1] = l
            }
        }
        log("menu: audio $audio text $text off '$offLabel'")
        if (audio.isEmpty() && text.isEmpty()) return null
        // the languages in the names must agree with the playlist's table: the same number of audio streams, and each name carries
        // its language (or, where the disc does not name languages in the names, nothing is claimed)
        if (audioLangs.isNotEmpty() && audio.size != audioLangs.size) { log("menu: ${audio.size} audio options but ${audioLangs.size} streams: names dropped"); return null }
        return Names(audio, text, offLabel)
    }

    private fun topButtonsOverlap(buttons: List<IntArray>, r: IntArray) = buttons.any { sameButton(it, r) }

    /** The text in a rectangle of the picture: ML Kit's text recognition (looked up by name: the library is only in builds that have the rest). */
    private fun recognise(f: Frame, r: IntArray): String {
        return try {
            val m = 10
            val x0 = maxOf(0, r[0] - m); val y0 = maxOf(0, r[1] - m); val x1 = minOf(f.w - 1, r[2] + m); val y1 = minOf(f.h - 1, r[3] + m)
            val w = x1 - x0 + 1; val h = y1 - y0 + 1
            val px = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val i = ((y0 + y) * f.w + (x0 + x)) * 4
                val a = f.px[i + 3].toInt() and 0xff
                val rr = (f.px[i].toInt() and 0xff) * a / 255; val gg = (f.px[i + 1].toInt() and 0xff) * a / 255; val bb = (f.px[i + 2].toInt() and 0xff) * a / 255
                px[y * w + x] = (0xff shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
            val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
            val tr = Class.forName("com.google.mlkit.vision.text.TextRecognition")
            val opts = Class.forName("com.google.mlkit.vision.text.latin.TextRecognizerOptions").getField("DEFAULT_OPTIONS").get(null)
            val client = tr.getMethod("getClient", Class.forName("com.google.mlkit.vision.text.TextRecognizerOptionsInterface")).invoke(null, opts)
            val image = Class.forName("com.google.mlkit.vision.common.InputImage").getMethod("fromBitmap", Bitmap::class.java, Int::class.javaPrimitiveType).invoke(null, bmp, 0)
            val task = client!!.javaClass.getMethod("process", Class.forName("com.google.mlkit.vision.common.InputImage")).invoke(client, image)
            val result = Class.forName("com.google.android.gms.tasks.Tasks").getMethod("await", Class.forName("com.google.android.gms.tasks.Task"), Long::class.javaPrimitiveType, TimeUnit::class.java)
                .invoke(null, task, 30L, TimeUnit.SECONDS)
            val text = result!!.javaClass.getMethod("getText").invoke(result) as String
            bmp.recycle()
            text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        } catch (e: Throwable) {
            log("menu: text recognition failed: ${e.cause ?: e}")
            ""
        }
    }
}
