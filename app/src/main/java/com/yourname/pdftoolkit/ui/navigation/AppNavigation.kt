package com.yourname.pdftoolkit.ui.navigation

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.yourname.pdftoolkit.BuildConfig
import com.yourname.pdftoolkit.ui.components.HistorySidebar
import com.yourname.pdftoolkit.ui.screens.PdfViewerScreen
import com.yourname.pdftoolkit.ui.screens.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * URI normalization helper: copies content URIs from external providers to cache for reliable access.
 * Returns FileProvider URI for external content URIs, original URI for FileProvider URIs or file:// URIs.
 */
private suspend fun normalizeUriToCache(context: android.content.Context, uri: Uri, snackbarHostState: SnackbarHostState): Uri? {
    // Only process content:// URIs that aren't from our FileProvider
    if (uri.scheme != "content") return uri
    if (uri.authority?.contains(context.packageName) == true) return uri

    return withContext(Dispatchers.IO) {
        try {
            val cacheDir = File(context.cacheDir, "viewer_cache")
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val tempFile = File(cacheDir, "pdf_${System.currentTimeMillis()}.pdf")

            // CRITICAL: For Downloads provider, we need to handle it specially
            // The permission may have expired, so we try multiple approaches
            var inputStream: java.io.InputStream? = null

            try {
                // First attempt: direct open
                inputStream = context.contentResolver.openInputStream(uri)
            } catch (e: SecurityException) {
                android.util.Log.w("AppNavigation", "Direct open failed for $uri: ${e.message}")

                // For Downloads provider, try to get a file descriptor via alternative methods
                if (uri.authority?.contains("downloads") == true || uri.toString().contains("downloads")) {
                    try {
                        // Try using the document contract to get a file descriptor
                        val parcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
                        if (parcelFileDescriptor != null) {
                            inputStream = java.io.FileInputStream(parcelFileDescriptor.fileDescriptor)
                        }
                    } catch (e2: Exception) {
                        android.util.Log.w("AppNavigation", "File descriptor approach failed: ${e2.message}")
                    }
                }
            }

            if (inputStream == null) {
                throw IllegalStateException("Cannot open input stream for URI: $uri - Permission may have expired")
            }

            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            Uri.fromFile(tempFile)
        } catch (e: Exception) {
            android.util.Log.e("AppNavigation", "Failed to normalize URI: $uri", e)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                snackbarHostState.showSnackbar("Failed to access PDF: ${e.message}")
            }
            null
        }
    }
}

