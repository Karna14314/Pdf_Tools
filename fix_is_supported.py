import re

file_path = "app/src/main/java/com/yourname/pdftoolkit/ui/pdfviewer/engine/PdfEngineFactory.kt"
with open(file_path, "r") as f:
    content = f.read()

content = content.replace("PdfViewerCapability.isNativeViewerSupported()", "PdfViewerCapability.isSupported()")

with open(file_path, "w") as f:
    f.write(content)
