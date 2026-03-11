echo "Ah, look at PdfOrganizer.kt: resultDocument.importPage(page) actually imports AND adds the page."
echo "Wait, PdfBox PDDocument.importPage docs: 'This will import and copy the contents from another location. Currently the content stream is stored in a byte array. The original page can be from a different document. This page will be added to this document. Returns the imported page.'"
echo "Yes! It explicitly says 'This page will be added to this document' and 'Returns the imported page'."
echo "So calling \`destDoc.addPage(importedPage)\` AFTER \`destDoc.importPage(sourcePage)\` will add it a SECOND time! That's why 2 pages become 4 pages."
