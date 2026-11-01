package cn.run.freepocket.claim

import cn.run.freepocket.ClaimJournal
import cn.run.freepocket.data.EpicAccess
import cn.run.freepocket.data.EpicOwnership
import cn.run.freepocket.data.PocketBackend
import cn.run.freepocket.model.CatalogItem
import cn.run.freepocket.model.Category
import cn.run.freepocket.web.BrowserRequest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FabCoordinatorTest {
    @Test fun ownershipRefreshBindsBothOwnedAndUnownedToExactLibraryAccount() = runTest {
        val f = Fixture(this)
        f.begin()
        f.discover()
        f.ownership("library-account", true)
        assertEquals("library-account", f.backend.fabAccount.getString("accountId"))
        val items = f.backend.items
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            assertEquals("library-account", item.getString("ownershipAccount"))
            assertFalse(item.getBoolean("cached"))
            assertEquals(f.checkedAt, item.getLong("checkedAt"))
            assertEquals(if (item.getString("id") == ID) "owned" else "unowned", item.getString("status"))
        }
        assertEquals("owned", f.entry().getString("status"))
        assertFalse(f.coordinator.busy)
        f.coordinator.close()
    }

    @Test fun changedLibraryUidCannotContinueAnOldRun() = runTest {
        val f = Fixture(this)
        f.claim()
        f.coordinator.back()
        f.coordinator.command(JSONObject().put("action", "resumeBatch"))
        f.discover()
        f.ownership("another-library")
        assertFalse(f.coordinator.busy)
        assertEquals(ACCOUNT, f.backend.journal.current()!!.getJSONObject("owners").getJSONObject("fab").getString("id"))
        assertFalse(f.entry().optBoolean("productAuthorized"))
        f.coordinator.close()
    }

    @Test fun accountDriftWhileClaimPageIsOpenCannotReceivePermit() = runTest {
        val f = Fixture(this)
        f.claim()
        f.backend.fabAccount.put("accountId", "another-library")
        f.send(f.ready())
        assertTrue(f.replies.isEmpty())
        assertFalse(f.coordinator.busy)
        assertFalse(f.entry().optBoolean("productAuthorized"))
        f.coordinator.close()
    }

    @Test fun missingTierRejectsClaimBeforeOpeningProduct() = runTest {
        val f = Fixture(this)
        f.backend.fabTier = ""
        f.claim(expectClaim = false)
        assertFalse(f.coordinator.busy)
        assertTrue(f.entry().getString("reason").contains("明确选择"))
        assertFalse(f.entry().optBoolean("productAuthorized"))
        f.coordinator.close()
    }

    @Test fun changedTierCannotContinueOriginalRun() = runTest {
        val f = Fixture(this)
        f.claim()
        f.coordinator.back()
        f.backend.fabTier = "professional"
        f.coordinator.command(JSONObject().put("action", "resumeBatch"))
        f.discover()
        f.ownership()
        assertFalse(f.coordinator.busy)
        assertTrue(f.entry().getString("reason").contains("原任务不一致"))
        assertEquals("personal", f.backend.journal.current()!!.getString("fabTier"))
        f.coordinator.close()
    }

    @Test fun mismatchedProductTierCannotReserveOrGrant() = runTest {
        val f = Fixture(this)
        f.claim()
        f.send(f.ready().put("fabTier", "professional"))
        assertTrue(f.replies.isEmpty())
        assertFalse(f.entry().optBoolean("productAuthorized"))
        assertEquals(0, f.reservations().length())
        f.coordinator.close()
    }

    @Test fun submitRequiresProductLicenseConfirmation() = runTest {
        val f = Fixture(this)
        f.claim()
        f.send(f.ready("submit"))
        assertTrue(f.replies.isEmpty())
        assertFalse(f.entry().optBoolean("submitAuthorized"))
        assertEquals(0, f.reservations().length())
        f.coordinator.close()
    }

    @Test fun productPermitFollowsSavedReservationAndSameNonceVerifiedScript() = runTest {
        val f = Fixture(this)
        f.claim()
        val initial = f.browser!!
        f.events.clear()
        f.send(f.ready()) {
            assertEquals(listOf("save", "browser", "reply"), f.events)
            val saved = ClaimJournal(f.backend.saved)
            assertEquals("personal", saved.entry(KEY)!!.getString("selectedFabTier"))
            assertTrue(saved.entry(KEY)!!.getBoolean("productAuthorized"))
            assertEquals(1, JSONObject(f.backend.saved).getJSONObject("reservations").length())
            assertEquals(initial.id, f.browser!!.id)
            assertNotEquals(initial.script, f.browser!!.script)
            assertTrue(f.browser!!.script!!.contains("\"fabTierVerified\":true"))
            assertTrue(it.getBoolean("fabTierVerified"))
        }
        assertEquals(1, f.replies.size)
        f.events.clear()
        f.send(f.ready("submit")) {
            assertEquals(listOf("save", "reply"), f.events)
            assertTrue(ClaimJournal(f.backend.saved).entry(KEY)!!.getBoolean("submitAuthorized"))
        }
        f.coordinator.close()
    }

    @Test fun failedProductSaveCannotGrantOrCreateAnUnsentReservation() = runTest {
        val f = Fixture(this)
        f.claim()
        f.backend.canSave = false
        f.send(f.ready())
        assertTrue(f.replies.isEmpty())
        assertFalse(f.coordinator.busy)
        assertFalse(f.entry().optBoolean("productAuthorized"))
        assertEquals("pending", f.entry().getString("status"))
        assertEquals("paused", f.backend.journal.current()!!.getString("state"))
        assertEquals(0, f.reservations().length())
        f.coordinator.close()
    }

    @Test fun failedSubmitSavePreservesPreviouslyGrantedProductReservation() = runTest {
        val f = Fixture(this)
        f.claim()
        f.send(f.ready())
        f.backend.canSave = false
        f.send(f.ready("submit"))
        assertEquals(listOf("product"), f.replies.map { it.getString("stage") })
        assertFalse(f.coordinator.busy)
        assertTrue(f.entry().getBoolean("productAuthorized"))
        assertFalse(f.entry().optBoolean("submitAuthorized"))
        assertEquals("uncertain", f.entry().getString("status"))
        assertEquals(1, f.reservations().length())
        f.coordinator.close()
    }

    @Test fun licenseInvalidationStopsAndPreservesPriorReservation() = runTest {
        val f = Fixture(this)
        f.claim()
        f.send(f.ready())
        val submit = f.ready("submit")
        f.send(JSONObject().put("nonce", f.browser!!.id).put("type", "licenseInvalidated"))
        f.send(submit)
        assertFalse(f.coordinator.busy)
        assertEquals(1, f.replies.size)
        assertEquals("uncertain", f.entry().getString("status"))
        assertEquals(1, f.reservations().length())
        f.coordinator.close()
    }

    @Test fun allowedProductAndPaymentNavigationKeepsTierConfirmation() = runTest {
        val f = Fixture(this)
        f.claim()
        f.send(f.ready())
        for (url in listOf(URL, "$FAB/payment/web/purchase", "https://www.epicgames.com/payment/order")) f.coordinator.onNavigation(url)
        f.send(f.ready("submit"))
        assertEquals(listOf("product", "submit"), f.replies.map { it.getString("stage") })
        f.coordinator.close()
    }

    @Test fun leavingProductAndPaymentPagesRevokesTierConfirmation() = runTest {
        val f = Fixture(this)
        f.claim()
        f.send(f.ready())
        f.coordinator.onNavigation("$FAB/listings/another-product")
        f.coordinator.onNavigation(URL)
        f.send(f.ready("submit"))
        assertEquals(1, f.replies.size)
        assertFalse(f.entry().optBoolean("submitAuthorized"))
        assertEquals(1, f.reservations().length())
        f.coordinator.close()
    }

    @Test fun malformedNavigationRevokesTierConfirmation() = runTest {
        val f = Fixture(this)
        f.claim()
        f.send(f.ready())
        val submit = f.ready("submit")
        f.coordinator.onNavigation("https://www.fab.com/%invalid")
        f.send(submit)
        assertFalse(f.coordinator.busy)
        assertEquals("An unparseable navigation must revoke product license confirmation", 1, f.replies.size)
        assertFalse(f.entry().optBoolean("submitAuthorized"))
        f.coordinator.close()
    }

    @Test fun synchronousFailuresAdvanceWithoutReenteringBrowserCallbacks() = runTest {
        val f = Fixture(this)
        var depth = 0
        var maxDepth = 0
        f.onClaim = { request ->
            depth++
            maxDepth = maxOf(maxDepth, depth)
            f.send(JSONObject().put("nonce", request.id).put("type", "blocked").put("message", "local failure"))
            depth--
        }
        f.begin(JSONArray().put(KEY).put("fab:other-material"))
        f.discover()
        f.ownership()
        assertEquals(1, maxDepth)
        assertFalse(f.coordinator.busy)
        assertEquals(2, f.backend.journal.current()!!.getJSONArray("entries").length())
        assertEquals(0, f.reservations().length())
        f.coordinator.close()
    }

    @Test fun uncertainRecoveryOnlyVerifiesAndNeverIssuesAnotherPermit() = runTest {
        val f = Fixture(this)
        f.claim()
        f.send(f.ready())
        f.coordinator.back()
        f.coordinator.command(JSONObject().put("action", "resumeBatch"))
        f.discover()
        f.ownership()
        assertEquals("check", f.browser!!.mode)
        val nonce = f.browser!!.id
        f.send(f.ready())
        f.send(f.ready("submit"))
        assertEquals(1, f.replies.size)
        f.send(JSONObject().put("nonce", nonce).put("type", "result").put("status", "unowned"))
        assertFalse(f.coordinator.busy)
        assertEquals("uncertain", f.entry().getString("status"))
        assertEquals(1, f.reservations().length())
        f.coordinator.close()
    }

    private class Fixture(scope: TestScope) {
        val events = mutableListOf<String>()
        val backend = Backend(events)
        var browser: BrowserRequest? = null
        val replies = mutableListOf<JSONObject>()
        val checkedAt = System.currentTimeMillis()
        var onClaim: ((BrowserRequest) -> Unit)? = null
        val coordinator = ClaimCoordinator(backend, scope, {}, {
            browser = it
            if (it != null) {
                events.add("browser")
                if (it.mode == "claim") onClaim?.invoke(it)
            }
        }, {}, {})
        fun begin(keys: JSONArray = JSONArray().put(KEY)) {
            coordinator.command(JSONObject().put("action", "claim").put("category", "fab").put("keys", keys))
            assertEquals("discover", browser!!.mode)
        }
        fun discover() {
            send(JSONObject().put("nonce", browser!!.id).put("type", "catalogue").put("items", catalogue()))
            assertEquals("syncFab", browser!!.mode)
        }
        fun ownership(account: String = ACCOUNT, owned: Boolean = false) {
            send(JSONObject().put("nonce", browser!!.id).put("type", "ownership").put("accountId", account)
                .put("displayName", "Local test account").put("checkedAt", checkedAt)
                .put("owned", if (owned) JSONArray().put(ID) else JSONArray()))
        }
        fun claim(expectClaim: Boolean = true) {
            begin(); discover(); ownership()
            if (expectClaim) assertEquals("claim", browser?.mode)
        }
        fun ready(stage: String = "product") = JSONObject().put("nonce", browser!!.id).put("type", "actionReady")
            .put("stage", stage).put("fabTier", "personal")
        fun send(data: JSONObject, inspectReply: (JSONObject) -> Unit = {}) {
            coordinator.onWebMessage(data, FAB, true) {
                events.add("reply")
                replies.add(it)
                inspectReply(it)
            }
        }
        fun entry() = backend.journal.entry(KEY)!!
        fun reservations() = JSONObject(backend.journal.serialize()).getJSONObject("reservations")
    }

    private class Backend(private val events: MutableList<String>) : PocketBackend {
        override var items = catalogue()
        override var updated = JSONObject()
        override var fabAccount = JSONObject().put("connected", true).put("accountId", ACCOUNT).put("displayName", "Local test account")
        override var country = "CN"
        override var fabTier = "personal"
        override var journalError = ""
        override val journal = ClaimJournal("{}")
        override val initialMessage = ""
        override val resetPending = false
        override val epic = object : EpicAccess {
            override fun connected() = false
            override fun displayName() = ""
            override fun accountId() = ""
            override fun cachedSnapshot(): JSONObject? = null
            override fun disconnect() = Unit
            override fun save(value: JSONObject) = Unit
            override fun authorizationUrl() = error("Fab tests must not authorize Epic")
            override suspend fun exchange(code: String): JSONObject = error("No network in Fab tests")
            override suspend fun readOwnership(items: JSONArray): EpicOwnership = error("No Epic reads in Fab tests")
        }
        var canSave = true
        var saved = journal.serialize()
        override suspend fun fetchPcPromotions(country: String): String = error("No PC requests in Fab tests")
        override fun scriptSource() = "// local Fab test adapter"
        override fun saveCache() = Unit
        override fun saveJournal(): Boolean {
            events.add("save")
            if (canSave) saved = journal.serialize()
            return canSave
        }
        override fun setResetPending(pending: Boolean) = true
    }

    companion object {
        private const val FAB = "https://www.fab.com"
        private const val ID = "local-material"
        private const val KEY = "fab:$ID"
        private const val ACCOUNT = "original-library"
        private const val URL = "$FAB/listings/$ID"
        private fun catalogue() = JSONArray()
            .put(CatalogItem(KEY, Category.FAB, "Local material", URL, id = ID).toJson())
            .put(CatalogItem("fab:other-material", Category.FAB, "Other material", "$FAB/listings/other-material", id = "other-material").toJson())
    }
}