/**
 * Main navigation component with Bottom Navigation (2 tabs: Tools, Files)
 * and Settings accessible via top bar icon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    startDestination: String = Screen.Tools.route,
    initialPdfUri: Uri? = null,
    initialPdfName: String? = null
) {
    val context = LocalContext.current
    val actualStartDestination = when {
        initialPdfUri != null -> "pdf_viewer_direct"
        else -> startDestination
    }
    
    // Handle dynamic URI changes (e.g., from onNewIntent)
    LaunchedEffect(initialPdfUri) {
        if (initialPdfUri != null) {
            // Navigate to PDF viewer when URI changes
            // Use pdf_viewer_direct route which already has access to initialPdfUri/initialPdfName
            navController.navigate("pdf_viewer_direct") {
                // Pop up to Tools to avoid building up a large back stack
                popUpTo(Screen.Tools.route) { inclusive = false }
            }
        }
    }
    
    // Track current route
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    
    // Bottom bar is shown only on main tabs
    val showBottomBar = currentRoute in listOf(
        Screen.Tools.route,
        Screen.Files.route
    )
    
    // Top bar is shown only on main tabs
    val showTopBar = currentRoute in listOf(
        Screen.Tools.route,
        Screen.Files.route
    )
    
    // History sidebar state
    var isHistorySidebarOpen by remember { mutableStateOf(false) }

    // Snackbar for URI errors
    val snackbarHostState = remember { SnackbarHostState() }

    Box(modifier = modifier.fillMaxSize()) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (showTopBar) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { isHistorySidebarOpen = true }) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Open History"
                            )
                        }
                    },
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // App Icon
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PictureAsPdf,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(6.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = when (currentRoute) {
                                    Screen.Tools.route -> "PDF Toolkit"
                                    Screen.Files.route -> "Files"
                                    else -> "PDF Toolkit"
                                },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                navController.navigate(Screen.Settings.route)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                BottomNavigationBar(
                    navController = navController,
                    currentRoute = currentRoute
                )
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = actualStartDestination,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Main Tabs
            composable(Screen.Tools.route) {
                ToolsScreen(
                    onNavigateToScreen = { screen ->
                        navController.navigate(screen.route)
                    },
                    onNavigateToRoute = { route ->
                        navController.navigate(route)
                    },
                    onOpenPdfViewer = { uri, name ->
                        val encodedUri = Uri.encode(uri.toString())
                        val encodedName = Uri.encode(name)
                        navController.navigate(Screen.PdfViewer.createRoute(encodedUri, encodedName))
                    }
                )
            }
            
            composable(Screen.Files.route) {
                FilesScreen(
                    onOpenPdfViewer = { uri, name ->
                        val encodedUri = Uri.encode(uri.toString())
                        val encodedName = Uri.encode(name)
                        navController.navigate(Screen.PdfViewer.createRoute(encodedUri, encodedName))
                    }
                )
            }
            
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            // PDF Viewer with URI parameters - Always uses Legacy viewer for full annotation support
            composable(
                route = Screen.PdfViewer.route,
                arguments = listOf(
                    navArgument("uri") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("name") {
                        type = NavType.StringType
                        defaultValue = ""
                    }
                )
            ) { backStackEntry ->
                val uriString = backStackEntry.arguments?.getString("uri") ?: ""
                val name = backStackEntry.arguments?.getString("name") ?: "PDF Document"
                val rawUri = if (uriString.isNotEmpty()) Uri.parse(Uri.decode(uriString)) else null

                // URI normalization state
                var normalizedUri by remember(rawUri) { mutableStateOf<Uri?>(null) }
                var isNormalizing by remember { mutableStateOf(rawUri != null) }

                LaunchedEffect(rawUri) {
                    if (rawUri != null) {
                        isNormalizing = true
                        normalizedUri = normalizeUriToCache(context, rawUri, snackbarHostState)
                        isNormalizing = false
                    } else {
                        normalizedUri = null
                    }
                }

                // Show loading while normalizing
                if (isNormalizing) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    return@composable
                }

                if (rawUri != null && normalizedUri == null) {
                    LaunchedEffect(rawUri) {
                        navController.popBackStack()
                    }
                    return@composable
                }

                PdfViewerScreen(
                    pdfUri = normalizedUri,
                    pdfName = Uri.decode(name),
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToTool = { tool, toolUri, toolName ->
                        val encodedUri = toolUri?.let { Uri.encode(it.toString()) } ?: ""
                        val encodedName = Uri.encode(toolName ?: "PDF Document")
                        when (tool) {
                            "compress" -> navController.navigate("compress?uri=$encodedUri&name=$encodedName")
                            "watermark" -> navController.navigate("watermark?uri=$encodedUri&name=$encodedName")
                            else -> {}
                        }
                    }
                )
            }

            // Direct PDF viewer for intent handling - Always uses Legacy viewer
            composable("pdf_viewer_direct") {
                // URI normalization state for initialPdfUri
                var normalizedUri by remember(initialPdfUri) { mutableStateOf<Uri?>(null) }
                var isNormalizing by remember { mutableStateOf(initialPdfUri != null) }

                LaunchedEffect(initialPdfUri) {
                    if (initialPdfUri != null) {
                        isNormalizing = true
                        normalizedUri = normalizeUriToCache(context, initialPdfUri, snackbarHostState)
                        isNormalizing = false
                    } else {
                        normalizedUri = null
                    }
                }

                // Show loading while normalizing
                if (isNormalizing) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    return@composable
                }

                if (initialPdfUri != null && normalizedUri == null) {
                    LaunchedEffect(initialPdfUri) {
                        navController.navigate(Screen.Tools.route) {
                            popUpTo("pdf_viewer_direct") { inclusive = true }
                        }
                    }
                    return@composable
                }

                PdfViewerScreen(
                    pdfUri = normalizedUri,
                    pdfName = initialPdfName ?: "PDF Document",
                    onNavigateBack = {
                        navController.navigate(Screen.Tools.route) {
                            popUpTo("pdf_viewer_direct") { inclusive = true }
                        }
                    },
                    onNavigateToTool = { tool, toolUri, toolName ->
                        val encodedUri = toolUri?.let { Uri.encode(it.toString()) } ?: ""
                        val encodedName = Uri.encode(toolName ?: "PDF Document")
                        when (tool) {
                            "compress" -> navController.navigate("compress?uri=$encodedUri&name=$encodedName")
                            "watermark" -> navController.navigate("watermark?uri=$encodedUri&name=$encodedName")
                            else -> {}
                        }
                    }
                )
            }


            // PDF Tool Screens
            composable(Screen.Merge.route) {
                MergeScreen(onNavigateBack = { navController.popBackStack() })
            }
            
            composable(Screen.Split.route) {
                SplitScreen(onNavigateBack = { navController.popBackStack() })
            }
            
            composable(
                route = "compress?uri={uri}&name={name}",
                arguments = listOf(
                    navArgument("uri") { 
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("name") { 
                        type = NavType.StringType
                        defaultValue = ""
                    }
                )
            ) { backStackEntry ->
                val uriString = backStackEntry.arguments?.getString("uri") ?: ""
                val name = backStackEntry.arguments?.getString("name") ?: ""
                val uri = if (uriString.isNotEmpty()) Uri.parse(uriString) else null
                
                CompressScreen(
                    onNavigateBack = { navController.popBackStack() },
                    initialUri = uri,
                    initialName = if (name.isNotEmpty()) Uri.decode(name) else null
                )
            }
            
            composable(Screen.Convert.route) {
                ConvertScreen(onNavigateBack = { navController.popBackStack() })
            }
            
            composable(Screen.PdfToImage.route) {
                PdfToImageScreen(onNavigateBack = { navController.popBackStack() })
            }
            
            composable(Screen.Extract.route) {
                ExtractScreen(onNavigateBack = { navController.popBackStack() })
            }
            
            composable(Screen.Rotate.route) {
                RotateScreen(onNavigateBack = { navController.popBackStack() })
            }
            
            composable(Screen.Security.route) {
                SecurityScreen(onNavigateBack = { navController.popBackStack() })
            }
            
            composable(Screen.Metadata.route) {
                MetadataScreen(onNavigateBack = { navController.popBackStack() })
            }
            
            composable(Screen.PageNumber.route) {
                PageNumberScreen(onNavigateBack = { navController.popBackStack() })
            }
            
            composable(Screen.Organize.route) {
                OrganizeScreen(onNavigateBack = { navController.popBackStack() })
            }
            
            composable(Screen.Reorder.route) {
                ReorderScreen(onNavigateBack = { navController.popBackStack() })
            }
            
            composable(Screen.Unlock.route) {
                UnlockScreen(onNavigateBack = { navController.popBackStack() })
            }
            
            composable(Screen.Repair.route) {
                RepairScreen(onNavigateBack = { navController.popBackStack() })
            }
            
            composable(Screen.HtmlToPdf.route) {
                HtmlToPdfScreen(onNavigateBack = { navController.popBackStack() })
            }
            
            composable(Screen.ExtractText.route) {
                ExtractTextScreen(onNavigateBack = { navController.popBackStack() })
            }
            
            composable(
                route = "watermark?uri={uri}&name={name}",
                arguments = listOf(
                    navArgument("uri") { 
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("name") { 
                        type = NavType.StringType
                        defaultValue = ""
                    }
                )
            ) { backStackEntry ->
                val uriString = backStackEntry.arguments?.getString("uri") ?: ""
                val name = backStackEntry.arguments?.getString("name") ?: ""
                val uri = if (uriString.isNotEmpty()) Uri.parse(uriString) else null
                
                WatermarkScreen(
                    onNavigateBack = { navController.popBackStack() },
                    initialUri = uri,
                    initialName = if (name.isNotEmpty()) Uri.decode(name) else null
                )
            }
            
            composable(Screen.Flatten.route) {
                FlattenScreen(onNavigateBack = { navController.popBackStack() })
            }
            
            composable(Screen.SignPdf.route) {
                SignPdfScreen(onNavigateBack = { navController.popBackStack() })
            }
            
            composable(Screen.FillForms.route) {
                FillFormsScreen(onNavigateBack = { navController.popBackStack() })
            }
            
            composable(Screen.Annotate.route) {
                AnnotationScreen(onNavigateBack = { navController.popBackStack() })
            }
            
            composable(Screen.ScanToPdf.route) {
                ScanToPdfScreen(onNavigateBack = { navController.popBackStack() })
            }
            
            // OCR screen - only available in Play Store flavor
            if (BuildConfig.HAS_OCR) {
                composable(Screen.Ocr.route) {
                    OcrScreen(onNavigateBack = { navController.popBackStack() })
                }
            }
            
            composable(
                route = Screen.ImageTools.route,
                arguments = listOf(
                    navArgument("operation") {
                        type = NavType.StringType
                        defaultValue = "resize"
                    }
                )
            ) { backStackEntry ->
                val operation = backStackEntry.arguments?.getString("operation") ?: "resize"
                ImageToolsScreen(
                    initialOperation = operation,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
    
    // History Sidebar overlay
    HistorySidebar(
        isOpen = isHistorySidebarOpen,
        onClose = { isHistorySidebarOpen = false },
        onOpenFile = { uri ->
            isHistorySidebarOpen = false
            // Navigate to PDF viewer with the file
            val encodedUri = Uri.encode(uri.toString())
            navController.navigate(Screen.PdfViewer.createRoute(encodedUri, "PDF Document"))
        }
    )
    } // End Box
}

/**
 * Bottom Navigation Bar with 2 tabs (Tools, Files).
 */
@Composable
private fun BottomNavigationBar(
    navController: NavHostController,
    currentRoute: String?
) {
    NavigationBar {
        BottomNavTab.entries.forEach { tab ->
            val selected = when (tab) {
                BottomNavTab.TOOLS -> currentRoute == Screen.Tools.route
                BottomNavTab.FILES -> currentRoute == Screen.Files.route
            }
            
            NavigationBarItem(
                icon = { 
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.title
                    )
                },
                label = { Text(tab.title) },
                selected = selected,
                onClick = {
                    val targetRoute = when (tab) {
                        BottomNavTab.TOOLS -> Screen.Tools.route
                        BottomNavTab.FILES -> Screen.Files.route
                    }
                    
                    navController.navigate(targetRoute) {
                        // Pop up to start destination to avoid building up a stack
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        // Avoid multiple copies of the same destination
                        launchSingleTop = true
                        // Restore state when reselecting a previously selected item
                        restoreState = true
                    }
                }
            )
        }
    }
}
