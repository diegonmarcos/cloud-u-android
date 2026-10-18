/* cloud-media-center — the header Settings button, the Configs page and the Profile page.
 *
 * Two halves, like pages.js. The pure half (TABS, loadTab/normalizeTab,
 * renderConfigs, renderProfile, menuHtml) takes values in and gives HTML out,
 * so test/test-media-center-configs.sh drives it under node. The browser half
 * wires the round ⚙ button at the top right of every page's header, its
 * dropdown (Profile, Configs) and the forms, saving every change at once.
 *
 * Everything is stored in this app's webview storage only, never committed.
 * Movies uses the schema shared with My Movies (sources-core.js::MOVIE_CONFIG,
 * copied by data/regen.py), under the player's existing keys.
 */
(function (root) {
  'use strict';
  var L = root.LegalSources || (typeof require === 'function' ? require('./sources-core.js') : null);
  var MOVIE_KEY = 'cmc_sources_v1', USER_KEY = 'cmc_user_sources_v1';
  var ACCOUNT_INTENT = 'intent:#Intent;action=com.diegonmarcos.cloudaccount.OPEN;package=com.diegonmarcos.cloudaccount;end';
  var SIZE = { type: 'select', key: 'size', label: 'Card size', options: [['small', 'Small'], ['normal', 'Normal'], ['large', 'Large']], def: 'normal' };
  var START = { type: 'text', key: 'start', label: 'Open on filter (chip name, empty = All)', def: '' };

  // One section per media tab. Movies draws from the shared MOVIE_CONFIG instead.
  var TABS = [
    { id: 'music', label: 'Music', icon: '♪', fields: [
      { type: 'text', key: 'search', label: 'Track search URL (TIDAL; the track is appended)', def: '' }, START, SIZE] },
    { id: 'videos', label: 'Videos', icon: '▶', fields: [
      { type: 'bool', key: 'durations', label: 'Show video durations', def: true }, START, SIZE] },
    { id: 'gallery', label: 'Gallery', icon: '▦', fields: [
      { type: 'text', key: 'profile_url', label: 'Pinterest profile URL (empty = the synced one)', def: '' }, SIZE] },
    { id: 'books', label: 'Books', icon: '❏', fields: [
      { type: 'bool', key: 'covers', label: 'Load covers from Open Library', def: true }, SIZE] },
    { id: 'feed', label: 'Feed', icon: '≡', fields: [
      { type: 'bool', key: 'expand', label: 'Open articles expanded', def: false }, START] },
    { id: 'movies', label: 'Movies', icon: '◉', movies: true }
  ];

  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }
  function tabSpec(id) { return TABS.filter(function (t) { return t.id === id; })[0]; }
  function store() { try { return root.localStorage || null; } catch (e) { return null; } }
  function getItem(k) { var s = store(); try { return s ? s.getItem(k) : null; } catch (e) { return null; } }
  function setItem(k, v) { var s = store(); try { if (s) s.setItem(k, v); return !!s; } catch (e) { return false; } }
  function lang() { return (root.navigator && root.navigator.language) || 'en-US'; }

  function normalizeTab(id, raw) {
    var spec = tabSpec(id), out = {};
    if (!spec || spec.movies) return out;
    raw = raw && typeof raw === 'object' ? raw : {};
    spec.fields.forEach(function (f) {
      var v = raw[f.key];
      if (f.type === 'bool') out[f.key] = typeof v === 'boolean' ? v : f.def;
      else if (f.type === 'select') out[f.key] = f.options.some(function (o) { return o[0] === v; }) ? v : f.def;
      else out[f.key] = typeof v === 'string' ? v.trim() : f.def;
    });
    if (out.search && !/^https?:\/\//i.test(out.search)) out.search = '';
    if (out.profile_url && !/^https?:\/\//i.test(out.profile_url)) out.profile_url = '';
    return out;
  }
  function loadTab(id) {
    if (id === 'movies') return L.normalizeMovieConfig(getItem(MOVIE_KEY) || '{}', lang());
    var raw = {}; try { raw = JSON.parse(getItem('cmc_cfg_' + id) || '{}'); } catch (e) { raw = {}; }
    return normalizeTab(id, raw);
  }
  function saveTab(id, cfg) {
    if (id === 'movies') return setItem(MOVIE_KEY, JSON.stringify(L.normalizeMovieConfig(cfg, lang())));
    return setItem('cmc_cfg_' + id, JSON.stringify(normalizeTab(id, cfg)));
  }
  function loadUsers() { return L.parseUserSources(getItem(USER_KEY) || '[]'); }
  function saveUsers(list) { return setItem(USER_KEY, JSON.stringify(list)); }

  /* ── pure renderers ──────────────────────────────────────────────────── */
  function menuHtml(prefix) {
    return '<button class="cfgbtn" type="button" aria-haspopup="menu" aria-expanded="false" aria-label="Settings" data-cfgbtn>⚙</button>' +
      '<div class="cfgmenu" role="menu" hidden>' +
      '<a role="menuitem" href="' + prefix + 'profile/index.html" data-menu="profile">👤 Profile</a>' +
      '<a role="menuitem" href="' + prefix + 'configs/index.html" data-menu="configs">⚙ Configs</a></div>';
  }
  function field(f, v, tab) {
    var a = ' data-tab="' + tab + '" data-key="' + esc(f.key) + '" data-type="' + f.type + '"';
    if (f.type === 'bool') return '<label class="cfg__row"><input type="checkbox"' + a + (v ? ' checked' : '') + '><span>' + esc(f.label) + '</span></label>';
    if (f.type === 'select') {
      var opts = f.options === 'buffer' ? L.streamBufferModes().map(function (b) { return [b.id, b.label]; }) : f.options;
      return '<label>' + esc(f.label) + '<select' + a + '>' + opts.map(function (o) { return '<option value="' + esc(o[0]) + '"' + (o[0] === v ? ' selected' : '') + '>' + esc(o[1]) + '</option>'; }).join('') + '</select></label>';
    }
    var type = f.type === 'secret' ? 'password' : f.type === 'number' ? 'number' : 'text';
    return '<label>' + esc(f.label) + '<input' + a + ' type="' + type + '" value="' + esc(v) + '"' + (f.placeholder ? ' placeholder="' + esc(f.placeholder) + '"' : '') +
      (f.min != null ? ' min="' + f.min + '"' : '') + (f.max != null ? ' max="' + f.max + '"' : '') + (f.step ? ' step="' + f.step + '"' : '') +
      (f.type === 'region' ? ' maxlength="2"' : '') + ' autocomplete="off"></label>';
  }
  function sourceToggles(decl, cfg, torrent) {
    return (decl ? decl.sources : []).filter(function (s) { return (s.kind === 'torrent') === torrent; }).map(function (s) {
      return '<label class="cfg__row"><input type="checkbox" data-src-on="' + esc(s.id) + '"' + (L.isSourceEnabled(cfg, s.id) ? ' checked' : '') + '><span>' + esc(s.name) + '</span></label>';
    }).join('');
  }
  function userRows(users) {
    return users.map(function (u) {
      return '<div class="cfg__urow" data-user="' + esc(u.id) + '"><input type="checkbox" data-uon="' + esc(u.id) + '"' + (u.enabled ? ' checked' : '') + ' aria-label="Enabled">' +
        '<input data-uname="' + esc(u.id) + '" value="' + esc(u.name) + '" aria-label="Name"><input data-uurl="' + esc(u.id) + '" value="' + esc(u.url) + '" aria-label="URL">' +
        '<small>' + esc(u.type) + '</small><button type="button" class="cfg__icon" data-udel="' + esc(u.id) + '" aria-label="Delete">🗑</button></div>';
    }).join('');
  }
  function renderMovies(cfg, decl, users) {
    return L.MOVIE_CONFIG.map(function (sec) {
      var html = '<section class="cfg__sec" data-msec="' + sec.id + '"><h3>' + esc(sec.label) + '</h3>';
      if (sec.id === 'sources') html += '<h4>Sources on / off</h4>' + sourceToggles(decl, cfg, false);
      if (sec.id === 'torrent') html += '<h4>Built-in legal sources</h4>' + sourceToggles(decl, cfg, true);
      html += sec.fields.map(function (f) { return field(f, L.getPath(cfg, f.key), 'movies'); }).join('');
      if (sec.id === 'torrent') {
        html += '<h4>Your torrent sources</h4><p class="cfg__note">Added by you, kept on this phone only. Nothing is preset. Only use sources you have the right to download from.</p>' +
          '<div class="cfg__ulist">' + userRows(users) + '</div>' +
          '<div class="cfg__new"><label>Name<input data-unew="name"></label><label>Type<select data-unew="type"><option value="torznab">Torznab / Jackett</option><option value="rss">RSS feed</option><option value="template">Search URL with {query}</option></select></label>' +
          '<label>URL<input data-unew="url" placeholder="https://…"></label><label>API key (Torznab)<input data-unew="apikey" type="password" autocomplete="off"></label>' +
          '<button type="button" class="btn btn--accent" data-uadd>Add source</button><div class="cfg__umsg" role="status"></div></div>';
      }
      return html + '</section>';
    }).join('');
  }
  // tab: one of TABS' ids; cfg: that tab's values; decl/users only for movies.
  function renderConfigs(tab, cfg, decl, users) {
    var spec = tabSpec(tab) || TABS[0];
    var body = spec.movies ? renderMovies(cfg, decl, users || []) :
      '<section class="cfg__sec" data-tsec="' + spec.id + '">' + spec.fields.map(function (f) { return field(f, cfg[f.key], spec.id); }).join('') + '</section>';
    return '<div class="cfg" data-cfg="' + spec.id + '"><p class="cfg__note">Saved on this phone as you change it. Nothing is sent anywhere except the servers you enter.</p>' + body + '</div>';
  }
  function renderProfile(music, gallery) {
    var rows = [];
    if (music && music.profile) rows.push(['TIDAL', music.profile.username + ' · ' + music.profile.playlists + ' playlists · ' + music.profile.tracks + ' tracks']);
    if (gallery && gallery.profile_url) rows.push(['Pinterest', '<a href="' + esc(gallery.profile_url) + '" data-ext>' + esc(gallery.profile_url.replace(/^https?:\/\//, '')) + ' ↗</a>']);
    return '<div class="cfg"><section class="cfg__sec"><h3>Fleet account</h3><p class="cfg__note">Your account, devices and fleet sign-in are managed in cloud-account.</p>' +
      '<a class="btn btn--accent" href="' + ACCOUNT_INTENT + '" data-account>👤 Open cloud-account</a><div class="cfg__umsg" data-account-msg role="status"></div></section>' +
      '<section class="cfg__sec"><h3>Linked media profiles</h3>' + (rows.length ? rows.map(function (r) { return '<div class="cfg__kv"><b>' + esc(r[0]) + '</b><span>' + (r[0] === 'TIDAL' ? esc(r[1]) : r[1]) + '</span></div>'; }).join('') : '<p class="cfg__note">None synced.</p>') + '</section></div>';
  }

  /* ── browser half ────────────────────────────────────────────────────── */
  function openAccount(msg) {
    var t = root.__TAURI__;
    var fail = function () { if (msg) msg.textContent = 'Could not open cloud-account. Is it installed? Open it from the launcher.'; };
    if (t && t.opener && t.opener.openUrl) t.opener.openUrl(ACCOUNT_INTENT).catch(fail); else root.location.href = ACCOUNT_INTENT;
  }
  function wireMenu(doc, prefix) {
    var row = doc.querySelector('.top__row'); if (!row || row.querySelector('[data-cfgbtn]')) return;
    var box = doc.createElement('div'); box.className = 'cfgwrap'; box.innerHTML = menuHtml(prefix); row.appendChild(box);
    var btn = box.querySelector('[data-cfgbtn]'), menu = box.querySelector('.cfgmenu');
    function set(open) { menu.hidden = !open; btn.setAttribute('aria-expanded', String(open)); }
    btn.addEventListener('click', function (e) { e.stopPropagation(); set(menu.hidden); });
    doc.addEventListener('click', function (e) { if (!box.contains(e.target)) set(false); });
  }
  function bootConfigs(doc) {
    var main = doc.querySelector('main'), chips = doc.querySelector('.chips');
    var hash = (root.location.hash || '').slice(1), tab = tabSpec(hash) ? hash : 'music', decl = null, users = loadUsers();
    chips.innerHTML = TABS.map(function (t) { return '<button class="chip" data-chip="' + t.id + '" aria-pressed="' + (t.id === tab) + '">' + t.icon + ' ' + esc(t.label) + '</button>'; }).join('');
    function draw() {
      main.innerHTML = renderConfigs(tab, loadTab(tab), decl, users);
      [].forEach.call(chips.children, function (c) { c.setAttribute('aria-pressed', String(c.getAttribute('data-chip') === tab)); });
    }
    chips.addEventListener('click', function (e) { var b = e.target.closest('.chip'); if (!b) return; tab = b.getAttribute('data-chip'); history.replaceState(null, '', '#' + tab); draw(); });
    function umsg(t) { var m = main.querySelector('.cfg__umsg'); if (m) m.textContent = t || ''; }
    function storeU(r) { if (r.error) { umsg(r.error); return false; } users = r.list; saveUsers(users); main.querySelector('.cfg__ulist').innerHTML = userRows(users); umsg(''); return true; }
    main.addEventListener('change', function (e) {
      var i = e.target, cfg;
      if (i.hasAttribute('data-key')) {
        cfg = loadTab(tab);
        var v = i.getAttribute('data-type') === 'bool' ? i.checked : i.getAttribute('data-type') === 'number' ? Number(i.value) : i.value;
        if (tab === 'movies') L.setPath(cfg, i.getAttribute('data-key'), v); else cfg[i.getAttribute('data-key')] = v;
        saveTab(tab, cfg); return;
      }
      if (i.hasAttribute('data-src-on')) { saveTab('movies', L.setSourceEnabled(loadTab('movies'), i.getAttribute('data-src-on'), i.checked)); return; }
      if (i.hasAttribute('data-uon')) storeU(L.updateUserSource(users, i.getAttribute('data-uon'), { enabled: i.checked }));
      if (i.hasAttribute('data-uname')) storeU(L.updateUserSource(users, i.getAttribute('data-uname'), { name: i.value }));
      if (i.hasAttribute('data-uurl')) storeU(L.updateUserSource(users, i.getAttribute('data-uurl'), { url: i.value }));
    });
    main.addEventListener('click', function (e) {
      var d = e.target.closest('[data-udel]');
      if (d) { storeU(L.removeUserSource(users, d.getAttribute('data-udel'))); return; }
      if (e.target.closest('[data-uadd]')) {
        var nw = {}; [].forEach.call(main.querySelectorAll('[data-unew]'), function (i) { nw[i.getAttribute('data-unew')] = i.value.trim(); });
        if (storeU(L.addUserSource(users, nw))) [].forEach.call(main.querySelectorAll('input[data-unew]'), function (i) { i.value = ''; });
      }
    });
    draw();
    fetch('../data/sources.json').then(function (r) { return r.json(); }).then(function (j) { decl = L.parseSources(j); if (tab === 'movies') draw(); }).catch(function () {});
  }
  function bootProfile(doc) {
    var main = doc.querySelector('main');
    var get = function (p) { return fetch('../data/' + p + '.json').then(function (r) { return r.json(); }).catch(function () { return null; }); };
    Promise.all([get('music'), get('gallery')]).then(function (r) { main.innerHTML = renderProfile(r[0], r[1]); });
    main.addEventListener('click', function (e) { if (e.target.closest('[data-account]')) { e.preventDefault(); openAccount(main.querySelector('[data-account-msg]')); } });
  }
  function boot() {
    var doc = root.document, page = doc.body.getAttribute('data-page');
    wireMenu(doc, page === 'home' ? '' : '../');
    if (page === 'configs') bootConfigs(doc);
    if (page === 'profile') bootProfile(doc);
    var size = tabSpec(page) && !tabSpec(page).movies ? loadTab(page).size : '';
    if (size) doc.body.setAttribute('data-size', size);
  }

  var api = { TABS: TABS, ACCOUNT_INTENT: ACCOUNT_INTENT, MOVIE_KEY: MOVIE_KEY, USER_KEY: USER_KEY, normalizeTab: normalizeTab, loadTab: loadTab, saveTab: saveTab,
    loadUsers: loadUsers, saveUsers: saveUsers, menuHtml: menuHtml, renderConfigs: renderConfigs, renderProfile: renderProfile, boot: boot };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else { root.CmcConfig = api; if (root.document) root.document.addEventListener('DOMContentLoaded', boot); }
})(typeof window !== 'undefined' ? window : this);
