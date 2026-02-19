package com.yourname.pdftoolkit.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FileManagerTest {

    @Test
    fun formatFileSize_isCorrect() {
        assertEquals("0 B", FileManager.formatFileSize(0))
        assertEquals("0 B", FileManager.formatFileSize(-100))

        assertEquals("100 B", FileManager.formatFileSize(100))
        assertEquals("1023 B", FileManager.formatFileSize(1023))

        // 1024 bytes -> 1 KB
        assertEquals("1 KB", FileManager.formatFileSize(1024))

        // 1500 / 1024 = 1.4648... -> 1.46 KB
        assertEquals("1.46 KB", FileManager.formatFileSize(1500))

        // 100 KB
        assertEquals("100 KB", FileManager.formatFileSize(102400))

        // 500 KB
        assertEquals("500 KB", FileManager.formatFileSize(512000))

        // < 1 MB (1048575 bytes) -> 1023.999 KB -> 1024 KB
        assertEquals("1024 KB", FileManager.formatFileSize(1048575))

        // 1 MB -> 1.00 MB (MB/GB keeps 2 decimals)
        assertEquals("1.00 MB", FileManager.formatFileSize(1048576))

        // 1.5 MB -> 1.50 MB
        assertEquals("1.50 MB", FileManager.formatFileSize(1572864))

        // 20 MB -> 20.00 MB
        assertEquals("20.00 MB", FileManager.formatFileSize(20971520))

        // 25 MB -> 25.00 MB
        assertEquals("25.00 MB", FileManager.formatFileSize(26214400))

        // 1 GB -> 1.00 GB
        assertEquals("1.00 GB", FileManager.formatFileSize(1073741824))
    }
}
