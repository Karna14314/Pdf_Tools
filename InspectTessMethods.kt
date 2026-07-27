import com.googlecode.tesseract.android.TessBaseAPI
import com.googlecode.tesseract.android.TessBaseAPI.PageIteratorLevel

fun main() {
    val api = TessBaseAPI()
    val iter = api.resultIterator
    if (iter != null) {
        val rect = iter.getBoundingBox(PageIteratorLevel.RIL_WORD)
        val rectClass = rect::class.java.name
        println("Rect type: $rectClass")
    }
}
