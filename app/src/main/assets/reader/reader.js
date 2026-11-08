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
    fontFamily: "sans-serif",
    animation: "slide",  // "slide" | "fold" | "none"
    showImages: true,    // 挿絵を表示するか
    showTitle: true      // 話の頭にタイトルを出すか
  };

  var ANIMATION_MS = 220;

  var state = {
    doc: null,
    settings: Object.assign({}, DEFAULT_SETTINGS),
    mode: "paged",
    swipe: false,                    // スワイプで指に合わせてめくる

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
    if (typeof invalidateClones === "function") invalidateClones();
    content.textContent = "";
    state.blocks = [];
    state.textCache = [];

    // 英文などは、両端揃え・単語の途中でのハイフン区切り・欧文の書体で組む（.latin）
    var latin = isLatinDoc(doc);
    content.classList.toggle("latin", latin);
    content.setAttribute("lang", latin ? (doc.lang && !/^ja/i.test(doc.lang) ? doc.lang : "en") : "ja");

    // 話のタイトル（表示設定で隠せる。読書位置の番号には入らない）
    if (doc.title && state.settings.showTitle !== false) {
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
          if (state.settings.showImages === false) {
            // 挿絵を表示しない設定：読書位置の番号がずれないよう、小さな印だけ置く
            el = document.createElement("div");
            el.className = "img-hidden";
            el.textContent = "［挿絵］";
            break;
          }
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

  // 本文の言語。doc.lang があればそれに従い、なければ本文の頭の文字で見分ける（アルファベットが半分を超えたら英文）
  function isLatinDoc(doc) {
    if (doc.lang) return !/^ja/i.test(doc.lang);
    var letters = 0, total = 0;
    var blocks = doc.blocks || [];
    for (var i = 0; i < blocks.length && total < 3000; i++) {
      (blocks[i].spans || []).forEach(function (sp) {
        var t = sp.text || sp.base || "";
        for (var k = 0; k < t.length && total < 3000; k++) {
          var c = t.charCodeAt(k);
          if (c <= 32 || c === 0x3000) continue;
          total++;
          if ((c >= 65 && c <= 90) || (c >= 97 && c <= 122)) letters++;
        }
      });
    }
    return total > 40 && letters / total > 0.5;
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
  // アニメーション中でも正しく計算できるよう、#content の実際の位置を基準にする
  function pageOfX(x) {
    var origin = content.getBoundingClientRect().left;
    return Math.max(0, Math.floor((x - origin) / pageWidth()));
  }

  // animated = true のときだけページめくりのアニメーションを付ける（READ-13）
  function setTransform(animated) {
    if (state.mode !== "paged") {
      content.style.transition = "";
      content.style.transform = "";
      return;
    }
    var anim = state.settings.animation;
    if (animated && anim === "slide") {
      content.style.transition = "transform " + ANIMATION_MS + "ms cubic-bezier(0.2, 0.7, 0.3, 1)";
    } else {
      content.style.transition = "none";
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
    if (block === 0 && offset === 0) {
      // 話の先頭：タイトルも見えるよう、いちばん上（最初のページ）を出す
      if (state.mode === "scroll") {
        viewport.scrollTop = 0;
        state.page = 0;
      } else {
        state.page = 0;
        setTransform();
      }
    } else if (state.mode === "scroll") {
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

  function goToPage(index, animated) {
    if (state.mode === "scroll") {
      viewport.scrollTop = index * pageHeight();
      return;
    }
    var from = state.page;
    state.page = Math.max(0, Math.min(index | 0, state.totalPages - 1));
    if (animated === true && state.settings.animation === "fold" && from !== state.page) {
      foldTurn(from, state.page);
    } else {
      endTurn();
      setTransform(animated === true);
    }
    state.pos = getCurrentPosition();
    report();
  }


  // ---------------------------------------------------------------- 折り返しめくり（READ-13）
  //
  // 紙の右端が持ち上がって左へ折り返されるように見せる。重ね順（下から）:
  //   #viewport（本物の本文）  : 番号の大きいページ。全面に表示
  //   shadeHigh               : 次のページに落ちる影（折り目の右側）
  //   left                    : 番号の小さいページ。折り目より左だけ見せる
  //   flapShadow              : めくれた紙が下のページに落とす影（紙の形をぼかしたもの）
  //   flap                    : 番号の小さいページの「折り目より右」を、折り目で裏返した紙
  // 次へ進むときは t を 0→1、前へ戻るときは 1→0 で動かす（同じ絵を逆再生する）。
  //
  // 実機で描画が間に合うよう、アニメーション中は transform だけを変える
  // （clip-path を毎フレーム変えると巨大な本文を毎回描き直すことになり、白く欠ける）。
  // 折り目での切り抜きは「回転させた overflow:hidden の箱」と「その逆回転」で作る。

  var FOLD_MS = 480;
  var turnRoot = null, shadeHigh = null, flapShadow = null, flapShadowShape = null;
  var left = null, flapOuter = null, flapClip = null, shadeFlap = null;
  var cloneLeft = null, cloneFlap = null;
  var turning = null; // 進行中のめくり { raf, done }

  function makeDiv(cls, parent) {
    var d = document.createElement("div");
    d.className = cls;
    parent.appendChild(d);
    return d;
  }

  // 半平面の切り抜き：box（回転する箱）> inner（逆回転して画面座標に戻す）> page（画面と同じ大きさ）
  function makeHalfPlane(parent, side) {
    var box = makeDiv("hp-box hp-" + side, parent);
    var inner = makeDiv("hp-inner", box);
    var page = makeDiv("hp-page", inner);
    return { box: box, inner: inner, page: page, side: side };
  }

  function buildTurnLayers() {
    if (turnRoot) return;
    turnRoot = document.createElement("div");
    turnRoot.id = "turn";
    document.body.appendChild(turnRoot);
    shadeHigh = makeDiv("fold-shade fold-shade-high", turnRoot);
    left = makeHalfPlane(turnRoot, "left");
    // めくれた紙の形をした影（ぼかした外枠の中に、紙の形に切り抜いた板を置く）
    flapShadow = makeDiv("flap-shadow", turnRoot);
    flapShadowShape = makeDiv("flap-shadow-shape", flapShadow);
    flapOuter = makeDiv("turn-flap", turnRoot);
    flapClip = makeHalfPlane(flapOuter, "right");
    makeDiv("turn-flap-fade", flapClip.page);
    shadeFlap = makeDiv("fold-shade fold-shade-flap", flapClip.page);
  }

  // めくりに使う本文の複製は、めくる2ページの分だけ作る（長い話で本文全体を複製すると重いため。長文対応）。
  // 複製の最初の段落が元と同じ高さから始まるよう、上に空きを入れる。そうすれば段組みの区切りも元と同じになる。
  // 戻り値の base は「複製の0ページ目が元の何ページ目か」
  function firstRectOf(el) {
    var rs = el.getClientRects();
    return rs.length ? rs[0] : el.getBoundingClientRect();
  }
  function lastRectOf(el) {
    var rs = el.getClientRects();
    return rs.length ? rs[rs.length - 1] : el.getBoundingClientRect();
  }

  function partialClone(low, high) {
    // 検索の印（.search-hit）は本文ではないので除く
    var kids = Array.prototype.filter.call(content.children, function (el) { return !el.classList.contains("search-hit"); });
    var n = kids.length;
    // 始まり：low ページの頭を含む部品（終わりが low ページ以降にある最初の部品）
    var lo = 0, hi = n - 1, from = n - 1;
    while (lo <= hi) {
      var mid = (lo + hi) >> 1;
      if (pageOfX(lastRectOf(kids[mid]).left + 1) >= low) { from = mid; hi = mid - 1; } else lo = mid + 1;
    }
    // 終わり：high ページより後から始まる最初の部品の手前
    lo = from; hi = n - 1; var to = n - 1;
    while (lo <= hi) {
      var m2 = (lo + hi) >> 1;
      if (pageOfX(firstRectOf(kids[m2]).left + 1) > high) { to = m2 - 1; hi = m2 - 1; } else lo = m2 + 1;
    }
    to = Math.max(from, to);

    var c = content.cloneNode(false);
    c.removeAttribute("id");
    c.style.transition = "none";
    var first = firstRectOf(kids[from]);
    var base = from === 0 ? 0 : pageOfX(first.left + 1);
    if (from > 0) {
      var top = content.getBoundingClientRect().top;
      var mt = parseFloat(getComputedStyle(kids[from]).marginTop) || 0;
      var gap = first.top - top - mt;
      if (gap > 0.5) {
        var spacer = document.createElement("div");
        spacer.style.height = gap + "px";
        spacer.style.margin = "0";
        c.appendChild(spacer);
      }
    }
    for (var i = from; i <= to; i++) {
      var k = kids[i].cloneNode(true);
      if (k.id) k.removeAttribute("id");
      c.appendChild(k);
    }
    return { el: c, base: base };
  }

  var cloneBase = 0;
  function invalidateClones() {
    if (cloneLeft) cloneLeft.remove();
    if (cloneFlap) cloneFlap.remove();
    cloneLeft = cloneFlap = null;
  }

  // めくるたびに、めくる2ページ分の複製を作り直す
  function prepareClones(low, high) {
    buildTurnLayers();
    invalidateClones();
    var pc = partialClone(low, high);
    cloneBase = pc.base;
    cloneLeft = pc.el;
    left.page.appendChild(cloneLeft);
    cloneFlap = pc.el.cloneNode(true);
    flapClip.page.insertBefore(cloneFlap, flapClip.page.firstChild);
  }

  function showPage(el, index) {
    el.style.transition = "none";
    el.style.transform = "translate3d(" + (-index * pageWidth()) + "px,0,0)";
  }

  function easeInOut(x) {
    return x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2;
  }

  // 画面の大きさに合わせて、めくり用の要素の寸法を決める（めくり開始時に1回）
  function sizeTurnLayers() {
    // 基準点は画面の中心近くにあるので、箱は画面の対角線の2倍強あれば足りる（大きすぎると実機で描画されないことがある）
    var W = pageWidth(), H = pageHeight(), big = Math.ceil(2.2 * Math.sqrt(W * W + H * H));
    [left, flapClip].forEach(function (hp) {
      hp.box.style.width = big + "px";
      hp.box.style.height = big + "px";
      hp.box.style.top = (-big / 2) + "px";
      // 箱の縁（左側の箱なら右端、右側の箱なら左端）の真ん中が、画面の (0, 0) に来るように置く
      if (hp.side === "left") {
        hp.box.style.left = (-big) + "px";
        hp.box.style.transformOrigin = big + "px " + (big / 2) + "px";
        hp.inner.style.left = big + "px";
      } else {
        // めくれた紙は折り目より 2px 先まで描き、折り目に白い隙間（すじ）が出ないようにする
        hp.box.style.left = "-2px";
        hp.box.style.transformOrigin = "2px " + (big / 2) + "px";
        hp.inner.style.left = "2px";
      }
      hp.inner.style.top = (big / 2) + "px";
      hp.page.style.width = W + "px";
      hp.page.style.height = H + "px";
    });
    [shadeHigh, shadeFlap].forEach(function (el) {
      el.style.width = (0.45 * W) + "px";
      el.style.height = big + "px";
      el.style.top = (-big / 2) + "px";
      el.style.transformOrigin = "0px " + (big / 2) + "px";
    });
    // めくれた紙の陰りは、折り目より先に描いた 2px の部分も覆う
    shadeFlap.style.left = "-3px";
    shadeFlap.style.transformOrigin = "3px " + (big / 2) + "px";
  }

  // 折り目（点 (xt, 0) を通り、縦から phi だけ傾いた直線）で切り抜く
  // 折り目上の点 (px, py) を基準にする。基準点は画面の近くに取る
  // （画面から遠い点を基準にすると、回転した箱が画面を覆いきれずに欠ける）
  function placeHalfPlane(hp, px, py, phi) {
    hp.box.style.transform = "translate3d(" + px + "px," + py + "px,0) rotate(" + phi + "rad)";
    hp.inner.style.transform = "rotate(" + (-phi) + "rad) translate3d(" + (-px) + "px," + (-py) + "px,0)";
  }

  // 折り目の直線（点 (xt, 0) を通り、縦から phi だけ傾いた線）でページを折り返して描く。
  // 直線の右側（ページの右端がある側）がめくれ上がる。lift は影の濃さ（0〜1）。
  function renderFold(xt, phi, lift) {
    var W = pageWidth(), H = pageHeight();
    // 折り目の上で、画面の中心にいちばん近い点を基準点にする
    var ux = -Math.sin(phi), uy = Math.cos(phi);
    var k = (W / 2 - xt) * ux + (H / 2) * uy;
    var px = xt + ux * k, py = uy * k;
    placeHalfPlane(left, px, py, phi);
    placeHalfPlane(flapClip, px, py, phi);

    // 折り目の直線で鏡写しにする行列（transform-origin は 0 0）
    var dx = -Math.sin(phi), dy = Math.cos(phi); // 直線の向き（下向き）
    var a = 2 * dx * dx - 1, b = 2 * dx * dy, d = 2 * dy * dy - 1;
    var e = xt * (1 - a), g = -b * xt;
    flapOuter.style.transform = "matrix(" + a + "," + b + "," + b + "," + d + "," + e + "," + g + ")";

    // 折り目に沿った影
    var shadeT = "translate3d(" + px + "px," + py + "px,0) rotate(" + phi + "rad)";
    shadeHigh.style.transform = shadeT;
    shadeFlap.style.transform = shadeT;
    shadeHigh.style.opacity = String(0.2 + 0.8 * lift);
    shadeFlap.style.opacity = String(0.4 + 0.6 * lift);

    // めくれた紙の縁（ページの右端・上端・下端のうち、めくれている部分）が下のページに落とす影
    placeFlapShadow(xt, phi, a, b, d, lift);
  }

  // めくれた紙が下のページに落とす影。紙の形（ページの長方形のうち折り目より右の部分を
  // 折り目で裏返した多角形）をぼかして描くので、角の部分でも影が途切れない。
  function placeFlapShadow(xt, phi, a, b, d, lift) {
    var W = pageWidth(), H = pageHeight();
    var nx = Math.cos(phi), ny = Math.sin(phi);           // 折り目からめくれる側への向き
    var side = function (p) { return (p.x - xt) * nx + p.y * ny; };
    // ページの長方形を、折り目のめくれる側だけに切り詰める（多角形の切り取り）
    var rect = [{ x: 0, y: 0 }, { x: W, y: 0 }, { x: W, y: H }, { x: 0, y: H }];
    var poly = [];
    for (var i = 0; i < rect.length; i++) {
      var P = rect[i], Q = rect[(i + 1) % rect.length];
      var sp = side(P), sq = side(Q);
      if (sp >= 0) poly.push(P);
      if ((sp >= 0) !== (sq >= 0)) {
        var k = sp / (sp - sq);
        poly.push({ x: P.x + (Q.x - P.x) * k, y: P.y + (Q.y - P.y) * k });
      }
    }
    if (poly.length < 3 || lift <= 0) { flapShadow.style.opacity = "0"; return; }
    // 折り目で裏返す
    var pts = poly.map(function (p) {
      var px = p.x - xt, py = p.y;
      return (xt + a * px + b * py).toFixed(1) + "px " + (b * px + d * py).toFixed(1) + "px";
    });
    flapShadowShape.style.clipPath = "polygon(" + pts.join(",") + ")";
    flapShadow.style.opacity = String(lift);
  }

  // タップでめくるときの動き。t = 0：めくり始め（折り目は右端）、t = 1：めくり終わり（折り目は左端）
  function drawFold(t) {
    var W = pageWidth(), H = pageHeight();
    var lift = Math.sin(Math.PI * t);          // 途中ほど大きく傾き、影も濃くなる
    var f = W * (1 - t);                        // 折り目の中心
    var slant = 0.14 * W * lift;                // 上側が右に倒れた斜めの折り目
    var xt = f + slant, xb = f - slant;         // 折り目の上端・下端の x
    renderFold(xt, Math.atan2(xt - xb, H), lift);
  }

  // 指でつまんだ角の位置からめくる。corner = めくる前の角の位置、p = 角の今の位置。
  // 折り目は「corner と p を結ぶ線分の垂直二等分線」になる（紙を折ったときの幾何）。
  function drawFoldAt(corner, p) {
    var W = pageWidth(), H = pageHeight();
    var vx = p.x - corner.x, vy = p.y - corner.y;
    var dist = Math.sqrt(vx * vx + vy * vy);
    if (dist < 0.5) { renderFold(W + 50, 0, 0); return; }
    var mx = (corner.x + p.x) / 2, my = (corner.y + p.y) / 2;
    // 折り目の向き u は (vx, vy) に垂直。下向き（u.y > 0）にそろえる
    var ux = -vy / dist, uy = vx / dist;
    if (uy < 0) { ux = -ux; uy = -uy; }
    if (uy < 0.05) uy = 0.05; // ほぼ水平になるのを防ぐ
    var xt = mx - ux / uy * my;                 // 直線が y = 0 と交わる x
    var phi = Math.atan2(-ux, uy);
    // 影の濃さ：少しめくっただけでもはっきり付け、めくり切る直前に薄くする
    // （sin で決めると、角を少しめくったときに影がほとんど見えなかった）
    var lift = Math.min(1, dist / (0.25 * W)) * Math.min(1, (2 * W - dist) / (0.3 * W));
    lift = Math.max(0, lift);
    renderFold(xt, phi, lift);
  }

  function endTurn() {
    if (!turning) return;
    cancelAnimationFrame(turning.raf);
    var done = turning.done;
    turning = null;
    if (turnRoot) turnRoot.style.visibility = "hidden";
    done();
  }

  // めくり用の層を準備する（番号の小さいページを上、大きいページを下に敷く）
  function beginTurn(low, high) {
    endTurn();
    prepareClones(low, high);
    sizeTurnLayers();
    showPage(content, high);      // 下に敷くページ（始まりの時点では left に隠れて見えない）
    showPage(cloneLeft, low - cloneBase);
    showPage(cloneFlap, low - cloneBase);
  }

  function foldTurn(from, to) {
    var forward = to > from;
    var low = Math.min(from, to), high = Math.max(from, to);
    beginTurn(low, high);

    turning = {
      raf: 0,
      done: function () { showPage(content, state.page); }
    };
    drawFold(forward ? 0 : 1);
    turnRoot.style.visibility = "visible";

    // 複製の描画が間に合うよう、2フレーム待ってから動かし始める
    var warm = 2, start = null;
    var step = function (now) {
      if (!turning) return;
      if (warm > 0) { warm--; turning.raf = requestAnimationFrame(step); return; }
      if (start === null) start = now;
      var p = Math.min(1, (now - start) / FOLD_MS);
      var e = easeInOut(p);
      drawFold(forward ? e : 1 - e);
      if (p < 1) turning.raf = requestAnimationFrame(step);
      else endTurn();
    };
    turning.raf = requestAnimationFrame(step);
  }


  // ---------------------------------------------------------------- スワイプでめくる（READ-30）
  //
  // ページの端を指でつまんで、指に合わせて少しずつめくる。指を離すと、
  // 半分以上めくれていたか、速く払っていればめくり切り、そうでなければ元に戻る。
  //   画面の上 1/3 から始めたとき：右上の角をめくる
  //   下 1/3 から始めたとき      ：右下の角をめくる
  //   真ん中から始めたとき        ：縦の折り目でめくる
  // 前のページへ戻るときは、左にめくれていたページを右へ戻すように動かす。
  // （タップの判定は Kotlin 側。ここでは一定以上動いたときだけ扱う）

  var DRAG_START_PX = 12;
  var SETTLE_MS = 260;
  var drag = null;      // 指の情報 { id, x0, y0, started, ... }
  var settling = 0;     // 指を離した後の動きの requestAnimationFrame

  function swipeEnabled() {
    return state.swipe && state.mode === "paged" && !!state.doc;
  }

  // 背表紙（左端）につながった紙なので、角は背表紙から紙の幅より遠くへは行けない
  function constrainCorner(corner, p) {
    var W = pageWidth();
    var sx = 0, sy = corner.y;                  // 背表紙側の、角と同じ高さの点
    var vx = p.x - sx, vy = p.y - sy, len = Math.sqrt(vx * vx + vy * vy);
    if (len > W) { p = { x: sx + vx / len * W, y: sy + vy / len * W }; }
    return p;
  }

  function cornerFor(y0) {
    var W = pageWidth(), H = pageHeight();
    if (y0 < H / 3) return { x: W, y: 0, edge: false };
    if (y0 > H * 2 / 3) return { x: W, y: H, edge: false };
    return { x: W, y: y0, edge: true };
  }

  // 指の位置から、角の今の位置を求める
  function cornerPosition(ev) {
    var W = pageWidth();
    var c = drag.corner;
    var ddx = ev.clientX - drag.x0, ddy = c.edge ? 0 : ev.clientY - drag.y0;
    var p = drag.forward
      ? { x: c.x + ddx, y: c.y + ddy }
      : { x: -W + 2 * ddx, y: c.y + ddy };      // 戻るときは、左にめくれた状態から引き戻す
    if (p.x > c.x) p.x = c.x;
    return constrainCorner(c, p);
  }

  function animateCorner(from, to, done) {
    var start = null;
    var step = function (now) {
      if (start === null) start = now;
      var k = Math.min(1, (now - start) / SETTLE_MS);
      var e = 1 - Math.pow(1 - k, 3);
      drawFoldAt(drag.corner, { x: from.x + (to.x - from.x) * e, y: from.y + (to.y - from.y) * e });
      if (k < 1) settling = requestAnimationFrame(step);
      else { settling = 0; done(); }
    };
    settling = requestAnimationFrame(step);
  }

  function finishDrag(completed) {
    var d = drag;
    drag = null;
    if (turnRoot) turnRoot.style.visibility = "hidden";
    if (completed) {
      state.page = d.forward ? d.high : d.low;
      showPage(content, state.page);
      state.pos = getCurrentPosition();
      report();
    } else {
      showPage(content, state.page);
    }
  }

  document.addEventListener("pointerdown", function (ev) {
    if (!swipeEnabled() || settling || drag) return;
    drag = { id: ev.pointerId, x0: ev.clientX, y0: ev.clientY, started: false, lastX: ev.clientX, lastT: ev.timeStamp, vx: 0 };
  });

  document.addEventListener("pointermove", function (ev) {
    if (!drag || ev.pointerId !== drag.id) return;
    if (!drag.started) {
      var dx = ev.clientX - drag.x0;
      if (Math.abs(dx) < DRAG_START_PX) return;
      var forward = dx < 0;
      if ((forward && state.page >= state.totalPages - 1) || (!forward && state.page <= 0)) {
        // 話の端：めくる代わりに「引っ張って次（前）の話へ」
        drag.started = true;
        drag.edge = forward ? "end" : "start";
      }
    }
    if (drag.edge) {
      drag.edgeDist = Math.max(0, Math.abs(ev.clientX - drag.x0) - DRAG_START_PX);
      disarm();
      // 引っ張り始めは指にほぼそのままついてきて、引くほど重くなる
      var dd = drag.edgeDist;
      var sh = dd <= 120 ? dd * 0.9 : 108 + rubber(dd - 120, 90);
      placeEdge(drag.edge, sh, dd >= PULL_TRIGGER, false);
      return;
    }
    if (!drag.started) {
      var forward = ev.clientX - drag.x0 < 0;
      drag.started = true;
      drag.forward = forward;
      drag.low = forward ? state.page : state.page - 1;
      drag.high = forward ? state.page + 1 : state.page;
      drag.corner = cornerFor(drag.y0);
      beginTurn(drag.low, drag.high);
      drawFoldAt(drag.corner, cornerPosition(ev));
      turnRoot.style.visibility = "visible";
    }
    var dt = ev.timeStamp - drag.lastT;
    if (dt > 0) drag.vx = 0.7 * drag.vx + 0.3 * ((ev.clientX - drag.lastX) / dt);
    drag.lastX = ev.clientX;
    drag.lastT = ev.timeStamp;
    drag.p = cornerPosition(ev);
    drawFoldAt(drag.corner, drag.p);
  });

  function onPointerEnd(ev) {
    if (!drag || ev.pointerId !== drag.id) return;
    if (!drag.started) { drag = null; return; }
    if (drag.edge) {
      var e = drag;
      drag = null;
      if (!(e.edgeDist >= PULL_TRIGGER && goEdge(e.edge))) releaseEdge(e.edge);
      return;
    }
    var W = pageWidth();
    var p = drag.p || drag.corner;
    var flung = Math.abs(drag.vx) > 0.4;          // px/ms
    var turned;                                   // めくり切った状態（角が左端の向こう）にするか
    if (drag.forward) turned = flung ? drag.vx < 0 : p.x < W * 0.4;
    else turned = !(flung ? drag.vx > 0 : p.x > -W * 0.4);
    var target = turned ? { x: -W, y: drag.corner.y } : { x: drag.corner.x, y: drag.corner.y };
    // 戻るとき：「めくれた状態」が元のページ、「平らな状態」が前のページ
    var completed = drag.forward ? turned : !turned;
    animateCorner(p, target, function () { finishDrag(completed); });
  }
  document.addEventListener("pointerup", onPointerEnd);
  document.addEventListener("pointercancel", onPointerEnd);

  function setSwipeEnabled(on) {
    state.swipe = !!on;
  }


  // ---------------------------------------------------------------- 話の切り替え（引っ張る・2回タップ）
  //
  // 話の最初・最後で、うっかり次の話に移らないようにする。
  //   スクロール：底（天井）に着いてから、さらに引っ張って離す
  //   スワイプ  ：最後（最初）のページで、さらに横に引っ張って離す
  //   タップ    ：最後（最初）のページで、もう一度タップする
  // 引っ張ると本文そのものがずれ、空いた所に「矢印＋移る先の話のタイトル」が見える。
  // 案内は背景と同じ色の上に本文の色で薄く書き、十分引っ張ると濃くなる。

  var PULL_TRIGGER = 110;                 // これ以上引っ張って離すと切り替える（px）
  var ARM_MS = 2500;                      // タップ操作で「もう一度」を受け付ける時間
  var edgeLabels = { prev: null, next: null }; // 前後の話のタイトル。null = その方向に話がない
  // 話がない方向（最初の話の前・最終話の次）でしたいこと。null = 何もしない
  // { glyph: "⟳", text: "更新を確認" } のように、矢印の代わりの印と案内の言葉を持つ
  var edgeAction = null;
  var ind = null, indArrow = null, indTitle = null;
  var armed = null, armTimer = 0;
  var scrollPull = null;                  // スクロールモードの引っ張り

  function setEdgeLabels(prev, next, action) {
    edgeLabels.prev = prev == null ? null : String(prev);
    edgeLabels.next = next == null ? null : String(next);
    if (action !== undefined) {
      if (typeof action === "string") action = JSON.parse(action);
      edgeAction = action && action.text ? { glyph: String(action.glyph || ""), text: String(action.text) } : null;
    }
  }

  function edgeTitle(direction) { return direction === "end" ? edgeLabels.next : edgeLabels.prev; }
  // その方向に進めるか（次の話があるか、話がなくても決めた動作があるか）
  function edgeUsable(direction) { return edgeTitle(direction) != null || edgeAction != null; }

  function buildIndicator() {
    if (ind) return;
    ind = document.createElement("div");
    ind.id = "edge-ind";
    indArrow = document.createElement("div");
    indArrow.className = "edge-arrow";
    indTitle = document.createElement("div");
    indTitle.className = "edge-title";
    ind.appendChild(indArrow);
    ind.appendChild(indTitle);
    document.body.appendChild(ind);
  }

  // 引っ張った距離 dist に対して、本文をどれだけずらすか（引くほど重くなる）
  function rubber(dist, max) {
    return max * (1 - Math.exp(-dist / max));
  }

  // 本文を shift px だけずらし、空いた所に案内を置く。animate = true なら滑らかに動かす
  function placeEdge(direction, shift, ready, animate) {
    buildIndicator();
    var W = pageWidth(), H = pageHeight();
    var scroll = state.mode === "scroll";
    var title = edgeTitle(direction);
    var action = title == null ? edgeAction : null;
    var dur = animate ? "transform 220ms ease-out, opacity 220ms" : "none";
    content.style.transition = dur;
    ind.style.transition = dur;

    // 本文をずらす（ページ送りのときは、今のページの位置に足す）
    var sign = direction === "end" ? -1 : 1;
    if (scroll) {
      content.style.transform = shift ? "translate3d(0," + (sign * shift) + "px,0)" : "";
    } else {
      content.style.transform = "translate3d(" + (-state.page * W + sign * shift) + "px,0,0)";
    }

    // 案内：矢印 ＋ 移る先の話のタイトル（話がなければタイトルだけ薄く）
    // 話がなく動作が決めてあるとき：矢印の代わりに動作の印、タイトルの代わりに「最終話 ・ 更新を確認」など
    indArrow.textContent = action ? action.glyph
      : scroll ? (direction === "end" ? "↓" : "↑") : (direction === "end" ? "→" : "←");
    var endName = direction === "end" ? "最終話" : "最初の話";
    indTitle.textContent = title != null ? title : action ? endName + (scroll ? "　·　" : "\n") + action.text : endName;
    if (noticeText != null) indTitle.textContent = noticeText;
    ind.className = (scroll ? "vertical " : "horizontal ") + (action ? "action " : "") +
      ((title != null || action) && ready ? "ready" : "") + (noticeText != null ? " notice" : "");
    if (scroll) {
      ind.style.width = W + "px";
      ind.style.transform = direction === "end"
        ? "translate3d(0," + (H - shift) + "px,0)"
        : "translate3d(0," + (shift - ind.offsetHeight) + "px,0)";
    } else {
      // 本文の端にくっついて、画面の外から出てくるように見せる（狭いうちは一部が画面の外）
      var iw = Math.max(shift - 16, 84);
      ind.style.width = iw + "px";
      ind.style.transform = direction === "end"
        ? "translate3d(" + (W - shift + 8) + "px," + (H / 2 - ind.offsetHeight / 2) + "px,0)"
        : "translate3d(" + (shift - iw - 8) + "px," + (H / 2 - ind.offsetHeight / 2) + "px,0)";
    }
    ind.style.opacity = shift > 4 ? "1" : "0";
  }

  // 引っ張りをやめて元に戻す
  function releaseEdge(direction) {
    if (!ind) return;
    placeEdge(direction, 0, false, true);
    setTimeout(function () {
      if (!scrollPull && !armed && !(drag && drag.edge) && noticeText == null) {
        content.style.transition = "";
        if (state.mode === "scroll") content.style.transform = "";
      }
    }, 240);
  }

  function goEdge(direction) {
    disarm();
    if (!edgeUsable(direction)) { releaseEdge(direction); return false; }
    if (edgeTitle(direction) == null) {
      // 動作のとき：本文はずらしたまま待つ（結果は showNotice で出る）
      noticeDir = direction;
      placeEdge(direction, edgeHoldShift(), true, true);
    }
    bridge.onEdge(direction);
    return true;
  }

  function disarm() {
    armed = null;
    clearTimeout(armTimer);
  }

  // タップ操作で話の端に達したとき。1回目は本文を少しずらして案内を見せ、2回目で移る
  function tapAtEdge(direction) {
    if (armed === direction && edgeUsable(direction)) { goEdge(direction); return; }
    armed = direction;
    placeEdge(direction, edgeHoldShift(), true, true);
    clearTimeout(armTimer);
    armTimer = setTimeout(function () { armed = null; releaseEdge(direction); }, ARM_MS);
  }

  // 動作（更新を確認など）の途中経過・結果を、案内と同じ場所に出す。
  // 本文をずらしたまま矢印の位置に印、タイトルの位置に知らせを書き、ms 経ったら元に戻す（0 = 出したまま）。
  // text が空なら、すぐ元に戻す
  var noticeText = null, noticeDir = "end", noticeTimer = 0;
  function showNotice(text, ms, direction) {
    clearTimeout(noticeTimer);
    disarm();
    if (direction) noticeDir = direction;
    if (!text) { noticeText = null; releaseEdge(noticeDir); return; }
    noticeText = String(text);
    placeEdge(noticeDir, edgeHoldShift(), true, true);
    // 知らせが来ないまま止まらないよう、出したままでも 10 秒で戻す
    noticeTimer = setTimeout(function () { noticeText = null; releaseEdge(noticeDir); }, ms > 0 ? ms : 10000);
  }
  function edgeHoldShift() { return state.mode === "scroll" ? 72 : Math.min(150, pageWidth() * 0.38); }

  // スクロールモード：底・天井でさらに引っ張る
  function atScrollBottom() { return viewport.scrollTop + pageHeight() >= viewport.scrollHeight - 1; }
  function atScrollTop() { return viewport.scrollTop <= 0; }

  viewport.addEventListener("touchstart", function (ev) {
    if (state.mode !== "scroll" || ev.touches.length !== 1) return;
    scrollPull = { y: ev.touches[0].clientY, base: null, direction: null, dist: 0 };
  }, { passive: true });

  viewport.addEventListener("touchmove", function (ev) {
    if (!scrollPull || state.mode !== "scroll") return;
    var y = ev.touches[0].clientY;
    if (scrollPull.direction === null) {
      // 底（天井）に着いた瞬間の指の位置を基準にする
      if (y < scrollPull.y && atScrollBottom()) { scrollPull.direction = "end"; scrollPull.base = y; }
      else if (y > scrollPull.y && atScrollTop()) { scrollPull.direction = "start"; scrollPull.base = y; }
      scrollPull.y = y;
      return;
    }
    var dist = scrollPull.direction === "end" ? scrollPull.base - y : y - scrollPull.base;
    scrollPull.dist = Math.max(0, dist);
    disarm();
    placeEdge(scrollPull.direction, rubber(scrollPull.dist, 140), scrollPull.dist >= PULL_TRIGGER, false);
  }, { passive: true });

  function endScrollPull() {
    if (!scrollPull) return;
    var p = scrollPull;
    scrollPull = null;
    if (!p.direction) return;
    if (p.dist >= PULL_TRIGGER && goEdge(p.direction)) return;
    releaseEdge(p.direction);
  }
  viewport.addEventListener("touchend", endScrollPull);
  viewport.addEventListener("touchcancel", endScrollPull);

  // ---------------------------------------------------------------- 操作

  function next() {
    if (state.mode === "scroll") {
      if (atScrollBottom()) { tapAtEdge("end"); return false; }
      disarm();
      viewport.scrollTop += pageHeight() - lineStep();
      return true;
    }
    if (state.page >= state.totalPages - 1) { tapAtEdge("end"); return false; }
    if (armed) { disarm(); releaseEdge("start"); }
    goToPage(state.page + 1, true);
    return true;
  }

  function prev() {
    if (state.mode === "scroll") {
      if (atScrollTop()) { tapAtEdge("start"); return false; }
      disarm();
      viewport.scrollTop -= pageHeight() - lineStep();
      return true;
    }
    if (state.page <= 0) { tapAtEdge("start"); return false; }
    if (armed) { disarm(); releaseEdge("end"); }
    goToPage(state.page - 1, true);
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
    endTurn();
    clearHighlight();
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

  // 話を切り替える（WebView は作り直さない）。direction：1 = 次の話、-1 = 前の話、0 = その他
  // 今の本文を進む方向へ少し流しながら消し、新しい本文を反対側から流し込む
  var SWITCH_OUT_MS = 140, SWITCH_IN_MS = 240;
  var switchTimer = 0;
  function switchChapter(doc, position, direction) {
    endTurn();
    stopAuto(false);
    hits = [];
    disarm();
    noticeText = null;
    clearTimeout(noticeTimer);
    if (ind) ind.style.opacity = "0";
    var d = direction > 0 ? 1 : direction < 0 ? -1 : 0;
    var dx = 28 * d;
    // スクロール操作では縦に流す（次の話は下から上へ）。ページ送りでは横に流す
    var vertical = state.mode === "scroll";
    var shiftBy = function (px) { return vertical ? "translate3d(0," + px + "px,0)" : "translate3d(" + px + "px,0,0)"; };
    if (vertical) dx = 40 * d;
    viewport.style.transition = "opacity " + SWITCH_OUT_MS + "ms ease-in, transform " + SWITCH_OUT_MS + "ms ease-in";
    viewport.style.opacity = "0";
    viewport.style.transform = shiftBy(-dx);
    clearTimeout(switchTimer);
    switchTimer = setTimeout(function () {
      state.doc = typeof doc === "string" ? JSON.parse(doc) : doc;
      state.pos = position || { block: 0, offset: 0 };
      content.style.transition = "";
      if (state.mode === "scroll") content.style.transform = "";
      render(state.doc);
      whenFontsReady(function () {
        waitForSize(function () {
          relayout();
          bridge.onReady(state.totalPages);
          viewport.style.transition = "none";
          viewport.style.transform = shiftBy(dx);
          void viewport.offsetWidth; // 位置を確定させてからアニメーションを始める
          viewport.style.transition = "opacity " + SWITCH_IN_MS + "ms ease-out, transform " + SWITCH_IN_MS + "ms ease-out";
          viewport.style.opacity = "1";
          viewport.style.transform = "";
        });
      });
    }, SWITCH_OUT_MS);
  }

  function applySettings(settings) {
    var before = state.settings.showImages, beforeTitle = state.settings.showTitle;
    state.settings = Object.assign({}, state.settings, settings);
    applyCssVariables();
    // 挿絵・タイトルの表示・非表示が変わったら、本文を描き直す（読書位置は state.pos で保たれる）
    if (state.doc && (before !== state.settings.showImages || beforeTitle !== state.settings.showTitle)) render(state.doc);
    whenFontsReady(relayout);
  }

  function setMode(mode) {
    if (mode !== "paged" && mode !== "scroll") return;
    state.mode = mode;
    endTurn();
    stopAuto(true); // 操作を変えたら自動送りは止める（ボタンの表示も戻してもらう）
    invalidateClones(); // スクロールモードでは複製を使わないので捨てる
    applyCssVariables();
    whenFontsReady(relayout);
  }

  var resizeTimer = null;
  window.addEventListener("resize", function () {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(function () { if (state.doc) relayout(); }, 100);
  });

  // 本文内の位置の一覧が、それぞれ何ページ目（0始まり）にあるかを返す（栞の表示に使う）
  function pagesOf(list) {
    if (typeof list === "string") list = JSON.parse(list);
    return JSON.stringify((list || []).map(function (p) {
      if (!state.blocks.length) return 0;
      var b = Math.max(0, Math.min(p.block | 0, state.blocks.length - 1));
      var r = charRect(b, Math.max(0, p.offset | 0));
      if (state.mode === "scroll") return Math.floor((r.top + viewport.scrollTop) / pageHeight());
      return pageOfX(r.left + 1);
    }));
  }

  // ---------------------------------------------------------------- 本文内検索（READ-15）
  //
  // 今の話の中を探す。ルビの読みは探さず、親文字だけを探す（読書位置の数え方と同じ）。
  // 英字は大文字・小文字を区別しない。見つけた所は、本文の上に薄い色の帯を重ねて示す

  var hits = [], hitLength = 0;

  function blockText(i) {
    var t = textNodesOf(i);
    var s = "";
    for (var k = 0; k < t.nodes.length; k++) s += t.nodes[k].node.data;
    return s;
  }

  function search(q) {
    clearHighlight();
    hits = [];
    hitLength = q ? String(q).length : 0;
    if (!q) return "[]";
    var needle = String(q).toLowerCase();
    for (var i = 0; i < state.blocks.length && hits.length < 1000; i++) {
      var text = blockText(i).toLowerCase();
      var from = 0, at;
      while ((at = text.indexOf(needle, from)) >= 0 && hits.length < 1000) {
        hits.push({ block: i, offset: at });
        from = at + Math.max(1, needle.length);
      }
    }
    return JSON.stringify(hits);
  }

  function clearHighlight() {
    var old = content.querySelectorAll(".search-hit");
    for (var i = 0; i < old.length; i++) old[i].remove();
  }

  // k 番目に見つけた所へ移って、帯を重ねる
  function showHit(k) {
    var h = hits[k];
    if (!h) return;
    stopAuto();
    goToPosition(h.block, h.offset, false);
    clearHighlight();
    var cr = content.getBoundingClientRect();
    var box = null;
    var flush = function () {
      if (!box) return;
      var d = document.createElement("div");
      d.className = "search-hit";
      d.style.left = box.left + "px";
      d.style.top = box.top + "px";
      d.style.width = box.width + "px";
      d.style.height = box.height + "px";
      content.appendChild(d);
      box = null;
    };
    for (var off = h.offset; off < h.offset + hitLength; off++) {
      var r = charRect(h.block, off);
      var x = r.left - cr.left, y = r.top - cr.top;
      // 同じ行で続いている文字は1本の帯にまとめる
      if (box && Math.abs(box.top - y) < 2 && Math.abs(box.left + box.width - x) < 3) {
        box.width = x + r.width - box.left;
      } else {
        flush();
        box = { left: x, top: y, width: r.width, height: r.height };
      }
    }
    flush();
  }

  // ---------------------------------------------------------------- 自動スクロール（READ-12）
  //
  // 一定の速さで上へ流す。指で触れている間は止まり、離して少ししたら再開する。
  // ページ送りの設定のときは、Kotlin 側が動いている間だけスクロール操作に切り替えてから呼ぶ
  // （ページ送りのまま呼ばれたときは、一定の間隔で次のページへめくる）。
  // 話の終わりに着いたら止めて、Kotlin に知らせる（onAutoStop）。speed は 1（遅い）〜10（速い）

  var auto = null;

  function startAuto(speed) {
    stopAuto();
    speed = Math.max(1, Math.min(10, speed | 0 || 5));
    disarm();
    if (state.mode === "scroll") {
      // 1 で毎秒 約0.15行、10 で 約4.2行（段ごとに約1.45倍）
      auto = { kind: "scroll", pxPerSec: lineStep() * 0.15 * Math.pow(1.45, speed - 1), last: null, carry: 0, pausedUntil: 0, raf: 0 };
      var step = function (now) {
        if (!auto || auto.kind !== "scroll") return;
        if (auto.last !== null && now >= auto.pausedUntil) {
          auto.carry += auto.pxPerSec * Math.min(0.1, (now - auto.last) / 1000);
          var px = Math.floor(auto.carry);
          if (px >= 1) { viewport.scrollTop += px; auto.carry -= px; }
          if (atScrollBottom()) { stopAuto(true); return; }
        }
        auto.last = now;
        auto.raf = requestAnimationFrame(step);
      };
      auto.raf = requestAnimationFrame(step);
    } else {
      auto = { kind: "paged", timer: 0 };
      var turn = function () {
        if (!auto || auto.kind !== "paged") return;
        if (state.page >= state.totalPages - 1) { stopAuto(true); return; }
        goToPage(state.page + 1, true);
        auto.timer = setTimeout(turn, (30 - 2.5 * speed) * 1000);
      };
      auto.timer = setTimeout(turn, (30 - 2.5 * speed) * 1000);
    }
  }

  // reachedEnd = true なら、話の終わりで止まったことを Kotlin に知らせる
  function stopAuto(reachedEnd) {
    if (!auto) return;
    if (auto.raf) cancelAnimationFrame(auto.raf);
    if (auto.timer) clearTimeout(auto.timer);
    auto = null;
    if (reachedEnd && bridge.onAutoStop) bridge.onAutoStop();
  }

  // スクロールの自動送り中に指で触れたら一時停止
  viewport.addEventListener("touchstart", function () { if (auto && auto.kind === "scroll") auto.pausedUntil = Infinity; }, { passive: true });
  var resumeAuto = function () { if (auto && auto.kind === "scroll") auto.pausedUntil = performance.now() + 1500; };
  viewport.addEventListener("touchend", resumeAuto, { passive: true });
  viewport.addEventListener("touchcancel", resumeAuto, { passive: true });

  window.Reader = {
    search: search,
    showHit: showHit,
    clearSearch: function () { hits = []; clearHighlight(); },
    startAuto: startAuto,
    stopAuto: function () { stopAuto(false); },
    switchChapter: switchChapter,
    setEdgeLabels: setEdgeLabels,
    showNotice: showNotice,
    pagesOf: pagesOf,
    setSwipeEnabled: setSwipeEnabled,
    load: load,
    applySettings: applySettings,
    setMode: setMode,
    next: next,
    prev: prev,
    goToPosition: function (b, o) { goToPosition(b, o, false); },
    goToPage: function (i) { goToPage(i, false); },
    getCurrentPosition: getCurrentPosition,
    relayout: relayout,
    _state: state
  };
})();
