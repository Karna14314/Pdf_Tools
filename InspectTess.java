import com.googlecode.tesseract.android.TessBaseAPI;
import com.googlecode.tesseract.android.ResultIterator;
import com.googlecode.tesseract.android.TessBaseAPI.PageIteratorLevel;

public class InspectTess {
    public static void main(String[] args) {
        // Just compiling to see what's available
        TessBaseAPI api = new TessBaseAPI();
        ResultIterator it = api.getResultIterator();
        it.begin();
        it.next(PageIteratorLevel.RIL_WORD);
        it.getUTF8Text(PageIteratorLevel.RIL_WORD);
        int[] rect = it.getBoundingBox(PageIteratorLevel.RIL_WORD); // sometimes returns array or int array?
        it.delete();
    }
}
