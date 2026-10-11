package io.github.lumen.tv

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.pm.PackageManager
import android.os.Build
import android.os.storage.StorageManager
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * The storage connected to the television, as the page shows it: volumes, folders, films.
 *
 * Android keeps apps away from the raw file system of a USB drive (scoped storage); what the system
 * has indexed it hands out through MediaStore – with the folder each file lies in. So folders are
 * built from the indexed files: a folder with no film in it (below) does not show.
 */
class Drives(private val activity: Activity, private val discs: Discs) {
    private var waiting = mutableListOf<() -> Unit>()
    private var asked = false
    var onPermission: (() -> Unit)? = null

    private fun permissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun granted() = permissions().all { activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Runs [then] once the permission is there (asks for it the first time). */
    private fun withPermission(then: () -> Unit, denied: () -> Unit) {
        if (granted()) { then(); return }
        synchronized(this) { waiting.add(then) }
        if (!asked) {
            asked = true
            activity.runOnUiThread { activity.requestPermissions(permissions(), 77) }
        } else {
            denied()
        }
    }

    fun permissionResult(ok: Boolean) {
        val run: List<() -> Unit>
        synchronized(this) { run = waiting.toList(); waiting.clear() }
        asked = false
        if (ok) run.forEach { it() }
        onPermission?.invoke()
    }

    private data class Volume(val media: String, val name: String, val detail: String)

    private fun volumes(): List<Volume> {
        val sm = activity.getSystemService(Activity.STORAGE_SERVICE) as StorageManager
        val out = mutableListOf<Volume>()
        for (v in sm.storageVolumes) {
            if (v.state != "mounted" && v.state != "mounted_ro") continue
            val media = if (Build.VERSION.SDK_INT >= 30) v.mediaStoreVolumeName ?: continue
                        else if (v.isPrimary) "external_primary" else (v.uuid ?: continue).lowercase()
            val name = v.getDescription(activity)
            out.add(Volume(media, name, if (v.isRemovable) "USB" else ""))
        }
        return out
    }

    fun list(done: (String) -> Unit) {
        thread {
            val a = JSONArray()
            // the discs do not depend on the storage permission: they are read through USB directly
            val found = discs.volumes(discs.refresh())
            for (i in 0 until found.length()) a.put(found.get(i))
            if (granted()) {
                for (v in volumes()) a.put(JSONObject().put("name", v.name).put("path", v.media + "|").put("detail", v.detail))
            } else if (!asked) {
                asked = true
                activity.runOnUiThread { activity.requestPermissions(permissions(), 77) }
            }
            if (discs.copiedFolder().listFiles().orEmpty().any { it.isDirectory })
                a.put(JSONObject().put("name", "Copied discs").put("path", "app|").put("detail", ""))
            done(a.toString())
        }
    }

    fun folder(path: String, done: (String) -> Unit) {
        val bar = path.indexOf('|')
        if (bar < 0) { done("[]"); return }
        val volume = path.substring(0, bar)
        val rel = path.substring(bar + 1)
        // a disc (its files are read from the disc itself, no permission needed)
        if (volume.startsWith("disc:")) { thread { done(discs.folder(volume.removePrefix("disc:"), rel).toString()) }; return }
        // what the app copied from discs
        if (volume == "app") {
            thread {
                val a = JSONArray()
                val dir = File(discs.copiedFolder(), rel)
                for (f in dir.listFiles().orEmpty().sortedBy { it.name }) {
                    if (f.isDirectory) a.put(JSONObject().put("name", f.name).put("path", "app|$rel${f.name}/").put("dir", true).put("size", 0))
                    else a.put(JSONObject().put("name", f.name).put("path", "app|$rel${f.name}").put("dir", false).put("size", f.length())
                        .put("uri", android.net.Uri.fromFile(f).toString()).put("video", true))
                }
                done(a.toString())
            }
            return
        }
        withPermission({
            thread { done(read(volume, rel).toString()) }
        }, { done("[]") })
    }

    private fun read(volume: String, rel: String): JSONArray {
        val dirs = linkedSetOf<String>()
        val files = JSONArray()
        val tables = listOf(MediaStore.Video.Media.getContentUri(volume), MediaStore.Audio.Media.getContentUri(volume))
        val relCol = if (Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
        for (table in tables) {
            val isVideo = table == tables[0]
            try {
                activity.contentResolver.query(
                    table, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, relCol),
                    null, null, MediaStore.MediaColumns.DISPLAY_NAME
                )?.use { c ->
                    while (c.moveToNext()) {
                        var where = c.getString(3) ?: continue
                        if (Build.VERSION.SDK_INT < 29) where = where.substringBeforeLast('/', "").substringAfter("/storage/", "").substringAfter('/', "") + "/"
                        if (where == "/") where = ""
                        if (!where.startsWith(rel)) continue
                        val rest = where.substring(rel.length)
                        if (rest.isEmpty()) {
                            val id = c.getLong(0)
                            val uri = ContentUris.withAppendedId(table, id)
                            files.put(JSONObject().put("name", c.getString(1)).put("path", "$volume|$rel${c.getString(1)}")
                                .put("dir", false).put("size", c.getLong(2)).put("uri", uri.toString()).put("video", isVideo))
                        } else {
                            dirs.add(rest.substringBefore('/'))
                        }
                    }
                }
            } catch (_: Exception) { /* a volume that went away while asking */ }
        }
        val out = JSONArray()
        for (d in dirs) out.put(JSONObject().put("name", d).put("path", "$volume|$rel$d/").put("dir", true).put("size", 0))
        for (i in 0 until files.length()) out.put(files.get(i))
        return out
    }
}
