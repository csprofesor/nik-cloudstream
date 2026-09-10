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
                if (window.__csHintKineV11) return true;
                window.__csHintKineV11 = true;

                function manifest(u) {
                  try {
                    if (typeof u !== 'string') return null;
                    var m = u.match(/https?:\\/\\/[^\\s\"']+\\.kinescopecdn\\.net\\/hls\\/[^\\s\"']+\\/index\\.m3u8(?:\\?[^\\s\"']*)?/i);
                    return m ? m[0] : null;
                  } catch (_) { return null; }
                }

                function cleanOverlays(root) {
                  try {
                    root.querySelectorAll('.belink,.belink.active,[class*="belink"],[id*="belink"]').forEach(function(e) {
                      e.style.setProperty('display','none','important');
                      e.style.setProperty('visibility','hidden','important');
                      e.style.setProperty('pointer-events','none','important');
                    });
                  } catch (_) {}
                }

                function startVideos(root) {
                  try {
                    root.querySelectorAll('video').forEach(function(v) {
                      try {
                        v.muted = true;
                        v.setAttribute('muted','');
                        v.setAttribute('playsinline','');
                        v.autoplay = true;
                        if (v.paused) {
                          var p = v.play();
                          if (p && p.catch) p.catch(function(){});
                        }
                      } catch (_) {}
                    });
                  } catch (_) {}
                }

                function inspect() {
                  try {
                    cleanOverlays(document);
                    startVideos(document);

                    // The resolver watches network requests. We only read the
                    // browser's resource list; we never cancel or replay API calls.
                    var es = performance.getEntriesByType('resource') || [];
                    for (var i = 0; i < es.length; i++) {
                      var u = String(es[i].name || '');
                      if (manifest(u)) {
                        window.__csHintManifest = manifest(u);
                        return;
                      }
                    }
                  } catch (_) {}
                }

                // Start as soon as the Kinescope DOM/player exists, then keep
                // nudging playback without touching the signed API handshake.
                inspect();
                setTimeout(inspect, 100);
                setTimeout(inspect, 300);
                setTimeout(inspect, 700);
                setTimeout(inspect, 1500);
                setInterval(inspect, 1000);

                new MutationObserver(function() {
                  try { cleanOverlays(document); startVideos(document); } catch (_) {}
                }).observe(document.documentElement || document, {subtree:true, childList:true});

                return true;
              } catch (_) { return false; }
            })()
        """.trimIndent()'''

text = text[:start] + script + text[end + len('        """.trimIndent()'):]
PATH.write_text(text, encoding='utf-8')
print('HintFilmIzle Kinescope runtime upgraded to V11: no API replay/interception, playback is triggered safely')
