import re
import subprocess

file_path = "app/src/main/java/com/yourname/pdftoolkit/ui/screens/PdfViewerScreen.kt"

with open(file_path, "r") as f:
    content = f.read()

# 1. Outer Box and Inner Box for PdfPageWithAnnotations
# The current structure is:
# Box( modifier = Modifier.fillMaxWidth().padding().shadow().background() ) {
#   Box( modifier = Modifier.fillMaxWidth().onSizeChanged().then( ... gestures ... ) ) {
#       ... content (Image, Search Highlights, Annotations Canvas) ...
#   }
# }

# We need:
# Box( modifier = Modifier.fillMaxWidth().padding().shadow().background() ) {
#   Box( modifier = Modifier.fillMaxWidth().onSizeChanged().then( ... gestures ... ) ) {
#       Box( modifier = Modifier.matchParentSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = pagePanX; clip = true } ) {
#           Image( ... NO graphicsLayer ... )
#           // Canvas overlays...
#       }
#   }
# }
# Wait, the instruction says:
# Box( modifier = Modifier.fillMaxWidth().onSizeChanged { size = it }.then( ... gesture ... ) ) {
#    Image( bitmap = pageBitmap.asImageBitmap(), modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale; translationX = pagePanX; clip = true } )
#    Canvas( modifier = Modifier.matchParentSize() ) { ... }
# }
# Wait, I asked the user: "Should the Canvas actually share the same graphicsLayer as the Image?"
# User replied:
# "Apply graphicsLayer to a Box that wraps BOTH the Image and the Canvas together, not to the Image alone:
# Box( modifier = Modifier.fillMaxWidth().graphicsLayer { ... } ) {
#    Image(...)
#    Canvas(...)
# }"

# Let's rewrite `PdfPageWithAnnotations` from `Box(modifier = Modifier.fillMaxWidth().padding(...)` to the end of the composable.
