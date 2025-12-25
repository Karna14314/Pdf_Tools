package com.yourname.pdftoolkit.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Text(
            text = "PDF Toolkit",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // Feature Grid
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(pdfFeatures) { feature ->
                FeatureCard(
                    feature = feature,
                    onClick = { /* TODO: Navigate to feature */ }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureCard(
    feature: PdfFeature,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = feature.icon,
                contentDescription = feature.title,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = feature.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = feature.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

data class PdfFeature(
    val title: String,
    val description: String,
    val icon: ImageVector
)

val pdfFeatures = listOf(
    PdfFeature(
        title = "Merge PDFs",
        description = "Combine multiple PDF files into one",
        icon = Icons.Default.MergeType
    ),
    PdfFeature(
        title = "Split PDF",
        description = "Split a PDF into multiple files",
        icon = Icons.Default.CallSplit
    ),
    PdfFeature(
        title = "Compress PDF",
        description = "Reduce PDF file size",
        icon = Icons.Default.Compress
    ),
    PdfFeature(
        title = "Convert Images",
        description = "Convert images to PDF",
        icon = Icons.Default.Image
    ),
    PdfFeature(
        title = "Extract Pages",
        description = "Extract specific pages from PDF",
        icon = Icons.Default.ContentCopy
    ),
    PdfFeature(
        title = "Rotate Pages",
        description = "Rotate PDF pages",
        icon = Icons.Default.RotateRight
    ),
    PdfFeature(
        title = "Add Security",
        description = "Password protect your PDFs",
        icon = Icons.Default.Security
    ),
    PdfFeature(
        title = "View Metadata",
        description = "View and edit PDF properties",
        icon = Icons.Default.Info
    )
)