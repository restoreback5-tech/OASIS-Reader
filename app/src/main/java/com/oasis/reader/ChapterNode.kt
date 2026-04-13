package com.oasis.reader

data class ChapterNode(
    val title: String,
    val src: String,
    val children: MutableList<ChapterNode> = mutableListOf()
) {
    fun isLeaf(): Boolean = children.isEmpty()
}
