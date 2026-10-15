/*
 * Lumen TV on Android TV: the parts receiver.js cannot know. Loaded before receiver.js, inside the
 * app's WebView; everything native is behind the object "LumenAndroid" (MainActivity.kt).
 */
(function () {
  'use strict';
  var N = window.LumenAndroid;
  if (!N) return;

  var calls = {}, seq = 0;
  window.__lumenCb = function (id, json) {
    var f = calls[id];
    delete calls[id];
    if (f) { try { f(JSON.parse(json)); } catch (e) { f([]); } }
  };
  function ask(fn, arg, done) {
    var id = ++seq;
    calls[id] = done;
    if (arg === undefined) N[fn](id); else N[fn](id, arg);
  }

  // The page's <video> element stands in for the native player: position, duration and pause state
  // come from it, and its events are fired from what the player reports.
  var state = { pos: 0, dur: 0, paused: true };
  var proxied = null;
  var failed = null;
  function fire(video, name) {
    var e;
    try { e = new Event(name); } catch (x) { e = document.createEvent('Event'); e.initEvent(name, false, false); }
    video.dispatchEvent(e);
  }
  window.__lumenEvent = function (name, pos, dur, paused) {
    state.pos = pos / 1000; state.dur = dur / 1000; state.paused = paused;
    var v = proxied;
    if (!v) return;
    if (name === 'error') { if (failed) failed(); return; }
    if (name === 'ended') { state.ended = true; }
    fire(v, name);
  };
  function proxy(video, onFail) {
    proxied = video;
    failed = onFail;
    state = { pos: 0, dur: 0, paused: false };
    function def(name, get, set) {
      try { Object.defineProperty(video, name, { configurable: true, get: get, set: set }); } catch (e) { /* ignore */ }
    }
    def('currentTime', function () { return state.pos; }, function (t) { state.pos = t; N.seek(t * 1000); });
    def('duration', function () { return state.dur; });
    def('paused', function () { return state.paused; });
    video.play = function () { N.resume(); state.paused = false; return Promise.resolve(); };
    video.pause = function () { N.pause(); state.paused = true; };
  }
  function release(video) {
    if (proxied !== video) return;
    proxied = null;
    failed = null;
    try { N.stop(); } catch (e) { /* ignore */ }
    delete video.currentTime; delete video.duration; delete video.paused; delete video.play; delete video.pause;
  }

  window.LumenPlatform = {
    name: 'android',
    deviceName: String(N.deviceName() || 'Android TV'),
    native: true,

    init: function () { document.documentElement.className = 'native'; },
    localIp: function (done) { done(String(N.localIp() || '')); },
    exit: function () { N.exit(); },

    // the stream Lumen casts: HLS, played by the native player
    attach: function (video, cmd, onFail) {
      proxy(video, onFail);
      N.play(cmd.hls, true);
    },
    detach: function (video) { release(video); },

    // the drives connected to the television
    drives: {
      list: function (done) { ask('drives', undefined, done); },
      folder: function (path, done) { ask('folder', path, done); },
      attach: function (video, entry, onFail) {
        proxy(video, onFail);
        N.play(entry.uri, false);
      },
      detach: function (video) { release(video); },
      // an action of a disc ("copy"): messages arrive as toasts
      act: function (path, done) { ask('act', path, function () { done(); }); },
      // the audio and subtitle tracks of what plays, and choosing one ("off": no subtitles)
      tracks: function (done) { ask('tracks', undefined, done); },
      track: function (type, id) { N.track(type, id); },
      watch: function (cb) { window.__lumenDrivesChanged = cb; }
    },

    keys: {}
  };
})();
