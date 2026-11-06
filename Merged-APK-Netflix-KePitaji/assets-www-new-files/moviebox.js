/* ============================================================
   MOVIEBOX DATA BRIDGE — additive layer (v1.0)
   Merges MovieBox (Hindi dubbed) rows + search results + native
   stream playback into the existing Netflix-style shell.
   Style untouched: reuses the app's existing CSS classes only.
   ============================================================ */
(function () {
  "use strict";
  if (window.__MB__) return;
  window.__MB__ = true;

  var PH = "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='71' height='101'><rect width='100%25' height='100%25' fill='%23161616'/><text x='50%25' y='58%25' font-size='26' font-weight='bold' fill='%23E50914' text-anchor='middle' font-family='Arial'>MB</text></svg>";

  var seq = 0;                 // search sequence guard
  var searchSeq = 0;
  var playerOpen = false;
  var overlayEl = null;
  var videoEl = null;
  var hls = null;
  var cands = [];
  var candIdx = 0;
  var mbItem = null;
  var seasons = [];
  var curSe = 0, curEp = 0;
  var lastSearchAppended = "";

  /* ---------------- bridge ---------------- */
  function callBridge(method) {
    var args = Array.prototype.slice.call(arguments, 1);
    return new Promise(function (resolve, reject) {
      try {
        if (!window.NFApp || !window.NFApp[method]) { reject(new Error("bridge missing")); return; }
        var cbId = "mb" + (Date.now()) + "_" + (++seq);
        var handler = function (id, payload) {
          if (String(id) !== cbId) return;
          delete window.__NF_mbHandlers[cbId];
          if (payload == null) { reject(new Error("no reply")); return; }
          try {
            var d = (typeof payload === "string") ? JSON.parse(payload) : payload;
            resolve(d);
          } catch (e) { reject(e); }
        };
        if (!window.__NF_mbHandlers) window.__NF_mbHandlers = {};
        window.__NF_mbHandlers[cbId] = handler;
        var jsArgs = args.map(function (a) { return JSON.stringify(String(a == null ? "" : a)); });
        NFApp[method].apply(NFApp, jsArgs.concat([cbId]));
        /* safety timeout */
        setTimeout(function () {
          if (window.__NF_mbHandlers && window.__NF_mbHandlers[cbId]) {
            delete window.__NF_mbHandlers[cbId];
            reject(new Error("timeout"));
          }
        }, 45000);
      } catch (e) { reject(e); }
    });
  }
  /* Java replies land here */
  window.__NF_mbReply = function (cbId, payload) {
    try {
      var h = window.__NF_mbHandlers && window.__NF_mbHandlers[cbId];
      if (h) h(cbId, payload);
    } catch (e) { }
  };

  /* ---------------- poster strategy ---------------- */
  var posterMode = null; // 'direct' | 'proxy'
  try { posterMode = localStorage.getItem("mb_pm"); } catch (e) { }
  function posterSrc(u) {
    if (!u) return PH;
    if (posterMode === "proxy") return "/mbp?u=" + encodeURIComponent(u);
    return u;
  }
  function probePosters(firstUrl) {
    if (posterMode || !firstUrl) return;
    var im = new Image();
    im.onload = function () {
      posterMode = "direct";
      try { localStorage.setItem("mb_pm", "direct"); } catch (e) { }
    };
    im.onerror = function () {
      posterMode = "proxy";
      try { localStorage.setItem("mb_pm", "proxy"); } catch (e) { }
      repaintMbPosters();
    };
    im.src = firstUrl;
  }
  var mbImgs = [];
  function repaintMbPosters() {
    mbImgs = mbImgs.filter(function (e) { return e.isConnected; });
    mbImgs.forEach(function (im) {
      var u = im.getAttribute("data-mbu");
      if (u) im.src = posterSrc(u);
    });
  }

  function esc(s) {
    return String(s == null ? "" : s)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;")
      .replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }

  function proxied(u, cookie) {
    var p = "/mbp?u=" + encodeURIComponent(u);
    if (cookie) p += "&ck=" + encodeURIComponent(cookie);
    return p;
  }

  /* ================= HOME ROWS ================= */
  function renderHomeRows(rowsBox) {
    if (!rowsBox) return;
    if (rowsBox.querySelector("#mb-home-pending") || rowsBox.querySelector(".mb-row")) return;
    callBridge("mbHome").then(function (d) {
      var pend = document.getElementById("mb-home-pending");
      if (pend) pend.remove();
      if (!d || !d.ok || !d.rows || !d.rows.length) return;
      var box = document.getElementById("nf-rows") || rowsBox;
      if (!box || !box.isConnected) return;
      /* respect the active feed chip: Movies -> only movie rows, Shows -> only series rows */
      var feed = (window.__NF_state && window.__NF_state.feed) || "home";
      var rowsArr = (d.rows || []).filter(function (row) {
        var it0 = (row.items || [])[0];
        var isSeriesRow = !!(it0 && it0.s);
        if (feed === "movies") return !isSeriesRow;
        if (feed === "shows") return isSeriesRow;
        return true;
      });
      rowsArr.forEach(function (row) {
        var wrap = document.createElement("div");
        wrap.className = "nf-fade mb-row";
        var head = document.createElement("div");
        head.className = "nf-rowhead";
        head.innerHTML = '<span class="nf-li-badge" style="vertical-align:2px">MOVIEBOX</span>' +
          "<span>" + esc(row.label) + '</span><span class="nf-arrow">&#9654;</span>';
        wrap.appendChild(head);
        var rr = document.createElement("div");
        rr.className = "nf-row";
        wrap.appendChild(rr);
        (row.items || []).forEach(function (m) {
          var c = document.createElement("div");
          c.className = "nf-card";
          var img = document.createElement("img");
          img.loading = "lazy";
          img.setAttribute("data-mbu", m.p || "");
          img.src = posterSrc(m.p);
          mbImgs.push(img);
          c.appendChild(img);
          var t = document.createElement("div");
          t.className = "nf-card-title";
          t.textContent = (m.t || "") + (m.s ? " \u2022 Series" : "");
          c.appendChild(t);
          c.addEventListener("click", function () { openPlayer(m); });
          rr.appendChild(c);
        });
        box.appendChild(wrap);
        probePosters((row.items && row.items[0] && row.items[0].p) || "");
      });
    }).catch(function () {
      var pend = document.getElementById("mb-home-pending");
      if (pend) pend.remove();
    });
    /* pending skeleton row (same look, spinner inside) */
    var wrap0 = document.createElement("div");
    wrap0.className = "nf-fade";
    wrap0.id = "mb-home-pending";
    var h0 = document.createElement("div");
    h0.className = "nf-rowhead";
    h0.innerHTML = '<span class="nf-li-badge" style="vertical-align:2px">MOVIEBOX</span>' +
      "<span>Hindi &amp; Dubbed</span><span class=\"nf-arrow\">&#9654;</span>";
    wrap0.appendChild(h0);
    var spin = document.createElement("div");
    spin.className = "nf-spin";
    spin.style.margin = "10px 14px";
    wrap0.appendChild(spin);
    rowsBox.appendChild(wrap0);
  }

  /* ================= SEARCH MERGE ================= */
  function searchAppend(q, box) {
    if (!box || !q) return;
    var mySeq = ++searchSeq;
    lastSearchAppended = q;
    /* wait until the site's own paint finished (spinner gone) */
    var tries = 0;
    (function poll() {
      if (mySeq !== searchSeq) return;                 /* newer search started */
      var spinning = box.querySelector(".nf-spin");
      if (spinning && tries++ < 48) { setTimeout(poll, 250); return; }
      if (mySeq !== searchSeq) return;
      callBridge("mbSearch", q).then(function (d) {
        if (mySeq !== searchSeq) return;
        if (!d || !d.ok || !d.items || !d.items.length) return;
        var box2 = document.getElementById("nf-searchlist") || box;
        if (!box2 || !box2.isConnected) return;
        var head = document.createElement("div");
        head.className = "nf-rowhead";
        head.style.padding = "14px 12px 4px";
        head.innerHTML = '<span class="nf-li-badge" style="vertical-align:2px">MOVIEBOX</span>' +
          "<span>More results \u2014 Hindi Dubbed</span>";
        var hadResults = box2.querySelector(".nf-listitem");
        if (!hadResults) {
          /* no old-data results: MovieBox fills the page */
          head.innerHTML = '<span class="nf-li-badge" style="vertical-align:2px">MOVIEBOX</span>' +
            "<span>Results \u2014 Hindi Dubbed</span>";
          box2.innerHTML = "";
        }
        box2.appendChild(head);
        (d.items || []).forEach(function (m) {
          if (String(m.id) === "" || !m.p) return;
          var li = document.createElement("div");
          li.className = "nf-listitem";
          var img = document.createElement("img");
          img.loading = "lazy";
          img.setAttribute("data-mbu", m.p);
          img.src = posterSrc(m.p);
          mbImgs.push(img);
          li.appendChild(img);
          var tx = document.createElement("div");
          tx.className = "nf-li-text";
          var t1 = document.createElement("div");
          t1.className = "nf-li-title";
          t1.textContent = m.t || "";
          var t2 = document.createElement("div");
          t2.className = "nf-li-sub";
          t2.innerHTML = '<span class="nf-li-badge">MOVIEBOX</span>' +
            (m.s ? "Web Series \u2022 Hindi Dubbed" : "Movie \u2022 Hindi Dubbed");
          tx.appendChild(t1);
          tx.appendChild(t2);
          li.appendChild(tx);
          var play = document.createElementNS("http://www.w3.org/2000/svg", "svg");
          play.setAttribute("viewBox", "0 0 24 24");
          play.setAttribute("class", "nf-li-play");
          var path = document.createElementNS("http://www.w3.org/2000/svg", "path");
          path.setAttribute("d", "M6 4v16l14-8L6 4z");
          play.appendChild(path);
          li.appendChild(play);
          li.addEventListener("click", function () { openPlayer(m); });
          box2.appendChild(li);
        });
      }).catch(function () { });
    })();
  }

  /* ================= PLAYER OVERLAY ================= */
  function ensureOverlay() {
    if (overlayEl) return overlayEl;
    var ov = document.createElement("div");
    ov.id = "mb-player";
    ov.style.cssText = "position:fixed;inset:0;background:#000;z-index:2147483000;display:none;flex-direction:column;";
    ov.innerHTML =
      '<div id="mb-ph" style="display:flex;align-items:center;gap:10px;padding:10px 12px;flex:0 0 auto;">' +
        '<div id="mb-back" style="width:34px;height:34px;border-radius:50%;background:#1a1a1a;display:flex;align-items:center;justify-content:center;color:#fff;font-size:18px;cursor:pointer;">&#8249;</div>' +
        '<div style="flex:1;min-width:0;">' +
          '<div id="mb-ptitle" style="color:#fff;font-weight:700;font-size:15px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">MovieBox</div>' +
          '<div id="mb-psub" style="color:#8c8c8c;font-size:11.5px;margin-top:1px;">Hindi Dubbed</div>' +
        '</div>' +
        '<div id="mb-close" style="width:34px;height:34px;border-radius:50%;background:#1a1a1a;display:flex;align-items:center;justify-content:center;color:#fff;font-size:14px;cursor:pointer;">&#10005;</div>' +
      '</div>' +
      '<div id="mb-seasons" style="display:none;"></div>' +
      '<div style="flex:1;min-height:0;position:relative;background:#000;">' +
        '<video id="mb-video" controls playsinline webkit-playsinline style="position:absolute;inset:0;width:100%;height:100%;background:#000;object-fit:contain;"></video>' +
        '<div id="mb-load" style="position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;background:#000;gap:12px;">' +
          '<div class="nf-spin"></div>' +
          '<div id="mb-loadtxt" style="color:#8c8c8c;font-size:12px;">Loading MovieBox stream...</div>' +
        '</div>' +
        '<div id="mb-err" style="position:absolute;inset:0;display:none;flex-direction:column;align-items:center;justify-content:center;background:#000;gap:14px;padding:20px;text-align:center;">' +
          '<div id="mb-errtxt" style="color:#8c8c8c;font-size:13px;">Stream unavailable</div>' +
          '<div id="mb-retry" style="background:#E50914;color:#fff;padding:9px 22px;border-radius:6px;font-weight:700;font-size:13px;cursor:pointer;">Try Again</div>' +
        '</div>' +
        '<div id="mb-srv" style="position:absolute;right:10px;bottom:70px;background:rgba(22,22,22,.92);border:1px solid #333;color:#fff;font-size:11px;font-weight:700;padding:6px 12px;border-radius:16px;cursor:pointer;display:none;">Server</div>' +
      '</div>';
    document.body.appendChild(ov);
    overlayEl = ov;
    videoEl = ov.querySelector("#mb-video");
    ov.querySelector("#mb-close").addEventListener("click", closePlayer);
    ov.querySelector("#mb-back").addEventListener("click", closePlayer);
    ov.querySelector("#mb-retry").addEventListener("click", function () {
      tryCandidate(candIdx, true);
    });
    ov.querySelector("#mb-srv").addEventListener("click", function () {
      if (cands.length > 1) tryCandidate((candIdx + 1) % cands.length, false);
    });
    videoEl.addEventListener("waiting", showLoad);
    videoEl.addEventListener("stalled", showLoad);
    videoEl.addEventListener("playing", hideLoad);
    videoEl.addEventListener("canplay", hideLoad);
    videoEl.addEventListener("error", function () {
      if (!videoEl.src && !videoEl.currentSrc) return;
      if (hls) return; /* hls.js surfaces errors through its own handler */
      nextCandidateOrError();
    });
    return ov;
  }

  function showLoad() {
    var l = document.getElementById("mb-load");
    if (l) l.style.display = "flex";
  }
  function hideLoad() {
    var l = document.getElementById("mb-load");
    if (l) l.style.display = "none";
  }
  function showErr(msg) {
    hideLoad();
    var e = document.getElementById("mb-err");
    if (e) {
      e.style.display = "flex";
      var t = document.getElementById("mb-errtxt");
      if (t) t.textContent = msg || "Stream unavailable. Try another server.";
    }
  }

  function nextCandidateOrError() {
    if (candIdx + 1 < cands.length) tryCandidate(candIdx + 1, false);
    else showErr("Ye title abhi play nahi ho rahi. Thodi der baad try karein.");
  }

  function destroyStream() {
    try { if (hls) { hls.destroy(); hls = null; } } catch (e) { }
    try { videoEl.pause(); } catch (e) { }
    try { videoEl.removeAttribute("src"); videoEl.load(); } catch (e) { }
  }

  function tryCandidate(idx, isRetry) {
    if (!cands.length) { showErr(); return; }
    candIdx = idx;
    var c = cands[idx];
    destroyStream();
    hideErr();
    showLoad();
    var srv = document.getElementById("mb-srv");
    if (srv) {
      srv.style.display = cands.length > 1 ? "block" : "none";
      srv.textContent = "Server " + (idx + 1) + "/" + cands.length +
        (c.q ? " \u2022 " + c.q : "") + (c.c ? " \u2022 " + String(c.c).toUpperCase() : "");
    }
    var src = proxied(c.u, c.cookie);
    if (c.kind === "hls" && window.Hls && Hls.isSupported()) {
      try {
        hls = new Hls({ maxBufferLength: 30, maxMaxBufferLength: 60, nudgeMaxRetry: 5 });
        hls.on(Hls.Events.ERROR, function (evt, data) {
          if (!hls) return;
          if (data && data.fatal) {
            hls.destroy(); hls = null;
            nextCandidateOrError();
          }
        });
        hls.loadSource(src);
        hls.attachMedia(videoEl);
        videoEl.play && videoEl.play().catch(function () { });
        return;
      } catch (e) { hls = null; }
    }
    if (c.kind === "hls" && videoEl.canPlayType("application/vnd.apple.mpegurl")) {
      videoEl.src = src;
      videoEl.play && videoEl.play().catch(function () { });
      return;
    }
    /* plain mp4 */
    videoEl.src = src;
    videoEl.play && videoEl.play().catch(function () { });
    void isRetry;
  }

  function hideErr() {
    var e = document.getElementById("mb-err");
    if (e) e.style.display = "none";
  }

  function paintSeasons() {
    var bar = document.getElementById("mb-seasons");
    if (!bar) return;
    if (!mbItem || !mbItem.s || !seasons.length) { bar.style.display = "none"; return; }
    bar.style.display = "flex";
    bar.style.gap = "8px";
    bar.style.overflowX = "auto";
    bar.style.padding = "6px 14px";
    bar.style.scrollbarWidth = "none";
    bar.innerHTML = "";
    seasons.forEach(function (s) {
      var se = s.se || 0;
      if (se <= 0) return;
      var pill = document.createElement("div");
      pill.className = "sp" + (se === curSe ? " on" : "");
      pill.textContent = "Season " + se;
      pill.addEventListener("click", function () {
        if (se === curSe) return;
        curSe = se;
        curEp = 1;
        paintSeasons();
        loadStream(true);
      });
      bar.appendChild(pill);
      if (se === curSe) {
        var maxEp = Math.min(s.maxEp || 1, 60);
        for (var e = 1; e <= maxEp; e++) {
          (function (ep) {
            var p = document.createElement("div");
            p.className = "sp" + (ep === curEp ? " on" : "");
            p.textContent = "E" + ep;
            p.addEventListener("click", function () {
              if (ep === curEp) return;
              curEp = ep;
              paintSeasons();
              loadStream(true);
            });
            bar.appendChild(p);
          })(e);
        }
      }
    });
  }

  function loadStream(fresh) {
    if (!mbItem) return;
    hideErr();
    showLoad();
    var lt = document.getElementById("mb-loadtxt");
    if (lt) lt.textContent = "Loading MovieBox stream...";
    callBridge("mbPlay", mbItem.id, curSe || 0, curEp || 0).then(function (d) {
      if (!d || !d.ok || !d.cands || !d.cands.length) {
        showErr((d && d.error) || "No playable stream found right now");
        return;
      }
      cands = d.cands;
      seasons = d.seasons || seasons;
      if (d.se) curSe = d.se;
      if (d.ep) curEp = d.ep;
      paintSeasons();
      var sub = document.getElementById("mb-psub");
      if (sub) {
        sub.textContent = "MovieBox \u2022 " + (mbItem.s ? "S" + curSe + ":E" + curEp + " Web Series" : "Movie") +
          " \u2022 Hindi Dubbed";
      }
      tryCandidate(0, false);
    }).catch(function () {
      showErr("MovieBox network error. Check internet and retry.");
    });
    void fresh;
  }

  function openPlayer(item) {
    if (!item || !item.id) return;
    mbItem = item;
    cands = [];
    candIdx = 0;
    curSe = 0;
    curEp = 0;
    seasons = [];
    var ov = ensureOverlay();
    var title = document.getElementById("mb-ptitle");
    if (title) title.textContent = item.t || "MovieBox";
    var sub = document.getElementById("mb-psub");
    if (sub) sub.textContent = "MovieBox \u2022 Hindi Dubbed";
    destroyStream();
    hideErr();
    showLoad();
    ov.style.display = "flex";
    playerOpen = true;
    try { document.body.style.overflow = "hidden"; } catch (e) { }
    window.scrollTo(0, 0);
    loadStream(false);
  }

  function closePlayer() {
    playerOpen = false;
    destroyStream();
    if (overlayEl) overlayEl.style.display = "none";
    try { document.body.style.overflow = ""; } catch (e) { }
  }

  function isPlaying() { return playerOpen; }

  /* expose + integrate */
  window.MB = {
    renderHomeRows: renderHomeRows,
    searchAppend: searchAppend,
    openPlayer: openPlayer,
    closePlayer: closePlayer,
    isPlaying: isPlaying
  };

  /* if the shell already rendered the home rows before we loaded, append now */
  function lateBoot() {
    try {
      var rows = document.getElementById("nf-rows");
      if (rows && rows.childElementCount && !rows.querySelector(".mb-row")
          && !document.getElementById("mb-home-pending")) {
        renderHomeRows(rows);
      }
    } catch (e) { }
  }
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", function () { setTimeout(lateBoot, 300); });
  } else {
    setTimeout(lateBoot, 300);
  }
})();
