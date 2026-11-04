/*
 * Wint Reader 本文表示エンジン（横書き専用）
 *
 * Kotlin 側から呼ぶ API（window.Reader）:
 *   load(doc, settings, position)  本文を表示する。position は {block, offset} または null
 *   applySettings(settings)        表示設定を変更する（読書位置は維持）
 *   setMode("paged" | "scroll")    ページ送り / スクロールの切り替え（読書位置は維持）
 *   next() / prev()                次 / 前のページ。端に達していたら false を返す
 *   goToPosition(block, offset)    本文内の論理位置へ移動
 *   goToPage(index)                ページ番号（0始まり）へ移動
 *   getCurrentPosition()           今のページ先頭の論理位置 {block, offset}
 *   relayout()                     画面サイズ変更後の再組版（読書位置は維持）
 *
 * Kotlin へ通知するもの（window.ReaderBridge, @JavascriptInterface）:
 *   onReady(totalPages)
 *   onPageChanged(page, totalPages, block, offset)   page は 1 始まり
 *   onEdge(direction)                                "end" | "start"
 *
 * 読書位置 {block, offset}:
 *   block  = NovelDocument.blocks の添字（HTML の data-block と同じ）
 *   offset = その段落内の文字位置。ルビの読みは数えず、親文字だけを数える
 */
