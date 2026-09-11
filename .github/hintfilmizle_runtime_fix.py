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
                if (window.__csHintKineV12) return true;
                window.__csHintKineV12 = true;

                // Do not replay, replace, or cancel the Kinescope API request.
                // The player must complete its own handshake and create the signed HLS URL.
                function isManifest(u) {
                  try {
                    if (typeof u !== 'string') return null;
                    var m = u.match(/https?:\\/\\/[^\\s\"']+\\.kinescopecdn\\.net\\/hls\\/[^\\s\"']+\\/index\\.m3u8(?:\\?[^\\s\"']*)?/i);
                    return m ? m[0] : null;
                  } catch (_) { return null; }
                }

                // Remove transparent/click-blocking advertisement overlays only.
                // Never touch video, source, iframe, API, player or HLS elements.
                function cleanOverlays(root) {
                  try {
                    var selectors = [
                      '.belink', '.belink.active', '[class*="belink"]', '[id*="belink"]',
                      '.ad-overlay', '.ad-overlay-container', '.advertisement-overlay',
                      '.video-ad-overlay', '.player-ad-overlay'
                    ];
                    root.querySelectorAll(selectors.join(',')).forEach(function(e) {
                      e.style.setProperty('display','none','important');
                      e.style.setProperty('visibility','hidden','important');
                      e.style.setProperty('pointer-events','none','important');
                    });
                  } catch (_) {}
                }

                // Kinescope may wait for a user gesture before starting. CloudStream has
                // already requested autoplay, so keep the actual player video running.
                function startVideos(root) {
                  try {
                    root.querySelectorAll('video').forEach(function(v) {
                      try {
                        v.muted = true;
                        v.setAttribute('muted','');
                        v.setAttribute('playsinline','');
                        v.setAttribute('webkit-playsinline','');
                        v.autoplay = true;
                        if (v.paused || v.readyState < 2) {
                          var p = v.play();
                          if (p && p.catch) p.catch(function(){});
                        }
                      } catch (_) {}
                    });
                  } catch (_) {}
                }

                // Watch only what the player has already requested. We do not make our
                // own API request and therefore do not duplicate nonce/signature traffic.
                function scanResources() {
                  try {
                    cleanOverlays(document);
                    startVideos(document);
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
                setTimeout(scanResources, 100);
                setTimeout(scanResources, 300);
                setTimeout(scanResources, 700);
                setTimeout(scanResources, 1500);
                setTimeout(scanResources, 3000);
                setInterval(scanResources, 1000);

                new MutationObserver(function() {
                  try {
                    cleanOverlays(document);
                    startVideos(document);
                  } catch (_) {}
                }).observe(document.documentElement || document, {
                  subtree:true,
                  childList:true,
                  attributes:true,
                  attributeFilter:['class','style']
                });

                return true;
              } catch (_) { return false; }
            })()
        """.trimIndent()'''

text = text[:start] + script + text[end + len('        """.trimIndent()'):]
PATH.write_text(text, encoding='utf-8')
print('HintFilmIzle Kinescope runtime upgraded to V12: player handshake untouched, ad overlays cleaned, playback triggered, final signed M3U8 only')
