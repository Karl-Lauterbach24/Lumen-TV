package io.github.lumen.tv

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Lumen TV for Android TV: the receiver page of Lumen (assets/, the same files Lumen serves to
 * browsers and the Samsung and LG apps) in a WebView, with Media3's player behind it. The page is
 * the interface; what a web page cannot do comes from here through [Bridge]: playing (Dolby and DTS
 * as they are, HDR, the film's frame rate – the player does that on a television), the drives
 * connected to the television, the network address.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private val main = Handler(Looper.getMainLooper())
    private lateinit var drives: Drives
    private lateinit var discs: Discs
    private var pageReady = false
    private var bd: Bd? = null
    private var bdFs: DiscFs? = null
    private var bdKey = ""
    private var remoteBd: RemoteBd? = null
    private lateinit var bdOverlay: android.view.View
    private lateinit var bdPg: android.widget.ImageView
    private lateinit var bdIg: android.widget.ImageView
    private val bdBitmaps = arrayOfNulls<android.graphics.Bitmap>(2)
    private var lastLogged = -1L
    // MakeMKV (test build): the drive lent to it, the pipe the player reads
    private lateinit var makemkv: MakeMkv
    @Volatile private var mkKey: String? = null
    private var mkPipe: PipeStream? = null
    private val mkTitles = HashMap<String, List<MakeMkv.Title>>()
    /** the episodes' names from TVmaze, per disc */
    private val mkGuide = HashMap<String, EpisodeGuide.Result>()
    private var playerLong = false
    /** the drive got stuck during a film: the disc is ejected and loaded again before the next one */
    @Volatile private var mkReload = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        playerView = findViewById(R.id.player)
        web = findViewById(R.id.web)
        bdOverlay = findViewById(R.id.bdOverlay)
        bdPg = findViewById(R.id.bdPg)
        bdIg = findViewById(R.id.bdIg)
        discs = Discs(this) { js("window.__lumenDrivesChanged&&window.__lumenDrivesChanged()") }
        drives = Drives(this, discs)
        drives.onPermission = { js("window.__lumenDrivesChanged&&window.__lumenDrivesChanged()") }
        makemkv = MakeMkv(this) { mkLog(it) }
        mkLog("Lumen TV ${packageManager.getPackageInfo(packageName, 0).versionName}, ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, " +
            "MakeMKV ${if (makemkv.available) "available (${makemkv.abi})" else "not available on this device"}, key: ${makemkv.keyMode.ifEmpty { "none" }}")
        makemkv.refreshBetaKeyIfDue()
        makemkv.onVersion = { updates.makemkvInstalled = it }
        makemkv.onKeyDownload = { on ->
            if (on) js("window.lumenToast&&window.lumenToast(${JSONObject.quote(t("MakeMKV lädt seine Schlüsseldatenbank (für 4K-Discs, einige Minuten) …", "MakeMKV is downloading its key database (for 4K discs, a few minutes) …"))},600000,-1)")
            else say("", 1)
        }
        kotlin.concurrent.thread(isDaemon = true) {
            if (updates.due()) updates.check()
            announceUpdates()
        }
        discs.extraEntries = { key, rel -> mkEntries(key, rel) }
        discs.nameFor = { key -> mkDiscName(key) }
        discs.coverFor = { key, label ->
            // (a drive whose disc is not read yet has only its own name: no picture for that – it would be some earlier disc's)
            val unread = mkLabels[key] == null && discs.label(key).isEmpty()
            val l = mkLabels[key] ?: label
            val c = if (unread) "" else ownCover(key, l).ifEmpty { makemkv.cover(l) }
            // a disc seen before: its titles from the memory (name, picture), once per start
            if (key.startsWith("usb") && makemkv.available && mkTitles[key] == null && makemkv.hasTitles(l) && mkCoverTried.add(key)) kotlin.concurrent.thread {
                makemkv.loadTitles(l)?.let { mkLabels[key] = l; mkTitles[key] = it; mkLookup(key, it) }
            }
            c
        }
        makemkv.onStuck = {
            if ((mkPipe?.position ?: 0L) > 0L) {
                mkReload = true
                mkLog("drive stuck during the film")
                say(t("Das Laufwerk antwortet während des Films nicht mehr. Bitte neu starten (die Disc wird vorher aus- und eingezogen).", "The drive stopped answering during the film. Start it again (the disc is ejected and loaded again first)."), 20000)
            }
        }

        web.setBackgroundColor(Color.TRANSPARENT)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW   // Lumen answers over http in the local network
            allowFileAccess = false
            textZoom = 100                                              // the page is laid out in its own units
        }
        val loader = WebViewAssetLoader.Builder()
            // the pictures the discs bring (BDMV/META/DL/Jacket*.jpg), copied to the app's folder
            .addPathHandler("/cover/", WebViewAssetLoader.InternalStoragePathHandler(this, java.io.File(filesDir, "covers").apply { mkdirs() }))
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)
            override fun onPageFinished(view: WebView, url: String) { pageReady = true }
        }
        web.addJavascriptInterface(Bridge(), "LumenAndroid")
        web.loadUrl("https://appassets.androidplatform.net/index.html")
        web.requestFocus()
    }

    // ---- the player behind the page ----
    private fun ensurePlayer(long: Boolean = false): ExoPlayer {
        player?.let { if (playerLong == long) return it; it.release(); player = null }
        playerLong = long
        // the television's own decoders first; what it cannot decode (DTS, TrueHD, often Dolby) the app's FFmpeg does
        val factory = object : DefaultRenderersFactory(this) {
            // sound as PCM always: the television reports Dolby Digital passthrough (after a restart, with its audio output on
            // "auto"), then cannot create the track for it ("Cannot create AudioTrack") – decoding here works in every setting
            override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder()
                    .setAudioCapabilities(androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
        }.setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        // a short look-ahead: the menu of a disc runs ahead of the picture by what the player has buffered;
        // MakeMKV's stream comes in bursts: a longer one, but held to 32 MB (MakeMKV needs the memory of a small television)
        val load = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .apply {
                if (long) setBufferDurationsMs(6000, 15000, 2500, 4000).setTargetBufferBytes(12 shl 20).setPrioritizeTimeOverSizeThresholds(false)
                else setBufferDurationsMs(2000, 4000, 800, 1500).setPrioritizeTimeOverSizeThresholds(true)   // (a disc stream runs seconds ahead in the picture: size must not stop the loading while the sound is still missing)
            }.build()
        val p = ExoPlayer.Builder(this, factory).setLoadControl(load).build()
        p.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onAudioInputFormatChanged(e: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, f: androidx.media3.common.Format, r: androidx.media3.exoplayer.DecoderReuseEvaluation?) { mkLog("audio: input format ${f.sampleMimeType} ${f.channelCount}ch ${f.sampleRate} Hz ${f.codecs}") }
            override fun onAudioDecoderInitialized(e: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, n: String, t: Long, d: Long) { mkLog("audio: decoder $n") }
            override fun onDownstreamFormatChanged(e: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, d: androidx.media3.exoplayer.source.MediaLoadData) { d.trackFormat?.let { mkLog("downstream: ${it.sampleMimeType} ${it.channelCount}ch ${it.sampleRate} Hz ${it.width}x${it.height}") } }
            override fun onAudioSinkError(e: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, x: Exception) { mkLog("audio: sink error $x") }
            override fun onAudioCodecError(e: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, x: Exception) { mkLog("audio: codec error $x") }
            override fun onAudioDecoderReleased(e: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, n: String) { mkLog("audio: decoder released") }
            override fun onAudioUnderrun(e: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, b: Int, ms: Long, el: Long) { mkLog("audio: underrun") }
            override fun onAudioPositionAdvancing(e: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, ms: Long) { mkLog("audio: position advancing") }
            override fun onPositionDiscontinuity(e: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, o: Player.PositionInfo, n: Player.PositionInfo, reason: Int) { mkLog("player: discontinuity reason $reason") }
        })
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (mkKey != null) mkLog("player: state ${when (state) { Player.STATE_BUFFERING -> "buffering"; Player.STATE_READY -> "ready"; Player.STATE_ENDED -> "ended"; else -> "idle" }} at ${p.currentPosition / 1000} s, buffered to ${p.bufferedPosition / 1000} s")
                when (state) {
                    Player.STATE_BUFFERING -> event("waiting")
                    Player.STATE_READY -> event(if (p.playWhenReady) "playing" else "pause")
                    Player.STATE_ENDED -> {
                        // the disc's menu: its stream ends where the streams change (bdmenu says RESTART) and goes on in a new one
                        val rb = remoteBd
                        if (rb != null && rb.opened) { mkLog("disc menu: the stream goes on in a new one (none was queued)"); restartMenuStream(p, rb) }
                        else event("ended")
                    }
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { event(if (isPlaying) "playing" else "pause") }
            override fun onPlayerError(error: PlaybackException) {
                val fmt = (error as? androidx.media3.exoplayer.ExoPlaybackException)?.rendererFormat
                val what = if (fmt != null) " (${fmt.sampleMimeType} ${fmt.width}x${fmt.height})" else ""
                var cause: Throwable? = error.cause
                while (cause?.cause != null && cause.cause !== cause) cause = cause.cause
                val failed = bdFs?.failedUnits() ?: 0
                val why = when {
                    failed > 20 -> "The disc's stream could not be decrypted ($failed units). " + discs.keyInfo(bdKey)
                    // a 4K picture on a television whose decoder stops at 1920x1088
                    fmt != null && fmt.sampleMimeType?.startsWith("video/") == true && (fmt.width > 1920 || fmt.height > 1088) && !videoFits(fmt) ->
                        t("Dieser Fernseher kann ${fmt.width}×${fmt.height} nicht abspielen: sein Video-Decoder schafft höchstens ${maxVideo()}. " +
                            "Die 4K-Disc wird gelesen und entschlüsselt, nur das Bild geht nicht – die Blu-ray in Full HD (oft in der Packung) oder Lumen am Computer (rechnet das Bild herunter) gehen.",
                          "This television cannot play ${fmt.width}×${fmt.height}: its video decoder stops at ${maxVideo()}. The 4K disc is read and decrypted, only the picture does not work – " +
                            "the Full HD Blu-ray (often in the box) or Lumen on a computer (which scales the picture down) work.")
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                        "This television cannot decode this video$what: ${error.errorCodeName}."
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
                        "The stream of the disc could not be read (${error.errorCodeName}${cause?.message?.let { ": $it" } ?: ""})."
                    else -> "Playback stopped: ${error.errorCodeName}${cause?.message?.let { " – $it" } ?: ""}$what"
                }
                android.util.Log.w("LumenTV", "player error: $why / $error")
                if (mkKey != null) mkLog("player error: $why / $error ${android.util.Log.getStackTraceString(error).take(1500)}")
                event("error")
                // (after the page's own "does not play" message, which it shows at once)
                main.postDelayed({ js("window.lumenToast&&window.lumenToast(${JSONObject.quote(why)},20000)") }, 500)
            }
        })
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguages(java.util.Locale.getDefault().language).build()
        p.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onDroppedVideoFrames(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, dropped: Int, ms: Long) {
                if (mkKey != null) mkLog("player: $dropped frames dropped in $ms ms at ${t.currentPlaybackPositionMs / 1000} s")
            }
            override fun onTracksChanged(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, tracks: androidx.media3.common.Tracks) {
                if (mkKey == null) return
                applyPicks(p, tracks)
                for (g in tracks.groups) for (i in 0 until g.length) {
                    val f = g.getTrackFormat(i)
                    mkLog("track: ${f.sampleMimeType} ${f.language ?: ""} ${f.label ?: ""} ${if (f.channelCount > 0) "${f.channelCount}ch" else ""}${if (f.width > 0) "${f.width}x${f.height}" else ""} " +
                        "supported=${androidx.media3.common.C.getFormatSupportString(g.getTrackSupport(i))} selected=${g.isTrackSelected(i)}")
                }
            }
            override fun onAudioCodecError(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, e: Exception) { if (mkKey != null) mkLog("player: audio codec error $e") }
            override fun onAudioSinkError(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, e: Exception) { if (mkKey != null) mkLog("player: audio sink error $e") }
        })
        playerView.player = p
        player = p
        return p
    }

    private val tick = object : Runnable {
        override fun run() {
            if (player != null) event("timeupdate")
            main.postDelayed(this, 500)
        }
    }

    /** Tells the page what the player does; the page keeps its own video element in step with it. */
    private var stallPos = -1L
    private var stallSince = 0L
    private fun event(name: String) {
        val p = player ?: return
        val dur = if (p.duration > 0) p.duration else 0
        if (name == "timeupdate") {      // (diagnostics: the threads of the player when the picture stands still)
            if (p.currentPosition != stallPos) { stallPos = p.currentPosition; stallSince = System.currentTimeMillis() }
            else if (p.isPlaying && System.currentTimeMillis() - stallSince in 6000..6600) {
                for ((t, st) in Thread.getAllStackTraces()) if (t.name.startsWith("ExoPlayer") || t.name.startsWith("Loader"))
                    android.util.Log.i("LumenTV", "stall: ${t.name} ${t.state}: " + st.take(9).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}" })
            }
        }
        if (name == "timeupdate" && p.currentPosition / 5000 != lastLogged) {
            lastLogged = p.currentPosition / 5000
            android.util.Log.i("LumenTV", "position ${p.currentPosition} duration ${p.duration} seekable ${p.isCurrentMediaItemSeekable} state ${p.playbackState} playing ${p.isPlaying} buffered ${p.bufferedPosition} fifo ${FifoDataSource.total / 1000000.0} MB")
        }
        js("window.__lumenEvent&&window.__lumenEvent('$name',${p.currentPosition},$dur,${if (p.isPlaying || (p.playWhenReady && p.playbackState == Player.STATE_BUFFERING)) "false" else "true"},${p.bufferedPosition},${p.playbackState == Player.STATE_BUFFERING})")
    }

    fun js(code: String) {
        main.post { if (pageReady) web.evaluateJavascript(code, null) }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    /** The audio and subtitle languages chosen (in the settings or on the disc page) for what is about to play. */
    /** What is playing through MakeMKV: its disc's label and the streams the player will list (the disc page's choice is applied to them). */
    @Volatile private var mkPlaying: MakeMkv.Title? = null
    @Volatile private var mkPlayingLabel = ""
    private var picksFor: Any? = null
    private var pausedForPanel = false

    /** The disc page's choice of an audio or subtitle track (by position among the title's streams), applied once the player lists the tracks. */
    private fun applyPicks(p: ExoPlayer, tracks: androidx.media3.common.Tracks) {
        val title = mkPlaying ?: return
        if (picksFor === title) return
        if (tracks.groups.none { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }) return
        picksFor = title
        val b = p.trackSelectionParameters.buildUpon()
        var changed = false
        for ((type, kind) in listOf("audio" to androidx.media3.common.C.TRACK_TYPE_AUDIO, "text" to androidx.media3.common.C.TRACK_TYPE_TEXT)) {
            val pick = makemkv.pick(mkPlayingLabel, type)
            if (pick.isEmpty()) continue
            if (pick == "off") { b.setTrackTypeDisabled(kind, true); changed = true; continue }
            val idx = pick.toIntOrNull() ?: continue
            val groups = tracks.groups.filter { it.type == kind }
            val g = groups.getOrNull(idx) ?: continue
            b.setTrackTypeDisabled(kind, false).setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, 0)); changed = true
            mkLog("player: $type track ${idx + 1} chosen on the disc page")
        }
        if (changed) p.trackSelectionParameters = b.build()
    }

    private fun applyLanguages(p: ExoPlayer) {
        val b = p.trackSelectionParameters.buildUpon()
        val a = makemkv.audioLang
        b.setPreferredAudioLanguages(*(listOf(a, java.util.Locale.getDefault().language).filter { it.isNotEmpty() }.toTypedArray()))
        when (val sl = makemkv.subLang) {
            "off" -> b.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
            "" -> b.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
            else -> b.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(sl).setSelectUndeterminedTextLanguage(false)
        }
        b.clearOverrides()
        p.trackSelectionParameters = b.build()
    }

    private fun open(uri: Uri, hls: Boolean, prepared: androidx.media3.extractor.ExtractorsFactory? = null) {
        val p = ensurePlayer(uri.scheme == "makemkv")
        if (uri.scheme != "makemkv") applyLanguages(p)
        if (uri.scheme == "makemkv") {
            val pipe = mkPipe ?: return
            applyLanguages(p)
            val factory = androidx.media3.datasource.DataSource.Factory { PipeDataSource(pipe) }
            // only Matroska, and no look for the index at the end: the stream cannot go there and back
            val extractors = androidx.media3.extractor.ExtractorsFactory {
                arrayOf(LiveMatroska(androidx.media3.extractor.mkv.MatroskaExtractor(
                    androidx.media3.extractor.text.DefaultSubtitleParserFactory(),
                    androidx.media3.extractor.mkv.MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES), pipe))
            }
            val source = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(factory, extractors)
                .setContinueLoadingCheckIntervalBytes(256 * 1024)
                .createMediaSource(MediaItem.fromUri(uri))
            p.setMediaSource(source)
        } else if (uri.scheme == "bdnav") {
            val b = bd ?: return
            val factory = BdDataSource.Factory(b) { type, value -> bdEvent(type, value) }
            val extractors = androidx.media3.extractor.DefaultExtractorsFactory()
                .setTsExtractorFlags(BLURAY_TS_FLAGS)
                .setConstantBitrateSeekingEnabled(false)
            val source = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(factory, extractors)
                .createMediaSource(MediaItem.fromUri(uri))
            p.setMediaSource(source)
        } else if (uri.scheme == "makemenu") {
            val rb = remoteBd ?: return
            p.setMediaSource(menuSource(rb))
        } else if (uri.scheme == "disc") {
            // a file of a disc the app reads itself
            val factory = DiscDataSource.Factory { key, path -> discs.reader(key, path) }
            // a Blu-ray title: the length and seeking from the stream's clock (see BdavSeek)
            val extractors: androidx.media3.extractor.ExtractorsFactory = prepared ?: androidx.media3.extractor.DefaultExtractorsFactory().setTsExtractorFlags(BLURAY_TS_FLAGS)
            val source = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(factory, extractors)
                .createMediaSource(MediaItem.fromUri(uri))
            p.setMediaSource(source)
        } else if (prepared != null) {
            val source = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(androidx.media3.datasource.DefaultDataSource.Factory(this), prepared)
                .createMediaSource(MediaItem.fromUri(uri))
            p.setMediaSource(source)
        } else {
            val item = MediaItem.Builder().setUri(uri).apply { if (hls) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
            p.setMediaItem(item)
        }
        p.playWhenReady = true
        p.prepare()
        playerView.visibility = View.VISIBLE
        main.removeCallbacks(tick)
        main.post(tick)
    }

    /** Work that reads from the disc, done off the main thread before the player is asked. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun prepare(uri: Uri): androidx.media3.extractor.ExtractorsFactory? {
        val path = uri.path.orEmpty().removePrefix("/")
        return try {
            if (uri.scheme == "disc" && path.endsWith(".m2ts", true)) {
                chapters = clipChapters(uri.host ?: "", path.substringAfterLast('/').substringBeforeLast('.'))
                val r = discs.reader(uri.host ?: "", path) ?: return null
                val d = BdavSeek.duration(r, 192)
                if (d > 0) ProportionalTsFactory(d, r.size / 192 * 188) else null
            } else if (uri.scheme == "file" && path.endsWith(".ts", true)) {
                // a transport stream the app wrote itself (a copied disc title)
                val r = LocalFileReader(java.io.File(path))
                val d = BdavSeek.duration(r, 188)
                if (d > 0) ProportionalTsFactory(d, r.size) else null
            } else null
        } catch (_: Exception) { null }
    }

    /** The chapters of what plays (seconds), for the controls. */
    @Volatile private var chapters: List<Double> = emptyList()
    /** The chapters of a disc's clips, from its playlists (key -> clip -> seconds of the clip). */
    private val clipMarks = HashMap<String, Map<String, List<Double>>>()

    private fun clipChapters(key: String, clip: String): List<Double> {
        val all = synchronized(clipMarks) { clipMarks[key] } ?: run {
            val out = HashMap<String, List<Double>>()
            try {
                val udf = discs.udf(key)
                if (udf != null) for (e in udf.list("BDMV/PLAYLIST").filter { !it.dir && it.name.endsWith(".mpls", true) }) {
                    val r = udf.open("BDMV/PLAYLIST/${e.name}")
                    val b = ByteArray(r.size.toInt())
                    var n = 0
                    while (n < b.size) { val k = r.read(n.toLong(), b, n, b.size - n); if (k <= 0) break; n += k }
                    // a clip in several playlists: the one with the most chapters for it
                    Playlist.parse(e.name, b)?.clipMarks?.forEach { (c, m) -> if (m.size > (out[c]?.size ?: 1)) out[c] = m }
                }
            } catch (ex: Exception) { android.util.Log.i("LumenTV", "chapters: $ex") }
            synchronized(clipMarks) { clipMarks[key] = out }
            out
        }
        return all[clip].orEmpty()
    }

    private fun infoJson(): String {
        val o = JSONObject()
        val p = player ?: return o.toString()
        o.put("chapters", org.json.JSONArray(chapters.map { (it * 1000).toLong() }))
        o.put("seekable", p.isCurrentMediaItemSeekable && remoteBd == null && bd == null)
        // MakeMKV's stream: only what the cache holds can be jumped to
        val pipe = mkPipe
        if (mkKey != null && pipe != null) pipe.cachedRangeMs()?.let { o.put("seekFrom", it.first).put("seekTo", it.second) }
        p.videoFormat?.let { f ->
            val h = if (f.height > 0) f.height else 0
            val res = when { h >= 2000 -> "4K"; h >= 1000 -> "1080p"; h >= 700 -> "720p"; h > 0 -> "${h}p"; else -> "" }
            val hdr = f.colorInfo?.colorTransfer.let { it == androidx.media3.common.C.COLOR_TRANSFER_ST2084 || it == androidx.media3.common.C.COLOR_TRANSFER_HLG }
            val codec = when (f.sampleMimeType) { MimeTypes.VIDEO_H265 -> "HEVC"; MimeTypes.VIDEO_H264 -> "H.264"; MimeTypes.VIDEO_MPEG2 -> "MPEG-2"; MimeTypes.VIDEO_VC1 -> "VC-1"; MimeTypes.VIDEO_AV1 -> "AV1"; else -> "" }
            o.put("video", listOf(res, if (hdr) "HDR" else "", codec).filter { it.isNotEmpty() }.joinToString(" · "))
        }
        try {
            val tr = org.json.JSONArray(tracksJson())
            for (i in 0 until tr.length()) {
                val t = tr.getJSONObject(i)
                if (t.optBoolean("selected") && !o.has(t.getString("type"))) o.put(t.getString("type"), t.getString("label"))
            }
        } catch (_: Exception) { }
        return o.toString()
    }

    /** A disc with its menus (libbluray). Runs on a worker thread. */
    private fun startBd(uri: Uri) {
        val say = { msg: String -> js("window.lumenToast&&window.lumenToast(${JSONObject.quote(msg)},7000)") }
        val fs = discs.fs(uri.host ?: "")
        if (fs == null) { say(discs.keyInfo(uri.host ?: "")); return }
        val b = Bd()
        if (!Bd.available()) { say("Menus are not available on this device."); return }
        bdFs = fs
        bdKey = uri.host ?: ""
        b.overlay = { plane, x, y, w, h, px -> bdDraw(plane, x, y, w, h, px) }
        if (!b.open(fs)) { say("The disc could not be opened for its menus."); return }
        val info = try { JSONObject(b.info()) } catch (_: Exception) { JSONObject() }
        android.util.Log.i("LumenTV", "disc: $info")
        if (info.optInt("hdmv") == 0 && info.optInt("bdj") > 0) {
            say("This disc's menu is a Java program (BD-J); this app cannot run it. Its titles are listed instead.")
            b.close(); return
        }
        if (info.optInt("aacs") != 0 && info.optInt("bdplus") != 0 && info.optInt("bdplusHandled") == 0)
            say("This disc has BD+; its picture may be damaged.")
        if (!b.play()) { say("The disc's menu could not be started."); b.close(); return }
        bd = b
        main.post {
            bdOverlay.visibility = View.VISIBLE
            open(uri, false, null)
        }
    }

    private fun bdEvent(type: Int, value: Int) {
        android.util.Log.i("LumenTV", "bd event $type $value")
        when (type) {
            Bd.EV_ENCRYPTED -> js("window.lumenToast&&window.lumenToast('This disc is encrypted and cannot be played.',7000)")
            Bd.EV_SOUND -> bd?.playSound(value)
            Bd.EV_ERROR -> js("window.lumenToast&&window.lumenToast('The disc could not be played.',7000)")
        }
    }

    /** A plane of the menu changed. */
    private fun bdDraw(plane: Int, x: Int, y: Int, w: Int, h: Int, px: IntArray?) {
        if (plane !in 0..1) return
        val bmp = synchronized(bdBitmaps) {
            var m = bdBitmaps[plane]
            if (m == null) { m = android.graphics.Bitmap.createBitmap(1920, 1080, android.graphics.Bitmap.Config.ARGB_8888); bdBitmaps[plane] = m }
            if (px == null) m.eraseColor(0) else m.setPixels(px, 0, w, x, y, w, h)
            m
        }
        main.post { (if (plane == 0) bdPg else bdIg).apply { setImageBitmap(bmp); invalidate() } }
    }

    /** The stream of the disc's menu (a fresh extractor each time: one stream has one format per track). */
    private fun menuSource(rb: RemoteBd): androidx.media3.exoplayer.source.MediaSource {
        val extractors = androidx.media3.extractor.DefaultExtractorsFactory()
            .setTsExtractorFlags(BLURAY_TS_FLAGS)
            .setConstantBitrateSeekingEnabled(false)
        return androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(FifoDataSource.Factory(rb.fifo), extractors)
            .createMediaSource(MediaItem.fromUri("makemenu://stream/${System.nanoTime()}"))
    }

    private fun restartMenuStream(p: ExoPlayer, rb: RemoteBd) {
        p.setMediaSource(menuSource(rb))
        p.prepare()
        p.playWhenReady = true
    }

    /** Whether a hardware decoder of this television takes [f]'s size. */
    private fun videoFits(f: androidx.media3.common.Format): Boolean = try {
        android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { c ->
            !c.isEncoder && c.supportedTypes.any { it.equals(f.sampleMimeType, true) } &&
                c.getCapabilitiesForType(f.sampleMimeType).videoCapabilities?.isSizeSupported(f.width, f.height) == true &&
                !c.name.startsWith("c2.android") && !c.name.startsWith("OMX.google")
        }
    } catch (_: Exception) { true }

    private fun maxVideo(): String = try {
        android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals("video/hevc", true) } && !it.name.startsWith("c2.android") && !it.name.startsWith("OMX.google") }
            .mapNotNull { c -> c.getCapabilitiesForType("video/hevc").videoCapabilities?.let { "${it.supportedWidths.upper}×${it.supportedHeights.upper}" } }
            .firstOrNull() ?: "Full HD"
    } catch (_: Exception) { "Full HD" }

    private fun closeBd() {
        val b = bd ?: return
        bd = null
        bdOverlay.visibility = View.GONE
        bdBitmaps.fill(null)
        kotlin.concurrent.thread { b.close() }
    }

    private fun closePlayer() {
        closeBd()
        remoteBd?.let { rb -> remoteBd = null; bdOverlay.visibility = View.GONE; bdBitmaps.fill(null); rb.close() }
        stopMakeMkv()
        main.removeCallbacks(tick)
        player?.let { it.stop(); it.clearMediaItems() }
        playerView.visibility = View.INVISIBLE
    }

    // ---- audio and subtitle tracks ----
    private fun trackLabel(f: androidx.media3.common.Format): String {
        val lang = f.language?.takeIf { it.isNotEmpty() && it != "und" }?.let { java.util.Locale.forLanguageTag(it).getDisplayLanguage(java.util.Locale.getDefault()) }
            ?.replaceFirstChar { it.titlecase(java.util.Locale.getDefault()) }
        val mime = if (f.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) f.codecs ?: "" else f.sampleMimeType ?: ""
        val codec = when (mime) {
            MimeTypes.AUDIO_DTS -> "DTS"; MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS-HD"
            MimeTypes.AUDIO_AC3 -> "Dolby Digital"; MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital Plus"
            MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"; MimeTypes.AUDIO_AAC -> "AAC"; MimeTypes.AUDIO_RAW -> "PCM"
            MimeTypes.AUDIO_FLAC -> "FLAC"; MimeTypes.AUDIO_MPEG -> "MP3"; MimeTypes.AUDIO_OPUS -> "Opus"
            MimeTypes.APPLICATION_PGS -> "PGS"; MimeTypes.APPLICATION_VOBSUB -> "VobSub"; MimeTypes.TEXT_SSA -> "SSA"
            MimeTypes.APPLICATION_SUBRIP -> "SRT"
            else -> ""
        }
        val ch = when (f.channelCount) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 7 -> "6.1"; 8 -> "7.1"; else -> if (f.channelCount > 0) "${f.channelCount} ch" else "" }
        val parts = listOfNotNull(lang ?: f.label, codec.ifEmpty { null }, ch.ifEmpty { null })
        // MakeMKV names its tracks ("Surround 5.1", "Stereo"): only a name that says more is kept
        val name = f.label?.takeIf { l -> lang != null && parts.none { l.contains(it, true) } && !Regex("(?i)^(surround|stereo|mono)").containsMatchIn(l) }
        return (parts + listOfNotNull(name)).joinToString(" · ").ifEmpty { "?" }
    }

    private fun tracksJson(): String {
        val a = org.json.JSONArray()
        val p = player ?: return a.toString()
        val textOff = p.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_TEXT)
        val seen = HashMap<String, Int>()
        p.currentTracks.groups.forEachIndexed { gi, g ->
            val type = when (g.type) { androidx.media3.common.C.TRACK_TYPE_AUDIO -> "audio"; androidx.media3.common.C.TRACK_TYPE_TEXT -> "text"; else -> return@forEachIndexed }
            val nth = seen.merge(type, 1, Int::plus)!! - 1
            val ofType = mkPlaying?.streams?.filter { it.type == type }.orEmpty()
            if (type == "audio" && nth in coreCopies(ofType)) return@forEachIndexed
            val info = ofType.getOrNull(nth)
            val menu = mkKey?.let { mkMenuNamesOf(it) }
            val textLabel = if (type == "text" && ofType.isNotEmpty()) subtitleLabels(ofType, menu).getOrNull(nth)
                else if (type == "audio") menu?.audio?.get(nth - coreCopies(ofType).count { it < nth } + 1) else null
            for (i in 0 until g.length) {
                val f = g.getTrackFormat(i)
                a.put(JSONObject().put("type", type).put("id", "$gi:$i").put("label", textLabel ?: if (info != null) streamLabel(info) else trackLabel(f))
                    .put("selected", g.isTrackSelected(i) && !(type == "text" && textOff))
                    .put("ok", g.isTrackSupported(i))
                    .put("forced", f.selectionFlags and androidx.media3.common.C.SELECTION_FLAG_FORCED != 0))
            }
        }
        // alike labels get numbers (per type)
        for (type in listOf("audio", "text")) {
            val idx = (0 until a.length()).filter { a.getJSONObject(it).getString("type") == type }
            val labels = numbered(idx.map { a.getJSONObject(it).getString("label") })
            idx.forEachIndexed { n, i -> a.getJSONObject(i).put("label", labels[n]) }
        }
        return a.toString()
    }

    private fun selectTrack(type: String, which: String) {
        val p = player ?: return
        val live = mkKey != null
        val kind = if (type == "audio") androidx.media3.common.C.TRACK_TYPE_AUDIO else androidx.media3.common.C.TRACK_TYPE_TEXT
        val b = p.trackSelectionParameters.buildUpon()
        if (which == "off") b.setTrackTypeDisabled(kind, true)
        else {
            val gi = which.substringBefore(':').toIntOrNull() ?: return
            val ti = which.substringAfter(':').toIntOrNull() ?: return
            val g = p.currentTracks.groups.getOrNull(gi) ?: return
            if (ti >= g.length) return
            b.setTrackTypeDisabled(kind, false).setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, ti))
        }
        p.trackSelectionParameters = b.build()
        if (live && pausedForPanel) { pausedForPanel = false; p.play() }
        if (live) {
            // kept for the disc: the position of the track among those of its kind
            val gi = which.substringBefore(':').toIntOrNull()
            val nth = if (which == "off") "off" else p.currentTracks.groups.take(gi ?: 0).count { it.type == kind }.toString()
            makemkv.setPick(mkPlayingLabel, type, nth)
            mkLog("player: $type track $which chosen -> disc pick $nth")
        } else if (mkKey != null) mkLog("player: $type track $which chosen")
    }

    // ---- the settings of the television (the page asks for them: Settings › Playback, Settings › Disc and MakeMKV) ----
    private val langChoices = listOf("", "de", "en", "fr", "es", "it", "pt", "nl", "pl", "ja")

    private fun langLabel(code: String, off: String = "", auto: String = ""): String = when (code) {
        "" -> auto.ifEmpty { t("Automatisch", "Automatic") }
        "off" -> off.ifEmpty { t("Aus", "Off") }
        else -> langName(code)
    }

    private val updates by lazy { Updates(this, packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(), ::mkLog) }

    /** One toast per new version found (the app's or MakeMKV's). */
    private fun announceUpdates() {
        val p = getSharedPreferences("updates", MODE_PRIVATE)
        if (updates.appNewer && p.getString("told-app", "") != updates.appLatest) {
            p.edit().putString("told-app", updates.appLatest).apply()
            say(t("Lumen TV ${updates.appLatest} ist erschienen (Einstellungen › Updates).", "Lumen TV ${updates.appLatest} is out (Settings › Updates)."), 9000)
        } else if (updates.makemkvNewer && p.getString("told-mk", "") != updates.makemkvLatest) {
            p.edit().putString("told-mk", updates.makemkvLatest).apply()
            say(t("MakeMKV ${updates.makemkvLatest} ist erschienen (Einstellungen › Updates).", "MakeMKV ${updates.makemkvLatest} is out (Settings › Updates)."), 9000)
        }
    }

    private fun updateLine(now: String, latest: String, newer: Boolean) = when {
        latest.isEmpty() -> "$now · " + t("noch nicht geprüft", "not checked yet")
        newer -> "$now → $latest " + t("verfügbar", "available")
        else -> "$now · " + t("aktuell", "up to date")
    }

    private fun settingsJson(section: String): String {
        val a = org.json.JSONArray()
        fun item(id: String, label: String, detail: String, icon: String, off: Boolean = false) =
            a.put(JSONObject().put("id", id).put("label", label).put("detail", detail).put("icon", icon).put("off", off))
        when (section) {
            "" -> {
                item("playback", t("Wiedergabe", "Playback"), "${langLabel(makemkv.audioLang)} · ${langLabel(makemkv.subLang, t("Untertitel aus", "no subtitles"), t("Untertitel automatisch", "subtitles automatic"))}", "volume")
                item("updates", "Updates", if (updates.appNewer || updates.makemkvNewer) t("Neue Version verfügbar", "New version available") else if (updates.error.isNotEmpty()) t("Keine Verbindung", "no connection") else t("täglich geprüft", "checked daily"), "refresh")
                if (makemkv.available) item("disc", t("Disc und MakeMKV", "Disc and MakeMKV"),
                    when (makemkv.keyMode) { "beta" -> t("Beta-Schlüssel", "beta key"); "manual" -> t("eigener Schlüssel", "own key"); else -> t("kein Schlüssel", "no key") }, "disc")
            }
            "updates" -> {
                item("upd-app", "Lumen TV", updateLine(updates.installedAppName(), updates.appLatest, updates.appNewer), "download")
                if (makemkv.available) item("upd-mk", "MakeMKV", updateLine(updates.makemkvInstalled, updates.makemkvLatest, updates.makemkvNewer), "download")
                item("upd-check", t("Jetzt nach Updates suchen", "Check for updates now"), if (updates.error.isNotEmpty()) t("Keine Verbindung: ", "No connection: ") + updates.error
                    else if (updates.checkedAt > 0) t("zuletzt ", "last ") + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(updates.checkedAt)) else t("noch nie", "never"), "refresh")
                item("note", t("Wird nicht selbst installiert", "Not installed by itself"), t("MakeMKV steckt in der App; ein Update der App baust oder lädst du selbst", "MakeMKV is part of the app; you build or download an app update yourself"), "info", true)
            }
            "playback" -> {
                item("alang", t("Tonsprache", "Audio language"), langLabel(makemkv.audioLang), "volume")
                item("slang", t("Untertitel", "Subtitles"), langLabel(makemkv.subLang, t("Aus", "Off"), t("Automatisch", "Automatic")), "language")
                item("note", t("Pro Disc änderbar", "Changeable per disc"), t("auf der Seite der Disc", "on the disc's page"), "info", true)
            }
            "disc" -> {
                val plan = try { makemkv.cachePlan() } catch (_: Exception) { null }
                item("key-beta", t("Schlüssel: Beta aus dem Forum", "Key: the forum's beta key"),
                    if (makemkv.keyMode == "beta") t("in Gebrauch, täglich erneuert", "in use, renewed daily") else t("täglich erneuert", "renewed daily"), "key")
                item("key-own", t("Schlüssel: eigener", "Key: your own"),
                    if (makemkv.keyMode == "manual") makemkv.ownKey().take(8) + "…" else "", "key")
                item("cache-mode", t("Zwischenspeicher", "Cache"),
                    when (makemkv.cacheMode) { "ram" -> t("Arbeitsspeicher", "memory"); "storage" -> t("interner Speicher", "storage"); else -> t("automatisch", "automatic") } +
                        (plan?.let { " · ${it.bytes shr 20} MB" } ?: ""), "tune")
                item("cache-size", t("Größe des Zwischenspeichers", "Cache size"), if (makemkv.cacheMb > 0) "${makemkv.cacheMb} MB" else t("automatisch", "automatic"), "tune")
                item("tmdb", t("Filmcover (TMDB)", "Film covers (TMDB)"), if (makemkv.tmdbKey.isEmpty()) t("Schlüssel eingeben", "enter a key") else t("Schlüssel eingetragen", "key entered"), "film")
                item("quick", t("Schnellstart", "Quick start"), if (makemkv.quickStart) t("an: der Film wird schon auf der Seite der Disc vorbereitet", "on: the film is prepared on the disc's page") else t("aus", "off"), "film")
                item("keydb", t("Schlüsseldatenbank laden", "Load the key database"), t("für Discs ohne MakeMKV", "for discs without MakeMKV"), "download")
                item("note", "MakeMKV 2.0", (if (makemkv.abi == "arm64-v8a") "64 Bit" else "32 Bit") + " · makemkv-log.txt", "info", true)
            }
        }
        return a.toString()
    }

    private fun settingAct(item: String) {
        fun next(list: List<String>, now: String) = list[(list.indexOf(now).coerceAtLeast(0) + 1) % list.size]
        when (item) {
            "alang" -> makemkv.audioLang = next(langChoices, makemkv.audioLang)
            "slang" -> makemkv.subLang = next(listOf("", "off") + langChoices.drop(1), makemkv.subLang)
            "key-beta" -> { makemkv.useBetaKey(); say(t("MakeMKV: der Beta-Schlüssel wird jetzt und dann täglich aus dem Forum geholt.", "MakeMKV: the beta key is fetched from the forum now and every day.")) }
            "key-own" -> askOwnKey()
            "cache-mode" -> makemkv.cacheMode = next(listOf("auto", "ram", "storage"), makemkv.cacheMode)
            "cache-size" -> makemkv.cacheMb = next(listOf("0", "64", "128", "256", "512", "1024"), makemkv.cacheMb.toString()).toInt()
            "tmdb" -> askText(t("TMDB-Schlüssel (kostenlos auf themoviedb.org)", "TMDB key (free at themoviedb.org)"), "API key", makemkv.tmdbKey) { k -> makemkv.tmdbKey = k; say(t("Gespeichert. Cover erscheinen beim nächsten Erkennen einer Disc.", "Saved. Covers appear the next time a disc is identified.")) }
            "keydb" -> discs.loadKeyDatabase { say(it) }
            "quick" -> makemkv.quickStart = !makemkv.quickStart
            "upd-check", "upd-app", "upd-mk" -> {
                say(t("Suche nach Updates …", "Looking for updates …"), 3000)
                val ok = updates.check()
                say(when {
                    !ok -> t("Keine Verbindung zu GitHub oder zum MakeMKV-Forum (${updates.error}). Bitte das Netzwerk prüfen und noch einmal versuchen.", "Could not reach GitHub or the MakeMKV forum (${updates.error}). Check the network and try again.")
                    updates.appNewer || updates.makemkvNewer -> listOfNotNull(
                        if (updates.appNewer) t("Lumen TV ${updates.appLatest} ist erschienen.", "Lumen TV ${updates.appLatest} is out.") else null,
                        if (updates.makemkvNewer) t("MakeMKV ${updates.makemkvLatest} ist erschienen.", "MakeMKV ${updates.makemkvLatest} is out.") else null).joinToString(" ")
                    else -> t("Alles auf dem neuesten Stand.", "Everything is up to date.")
                }, 8000)
            }
        }
    }

    // ---- MakeMKV (test build) ----
    private val mkLogFile by lazy { java.io.File(getExternalFilesDir(null) ?: filesDir, "makemkv-log.txt") }

    /** Debug log: logcat (LumenMKV) and Android/data/io.github.lumen.tv/files/makemkv-log.txt */
    fun mkLog(msg: String) {
        android.util.Log.i("LumenMKV", msg)
        try {
            synchronized(mkLogFile) {
                if (mkLogFile.length() > 4_000_000) mkLogFile.renameTo(java.io.File(mkLogFile.path + ".old"))
                mkLogFile.appendText(java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT).format(java.util.Date()) + " " + msg + "\n")
            }
        } catch (_: Exception) { }
    }

    /** A message on the page; long waits (60 s and more) get a running bar: something is happening. */
    private fun say(msg: String, ms: Int = 7000) =
        js("window.lumenToast&&window.lumenToast(${JSONObject.quote(msg)},$ms${if (ms >= 60000 && msg.endsWith("…")) ",-1" else ""})")
    private fun t(de: String, en: String) = if (java.util.Locale.getDefault().language == "de") de else en

    private fun langName(code: String): String =
        java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.getDefault()).replaceFirstChar { it.titlecase(java.util.Locale.getDefault()) }.ifEmpty { code }

    /** a drive's disc label (the disc is not open while MakeMKV has the drive: remembered from before) */
    private val mkLabels = HashMap<String, String>()
    private fun mkLabel(key: String): String = discs.label(key).ifEmpty { mkLabels[key].orEmpty() }.also { if (it.isNotEmpty()) mkLabels[key] = it }
    private val mkCoverTried = HashSet<String>()
    private val mkJacketTried = HashSet<String>()

    /**
     * The picture the disc itself carries (a Blu-ray's BDMV/META/DL/Jacket_*.jpg, the larger one): copied once to the app's folder,
     * served to the page as /cover/<file>. "" where the disc has none (or is not open now: asked again later).
     */
    private fun ownCover(key: String, label: String): String {
        val name = "c" + label.hashCode().toUInt().toString(16) + ".jpg"
        val file = java.io.File(java.io.File(filesDir, "covers"), name)
        if (file.isFile) return "https://appassets.androidplatform.net/cover/$name"
        val udf = discs.udf(key) ?: return ""
        if (!key.startsWith("usb") || !mkJacketTried.add(key)) return ""
        kotlin.concurrent.thread {
            try {
                val best = udf.list("BDMV/META/DL").filter { !it.dir && (it.name.endsWith(".jpg", true) || it.name.endsWith(".jpeg", true) || it.name.endsWith(".png", true)) && it.size < 4_000_000 }.maxByOrNull { it.size }
                if (best != null) {
                    val r = udf.open("BDMV/META/DL/${best.name}")
                    file.parentFile?.mkdirs()
                    java.io.FileOutputStream(file).use { o -> val b = ByteArray(1 shl 16); var pos = 0L; while (true) { val n = r.read(pos, b, 0, b.size); if (n <= 0) break; o.write(b, 0, n); pos += n } }
                    mkLog("cover: the disc's own picture ${best.name} (${best.size / 1024} KB)")
                    js("window.__lumenDrivesChanged&&window.__lumenDrivesChanged()")
                }
            } catch (e: Exception) { mkLog("cover: no picture on the disc ($e)") }
        }
        return ""
    }

    /** "Doctor Who (2005) · Staffel 1 · Disc 3": the name of an identified disc (the series from TVmaze where known). */
    private fun mkDiscName(key: String): String {
        val titles = mkTitles[key] ?: return ""
        val d = makemkv.identify(titles)
        val de = java.util.Locale.getDefault().language == "de"
        val guide = mkGuide[key]
        val base = d.label(de)
        return if (guide != null && d.name.isNotEmpty()) base.replaceFirst(d.name, guide.show + (guide.premiered.take(4).takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "")) else base
    }
    @Volatile private var mkMenuWanted = false
    private val mkLibreTriggered = HashSet<String>()

    /** Switches LibreDrive on in the drive of [key] through MakeMKV (a 4K disc the app then decrypts itself with the key database). */
    private fun mkLibre(key: String) {
        if (mkKey != null) return
        mkKey = key
        mkLog("---- LibreDrive for $key")
        js("window.__lumenDrivesChanged&&window.__lumenDrivesChanged()")
        try {
            val device = mkLend(key) ?: run { say(t("Das Laufwerk ist nicht mehr da.", "The drive is gone.")); return }
            if (!makemkv.openBridge(device) || !(makemkv.identify() || (makemkv.ejectAndReload() && makemkv.identify()))) {
                say(t("Das Laufwerk antwortet nicht. Bitte aus- und wieder einschalten.", "The drive does not answer. Switch it off and on again.")); return
            }
            if (makemkv.enableLibreDrive()) discs.libreReady.add(key)
            else say(t("MakeMKV konnte LibreDrive nicht einschalten – dieses Laufwerk kann 4K-Discs so nicht lesen.", "MakeMKV could not switch LibreDrive on – this drive cannot read 4K discs this way."), 10000)
        } catch (e: Throwable) {
            mkLog("LibreDrive failed: ${android.util.Log.getStackTraceString(e)}")
        } finally {
            stopMakeMkv()
            js("window.__lumenDrivesChanged&&window.__lumenDrivesChanged()")
        }
    }
    private val mkReading = HashSet<String>()      // discs MakeMKV is reading (or has been asked to, once)

    /** What the disc page of the drive [key] shows besides the disc's own files; [rel] "" is the page, "#extras/" the extras. */
    /** The disc's own menu (background picture and sound, its buttons): the disc plays as a whole. It needs no titles read first. */
    private fun mkMenuRow(key: String): JSONObject = JSONObject().put("name", t("Disc mit ihrem Menü abspielen", "Play the disc with its menu")).put("detail", t("Hauptmenü der Disc", "the disc's main menu")).put("icon", "disc")
        .put("path", "disc:$key|#mkdisc").put("dir", false).put("uri", "makemenu://$key/").put("video", true).put("group", "menu")

    private fun mkEntries(key: String, rel: String): List<JSONObject> {
        if (!key.startsWith("usb") || !makemkv.available) return emptyList()
        val out = ArrayList<JSONObject>()
        val de = java.util.Locale.getDefault().language == "de"
        fun t(deText: String, enText: String) = if (de) deText else enText
        fun action(name: String, detail: String, what: String, icon: String) = out.add(JSONObject().put("name", name).put("detail", detail)
            .put("path", "disc:$key|#$what").put("dir", false).put("action", what).put("icon", icon))
        fun note(name: String, detail: String, icon: String) = out.add(JSONObject().put("name", name).put("detail", detail)
            .put("icon", icon).put("path", "").put("dir", false).put("off", true))
        if (rel == "#more/") return listOf(JSONObject().put("name", t("Menü-Dateien der Disc sichern", "Save the disc's menu files")).put("detail", t("Diagnose: Java-Menü, Metadaten", "diagnostics: Java menu, metadata"))
            .put("icon", "download").put("path", "disc:$key|#mkdump").put("dir", false).put("action", "mkdump"),
            JSONObject().put("name", "Menü ansehen (Test)").put("detail", "Experiment: das Bildmenü der Disc zeichnen lassen").put("icon", "screen").put("path", "disc:$key|#mkmenu").put("dir", false).put("action", "mkmenu"), JSONObject().put("name", t("Disc neu einlesen", "Read the disc again")).put("detail", t("Titel, Spuren, Folgen", "titles, tracks, episodes"))
            .put("icon", "refresh").put("path", "disc:$key|#mkreset").put("dir", false).put("action", "mkreset"))
        // a drive that hangs: nothing of the disc is known, MakeMKV would hang with it
        if (discs.noAnswer(key) && mkKey == null) {
            if (rel.isEmpty()) note(t("Das Laufwerk antwortet nicht", "The drive does not answer"),
                t("bitte das Laufwerk aus- und wieder einschalten (Netzteil), dann geht es weiter", "switch the drive off and on (its power supply), then it goes on"), "usb")
            return out
        }
        if (mkTitles[key] == null) makemkv.loadTitles(mkLabel(key))?.let { mkTitles[key] = it; kotlin.concurrent.thread { mkLookup(key, it) } }
        val titles = mkTitles[key]
        if (titles == null) {
            if (rel.isNotEmpty()) return emptyList()
            // a 4K disc the key database knows: MakeMKV only switches LibreDrive on, the app plays it itself
            if (discs.needsLibreDrive(key) && key in discs.uhdTry) {
                if (mkKey == null && synchronized(mkLibreTriggered) { mkLibreTriggered.add(key) }) kotlin.concurrent.thread { mkLibre(key) }
                if (mkKey == key) note(t("4K-Disc wird vorbereitet …", "Preparing the 4K disc …"), t("MakeMKV schaltet LibreDrive ein, etwa zwei Minuten", "MakeMKV switches LibreDrive on, about two minutes"), "disc")
                else action(t("4K-Disc vorbereiten", "Prepare the 4K disc"), t("LibreDrive über MakeMKV einschalten", "switch LibreDrive on through MakeMKV"), "mklibre", "disc")
                return out
            }
            out.add(mkMenuRow(key))
            // a protected disc the app cannot open itself: MakeMKV is asked at once (once per disc)
            if (discs.needsMakeMkv(key) && mkKey == null && synchronized(mkReading) { mkReading.add(key) }) {
                kotlin.concurrent.thread { mkRead(key) { msg -> say(msg) } }
            }
            if (mkKey == key && mkReading.contains(key))
                note(t("Disc wird erkannt …", "Identifying the disc …"), t("etwa eine Minute", "about a minute"), "disc")
            else if (makemkv.noKey(mkLabel(key))) {
                note(t("MakeMKV kennt den Schlüssel dieser Disc nicht", "MakeMKV does not know this disc's key"),
                    if (updates.makemkvNewer) t("MakeMKV ${updates.makemkvLatest} ist erschienen – damit sollte es gehen", "MakeMKV ${updates.makemkvLatest} is out – it should work with that")
                        else t("meist eine neue 4K-Disc (AACS 2): MakeMKV hat sie zur Auswertung gesendet, eine neuere Version löst das", "mostly a new 4K disc (AACS 2): MakeMKV has sent it for analysis, a newer version solves it"), "key")
                action(t("Noch einmal versuchen", "Try again"), t("liest die Titel, etwa eine Minute", "reads the titles, about a minute"), "mkread", "refresh")
                if (discs.isUhd(key) && discs.needsLibreDrive(key))
                    action(t("Mit der Schlüsseldatenbank versuchen (experimentell)", "Try with the key database (experimental)"),
                        t("LibreDrive, dann liest die App selbst – kann das Laufwerk aufhängen", "LibreDrive, then the app reads itself – may hang the drive"), "mkuhdtry", "key")
            } else action(t("Disc mit MakeMKV erkennen", "Identify the disc with MakeMKV"), t("liest die Titel, etwa eine Minute", "reads the titles, about a minute"), "mkread", "disc")
            return out
        }
        if (mkMenuNamesOf(key) == null && !makemkv.menuTried(mkLabel(key)) && mkMenuState.isEmpty() && mkKey == null && discs.udf(key) != null && mkMenuTriggered.add(key))
            kotlin.concurrent.thread { mkMenuRead(key) }
        val d = makemkv.identify(titles)
        val guide = mkGuide[key]
        var group = ""
        fun play(tt: MakeMkv.Title, label: String, icon: String) = out.add(JSONObject().put("name", label)
            .put("detail", "${tt.minutes} min" + (if (tt.chapters > 1) " · ${tt.chapters} ${t("Kapitel", "chapters")}" else "")).put("icon", icon).put("path", "disc:$key|#mk${tt.id}")
            .put("dir", false).put("uri", "makemkv://$key/${tt.id}").put("video", true).put("group", group))
        fun menuRow() = out.add(mkMenuRow(key))
        if (rel == "#audio/") return mkTrackEntries(key, "audio")
        if (rel == "#text/") return mkTrackEntries(key, "text")
        if (rel == "#extras/") {
            d.extras.forEachIndexed { i, tt -> play(tt, "Extra ${i + 1}", "extra") }
            return out
        }
        if (rel.isNotEmpty()) return emptyList()
        // the title most likely wanted is prepared while the page is looked at (see warmMakeMkv)
        if (makemkv.quickStart) d.main.firstOrNull()?.let { warmMakeMkv(Uri.parse("makemkv://$key/${it.id}")) }
        out.add(JSONObject().put("name", mkDiscName(key).ifEmpty { t("Unbekannte Disc", "Unknown disc") })
            .put("detail", if (d.kind == "episodes") t("${d.main.size} Episoden", "${d.main.size} episodes") else "Film · ${d.main.firstOrNull()?.minutes ?: 0} min")
            .put("icon", if (d.kind == "episodes") "episodes" else "film").put("path", "").put("dir", false).put("off", true))
        // the episodes follow one another (the page goes on to the next when one ends); the rest stands alone
        group = "episodes"
        // the disc's own menu (background picture and sound, its buttons): the disc plays as a whole
        if (d.kind == "episodes") d.main.forEachIndexed { i, tt ->
            val e = guide?.episodes?.getOrNull(i)
            play(tt, if (e != null) (if (e.number == 0) "Special · ${e.name}" else t("Folge ${e.number} · ${e.name}", "Episode ${e.number} · ${e.name}")) else "Episode ${i + 1}", "film")
        }
        else d.main.firstOrNull()?.let { play(it, t("Film abspielen", "Play the film"), "film") }
        group = "all"
        if (d.kind == "episodes") d.all?.let { play(it, t("Alle Episoden hintereinander", "All episodes in a row"), "episodes") }
        // the disc's own menu after what is played most (the film, the episodes)
        menuRow()
        // the audio and subtitle tracks, chosen before playing: a page each (the same language may come twice: other format, other dub)
        if (mkStreams(key, "audio").isNotEmpty())
            out.add(JSONObject().put("name", t("Ton", "Audio")).put("detail", mkPickLabel(key, "audio")).put("icon", "volume").put("path", "disc:$key|#audio/").put("dir", true))
        if (mkStreams(key, "text").isNotEmpty())
            out.add(JSONObject().put("name", t("Untertitel", "Subtitles")).put("detail", mkPickLabel(key, "text")).put("icon", "language").put("path", "disc:$key|#text/").put("dir", true))
        // the episode numbers are reckoned from the disc number: one row steps through the places they can have
        if (guide != null && guide.slots > 1)
            action(t("Folgen ${guide.episodes.first().number}–${guide.episodes.last().number}: falsch?", "Episodes ${guide.episodes.first().number}–${guide.episodes.last().number}: wrong?"),
                t("weiter zur nächsten Zählung", "try the next numbering"), "mkshift", "refresh")
        if (mkMenuState == key)
            out.add(JSONObject().put("name", t("Spurnamen werden aus dem Disc-Menü gelesen …", "Reading the track names from the disc's menu …")).put("detail", t("einige Minuten", "a few minutes"))
                .put("icon", "info").put("path", "").put("dir", false).put("off", true))
        if (d.extras.isNotEmpty())
            out.add(JSONObject().put("name", t("Extras", "Extras")).put("detail", "${d.extras.size}").put("icon", "extra")
                .put("path", "disc:$key|#extras/").put("dir", true))
        return out
    }

    /** A line of text asked of the user (a key); [ok] gets it. */
    private fun askText(title: String, hint: String, initial: String, ok: (String) -> Unit) = main.post {
        val edit = android.widget.EditText(this).apply { setSingleLine(); setText(initial); this.hint = hint }
        android.app.AlertDialog.Builder(this).setTitle(title).setView(edit)
            .setPositiveButton("OK") { _, _ -> kotlin.concurrent.thread { ok(edit.text.toString().trim()) } }
            .setNegativeButton(t("Abbrechen", "Cancel"), null).show()
    }

    private fun askOwnKey() = main.post {
        val edit = android.widget.EditText(this).apply { setSingleLine(); setText(makemkv.ownKey()); hint = "T-…  or  M-…" }
        android.app.AlertDialog.Builder(this)
            .setTitle("Your MakeMKV registration key")
            .setView(edit)
            .setPositiveButton("OK") { _, _ ->
                val k = edit.text.toString().trim()
                if (k.length < 20) say(t("Das ist kein MakeMKV-Schlüssel.", "That is not a MakeMKV key."))
                else kotlin.concurrent.thread { makemkv.setOwnKey(k); say(t("MakeMKV: dein Schlüssel ist eingetragen.", "MakeMKV: your key is entered.")); js("window.__lumenDrivesChanged&&window.__lumenDrivesChanged()") }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Copies what the disc's menu is made of (it is not encrypted: only the films are) to the app's folder, to see what a menu says about
     * its tracks without running it: the Java programs (BDMV/JAR), their descriptions (BDMV/BDJO), the metadata (BDMV/META), the menu
     * commands. Returns a message.
     */
    private fun dumpMenuFiles(key: String): String {
        val udf = discs.udf(key) ?: return t("Die Disc ist gerade nicht frei.", "The disc is not free right now.")
        val out = java.io.File(getExternalFilesDir(null) ?: filesDir, "disc-dump").apply { deleteRecursively(); mkdirs() }
        var files = 0; var bytes = 0L
        fun copy(path: String) {
            try {
                for (e in udf.list(path)) {
                    val p = "$path/${e.name}"
                    if (e.dir) { copy(p); continue }
                    if (e.size > 30_000_000) continue
                    val target = java.io.File(out, p.removePrefix("BDMV/")); target.parentFile?.mkdirs()
                    val r = udf.open(p)
                    java.io.FileOutputStream(target).use { o ->
                        val b = ByteArray(1 shl 16); var pos = 0L
                        while (true) { val n = r.read(pos, b, 0, b.size); if (n <= 0) break; o.write(b, 0, n); pos += n }
                    }
                    files++; bytes += e.size
                }
            } catch (ex: Exception) { mkLog("dump $path: $ex") }
        }
        for (d in listOf("BDMV/JAR", "BDMV/BDJO", "BDMV/META", "BDMV/AUXDATA", "BDMV/CLIPINF", "CERTIFICATE")) copy(d)
        for (f in listOf("BDMV/index.bdmv", "BDMV/MovieObject.bdmv")) try {
            val r = udf.open(f); val b = ByteArray(r.size.toInt()); var n = 0
            while (n < b.size) { val k = r.read(n.toLong(), b, n, b.size - n); if (k <= 0) break; n += k }
            java.io.File(out, f.removePrefix("BDMV/")).writeBytes(b); files++; bytes += b.size
        } catch (_: Exception) { }
        mkLog("dump: $files files, ${bytes / 1024} KB in ${out.path}")
        return t("$files Dateien gesichert (${bytes / 1024} KB): Android/data/io.github.lumen.tv/files/disc-dump", "$files files saved (${bytes / 1024} KB): Android/data/io.github.lumen.tv/files/disc-dump")
    }

    /** Experiment: lets bdmenu play the disc's menu for a while, logs its events and takes pictures of the menu. */
    private fun mkMenuProbe(key: String, say: (String) -> Unit) {
        if (mkKey != null) { say("MakeMKV is busy."); return }
        mkLabel(key)
        mkLog("---- menu probe of $key")
        val device = mkLend(key) ?: run { say("The drive is gone."); discs.giveBack(key); return }
        mkKey = key
        try {
            if (!makemkv.openBridge(device) || !(makemkv.identify() || (makemkv.ejectAndReload() && makemkv.identify()))) { say("The drive does not answer."); return }
            say("Menü wird gestartet …")
            val p = makemkv.startMenu()
            val out = p.outputStream
            val t0 = System.currentTimeMillis()
            val reader = kotlin.concurrent.thread(isDaemon = true) {
                try { p.inputStream.bufferedReader().forEachLine { l -> mkLog("menu: " + l.take(300)) } } catch (_: java.io.IOException) { }
            }
            // (for the experiment: commands arrive in tmp/menu/cmd.txt, one per line, for up to 15 minutes; "quit" ends it)
            val cmdFile = java.io.File(makemkv.menuDir(), "cmd.txt")
            var done = 0
            while (p.isAlive && System.currentTimeMillis() - t0 < 900_000) {
                Thread.sleep(300)
                try {
                    val lines = if (cmdFile.isFile) cmdFile.readLines() else emptyList()
                    while (done < lines.size) { val l = lines[done++].trim(); if (l.isNotEmpty()) { out.write((l + "\n").toByteArray()); out.flush(); mkLog("menu <- $l") } }
                } catch (_: Exception) { break }
            }
            mkLog("menu probe ends (${if (p.isAlive) "stopped" else "exit " + p.exitValue()})")
        } catch (e: Throwable) {
            mkLog("menu probe failed: ${android.util.Log.getStackTraceString(e)}")
        } finally {
            stopMakeMkv()
        }
    }

    // ---- the names of the tracks, read from the disc's own menu (see MenuReader) ----
    private val mkMenuNames = HashMap<String, MenuReader.Names>()
    @Volatile private var mkMenuState = ""            // the key of the disc whose menu is being read

    private fun mkMenuNamesOf(key: String): MenuReader.Names? =
        mkMenuNames[key] ?: makemkv.menuNames(mkLabel(key))?.let { MenuReader.Names.fromJson(it) }?.also { mkMenuNames[key] = it }

    /** Reads the menu of the disc [key] (once per disc, remembered): runs on a worker thread, gives up when a film is to be played. */
    private fun mkMenuRead(key: String) {
        if (mkMenuState.isNotEmpty() || mkKey != null || mkMenuNamesOf(key) != null || makemkv.menuTried(mkLabel(key))) return
        // a disc with a Java menu (BD-J): bdmenu cannot run it, there is nothing to read
        val bdj = try { discs.udf(key)?.list("BDMV/JAR")?.isNotEmpty() == true } catch (_: Exception) { false }
        if (bdj) { mkLog("menu: a Java menu (BD-J), not read"); makemkv.setMenuTried(mkLabel(key)); return }
        mkMenuState = key
        js("window.__lumenDrivesChanged&&window.__lumenDrivesChanged()")
        mkLog("---- read the menu of $key")
        val device = mkLend(key)
        mkKey = key
        try {
            if (device == null || !makemkv.openBridge(device) || !(makemkv.identify() || (makemkv.ejectAndReload() && makemkv.identify()))) { mkLog("menu: the drive does not answer"); return }
            val stn = mkStn[key]?.let { m -> makemkv.identify(mkTitles[key] ?: emptyList()).main.firstOrNull()?.source?.let { m[it] } }
            val names = MenuReader(makemkv) { mkLog(it) }.read(stn?.audio?.map { it.lang } ?: emptyList(), stn?.subtitles?.map { it.lang } ?: emptyList()) { mkKey != key || mkWantsDrive }
            makemkv.setMenuTried(mkLabel(key))
            if (names != null) { makemkv.saveMenuNames(mkLabel(key), names.toJson()); mkMenuNames[key] = names }
        } catch (e: Throwable) {
            mkLog("menu: failed: ${android.util.Log.getStackTraceString(e)}")
        } finally {
            // (MakeMKV stopped first, then the state cleared: a film waiting for the drive starts only after this)
            stopMakeMkv()
            mkMenuState = ""
            js("window.__lumenDrivesChanged&&window.__lumenDrivesChanged()")
        }
    }
    @Volatile private var mkWantsDrive = false

    /** The playlists' stream tables, read from the disc itself before MakeMKV has the drive (key -> playlist name -> table). */
    private val mkStn = HashMap<String, Map<String, StreamTable>>()
    /** The playlists' chapters (key -> playlist name -> seconds), read with their stream tables. */
    private val mkMarks = HashMap<String, Map<String, List<Double>>>()

    private fun mkReadPlaylists(key: String) {
        if (mkStn.containsKey(key)) return
        val udf = discs.udf(key) ?: return
        val out = HashMap<String, StreamTable>()
        val marks = HashMap<String, List<Double>>()
        try {
            for (e in udf.list("BDMV/PLAYLIST").filter { !it.dir && it.name.endsWith(".mpls", true) }) {
                val r = udf.open("BDMV/PLAYLIST/${e.name}")
                val b = ByteArray(r.size.toInt())
                var n = 0
                while (n < b.size) { val k = r.read(n.toLong(), b, n, b.size - n); if (k <= 0) break; n += k }
                val pl = Playlist.parse(e.name, b)
                if (pl != null && pl.marks.size > 1) marks[e.name] = pl.marks
                val st = StreamTable.parse(b) ?: continue
                out[e.name] = st
                if (pl != null && pl.seconds >= 900) mkLog("playlist ${e.name} ${(pl.seconds / 60).toInt()} min: $st")
            }
        } catch (ex: Exception) { mkLog("playlists: $ex") }
        mkStn[key] = out
        mkMarks[key] = marks
    }

    /** Reads the disc's titles with MakeMKV (no playing): the page then lists the film or the episodes. */
    private fun mkRead(key: String, say: (String) -> Unit) {
        if (mkKey != null) { say(t("MakeMKV ist beschäftigt.", "MakeMKV is busy.")); return }
        mkKey = key
        mkLabel(key)
        mkLog("---- read titles of $key")
        mkReadPlaylists(key)
        if (mkMenuWanted) { mkLog("the disc's menu is wanted: the titles are not read"); mkKey = null; return }
        val device = mkLend(key) ?: run { say(t("Das Laufwerk ist nicht mehr da.", "The drive is gone.")); discs.giveBack(key); mkKey = null; return }
        try {
            if (!makemkv.openBridge(device) || !(makemkv.identify() || (makemkv.ejectAndReload() && makemkv.identify()))) {
                say(t("Das Laufwerk antwortet nicht. Bitte aus- und wieder einschalten.", "The drive does not answer. Switch it off and on again.")); return
            }
            say(if (java.util.Locale.getDefault().language == "de") "MakeMKV liest die Disc …" else "MakeMKV reads the disc …")
            var titles = makemkv.titles()
            // the drive answered reads with timeouts: the disc out and in once and read again; then it is the user's turn
            if (makemkv.driveStuck && mkKey == key) {
                mkLog("drive stuck while reading the titles: eject and reload")
                say(t("Das Laufwerk kommt mit der Disc nicht weiter: sie wird aus- und wieder eingezogen …", "The drive cannot go on with the disc: it is ejected and loaded again …"), 90000)
                if (makemkv.ejectAndReload() && makemkv.identify()) titles = makemkv.titles()
                if (makemkv.driveStuck) {
                    mkLog("drive still stuck: the user is asked")
                    say(t("Das Laufwerk kann die Disc nicht lesen. Bitte die Disc herausnehmen, prüfen (Kratzer, Staub) und wieder einlegen; hilft das nicht, das Laufwerk aus- und einschalten.",
                        "The drive cannot read the disc. Take it out, check it (scratches, dust) and put it in again; if that does not help, switch the drive off and on."), 30000)
                    return
                }
            }
            mkLog("titles: " + titles.joinToString(" | "))
            if (titles.isNotEmpty()) { mkTitles[key] = titles; makemkv.saveTitles(mkLabel(key), titles) }
            else if (makemkv.keyUnknown) {
                makemkv.setNoKey(mkLabel(key), true)
                if (updates.due()) updates.check()
                say(if (updates.makemkvNewer) t("MakeMKV kennt den Schlüssel dieser Disc nicht. MakeMKV ${updates.makemkvLatest} ist erschienen – damit sollte es gehen.", "MakeMKV does not know this disc's key. MakeMKV ${updates.makemkvLatest} is out – it should work with that.")
                    else t("MakeMKV kennt den Schlüssel dieser Disc nicht. Es gibt noch keine neuere MakeMKV-Version (${updates.makemkvInstalled} ist die aktuelle); die Disc wurde zur Auswertung gesendet.", "MakeMKV does not know this disc's key. There is no newer MakeMKV yet (${updates.makemkvInstalled} is the latest); the disc has been sent for analysis."), 12000)
            } else say(t("MakeMKV hat keine Titel gefunden (siehe makemkv-log.txt).", "MakeMKV found no titles (see makemkv-log.txt)."))
            if (titles.isNotEmpty()) makemkv.setNoKey(mkLabel(key), false)
            mkLookup(key, titles)
            if (titles.isNotEmpty()) mkAfterRead = key
        } catch (e: Throwable) {
            mkLog("failed: ${android.util.Log.getStackTraceString(e)}")
        } finally {
            stopMakeMkv()
            js("window.__lumenDrivesChanged&&window.__lumenDrivesChanged()")
        }
        // then the disc's menu: the names of the tracks
        if (mkAfterRead == key) { mkAfterRead = ""; mkMenuRead(key) }
    }
    @Volatile private var mkAfterRead = ""
    private val mkMenuTriggered = HashSet<String>()

    /** A series disc: the names of its episodes from TVmaze (only the series' name is sent). */
    private fun mkLookup(key: String, titles: List<MakeMkv.Title>) {
        val d = makemkv.identify(titles)
        if (d.kind != "episodes") {
            // a film: its poster from TMDB (where the user gave a key)
            val label = mkLabel(key)
            if (makemkv.cover(label).isEmpty() && makemkv.tmdbKey.isNotEmpty()) {
                val url = Cover.movie(d.name, makemkv.tmdbKey, java.util.Locale.getDefault().language) { mkLog(it) }
                if (url.isNotEmpty()) { makemkv.setCover(label, url); js("window.__lumenDrivesChanged&&window.__lumenDrivesChanged()") }
            }
            return
        }
        // (kept per disc and numbering: TVmaze is asked once, not at every start)
        val prefs = getSharedPreferences("makemkv", MODE_PRIVATE)
        val cacheKey = "guide:${mkLabel(key)}:${episodeShift(d)}:${d.main.size}"
        val r = EpisodeGuide.fromJson(prefs.getString(cacheKey, null))?.takeIf { it.episodes.size == d.main.size }
            ?: EpisodeGuide.lookup(d.name, d.season, d.discNo, d.main.map { it.minutes }, episodeShift(d)) { mkLog(it) }?.also { prefs.edit().putString(cacheKey, EpisodeGuide.toJson(it)).apply() }
            ?: return
        mkLog("episodes: ${r.show} (${r.premiered}): " + r.episodes.joinToString(" | ") { "${it.season}x${it.number} ${it.name}" })
        mkGuide[key] = r
        if (r.image.isNotEmpty() && makemkv.cover(mkLabel(key)) != r.image) makemkv.setCover(mkLabel(key), r.image)
        js("window.__lumenDrivesChanged&&window.__lumenDrivesChanged()")
    }

    /** The user's choice of where the episodes of this disc start in the season (kept per series, season and disc). */
    private fun shiftKey(d: MakeMkv.Disc) = "shift:${d.name}:${d.season}:${d.discNo}"
    private fun episodeShift(d: MakeMkv.Disc) = getSharedPreferences("makemkv", MODE_PRIVATE).getInt(shiftKey(d), 0)

    /** The next place for the episodes of the disc [key] (after the last, the reckoned one again), named again. */
    private fun mkShift(key: String) {
        val titles = mkTitles[key] ?: return
        val g = mkGuide[key] ?: return
        val d = makemkv.identify(titles)
        val next = (g.start + 1) % g.slots
        getSharedPreferences("makemkv", MODE_PRIVATE).edit().putInt(shiftKey(d), next - g.base).apply()
        mkGuide.remove(key)
        mkLookup(key, titles)
    }

    /** "Deutsch · DTS-HD MA · 5.1 · erzwungen": what tells two tracks of one language apart. */
    private fun streamLabel(x: MakeMkv.Stream): String {
        val ch = when (x.channels) { 0 -> ""; 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 7 -> "6.1"; 8 -> "7.1"; else -> "${x.channels} ch" }
        val codec = x.codec.replace("DTS-HD MA", "DTS-HD MA").ifEmpty { "" }
        return listOfNotNull(
            if (x.lang.isNotEmpty()) langName(x.lang) else x.langName.ifEmpty { null }, codec.ifEmpty { null }, ch.ifEmpty { null },
            if (x.forced) t("erzwungen", "forced") else null, if (x.commentary) t("Kommentar", "commentary") else null,
            x.bitrate.takeIf { it.isNotEmpty() && x.type == "audio" },
            // what the disc says in brackets about a subtitle track ("(forced only)", "(SDH)"), if it has not been said yet
            Regex("""\(([^)]*)\)""").find(x.desc)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() && !it.contains("forced", true) && !it.contains("comment", true) }
        ).joinToString(" · ")
    }

    /**
     * MakeMKV lists a DTS-HD MA track twice: the track and, right after it, the DTS core inside it as a track of its own.
     * It is one track for the listener: the core is left out of the lists (and cannot be chosen by mistake).
     */
    private fun coreCopies(streams: List<MakeMkv.Stream>): Set<Int> =
        streams.indices.filter { i ->
            i > 0 && streams[i].type == "audio" && streams[i].codec.trim() == "DTS" && streams[i - 1].codec.startsWith("DTS-HD") &&
                streams[i - 1].lang == streams[i].lang && streams[i - 1].channels == streams[i].channels
        }.toSet()

    /**
     * The subtitle tracks of a disc: MakeMKV adds, after a stream, a "forced only" copy of it where the stream holds forced
     * subtitles. "Subtitles 2 · German" and "Subtitles 2 · German · only forced parts".
     */
    private fun subtitleLabels(streams: List<MakeMkv.Stream>, names: MenuReader.Names? = null): List<String> {
        var k = 0
        return streams.map { x ->
            val lang = if (x.lang.isNotEmpty()) langName(x.lang) else x.langName
            if (x.forced) (names?.text?.get(maxOf(k, 1)) ?: "${t("Untertitel", "Subtitles")} ${maxOf(k, 1)} · $lang") + " · ${t("nur erzwungene Teile", "only forced parts")}"
            else { k++; names?.text?.get(k) ?: "${t("Untertitel", "Subtitles")} $k · $lang" }
        }
    }

    /** Two tracks that look alike get a number, so the list does not show the same line twice. */
    private fun numbered(labels: List<String>): List<String> {
        val total = labels.groupingBy { it }.eachCount()
        val seen = HashMap<String, Int>()
        return labels.map { l -> if (total[l]!! > 1) "$l (${seen.merge(l, 1, Int::plus)})" else l }
    }

    /** The audio or subtitle streams of what the disc page plays (of the first episode or the film). */
    private fun mkStreams(key: String, type: String): List<MakeMkv.Stream> {
        val d = makemkv.identify(mkTitles[key] ?: return emptyList())
        return d.main.firstOrNull()?.streams?.filter { it.type == type }.orEmpty()
    }

    /** The page of the audio or the subtitle tracks of a disc (a choice of the actual tracks, not of a language). */
    private fun mkTrackEntries(key: String, type: String): List<JSONObject> {
        val label = mkLabel(key)
        val now = makemkv.pick(label, type)
        val out = ArrayList<JSONObject>()
        fun row(name: String, detail: String, id: String, on: Boolean, icon: String) = out.add(JSONObject().put("name", name)
            .put("detail", if (on) t("✓ gewählt", "✓ chosen") else detail).put("icon", icon).put("path", "disc:$key|#mp$type:$id").put("dir", false).put("action", "mkpick"))
        val icon = if (type == "audio") "volume" else "language"
        row(t("Automatisch", "Automatic"), t("nach Sprache der Einstellungen", "by the language in the settings"), "", now == "", icon)
        if (type == "text") row(t("Aus", "Off"), "", "off", now == "off", icon)
        val streams = mkStreams(key, type)
        val hidden = if (type == "audio") coreCopies(streams) else emptySet()
        val shown = streams.indices.filter { it !in hidden }
        val names = mkMenuNamesOf(key)
        val labels = if (type == "text") subtitleLabels(streams, names) else numbered(shown.mapIndexed { n, i -> names?.audio?.get(n + 1) ?: streamLabel(streams[i]) })
        shown.forEachIndexed { n, i -> row(labels[n], "", i.toString(), now == i.toString(), icon) }
        return out
    }

    private fun mkPickLabel(key: String, type: String): String {
        val now = makemkv.pick(mkLabel(key), type)
        val streams = mkStreams(key, type)
        return when {
            now == "off" -> t("aus", "off")
            now.toIntOrNull() != null && now.toInt() < streams.size -> streamLabel(streams[now.toInt()])
            else -> (if (type == "audio") makemkv.audioLang else makemkv.subLang).let { l -> if (l == "off") t("aus", "off") else if (l.isEmpty()) t("automatisch", "automatic") else langName(l) }
        }
    }

    /** The next language (or automatic / off) for the audio or the subtitles of the disc [key]. */
    private fun mkCycle(key: String, type: String) {
        val titles = mkTitles[key] ?: return
        val d = makemkv.identify(titles)
        val langs = makemkv.languages(d.main, type)
        val choices = listOf("") + (if (type == "text") listOf("off") else emptyList()) + langs
        val now = if (type == "audio") makemkv.audioLang else makemkv.subLang
        val next = choices[(choices.indexOf(now).coerceAtLeast(0) + 1) % choices.size]
        if (type == "audio") makemkv.audioLang = next else makemkv.subLang = next
    }

    /** The disc's own menu through bdmenu (libbluray, libmmbd): the disc plays as a whole, menus and film. Runs on a worker thread. */
    private fun startMakeMenu(uri: Uri) {
        val key = uri.host ?: return
        if (mkMenuState.isNotEmpty()) {
            mkWantsDrive = true; makemkv.stop()
            for (i in 0 until 40) { if (mkMenuState.isEmpty()) break; Thread.sleep(500) }
            mkWantsDrive = false
        }
        if (mkKey != null) {          // (the titles are being read: the menu does not need them, MakeMKV is stopped and the drive freed)
            mkLog("the menu is wanted: reading the titles is stopped")
            mkMenuWanted = true
            makemkv.stop()
            for (i in 0 until 40) { if (mkKey == null) break; Thread.sleep(500) }
            mkMenuWanted = false
        }
        val closed = java.util.concurrent.CountDownLatch(1)
        main.post { closePlayer(); closed.countDown() }
        closed.await()
        mkLabel(key)
        mkLog("---- play the disc's menu $uri")
        if (makemkv.keyMode.isEmpty()) say(t("MakeMKV hat noch keinen Schlüssel (siehe Einstellungen).", "MakeMKV has no key yet (see the settings)."), 8000)
        val device = mkLend(key)
        if (device == null) { say(t("Das Laufwerk ist nicht mehr da.", "The drive is gone.")); discs.giveBack(key); return }
        mkKey = key
        try {
            say(t("Das Menü der Disc wird geöffnet (etwa eine Minute) …", "Opening the disc's menu (about a minute) …"), 90000)
            if (!makemkv.openBridge(device) || !(makemkv.identify() || (makemkv.ejectAndReload() && makemkv.identify()))) {
                say(t("Das Laufwerk antwortet nicht. Bitte aus- und wieder einschalten.", "The drive does not answer. Switch it off and on again.")); stopMakeMkv(); return
            }
            val rb = RemoteBd(makemkv) { mkLog(it) }
            rb.overlay = { plane, x, y, w, h, px -> bdDraw(plane, x, y, w, h, px) }
            rb.onSound = { samples, ch -> playPcm(samples, ch) }
            if (!rb.start(if (java.util.Locale.getDefault().language == "de") "ger" else "eng") { mkKey != key }) {
                say(t("Das Menü der Disc ließ sich nicht öffnen (siehe makemkv-log.txt).", "The disc's menu could not be opened (see makemkv-log.txt)."), 10000)
                rb.close(); stopMakeMkv(); return
            }
            if (rb.keyUnknown) {
                makemkv.setNoKey(mkLabel(key), true)
                if (updates.due()) updates.check()
                say(if (updates.makemkvNewer) t("MakeMKV kennt den Schlüssel dieser Disc nicht. MakeMKV ${updates.makemkvLatest} ist erschienen – damit sollte es gehen.", "MakeMKV does not know this disc's key. MakeMKV ${updates.makemkvLatest} is out – it should work with that.")
                    else t("MakeMKV kennt den Schlüssel dieser Disc nicht, das Menü lässt sich nicht öffnen. Es gibt noch keine neuere MakeMKV-Version (${updates.makemkvInstalled} ist die aktuelle).", "MakeMKV does not know this disc's key, so the menu cannot be opened. There is no newer MakeMKV yet (${updates.makemkvInstalled} is the latest)."), 14000)
                rb.close(); stopMakeMkv(); return
            }
            remoteBd = rb
            // the next stream is queued at once: the player opens it while it plays the end of the old one (no still picture between)
            rb.onRestart = { main.post { if (remoteBd === rb) player?.let { p -> p.addMediaSource(menuSource(rb)); mkLog("disc menu: the next stream is queued") } } }
            // (bdmenu stopping by itself – the disc could not be read on, a menu it cannot run – leaves no empty player behind)
            rb.onEnded = { main.post { if (remoteBd === rb) {
                event("ended"); closePlayer()
                say(t("Die Wiedergabe mit dem Menü der Disc ist beendet (siehe makemkv-log.txt).", "Playing the disc with its menu has ended (see makemkv-log.txt)."), 9000)
            } } }
            say("", 1)
            main.post { bdOverlay.visibility = View.VISIBLE; open(uri, false, null) }
        } catch (e: Throwable) {
            mkLog("menu failed: ${android.util.Log.getStackTraceString(e)}")
            say("$e"); stopMakeMkv()
        }
    }

    /** A sound effect of a disc's menu (48 kHz, 16 bit), played once. */
    private fun playPcm(data: ShortArray, ch: Int) {
        kotlin.concurrent.thread {
            try {
                val track = android.media.AudioTrack.Builder()
                    .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(48000).setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(if (ch == 1) android.media.AudioFormat.CHANNEL_OUT_MONO else android.media.AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(data.size * 2).setTransferMode(android.media.AudioTrack.MODE_STATIC).build()
                track.write(data, 0, data.size); track.play()
                Thread.sleep(data.size / ch * 1000L / 48000 + 200)
                track.release()
            } catch (_: Exception) { }
        }
    }

    /** Runs on a worker thread: lends the drive to MakeMKV, has it stream the title, plays the stream. */
    /**
     * Quick start: the disc page's main title is prepared in the background (MakeMKV's scan of half a minute, the cache's first
     * seconds) while the user looks at the page; started then, it plays at once. Another choice ends the preparation.
     */
    @Volatile private var mkWarm: Uri? = null
    @Volatile private var mkWarmPlay = false
    @Volatile private var mkQuiet = false
    private val mkWarmTried = HashSet<String>()
    private fun tell(msg: String, ms: Int = 7000) { if (!mkQuiet) say(msg, ms) }

    private fun warmMakeMkv(uri: Uri) {
        if (mkKey != null || mkMenuState.isNotEmpty() || player?.isPlaying == true || remoteBd != null) return
        if (!synchronized(mkWarmTried) { mkWarmTried.add(uri.toString()) }) return
        kotlin.concurrent.thread { startMakeMkv(uri, play = false) }
    }

    private fun startMakeMkv(uri: Uri, play: Boolean = true) {
        val key = uri.host ?: return
        val which = uri.path.orEmpty().trim('/')
        if (play) {
            val w = mkWarm
            if (w != null && w == uri && mkKey == key) {
                if (mkPipe != null && !mkQuiet) { mkWarm = null; mkLog("quick start: $uri"); main.post { open(uri, false, null) }; return }
                mkWarmPlay = true; mkQuiet = false; mkLog("quick start: waits for the preparation of $uri")
                say(t("Wird gestartet …", "Starting …"), 60000)
                return
            }
            if (w != null && mkKey != null) {     // another title was prepared: that one ends first
                mkLog("quick start: $w is not wanted, it is stopped")
                mkWarm = null; mkWarmPlay = false
                stopMakeMkv()
            }
        }
        mkQuiet = !play
        if (!play) mkWarm = uri
        if (mkMenuState.isNotEmpty()) {          // the menu is being read: the film comes first
            mkWantsDrive = true; makemkv.stop()
            for (i in 0 until 40) { if (mkMenuState.isEmpty()) break; Thread.sleep(500) }
            mkWantsDrive = false
        }
        val closed = java.util.concurrent.CountDownLatch(1)
        main.post { closePlayer(); closed.countDown() }
        closed.await()
        mkLabel(key)
        mkLog("---- play $uri")
        mkReadPlaylists(key)      // (the chapters and stream tables, read before MakeMKV has the drive)
        if (makemkv.keyMode.isEmpty()) tell(t("MakeMKV hat noch keinen Schlüssel: Beta-Schlüssel aus dem Forum wählen oder eigenen eingeben (in der Liste der Disc).", "MakeMKV has no key yet: choose the forum's beta key or enter your own (in the drive's list)."), 10000)
        val device = mkLend(key)
        if (device == null) { tell(t("Das Laufwerk ist nicht mehr da.", "The drive is gone.")); discs.giveBack(key); return }
        mkKey = key
        try {
            tell(t("MakeMKV öffnet das Laufwerk …", "MakeMKV: opening the drive …"))
            if (!makemkv.openBridge(device)) { tell(t("MakeMKV: das Laufwerk lässt sich nicht öffnen (siehe makemkv-log.txt).", "MakeMKV: the drive could not be opened (see makemkv-log.txt).")); stopMakeMkv(); return }
            if (mkReload || !makemkv.identify()) {
                mkReload = false
                mkLog("drive stuck before the start: eject and reload")
                tell(t("Das Laufwerk antwortet nicht: die Disc wird aus- und wieder eingezogen …", "The drive does not answer: the disc is ejected and loaded again …"), 60000)
                if (!makemkv.ejectAndReload() || !makemkv.identify()) {
                    mkLog("drive still stuck: the user is asked to switch it off and on")
                    tell(t("Das Laufwerk antwortet weiterhin nicht. Bitte aus- und wieder einschalten und den Film neu starten.", "The drive still does not answer. Switch the drive off and on again, then start the film again."), 30000)
                    stopMakeMkv(); return
                }
            }
            for (attempt in 1..2) {
                if (mkKey != key) return
                if (mkAttempt(key, which, uri)) return
                if (!makemkv.driveStuck || mkKey != key) { stopMakeMkv(); return }
                // the drive answers reads with timeouts: the disc out and in once; then it is the user's turn
                if (attempt == 1) {
                    mkLog("drive stuck: eject and reload")
                    tell(t("Das Laufwerk antwortet nicht mehr: die Disc wird aus- und wieder eingezogen …", "The drive stopped answering: the disc is ejected and loaded again …"), 60000)
                    mkPipe?.close(); mkPipe = null
                    if (!makemkv.ejectAndReload()) break
                }
            }
            mkLog("drive still stuck: the user is asked to switch it off and on")
            tell(t("Das Laufwerk antwortet weiterhin nicht. Bitte aus- und wieder einschalten und den Film neu starten.", "The drive still does not answer. Switch the drive off and on again, then start the film again."), 30000)
            stopMakeMkv()
        } catch (e: Throwable) {
            mkLog("failed: ${android.util.Log.getStackTraceString(e)}")
            tell("MakeMKV: $e")
            stopMakeMkv()
        }
    }

    /** One try: the titles, then the stream; true when the player got it (or nothing more is to be done). */
    private fun mkAttempt(key: String, which: String, uri: Uri): Boolean {
        var titles = mkTitles[key]
        if (titles == null) {
            tell(t("MakeMKV liest die Disc (dauert etwa eine Minute) …", "MakeMKV: reading the disc (this takes a minute) …"), 60000)
            titles = makemkv.titles()
            if (makemkv.driveStuck) return false
            mkLog("titles: " + titles.joinToString(" | "))
            if (titles.isNotEmpty()) { mkTitles[key] = titles; makemkv.saveTitles(mkLabel(key), titles) }
            js("window.__lumenDrivesChanged&&window.__lumenDrivesChanged()")
        }
        if (mkKey != key) return true
        val t = (if (which == "main") makemkv.identify(titles).let { d -> d.all ?: d.main.firstOrNull() } else titles.firstOrNull { it.id.toString() == which })
        if (t == null) { tell(t("MakeMKV hat auf dieser Disc keinen Titel gefunden (siehe makemkv-log.txt).", "MakeMKV found no title on this disc (see makemkv-log.txt)."), 10000); stopMakeMkv(); return true }
        tell(t("Wird gestartet (${t.durationMs / 60000} min, etwa eine Minute) …", "Starting (${t.durationMs / 60000} min, about a minute) …"), 60000)
        makemkv.clearCache()
        mkPlaying = t; mkPlayingLabel = mkLabel(key); picksFor = null
        chapters = mkMarks[key]?.get(t.source).orEmpty()
        mkLog("chapters: ${chapters.size}")
        val plan = makemkv.cachePlan()
        mkLog("cache: $plan (${plan.why})")
        // (no SIGSTOP: under proot it stopped makemkvcon's threads one by one and SIGCONT did not bring them all back;
        // the drive bridge holds MakeMKV's reading instead while the cache is full)
        // full speed while the cache fills (the start, after a jump), quiet once a third of it is ahead; full again below a sixth
        makemkv.driveSpeed(0)
        val pipe = PipeStream(makemkv.stream(t), plan.bytes, plan.file, { mkLog(it) }, level = { f ->
            if (f < 0.17) makemkv.driveSpeed(0) else if (f > 0.33) makemkv.driveSpeed(MakeMkv.QUIET_KBS)
        })
        mkPipe = pipe
        val t0 = System.currentTimeMillis()
        if (!pipe.awaitStart()) {
            if (makemkv.driveStuck) return false
            tell(t("MakeMKV hat vor dem Film aufgehört (siehe makemkv-log.txt).", "MakeMKV stopped before the film began (see makemkv-log.txt)."), 10000); stopMakeMkv(); return true
        }
        mkLog("first bytes after ${System.currentTimeMillis() - t0} ms")
        // a head start: the player begins when a few seconds of film are in the cache
        // (a short one: the drive goes on at full speed while the film begins, the cache fills behind it)
        val until = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < until && pipe.ahead() < minOf(plan.bytes / 2, 20L shl 20) && mkKey == key) Thread.sleep(200)
        mkLog("player starts with %.0f MB in the cache".format(java.util.Locale.ROOT, pipe.ahead() / 1e6))
        if (mkKey != key) return true
        if (mkQuiet && !mkWarmPlay) { mkLog("quick start: $uri is ready"); mkQuiet = false; return true }
        mkWarm = null; mkWarmPlay = false; mkQuiet = false
        main.post { open(uri, false, null) }
        return true
    }

    /**
     * Ends MakeMKV, then lets go of the drive – in this order: the bridge closed under a USB command still running leaves the
     * drive hanging until it is switched off. On the main thread this goes on in the background; the next use of the drive
     * waits for it ([mkLend]).
     */
    private fun stopMakeMkv() {
        val key = mkKey ?: return
        mkKey = null
        mkWarm = null; mkWarmPlay = false; mkQuiet = false
        mkLog("---- stop (${mkPipe?.position?.let { "%.1f MB played".format(java.util.Locale.ROOT, it / 1e6) } ?: "nothing played"})")
        val pipe = mkPipe
        mkPipe = null
        val done = java.util.concurrent.CountDownLatch(1)
        mkStopped = done
        val work = {
            try {
                pipe?.close()                // (first: a held read lets go, so MakeMKV can end its command)
                makemkv.stopAndWait()
                makemkv.driveSpeed(0)
                makemkv.closeBridge()
                discs.giveBack(key)
            } finally { done.countDown() }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) kotlin.concurrent.thread { work() } else work()
    }
    @Volatile private var mkStopped: java.util.concurrent.CountDownLatch? = null
    /** The drive for MakeMKV, once the run before has ended. */
    private fun mkLend(key: String): android.hardware.usb.UsbDevice? {
        mkStopped?.await(30, java.util.concurrent.TimeUnit.SECONDS)
        return discs.lend(key)
    }

    // ---- keys: the page asks for them as the keyCodes of a TV browser ----
    private fun pageKey(code: Int) {
        js("(function(){var e=new KeyboardEvent('keydown',{keyCode:$code,which:$code,bubbles:true});document.dispatchEvent(e);})()")
    }

    private fun bdKeyOf(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> Bd.VK_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Bd.VK_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> Bd.VK_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Bd.VK_RIGHT
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> Bd.VK_ENTER
        KeyEvent.KEYCODE_MENU -> Bd.VK_POPUP
        KeyEvent.KEYCODE_MEDIA_TOP_MENU, KeyEvent.KEYCODE_GUIDE -> Bd.VK_ROOT_MENU
        KeyEvent.KEYCODE_PROG_RED -> Bd.VK_RED
        KeyEvent.KEYCODE_PROG_GREEN -> Bd.VK_GREEN
        KeyEvent.KEYCODE_PROG_YELLOW -> Bd.VK_YELLOW
        KeyEvent.KEYCODE_PROG_BLUE -> Bd.VK_BLUE
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
        else -> -1
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // a disc from MakeMKV with its own menu: the keys go to the menu
        val rb = remoteBd
        if (rb != null && rb.opened) {
            val vk = bdKeyOf(event.keyCode)
            if (vk >= 0) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) kotlin.concurrent.thread { rb.key(vk) }
                return true
            }
        }
        val b = bd
        if (b != null && b.opened) {
            val vk = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> Bd.VK_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> Bd.VK_DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> Bd.VK_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> Bd.VK_RIGHT
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> Bd.VK_ENTER
                KeyEvent.KEYCODE_MENU -> Bd.VK_POPUP
                KeyEvent.KEYCODE_MEDIA_TOP_MENU, KeyEvent.KEYCODE_GUIDE -> Bd.VK_ROOT_MENU
                KeyEvent.KEYCODE_PROG_RED -> Bd.VK_RED
                KeyEvent.KEYCODE_PROG_GREEN -> Bd.VK_GREEN
                KeyEvent.KEYCODE_PROG_YELLOW -> Bd.VK_YELLOW
                KeyEvent.KEYCODE_PROG_BLUE -> Bd.VK_BLUE
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> event.keyCode - KeyEvent.KEYCODE_0
                else -> -1
            }
            if (vk >= 0) {
                // the number keys: 1..9 are BD_VK_1..9 ("0" is BD_VK_0), see keys.h
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) kotlin.concurrent.thread { b.key(vk) }
                return true
            }
        }
        val code = when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> 27
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> 10252
            KeyEvent.KEYCODE_MEDIA_PLAY -> 415
            KeyEvent.KEYCODE_MEDIA_PAUSE -> 19
            KeyEvent.KEYCODE_MEDIA_STOP -> 413
            KeyEvent.KEYCODE_MEDIA_NEXT -> 10233
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> 10232
            KeyEvent.KEYCODE_MEDIA_REWIND -> 412
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> 417
            KeyEvent.KEYCODE_MENU -> 457
            else -> 0
        }
        if (code != 0) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) pageKey(code)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        drives.permissionResult(grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })
    }

    override fun onDestroy() {
        main.removeCallbacks(tick)
        player?.release()
        player = null
        stopMakeMkv()
        discs.close()
        web.destroy()
        super.onDestroy()
    }

    /** What the page's platform file calls (assets/platform.js). Runs on a thread of the WebView. */
    inner class Bridge {
        @JavascriptInterface fun deviceName(): String = listOfNotNull(Build.MANUFACTURER, Build.MODEL).joinToString(" ")

        @JavascriptInterface fun localIp(): String {
            try {
                for (nif in NetworkInterface.getNetworkInterfaces()) {
                    if (!nif.isUp || nif.isLoopback) continue
                    for (a in nif.inetAddresses) if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress ?: ""
                }
            } catch (_: Exception) { }
            return ""
        }

        @JavascriptInterface fun exit() { main.post { finish() } }
        /** The page on top: a title prepared for a quick start is let go when its disc's page is left (the drive goes quiet). */
        @JavascriptInterface fun pageShown(what: String) {
            val w = mkWarm ?: return
            if (what.startsWith("disc:${w.host}")) return
            if (player?.isPlaying == true || mkPipe?.position?.let { it > 0 } == true) return
            mkLog("quick start: the page of the disc was left, $w is let go")
            kotlin.concurrent.thread { if (mkWarm == w) stopMakeMkv() }
        }

        @JavascriptInterface fun play(url: String, hls: Boolean) {
            chapters = emptyList()
            kotlin.concurrent.thread {
                val u = Uri.parse(url)
                if (u.scheme == "bdnav") { startBd(u); return@thread }
                if (u.scheme == "makemkv") { startMakeMkv(u); return@thread }
                if (u.scheme == "makemenu") { startMakeMenu(u); return@thread }
                val prepared = prepare(u)
                main.post { open(u, hls, prepared) }
            }
        }
        @JavascriptInterface fun resume() { main.post { player?.play() } }
        @JavascriptInterface fun pause() { main.post { player?.pause() } }
        @JavascriptInterface fun seek(ms: Double) { main.post { player?.seekTo(ms.toLong()) } }
        @JavascriptInterface fun stop() { main.post { closePlayer() } }

        @JavascriptInterface fun act(id: Int, path: String) {
            val key = path.removePrefix("disc:").substringBefore('|')
            val what = path.substringAfter('|')
            val say = { msg: String -> js("window.lumenToast&&window.lumenToast(${JSONObject.quote(msg)},6000)") }
            kotlin.concurrent.thread {
                when (what) {
                    "#rip" -> discs.rip(key, say)
                    "#keys" -> discs.loadKeyDatabase(say)
                    "#info" -> say(discs.keyInfo(key, true))
                    "#mkread" -> mkRead(key, say)
                    "#mkuhdtry" -> { discs.uhdTry.add(key); synchronized(mkLibreTriggered) { mkLibreTriggered.add(key) }; mkLibre(key) }
                    "#mklibre" -> { synchronized(mkLibreTriggered) { mkLibreTriggered.add(key) }; mkLibre(key) }
                    "#mkshift" -> mkShift(key)
                    "#mkdump" -> say(dumpMenuFiles(key))
                    "#mkmenu" -> mkMenuProbe(key, say)
                    "#mkreset" -> {
                        makemkv.forgetTitles(mkLabel(key)); mkTitles.remove(key); mkGuide.remove(key); mkStn.remove(key); mkMenuNames.remove(key); mkMenuTriggered.remove(key)
                        synchronized(mkReading) { mkReading.remove(key) }
                        js("window.__lumenDrivesChanged&&window.__lumenDrivesChanged()")
                    }
                    else -> if (what.startsWith("#mp")) {
                        // "#mpaudio:2": the third audio track of this disc
                        makemkv.setPick(mkLabel(key), what.substring(3).substringBefore(':'), what.substringAfter(':'))
                    } else discs.copy(key, say)
                }
                reply(id, "[]")
            }
        }
        /** What the controls show of what plays: its chapters, where it can be jumped to, and its picture and sound (see [infoJson]). */
        @JavascriptInterface fun info(id: Int) { main.post { reply(id, infoJson()) } }
        /** The audio and subtitle tracks of what plays: [{type, id, label, selected, ok, forced}]. */
        @JavascriptInterface fun tracks(id: Int) { main.post { reply(id, tracksJson()) } }
        /** The track list was closed: the film goes on if it was held for it. */
        @JavascriptInterface fun tracksClosed() { main.post { if (pausedForPanel) { pausedForPanel = false; player?.play() } } }
        /** Chooses a track ([which] as from [tracks], or "off" for no subtitles). */
        @JavascriptInterface fun track(type: String, which: String) { main.post { selectTrack(type, which) } }

        /** The settings the television itself offers: [section] "" lists the sections, else the items of one. */
        @JavascriptInterface fun settings(id: Int, section: String) { kotlin.concurrent.thread { reply(id, settingsJson(section)) } }
        @JavascriptInterface fun settingAct(id: Int, item: String) { kotlin.concurrent.thread { settingAct(item); reply(id, "[]") } }

        @JavascriptInterface fun drives(id: Int) { drives.list { json -> reply(id, json) } }
        @JavascriptInterface fun folder(id: Int, path: String) { drives.folder(path) { json -> reply(id, json) } }

        private fun reply(id: Int, json: String) {
            js("window.__lumenCb&&window.__lumenCb($id,${JSONObject.quote(json)})")
        }
    }
}
