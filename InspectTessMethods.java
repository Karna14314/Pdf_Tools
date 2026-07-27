import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import com.googlecode.tesseract.android.TessBaseAPI;
import com.googlecode.tesseract.android.ResultIterator;

public class InspectTessMethods {
    public static void main(String[] args) {
        System.out.println("TessBaseAPI methods:");
        for (Method m : TessBaseAPI.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers())) {
                System.out.println(m);
            }
        }
        System.out.println("\nResultIterator methods:");
        for (Method m : ResultIterator.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers())) {
                System.out.println(m);
            }
        }
    }
}
