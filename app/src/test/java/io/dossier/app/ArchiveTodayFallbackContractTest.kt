package io.dossier.app

import io.dossier.app.data.web.ArchivePageResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveTodayFallbackContractTest {

    @Test
    fun archiveTodayTargetDropsFragmentAndQueryInsteadOfMisroutingNewestLookup() {
        assertEquals(
            "https://example.com/profile",
            ArchivePageResolver.sanitizeArchiveTodayTarget("https://example.com/profile?tab=posts#bio")
        )
    }

    @Test
    fun snapshotValidationAcceptsKnownArchiveHostsOnly() {
        assertEquals(
            "https://archive.ph/AbC12",
            ArchivePageResolver.normalizeArchiveTodaySnapshotUrl("https://archive.ph/AbC12")
        )
        assertEquals(
            "https://archive.today/ZXcv9",
            ArchivePageResolver.normalizeArchiveTodaySnapshotUrl("https://archive.today/ZXcv9")
        )
        assertNull(
            ArchivePageResolver.normalizeArchiveTodaySnapshotUrl("https://archive.ph/newest/https://example.com/profile")
        )
        assertNull(
            ArchivePageResolver.normalizeArchiveTodaySnapshotUrl("https://example.invalid/AbC12")
        )
    }

    @Test
    fun mementoTimestampIsNormalizedForTimelineUse() {
        assertEquals(
            "20151021072800",
            ArchivePageResolver.parseArchiveTimestamp("Wed, 21 Oct 2015 07:28:00 GMT")
        )
        assertNull(ArchivePageResolver.parseArchiveTimestamp("not a date"))
    }

    @Test
    fun archiveCatalogDateRenderingHandlesMissingArchiveTodayTimestamp() {
        assertEquals("unknown date", ArchivePageResolver.displayTimestamp(""))
        assertTrue(ArchivePageResolver.displayTimestamp("20260821").startsWith("2026-08-21"))
    }
}
