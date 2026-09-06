package android.print

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor

object PrintAdapterHelper {
    fun print(
        printAdapter: PrintDocumentAdapter,
        printAttributes: PrintAttributes,
        pfd: ParcelFileDescriptor,
        onComplete: (Boolean, String?) -> Unit
    ) {
        val layoutCallback = object : PrintDocumentAdapter.LayoutResultCallback() {
            override fun onLayoutFinished(info: PrintDocumentInfo?, changed: Boolean) {
                val writeCallback = object : PrintDocumentAdapter.WriteResultCallback() {
                    override fun onWriteFinished(pages: Array<out PageRange>?) {
                        onComplete(true, null)
                    }

                    override fun onWriteFailed(error: CharSequence?) {
                        onComplete(false, error?.toString())
                    }

                    override fun onWriteCancelled() {
                        onComplete(false, "Write cancelled")
                    }
                }

                try {
                    printAdapter.onWrite(
                        arrayOf(PageRange.ALL_PAGES),
                        pfd,
                        CancellationSignal(),
                        writeCallback
                    )
                } catch (e: Exception) {
                    onComplete(false, e.localizedMessage)
                }
            }

            override fun onLayoutFailed(error: CharSequence?) {
                onComplete(false, error?.toString())
            }

            override fun onLayoutCancelled() {
                onComplete(false, "Layout cancelled")
            }
        }

        try {
            printAdapter.onLayout(
                null,
                printAttributes,
                CancellationSignal(),
                layoutCallback,
                null
            )
        } catch (e: Exception) {
            onComplete(false, e.localizedMessage)
        }
    }
}
