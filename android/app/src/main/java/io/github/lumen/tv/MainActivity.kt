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
        discs.extraEntries = { key -> mkEntries(key) }
        makemkv.onStuck = {
            if ((mkPipe?.position ?: 0L) > 0L) {
                mkReload = true
                mkLog("drive stuck during the film")
                say("The drive stopped answering during the film. Start it again (the disc is ejected and loaded again first).", 20000)
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
        val factory = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        // a short look-ahead: the menu of a disc runs ahead of the picture by what the player has buffered;
        // MakeMKV's stream comes in bursts: a long one
        val load = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .apply { if (long) setBufferDurationsMs(20000, 60000, 5000, 8000) else setBufferDurationsMs(2000, 4000, 800, 1500) }.build()
        val p = ExoPlayer.Builder(this, factory).setLoadControl(load).build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> event("waiting")
                    Player.STATE_READY -> event(if (p.playWhenReady) "playing" else "pause")
                    Player.STATE_ENDED -> event("ended")
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
    private fun event(name: String) {
        val p = player ?: return
        val dur = if (p.duration > 0) p.duration else 0
        if (name == "timeupdate" && p.currentPosition / 5000 != lastLogged) {
            lastLogged = p.currentPosition / 5000
            android.util.Log.i("LumenTV", "position ${p.currentPosition} duration ${p.duration} seekable ${p.isCurrentMediaItemSeekable}")
        }
        js("window.__lumenEvent&&window.__lumenEvent('$name',${p.currentPosition},$dur,${if (p.isPlaying || (p.playWhenReady && p.playbackState == Player.STATE_BUFFERING)) "false" else "true"})")
    }

    fun js(code: String) {
        main.post { if (pageReady) web.evaluateJavascript(code, null) }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun open(uri: Uri, hls: Boolean, prepared: androidx.media3.extractor.ExtractorsFactory? = null) {
        val p = ensurePlayer(uri.scheme == "makemkv")
        if (uri.scheme == "makemkv") {
            val pipe = mkPipe ?: return
            val factory = androidx.media3.datasource.DataSource.Factory { PipeDataSource(pipe) }
            // only Matroska, and no look for the index at the end: the stream cannot go there and back
            val extractors = androidx.media3.extractor.ExtractorsFactory {
                arrayOf(androidx.media3.extractor.mkv.MatroskaExtractor(
                    androidx.media3.extractor.text.SubtitleParser.Factory.UNSUPPORTED,
                    androidx.media3.extractor.mkv.MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES))
            }
            val source = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(factory, extractors)
                .setContinueLoadingCheckIntervalBytes(256 * 1024)
                .createMediaSource(MediaItem.fromUri(uri))
            p.setMediaSource(source)
        } else if (uri.scheme == "bdnav") {
            val b = bd ?: return
            val factory = BdDataSource.Factory(b) { type, value -> bdEvent(type, value) }
            val extractors = androidx.media3.extractor.DefaultExtractorsFactory()
                .setTsExtractorFlags(androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
                .setConstantBitrateSeekingEnabled(false)
            val source = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(factory, extractors)
                .createMediaSource(MediaItem.fromUri(uri))
            p.setMediaSource(source)
        } else if (uri.scheme == "disc") {
            // a file of a disc the app reads itself
            val factory = DiscDataSource.Factory { key, path -> discs.reader(key, path) }
            // a Blu-ray title: the length and seeking from the stream's clock (see BdavSeek)
            val extractors: androidx.media3.extractor.ExtractorsFactory = prepared ?: androidx.media3.extractor.DefaultExtractorsFactory()
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

    private fun closeBd() {
        val b = bd ?: return
        bd = null
        bdOverlay.visibility = View.GONE
        bdBitmaps.fill(null)
        kotlin.concurrent.thread { b.close() }
    }

    private fun closePlayer() {
        closeBd()
        stopMakeMkv()
        main.removeCallbacks(tick)
        player?.let { it.stop(); it.clearMediaItems() }
        playerView.visibility = View.INVISIBLE
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

    private fun say(msg: String, ms: Int = 7000) = js("window.lumenToast&&window.lumenToast(${JSONObject.quote(msg)},$ms)")

    private fun mkEntries(key: String): List<JSONObject> {
        if (!key.startsWith("usb") || !makemkv.available) return emptyList()
        val out = ArrayList<JSONObject>()
        val titles = mkTitles[key]
        if (titles == null) {
            out.add(JSONObject().put("name", "MakeMKV (test): play the main title").put("path", "disc:$key|#mkplay")
                .put("dir", false).put("uri", "makemkv://$key/main").put("video", true))
        } else {
            for (t in titles.sortedByDescending { it.durationMs })
                out.add(JSONObject().put("name", "MakeMKV: ${t.name} – ${t.durationMs / 60000} min, ${t.size}").put("path", "disc:$key|#mk${t.id}")
                    .put("dir", false).put("uri", "makemkv://$key/${t.id}").put("video", true))
        }
        val mode = makemkv.keyMode
        out.add(JSONObject().put("name", "MakeMKV key: the beta key from the forum, updated daily" + if (mode == "beta") " (in use)" else "")
            .put("path", "disc:$key|#mkbeta").put("dir", false).put("action", "mkbeta"))
        out.add(JSONObject().put("name", "MakeMKV key: enter your own" + if (mode == "manual") " (in use)" else "")
            .put("path", "disc:$key|#mkown").put("dir", false).put("action", "mkown"))
        return out
    }

    private fun askOwnKey() = main.post {
        val edit = android.widget.EditText(this).apply { setSingleLine(); setText(makemkv.ownKey()); hint = "T-…  or  M-…" }
        android.app.AlertDialog.Builder(this)
            .setTitle("Your MakeMKV registration key")
            .setView(edit)
            .setPositiveButton("OK") { _, _ ->
                val k = edit.text.toString().trim()
                if (k.length < 20) say("That is not a MakeMKV key.")
                else kotlin.concurrent.thread { makemkv.setOwnKey(k); say("MakeMKV: your key is entered."); js("window.__lumenDrivesChanged&&window.__lumenDrivesChanged()") }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Runs on a worker thread: lends the drive to MakeMKV, has it stream the title, plays the stream. */
    private fun startMakeMkv(uri: Uri) {
        val key = uri.host ?: return
        val which = uri.path.orEmpty().trim('/')
        val closed = java.util.concurrent.CountDownLatch(1)
        main.post { closePlayer(); closed.countDown() }
        closed.await()
        mkLog("---- play $uri")
        if (makemkv.keyMode.isEmpty()) say("MakeMKV has no key yet: choose the forum's beta key or enter your own (in the drive's list).", 10000)
        val device = discs.lend(key)
        if (device == null) { say("The drive is gone."); discs.giveBack(key); return }
        mkKey = key
        try {
            say("MakeMKV: opening the drive …")
            if (!makemkv.openBridge(device)) { say("MakeMKV: the drive does not answer (see makemkv-log.txt)."); stopMakeMkv(); return }
            if (mkReload) {
                mkReload = false
                say("The disc is ejected and loaded again …", 60000)
                makemkv.ejectAndReload()
            }
            for (attempt in 1..2) {
                if (mkKey != key) return
                if (mkAttempt(key, which, uri)) return
                if (!makemkv.driveStuck || mkKey != key) { stopMakeMkv(); return }
                // the drive answers reads with timeouts: the disc out and in once; then it is the user's turn
                if (attempt == 1) {
                    mkLog("drive stuck: eject and reload")
                    say("The drive stopped answering: the disc is ejected and loaded again …", 60000)
                    mkPipe?.close(); mkPipe = null
                    if (!makemkv.ejectAndReload()) break
                }
            }
            mkLog("drive still stuck: the user is asked to switch it off and on")
            say("The drive still does not answer. Switch the drive off and on again, then start the film again.", 30000)
            stopMakeMkv()
        } catch (e: Exception) {
            mkLog("failed: ${android.util.Log.getStackTraceString(e)}")
            say("MakeMKV: $e")
            stopMakeMkv()
        }
    }

    /** One try: the titles, then the stream; true when the player got it (or nothing more is to be done). */
    private fun mkAttempt(key: String, which: String, uri: Uri): Boolean {
        var titles = mkTitles[key]
        if (titles == null) {
            say("MakeMKV: reading the disc (this takes a minute) …", 60000)
            titles = makemkv.titles()
            if (makemkv.driveStuck) return false
            mkLog("titles: " + titles.joinToString(" | "))
            if (titles.isNotEmpty()) mkTitles[key] = titles
            js("window.__lumenDrivesChanged&&window.__lumenDrivesChanged()")
        }
        if (mkKey != key) return true
        val t = (if (which == "main") titles.maxByOrNull { it.durationMs } else titles.firstOrNull { it.id.toString() == which })
        if (t == null) { say("MakeMKV found no title on this disc (see makemkv-log.txt).", 10000); stopMakeMkv(); return true }
        say("MakeMKV: starting ${t.name} (${t.durationMs / 60000} min) …", 60000)
        val pipe = PipeStream(makemkv.stream(t)) { mkLog(it) }
        mkPipe = pipe
        val t0 = System.currentTimeMillis()
        if (!pipe.awaitStart()) {
            if (makemkv.driveStuck) return false
            say("MakeMKV stopped before the film began (see makemkv-log.txt).", 10000); stopMakeMkv(); return true
        }
        mkLog("first bytes after ${System.currentTimeMillis() - t0} ms")
        if (mkKey != key) return true
        main.post { open(uri, false, null) }
        return true
    }

    private fun stopMakeMkv() {
        val key = mkKey ?: return
        mkKey = null
        mkLog("---- stop (${mkPipe?.position?.let { "%.1f MB played".format(java.util.Locale.ROOT, it / 1e6) } ?: "nothing played"})")
        makemkv.stop()
        mkPipe?.close(); mkPipe = null
        makemkv.closeBridge()
        discs.giveBack(key)
    }

    // ---- keys: the page asks for them as the keyCodes of a TV browser ----
    private fun pageKey(code: Int) {
        js("(function(){var e=new KeyboardEvent('keydown',{keyCode:$code,which:$code,bubbles:true});document.dispatchEvent(e);})()")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
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

        @JavascriptInterface fun play(url: String, hls: Boolean) {
            kotlin.concurrent.thread {
                val u = Uri.parse(url)
                if (u.scheme == "bdnav") { startBd(u); return@thread }
                if (u.scheme == "makemkv") { startMakeMkv(u); return@thread }
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
                    "#mkbeta" -> { makemkv.useBetaKey(); say("MakeMKV: the beta key is fetched from the forum now and every day.") }
                    "#mkown" -> askOwnKey()
                    else -> discs.copy(key, say)
                }
                reply(id, "[]")
            }
        }
        @JavascriptInterface fun drives(id: Int) { drives.list { json -> reply(id, json) } }
        @JavascriptInterface fun folder(id: Int, path: String) { drives.folder(path) { json -> reply(id, json) } }

        private fun reply(id: Int, json: String) {
            js("window.__lumenCb&&window.__lumenCb($id,${JSONObject.quote(json)})")
        }
    }
}
