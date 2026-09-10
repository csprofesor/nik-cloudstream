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
                if (window.__csHintKineV7) return true;
                window.__csHintKineV7 = true;
                var KEY = 'RySdvcyu5iTUxn97vn4HwoniwgxaCynA';
                var done = false;
                function manifest(s) {
                  try {
                    if (typeof s !== 'string') return null;
                    var m = s.match(/https?:\\/\\/[^\\s\"']+\\.kinescopecdn\\.net\\/hls\\/[^\\s\"']+\\/index\\.m3u8(?:\\?[^\\s\"']*)?/i);
                    return m ? m[0] : null;
                  } catch (_) { return null; }
                }
                function emit(u) {
                  try {
                    u = manifest(u) || u;
                    if (!u || done) return;
                    done = true;
                    window.__csHintManifest = u;
                    var v = document.createElement('video');
                    v.muted = true;
                    v.setAttribute('muted', '');
                    v.setAttribute('playsinline', '');
                    v.preload = 'metadata';
                    v.src = u;
                    (document.documentElement || document.body).appendChild(v);
                    v.load();
                  } catch (_) {}
                }
                function decodeApi(text) {
                  try {
                    var o = JSON.parse(text);
                    if (!o || typeof o.p !== 'string') return;
                    var b = atob(o.p.split('').reverse().join(''));
                    var out = new Uint8Array(b.length);
                    for (var i = 0; i < b.length; i++) out[i] = b.charCodeAt(i) ^ KEY.charCodeAt(i % KEY.length);
                    var s = new TextDecoder('utf-8').decode(out);
                    var u = manifest(s);
                    if (!u) {
                      var p = JSON.parse(s);
                      var stack = [p];
                      while (stack.length && !u) {
                        var x = stack.pop();
                        if (typeof x === 'string') u = manifest(x);
                        else if (x && typeof x === 'object') for (var k in x) stack.push(x[k]);
                      }
                    }
                    if (u) emit(u);
                  } catch (_) {}
                }
                function inspectEntries() {
                  try {
                    var es = performance.getEntriesByType('resource') || [];
                    for (var i = 0; i < es.length; i++) {
                      var u = String(es[i].name || '');
                      if (/\\/api\\/v1\\/embed\\//i.test(u)) {
                        fetch(u, {credentials:'include'}).then(function(r){return r.text();}).then(decodeApi).catch(function(){});
                      } else if (/\\.m3u8(?:\\?|$)/i.test(u)) emit(u);
                    }
                  } catch (_) {}
                }
                var ofetch = window.fetch;
                if (ofetch) {
                  window.fetch = function() {
                    var a = arguments;
                    return ofetch.apply(this, a).then(function(r) {
                      try {
                        var u = typeof a[0] === 'string' ? a[0] : (a[0] && a[0].url);
                        if (/\\.m3u8(?:\\?|$)/i.test(String(u || ''))) emit(String(u));
                        if (/\\/api\\/v1\\/embed\\//i.test(String(u || ''))) r.clone().text().then(decodeApi).catch(function(){});
                      } catch (_) {}
                      return r;
                    });
                  };
                }
                var oo = XMLHttpRequest.prototype.open;
                var os = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open = function(method, url) { this.__csUrl = String(url || ''); return oo.apply(this, arguments); };
                XMLHttpRequest.prototype.send = function() {
                  try { this.addEventListener('load', function() { var u=String(this.__csUrl||''); if (/\\.m3u8(?:\\?|$)/i.test(u)) emit(u); if (/\\/api\\/v1\\/embed\\//i.test(u)) decodeApi(this.responseText||''); }); } catch (_) {}
                  return os.apply(this, arguments);
                };
                setInterval(inspectEntries, 250);
                setTimeout(inspectEntries, 100);
                return true;
              } catch (_) { return false; }
            })()
        """.trimIndent()'''
text = text[:start] + script + text[end + len('        """.trimIndent()'):]
PATH.write_text(text, encoding='utf-8')
print('HintFilmIzle Kinescope script upgraded to V7 timing-safe API/resource detection')