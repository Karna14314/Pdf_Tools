# PDF Toolkit - Scope Freeze & Wishlist

## 1. Core PDF Editing Features
### Page-Level Operations
- [x] Merge multiple PDFs into one
- [x] Split PDF (Page range, Individual pages, Every N pages)
- [ ] Custom page selection (Partially covered by Extract)
- [ ] Reorder pages (drag & drop)
- [x] Rotate pages (90°, 180°, 270°)
- [ ] Delete selected pages (Covered by Extract implicitly, but needs dedicated UI)
- [ ] Duplicate pages
- [x] Extract selected pages into a new PDF

## 2. PDF Optimization & Size Control
- [x] Compress PDF (Light, Medium, Aggressive)
- [x] Image downscaling inside PDFs
- [x] Remove unused objects/resources (Implicit in Save)
- [ ] Optimize for Sharing/Storage/Printing (Profiles)

## 3. PDF Conversion Features
### Image ↔ PDF
- [x] Images to PDF (Multiple images)
- [ ] Custom page order (Partially via pickle order)
- [ ] PDF to Images (Per page export)
- [ ] Configurable image format (PNG/JPEG)
- [ ] Resolution control

## 4. PDF Security Features
- [ ] Add password protection
- [ ] Remove password protection (with valid password)
- [ ] Encrypt PDF
- [ ] Decrypt PDF
- [ ] Restrict permissions (Printing, Copying)

## 5. Metadata & Document Info
- [ ] View PDF metadata (Title, Author, Subject, etc.)
- [ ] Edit PDF metadata
- [ ] Clear metadata

## 6. Batch Operations
- [ ] Batch merge (Already core feature)
- [ ] Batch compress
- [ ] Batch convert
- [ ] Progress tracking per file

## 7. Integrated PDF Viewer (Core Component)
- [x] Open and render PDFs locally
- [x] Zoom / Scroll
- [ ] Page thumbnails sidebar
- [ ] Viewer-driven workflows (Select pages visually)

## 8. File Handling & Storage
- [x] SAF / Intent opening
- [x] Save to user location
- [ ] Share output via share sheet
- [x] Preserve original files

## 9. App-Level UX
- [ ] Tool Categorization (Edit, Convert, Optimize, Secure, View)
- [ ] Tool Search
- [ ] Determinate progress indicators (Implemented)
- [ ] Cancel operations (Back navigation cancels scope)

## 10. Privacy & Compliance
- [x] Offline operation
- [x] No internet permission
- [x] No analytics/tracking
- [ ] Open-source licenses screen
- [ ] Privacy statement

## 11. Platform Scope
- [x] Android
- [ ] Windows/Linux (Future)
