package com.yourname.pdftoolkit.ui.screens

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yourname.pdftoolkit.BuildConfig
import com.yourname.pdftoolkit.ui.components.LicensesDialog
import com.yourname.pdftoolkit.util.CacheManager
import com.yourname.pdftoolkit.util.ThemeManager
import com.yourname.pdftoolkit.util.ThemeMode
import com.yourname.pdftoolkit.util.PdfTools
import com.yourname.pdftoolkit.util.LanguageManager
import com.yourname.pdftoolkit.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Image format options for default setting.
 */
enum class DefaultImageFormat(val displayName: String, val extension: String) {
    WEBP("WebP (Recommended)", "webp"),
    JPEG("JPEG", "jpg")
}

/**
 * Settings preferences manager.
 */
object SettingsPreferences {
    private const val PREFS_NAME = "pdf_toolkit_settings"
    private const val KEY_COMPRESSION_QUALITY = "compression_quality"
    private const val KEY_IMAGE_FORMAT = "default_image_format"
    
    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    fun getCompressionQuality(context: Context): Int {
        return getPrefs(context).getInt(KEY_COMPRESSION_QUALITY, 75)
    }
    
    fun setCompressionQuality(context: Context, quality: Int) {
        getPrefs(context).edit().putInt(KEY_COMPRESSION_QUALITY, quality).apply()
    }
    
    fun getDefaultImageFormat(context: Context): DefaultImageFormat {
        val formatName = getPrefs(context).getString(KEY_IMAGE_FORMAT, DefaultImageFormat.WEBP.name)
        return try {
            DefaultImageFormat.valueOf(formatName ?: DefaultImageFormat.WEBP.name)
        } catch (e: Exception) {
            DefaultImageFormat.WEBP
        }
    }
    
    fun setDefaultImageFormat(context: Context, format: DefaultImageFormat) {
        getPrefs(context).edit().putString(KEY_IMAGE_FORMAT, format.name).apply()
    }
}

