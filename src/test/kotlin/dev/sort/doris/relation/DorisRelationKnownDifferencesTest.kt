package dev.sort.doris.relation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DorisRelationKnownDifferencesTest {
    @Test fun completeLedgerIsValid() {
        val ledger = DorisRelationKnownDifferences
        assertEquals(110, ledger.entries.size)
        assertEquals(mapOf("platform" to 91, "harness" to 6, "support-bug: pending library fix" to 13),
            ledger.entries.values.groupingBy { it.classification }.eachCount())
        ledger.verify(ledger.entries.keys)
    }

    @Test fun newDifferenceRequiresReview() {
        val failure = assertThrows(AssertionError::class.java) {
            DorisRelationKnownDifferences.verify(DorisRelationKnownDifferences.entries.keys + "new-case/resolve")
        }
        assertTrue(failure.message.orEmpty().contains("New: [new-case/resolve]"))
    }

    @Test fun disappearingDifferenceRequiresReview() {
        val key = "left-using-merged-star/resolve"
        val failure = assertThrows(AssertionError::class.java) {
            DorisRelationKnownDifferences.verify(DorisRelationKnownDifferences.entries.keys - key)
        }
        assertTrue(failure.message.orEmpty().contains("disappeared: [$key]"))
    }
}
