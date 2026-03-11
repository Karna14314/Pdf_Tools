import re

file_path = "app/src/main/java/com/yourname/pdftoolkit/ui/screens/PdfViewerViewModel.kt"

with open(file_path, "r") as f:
    content = f.read()

# Replace destDoc.addPage(importedPage)
new_content = re.sub(r'destDoc\.addPage\(importedPage\)', r'// destDoc.addPage(importedPage) // Removed to prevent duplicate pages', content)

with open(file_path, "w") as f:
    f.write(new_content)

print("Replaced destDoc.addPage(importedPage)")
