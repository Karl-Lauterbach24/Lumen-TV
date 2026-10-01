/*
 * Lumen TV on LG TVs (webOS): the parts receiver.js cannot know.
 * Loaded before receiver.js.
 */
(function () {
  'use strict';

  // Luna service call without LG's webOSTV.js: the bridge object it wraps
  function luna(uri, params, done) {
    var Bridge = window.WebOSServiceBridge || window.PalmServiceBridge;
    if (!Bridge) { done(null); return; }
    try {
      var bridge = new Bridge();
      bridge.onservicecallback = function (message) {
        var reply = null;
        try { reply = JSON.parse(message); } catch (e) { /* not JSON */ }
        done(reply);
      };
      bridge.call(uri, JSON.stringify(params || {}));
    } catch (e) {
      done(null);
    }
  }

  window.LumenPlatform = {
    name: 'webos',
    deviceName: 'LG TV',

    // Own address, to search the local network for Lumen
    localIp: function (done) {
      luna('luna://com.palm.connectionmanager/getStatus', {}, function (status) {
        var ip = '';
        if (status) {
          if (status.wired && status.wired.state === 'connected') ip = status.wired.ipAddress;
          else if (status.wifi && status.wifi.state === 'connected') ip = status.wifi.ipAddress;
        }
        done(ip || '');
      });
    },

    exit: function () {
      try { window.close(); } catch (e) { /* ignore */ }
    },

    keys: { 461: 'back', 415: 'play', 19: 'pause', 413: 'stop', 412: 'rewind', 417: 'forward' }
  };
})();
