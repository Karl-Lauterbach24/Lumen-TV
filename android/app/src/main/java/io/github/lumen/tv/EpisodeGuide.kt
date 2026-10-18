package io.github.lumen.tv

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Names the episodes of a series disc with TVmaze (api.tvmaze.com: free, no key). Only the series name from
 * the disc is sent. Of the shows the search finds, the one whose season has episodes as long as the disc's
 * titles is taken (so "Doctor Who" of 2005 with 45 minutes, not the one of 1963 with 25). Which episodes a disc
 * holds is a guess from its number: a season of N episodes on discs of k has N / k discs, the episodes left over
 * lie on the first discs (13 episodes, 3 per disc: 4 + 3 + 3 + 3, so disc 3 holds episodes 8–10). The user can
 * shift the numbers ([shift]).
 */
object EpisodeGuide {
    class Episode(val season: Int, val number: Int, val name: String, val minutes: Int)
    /** [start]: index of the first episode in the season; [base]: where the reckoning put it; [slots]: how many places there are. */
    class Result(val show: String, val premiered: String, val episodes: List<Episode>, val firstGuessed: Boolean,
                 val start: Int = 0, val base: Int = 0, val slots: Int = 1, val image: String = "")

    fun toJson(r: Result): String = JSONObject().put("show", r.show).put("premiered", r.premiered).put("guessed", r.firstGuessed)
        .put("start", r.start).put("base", r.base).put("slots", r.slots).put("image", r.image)
        .put("episodes", JSONArray(r.episodes.map { JSONObject().put("s", it.season).put("n", it.number).put("name", it.name).put("min", it.minutes) })).toString()

    fun fromJson(s: String?): Result? = try {
        val o = JSONObject(s ?: return null)
        val a = o.getJSONArray("episodes")
        Result(o.getString("show"), o.optString("premiered"), (0 until a.length()).map { a.getJSONObject(it) }.map { Episode(it.getInt("s"), it.getInt("n"), it.optString("name"), it.optInt("min")) },
            o.optBoolean("guessed"), o.optInt("start"), o.optInt("base"), o.optInt("slots", 1), o.optString("image"))
    } catch (_: Exception) { null }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 25000
        c.setRequestProperty("User-Agent", "LumenTV")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    /** The episodes on the disc, in the order of [minutes] (the disc's episode titles), or null. */
    fun lookup(series: String, season: Int, discNo: Int, minutes: List<Int>, shift: Int, log: (String) -> Unit): Result? {
        if (series.isBlank() || season <= 0 || minutes.isEmpty()) return null
        return try {
            val found = JSONArray(get("https://api.tvmaze.com/search/shows?q=" + URLEncoder.encode(series, "UTF-8")))
            var best: Pair<Double, Result>? = null
            for (i in 0 until minOf(found.length(), 5)) {
                val show = found.getJSONObject(i).getJSONObject("show")
                // (one show that does not answer in time – a long one, like the Doctor Who of 1963 – is left out, not the whole look-up)
                val eps = try { JSONArray(get("https://api.tvmaze.com/shows/${show.getInt("id")}/episodes?specials=1")) }
                    catch (ex: Exception) { log("TVmaze: ${show.optString("name")} (${show.optString("premiered")}): $ex"); continue }
                // the season as the boxes have it: its episodes, led by a long special shown after the last season's finale
                // (Doctor Who's Christmas specials: TVmaze files "The Christmas Invasion" under season 1, the box of season 2
                // begins with it); short extras ("Attack of the Graske", 14 min) are not episodes
                val all = (0 until eps.length()).map { eps.getJSONObject(it) }
                fun air(o: JSONObject) = o.optString("airstamp", o.optString("airdate"))
                fun regular(s: Int) = all.filter { it.optInt("season") == s && !it.isNull("number") }.sortedBy { it.getInt("number") }
                val lastBefore = regular(season - 1).maxOfOrNull { air(it) }
                val lead = if (lastBefore == null) emptyList() else all.filter { it.optInt("season") == season - 1 && it.isNull("number") &&
                    it.optInt("runtime") >= 40 && air(it) > lastBefore }.sortedBy { air(it) }
                val inSeason = (lead + regular(season)).map {
                    Episode(season, if (it.isNull("number")) 0 else it.getInt("number"), it.optString("name"), it.optInt("runtime", show.optInt("averageRuntime", 0)))
                }
                if (inSeason.size < minutes.size) continue
                val k = minutes.size
                val discs = maxOf(1, inSeason.size / k)
                val extra = inSeason.size - discs * k
                val wanted = if (discNo > 0) (discNo - 1) * k + minOf(discNo - 1, maxOf(0, extra)) else 0
                val start = wanted.coerceAtMost(inSeason.size - k)
                val pick = inSeason.subList(start, start + k)
                // how far the lengths are off (TVmaze gives the slot, a disc the real running time)
                var off = pick.indices.sumOf { j -> kotlin.math.abs(pick[j].minutes - minutes[j]).toDouble() } / k
                // a season too short for this disc number (e.g. the 2023 "Doctor Who" with 8 episodes for disc 3) counts against it,
                // and so does another name ("Doctor Who Confidential" has the same lengths)
                if (start != wanted) off += 10
                if (!show.optString("name").equals(series.trim(), ignoreCase = true)) off += 3
                log("TVmaze: ${show.optString("name")} (${show.optString("premiered")}) season $season: ${inSeason.size} episodes, lengths off by %.0f min".format(java.util.Locale.ROOT, off))
                if (best == null || off < best.first) {
                    val from = (start + shift).coerceIn(0, inSeason.size - k)
                    best = off to Result(show.optString("name"), show.optString("premiered"), inSeason.subList(from, from + k), discNo > 0,
                        from, start, inSeason.size - k + 1, show.optJSONObject("image")?.optString("medium").orEmpty().replace("http://", "https://"))
                }
            }
            best?.takeIf { it.first <= 12 }?.second
        } catch (e: Exception) {
            log("TVmaze: $e"); null
        }
    }
}
