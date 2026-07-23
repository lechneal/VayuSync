package com.lechneralexander.vayusync.extensions

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure-JVM tests for the numeric/duration formatting helpers. No Android framework
 * involved, so these run without Robolectric. Decimal separators are locale-dependent
 * (String.format uses the default locale), so float assertions normalise ',' -> '.'.
 */
class ExtensionsFormatTest {

    private fun norm(s: String) = s.replace(',', '.')

    @Test
    fun formatBytes_underOneKilobyte_usesBytes() {
        assertThat(0L.formatBytes()).isEqualTo("0 B")
        assertThat(512L.formatBytes()).isEqualTo("512 B")
        assertThat(1023L.formatBytes()).isEqualTo("1023 B")
    }

    @Test
    fun formatBytes_scalesToKilobytesAndMegabytes() {
        assertThat(norm(1024L.formatBytes())).isEqualTo("1.0 KB")
        assertThat(norm((1536L).formatBytes())).isEqualTo("1.5 KB")
        assertThat(norm((1024L * 1024).formatBytes())).isEqualTo("1.0 MB")
        assertThat(norm((5L * 1024 * 1024 * 1024).formatBytes())).isEqualTo("5.0 GB")
    }

    @Test
    fun formatDuration_secondsMinutesHours() {
        assertThat(0.formatDuration()).isEqualTo("0 s")
        assertThat(45.formatDuration()).isEqualTo("45 s")
        assertThat(59.formatDuration()).isEqualTo("59 s")
        assertThat(60.formatDuration()).isEqualTo("1 min 0 s")
        assertThat(90.formatDuration()).isEqualTo("1 min 30 s")
        assertThat(3599.formatDuration()).isEqualTo("59 min 59 s")
        assertThat(3600.formatDuration()).isEqualTo("1 h 0 min")
        assertThat(3661.formatDuration()).isEqualTo("1 h 1 min")
    }

    @Test
    fun formatTimestamp_nonPositiveIsNotAvailable() {
        assertThat(0L.formatTimestamp()).isEqualTo("N/A")
        assertThat((-1L).formatTimestamp()).isEqualTo("N/A")
    }

    @Test
    fun formatTimestamp_positiveProducesDatePattern() {
        // Avoid timezone flakiness: only assert the shape (yyyy-MM-dd HH:mm), not the value.
        val formatted = 1_700_000_000_000L.formatTimestamp()
        assertThat(formatted).matches("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""")
    }
}
