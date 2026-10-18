/* cloud-media-center — the legal multi-source player (Movies / Docs detail sheet).
 *
 * Browser half only. The source list is web/data/sources.json and the logic is
 * web/assets/sources-core.js (window.LegalSources), both copied by data/regen.py
 * from diegonmarcos.github.io at build.json::data.shared.commit, so the web
 * site and this app read the same declaration. Settings are edited on the
 * Configs → Movies page (assets/configs.js, the shared MOVIE_CONFIG schema; the
 * ⚙ here opens it), live in this app's webview storage only, never committed. Links out (where-to-watch
 * deep links) are a[data-ext], which pages.js hands to the phone's apps.
 */
(function (root) {
  'use strict';
  var L = root.LegalSources, doc = root.document, KEY = 'cmc_sources_v1';
  var decl = null, settings = load(), run = 0, cleanup = function () {};
  // User-defined torrent sources: managed on Configs → Movies, searched here.
  var UKEY = 'cmc_user_sources_v1', users = [], found = [];
  function loadUsers() { try { return L.parseUserSources(root.localStorage.getItem(UKEY) || '[]'); } catch (e) { return []; } }
  var ICON = { library: '🗄', local: '📁', providers: '📺', trailer: '🎞', catalogue: '🏛', torrent: '🧲' };

  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }
  function load() {
    var raw = '{}'; try { raw = root.localStorage.getItem(KEY) || '{}'; } catch (e) { /* storage blocked */ }
    return L.normalizeMovieConfig(raw, root.navigator.language);
  }
  function save() { try { root.localStorage.setItem(KEY, JSON.stringify(settings)); } catch (e) { /* storage blocked: this visit only */ } }
  function get(url, headers) { return fetch(url, { headers: headers }).then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); }); }
  function declared() {
    if (decl) return Promise.resolve(decl);
    return fetch('../data/sources.json').then(function (r) { return r.json(); }).then(function (j) { decl = L.parseSources(j); return decl; });
  }

  var el, files = [];
  function shell(m) {
    el = doc.createElement('div');
    el.className = 'player';
    el.innerHTML = '<div class="player__top"><b>' + esc(m.title) + '</b><span class="muted"> ' + esc(m.year || '') + '</span>' +
      '<a class="icon" href="../configs/index.html#movies" aria-label="Movies config">⚙</a><button class="icon" data-act="close" aria-label="Close">✕</button></div>' +
      '<div class="player__stage"><p class="empty">Looking for this title in your sources…</p></div>' +
      '<div class="player__err" role="alert" hidden></div>' +
      '<div class="player__picker"><div class="player__list"></div>' +
      '<label class="player__row">📁 Files on this phone <input type="file" accept="video/*" multiple data-act="files"></label>' +
      '<form class="player__row" data-act="paste"><input name="t" placeholder="magnet:… or https://….torrent"><button class="btn">▶</button></form>' +
      '<form class="player__usearch" data-act="usearch"' + (users.length ? '' : ' hidden') + '><div class="muted">Your sources</div><div class="player__row"><input name="q" placeholder="Search: ' + esc(m.title) + '"><button class="btn">⌕</button></div><div class="player__ures"></div></form>' +
      '<label class="player__row">Buffer <select data-act="buffer">' + L.streamBufferModes().map(function (b) { return '<option value="' + b.id + '"' + (b.id === settings.buffer ? ' selected' : '') + '>' + esc(b.label) + '</option>'; }).join('') + '</select></label>' +
      '<label class="player__row"><input type="checkbox" data-act="offline"' + (settings.offline ? ' checked' : '') + ' style="flex:none"> Offline: download, then play</label></div>';
    doc.body.appendChild(el);
  }
  function err(msg) { var e = el.querySelector('.player__err'); e.hidden = !msg; e.textContent = msg ? '⚠ ' + msg : ''; }

  function probe(m) {
    var my = ++run, title = { imdbId: m.id, tmdbId: m.tmdbId, type: m.type, title: m.title, year: m.year }, list = el.querySelector('.player__list'), have = [], done = 0;
    list.innerHTML = '<div class="muted">Checking sources…</div>';
    return declared().then(function (all) {
      var d = L.enabledSources(all, settings);
      var pre = settings.tmdb_key && L.isImdbId(m.id) ? L.imdbToTmdb(get, m.id, m.type, settings.tmdb_key).catch(function () { return null; }) : Promise.resolve(null);
      return pre.then(function (f) {
        if (f) title.tmdbId = f.tmdbId;
        var cfg = Object.assign({}, settings, { deviceFiles: files });
        return L.probeAll(d, title, cfg, get, function (r) {
          if (my !== run) return;
          done++; if (r.ok) have.push(r);
          have.sort(function (a, b) { return a.source.order - b.source.order; });
          var failed = [];
          list.innerHTML = have.map(function (r, i) { return '<button class="src" data-src="' + i + '">' + ICON[r.source.kind] + ' ' + esc(r.source.name) + (r.badge ? ' <small class="badge">' + esc(r.badge) + '</small>' : '') + '</button>'; }).join('') +
            (done < d.sources.length ? '<div class="muted">checking ' + (d.sources.length - done) + '…</div>' : '');
          list.__have = have; list.__failed = failed;
        }).then(function (all) {
          if (my !== run) return;
          var failed = all.filter(function (r) { return r.error; });
          if (!have.length) {
            list.innerHTML = '<p class="empty">No legal source has this title.<br><span class="muted">Add your Jellyfin/Plex/Emby server, a drive folder or a TMDB key in ⚙ (Configs → Movies), pick files on this phone, or paste a torrent you have the right to share.</span></p>';
            el.querySelector('.player__stage').innerHTML = '<p class="empty">Nothing to play.</p>';
          }
          if (failed.length) list.insertAdjacentHTML('beforeend', '<details class="muted"><summary>' + failed.length + ' source(s) failed</summary>' + failed.map(function (r) { return '<div>' + esc(r.source.name) + ': ' + esc(r.error) + '</div>'; }).join('') + '</details>');
          var first = have.filter(function (r) { return r.play || r.embed || r.file; })[0] || have[0];
          if (first) choose(first);
        });
      });
    }).catch(function (e) { list.innerHTML = '<p class="empty">Could not load the source list: ' + esc(e.message || e) + '</p>'; });
  }

  function choose(r) {
    cleanup(); cleanup = function () {}; err('');
    var stage = el.querySelector('.player__stage');
    [].forEach.call(el.querySelectorAll('.src'), function (b) { b.classList.toggle('on', (el.querySelector('.player__list').__have || [])[+b.getAttribute('data-src')] === r); });
    if (r.embed) { stage.innerHTML = '<iframe src="' + esc(r.embed) + '" allow="autoplay; fullscreen; picture-in-picture" allowfullscreen></iframe>'; return; }
    if (r.groups) {
      stage.innerHTML = '<div class="providers">' + r.groups.map(function (g) {
        return '<h3>' + esc(g.label) + '</h3><div class="prov-row">' + g.services.map(function (s) { return '<a class="prov" href="' + esc(s.link) + '" data-ext>' + (s.logo ? '<img alt="" src="' + esc(s.logo) + '">' : '') + '<span>' + esc(s.name) + '</span></a>'; }).join('') + '</div>';
      }).join('') + '<p class="muted">From TMDB / JustWatch for ' + esc(settings.region) + '.</p></div>';
      return;
    }
    stage.innerHTML = '<video controls playsinline preload="auto"></video><div class="bufbar"><i class="buf"></i><i class="pos"></i></div><div class="player__status muted"></div>';
    var v = stage.querySelector('video'), status = stage.querySelector('.player__status'), pol = L.bufferPolicy(L.effectiveBuffer(settings)), objectUrl = '', client = null, alive = true;
    cleanup = function () { alive = false; try { v.pause(); v.removeAttribute('src'); v.load(); } catch (e) {} if (objectUrl) URL.revokeObjectURL(objectUrl); if (client) try { client.destroy(); } catch (e) {} };
    v.addEventListener('progress', bar); v.addEventListener('timeupdate', bar);
    v.addEventListener('waiting', function () { if (!pol.offline && !v.paused) { v.pause(); hold(); } });
    v.addEventListener('error', function () {
      var c = v.error && v.error.code;
      err({ 1: 'Playback was aborted.', 2: 'A network error stopped the download.', 3: 'This file could not be decoded (MP4/H.264 is safest).', 4: 'The source is unreachable, not a video, or refused (check the URL/token).' }[c] || 'The video could not be played.');
    });
    function bar() {
      if (!v.duration || !isFinite(v.duration)) return;
      stage.querySelector('.buf').style.width = (v.buffered.length ? v.buffered.end(v.buffered.length - 1) / v.duration * 100 : 0) + '%';
      stage.querySelector('.pos').style.width = (v.currentTime / v.duration * 100) + '%';
    }
    function hold() {
      if (pol.offline) { v.play().catch(function () {}); return; }
      status.textContent = 'Buffering ' + (isFinite(pol.startAhead) ? pol.startAhead + ' s' : 'everything') + ' ahead…';
      (function check() {
        if (!alive) return;
        if (L.readyToPlay(pol, L.bufferedAhead(v.buffered, v.currentTime), v.currentTime, v.duration)) { status.textContent = ''; v.play().catch(function () {}); return; }
        setTimeout(check, 400);
      })();
    }
    function play(url, local) {
      if (pol.offline && !local) {
        status.textContent = 'Downloading…';
        return fetch(url).then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.blob(); })
          .then(function (b) { if (!alive) return; objectUrl = URL.createObjectURL(b); status.textContent = 'Downloaded.'; play(objectUrl, true); })
          .catch(function (e) { err('Download failed: ' + (e.message || e)); });
      }
      v.src = url; v.load(); hold();
    }
    if (r.page) status.insertAdjacentHTML('afterend', '<a class="muted" href="' + esc(r.page) + '" data-ext>Archive page ↗</a>');
    if (r.play) return play(r.play);
    if (r.file) { var f = files.filter(function (x) { return x.name === r.file; })[0]; if (f) { objectUrl = URL.createObjectURL(f); return play(objectUrl, true); } }
    if (r.torrent) {
      var p = L.parseTorrentInput(r.torrent);
      if (p.error) return err(p.error);
      status.textContent = 'Loading the torrent engine…';
      return loadWebTorrent().then(function (WT) {
        if (!alive) return;
        client = new WT(L.torrentClientOptions(settings));
        client.on('error', function (e) { err('Torrent error: ' + (e.message || e)); });
        client.add(p.uri, L.torrentAddOptions(settings), function (t) {
          var vf = L.pickTorrentVideo(t.files);
          if (!vf) { err('This torrent has no playable video (MP4/WebM).'); return; }
          t.files.forEach(function (x) { if (x !== vf) x.deselect(); }); vf.select();
          if (pol.offline) t.on('done', function () { vf.getBlobURL(function (e, u) { if (e) err(String(e)); else { objectUrl = u; play(u, true); } }); });
          else vf.renderTo(v, { autoplay: false }, function (e) { if (e) err('Torrent playback failed: ' + e.message); else hold(); });
          (function tick() { if (!alive) return; status.textContent = Math.round(t.progress * 100) + '% · ' + t.numPeers + ' peers · ' + Math.round(t.downloadSpeed / 1024) + ' KB/s'; if (t.progress < 1) { setTimeout(tick, 1000); return; } seed(Date.now()); })();
          // Done: seed until the configured ratio or time (Configs → Movies → Torrents), then stop.
          function seed(t0) { if (!alive) return; if (L.seedingDone(settings, t.ratio || 0, (Date.now() - t0) / 1000)) { try { t.pause(); t.wires.forEach(function (w) { w.destroy(); }); } catch (e) {} status.textContent = '100% · seeding stopped'; return; } setTimeout(function () { seed(t0); }, 5000); }
        });
      }).catch(function (e) { err('Torrent playback failed: ' + (e.message || e)); });
    }
  }
  function loadWebTorrent() {
    if (root.WebTorrent) return Promise.resolve(root.WebTorrent);
    return new Promise(function (ok, bad) {
      var s = doc.createElement('script'); s.src = 'https://cdn.jsdelivr.net/npm/webtorrent@1.9.7/webtorrent.min.js';
      s.onload = function () { ok(root.WebTorrent); }; s.onerror = function () { bad(new Error('could not load WebTorrent (offline?)')); };
      doc.head.appendChild(s);
    });
  }

  function open(m) {
    close(); settings = load(); users = loadUsers(); shell(m); files = [];
    el.addEventListener('click', function (e) {
      var a = e.target.closest('[data-act]'), s = e.target.closest('[data-src]');
      if (s) return choose(el.querySelector('.player__list').__have[+s.getAttribute('data-src')]);
      var ur = e.target.closest('[data-ures]');
      if (ur) {
        var r = found[+ur.getAttribute('data-ures')];
        if (r.open) { var link = doc.createElement('a'); link.href = r.open; link.setAttribute('data-ext', ''); doc.body.appendChild(link); link.click(); link.remove(); return; }
        if (!(r.magnet || r.torrent)) return err('That result has no magnet or .torrent link.');
        return choose({ source: { id: 'user', name: r.title, kind: 'torrent', order: 999 }, ok: true, torrent: r.magnet || r.torrent, badge: r.badge });
      }
      if (!a) return;
      if (a.getAttribute('data-act') === 'close') close();
    });
    el.addEventListener('change', function (e) {
      var a = e.target.getAttribute('data-act');
      if (a === 'files') { files = [].slice.call(e.target.files || []); probe(m); }
      if (a === 'buffer') { settings.buffer = e.target.value; save(); }
      if (a === 'offline') { settings.offline = e.target.checked; save(); }
    });
    el.addEventListener('submit', function (e) {
      e.preventDefault();
      if (e.target.getAttribute('data-act') === 'usearch') {
        var box = e.target.querySelector('.player__ures'); box.innerHTML = '<div class="muted">searching…</div>';
        var getText = function (u) { return fetch(u).then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.text(); }); };
        return L.searchUserSources(users, e.target.q.value || m.title, getText).then(function (out) {
          found = out.results;
          box.innerHTML = found.map(function (r, i) { return '<button type="button" class="src" data-ures="' + i + '">🧲 ' + esc(r.title) + ' <small class="badge">' + esc(r.badge) + (r.seeders != null ? ' · ' + r.seeders + ' seeders' : '') + '</small></button>'; }).join('') +
            (found.length ? '' : '<div class="muted">No results.</div>') + out.errors.map(function (x) { return '<div class="muted">' + esc(x.name) + ': ' + esc(x.error) + '</div>'; }).join('');
        });
      }
      if (e.target.getAttribute('data-act') === 'paste') {
        var p = L.parseTorrentInput(e.target.t.value);
        if (p.error) return err(p.error);
        return choose({ source: { id: 'pasted', name: 'Your torrent', kind: 'torrent', order: 999 }, ok: true, torrent: p.uri });
      }
    });
    probe(m);
  }
  function close() { cleanup(); cleanup = function () {}; run++; if (el) el.remove(); el = null; }

  root.MoviePlayer = { open: open, close: close };
})(window);
