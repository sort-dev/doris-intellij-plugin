package dev.sort.doris.sql

import dev.sort.doris.DorisCatalogs

/**
 * Route B replay flag (0.5.0 graduation: DEFAULT ON).
 *
 * `-Ddoris.replay.poc=false` falls back to the lenient-only parse path (the pre-0.5.0 default).
 * Same semantics/rationale as [DorisCatalogs]: only an explicit `"false"` disables; the property is
 * read per access so tests can pin a mode across the plugin/test classloader split (see
 * [DorisCatalogs.enabled] for the full explanation). The test suite pins `false` globally in
 * build.gradle.kts so the flag-off golden corpus stays the baseline; DorisReplayPocTest overrides
 * per-test.
 */
object DorisReplay {

    const val PROPERTY: String = "doris.replay.poc"

    val enabled: Boolean
        get() = DorisCatalogs.isEnabledValue(System.getProperty(PROPERTY))
}