/**
 * Comprehensive Settings Screen with organized sections.
 * Includes: Default compression quality, Default image format, Cache cleanup, About/Privacy/License
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var cacheSize by remember { mutableStateOf("Calculating...") }
    var isClearing by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showFeatureRequestDialog by remember { mutableStateOf(false) }
    var showImageFormatDialog by remember { mutableStateOf(false) }
    var showLicensesDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageScreen by remember { mutableStateOf(false) }
    
    // Settings state
    var compressionQuality by remember { mutableStateOf(SettingsPreferences.getCompressionQuality(context)) }
    var defaultImageFormat by remember { mutableStateOf(SettingsPreferences.getDefaultImageFormat(context)) }
    
    // Theme state
    val currentTheme by ThemeManager.getThemeMode(context).collectAsState(initial = ThemeMode.SYSTEM)
    
    // Language state
    val currentLanguage by LanguageManager.getLanguageFlow(context).collectAsState(initial = LanguageManager.getCurrentLanguage())
    
    // Calculate cache size on screen load
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            cacheSize = CacheManager.getFormattedCacheSize(context)
        }
    }
    
    if (showLanguageScreen) {
        LanguageSelectionScreen(
            currentLanguage = currentLanguage,
            onNavigateBack = { showLanguageScreen = false }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            // Quality Settings Section
            item {
                SettingsSectionHeader(title = stringResource(R.string.settings_section_quality))
            }
            
            // Default Compression Quality
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.HighQuality,
                                contentDescription = stringResource(R.string.cd_compression_quality),
                                modifier = Modifier
                                    .padding(8.dp)
                                    .size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_compression_quality),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "${compressionQuality}% - ${getQualityDescription(compressionQuality)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Slider(
                        value = compressionQuality.toFloat(),
                        onValueChange = { 
                            compressionQuality = it.toInt()
                        },
                        onValueChangeFinished = {
                            SettingsPreferences.setCompressionQuality(context, compressionQuality)
                        },
                        valueRange = 30f..100f,
                        steps = 6,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.compress_smaller_file),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.compress_better_quality),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // Default Image Format
            item {
                SettingsItem(
                    title = stringResource(R.string.settings_image_format),
                    subtitle = defaultImageFormat.displayName,
                    icon = Icons.Default.Image,
                    onClick = { showImageFormatDialog = true }
                )
            }
            
            // Appearance Section
            item {
                SettingsSectionHeader(title = stringResource(R.string.settings_section_appearance))
            }
            
            // Theme Mode
            item {
                SettingsItem(
                    title = stringResource(R.string.settings_theme_mode),
                    subtitle = currentTheme.displayName,
                    icon = Icons.Default.Palette,
                    onClick = { showThemeDialog = true }
                )
            }
            
            // Language Section
            item {
                SettingsSectionHeader(title = stringResource(R.string.settings_section_language))
            }
            
            // Language Selector
            item {
                SettingsItem(
                    title = stringResource(R.string.settings_select_language),
                    subtitle = LanguageManager.getLanguageDisplayName(currentLanguage),
                    icon = Icons.Default.Language,
                    onClick = { showLanguageScreen = true }
                )
            }
            
            // Storage Section
            item {
                SettingsSectionHeader(title = stringResource(R.string.settings_section_storage))
            }
            
            item {
                SettingsItem(
                    title = stringResource(R.string.settings_cache_size),
                    subtitle = cacheSize,
                    icon = Icons.Default.Storage,
                    onClick = { showClearCacheDialog = true }
                ) {
                    if (isClearing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        TextButton(onClick = { showClearCacheDialog = true }) {
                            Text(stringResource(R.string.action_clear))
                        }
                    }
                }
            }
            
            // Support Section
            item {
                SettingsSectionHeader(title = stringResource(R.string.settings_section_support))
            }
            
            item {
                SettingsItem(
                    title = stringResource(R.string.settings_request_feature),
                    subtitle = stringResource(R.string.settings_request_feature_subtitle),
                    icon = Icons.Default.Lightbulb,
                    onClick = { showFeatureRequestDialog = true }
                )
            }
            
            item {
                SettingsItem(
                    title = stringResource(R.string.settings_report_bug),
                    subtitle = stringResource(R.string.settings_report_bug_subtitle),
                    icon = Icons.Default.BugReport,
                    onClick = {
                        sendBugReport(context)
                    }
                )
            }
            
            item {
                SettingsItem(
                    title = stringResource(R.string.settings_rate_app),
                    subtitle = stringResource(R.string.settings_rate_app_subtitle),
                    icon = Icons.Default.Star,
                    onClick = {
                        openPlayStore(context)
                    }
                )
            }
            
            // About Section
            item {
                SettingsSectionHeader(title = stringResource(R.string.settings_section_about))
            }
            
            item {
                SettingsItem(
                    title = stringResource(R.string.settings_version),
                    subtitle = "${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                    icon = Icons.Default.Info,
                    onClick = { showAboutDialog = true }
                )
            }
            
            item {
                SettingsItem(
                    title = stringResource(R.string.settings_privacy_policy),
                    subtitle = stringResource(R.string.settings_privacy_policy_subtitle),
                    icon = Icons.Default.PrivacyTip,
                    onClick = {
                        openPrivacyPolicy(context)
                    }
                )
            }
            
            item {
                SettingsItem(
                    title = stringResource(R.string.settings_licenses),
                    subtitle = stringResource(R.string.settings_licenses_subtitle),
                    icon = Icons.Default.Description,
                    onClick = {
                        showLicensesDialog = true
                    }
                )
            }
            
            // App Info Footer
            item {
                Spacer(modifier = Modifier.height(32.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(64.dp)
                    ) {
                        Icon(
                            Icons.Default.PictureAsPdf,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(16.dp)
                                .size(32.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.settings_app_name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.settings_made_with_love),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_copyright),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // Bottom spacing for navigation
            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
    
    // Licenses Dialog
    if (showLicensesDialog) {
        LicensesDialog(
            onDismiss = { showLicensesDialog = false }
        )
    }
    
    // Theme Selection Dialog
    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            icon = { Icon(Icons.Default.Palette, contentDescription = null) },
            title = { Text(stringResource(R.string.settings_theme_mode)) },
            text = {
                Column {
                    ThemeMode.entries.forEach { theme ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        ThemeManager.setThemeMode(context, theme)
                                    }
                                    showThemeDialog = false
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentTheme == theme,
                                onClick = {
                                    scope.launch {
                                        ThemeManager.setThemeMode(context, theme)
                                    }
                                    showThemeDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = theme.displayName,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = when (theme) {
                                        ThemeMode.LIGHT -> "Always use light theme"
                                        ThemeMode.DARK -> "Always use dark theme"
                                        ThemeMode.SYSTEM -> "Follow system settings"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    
    // Image Format Selection Dialog
    if (showImageFormatDialog) {
        AlertDialog(
            onDismissRequest = { showImageFormatDialog = false },
            icon = { Icon(Icons.Default.Image, contentDescription = null) },
            title = { Text(stringResource(R.string.settings_image_format)) },
            text = {
                Column {
                    DefaultImageFormat.entries.forEach { format ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    defaultImageFormat = format
                                    SettingsPreferences.setDefaultImageFormat(context, format)
                                    showImageFormatDialog = false
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = defaultImageFormat == format,
                                onClick = {
                                    defaultImageFormat = format
                                    SettingsPreferences.setDefaultImageFormat(context, format)
                                    showImageFormatDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = format.displayName,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                if (format == DefaultImageFormat.WEBP) {
                                    Text(
                                        text = "Best compression, smaller files",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Text(
                                        text = "Universal compatibility",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showImageFormatDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    
    // Clear Cache Dialog
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            icon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
            title = { Text(stringResource(R.string.settings_clear_cache)) },
            text = {
                Text(stringResource(R.string.settings_clear_cache_message))
            },
            confirmButton = {
                Button(
                    onClick = {
                        isClearing = true
                        showClearCacheDialog = false
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                CacheManager.clearAllCache(context)
                            }
                            cacheSize = CacheManager.getFormattedCacheSize(context)
                            isClearing = false
                            Toast.makeText(context, context.getString(R.string.settings_cache_cleared), Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text(stringResource(R.string.action_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    
    // About Dialog
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            icon = { Icon(Icons.Default.Info, contentDescription = null) },
            title = { Text(stringResource(R.string.settings_about_title)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(stringResource(R.string.settings_about_description))
                    
                    Divider()
                    
                    Text(
                        text = stringResource(R.string.settings_about_features),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(stringResource(R.string.settings_about_feature_1))
                    Text(stringResource(R.string.settings_about_feature_2))
                    Text(stringResource(R.string.settings_about_feature_3))
                    Text(stringResource(R.string.settings_about_feature_4))
                    Text(stringResource(R.string.settings_about_feature_5))
                    
                    Divider()
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.settings_about_version), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(BuildConfig.VERSION_NAME)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.settings_about_build), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(BuildConfig.VERSION_CODE.toString())
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.settings_about_made_with), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.settings_about_kotlin_compose))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
    }
    
    // Feature Request Dialog
    if (showFeatureRequestDialog) {
        FeatureRequestDialog(
            onDismiss = { showFeatureRequestDialog = false },
            onSubmit = { featureText ->
                sendFeatureRequest(context, featureText)
                showFeatureRequestDialog = false
            }
        )
    }
}

@Composable
private fun getQualityDescription(quality: Int): String {
    return when {
        quality >= 90 -> stringResource(R.string.compress_quality_maximum)
        quality >= 75 -> stringResource(R.string.compress_quality_high)
        quality >= 60 -> stringResource(R.string.compress_quality_balanced)
        quality >= 45 -> stringResource(R.string.compress_quality_compressed)
        else -> stringResource(R.string.compress_quality_maximum_compression)
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingsItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            if (trailing != null) {
                trailing()
            } else {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeatureRequestDialog(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var featureText by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Feature") }
    var showCategoryMenu by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Lightbulb, contentDescription = null) },
        title = { Text(stringResource(R.string.feature_request_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.feature_request_description))
                
                // Category selector
                ExposedDropdownMenuBox(
                    expanded = showCategoryMenu,
                    onExpandedChange = { showCategoryMenu = it }
                ) {
                    OutlinedTextField(
                        value = category,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.feature_request_category)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = showCategoryMenu)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = showCategoryMenu,
                        onDismissRequest = { showCategoryMenu = false }
                    ) {
                        listOf("Feature", "Improvement", "UI/UX", "Other").forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    category = option
                                    showCategoryMenu = false
                                }
                            )
                        }
                    }
                }
                
                OutlinedTextField(
                    value = featureText,
                    onValueChange = { featureText = it },
                    label = { Text(stringResource(R.string.feature_request_idea)) },
                    placeholder = { Text(stringResource(R.string.feature_request_placeholder)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    maxLines = 6
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit("[$category] $featureText") },
                enabled = featureText.isNotBlank()
            ) {
                Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_submit))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

// Helper functions for intents

/**
 * Developer email for support requests.
 */
