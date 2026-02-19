# Agent Guidelines for PDF Toolkit Development

This document contains important guidelines and rules that AI agents should follow when working on this PDF Toolkit Android application.

## PDF Viewer Requirements

### UI/UX Guidelines
- **Highlighter Tool**: The highlighter must use transparency and blend modes to highlight text (making it readable underneath), not work as a solid marker
  - Use `BlendMode.MULTIPLY` or `PorterDuff.Mode.MULTIPLY` for proper highlighting effect
  - Apply 30% alpha (0.3) for the highlighter layer
  - Use non-stacking rendering (draw all highlights in a single layer)

- **Zoom Controls**: Always hide the zoom buttons container in the PDF viewer
  - Hide `zoom_buttons_container` view
  - Keep the 3-dot menu visible and functional for user access to other PDF features

- **Touch Gestures**: Ensure pinch-to-zoom gestures work properly in the PDF viewer
  - Pass through multi-touch events (`event.pointerCount > 1`) to the underlying PDF viewer
  - Only intercept single-touch events when in annotation mode
  - Make overlay views completely transparent to touch events when not annotating

### Fragment Lifecycle
- Set `documentUri` BEFORE adding the fragment to FragmentManager
- Use `view.post {}` to defer UI modifications until after PDF is fully loaded
- This prevents blank page issues and ensures proper PDF rendering

### Annotation Overlay
- Make the InkOverlayView non-clickable and non-focusable by default
- Only intercept touch events when actively in annotation mode
- Set `setOnTouchListener { _, _ -> false }` to ensure complete touch pass-through

## Code Quality Standards

### Android Best Practices
- Follow Material Design 3 guidelines
- Use Jetpack Compose for UI where possible
- Implement proper ViewModel pattern for state management
- Handle configuration changes properly
- Use coroutines for async operations

### Performance
- Recycle bitmaps immediately after use to free native memory
- Use mutex locks for thread-safe document access
- Cache extracted text data for search operations
- Implement proper cancellation for long-running operations

### Error Handling
- Always catch and log exceptions with meaningful messages
- Provide user-friendly error messages
- Handle password-protected PDFs gracefully
- Validate file URIs before processing

## Testing Requirements
- Test PDF rendering with various PDF formats
- Verify annotation tools work correctly (highlighter, marker, underline)
- Test pinch-to-zoom and pan gestures
- Verify save functionality preserves annotations
- Test with rotated PDF pages

## Security Considerations
- Never commit keystore files or signing credentials
- Use content URIs for file access
- Request proper permissions before file operations
- Validate user input before processing

## Documentation
- Add clear comments for critical fixes and workarounds
- Document any androidx.pdf library limitations
- Explain complex coordinate transformations
- Note any API version-specific code

---

Last Updated: 2024-12-30
