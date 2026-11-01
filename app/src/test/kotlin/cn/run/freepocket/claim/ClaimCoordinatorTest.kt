package cn.run.freepocket.claim

import cn.run.freepocket.ClaimJournal
import cn.run.freepocket.data.EpicAccess
import cn.run.freepocket.data.EpicOwnership
import cn.run.freepocket.data.PocketBackend
import cn.run.freepocket.model.CatalogIdentity
import cn.run.freepocket.model.CatalogItem
import cn.run.freepocket.model.Category
import cn.run.freepocket.model.Ownership
import cn.run.freepocket.web.BrowserRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClaimCoordinatorTest {
    @Test fun initialBrowserPresentationDoesNotMasqueradeAsAWebResponse() = runTest {
        val backend = FakeBackend()
        val statuses = mutableListOf<String>()
        var browser: BrowserRequest? = null
        val coordinator = ClaimCoordinator(backend, this, {}, { browser = it }, statuses::add, {})
        coordinator.command(JSONObject().put("action", "refresh").put("category", "android"))
        assertEquals("discover", browser!!.mode)
        assertTrue(statuses.isEmpty()) // The browser owns the initial 40-second read deadline.
        coordinator.onWebMessage(JSONObject().put("nonce", browser!!.id).put("type", "waiting")
            .put("message", "请完成官网验证"), STORE, true) {}
        assertEquals(1, statuses.size)
        assertTrue(statuses.single().contains("请完成官网验证"))
        coordinator.close()
    }

    @Test fun checkpointFailureNeverGrantsPagePermission() = runTest {
        val fixture = Fixture(this)
        fixture.beginClaim()
        fixture.backend.canSave = false
        val replies = mutableListOf<JSONObject>()
        fixture.coordinator.onWebMessage(fixture.actionReady(), STORE, true, replies::add)
        assertTrue(replies.isEmpty())
        assertFalse(fixture.coordinator.busy)
        assertNull(fixture.browser)
        assertTrue(fixture.coordinator.message.contains("无法保存"))
        assertEquals("pending", fixture.backend.journal.entry(KEY)!!.getString("status"))
        assertEquals("paused", fixture.backend.journal.current()!!.getString("state"))
        fixture.coordinator.close()
    }

    @Test fun storageRecoveryCanRetryAnActionWhosePermissionWasNeverSent() = runTest {
        val fixture = Fixture(this)
        fixture.beginClaim()
        fixture.backend.canSave = false
        val replies = mutableListOf<JSONObject>()
        fixture.coordinator.onWebMessage(fixture.actionReady(), STORE, true, replies::add)
        assertTrue(replies.isEmpty())
        fixture.backend.canSave = true
        fixture.coordinator.command(JSONObject().put("action", "resumeBatch"))
        runCurrent()
        assertEquals("claim", fixture.browser?.mode)
        fixture.coordinator.onWebMessage(fixture.actionReady(), STORE, true, replies::add)
        assertEquals("product", replies.single().getString("stage"))
        fixture.coordinator.close()
    }

    @Test fun failedSubmitCheckpointCannotUndoPreviouslyGrantedProductPermission() = runTest {
        val fixture = Fixture(this)
        fixture.beginClaim()
        val replies = mutableListOf<JSONObject>()
        fixture.coordinator.onWebMessage(fixture.actionReady(), STORE, true, replies::add)
        fixture.backend.canSave = false
        fixture.coordinator.onWebMessage(fixture.actionReady().put("stage", "submit"),
            "https://www.epicgames.com", false, replies::add)
        assertEquals(1, replies.size)
        assertEquals("uncertain", fixture.backend.journal.entry(KEY)!!.getString("status"))
        assertTrue(fixture.backend.journal.entry(KEY)!!.getBoolean("productAuthorized"))
        assertFalse(fixture.backend.journal.entry(KEY)!!.optBoolean("submitAuthorized"))
        fixture.backend.canSave = true
        fixture.coordinator.command(JSONObject().put("action", "resumeBatch"))
        runCurrent()
        assertFalse(fixture.browser?.mode == "claim")
        assertEquals(1, replies.size)
        fixture.coordinator.close()
    }

    @Test fun oldNonceAndIncompleteMessagesCannotChangeJournal() = runTest {
        val fixture = Fixture(this)
        fixture.beginClaim()
        val before = fixture.backend.journal.serialize()
        val replies = mutableListOf<JSONObject>()
        fixture.coordinator.onWebMessage(fixture.actionReady().put("nonce", "obsolete"), STORE, true, replies::add)
        fixture.coordinator.onWebMessage(JSONObject().put("nonce", fixture.browser!!.id).put("type", "result"), STORE, true, replies::add)
        assertEquals(before, fixture.backend.journal.serialize())
        assertTrue(replies.isEmpty())
        assertTrue(fixture.coordinator.busy)
        fixture.coordinator.close()
    }

    @Test fun userInterruptPreservesUncertainReservation() = runTest {
        val fixture = Fixture(this)
        fixture.beginClaim()
        val replies = mutableListOf<JSONObject>()
        fixture.coordinator.onWebMessage(fixture.actionReady(), STORE, true, replies::add)
        assertEquals("actionPermit", replies.single().getString("type"))
        fixture.coordinator.back()
        assertEquals("paused", fixture.backend.journal.current()!!.getString("state"))
        assertEquals("uncertain", fixture.backend.journal.entry(KEY)!!.getString("status"))
        assertFalse(fixture.coordinator.busy)
        fixture.coordinator.command(JSONObject().put("action", "resumeBatch"))
        runCurrent()
        assertFalse(fixture.browser?.mode == "claim")
        assertTrue(fixture.backend.journal.serialize().contains("reservations"))
        fixture.coordinator.close()
    }

    @Test fun changedAccountCannotReceivePermitForOriginalRun() = runTest {
        val fixture = Fixture(this)
        fixture.beginClaim()
        fixture.backend.epic.owner = "another-account"
        val replies = mutableListOf<JSONObject>()
        fixture.coordinator.onWebMessage(fixture.actionReady(), STORE, true, replies::add)
        assertTrue(replies.isEmpty())
        assertEquals("account-original", fixture.backend.journal.current()!!.getJSONObject("owners").getJSONObject("epic").getString("id"))
        assertFalse(fixture.coordinator.busy)
        fixture.coordinator.close()
    }

    @Test fun pagePermissionsAreSingleUseForEachStage() = runTest {
        val fixture = Fixture(this)
        fixture.beginClaim()
        val request = fixture.actionReady()
        val replies = mutableListOf<JSONObject>()
        fixture.coordinator.onWebMessage(request, STORE, true, replies::add)
        fixture.coordinator.onWebMessage(JSONObject(request.toString()).put("stage", "submit"), "https://www.epicgames.com", false, replies::add)
        assertEquals(listOf("product", "submit"), replies.map { it.getString("stage") })
        fixture.coordinator.onWebMessage(request, STORE, true, replies::add)
        assertEquals(2, replies.size)
        assertEquals("uncertain", fixture.backend.journal.entry(KEY)!!.getString("status"))
        fixture.coordinator.close()
    }

    @Test fun untrustedOriginsAndWrongFramesCannotGrantPermission() = runTest {
        val fixture = Fixture(this)
        fixture.beginClaim()
        val before = fixture.backend.journal.serialize()
        val replies = mutableListOf<JSONObject>()
        fixture.coordinator.onWebMessage(fixture.actionReady(), "https://untrusted.example", true, replies::add)
        fixture.coordinator.onWebMessage(fixture.actionReady(), STORE, false, replies::add)
        fixture.coordinator.onWebMessage(fixture.actionReady().apply { remove("stage") }, STORE, true, replies::add)
        assertTrue(replies.isEmpty())
        assertEquals(before, fixture.backend.journal.serialize())
        fixture.coordinator.close()
    }

    @Test fun unknownOwnershipMustPassReadonlyCheckBeforeClaim() = runTest {
        val fixture = Fixture(this)
        fixture.backend.epic.knownOwnership = false
        fixture.coordinator.command(claimCommand())
        runCurrent()
        assertEquals("check", fixture.browser!!.mode)
        val firstNonce = fixture.browser!!.id
        val replies = mutableListOf<JSONObject>()
        fixture.coordinator.onWebMessage(fixture.actionReady(), STORE, true, replies::add)
        assertTrue(replies.isEmpty())
        fixture.coordinator.onWebMessage(JSONObject().put("nonce", firstNonce).put("type", "result").put("status", "unowned"), STORE, true, replies::add)
        assertEquals("claim", fixture.browser!!.mode)
        assertNotEquals(firstNonce, fixture.browser!!.id)
        fixture.coordinator.close()
    }

    @Test fun retryAfterPermissionOnlyVerifiesAndKeepsReservation() = runTest {
        val fixture = Fixture(this)
        fixture.beginClaim()
        val originalNonce = fixture.browser!!.id
        fixture.coordinator.onWebMessage(fixture.actionReady(), STORE, true) {}
        fixture.coordinator.retry()
        runCurrent()
        assertNull(fixture.browser)
        assertTrue(fixture.coordinator.busy)
        assertEquals(2, fixture.backend.epic.reads)
        advanceTimeBy(3000)
        runCurrent()
        assertEquals(3, fixture.backend.epic.reads)
        assertFalse(fixture.coordinator.busy)
        assertEquals("uncertain", fixture.backend.journal.entry(KEY)!!.getString("status"))
        val replies = mutableListOf<JSONObject>()
        fixture.coordinator.onWebMessage(JSONObject().put("nonce", originalNonce).put("type", "actionReady").put("stage", "submit"), STORE, true, replies::add)
        assertTrue(replies.isEmpty())
        fixture.coordinator.close()
    }

    @Test fun resumeInAnotherRegionShowsTheRejectionReason() = runTest {
        val fixture = Fixture(this)
        fixture.beginClaim()
        fixture.coordinator.back()
        fixture.backend.country = "HK"
        fixture.coordinator.command(JSONObject().put("action", "resumeBatch"))
        assertEquals("账号地区已变化，请开始新的领取任务", fixture.coordinator.message)
        assertEquals("paused", fixture.backend.journal.current()!!.getString("state"))
        assertFalse(fixture.coordinator.busy)
        fixture.coordinator.close()
    }

    @Test fun readonlyRetryBudgetSurvivesNonceChanges() = runTest {
        val fixture = Fixture(this)
        fixture.coordinator.command(JSONObject().put("action", "refresh").put("category", "android"))
        val first = fixture.browser!!
        assertEquals("discover", first.mode)
        assertTrue(first.autoRetryAllowed)
        fixture.coordinator.retry()
        assertNotEquals(first.id, fixture.browser!!.id)
        assertFalse(fixture.browser!!.autoRetryAllowed)
        fixture.coordinator.retry()
        assertFalse(fixture.browser!!.autoRetryAllowed)
        fixture.coordinator.close()
    }

    @Test fun pendingSessionCleanupBlocksAllNewWorkUntilSuccessful() = runTest {
        val fixture = Fixture(this)
        fixture.backend.resetPending = true
        fixture.coordinator.start()
        assertEquals(1, fixture.clearRequests)
        assertTrue(fixture.coordinator.busy)
        fixture.coordinator.command(claimCommand())
        assertNull(fixture.backend.journal.current())
        assertEquals(0, fixture.backend.fetches)
        fixture.coordinator.onSessionCleared(false)
        assertTrue(fixture.backend.resetPending)
        assertTrue(fixture.coordinator.message.contains("清理未完成"))
        fixture.coordinator.onSessionCleared(true)
        assertFalse(fixture.backend.resetPending)
        assertFalse(fixture.coordinator.busy)
        fixture.coordinator.close()
    }

    @Test fun closePausesTheRunWithoutCancellingTheOwnerScope() = runTest {
        val fixture = Fixture(this)
        fixture.beginClaim()
        fixture.coordinator.onWebMessage(fixture.actionReady(), STORE, true) {}
        fixture.coordinator.close()
        assertEquals("paused", fixture.backend.journal.current()!!.getString("state"))
        assertEquals("uncertain", fixture.backend.journal.entry(KEY)!!.getString("status"))
        assertFalse(fixture.coordinator.busy)
        assertTrue(coroutineContext[kotlinx.coroutines.Job]!!.isActive)
    }

    private class Fixture(private val scope: TestScope) {
        val backend = FakeBackend()
        var browser: BrowserRequest? = null
        var clearRequests = 0
        val coordinator = ClaimCoordinator(backend, scope, {}, { browser = it }, {}, { clearRequests++ })
        fun beginClaim() {
            coordinator.command(claimCommand())
            scope.runCurrent()
            assertEquals("Selected unowned item should reach its official claim page", "claim", browser?.mode)
        }
        fun actionReady(): JSONObject = JSONObject().put("nonce", browser!!.id).put("type", "actionReady").put("stage", "product")
    }

    private class FakeBackend : PocketBackend {
        override var items = JSONArray().put(item().toJson())
        override var updated = JSONObject()
        override var fabAccount = JSONObject()
        override var country = "CN"
        override var fabTier = ""
        override var journalError = ""
        override val journal = ClaimJournal("{}")
        override val initialMessage = ""
        override var resetPending = false
        override val epic = FakeEpic()
        var canSave = true
        var fetches = 0
        override suspend fun fetchPcPromotions(country: String): String {
            fetches++
            return JSONObject().put("data", JSONObject().put("Catalog", JSONObject().put("searchStore", JSONObject().put("elements", JSONArray().put(
            JSONObject().put("id", "offer").put("namespace", "ns").put("title", "Game").put("productSlug", "game")
                .put("items", JSONArray().put(JSONObject().put("namespace", "ns").put("id", "entitlement")))
                .put("price", JSONObject().put("totalPrice", JSONObject().put("discountPrice", 0)))
                .put("promotions", JSONObject().put("promotionalOffers", JSONArray().put(JSONObject().put("promotionalOffers", JSONArray().put(JSONObject()
                    .put("startDate", "2020-01-01T00:00:00Z").put("endDate", "2099-01-01T00:00:00Z").put("discountSetting", JSONObject().put("discountPercentage", 0)))))))
        ))))).toString()
        }
        override fun scriptSource() = "// test website transport"
        override fun saveCache() = Unit
        override fun saveJournal() = canSave
        override fun setResetPending(pending: Boolean): Boolean { resetPending = pending; return canSave }
    }

    private class FakeEpic : EpicAccess {
        var owner = "account-original"
        var isConnected = true
        var knownOwnership = true
        var reads = 0
        override fun connected() = isConnected
        override fun displayName() = "Test account"
        override fun accountId() = owner
        override fun cachedSnapshot(): JSONObject? = null
        override fun disconnect() { isConnected = false }
        override fun save(value: JSONObject) = Unit
        override fun authorizationUrl() = "https://www.epicgames.com/id/login"
        override suspend fun exchange(code: String) = JSONObject()
        override suspend fun readOwnership(items: JSONArray): EpicOwnership {
            reads++
            return EpicOwnership(JSONObject(), JSONObject()
            .put("accountId", owner).put("checkedAt", System.currentTimeMillis()).put("owned", JSONArray())
            .put("checks", if (knownOwnership) JSONArray().put(JSONObject().put("namespace", "ns").put("id", "entitlement").put("owned", false)) else JSONArray()))
        }
    }

    companion object {
        private const val STORE = "https://store.epicgames.com"
        private const val KEY = "pc:ns:offer"
        private fun claimCommand() = JSONObject().put("action", "claim").put("category", "pc").put("keys", JSONArray().put(KEY))
        private fun item() = CatalogItem(KEY, Category.PC, "Game", "$STORE/en-US/p/game", id = "offer", namespace = "ns",
            status = Ownership.UNOWNED, catalogItems = listOf(CatalogIdentity("ns", "entitlement")))
    }
}
