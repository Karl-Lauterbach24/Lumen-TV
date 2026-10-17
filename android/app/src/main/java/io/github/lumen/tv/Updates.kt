package io.github.lumen.tv

import android.content.Context
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Looks for newer versions, once a day and on request: of this app (the tags of its repository on GitHub) and of MakeMKV (its
 * forum's download post). Nothing is installed: MakeMKV's files are part of the app (they cannot be exchanged while it runs, and are
 * not ours to hand out), and an update of the app is signed by whoever built it. The result is shown under Settings › Updates
 * and in the message about a disc whose key MakeMKV does not know.
 */
class Updates(context: Context, val installedApp: String, private val log: (String) -> Unit) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    fun installedAppName() = installedApp
    val appLatest: String get() = prefs.getString("app", "").orEmpty()
    val makemkvLatest: String get() = prefs.getString("mk", "").orEmpty()
    /** The MakeMKV the app carries, as its own output says (MSG:1005 "MakeMKV v2.0.0 …"). */
    var makemkvInstalled: String
        get() = prefs.getString("mkinst", "2.0.0").orEmpty()
        set(v) { if (v != makemkvInstalled) prefs.edit().putString("mkinst", v).apply() }
    val checkedAt: Long get() = prefs.getLong("at", 0)
    val error: String get() = prefs.getString("err", "").orEmpty()

    val appNewer: Boolean get() = appLatest.isNotEmpty() && compare(appLatest, installedApp) > 0
    val makemkvNewer: Boolean get() = makemkvLatest.isNotEmpty() && compare(makemkvLatest, makemkvInstalled) > 0
    fun due() = System.currentTimeMillis() - checkedAt > 20 * 3600 * 1000L

    /** Asks both sources (blocking; call off the main thread). Returns true when both answered. */
    fun check(): Boolean {
        var app = ""; var mk = ""; var err = ""
        try {
            val tags = JSONArray(get("https://api.github.com/repos/Karl-Lauterbach24/Lumen-TV/tags?per_page=30"))
            for (i in 0 until tags.length()) {
                val v = tags.getJSONObject(i).optString("name").removePrefix("v")
                if (Regex("\\d+\\.\\d+\\.\\d+").matches(v) && (app.isEmpty() || compare(v, app) > 0)) app = v
            }
        } catch (e: Exception) { err = "Lumen TV: ${e.javaClass.simpleName}"; log("updates: the app's tags: $e") }
        try {
            val text = get("https://forum.makemkv.com/forum/viewtopic.php?f=3&t=224").replace(Regex("<[^>]*>"), " ")
            for (m in Regex("MakeMKV\\s+v?(\\d+\\.\\d+\\.\\d+)").findAll(text)) if (mk.isEmpty() || compare(m.groupValues[1], mk) > 0) mk = m.groupValues[1]
        } catch (e: Exception) { err += (if (err.isEmpty()) "" else "; ") + "MakeMKV: ${e.javaClass.simpleName}"; log("updates: MakeMKV's page: $e") }
        prefs.edit().apply {
            if (app.isNotEmpty()) putString("app", app)
            if (mk.isNotEmpty()) putString("mk", mk)
            putString("err", err)
            if (err.isEmpty() || app.isNotEmpty() || mk.isNotEmpty()) putLong("at", System.currentTimeMillis())
        }.apply()
        log("updates: app $installedApp → latest ${app.ifEmpty { "?" }}, MakeMKV $makemkvInstalled → latest ${mk.ifEmpty { "?" }}${if (err.isEmpty()) "" else " ($err)"}")
        return err.isEmpty()
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "LumenTV")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    companion object {
        fun compare(a: String, b: String): Int {
            val x = a.split('.').map { it.toIntOrNull() ?: 0 }; val y = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }; if (d != 0) return d }
            return 0
        }
    }
}
