package cn.run.freepocket

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ReservationSaveTest {
    @Test fun failedFirstWriteRestoresOnlyTheUnissuedReservation() {
        val journal = journal()
        val before = journal.serialize()
        assertFalse(journal.reserveAndSave("fab:fixture", "product") {
            assertTrue(journal.entry("fab:fixture")!!.getBoolean("productAuthorized"))
            assertEquals("uncertain", journal.entry("fab:fixture")!!.getString("status"))
            false
        })
        assertEquals(before, journal.serialize())
    }

    @Test fun failedSubmitWriteKeepsEarlierProductPermitByteForByte() {
        val journal = journal()
        assertTrue(journal.reserveAndSave("fab:fixture", "product") { true })
        val before = journal.serialize()
        assertFalse(journal.reserveAndSave("fab:fixture", "submit") { false })
        assertEquals(before, journal.serialize())
        assertTrue(journal.entry("fab:fixture")!!.getBoolean("productAuthorized"))
    }

    @Test fun storageExceptionRestoresMemoryButRemainsAnError() {
        val journal = journal()
        val before = journal.serialize()
        assertThrows(IOException::class.java) {
            journal.reserveAndSave("fab:fixture", "product") { throw IOException("fixture storage failure") }
        }
        assertEquals(before, journal.serialize())
    }

    @Test fun crashedProcessStillTreatsAnIndeterminateDiskWriteAsUncertain() {
        val journal = journal()
        var possibleDiskValue = ""
        assertFalse(journal.reserveAndSave("fab:fixture", "product") {
            possibleDiskValue = journal.serialize() // A failed commit is not proof that disk stayed unchanged.
            false
        })
        val restarted = ClaimJournal(possibleDiskValue)
        assertEquals("paused", restarted.current()!!.getString("state"))
        assertEquals("uncertain", restarted.entry("fab:fixture")!!.getString("status"))
        assertTrue(restarted.entry("fab:fixture")!!.getBoolean("productAuthorized"))
    }

    private fun journal(): ClaimJournal {
        val items = JSONArray().put(JSONObject().put("key", "fab:fixture").put("category", "fab")
            .put("title", "Fixture").put("status", "unowned"))
        return ClaimJournal("{}").apply {
            start("CN", listOf("fab"), items)
            bind("fab", "fixture-owner", "Fixture")
            plan("fab", items, System.currentTimeMillis())
            working("fab:fixture")
        }
    }
}
