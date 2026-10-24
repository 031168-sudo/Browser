package com.andrey.readerlite

/** Один смысловой блок статьи. Всё остальное со страницы сюда не попадает. */
sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Paragraph(val text: String) : Block
    data class Quote(val text: String) : Block
    data class ListItem(val text: String) : Block
    data class Code(val text: String) : Block
    data class Image(val url: String, val alt: String) : Block
}

data class Article(
    val url: String,
    val title: String,
    val siteName: String,
    val blocks: List<Block>,
)
