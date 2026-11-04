package com.example.wintreader

/**
 * 動作確認用のサンプル作品（自作の文章）。
 * サイトからの取得（M3）ができるまで、本棚・目次・栞の確認に使う。
 */
object SampleDocument {

    data class SampleWork(
        val workId: String,
        val title: String,
        val author: String,
        val chapterCount: Int,
    )

    val works = listOf(
        SampleWork("w1", "始まりの夜の物語", "サンプル作者A", 6),
        SampleWork("w2", "窓辺の地図", "サンプル作者B", 4),
        SampleWork("w3", "鐘が三度鳴るとき", "サンプル作者C", 3),
    )

    private val chapterTitles = listOf(
        "始まりの夜", "古い地図", "見知らぬ文字", "鐘の音", "旅立ち", "新しい街", "約束", "帰り道",
    )

    fun chapterTitle(index: Int): String =
        "第${index + 1}話　${chapterTitles[index % chapterTitles.size]}"

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

    /** 作品 work の第 (index + 1) 話の本文。話ごとに文章の並びを少し変える */
    fun create(work: SampleWork, index: Int): NovelDocument {
        val blocks = mutableListOf<ContentBlock>()
        var n = 0
        val shift = index * 3 + work.workId.hashCode().mod(5)
        val length = 120 + (index % 3) * 60
        for (i in 0 until length) {
            when {
                i % 7 == 6 -> blocks += ContentBlock.BlankLine
                i == 40 -> blocks += ContentBlock.Divider()
                else -> {
                    n++
                    val spans = mutableListOf<TextSpan>()
                    spans += TextSpan.Plain("【$n】")
                    spans += TextSpan.Plain(sentences[(i + shift) % sentences.size])
                    if (i % 3 == 0) {
                        spans += TextSpan.Ruby("魔導書", "グリモワール")
                        spans += TextSpan.Plain("を開くと、")
                        spans += TextSpan.Emphasis("それ")
                        spans += TextSpan.Plain("が目を覚ました。")
                    }
                    if (i % 4 == 0) {
                        spans += TextSpan.Plain(sentences[(i + shift + 3) % sentences.size].repeat(2))
                    }
                    blocks += ContentBlock.Paragraph(spans)
                }
            }
        }
        return NovelDocument(
            workId = work.workId,
            chapterId = "${index + 1}",
            title = chapterTitle(index),
            blocks = blocks,
        )
    }
}
