from pathlib import Path

PATH = Path('HintFilmIzle/src/main/kotlin/com/nikyokki/HintFilmIzlePlugin.kt')
text = PATH.read_text(encoding='utf-8')
start = text.find('        val script = """')
if start < 0:
    raise SystemExit('Kinescope script start not found')
end = text.find('        """.trimIndent()', start)
if end < 0:
    raise SystemExit('Kinescope script end not found')

script = r'''        val script = """
            (function() {
              try {
                if (window.__csHintKineV13) return true;
                window.__csHintKineV13 = true;

                // Never replay/cancel Kinescope API requests. The player owns the
                // handshake and must be allowed to create its signed HLS URL.
                function isManifest(u) {
                  try {
                    if (typeof u !== 'string') return null;
                    var m = u.match(/https?:\\/\\/[^\\s\"']+\\.kinescopecdn\\.net\\/hls\\/[^\\s\"']+\\/index\\.m3u8(?:\\?[^\\s\"']*)?/i);
                    return m ? m[0] : null;
                  } catch (_) { return null; }
                }

                // Remove known transparent ad/click overlays. Do not hide video,
                // source, iframe, player, API or HLS elements.
                function cleanAds(root) {
                  try {
                    var selectors = [
                      '.belink', '[class*="belink"]', '[id*="belink"]',
                      '.ad-overlay', '.ad-overlay-container',
                      '.advertisement-overlay', '.video-ad-overlay',
                      '.player-ad-overlay', '[data-ad-overlay]'
                    ];
                    root.querySelectorAll(selectors.join(',')).forEach(function(e) {
                      e.style.setProperty('display','none','important');
                      e.style.setProperty('visibility','hidden','important');
                      e.style.setProperty('pointer-events','none','important');
                    });
                  } catch (_) {}
                }

                // Block popup/new-tab navigation commonly used by ad click layers,
                // while leaving the current Kinescope document and network untouched.
                try {
                  window.open = function() { return null; };
                } catch (_) {}

                function startPlayer() {
                  try {
                    cleanAds(document);
                    document.querySelectorAll('video').forEach(function(v) {
                      try {
                        v.muted = true;
                        v.autoplay = true;
                        v.setAttribute('muted','');
                        v.setAttribute('autoplay','');
                        v.setAttribute('playsinline','');
                        v.setAttribute('webkit-playsinline','');
                        v.removeAttribute('controlslist');
                        if (v.paused || v.readyState < 2) {
                          var p = v.play();
                          if (p && p.catch) p.catch(function(){});
                        }
                      } catch (_) {}
                    });

                    // Some Kinescope builds expose a native play button after the
                    // player is mounted. Clicking only recognized play controls avoids
                    // clicking arbitrary ad links.
                    var buttons = document.querySelectorAll(
                      'button[aria-label*="Play" i], button[title*="Play" i], ' +
                      '[role="button"][aria-label*="Play" i], .kinescope-player button'
                    );
                    for (var i = 0; i < buttons.length; i++) {
                      try {
                        var b = buttons[i];
                        var label = ((b.getAttribute('aria-label') || '') + ' ' +
                                     (b.getAttribute('title') || '')).toLowerCase();
                        if (label.indexOf('play') >= 0 && label.indexOf('playlist') < 0) {
                          b.click();
                          break;
                        }
                      } catch (_) {}
                    }
                  } catch (_) {}
                }

                function scanResources() {
                  try {
                    cleanAds(document);
                    startPlayer();
                    var es = performance.getEntriesByType('resource') || [];
                    for (var i = es.length - 1; i >= 0; i--) {
                      var u = String(es[i].name || '');
                      var m = isManifest(u);
                      if (m) {
                        window.__csHintManifest = m;
                        return;
                      }
                    }
                  } catch (_) {}
                }

                scanResources();
                [100,300,700,1500,3000,5000,10000].forEach(function(ms) {
                  setTimeout(scanResources, ms);
                });
                setInterval(scanResources, 1000);

                try {
                  new MutationObserver(function() {
                    cleanAds(document);
                    startPlayer();
                  }).observe(document.documentElement || document, {
                    subtree:true,
                    childList:true,
                    attributes:true,
                    attributeFilter:['class','style','aria-label']
                  });
                } catch (_) {}

                return true;
              } catch (_) { return false; }
            })()
        """.trimIndent()'''

text = text[:start] + script + text[end + len('        """.trimIndent()'):]
PATH.write_text(text, encoding='utf-8')
print('HintFilmIzle Kinescope runtime upgraded to V13: player startup + safe ad overlay/popup suppression; no API replay/interception')
