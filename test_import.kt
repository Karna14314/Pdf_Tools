import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import java.io.File
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

fun main() {
    val src = PDDocument()
    src.addPage(PDPage())
    src.addPage(PDPage())
    println("Src pages: ${src.numberOfPages}")

    val dest = PDDocument()
    for (i in 0 until src.numberOfPages) {
        val page = src.getPage(i)
        val imported = dest.importPage(page)
        println("Dest pages after import: ${dest.numberOfPages}")
        dest.addPage(imported)
        println("Dest pages after addPage: ${dest.numberOfPages}")
    }
}
