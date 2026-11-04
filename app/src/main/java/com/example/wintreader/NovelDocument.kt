package com.example.wintreader

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 共通本文モデル（要件定義書 10.2）。
 * サイトごとのHTMLはこの形に変換してから保存・表示する。
 * JSONにしたときの "type" の値は reader.js の描画処理と対応している。
 */
@Serializable
data class NovelDocument(
    val workId: String,
    val chapterId: String,
    val title: String,
    val blocks: List<ContentBlock>,
)

@Serializable
sealed interface ContentBlock {
    @Serializable
    @SerialName("p")
    data class Paragraph(val spans: List<TextSpan>) : ContentBlock

    @Serializable
    @SerialName("img")
    data class Image(val uri: String, val alt: String? = null) : ContentBlock

    @Serializable
    @SerialName("blank")
    data object BlankLine : ContentBlock

    @Serializable
    @SerialName("hr")
    data class Divider(val style: String = "default") : ContentBlock
}

@Serializable
sealed interface TextSpan {
    @Serializable
    @SerialName("text")
    data class Plain(val text: String) : TextSpan

    @Serializable
    @SerialName("ruby")
    data class Ruby(val base: String, val reading: String) : TextSpan

    /** 傍点 */
    @Serializable
    @SerialName("em")
    data class Emphasis(val text: String) : TextSpan
}

/**
 * 読書位置（要件定義書 10.1 / 10.5）。
 * blockIndex = blocks の添字、textOffset = 段落内の文字位置（ルビの読みは数えない）。
 */
@Serializable
data class ReadingPosition(
    val block: Int = 0,
    val offset: Int = 0,
)
