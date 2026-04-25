package com.yourname.pdftoolkit.ui.pdfviewer

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.FrameLayout
import android.view.View
import com.yourname.pdftoolkit.ui.pdfviewer.PdfEngineCallbacks
import androidx.compose.ui.unit.dp

import com.yourname.pdftoolkit.ui.pdfviewer.engine.PdfEngineFactory
import com.yourname.pdftoolkit.ui.pdfviewer.engine.PdfViewerEngine
import com.yourname.pdftoolkit.util.PrintUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Simplified PDF Viewer Screen using pluggable engine architecture.
 *
 * Routing:
 * - API 31+ with SDK Extension 13: Uses AndroidX PdfViewerFragment (native viewer)
 * - FOSS/F-Droid flavors: Uses MuPDF
 * - All others: Uses PdfBoxFallbackEngine
 *
 * Native viewer provides: zoom, scroll, text selection, search, password dialog, link navigation
 * Fallback engines provide: basic rendering with manual zoom/pan (if implemented)
 *
 * Features:
 * - Native engine: Print button, Annotate routing, Page indicator
 * - Fallback engine: Fallback banner (shown when runtime fallback occurs)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    pdfUri: Uri?,
    pdfName: String = "PDF Document",
    onNavigateBack: () -> Unit,
    onNavigateToTool: ((String, Uri?, String?) -> Unit)? = null,
    onAnnotateRequested: ((Uri) -> Unit)? = null
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    if (pdfUri == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("No PDF selected", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    // Track fallback state for native engine errors
    var useFallbackEngine by remember { mutableStateOf(false) }
    // Track if fallback was triggered at runtime (for banner display)
    var wasRuntimeFallback by remember { mutableStateOf(false) }

    // Track current page for UI (polls from engine if available)
    var currentPage by remember { mutableIntStateOf(0) }
    var pageCount by remember { mutableIntStateOf(0) }
    var showMenu by remember { mutableStateOf(false) }
    // Track if native engine actually supports page navigation (alpha12 may not)
    var nativeSupportsPageNav by remember { mutableStateOf(false) }

    // Async engine initialization state
    var isEngineLoading by remember { mutableStateOf(true) }
    var engine by remember { mutableStateOf<PdfViewerEngine?>(null) }
    var engineLoadError by remember { mutableStateOf<String?>(null) }

    // Async engine initialization
    LaunchedEffect(pdfUri, useFallbackEngine) {
        isEngineLoading = true
        engineLoadError = null
        engine = null

        try {
            @android.annotation.SuppressLint("NewApi")
            val newEngine = withContext(Dispatchers.Default) {
                if (useFallbackEngine) {
                    // Explicit fallback to PdfBox
                    PdfEngineFactory.createWithFallback(context, pdfUri)
                } else {
                    // Try native engine if available
                    if (PdfEngineFactory.isNativeViewerAvailable()) {
                        // Create callbacks interface for reflection compatibility
                        val callbacks = object : PdfEngineCallbacks {
                            override fun onError(error: Throwable) {
                                Log.e("PdfViewerScreen", "Engine error: ${error.message}")
                                if (error is UnsupportedOperationException ||
                                    error is ClassNotFoundException) {
                                    wasRuntimeFallback = true
                                    useFallbackEngine = true
                                }
                            }
                            override fun onFallbackRequired() {
                                wasRuntimeFallback = true
                                useFallbackEngine = true
                            }
                            override fun onPageChanged(current: Int, total: Int) {
                                currentPage = current
                                pageCount = total
                                nativeSupportsPageNav = true
                            }
                        }
                        PdfEngineFactory.createWithFragmentManager(
                            fragmentManager = (context as FragmentActivity).supportFragmentManager,
                            containerId = View.generateViewId(),
                            context = context,
                            uri = pdfUri,
                            callbacks = callbacks
                        )
                    } else {
                        // Native not supported, use fallback
                        PdfEngineFactory.createWithFallback(context, pdfUri)
                    }
                }
            }
            // Update engine on main thread after creation
            engine = newEngine
        } catch (e: Throwable) {
            Log.e("PdfViewerScreen", "Engine creation failed: ${e.javaClass.simpleName}", e)
            engineLoadError = e.message ?: "Failed to initialize PDF engine"
            useFallbackEngine = true
        } finally {
            isEngineLoading = false
        }
    }

    // Poll for page changes if using native engine (alpha12 doesn't fire callbacks reliably)
    LaunchedEffect(engine, useFallbackEngine) {
        if (!useFallbackEngine && PdfEngineFactory.isNativeViewerAvailable() && engine != null) {
            while (true) {
                delay(500)
                try {
                    val eng = engine ?: break
                    val newPage = eng.getCurrentPage()
                    val newTotal = eng.getPageCount()
                    if (newPage != currentPage || newTotal != pageCount) {
                        currentPage = newPage
                        pageCount = newTotal
                    }
                } catch (e: Exception) {
                    // Ignore polling errors
                }
            }
        }
    }

    // Cleanup on dispose
    DisposableEffect(engine) {
        onDispose {
            engine?.cleanup()
        }
    }

    // Gate all UI on engine readiness
    when {
        isEngineLoading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Opening document...",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        engineLoadError != null && engine == null -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Failed to open PDF: $engineLoadError",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        else -> {
            val currentEngine = engine
            if (currentEngine != null) {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    text = if (pageCount > 0) {
                                        "$pdfName (${currentPage + 1}/$pageCount)"
                                    } else {
                                        pdfName
                                    },
                                    style = MaterialTheme.typography.titleMedium
                                )
                            },
                            navigationIcon = {
                                IconButton(onClick = onNavigateBack) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                                }
                            },
                            actions = {
                                // Print button (always available)
                                IconButton(
                                    onClick = {
                                        val success = PrintUtils.printPdf(context, pdfUri, pdfName)
                                        if (!success) {
                                            scope.launch {
                                                snackbarHostState.showSnackbar("Unable to print PDF")
                                            }
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.Print, contentDescription = "Print")
                                }

                                // Annotate button - navigates to legacy viewer with full annotation support
                                if (onAnnotateRequested != null) {
                                    IconButton(
                                        onClick = { onAnnotateRequested(pdfUri) }
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Annotate PDF")
                                    }
                                }

                                // Search button - activates native search UI
                                IconButton(
                                    onClick = {
                                        // For native viewer, search() activates isTextSearchActive
                                        // For fallback engines, this is a no-op
                                        currentEngine.search("")
                                    }
                                ) {
                                    Icon(Icons.Default.Search, contentDescription = "Search")
                                }

                                // Share button
                                IconButton(
                                    onClick = { sharePdf(context, pdfUri) }
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = "Share")
                                }

                                // More options menu
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                                }

                                PdfViewerDropdownMenu(
                                    expanded = showMenu,
                                    onDismiss = { showMenu = false },
                                    pdfUri = pdfUri,
                                    onNavigateToTool = onNavigateToTool
                                )
                            }
                        )
                    },
                    bottomBar = {
                        // Page navigation overlay - only show if we have page info
                        if (pageCount > 0) {
                            PageNavigationOverlay(
                                currentPage = currentPage,
                                totalPages = pageCount,
                                onPrevious = {
                                    // Try to scroll to previous page
                                    // Note: Native engine may not support programmatic scroll in alpha12
                                    if (!nativeSupportsPageNav) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Use scroll gesture to navigate pages")
                                        }
                                    }
                                },
                                onNext = {
                                    if (!nativeSupportsPageNav) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Use scroll gesture to navigate pages")
                                        }
                                    }
                                },
                                navEnabled = nativeSupportsPageNav
                            )
                        }
                    }
                ) { paddingValues ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                    ) {
                        // Fallback banner - show when runtime fallback occurred on API 31+ device
                        AnimatedVisibility(visible = wasRuntimeFallback) {
                            FallbackBanner(
                                onDismiss = { wasRuntimeFallback = false }
                            )
                        }

                        // Engine rendering area
                        Box(modifier = Modifier.fillMaxSize()) {
                            // Engine handles all rendering
                            // - AndroidXPdfViewerEngine: PdfViewerFragment via AndroidView
                            // - PdfBoxFallbackEngine/MuPdfViewerEngine: LazyColumn with bitmaps
                            if (currentEngine.javaClass.simpleName == "AndroidXPdfViewerEngine") {
                                AndroidView(
                                    factory = { ctx ->
                                        FrameLayout(ctx).apply {
                                            id = View.generateViewId()
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                currentEngine.render()
                            }
                        }
                    }
                }

                // Handle back press
                BackHandler {
                    onNavigateBack()
                }
            }
        }
    }

}

