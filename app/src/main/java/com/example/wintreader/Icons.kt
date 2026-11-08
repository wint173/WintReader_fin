package com.example.wintreader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * アプリで使う線画のアイコン（24×24）。アイコン用のライブラリは使わず、ここで描く。
 * 色は Icon(tint = …) で付ける。
 */
object WIcons {

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    /** 線で描く */
    private fun ImageVector.Builder.line(width: Float = 2f, d: PathBuilder.() -> Unit) {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = d,
        )
    }

    /** 塗りつぶして描く */
    private fun ImageVector.Builder.solid(d: PathBuilder.() -> Unit) {
        path(fill = SolidColor(Color.Black), pathBuilder = d)
    }

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + r, y1 = cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - r, y1 = cy)
        close()
    }

    /** 本棚へ戻る（左向きの山形） */
    val Back = icon("back") { line { moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f) } }

    /** 目次（点と横線のリスト） */
    val Toc = icon("toc") {
        solid { circle(5f, 6f, 1.4f); circle(5f, 12f, 1.4f); circle(5f, 18f, 1.4f) }
        line { moveTo(9f, 6f); lineTo(20f, 6f); moveTo(9f, 12f); lineTo(20f, 12f); moveTo(9f, 18f); lineTo(20f, 18f) }
    }

    /** 栞（リボン）。付いているときは塗りつぶし */
    val Bookmark = icon("bookmark") { line { moveTo(6f, 3f); lineTo(18f, 3f); lineTo(18f, 21f); lineTo(12f, 16f); lineTo(6f, 21f); close() } }
    val BookmarkFilled = icon("bookmark_filled") { solid { moveTo(6f, 3f); lineTo(18f, 3f); lineTo(18f, 21f); lineTo(12f, 16f); lineTo(6f, 21f); close() } }

    /** 元のサイトのページを開く（ルーペ） */
    val Search = icon("search") {
        line { circle(10.5f, 10.5f, 6.5f) }
        line { moveTo(15.5f, 15.5f); lineTo(21f, 21f) }
    }

    /** 表示設定（つまみの付いた3本の線） */
    val Tune = icon("tune") {
        line { moveTo(3f, 6f); lineTo(21f, 6f); moveTo(3f, 12f); lineTo(21f, 12f); moveTo(3f, 18f); lineTo(21f, 18f) }
        solid { circle(8f, 6f, 2.4f); circle(16f, 12f, 2.4f); circle(10f, 18f, 2.4f) }
    }

    /** 前の話・次の話（二重の山形） */
    val PrevChapter = icon("prev_chapter") {
        line { moveTo(12f, 5f); lineTo(5f, 12f); lineTo(12f, 19f); moveTo(19f, 5f); lineTo(12f, 12f); lineTo(19f, 19f) }
    }
    val NextChapter = icon("next_chapter") {
        line { moveTo(5f, 5f); lineTo(12f, 12f); lineTo(5f, 19f); moveTo(12f, 5f); lineTo(19f, 12f); lineTo(12f, 19f) }
    }

    /**
     * 更新確認（矢印の付いた円）。右から時計回りに 300° 回る弧の終わり（右上）に、
     * 弧の進む向き（接線）に向いた三角形の矢じりを付ける
     */
    val Refresh = icon("refresh") {
        line {
            moveTo(19f, 12f)
            arcTo(7f, 7f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 15.5f, y1 = 5.94f)
        }
        // 矢じりは塗りつぶしの三角形。底辺の中央が弧の終わりに来るようにして、弧の線とずれないようにする
        solid { moveTo(14f, 8.54f); lineTo(17f, 3.34f); lineTo(18.27f, 7.54f); close() }
    }

    /** 作品のページ（外へ出る矢印の付いた四角） */
    val OpenPage = icon("open_page") {
        line { moveTo(11f, 4f); lineTo(5f, 4f); lineTo(5f, 19f); lineTo(20f, 19f); lineTo(20f, 13f) }
        line { moveTo(14f, 4f); lineTo(20f, 4f); lineTo(20f, 10f); moveTo(20f, 4f); lineTo(11f, 13f) }
    }

    // ---------------------------------------------------------------- 表示設定のアイコン

    /** 操作：タップ（指先の円と中心の点） */
    val ModeTap = icon("mode_tap") {
        line { circle(12f, 12f, 7f) }
        solid { circle(12f, 12f, 2.5f) }
    }

    /** 操作：スワイプ（左右の矢印） */
    val ModeSwipe = icon("mode_swipe") {
        line { moveTo(3f, 12f); lineTo(21f, 12f); moveTo(7f, 8f); lineTo(3f, 12f); lineTo(7f, 16f); moveTo(17f, 8f); lineTo(21f, 12f); lineTo(17f, 16f) }
    }

    /** 操作：スクロール（上下の矢印） */
    val ModeScroll = icon("mode_scroll") {
        line { moveTo(12f, 3f); lineTo(12f, 21f); moveTo(8f, 7f); lineTo(12f, 3f); lineTo(16f, 7f); moveTo(8f, 17f); lineTo(12f, 21f); lineTo(16f, 17f) }
    }

    /** 左（右）をタップで次へ：ページと矢印 */
    val TapLeftNext = icon("tap_left_next") {
        line { moveTo(9f, 4f); lineTo(20f, 4f); lineTo(20f, 20f); lineTo(9f, 20f); close() }
        line { moveTo(7f, 12f); lineTo(2f, 12f); moveTo(4.5f, 9.5f); lineTo(2f, 12f); lineTo(4.5f, 14.5f) }
    }
    val TapRightNext = icon("tap_right_next") {
        line { moveTo(4f, 4f); lineTo(15f, 4f); lineTo(15f, 20f); lineTo(4f, 20f); close() }
        line { moveTo(17f, 12f); lineTo(22f, 12f); moveTo(19.5f, 9.5f); lineTo(22f, 12f); lineTo(19.5f, 14.5f) }
    }

    /** めくり：折り返し（角が折れたページ） */
    val AnimFold = icon("anim_fold") {
        line { moveTo(5f, 3f); lineTo(19f, 3f); lineTo(19f, 14f); lineTo(13f, 21f); lineTo(5f, 21f); close() }
        line { moveTo(19f, 14f); lineTo(13f, 14f); lineTo(13f, 21f) }
    }

    /** めくり：スライド（ずれた2枚のページ） */
    val AnimSlide = icon("anim_slide") {
        line { moveTo(3f, 5f); lineTo(13f, 5f); lineTo(13f, 19f); lineTo(3f, 19f); close() }
        line { moveTo(16f, 5f); lineTo(21f, 5f); lineTo(21f, 19f); lineTo(16f, 19f) }
    }

    /** めくり：なし（1枚のページ） */
    val AnimNone = icon("anim_none") {
        line { moveTo(6f, 3f); lineTo(18f, 3f); lineTo(18f, 21f); lineTo(6f, 21f); close() }
    }

    /** 挿絵：表示（絵の四角と山）・表示しない（斜線付き） */
    val Image = icon("image") {
        line { moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 19f); lineTo(3f, 19f); close() }
        line { moveTo(3f, 17f); lineTo(9f, 11f); lineTo(13f, 15f); lineTo(16f, 12f); lineTo(21f, 17f) }
        solid { circle(16f, 8.5f, 1.5f) }
    }
    val ImageOff = icon("image_off") {
        line { moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 19f); lineTo(3f, 19f); close() }
        line { moveTo(3f, 17f); lineTo(9f, 11f); lineTo(13f, 15f) }
        line { moveTo(2f, 3f); lineTo(22f, 21f) }
    }

    /** 文字の大きさ・行の高さ */
    val FontSize = icon("font_size") {
        line { moveTo(3f, 19f); lineTo(8f, 7f); lineTo(13f, 19f); moveTo(5f, 15f); lineTo(11f, 15f) }
        line(1.6f) { moveTo(14f, 19f); lineTo(17.5f, 11f); lineTo(21f, 19f); moveTo(15.3f, 16.2f); lineTo(19.7f, 16.2f) }
    }
    val LineHeight = icon("line_height") {
        line { moveTo(10f, 6f); lineTo(21f, 6f); moveTo(10f, 12f); lineTo(21f, 12f); moveTo(10f, 18f); lineTo(21f, 18f) }
        line { moveTo(5f, 4f); lineTo(5f, 20f); moveTo(3f, 6f); lineTo(5f, 4f); lineTo(7f, 6f); moveTo(3f, 18f); lineTo(5f, 20f); lineTo(7f, 18f) }
    }

    // ---------------------------------------------------------------- アプリの画面

    /** 設定（歯車：輪と8本の歯、中心の穴） */
    val Settings = icon("settings") {
        line { circle(12f, 12f, 6.5f) }
        line(2.6f) {
            moveTo(18.50f, 12.00f); lineTo(20.6f, 12.00f); moveTo(16.60f, 16.60f); lineTo(18.1f, 18.1f)
            moveTo(12.00f, 18.50f); lineTo(12.00f, 20.6f); moveTo(7.40f, 16.60f); lineTo(5.9f, 18.1f)
            moveTo(5.50f, 12.00f); lineTo(3.4f, 12.00f); moveTo(7.40f, 7.40f); lineTo(5.9f, 5.9f)
            moveTo(12.00f, 5.50f); lineTo(12.00f, 3.4f); moveTo(16.60f, 7.40f); lineTo(18.1f, 5.9f)
        }
        line { circle(12f, 12f, 2.5f) }
    }

    // ---------------------------------------------------------------- 読書画面の明るさ・向き

    /** 夜の配色（三日月）。夜の配色のときは塗りつぶし */
    val Moon = icon("moon") {
        line { moveTo(20f, 14.5f); arcTo(8.5f, 8.5f, 0f, true, true, 9.5f, 4f); arcTo(6.5f, 6.5f, 0f, false, false, 20f, 14.5f); close() }
    }
    val MoonFilled = icon("moon_filled") {
        solid { moveTo(20f, 14.5f); arcTo(8.5f, 8.5f, 0f, true, true, 9.5f, 4f); arcTo(6.5f, 6.5f, 0f, false, false, 20f, 14.5f); close() }
    }

    /** 明るさ（太陽：円と8本の光） */
    val Sun = icon("sun") {
        line { circle(12f, 12f, 4f) }
        line {
            moveTo(12f, 2.5f); lineTo(12f, 4.5f); moveTo(12f, 19.5f); lineTo(12f, 21.5f)
            moveTo(2.5f, 12f); lineTo(4.5f, 12f); moveTo(19.5f, 12f); lineTo(21.5f, 12f)
            moveTo(5.3f, 5.3f); lineTo(6.7f, 6.7f); moveTo(17.3f, 17.3f); lineTo(18.7f, 18.7f)
            moveTo(5.3f, 18.7f); lineTo(6.7f, 17.3f); moveTo(17.3f, 6.7f); lineTo(18.7f, 5.3f)
        }
    }

    /** 画面の向き：自動（端末と回る矢印）・縦・横 */
    val OrientAuto = icon("orient_auto") {
        line { moveTo(9f, 7f); lineTo(15f, 7f); lineTo(15f, 17f); lineTo(9f, 17f); close() }
        line { moveTo(3.5f, 12f); arcTo(8.5f, 8.5f, 0f, false, true, 9f, 4.1f); moveTo(7f, 2.5f); lineTo(9f, 4.1f); lineTo(7.4f, 6.1f) }
        line { moveTo(20.5f, 12f); arcTo(8.5f, 8.5f, 0f, false, true, 15f, 19.9f); moveTo(17f, 21.5f); lineTo(15f, 19.9f); lineTo(16.6f, 17.9f) }
    }
    val OrientPortrait = icon("orient_portrait") {
        line { moveTo(7f, 3f); lineTo(17f, 3f); lineTo(17f, 21f); lineTo(7f, 21f); close() }
        line { moveTo(11f, 18f); lineTo(13f, 18f) }
    }
    val OrientLandscape = icon("orient_landscape") {
        line { moveTo(3f, 7f); lineTo(21f, 7f); lineTo(21f, 17f); lineTo(3f, 17f); close() }
        line { moveTo(18f, 11f); lineTo(18f, 13f) }
    }

    /** 全話を保存（下向きの矢印と受け皿） */
    val Download = icon("download") {
        line { moveTo(12f, 3f); lineTo(12f, 15f); moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f) }
        line { moveTo(4f, 16f); lineTo(4f, 20f); lineTo(20f, 20f); lineTo(20f, 16f) }
    }

    /** 止める（2本の縦線） */
    val Pause = icon("pause") {
        line { moveTo(9f, 5f); lineTo(9f, 19f); moveTo(15f, 5f); lineTo(15f, 19f) }
    }

    // ---------------------------------------------------------------- 読書中の機能

    /** 元のサイトのページを開く（ブラウザ：地球儀） */
    val Globe = icon("globe") {
        line { circle(12f, 12f, 9f) }
        line {
            moveTo(3f, 12f); lineTo(21f, 12f)
            moveTo(12f, 3f); quadTo(7f, 12f, 12f, 21f); quadTo(17f, 12f, 12f, 3f)
            moveTo(4.6f, 7.5f); lineTo(19.4f, 7.5f); moveTo(4.6f, 16.5f); lineTo(19.4f, 16.5f)
        }
    }

    /** 自動スクロール：始める（再生の三角）・止める（四角） */
    val AutoPlay = icon("auto_play") {
        line { moveTo(8f, 5f); lineTo(19f, 12f); lineTo(8f, 19f); close() }
    }
    val AutoStop = icon("auto_stop") {
        solid { moveTo(7f, 7f); lineTo(17f, 7f); lineTo(17f, 17f); lineTo(7f, 17f); close() }
    }
    /** 自動スクロールの速さ（下向きの二重矢印） */
    val AutoSpeed = icon("auto_speed") {
        line { moveTo(6f, 5f); lineTo(12f, 11f); lineTo(18f, 5f); moveTo(6f, 13f); lineTo(12f, 19f); lineTo(18f, 13f) }
    }

    /** 検索結果の前・次（上向き・下向きの山形） */
    val ChevronUp = icon("chevron_up") { line { moveTo(6f, 15f); lineTo(12f, 9f); lineTo(18f, 15f) } }
    val ChevronDown = icon("chevron_down") { line { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) } }

    /** 閉じる（×） */
    /** 鉛筆（カスタムの色を作る） */
    val Pencil = icon("pencil") {
        line { moveTo(4f, 20f); lineTo(5f, 15.5f); lineTo(16f, 4.5f); lineTo(19.5f, 8f); lineTo(8.5f, 19f); close() }
        line { moveTo(13.5f, 7f); lineTo(17f, 10.5f) }
    }

    /** チェック */
    val Check = icon("check") { line(2.6f) { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f) } }

    val Close = icon("close") { line { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) } }

    /** その他の操作（縦の三点） */
    val More = icon("more") {
        solid { circle(12f, 5.5f, 1.8f); circle(12f, 12f, 1.8f); circle(12f, 18.5f, 1.8f) }
    }

    /** 本棚の作品名：1行で省略（1本の線と「…」）・全部表示（折り返した3本の線） */
    val TitleOneLine = icon("title_one_line") {
        line { moveTo(3f, 12f); lineTo(14f, 12f) }
        solid { circle(17f, 12f, 1.2f); circle(20.5f, 12f, 1.2f) }
    }
    val TitleWrap = icon("title_wrap") {
        line { moveTo(3f, 6f); lineTo(21f, 6f); moveTo(3f, 12f); lineTo(21f, 12f); moveTo(3f, 18f); lineTo(14f, 18f) }
    }

    /** 話のタイトルを出す（太い見出しの帯と本文の線）・出さない（本文の線だけ、見出しの所は点線） */
    val TitleShow = icon("title_show") {
        solid { moveTo(4f, 4f); lineTo(16f, 4f); lineTo(16f, 8f); lineTo(4f, 8f); close() }
        line { moveTo(4f, 13f); lineTo(20f, 13f); moveTo(4f, 18f); lineTo(20f, 18f) }
    }
    val TitleHide = icon("title_hide") {
        line(1.6f) { moveTo(4f, 6f); lineTo(6f, 6f); moveTo(9f, 6f); lineTo(11f, 6f); moveTo(14f, 6f); lineTo(16f, 6f) }
        line { moveTo(4f, 13f); lineTo(20f, 13f); moveTo(4f, 18f); lineTo(20f, 18f) }
    }
}
