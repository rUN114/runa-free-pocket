package cn.run.freepocket

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Journal-level guarantees for the coordinator's reserve, persist, then permit sequence. */
class ReservationSaveTest {
    @Test fun rolledBackUnissuedReservationRestoresTheEntryByteForByte() {
        val journal = journal()
        val before = journal.serialize()
        val checkpoint = journal.reserve("fab:fixture", "product")
        assertTrue(journal.entry("fab:fixture")!!.getBoolean("productAuthorized"))
        assertEquals("uncertain", journal.entry("fab:fixture")!!.getString("status"))
        journal.rollbackUnissued(checkpoint)
        assertEquals(before, journal.serialize())
    }

    @Test fun rolledBackSubmitKeepsEarlierProductPermitByteForByte() {
        val journal = journal()
        journal.reserve("fab:fixture", "product")
        val before = journal.serialize()
        val checkpoint = journal.reserve("fab:fixture", "submit")
        assertTrue(journal.entry("fab:fixture")!!.getBoolean("submitAuthorized"))
        journal.rollbackUnissued(checkpoint)
        assertEquals(before, journal.serialize())
        assertTrue(journal.entry("fab:fixture")!!.getBoolean("productAuthorized"))
    }

    @Test fun crashedProcessStillTreatsAnIndeterminateDiskWriteAsUncertain() {
        val journal = journal()
        val checkpoint = journal.reserve("fab:fixture", "product")
        val possibleDiskValue = journal.serialize() // A failed commit is not proof that disk stayed unchanged.
        journal.rollbackUnissued(checkpoint)
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
