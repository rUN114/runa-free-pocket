package cn.run.freepocket

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaimJournalTest {
    private fun item(key: String, category: String, status: String): JSONObject =
        JSONObject().put("key", key).put("category", category).put("title", key)
            .put("status", status).put("start", NOW - 1000).put("end", "2099-01-01T00:00:00Z")

    @Test fun resumeBusinessRefusalIsDistinctFromCorruptJson() {
        val empty = assertThrows(ClaimJournal.Rejected::class.java) { ClaimJournal("{}").resume("CN") }
        assertEquals("没有可继续的领取记录", empty.reason)
        val journal = ready()
        val previous = journal.serialize()
        val changed = assertThrows(ClaimJournal.Rejected::class.java) { journal.resume("HK") }
        assertEquals("账号地区已变化，请开始新的领取任务", changed.reason)
        assertEquals(previous, journal.serialize())
    }

    private fun ready(): ClaimJournal {
        val journal = ClaimJournal("{}")
        journal.start("CN", listOf("pc"), null)
        journal.bind("pc", "account-a", "示例账号")
        journal.plan("pc", JSONArray().put(item("pc-a", "pc", "unowned")), NOW)
        return journal
    }

    @Test
    fun allCategoriesRetainIndependentProductKeysAndOwnedAndFutureAreSkipped() {
        val journal = ClaimJournal("{}")
        journal.start("CN", listOf("pc", "android", "fab"), null)
        val catalogue = JSONArray().put(item("pc-a", "pc", "owned")).put(item("mobile-a", "android", "unowned"))
            .put(item("fab-a", "fab", "unowned")).put(item("future", "pc", "unowned").put("start", NOW + 10000))
        for (category in journal.categories()) {
            journal.bind(category, if (category == "fab") "fab-account" else "epic-account", "示例")
            journal.plan(category, catalogue, NOW)
        }
        assertEquals("owned", journal.entry("pc-a")!!.getString("status"))
        assertEquals("skipped", journal.entry("future")!!.getString("status"))
        assertEquals(1, journal.work("android").size)
        assertEquals(1, journal.work("fab").size)
    }

    @Test
    fun interruptedUnsubmittedWorkResumesAfterFreshInventory() {
        val journal = ready()
        journal.working("pc-a")
        val restored = ClaimJournal(journal.serialize())
        assertEquals("paused", restored.current()!!.getString("state"))
        restored.resume("CN")
        restored.bind("pc", "account-a", "示例账号")
        restored.plan("pc", JSONArray().put(item("pc-a", "pc", "unowned")), NOW)
        assertEquals("pending", restored.entry("pc-a")!!.getString("status"))
    }

    @Test
    fun crashAfterCheckpointNeverReplaysAnUnconfirmedOrder() {
        val journal = ready()
        journal.reserve("pc-a", "product")
        val restored = ClaimJournal(journal.serialize())
        restored.resume("CN")
        restored.bind("pc", "account-a", "示例账号")
        restored.plan("pc", JSONArray().put(item("pc-a", "pc", "unowned")), NOW)
        assertEquals("uncertain", restored.entry("pc-a")!!.getString("status"))
        assertThrows(JSONException::class.java) { restored.reserve("pc-a", "product") }
        restored.result("pc-a", "unowned", "暂未入库")
        assertEquals("uncertain", restored.entry("pc-a")!!.getString("status"))
        assertThrows(JSONException::class.java) { restored.prechecked("pc-a") }
    }

    @Test
    fun productAndSubmitEachPermitOnlyOnceAndRequireRecognizedStages() {
        val journal = ready()
        journal.reserve("pc-a", "product")
        journal.reserve("pc-a", "submit")
        assertThrows(JSONException::class.java) { journal.reserve("pc-a", "submit") }
        assertThrows(JSONException::class.java) { journal.reserve("pc-a", "other") }
        journal.result("pc-a", "owned", "官方确认")
        assertEquals("claimed", journal.entry("pc-a")!!.getString("status"))
    }

    @Test
    fun newBatchAndTrimmedHistoryCannotBypassOldOrderReservations() {
        val journal = ready()
        journal.reserve("pc-a", "product")
        journal.pause("中断")
        for (i in 0 until 22) journal.start("CN", listOf("pc"), null)
        journal.bind("pc", "account-a", "示例账号")
        journal.plan("pc", JSONArray().put(item("pc-a", "pc", "unowned")), NOW)
        assertEquals(20, journal.publicRuns().length())
        assertEquals("uncertain", journal.entry("pc-a")!!.getString("status"))
        assertThrows(JSONException::class.java) { journal.reserve("pc-a", "product") }
    }

    @Test
    fun reservationsAreAccountScopedAndResumeRejectsAnotherIdentityOrRegion() {
        val journal = ready()
        journal.reserve("pc-a", "product")
        journal.pause("中断")
        assertThrows(JSONException::class.java) { journal.bind("pc", "account-b", "另一个账号") }
        assertThrows(JSONException::class.java) { journal.bind("pc", "", "登录已失效") }
        assertThrows(ClaimJournal.Rejected::class.java) { journal.resume("HK") }
        journal.start("CN", listOf("pc"), null)
        journal.bind("pc", "account-b", "另一个账号")
        journal.plan("pc", JSONArray().put(item("pc-a", "pc", "unowned")), NOW)
        assertEquals("pending", journal.entry("pc-a")!!.getString("status"))
    }

    @Test
    fun failedUnreadAndCachedOwnershipCannotBecomeAClaimTask() {
        for (status in listOf("unknown", "error")) {
            val journal = ready()
            journal.plan("pc", JSONArray().put(item("pc-a", "pc", status)), NOW)
            assertEquals(1, journal.work("pc").size)
            assertTrue(journal.entry("pc-a")!!.getBoolean("pageCheck"))
            assertThrows(JSONException::class.java) { journal.reserve("pc-a", "product") }
            journal.prechecked("pc-a")
            journal.reserve("pc-a", "product")
        }
        val journal = ready()
        journal.plan("pc", JSONArray().put(item("pc-a", "pc", "unowned").put("cached", true)), NOW)
        assertTrue(journal.entry("pc-a")!!.getBoolean("pageCheck"))
        val reserved = ready()
        reserved.reserve("pc-a", "product")
        reserved.plan("pc", JSONArray().put(item("pc-a", "pc", "owned").put("cached", true)), NOW)
        assertEquals("uncertain", reserved.entry("pc-a")!!.getString("status"))
    }

    @Test
    fun successfulAndOwnedEntriesAreNotRequeuedWhileFailedOnesCanRetry() {
        val journal = ready()
        journal.result("pc-a", "unowned", "网络故障前未提交")
        assertEquals("failed", journal.entry("pc-a")!!.getString("status"))
        journal.plan("pc", JSONArray().put(item("pc-a", "pc", "unowned")), NOW)
        assertEquals(1, journal.work("pc").size)
        journal.result("pc-a", "owned", "官方确认")
        journal.plan("pc", JSONArray().put(item("pc-a", "pc", "unowned")), NOW)
        assertTrue(journal.work("pc").isEmpty())
    }

    @Test
    fun retryKeepsOriginalTargetsAndDoesNotAddNewlyPublishedProducts() {
        val journal = ready()
        journal.plan("pc", JSONArray().put(item("pc-a", "pc", "unowned")).put(item("pc-b", "pc", "unowned")), NOW)
        assertNull(journal.entry("pc-b"))
    }

    @Test
    fun publicHistoryContainsNoAccountIdentifiersOrPrivateReservations() {
        val journal = ready()
        journal.reserve("pc-a", "product")
        val json = journal.publicRuns().toString()
        assertFalse(json.contains("account-a"))
        assertFalse(json.contains("reservations"))
        assertFalse(json.contains("targets"))
        assertTrue(json.contains("示例账号"))
    }

    @Test
    fun currentTimeAllowsStartedPreviewsAndRejectsEndedPromotions() {
        assertTrue(cn.run.freepocket.data.LegacyCatalogState.claimableNow(item("pc-a", "pc", "unowned").put("availability", "upcoming"), NOW))
        assertFalse(cn.run.freepocket.data.LegacyCatalogState.claimableNow(item("pc-a", "pc", "unowned").put("end", "2001-01-01T00:00:00Z"), NOW))
    }

    @Test
    fun invalidPersistedVersionIsNotSilentlyReplacedWithAnEmptyJournal() {
        assertThrows(JSONException::class.java) { ClaimJournal("{\"schema\":2}") }
        assertThrows(JSONException::class.java) { ClaimJournal("broken") }
    }

    companion object {
        private const val NOW = 1800000000000L
    }
}
