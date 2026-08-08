package com.yourname.pdftoolkit.data

import com.yourname.pdftoolkit.data.local.RecentFileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SafUriManagerTest {

    @Test
    fun persistedFile_conversion_isCorrect() {
        val original = PersistedFile(
            uriString = "content://com.yourname.pdftoolkit.provider/shared_files/shared_123.pdf",
            name = "Sample_Report.pdf",
            mimeType = "application/pdf",
            size = 2048L,
            lastAccessed = 1700000000000L
        )

        val entity = RecentFileEntity.fromPersistedFile(original)
        assertEquals(original.uriString, entity.uriString)
        assertEquals(original.name, entity.name)
        assertEquals(original.mimeType, entity.mimeType)
        assertEquals(original.size, entity.size)
        assertEquals(original.lastAccessed, entity.lastAccessed)

        val convertedBack = entity.toPersistedFile()
        assertEquals(original, convertedBack)
    }

    @Test
    fun persistedFile_uriParsing_isCorrect() {
        val file = PersistedFile(
            uriString = "content://com.yourname.pdftoolkit.provider/shared_files/shared_123.pdf",
            name = "Test.pdf",
            mimeType = "application/pdf",
            size = 100L,
            lastAccessed = 1000L
        )

        val uri = file.toUri()
        assertNotNull(uri)
        assertEquals("content", uri?.scheme)
        assertEquals("com.yourname.pdftoolkit.provider", uri?.authority)
    }
}
