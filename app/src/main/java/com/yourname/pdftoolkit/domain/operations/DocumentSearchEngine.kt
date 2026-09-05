package com.yourname.pdftoolkit.domain.operations

import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.File
import java.io.FileInputStream

data class DocSearchResult(
    val pageIndex: Int,
    val textSnippet: String,
    val extraData: String? = null
)

object DocumentSearchEngine {

    fun findAllMatchIndices(text: String, query: String): List<Int> {
        if (query.isEmpty()) return emptyList()
        val out = mutableListOf<Int>()
        var from = 0
        while (true) {
            val idx = text.indexOf(query, from, ignoreCase = true)
            if (idx < 0) break
            out.add(idx)
            from = idx + query.length.coerceAtLeast(1)
        }
        return out
    }

    private fun snippet(text: String, pos: Int, query: String): String {
        val start = maxOf(0, pos - 25)
        val end = minOf(text.length, pos + query.length + 25)
        return (if (start > 0) "..." else "") +
            text.substring(start, end).replace('\n', ' ').trim() +
            (if (end < text.length) "..." else "")
    }

    fun searchDocx(filePath: String, query: String): List<DocSearchResult> {
        val results = mutableListOf<DocSearchResult>()
        if (query.isBlank()) return results
        var fis: FileInputStream? = null
        var doc: XWPFDocument? = null
        try {
            fis = FileInputStream(File(filePath))
            doc = XWPFDocument(fis)
            doc.paragraphs.forEachIndexed { i, paragraph ->
                val text = paragraph.text ?: ""
                for (pos in findAllMatchIndices(text, query)) {
                    results.add(
                        DocSearchResult(
                            pageIndex = i,
                            textSnippet = snippet(text, pos, query),
                            extraData = "Paragraph ${i + 1}"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { doc?.close() } catch (e: Exception) {}
            try { fis?.close() } catch (e: Exception) {}
        }
        return results
    }

    fun searchDoc(filePath: String, query: String): List<DocSearchResult> {
        val results = mutableListOf<DocSearchResult>()
        if (query.isBlank()) return results
        var fis: FileInputStream? = null
        var doc: org.apache.poi.hwpf.HWPFDocument? = null
        try {
            fis = FileInputStream(File(filePath))
            doc = org.apache.poi.hwpf.HWPFDocument(fis)
            val range = doc.range
            for (i in 0 until range.numParagraphs()) {
                val text = range.getParagraph(i).text() ?: ""
                for (pos in findAllMatchIndices(text, query)) {
                    results.add(
                        DocSearchResult(
                            pageIndex = i,
                            textSnippet = snippet(text, pos, query),
                            extraData = "Paragraph ${i + 1}"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { doc?.close() } catch (e: Exception) {}
            try { fis?.close() } catch (e: Exception) {}
        }
        return results
    }
}
