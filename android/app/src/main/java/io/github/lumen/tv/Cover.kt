package io.github.lumen.tv

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The picture of a film's disc, for the disc card and the disc page (like the tiles of a games console).
 * Series come from TVmaze (see [EpisodeGuide]: no key needed). For films there is no source without a key:
 * The Movie Database (themoviedb.org) gives posters to anyone with a free account; its key (the "API key",
 * or the longer "access token") is entered by the user in the settings. Only the film's name is sent.
 */
object Cover {
    private fun get(url: String, bearer: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "LumenTV")
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    /** The poster of the film [name] (as on the disc: "Evangelion 2.22"), or "" . [lang]: the television's language. */
    fun movie(name: String, key: String, lang: String, log: (String) -> Unit): String {
        if (key.isBlank() || name.isBlank()) return ""
        val token = key.length > 40          // a v4 access token is a long JWT, a v3 key has 32 characters
        // the name as it stands, then without the version numbers some discs add ("Evangelion 2.22" is "Evangelion: 2.0 …")
        val tries = listOf(name, name.replace(Regex("""\s+\d+(\.\d+)*$"""), "")).filter { it.isNotBlank() }.distinct()
        for (q in tries) {
            try {
                val url = "https://api.themoviedb.org/3/search/movie?query=" + URLEncoder.encode(q, "UTF-8") + "&language=$lang" + if (token) "" else "&api_key=$key"
                val r = JSONObject(get(url, if (token) key else null)).getJSONArray("results")
                for (i in 0 until minOf(r.length(), 5)) {
                    val p = r.getJSONObject(i).optString("poster_path", "")
                    if (p.isNotEmpty() && p != "null") {
                        log("cover: TMDB '$q' -> ${r.getJSONObject(i).optString("title")} (${r.getJSONObject(i).optString("release_date").take(4)})")
                        return "https://image.tmdb.org/t/p/w500$p"
                    }
                }
            } catch (e: Exception) { log("cover: TMDB: $e"); return "" }
        }
        log("cover: TMDB found no poster for '$name'")
        return ""
    }
}
