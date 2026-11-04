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
    animation: "slide"   // "slide" | "fold" | "none"
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

  function cloneContent() {
    var c = content.cloneNode(true);
    c.removeAttribute("id");
    var marks = c.querySelectorAll("[id]");
    for (var i = 0; i < marks.length; i++) marks[i].removeAttribute("id");
    c.style.transition = "none";
    return c;
  }

  // 本文が変わったら複製を作り直す
  function invalidateClones() {
    if (cloneLeft) cloneLeft.remove();
    if (cloneFlap) cloneFlap.remove();
    cloneLeft = cloneFlap = null;
  }

  function ensureClones() {
    buildTurnLayers();
    if (cloneLeft) return;
    cloneLeft = cloneContent();
    left.page.appendChild(cloneLeft);
    cloneFlap = cloneContent();
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
    ensureClones();
    sizeTurnLayers();
    showPage(content, high);      // 下に敷くページ（始まりの時点では left に隠れて見えない）
    showPage(cloneLeft, low);
    showPage(cloneFlap, low);
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
      if (forward && state.page >= state.totalPages - 1) { bridge.onEdge("end"); drag = null; return; }
      if (!forward && state.page <= 0) { bridge.onEdge("start"); drag = null; return; }
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
    goToPage(state.page + 1, true);
    return true;
  }

  function prev() {
    if (state.mode === "scroll") {
      if (viewport.scrollTop <= 0) { bridge.onEdge("start"); return false; }
      viewport.scrollTop -= pageHeight() - lineStep();
      return true;
    }
    if (state.page <= 0) { bridge.onEdge("start"); return false; }
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
    endTurn();
    invalidateClones(); // スクロールモードでは複製を使わないので捨てる
    applyCssVariables();
    whenFontsReady(relayout);
  }

  var resizeTimer = null;
  window.addEventListener("resize", function () {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(function () { if (state.doc) relayout(); }, 100);
  });

  window.Reader = {
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
