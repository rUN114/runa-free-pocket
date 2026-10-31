package cn.run.freepocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Ported from the standalone tests/java harness; keeps the pagination-completeness checks in the Gradle suite. */
class EpicEntitlementsTest {
    private fun grant(namespace: String, id: String, active: Boolean) = EpicEntitlements.Grant(namespace, id, active)

    private val firstPage: List<EpicEntitlements.Grant> =
        (0 until EpicEntitlements.PAGE_SIZE).map { grant("ns", "item-$it", true) }

    @Test
    fun emptyAuthenticatedResultStaysEmpty() {
        assertTrue(EpicEntitlements.readAll { _, _ -> emptyList() }.isEmpty())
    }

    @Test
    fun deduplicatesExactGrantsAndExcludesInactiveOrNonGameRecords() {
        val filtered = EpicEntitlements.readAll { _, _ -> listOf(
            grant("ns", "a", true), grant("ns", "a", true), grant("ns", "revoked", false),
            grant("other", "a", true), grant("", "non-game", true)) }
        assertEquals(2, filtered.size)
        assertEquals("other", filtered[1].namespace)
    }

    @Test
    fun readsTheSecondPageBeforePublishingOwnership() {
        val calls = AtomicInteger()
        val all = EpicEntitlements.readAll { start, count ->
            assertEquals(1000, count.toLong())
            val page = calls.getAndIncrement()
            assertEquals(page * 1000L, start.toLong())
            if (page == 0) firstPage else listOf(grant("ns", "last", true))
        }
        assertEquals(2, calls.get())
        assertEquals(1001, all.size)
    }

    @Test
    fun aLaterPageFailureMustNotReturnPartialOwnership() {
        val error = assertThrows(IOException::class.java) {
            EpicEntitlements.readAll { start, _ -> if (start > 0) throw IOException("second page failed") else firstPage }
        }
        assertTrue(error.message!!.isNotEmpty())
    }

    @Test
    fun unauthorizedResponsesMustNotBecomeAnEmptyLibrary() {
        val error = assertThrows(IOException::class.java) {
            EpicEntitlements.readAll { _, _ -> throw IOException("HTTP 401") }
        }
        assertTrue(error.message!!.isNotEmpty())
    }

    @Test
    fun anEndlessFullPageResponseMustNotBecomeACompletedLibrary() {
        val error = assertThrows(IOException::class.java) {
            EpicEntitlements.readAll { _, _ -> firstPage }
        }
        assertTrue(error.message!!.isNotEmpty())
    }

    @Test
    fun anUnexpectedPaginationShapeMustFailExplicitly() {
        val oversized = ArrayList(firstPage)
        oversized.add(grant("ns", "overflow", true))
        val error = assertThrows(IOException::class.java) {
            EpicEntitlements.readAll { _, _ -> oversized }
        }
        assertTrue(error.message!!.isNotEmpty())
    }

    @Test
    fun emptyAndSingleItemSourcesStillReadCompletely() {
        assertEquals(Collections.emptyList<EpicEntitlements.Grant>(), EpicEntitlements.readAll { _, _ -> emptyList() })
        assertEquals(1, EpicEntitlements.readAll { _, _ -> listOf(grant("ns", "only", true)) }.size)
    }
}
