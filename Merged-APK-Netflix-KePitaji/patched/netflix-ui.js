/* ============================================================
   NETFLIX APP UI — v1.9 (Netflix-style shell over the site)
   Offline-first: home feed comes from the bundled TMDB snapshot
   (nf-data/index.js) so the app works even if TMDB is blocked.
   ============================================================ */
(function () {
  "use strict";
  if (window.__NF_UI__) return;
  window.__NF_UI__ = true;

  var K = "b6b677eb7d4ec17f700e3d4dfc31d005";
  var API = "https://api.themoviedb.org/3";
  var IMG = "https://image.tmdb.org/t/p/";
  var corsProxy = "https://cors-anywhere.pulkitpareekofficial.workers.dev/?url=";
  var IMF = "https://v3.sg.media-imdb.com/suggestion/x/";
  var WPROXY = corsProxy;

  window.NF_NET = [];   // network diagnostics log

  /* ---- in-app data proxy: WebView networking can fail on some
     networks/VPNs; the app fetches these hosts itself. /p?u=<url> ---- */
  function px(u) {
    try {
      u = String(u || "");
      if (/^https?:\/\//i.test(u) && !/\/\/localhost/i.test(u)) {
        return "/p?u=" + encodeURIComponent(u);
      }
      return u;
    } catch (e) { return u; }
  }

  function nlog(what, ok, ms, err) {
    try {
      window.NF_NET.push({ what: what, ok: !!ok, ms: Math.round(ms || 0),
        err: err ? String(err).slice(0, 60) : "" });
      if (window.NF_NET.length > 30) window.NF_NET.shift();
    } catch (e) {}
  }

  /* a promise that always settles: rejects if not done within ms */
  function withTimeout(p, ms) {
    return new Promise(function (res, rej) {
      var done = false;
      var t = setTimeout(function () {
        if (!done) { done = true; rej(new Error("timeout " + ms + "ms")); }
      }, ms);
      p.then(function (v) { if (!done) { done = true; clearTimeout(t); res(v); } },
             function (e) { if (!done) { done = true; clearTimeout(t); rej(e); } });
    });
  }

  /* route EVERY fetch (ours + script.min.js TMDB calls) through the proxy */
  try {
    var _nfFetch = window.fetch;
    window.fetch = function (input, init) {
      try {
        var u = (typeof input === "string") ? input
          : (input && input.url) ? input.url : String(input || "");
        var p = px(u);
        if (p !== u) input = p;
      } catch (e) {}
      return _nfFetch(input, init);
    };
  } catch (e) {}

  /* image fallback chain: direct -> app proxy -> weserv mirror -> placeholder */
  document.addEventListener("error", function (e) {
    var t = e.target;
    if (!t || t.tagName !== "IMG") return;
    var s = t.getAttribute("src") || "";
    if (s.indexOf("data:") === 0) return;
    if (s.indexOf("/nf-img/") >= 0) { t.src = POSTER_PH_LOCAL; return; }
    if (s.indexOf("/p?u=") === 0) {
      try { s = decodeURIComponent(s.split("u=").slice(1).join("u=")); } catch (e2) {}
    }
    var st = +(t.getAttribute("data-nfp") || 0);
    var orig = t.getAttribute("data-orig") || s;
    if (!t.getAttribute("data-orig")) t.setAttribute("data-orig", orig);
    st++;
    t.setAttribute("data-nfp", String(st));
    if (st === 1) {
      t.src = px(orig);
    } else if (st === 2) {
      t.src = "/p?u=" + encodeURIComponent(
        "https://images.weserv.nl/?url=" + String(orig).replace(/^https?:\/\//, ""));
    } else {
      t.src = POSTER_PH_LOCAL;
    }
  }, true);

  var POSTER_PH_LOCAL = "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='71' height='101'><rect width='100%25' height='100%25' fill='%23161616'/><text x='50%25' y='58%25' font-size='30' font-weight='bold' fill='%23E50914' text-anchor='middle' font-family='Arial'>N</text></svg>";

  var NF = {
    tab: "home",            // home | search | my
    feed: "home",           // home | shows | movies | new
    playing: false,
    profile: null,
    myList: [],
    cont: []
  };
  window.__NF_state = NF;

  function ls(k, d) {
    try {
      var v = localStorage.getItem(k);
      return v === null ? d : v;
    } catch (e) { return d; }
  }
  function lss(k, v) {
    try { localStorage.setItem(k, v); } catch (e) {}
  }
  function lsj(k, d) {
    try { return JSON.parse(ls(k, d)) || d; } catch (e) { return d; }
  }
  NF.profile = ls("nf_profile", "Mian Amir");
  NF.myList = lsj("nf_mylist", []);
  NF.cont = lsj("nf_continue", []);

  function $(id) { return document.getElementById(id); }
  function el(tag, cls, html) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (html != null) e.innerHTML = html;
    return e;
  }
  function esc(s) {
    return String(s == null ? "" : s)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;")
      .replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }
  var POSTER_PH = "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='71' height='101'><rect width='100%25' height='100%25' fill='%23161616'/><text x='50%25' y='58%25' font-size='30' font-weight='bold' fill='%23E50914' text-anchor='middle' font-family='Arial'>N</text></svg>";
  function posterSrc(u) { return u ? px(u) : POSTER_PH; }
  function toast(msg) {
    var t = el("div", "", msg);
    t.style.cssText = "position:fixed;left:50%;bottom:90px;transform:translateX(-50%);" +
      "background:#222;color:#fff;padding:9px 16px;border-radius:6px;font-size:13px;z-index:999999;";
    document.body.appendChild(t);
    setTimeout(function () { t.remove(); }, 2600);
  }
  function j(url) {
    return fetch(url).then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status);
      return r.json();
    });
  }
  /* offline-first TMDB: bundled snapshot -> app proxy -> worker proxy */
  function tmdb(path, extra) {
    var full = API + "/" + path + "?api_key=" + K + "&language=en-US" + (extra || "");
    try {
      if (window.NF_BUNDLED && window.NF_BUNDLED[path]) {
        return Promise.resolve({ results: window.NF_BUNDLED[path] });
      }
    } catch (e) {}
    var t0 = Date.now();
    return withTimeout(j(px(full)), 9000).then(function (d) {
      nlog("tmdb " + path, true, Date.now() - t0);
      return d;
    }).catch(function (e1) {
      nlog("tmdb " + path, false, Date.now() - t0, e1 && e1.message);
      var t1 = Date.now();
      return withTimeout(j(px(WPROXY + encodeURIComponent(full))), 11000).then(function (d) {
        nlog("tmdb-w " + path, true, Date.now() - t1);
        return d;
      }).catch(function (e2) {
        nlog("tmdb-w " + path, false, Date.now() - t1, e2 && e2.message);
        throw e2;
      });
    });
  }

  /* ================= PROFILE HOOK (called from Java) ================= */
  window.__NF_setProfile = function (name) {
    NF.profile = name || "Mian Amir";
    lss("nf_profile", NF.profile);
    paintProfileBits();
  };
  window.__NF_onBack = function () {
    try { if (window.MB && window.MB.isPlaying && window.MB.isPlaying()) { window.MB.closePlayer(); return true; } } catch (eMB) { }
    if (NF.playing) { exitPlayer(); return true; }
    return false;
  };

  /* ================= BUILD SHELL ================= */
  var top, chipsRow, chipEls = {}, bottom, tabEls = {},
      vHome, vSearch, vMy, heroBox, rowsBox, searchInputNf,
      searchList, searchLabel, myListGrid, contList, contListMy,
      playerTitle, pdWrap, seasonBar, simBox;

  var SVG = {
    home: '<svg viewBox="0 0 24 24"><path d="M12 3 2 12h3v8h6v-6h2v6h6v-8h3L12 3z"/></svg>',
    search: '<svg viewBox="0 0 24 24"><path d="M15.5 14h-.8l-.3-.3a6.5 6.5 0 1 0-.7.7l.3.3v.8l5 5 1.5-1.5-5-5zm-6 0a4.5 4.5 0 1 1 0-9 4.5 4.5 0 0 1 0 9z"/></svg>',
    me: '<svg viewBox="0 0 24 24"><path d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm-3.5 7a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3zm7 0a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3zM12 17.5c-2.3 0-4.2-1.4-5-3.5h10c-.8 2.1-2.7 3.5-5 3.5z"/></svg>',
    play: '<svg viewBox="0 0 24 24"><path d="M6 4v16l14-8L6 4z"/></svg>',
    plus: '<svg viewBox="0 0 24 24"><path d="M11 5h2v6h6v2h-6v6h-2v-6H5v-2h6V5z"/></svg>',
    check: '<svg viewBox="0 0 24 24"><path d="M9 16.2 4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4L9 16.2z"/></svg>',
    swap: '<svg viewBox="0 0 24 24"><path d="M6.99 11 3 15l3.99 4v-3H14v-2H6.99v-3zM21 9l-3.99-4v3H10v2h7.01v3L21 9z"/></svg>'
  };

  function buildShell() {
    /* ---- top bar ---- */
    top = el("div");
    top.id = "nf-top";
    top.innerHTML =
      '<div class="nf-toprow">' +
        '<div id="nf-wordmark">NETFLIX</div>' +
        '<div id="nf-topavatar">M</div>' +
      "</div>" +
      '<div id="nf-chips"></div>';
    document.body.insertBefore(top, document.body.firstChild);

    ["home:Home", "shows:Shows", "movies:Movies", "new:New &amp; Hot"].forEach(function (p) {
      var kv = p.split(":");
      var c = el("div", "chip" + (kv[0] === "home" ? " on" : ""), kv[1]);
      c.dataset.feed = kv[0];
      c.addEventListener("click", function () {
        if (NF.playing) exitPlayer();
        NF.feed = kv[0];
        for (var k in chipEls) chipEls[k].classList.toggle("on", k === kv[0]);
        setTab("home");
        renderHome(true);
      });
      chipsRow = $("nf-chips");
      chipsRow.appendChild(c);
      chipEls[kv[0]] = c;
    });
    $("nf-topavatar").addEventListener("click", function () {
      if (NF.playing) exitPlayer();
      setTab("my");
    });

    /* ---- views ---- */
    vHome = el("div", "nf-view on");
    vHome.id = "nf-home";
    vHome.innerHTML = '<div id="nf-hero"></div><div id="nf-rows"></div>';
    vSearch = el("div", "nf-view");
    vSearch.id = "nf-search";
    vSearch.innerHTML =
      '<div id="nf-searchbar">' + SVG.search +
      '<input type="search" id="nf-q" placeholder="Search shows, movies, genres..." autocomplete="off"></div>' +
      '<div id="nf-searchlabel">Recommended TV Shows &amp; Movies</div>' +
      '<div class="nf-list" id="nf-searchlist"></div>';
    vMy = el("div", "nf-view");
    vMy.id = "nf-my";
    vMy.innerHTML =
      '<div class="nf-my-head">' +
        '<div class="nf-my-avatar" id="nf-my-av">M</div>' +
        '<div><div class="nf-my-name" id="nf-my-name"></div>' +
        '<div class="nf-my-sub">Netflix Clone &bull; v1.9</div></div>' +
      "</div>" +
      '<div class="nf-my-actions">' +
        '<div class="nf-my-btn" id="nf-switchp">Switch Profile</div>' +
        '<div class="nf-my-btn" id="nf-cleardata">Clear Continue</div>' +
        '<div class="nf-my-btn" id="nf-nettest">Test Network</div>' +
      "</div>" +
      '<div class="nf-rowhead">Network Status</div><div id="nf-net" class="nf-net"></div>' +
      '<div class="nf-rowhead">Continue Watching</div><div class="nf-list" id="nf-my-cont"></div>' +
      '<div class="nf-rowhead">My List</div><div class="nf-grid" id="nf-my-list"></div>';
    document.body.insertBefore(vMy, top.nextSibling);
    document.body.insertBefore(vHome, vMy);
    document.body.insertBefore(vSearch, vMy);

    /* ---- bottom nav ---- */
    bottom = el("div");
    bottom.id = "nf-bottom";
    [["home", "Home", SVG.home], ["search", "Search", SVG.search], ["my", "My Netflix", SVG.me]]
      .forEach(function (t) {
        var d = el("div", "tab" + (t[0] === "home" ? " on" : ""), t[2] + "<span>" + t[1] + "</span>");
        d.addEventListener("click", function () {
          if (NF.playing) exitPlayer();
          /* Home tab always returns to the mixed home feed */
          if (t[0] === "home" && NF.feed !== "home") {
            NF.feed = "home";
            for (var k in chipEls) chipEls[k].classList.toggle("on", k === "home");
            renderHome(true);
          }
          setTab(t[0]);
        });
        bottom.appendChild(d);
        tabEls[t[0]] = d;
      });
    document.body.appendChild(bottom);

    /* ---- player chrome ---- */
    var pc = el("div");
    pc.id = "nf-player-chrome";
    pc.innerHTML =
      '<div class="nf-pc-grad"></div>' +
      '<div id="nf-close">&#10005;</div>' +
      '<div id="nf-ptitle"></div>';
    document.body.appendChild(pc);
    $("nf-close").addEventListener("click", exitPlayer);

    pdWrap = el("div", "nf-pd");
    pdWrap.id = "nf-pd";
    var videoDiv = $("video");
    videoDiv.parentNode.insertBefore(pdWrap, videoDiv.nextSibling);

    $("nf-switchp").addEventListener("click", function () {
      try {
        if (window.NFApp && window.NFApp.showProfiles) { window.NFApp.showProfiles(); return; }
      } catch (e) {}
      toast("Profile switch app ko restart kar ke milta hai");
    });
    $("nf-cleardata").addEventListener("click", function () {
      NF.cont = [];
      lss("nf_continue", "[]");
      paintMyTab();
      toast("Continue Watching cleared");
    });
    $("nf-nettest").addEventListener("click", function () {
      toast("Network test chal raha hai...");
      var t0 = Date.now();
      fetch("https://v3.sg.media-imdb.com/suggestion/x/a.json")
        .then(function (r) { nlog("probe imdb-direct", r.ok, Date.now() - t0); })
        .catch(function (e) { nlog("probe imdb-direct", false, Date.now() - t0, e.message); })
        .then(function () {
          var t1 = Date.now();
          return fetch(px("https://api.themoviedb.org/3/configuration?api_key=" + K))
            .then(function (r) { nlog("probe tmdb-proxy", r.ok, Date.now() - t1); })
            .catch(function (e) { nlog("probe tmdb-proxy", false, Date.now() - t1, e.message); });
        })
        .then(function () {
          var t2 = Date.now();
          return fetch(px("https://image.tmdb.org/t/p/w92/9n2tJBplPbgR2ca05hS5CKXwP2c.jpg"))
            .then(function (r) { nlog("probe img-proxy", r.ok, Date.now() - t2); })
            .catch(function (e) { nlog("probe img-proxy", false, Date.now() - t2, e.message); });
        })
        .then(function () {
          var t3 = Date.now();
          return fetch(px(WPROXY + encodeURIComponent("https://v3.sg.media-imdb.com/suggestion/x/a.json")))
            .then(function (r) { nlog("probe worker", r.ok, Date.now() - t3); })
            .catch(function (e) { nlog("probe worker", false, Date.now() - t3, e.message); });
        })
        .then(function () { paintNet(); toast("Network test mukammal"); });
    });

    /* ---- search input ---- */
    var timer = null;
    searchInputNf = $("nf-q");
    searchInputNf.addEventListener("input", function () {
      clearTimeout(timer);
      timer = setTimeout(doSearch, 450);
    });

    /* ---- season bar ---- */
    seasonBar = el("div");
    seasonBar.id = "nf-seasons";
    seasonBar.style.display = "none";
    var wsd = $("webSeriesData");
    wsd.parentNode.insertBefore(seasonBar, wsd);

    paintProfileBits();
    paintMyTab();
  }

  function paintProfileBits() {
    var ini = (NF.profile || "M").trim().charAt(0).toUpperCase();
    $("nf-topavatar").textContent = ini;
    $("nf-my-av").textContent = ini;
    $("nf-my-name").textContent = NF.profile;
  }

  function setTab(t) {
    NF.tab = t;
    vHome.classList.toggle("on", t === "home");
    vSearch.classList.toggle("on", t === "search");
    vMy.classList.toggle("on", t === "my");
    for (var k in tabEls) tabEls[k].classList.toggle("on", k === t);
    if (t === "search" && !searchList.childElementCount) recommendedList();
    if (t === "my") paintMyTab();
    window.scrollTo(0, 0);
  }

  /* ================= HOME FEED ================= */
  var FEEDS = {
    home: [
      { label: "Top 10 Movies &amp; Shows Today", path: "trending/all/week", top10: true },
      { label: "Popular Movies", path: "movie/popular" },
      { label: "Popular TV Shows", path: "tv/popular" },
      { label: "Top Rated Movies", path: "movie/top_rated" },
      { label: "New on Netflix", path: "movie/upcoming" }
    ],
    shows: [
      { label: "Popular TV Shows", path: "tv/popular" },
      { label: "Top Rated Shows", path: "tv/top_rated" },
      { label: "On The Air", path: "tv/on_the_air" },
      { label: "Action &amp; Adventure", path: "discover/tv?with_genres=10759" },
      { label: "Comedies", path: "discover/tv?with_genres=35" },
      { label: "Dramas", path: "discover/tv?with_genres=18" },
      { label: "Sci-Fi &amp; Fantasy", path: "discover/tv?with_genres=10765" },
      { label: "Mystery &amp; Thriller", path: "discover/tv?with_genres=9648" }
    ],
    movies: [
      { label: "Popular Movies", path: "movie/popular" },
      { label: "Top Rated Movies", path: "movie/top_rated" },
      { label: "Now Playing", path: "movie/now_playing" },
      { label: "Action", path: "discover/movie?with_genres=28" },
      { label: "Comedy", path: "discover/movie?with_genres=35" },
      { label: "Horror", path: "discover/movie?with_genres=27" },
      { label: "Sci-Fi", path: "discover/movie?with_genres=878" },
      { label: "Thriller", path: "discover/movie?with_genres=53" }
    ],
    "new": [
      { label: "Coming Soon", path: "movie/upcoming" },
      { label: "New TV Seasons", path: "discover/tv?sort_by=first_air_date.desc" },
      { label: "New Movies", path: "discover/movie?sort_by=primary_release_date.desc" },
      { label: "On The Air", path: "tv/on_the_air" }
    ]
  };

  function rowItems(path) {
    return tmdb(path).then(function (d) {
      var arr = (d.results || []).filter(function (r) {
        if (!r.poster_path) return false;
        if (path.indexOf("trending/") === 0 || path.indexOf("discover") === -1 && path.indexOf("/") === -1) return true;
        return true;
      });
      return arr.filter(function (r) {
        if (r.media_type && r.media_type !== "movie" && r.media_type !== "tv") return false;
        return true;
      });
    });
  }

  /* bundled item -> poster url (offline first, then proxied) */
  function posterUrl(m, size) {
    if (m && m._local) return m._local;
    if (m && m.poster_path) return px(IMG + (size || "w342") + m.poster_path);
    return POSTER_PH_LOCAL;
  }

  function renderHome(fresh) {
    rowsBox = $("nf-rows");
    if (fresh) rowsBox.innerHTML = "";
    if (NF.feed !== "home") $("nf-hero").style.display = "none";
    else renderHero();

    var contRow = null;
    if (NF.feed === "home" && NF.cont.length) {
      contRow = el("div", "nf-fade");
      contRow.appendChild(rowHead("Continue Watching for " + esc(NF.profile)));
      var rr = el("div", "nf-row");
      NF.cont.slice(0, 12).forEach(function (w) {
        var c = el("div", "nf-card");
        var src = posterSrc(w.poster);
        c.innerHTML = "<img src=\"" + esc(src) + "\" alt=\"\">" +
          "<div class=\"nf-card-title\">" + esc(w.title) +
          (w.type === "tv" && w.episode ? " &bull; E" + w.episode : "") + "</div>";
        c.addEventListener("click", function () { resumeWatch(w); });
        rr.appendChild(c);
      });
      contRow.appendChild(rr);
      rowsBox.appendChild(contRow);
    }

    FEEDS[NF.feed].forEach(function (spec) {
      var wrap = el("div", "nf-fade");
      wrap.appendChild(rowHead(spec.label, spec.top10));
      var rr = el("div", "nf-row");
      wrap.appendChild(rr);
      rowsBox.appendChild(wrap);
      rowItems(spec.path).then(function (items) {
        var list = items.slice(0, spec.top10 ? 10 : 14);
        if (!list.length) return;
        list.forEach(function (m, i) {
          var media = m.media_type || (spec.path.indexOf("tv") === 0 ? "tv" : "movie");
          var c;
          if (spec.top10) {
            c = el("div", "nf-card t10");
            c.innerHTML = "<div class=\"nf-rank\">" + (i + 1) + "</div>" +
              "<img loading=\"lazy\" src=\"" + posterUrl(m) + "\" alt=\"\">";
          } else {
            c = el("div", "nf-card");
            c.innerHTML = "<img loading=\"lazy\" src=\"" + posterUrl(m) + "\" alt=\"\">" +
              "<div class=\"nf-card-title\">" + esc(m.title || m.name) + "</div>";
          }
          c.addEventListener("click", function () {
            playTmdb(m.id, media, m.title || m.name, posterUrl(m), m.imdb_id);
          });
          rr.appendChild(c);
        });
      }).catch(function () {});
    });
    /* MovieBox rows (additive layer) */
    try { window.MB && window.MB.renderHomeRows && window.MB.renderHomeRows(rowsBox); } catch (eMB) { }
  }

  function rowHead(label, top10) {
    var h = el("div", "nf-rowhead");
    h.innerHTML = (top10 ? "<span style=\"color:#e50914\">TOP 10</span>" : "") +
      "<span>" + label + "</span><span class=\"nf-arrow\">&#9654;</span>";
    return h;
  }

  function renderHero() {
    var hero = $("nf-hero");
    hero.style.display = "block";
    if (hero.dataset.done === "1") return;
    hero.dataset.done = "1";
    tmdb("trending/all/week").then(function (d) {
      var h = (d.results || []).filter(function (r) {
        return r.backdrop_path && (r.media_type === "movie" || r.media_type === "tv");
      })[0];
      if (!h) { hero.style.minHeight = "0"; return; }
      hero.style.backgroundImage = h._blocal
        ? "url(" + h._blocal + ")"
        : "url(" + px(IMG + "w780" + h.backdrop_path) + ")";
      var title = h.title || h.name;
      hero.innerHTML =
        "<div class=\"nf-hero-meta\">" +
          "<div class=\"nf-hero-tag\">" + (h.media_type === "tv" ? "Show" : "Movie") +
          " &bull; Trending #1 Today</div>" +
          "<div class=\"nf-hero-title\">" + esc(title) + "</div>" +
          "<div class=\"nf-hero-btns\">" +
            "<button class=\"nf-btn play\">" + SVG.play + " Play</button>" +
            "<button class=\"nf-btn list\">" + SVG.plus + " My List</button>" +
          "</div>" +
        "</div>";
      hero.querySelector(".play").addEventListener("click", function () {
        playTmdb(h.id, h.media_type, title, posterUrl(h), h.imdb_id);
      });
      hero.querySelector(".list").addEventListener("click", function () {
        toggleList({ id: h.id, media: h.media_type, title: title,
          poster: posterUrl(h), imdb: h.imdb_id || null });
      });
    }).catch(function () { hero.style.minHeight = "0"; });
  }

  /* ================= PLAY ================= */
  function playTmdb(tmdbId, media, title, poster, imdb) {
    toast("Loading " + title + " ...");
    if (imdb) { startStream(imdb, media, title, poster); return; }
    tmdb(media + "/" + tmdbId).then(function (d) {
      var imdb2 = d.imdb_id;
      if (!imdb2) { toast("Streams not available for this title"); return; }
      startStream(imdb2, media, title, poster);
    }).catch(function () { toast("Network error, try again"); });
  }
  function startStream(imdb, media, title, poster) {
    var a = document.createElement("a");
    if (media === "movie") {
      a.setAttribute("url", "imdb=" + imdb + "&type=movie&title=" + String(title).replace(/ /g, "_"));
      a.setAttribute("isWebSeries", "false");
      a.setAttribute("href", "https://vidsrc.su/movie/" + imdb);
    } else {
      a.setAttribute("url", "imdb=" + imdb + "&season=1&episode=1&title=" + String(title).replace(/ /g, "_"));
      a.setAttribute("isWebSeries", "true");
      a.setAttribute("href", "https://vidsrc.su/tv/" + imdb + "/1/1");
    }
    a.setAttribute("IMDB", imdb);
    a.setAttribute("title", title || "");
    a.setAttribute("class", "links");
    a.setAttribute("data-poster", poster || "");
    a.setAttribute("data-media", media);
    window.setVideo(a);
  }

  function resumeWatch(w) {
    if (!w.imdb) return;
    var a = document.createElement("a");
    if (w.type === "tv") {
      a.setAttribute("url", "imdb=" + w.imdb + "&season=" + (w.season || 1) + "&episode=" + (w.episode || 1));
      a.setAttribute("isWebSeries", "true");
      a.setAttribute("href", "https://vidsrc.su/tv/" + w.imdb + "/" + (w.season || 1) + "/" + (w.episode || 1));
    } else {
      a.setAttribute("url", "imdb=" + w.imdb + "&type=movie&title=" + String(w.title).replace(/ /g, "_"));
      a.setAttribute("isWebSeries", "false");
      a.setAttribute("href", "https://vidsrc.su/movie/" + w.imdb);
    }
    a.setAttribute("IMDB", w.imdb);
    a.setAttribute("title", w.title || "");
    a.setAttribute("class", "links");
    a.setAttribute("data-poster", w.poster || "");
    a.setAttribute("data-media", w.type);
    window.setVideo(a);
  }

  /* wrap site setVideo: track continue-watching + enter player chrome */
  var _setVideo = window.setVideo;
  window.setVideo = function (elm) {
    var r = _setVideo(elm);
    try { onPlay(elm); } catch (e) {}
    return r;
  };

  function onPlay(elm) {
    NF.playing = true;
    document.body.classList.add("nf-playing");
    /* hide all tab views: player + detail page take over the screen */
    var views = document.querySelectorAll(".nf-view");
    for (var i = 0; i < views.length; i++) {
      views[i].classList.remove("on");
    }
    window.scrollTo(0, 0);

    var title = elm.getAttribute("title") || "";
    var imdb = elm.getAttribute("IMDB") || "";
    if (!imdb) {
      var mu = (elm.getAttribute("url") || "").match(/imdb=([^&]+)/);
      if (mu) imdb = mu[1];
      try { elm.setAttribute("IMDB", imdb); } catch (e2) {}
    }
    var href = elm.getAttribute("href") || "";
    var isTv = elm.getAttribute("isWebSeries") === "true" || /\/tv\//.test(href);
    var season = 1, episode = 1;
    var mh = href.match(/\/tv\/[^\/]+\/(\d+)\/(\d+)/);
    if (mh) { season = +mh[1]; episode = +mh[2]; }
    var poster = elm.getAttribute("data-poster") ||
      (elm.querySelector("img") ? elm.querySelector("img").getAttribute("src") : "");
    if (!poster) {
      for (var pi = 0; pi < NF.cont.length; pi++) {
        if (NF.cont[pi].imdb === imdb && NF.cont[pi].poster) { poster = NF.cont[pi].poster; break; }
      }
    }

    playerTitle.textContent = title;
    pdWrap.innerHTML =
      "<div class=\"nf-pd-title\">" + esc(title) + "</div>" +
      "<div class=\"nf-pd-meta\"><b>" + (isTv ? "E" + episode + " &bull; S" + season : "HD") + "</b>" +
      " &bull; " + (isTv ? "TV Show" : "Movie") +
      "</div>" +
      "<div class=\"nf-pd-btns\">" +
        "<div class=\"nf-pd-btn red\" id=\"nf-pd-list\">" + SVG.plus + " My List</div>" +
        "<div class=\"nf-pd-btn\" id=\"nf-pd-server\">" + SVG.swap + " Change Server</div>" +
      "</div>";

    $("nf-pd-list").addEventListener("click", function () {
      toggleList({ id: imdb, media: isTv ? "tv" : "movie", title: title, poster: poster, imdb: imdb });
      this.innerHTML = inList(imdb) ? SVG.check + " In My List" : SVG.plus + " My List";
    });
    $("nf-pd-server").addEventListener("click", function () {
      var b = $("pv-switch");
      if (b) b.click();
      else toast("Server switch unavailable");
    });

    /* Netflix-style "More Like This" grid below the player */
    try { renderSimilar(imdb, isTv); } catch (eSim) {}

    /* record continue watching */
    var item = { imdb: imdb, title: title, type: isTv ? "tv" : "movie",
      season: season, episode: episode, poster: poster, ts: Date.now() };
    NF.cont = NF.cont.filter(function (w) { return w.imdb !== imdb; });
    NF.cont.unshift(item);
    NF.cont = NF.cont.slice(0, 12);
    lss("nf_continue", JSON.stringify(NF.cont));
    paintMyTab();
  }

  function exitPlayer() {
    try {
      var f = $("iframe");
      if (f) f.src = "";
    } catch (e) {}
    try {
      $("video").style.display = "none";
    } catch (e) {}
    NF.playing = false;
    document.body.classList.remove("nf-playing");
    setTab("home");
  }

  /* ================= MORE LIKE THIS (below player) ================= */
  function renderSimilar(imdb, isTv) {
    if (!simBox) {
      simBox = el("div");
      simBox.id = "nf-similar";
      pdWrap.parentNode.insertBefore(simBox, pdWrap.nextSibling);
    }
    if (!imdb) { simBox.innerHTML = ""; return; }
    simBox.innerHTML = '<div class="nf-sim-head">More Like This</div>' +
      '<div class="nf-spin" style="margin:22px auto;"></div>';
    var mtype = isTv ? "tv" : "movie";
    tmdb("find/" + encodeURIComponent(imdb), "&external_source=imdb_id").then(function (d) {
      var hit = ((d.movie_results || [])[0]) || ((d.tv_results || [])[0]);
      if (!hit) { simBox.innerHTML = ""; return; }
      return fetchSimilarRows(mtype, hit.id, 1).then(function (items) {
        paintSimilar(items, mtype);
      });
    }).catch(function () {
      try { simBox.innerHTML = ""; } catch (e) {}
    });
  }
  function fetchSimilarRows(mtype, mid, pass) {
    var ep = pass === 1 ? "recommendations" : "similar";
    return tmdb(mtype + "/" + mid + "/" + ep).then(function (d) {
      var items = (d.results || []).filter(function (r) { return r.poster_path; }).slice(0, 12);
      if (!items.length && pass === 1) return fetchSimilarRows(mtype, mid, 2);
      return items;
    });
  }
  function paintSimilar(items, mtype) {
    if (!simBox) return;
    if (!items || !items.length) { simBox.innerHTML = ""; return; }
    var grid = el("div", "nf-sim-grid");
    items.forEach(function (r) {
      var t = r.title || r.name || "";
      var y = String(r.release_date || r.first_air_date || "").slice(0, 4);
      var c = el("div", "nf-sim-card");
      c.innerHTML =
        '<img loading="lazy" src="' + esc(posterSrc(IMG + "w342" + r.poster_path)) + '" alt="">' +
        '<div class="nf-sim-title">' + esc(t) + "</div>" +
        '<div class="nf-sim-meta">' + (y ? y + " &bull; " : "") +
        (mtype === "tv" ? "TV Show" : "Movie") + "</div>";
      c.addEventListener("click", function () {
        playTmdb(r.id, mtype, t, px(IMG + "w342" + r.poster_path), null);
      });
      grid.appendChild(c);
    });
    simBox.innerHTML = '<div class="nf-sim-head">More Like This</div>';
    simBox.appendChild(grid);
  }

  /* ================= MY LIST ================= */
  function inList(id) {
    return NF.myList.some(function (x) { return x.id === id; });
  }
  function toggleList(item) {
    if (inList(item.id)) {
      NF.myList = NF.myList.filter(function (x) { return x.id !== item.id; });
    } else {
      NF.myList.unshift(item);
      toast("Added to My List");
    }
    NF.myList = NF.myList.slice(0, 40);
    lss("nf_mylist", JSON.stringify(NF.myList));
    paintMyTab();
  }

  /* ================= SEARCH ================= */
  function doSearch() {
    var q = searchInputNf.value.trim();
    if (!q) { recommendedList(); return; }
    $("nf-searchlabel").textContent = "Results for \u201C" + q + "\u201D";
    var box = $("nf-searchlist");
    box.innerHTML = '<div class="nf-spin"></div>';
    var imdbUrl = IMF + encodeURIComponent(q) + ".json";
    var tries = [imdbUrl, px(imdbUrl), px(WPROXY + encodeURIComponent(imdbUrl))];
    var ti = 0;
    var javaTried = false;
    function tmdbFallback() {
      /* last resort: TMDB multi search (bundled/chain aware) */
      tmdb("search/multi", "&query=" + encodeURIComponent(q)).then(function (d) {
        var items = (d.results || []).filter(function (r) {
          return (r.media_type === "movie" || r.media_type === "tv") && r.poster_path;
        }).map(function (r) {
          return { l: r.title || r.name, qid: r.media_type === "tv" ? "tvSeries" : "movie",
            y: r.release_date || r.first_air_date,
            imgTmdb: r._local || (IMG + "w342" + r.poster_path),
            tmdbId: r.id, media: r.media_type, imdb: r.imdb_id };
        });
        if (items.length) paintSearchTmdb(items, box);
        else box.innerHTML = '<div class="nf-empty">No titles found.</div>';
      }).catch(function (e) {
        nlog("search tmdb", false, 0, e && e.message);
        box.innerHTML = '<div class="nf-empty">Internet se connect nahi ho pa raha.<br>My Netflix \u2192 Network Status mein wajah dekho.</div>';
      });
    }
    function javaFallback() {
      /* app-side search: Java fetches IMDb/TMDB itself, independent of the
         WebView network stack — works whenever normal fetch() cannot */
      if (!javaTried && window.NFApp && window.NFApp.search) {
        javaTried = true;
        var cbId = "s" + Date.now();
        var tsj = Date.now();
        window.__NF_javaSearch = function (id, payload) {
          if (String(id) !== cbId) return;
          nlog("search java", !!payload, Date.now() - tsj);
          try {
            if (payload) {
              var r = (typeof payload === "string") ? JSON.parse(payload) : payload;
              if (r && r.src === "imdb") {
                var d = (typeof r.body === "string") ? JSON.parse(r.body) : r.body;
                var items = ((d && d.d) || []).filter(function (r2) {
                  return r2.i && (r2.qid === "movie" || r2.qid === "tvSeries");
                });
                if (items.length) { paintSearch(items, box); return; }
              } else if (r && r.src === "tmdb") {
                var d2 = (typeof r.body === "string") ? JSON.parse(r.body) : r.body;
                var items2 = (d2.results || []).filter(function (r3) {
                  return (r3.media_type === "movie" || r3.media_type === "tv") && r3.poster_path;
                }).map(function (r3) {
                  return { l: r3.title || r3.name, qid: r3.media_type === "tv" ? "tvSeries" : "movie",
                    y: r3.release_date || r3.first_air_date,
                    imgTmdb: r3._local || (IMG + "w342" + r3.poster_path),
                    tmdbId: r3.id, media: r3.media_type, imdb: r3.imdb_id };
                });
                if (items2.length) { paintSearchTmdb(items2, box); return; }
              }
            }
          } catch (e) { nlog("search java parse", false, 0, e && e.message); }
          tmdbFallback();
        };
        try { window.NFApp.search(q, cbId); } catch (e) { tmdbFallback(); }
        return;
      }
      tmdbFallback();
    }
    function nextTry() {
      if (ti >= tries.length) {
        javaFallback();
        return;
      }
      var name = ti === 0 ? "imdb" : (ti === 1 ? "imdb-proxy" : "worker");
      var url = tries[ti];
      ti++;
      var ts = Date.now();
      withTimeout(j(url), 8000).then(function (d) {
        nlog("search " + name, true, Date.now() - ts);
        var items = ((d && d.d) || []).filter(function (r) {
          return r.i && (r.qid === "movie" || r.qid === "tvSeries");
        });
        if (items.length) paintSearch(items, box);
        else nextTry();
      }).catch(function (e) {
        nlog("search " + name, false, Date.now() - ts, e && e.message);
        nextTry();
      });
    }
    nextTry();
    /* MovieBox results (additive layer) */
    try { window.MB && window.MB.searchAppend && window.MB.searchAppend(q, box); } catch (eMB2) { }
  }

  function paintSearch(items, box) {
    box.innerHTML = "";
    var shown = 0;
    items.forEach(function (r) {
      if (!r.i || (r.qid !== "movie" && r.qid !== "tvSeries")) return;
      shown++;
      var li = el("div", "nf-listitem");
      li.innerHTML =
        "<img loading=\"lazy\" src=\"" + esc(px(optim(r.i.imageUrl))) + "\" alt=\"\">" +
        "<div class=\"nf-li-text\">" +
          "<div class=\"nf-li-title\">" + esc(r.l) + "</div>" +
          "<div class=\"nf-li-sub\">" + (r.y ? r.y + " &bull; " : "") +
          (r.qid === "tvSeries" ? "TV Show" : "Movie") +
          (r.s ? " &bull; " + esc(r.s) : "") + "</div>" +
        "</div>" + SVG.play.replace("<svg", "<svg class=\"nf-li-play\"");
      li.addEventListener("click", function () {
        var a = document.createElement("a");
        var title = r.l;
        if (r.qid === "movie") {
          a.setAttribute("url", "imdb=" + r.id + "&type=movie&title=" + title.replace(/ /g, "_"));
          a.setAttribute("isWebSeries", "false");
          a.setAttribute("href", "https://vidsrc.su/movie/" + r.id);
        } else {
          a.setAttribute("url", "imdb=" + r.id + "&season=1&episode=1");
          a.setAttribute("isWebSeries", "true");
          a.setAttribute("href", "https://vidsrc.su/tv/" + r.id + "/1/1");
        }
        a.setAttribute("IMDB", r.id);
        a.setAttribute("title", title);
        a.setAttribute("class", "links");
        a.setAttribute("data-poster", optim(r.i.imageUrl));
        window.setVideo(a);
      });
      box.appendChild(li);
    });
    if (!shown) box.innerHTML = '<div class="nf-empty">No titles found for this search.</div>';
  }

  function paintSearchTmdb(items, box) {
    box.innerHTML = "";
    items.slice(0, 24).forEach(function (r) {
      var li = el("div", "nf-listitem");
      li.innerHTML =
        "<img loading=\"lazy\" src=\"" + esc(px(r.imgTmdb)) + "\" alt=\"\">" +
        "<div class=\"nf-li-text\">" +
          "<div class=\"nf-li-title\">" + esc(r.l) + "</div>" +
          "<div class=\"nf-li-sub\">" + (r.y ? String(r.y).slice(0, 4) + " &bull; " : "") +
          (r.qid === "tvSeries" ? "TV Show" : "Movie") + "</div>" +
        "</div>" + SVG.play.replace("<svg", "<svg class=\"nf-li-play\"");
      li.addEventListener("click", function () {
        playTmdb(r.tmdbId, r.media, r.l, r.imgTmdb, r.imdb);
      });
      box.appendChild(li);
    });
    if (!box.childElementCount) box.innerHTML = '<div class="nf-empty">No titles found.</div>';
  }

  function recommendedList() {
    $("nf-searchlabel").textContent = "Recommended TV Shows & Movies";
    var box = $("nf-searchlist");
    box.innerHTML = '<div class="nf-spin"></div>';
    tmdb("trending/all/week").then(function (d) {
      var items = (d.results || []).filter(function (r) {
        return r.poster_path && (r.media_type === "movie" || r.media_type === "tv");
      }).map(function (r) {
        return { l: r.title || r.name, qid: r.media_type === "tv" ? "tvSeries" : "movie",
          y: r.release_date || r.first_air_date,
          imgTmdb: r._local || (IMG + "w342" + r.poster_path), tmdbId: r.id, media: r.media_type, imdb: r.imdb_id };
      });
      paintSearchTmdb(items, box);
    }).catch(function () {
      box.innerHTML = '<div class="nf-empty">Network error. Check internet and try again.</div>';
    });
  }

  function optim(u) {
    return String(u || "").replace("._V1_.", "._V1_QL75_UX160_.");
  }

  /* ================= MY TAB ================= */
  function paintNet() {
    var n = $("nf-net");
    if (!n) return;
    var rows = window.NF_NET.slice(-8).reverse().map(function (r) {
      return '<div class="nf-net-row"><span class="' + (r.ok ? "ok" : "bad") + "\">" +
        (r.ok ? "\u2713" : "\u2717") + "</span> " + esc(r.what) +
        ' <span class="ms">' + r.ms + "ms" +
        (r.err ? " \u2014 " + esc(r.err) : "") + "</span></div>";
    });
    n.innerHTML = rows.join("") ||
      '<div class="nf-net-row">No network activity yet — \u201cTest Network\u201d dabao.</div>';
  }

  function paintMyTab() {
    var grid = $("nf-my-list");
    var cbox = $("nf-my-cont");
    if (!grid) return;
    paintNet();
    grid.innerHTML = "";
    cbox.innerHTML = "";
    if (!NF.myList.length) grid.innerHTML = '<div class="nf-empty">Your list is empty. Tap + My List on any title.</div>';
    NF.myList.forEach(function (m) {
      var im = el("img");
      im.src = posterSrc(m.poster);
      im.loading = "lazy";
      im.addEventListener("click", function () {
        if (m.imdb) resumeWatch(m);
        else if (m.id && m.media) playTmdb(m.id, m.media, m.title, m.poster);
      });
      grid.appendChild(im);
    });
    if (!NF.cont.length) cbox.innerHTML = '<div class="nf-empty">Nothing watched yet.</div>';
    NF.cont.forEach(function (w) {
      var li = el("div", "nf-listitem");
      li.innerHTML =
        "<img loading=\"lazy\" src=\"" + esc(posterSrc(w.poster)) + "\" alt=\"\">" +
        "<div class=\"nf-li-text\">" +
          "<div class=\"nf-li-title\">" + esc(w.title) + "</div>" +
          "<div class=\"nf-li-sub\">" + (w.type === "tv" ? "S" + (w.season || 1) + ":E" + (w.episode || 1) + " &bull; TV Show" : "Movie") +
          "</div></div>" + SVG.play.replace("<svg", "<svg class=\"nf-li-play\"");
      li.addEventListener("click", function () { resumeWatch(w); });
      cbox.appendChild(li);
    });
  }

  /* ================= SEASON PILLS ================= */
  function watchEpisodes() {
    var wsd = $("webSeriesData");
    var obs = new MutationObserver(function () { setTimeout(buildSeasons, 60); });
    obs.observe(wsd, { childList: true, subtree: true });
  }

  function buildSeasons() {
    var wsd = $("webSeriesData");
    var containers = wsd.querySelectorAll(".episode-container");
    if (!containers.length) { seasonBar.style.display = "none"; return; }
    seasonBar.innerHTML = "";
    seasonBar.style.display = "flex";
    var active = 0;
    for (var i = 0; i < containers.length; i++) {
      if (containers[i].classList.contains("on")) active = i;
    }
    var sel = seasonBar.dataset.sel ? +seasonBar.dataset.sel : 0;
    if (sel >= containers.length) sel = 0;
    for (var j = 0; j < containers.length; j++) {
      (function (idx) {
        var p = el("div", "sp" + (idx === sel ? " on" : ""), "Season " + (idx + 1));
        p.addEventListener("click", function () {
          seasonBar.dataset.sel = String(idx);
          showSeason(idx);
          buildSeasons();
        });
        seasonBar.appendChild(p);
      })(j);
    }
    showSeason(sel);
  }

  function showSeason(idx) {
    var wsd = $("webSeriesData");
    var containers = wsd.querySelectorAll(".episode-container");
    for (var i = 0; i < containers.length; i++) {
      containers[i].classList.toggle("on", i === idx);
    }
  }

  /* ================= BOOT ================= */
  function boot() {
    /* background network self-test: pre-fills Network Status so any future
       screenshot already contains the diagnosis */
    setTimeout(function () {
      try {
        var t0 = Date.now();
        fetch(px("https://api.themoviedb.org/3/configuration?api_key=" + K))
          .then(function (r) { nlog("auto tmdb-proxy", r.ok, Date.now() - t0); paintNet(); })
          .catch(function (e) { nlog("auto tmdb-proxy", false, Date.now() - t0, e.message); paintNet(); });
        var t1 = Date.now();
        fetch(IMF + "a.json")
          .then(function (r) { nlog("auto imdb-direct", r.ok, Date.now() - t1); paintNet(); })
          .catch(function (e) { nlog("auto imdb-direct", false, Date.now() - t1, e.message); paintNet(); });
      } catch (e) {}
    }, 6000);
    /* keep the URL clean: the site writes ?imdb=... params via
       history.replaceState; a page reload would re-trigger the player
       through the popup-trap instead of the visible player. */
    try {
      var _rs = history.replaceState.bind(history);
      history.replaceState = function (s, t, u) {
        try {
          if (u != null) {
            var url = new URL(String(u), location.origin);
            url.search = "";
            u = url.pathname;
          }
        } catch (e) {}
        return _rs(s, t, u);
      };
    } catch (e) {}

    buildShell();
    playerTitle = $("nf-ptitle");
    heroBox = $("nf-hero");
    rowsBox = $("nf-rows");
    searchList = $("nf-searchlist");
    myListGrid = $("nf-my-list");
    contList = $("nf-my-cont");
    renderHome(true);
    watchEpisodes();
    /* after initial page load, Java may call __NF_setProfile */
    document.addEventListener("DOMContentLoaded", function () {
      if (window.__NF_pendingProfile) window.__NF_setProfile(window.__NF_pendingProfile);
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})();