private const val DEVELOPER_EMAIL = "developerncn29@gmail.com"

private fun sendFeatureRequest(context: Context, featureText: String) {
    val deviceInfo = """
        
        ---
        Device: ${Build.MANUFACTURER} ${Build.MODEL}
        Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
        App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
    """.trimIndent()
    
    val emailBody = "$featureText\n$deviceInfo"
    
    try {
        // Restrict to Gmail only
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            setPackage("com.google.android.gm") // Gmail package
            putExtra(Intent.EXTRA_EMAIL, arrayOf(DEVELOPER_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, "[Feature Request] PDF Toolkit")
            putExtra(Intent.EXTRA_TEXT, emailBody)
        }
        
        context.startActivity(intent)
    } catch (e: Exception) {
        // Gmail not installed
        Toast.makeText(
            context, 
            "Gmail app is required. Please install Gmail or send feedback to $DEVELOPER_EMAIL", 
            Toast.LENGTH_LONG
        ).show()
    }
}

private fun sendBugReport(context: Context) {
    val deviceInfo = """
        Bug Description:
        [Please describe the issue you encountered]
        
        Steps to Reproduce:
        1. 
        2. 
        3. 
        
        Expected Behavior:
        [What did you expect to happen?]
        
        Actual Behavior:
        [What actually happened?]
        
        ---
        Device: ${Build.MANUFACTURER} ${Build.MODEL}
        Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
        App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
    """.trimIndent()
    
    try {
        // Restrict to Gmail only
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            setPackage("com.google.android.gm") // Gmail package
            putExtra(Intent.EXTRA_EMAIL, arrayOf(DEVELOPER_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, "[Bug Report] PDF Toolkit")
            putExtra(Intent.EXTRA_TEXT, deviceInfo)
        }
        
        context.startActivity(intent)
    } catch (e: Exception) {
        // Gmail not installed
        Toast.makeText(
            context, 
            "Gmail app is required. Please install Gmail or send bug reports to $DEVELOPER_EMAIL", 
            Toast.LENGTH_LONG
        ).show()
    }
}

private fun openPlayStore(context: Context) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.yourname.pdftoolkit"))
        )
    } catch (e: Exception) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.yourname.pdftoolkit"))
        )
    }
}

private fun openPrivacyPolicy(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://karna14314.github.io/Pdf_Tools/"))
    context.startActivity(intent)
}
