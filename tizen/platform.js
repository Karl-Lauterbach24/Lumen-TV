/*
 * Lumen TV on Samsung TVs (Tizen): the parts receiver.js cannot know.
 * Loaded before receiver.js; everything is wrapped in try/catch because the
 * available APIs differ between TV model years.
 */
(function () {
  'use strict';

  var MEDIA_KEYS = ['MediaPlay', 'MediaPause', 'MediaPlayPause', 'MediaStop', 'MediaRewind',
                    'MediaFastForward', 'MediaTrackPrevious', 'MediaTrackNext'];

  function model() {
    try { return 'Samsung ' + window.webapis.productinfo.getModel(); } catch (e) { return 'Samsung TV'; }
  }

  window.LumenPlatform = {
    name: 'tizen',
    deviceName: model(),

    // The remote's media keys reach an app only after it asks for them
    init: function () {
      for (var i = 0; i < MEDIA_KEYS.length; i++) {
        try { window.tizen.tvinputdevice.registerKey(MEDIA_KEYS[i]); } catch (e) { /* key not on this remote */ }
      }
    },

    // Own address, to search the local network for Lumen
    localIp: function (done) {
      try { done(window.webapis.network.getIp()); } catch (e) { done(''); }
    },

    exit: function () {
      try { window.tizen.application.getCurrentApplication().exit(); } catch (e) { /* ignore */ }
    },

    keys: {
      10009: 'back', 415: 'play', 19: 'pause', 10252: 'playpause', 413: 'stop',
      412: 'rewind', 417: 'forward', 10232: 'prev', 10233: 'next', 10135: 'menu'
    }
  };
})();