(function () {
  "use strict";

  var bridge = window.ReaderBridge || {
    onReady: function (t) { console.log("onReady", t); },
    onPageChanged: function (p, t, b, o) { console.log("onPageChanged", p, t, b, o); },
    onEdge: function (d) { console.log("onEdge", d); }
  };

  var DEFAULT_SETTINGS = {
    fontSize: 18,        // px（WebView の CSS px = dp）
    lineHeight: 1.8,
    padTop: 24,
    padBottom: 40,       // 時刻・ページ表示の分を空ける
    padLeft: 20,
    padRight: 20,
    bg: "#ffffff",
    fg: "#222222",
    fontFamily: "sans-serif"
  };

  var state = {
    doc: null,
    settings: Object.assign({}, DEFAULT_SETTINGS),
    mode: "paged",
    page: 0,
    totalPages: 1,
    pos: { block: 0, offset: 0 },   // 最後に確定した読書位置（再組版の基準）
    blocks: [],                      // data-block 順の要素
    textCache: []                    // 各段落の数える対象のテキストノード（遅延生成）
  };

  var viewport = document.getElementById("viewport");
  var content = document.getElementById("content");

  // ---------------------------------------------------------------- 描画

  function render(doc) {
    content.textContent = "";
    state.blocks = [];
    state.textCache = [];

    if (doc.title) {
      var h = document.createElement("div");
      h.className = "title";
      h.textContent = doc.title;
      content.appendChild(h);
    }

    (doc.blocks || []).forEach(function (b, i) {
      var el;
      switch (b.type) {
        case "p":
          el = document.createElement("p");
          (b.spans || []).forEach(function (s) { el.appendChild(renderSpan(s)); });
          if (!el.firstChild) el.appendChild(document.createElement("br"));
          break;
        case "img":
          el = document.createElement("figure");
          var img = document.createElement("img");
          img.src = b.uri;
          if (b.alt) img.alt = b.alt;
          img.addEventListener("load", onImageLoaded);
          el.appendChild(img);
          break;
        case "hr":
          el = document.createElement("hr");
          break;
        case "blank":
        default:
          el = document.createElement("div");
          el.className = "blank";
          break;
      }
      el.setAttribute("data-block", String(i));
      content.appendChild(el);
      state.blocks.push(el);
    });

    var marker = document.createElement("span");
    marker.id = "end-marker";
    content.appendChild(marker);
  }

  function renderSpan(s) {
    switch (s.type) {
      case "ruby":
        var r = document.createElement("ruby");
        r.appendChild(document.createTextNode(s.base));
        var rt = document.createElement("rt");
        rt.textContent = s.reading;
        r.appendChild(rt);
        return r;
      case "em":
        var e = document.createElement("span");
        e.className = "em";
        e.textContent = s.text;
        return e;
      case "text":
      default:
        return document.createTextNode(s.text || "");
    }
  }

  var imageTimer = null;
  function onImageLoaded() {
    // 挿絵の読み込みでページ数が変わるので、位置を保って組み直す
    clearTimeout(imageTimer);
    imageTimer = setTimeout(function () { relayout(); }, 50);
  }

  function applyCssVariables() {
    var s = state.settings;
    var st = document.documentElement.style;
    st.setProperty("--font-size", s.fontSize + "px");
    st.setProperty("--line-height", String(s.lineHeight));
    st.setProperty("--pad-top", s.padTop + "px");
    st.setProperty("--pad-bottom", s.padBottom + "px");
    st.setProperty("--pad-left", s.padLeft + "px");
    st.setProperty("--pad-right", s.padRight + "px");
    st.setProperty("--bg", s.bg);
    st.setProperty("--fg", s.fg);
    st.setProperty("--font-family", s.fontFamily);
    document.body.className = state.mode;
  }

  // ---------------------------------------------------------------- ページ計算

  // 画面サイズは viewport 要素の実寸を使う（vh/vw や innerHeight は WebView で 0 になることがある）
  function pageWidth() { return viewport.clientWidth || window.innerWidth || 1; }
  function pageHeight() { return viewport.clientHeight || window.innerHeight || 1; }

  function hasSize() { return viewport.clientWidth > 0 && viewport.clientHeight > 0; }

  function applyViewportSize() {
    var st = document.documentElement.style;
    st.setProperty("--vw", pageWidth() + "px");
    st.setProperty("--vh", pageHeight() + "px");
  }

  function measureTotalPages() {
    if (state.mode === "scroll") {
      return Math.max(1, Math.ceil(viewport.scrollHeight / pageHeight()));
    }
    var marker = document.getElementById("end-marker");
    var rect = marker.getBoundingClientRect();
    return Math.max(1, pageOfX(rect.left) + 1);
  }

  // ビューポート上の x 座標が何ページ目（0始まり）にあるか
  function pageOfX(x) {
    var abs = x + state.page * pageWidth();
    return Math.max(0, Math.floor(abs / pageWidth()));
  }

  function setTransform() {
    if (state.mode !== "paged") {
      content.style.transform = "";
      return;
    }
    content.style.transform = "translate3d(" + (-state.page * pageWidth()) + "px,0,0)";
  }

  // ---------------------------------------------------------------- 文字位置

  // 段落内で数える対象のテキストノード（<rt> の中は除く）
  function textNodesOf(i) {
    if (state.textCache[i]) return state.textCache[i];
    var el = state.blocks[i];
    var nodes = [];
    var walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, {
      acceptNode: function (n) {
        for (var p = n.parentNode; p && p !== el; p = p.parentNode) {
          if (p.nodeName === "RT") return NodeFilter.FILTER_REJECT;
        }
        return NodeFilter.FILTER_ACCEPT;
      }
    });
    var total = 0;
    for (var n = walker.nextNode(); n; n = walker.nextNode()) {
      nodes.push({ node: n, start: total });
      total += n.data.length;
    }
    var entry = { nodes: nodes, length: total };
    state.textCache[i] = entry;
    return entry;
  }

  // 段落 i の offset 文字目の矩形（ビューポート座標）。文字がなければ段落の先頭
  function charRect(i, offset) {
    var t = textNodesOf(i);
    if (t.length === 0) {
      var rects = state.blocks[i].getClientRects();
      return rects.length ? rects[0] : state.blocks[i].getBoundingClientRect();
    }
    offset = Math.max(0, Math.min(offset, t.length - 1));
    var k = 0;
    while (k + 1 < t.nodes.length && t.nodes[k + 1].start <= offset) k++;
    var node = t.nodes[k].node;
    var local = offset - t.nodes[k].start;
    var range = document.createRange();
    range.setStart(node, local);
    range.setEnd(node, Math.min(local + 1, node.data.length));
    var rs = range.getClientRects();
    // 改行位置などで矩形が2つ出る場合は、文字そのものの矩形（最後）を使う
    return rs.length ? rs[rs.length - 1] : range.getBoundingClientRect();
  }

  // 「この矩形は今のページの先頭以降にあるか」
  function isAtOrAfterCurrent(rect) {
    if (state.mode === "scroll") {
      return rect.top > -2; // 行の上端が画面内にある最初の文字
    }
    return pageOfX(rect.left + 1) >= state.page;
  }

  function blockLastRect(i) {
    var rs = state.blocks[i].getClientRects();
    return rs.length ? rs[rs.length - 1] : state.blocks[i].getBoundingClientRect();
  }

  // 画面 → 本文位置：今のページの先頭にある文字
  function getCurrentPosition() {
    var n = state.blocks.length;
    if (n === 0) return { block: 0, offset: 0 };
    // 段落の末尾が今のページ以降にある最初の段落を二分探索
    var lo = 0, hi = n - 1, found = n - 1;
    while (lo <= hi) {
      var mid = (lo + hi) >> 1;
      if (isAtOrAfterCurrent(blockLastRect(mid))) { found = mid; hi = mid - 1; }
      else lo = mid + 1;
    }
    var t = textNodesOf(found);
    if (t.length === 0) return { block: found, offset: 0 };
    // 段落内で今のページ以降にある最初の文字を二分探索
    var a = 0, b = t.length - 1, off = t.length - 1;
    while (a <= b) {
      var m = (a + b) >> 1;
      if (isAtOrAfterCurrent(charRect(found, m))) { off = m; b = m - 1; }
      else a = m + 1;
    }
    return { block: found, offset: off };
  }

  // 本文位置 → 画面：その文字があるページへ移動
  function goToPosition(block, offset, silent) {
    if (state.blocks.length === 0) return;
    block = Math.max(0, Math.min(block | 0, state.blocks.length - 1));
    offset = Math.max(0, offset | 0);
    if (state.mode === "scroll") {
      var r = charRect(block, offset);
      viewport.scrollTop += r.top - state.settings.padTop;
      state.page = Math.floor(viewport.scrollTop / pageHeight());
    } else {
      var rect = charRect(block, offset);
      state.page = Math.min(pageOfX(rect.left + 1), state.totalPages - 1);
      setTransform();
    }
    state.pos = { block: block, offset: offset };
    if (!silent) report();
  }

  function goToPage(index) {
    if (state.mode === "scroll") {
      viewport.scrollTop = index * pageHeight();
      return;
    }
    state.page = Math.max(0, Math.min(index | 0, state.totalPages - 1));
    setTransform();
    state.pos = getCurrentPosition();
    report();
  }

  // ---------------------------------------------------------------- 操作

  function next() {
    if (state.mode === "scroll") {
      if (viewport.scrollTop + pageHeight() >= viewport.scrollHeight - 1) {
        bridge.onEdge("end");
        return false;
      }
      viewport.scrollTop += pageHeight() - lineStep();
      return true;
    }
    if (state.page >= state.totalPages - 1) { bridge.onEdge("end"); return false; }
    goToPage(state.page + 1);
    return true;
  }

  function prev() {
    if (state.mode === "scroll") {
      if (viewport.scrollTop <= 0) { bridge.onEdge("start"); return false; }
      viewport.scrollTop -= pageHeight() - lineStep();
      return true;
    }
    if (state.page <= 0) { bridge.onEdge("start"); return false; }
    goToPage(state.page - 1);
    return true;
  }

  function lineStep() {
    return state.settings.fontSize * state.settings.lineHeight;
  }

  function report() {
    var page = state.mode === "scroll"
      ? Math.floor(viewport.scrollTop / pageHeight()) + 1
      : state.page + 1;
    bridge.onPageChanged(page, state.totalPages, state.pos.block, state.pos.offset);
  }

  var scrollTimer = null;
  viewport.addEventListener("scroll", function () {
    if (state.mode !== "scroll") return;
    clearTimeout(scrollTimer);
    scrollTimer = setTimeout(function () {
      state.pos = getCurrentPosition();
      report();
    }, 150);
  });

  // ---------------------------------------------------------------- 組版

  // 位置を保ったまま組み直す。基準は最後に確定した state.pos
  function relayout() {
    if (!hasSize()) return; // 画面サイズが決まったら resize イベントでもう一度呼ばれる
    applyViewportSize();
    var pos = state.pos;
    state.textCache = [];
    state.page = 0;
    setTransform();
    viewport.scrollTop = 0;
    state.totalPages = measureTotalPages();
    goToPosition(pos.block, pos.offset, true);
    report();
  }

  function whenFontsReady(fn) {
    if (document.fonts && document.fonts.ready) {
      document.fonts.ready.then(function () { requestAnimationFrame(fn); });
    } else {
      requestAnimationFrame(fn);
    }
  }

  // WebView の大きさが 0 のうちは組版しない
  function waitForSize(fn) {
    if (hasSize()) { fn(); return; }
    requestAnimationFrame(function () { waitForSize(fn); });
  }

  function load(doc, settings, position) {
    state.doc = typeof doc === "string" ? JSON.parse(doc) : doc;
    if (settings) state.settings = Object.assign({}, DEFAULT_SETTINGS, settings);
    state.pos = position || { block: 0, offset: 0 };
    applyCssVariables();
    render(state.doc);
    whenFontsReady(function () {
      waitForSize(function () {
        relayout();
        bridge.onReady(state.totalPages);
      });
    });
  }

  function applySettings(settings) {
    state.settings = Object.assign({}, state.settings, settings);
    applyCssVariables();
    whenFontsReady(relayout);
  }

  function setMode(mode) {
    if (mode !== "paged" && mode !== "scroll") return;
    state.mode = mode;
    applyCssVariables();
    whenFontsReady(relayout);
  }

  var resizeTimer = null;
  window.addEventListener("resize", function () {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(function () { if (state.doc) relayout(); }, 100);
  });

  window.Reader = {
    load: load,
    applySettings: applySettings,
    setMode: setMode,
    next: next,
    prev: prev,
    goToPosition: function (b, o) { goToPosition(b, o, false); },
    goToPage: goToPage,
    getCurrentPosition: getCurrentPosition,
    relayout: relayout,
    _state: state
  };
})();
