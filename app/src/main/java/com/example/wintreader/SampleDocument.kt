package com.example.wintreader

/**
 * M1 の動作確認用サンプル本文（自作）。
 * ルビ・傍点・空行・区切り線・長い段落を含み、数十ページになる長さにしている。
 */
object SampleDocument {

    private val sentences = listOf(
        "春の夜、静かな風が窓を揺らしていた。",
        "遠くの街灯が淡く光り、物語はここから始まる。",
        "「待って、まだ話は終わっていない」",
        "彼女はそう言って、古い地図を机の上に広げた。",
        "地図の端には、見たことのない文字が並んでいる。",
        "「これ、どこの国の言葉だと思う？」",
        "僕は首を振った。少なくとも、学校で習った文字ではない。",
        "窓の外で、鐘の音が三度鳴った。",
    )

    fun create(): NovelDocument {
        val blocks = mutableListOf<ContentBlock>()
        var n = 0
        for (i in 0 until 300) {
            when {
                i % 7 == 6 -> blocks += ContentBlock.BlankLine
                i == 60 -> blocks += ContentBlock.Divider()
                else -> {
                    n++
                    val spans = mutableListOf<TextSpan>()
                    spans += TextSpan.Plain("【$n】")
                    spans += TextSpan.Plain(sentences[i % sentences.size])
                    if (i % 3 == 0) {
                        spans += TextSpan.Ruby("魔導書", "グリモワール")
                        spans += TextSpan.Plain("を開くと、")
                        spans += TextSpan.Emphasis("それ")
                        spans += TextSpan.Plain("が目を覚ました。")
                    }
                    if (i % 4 == 0) {
                        spans += TextSpan.Plain(sentences[(i + 3) % sentences.size].repeat(3))
                    }
                    blocks += ContentBlock.Paragraph(spans)
                }
            }
        }
        return NovelDocument(
            workId = "sample",
            chapterId = "1",
            title = "第1話　始まりの夜",
            blocks = blocks,
        )
    }
}