/**
 * Page navigation overlay showing current page and prev/next controls.
 */
@Composable
private fun PageNavigationOverlay(
    currentPage: Int,
    totalPages: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    navEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
            )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                IconButton(
                    onClick = onPrevious,
                    enabled = navEnabled && currentPage > 0
                ) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "Previous page")
                }

                Text(
                    text = "${currentPage + 1} / $totalPages",
                    style = MaterialTheme.typography.bodyMedium
                )

                IconButton(
                    onClick = onNext,
                    enabled = navEnabled && currentPage < totalPages - 1
                ) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "Next page")
                }
            }
        }
    }
}

/**
 * Fallback banner shown when native viewer falls back at runtime.
 */
@Composable
private fun FallbackBanner(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Info, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Using compatibility viewer. Some features may be limited.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss")
            }
        }
    }
}

/**
 * Share PDF via system share sheet.
 */
private fun sharePdf(context: android.content.Context, pdfUri: Uri) {
    try {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, pdfUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "Share PDF")
        context.startActivity(chooser)
    } catch (e: Exception) {
        // Handle error silently - user will see no action
    }
}

@Composable
private fun PdfViewerDropdownMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    pdfUri: Uri,
    onNavigateToTool: ((String, Uri?, String?) -> Unit)?
) {
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("Compress") },
            onClick = {
                onDismiss()
                onNavigateToTool?.invoke("compress", pdfUri, null)
            }
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("Convert to Images") },
            onClick = {
                onDismiss()
                onNavigateToTool?.invoke("convert", pdfUri, null)
            }
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("Split PDF") },
            onClick = {
                onDismiss()
                onNavigateToTool?.invoke("split", pdfUri, null)
            }
        )
    }
}
