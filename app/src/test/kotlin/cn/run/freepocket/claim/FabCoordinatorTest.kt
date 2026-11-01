package cn.run.freepocket.claim

import cn.run.freepocket.ClaimJournal
import cn.run.freepocket.data.EpicAccess
import cn.run.freepocket.data.EpicOwnership
import cn.run.freepocket.data.PocketBackend
import cn.run.freepocket.model.CatalogItem
import cn.run.freepocket.model.Category
import cn.run.freepocket.model.Ownership
import cn.run.freepocket.web.BrowserRequest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FabCoordinatorTest {
    @Test fun productTierMismatchCannotReserveOrGrant() = runTest {
        val f = Fixture(this)
        f.beginClaim()
        f.send(f.action("product").put("fabTier", "professional"))
        assertTrue(f.replies.isEmpty())
        assertFalse(f.backend.journal.serialize().contains("productAuthorized"))
        assertTrue(f.backend.journal.entry(KEY)!!.getString("reason").contains("许可"))
        f.close()
    }

    @Test fun missingTierCannotReachClaimPage() = runTest {
        val f = Fixture(this)
        f.backend.fabTier = ""
        f.beginClaim(expectClaim = false)
        assertNull(f.browser)
        assertTrue(f.backend.journal.entry(KEY)!!.getString("reason").contains("档位"))
        f.close()
    }

    @Test fun submitRequiresThisProductsVerifiedTier() = runTest {
        val f = Fixture(this)
        f.beginClaim()
        f.send(f.action("submit"), EPIC, false)
        assertTrue(f.replies.isEmpty())
        assertFalse(f.backend.journal.serialize().contains("submitAuthorized"))
        f.close()
    }

    @Test fun matchingProductInstallsVerifiedScriptBeforeReplyAndSubmit() = runTest {
        val f = Fixture(this)
        f.beginClaim()
        f.send(f.action("product").put("fabTier", "personal"))
        assertEquals(1, f.replies.size)
        assertTrue(f.replies.single().getBoolean("fabTierVerified"))
        val task = JSONObject(f.browser!!.script!!.substringAfter("runPocketTask(").substringBeforeLast(");"))
        assertTrue(task.getBoolean("fabTierVerified"))
        assertEquals("personal", f.backend.journal.entry(KEY)!!.getString("selectedFabTier"))
        f.send(f.action("submit"), EPIC, false)
        assertEquals(listOf("product", "submit"), f.replies.map { it.getString("stage") })
        f.close()
    }

    @Test fun licenseInvalidationKeepsExistingReservationAndStopsSubmit() = runTest {
        val f = Fixture(this)
        f.beginClaim()
        val nonce = f.browser!!.id
        f.send(f.action("product").put("fabTier", "personal"))
        f.send(JSONObject().put("nonce", nonce).put("type", "licenseInvalidated"))
        f.send(JSONObject().put("nonce", nonce).put("type", "actionReady").put("stage", "submit"), EPIC, false)
        assertEquals(1, f.replies.size)
        assertEquals("uncertain", f.backend.journal.entry(KEY)!!.getString("status"))
        assertFalse(f.coordinator.busy)
        f.close()
    }

    @Test fun malformedNavigationRevokesPriorProductConfirmation() = runTest {
        val f = Fixture(this)
        f.beginClaim()
        f.send(f.action("product").put("fabTier", "personal"))
        val submit = f.action("submit")
        f.coordinator.onNavigation("https://www.fab.com/%invalid")
        f.send(submit, EPIC, false)
        assertEquals("Malformed navigation must not retain a product tier grant", 1, f.replies.size)
        assertEquals("uncertain", f.backend.journal.entry(KEY)!!.getString("status"))
        f.close()
    }

    @Test fun unrelatedNavigationRevokesConfirmationButOfficialCheckoutRetainsIt() = runTest {
        val f = Fixture(this)
        f.beginClaim()
        f.send(f.action("product").put("fabTier", "personal"))
        f.coordinator.onNavigation("https://www.epicgames.com/payment/web/purchase")
        f.send(f.action("submit"), EPIC, false)
        assertEquals(2, f.replies.size)
        f.close()
        val changed = Fixture(this)
        changed.beginClaim()
        changed.send(changed.action("product").put("fabTier", "personal"))
        val submit = changed.action("submit")
        changed.coordinator.onNavigation("https://www.fab.com/library")
        changed.send(submit, EPIC, false)
        assertEquals(1, changed.replies.size)
        changed.close()
    }

    @Test fun changedFabAccountCannotReceiveOriginalRunsPermit() = runTest {
        val f = Fixture(this)
        f.beginClaim()
        f.backend.fabAccount.put("accountId", "another-owner")
        f.send(f.action("product").put("fabTier", "personal"))
        assertTrue(f.replies.isEmpty())
        assertEquals("fab-owner", f.backend.journal.current()!!.getJSONObject("owners").getJSONObject("fab").getString("id"))
        f.close()
    }

    @Test fun ownershipResponseBindsFabIdentityAndSkipsAcquiredListing() = runTest {
        val f = Fixture(this)
        f.beginClaim(owned = true, expectClaim = false)
        assertNull(f.browser)
        assertTrue(f.replies.isEmpty())
        assertEquals(Ownership.OWNED, CatalogItem.fromJson(f.backend.items.getJSONObject(0)).status)
        assertEquals("fab-owner", f.backend.fabAccount.getString("accountId"))
        assertEquals("owned", f.backend.journal.entry(KEY)!!.getString("status"))
        f.close()
    }

    @Test fun savedRunTierCannotBeReplayedWithAnotherTier() = runTest {
        val f = Fixture(this)
        f.beginClaim()
        f.coordinator.back()
        f.backend.fabTier = "professional"
        f.coordinator.command(JSONObject().put("action", "resumeBatch"))
        f.completeDiscoveryAndOwnership(false)
        assertNull(f.browser)
        assertTrue(f.replies.isEmpty())
        assertTrue(f.backend.journal.entry(KEY)!!.getString("reason").contains("档位"))
        f.close()
    }

    private class Fixture(scope: TestScope) {
        val backend = Backend()
        var browser: BrowserRequest? = null
        val replies = mutableListOf<JSONObject>()
        val coordinator = ClaimCoordinator(backend, scope, {}, { browser = it }, {}, {})
        fun beginClaim(owned: Boolean = false, expectClaim: Boolean = true) {
            coordinator.command(JSONObject().put("action", "claim").put("category", "fab").put("keys", JSONArray().put(KEY)))
            completeDiscoveryAndOwnership(owned)
            if (expectClaim) assertEquals("claim", browser?.mode)
        }
        fun completeDiscoveryAndOwnership(owned: Boolean) {
            assertEquals("discover", browser?.mode)
            send(JSONObject().put("type", "catalogue").put("items", JSONArray().put(item().toJson())))
            assertEquals("syncFab", browser?.mode)
            send(JSONObject().put("type", "ownership").put("accountId", "fab-owner").put("displayName", "Fab fixture")
                .put("checkedAt", System.currentTimeMillis()).put("owned", if (owned) JSONArray().put("listing") else JSONArray()))
        }
        fun action(stage: String) = JSONObject().put("nonce", browser!!.id).put("type", "actionReady").put("stage", stage)
        fun send(data: JSONObject, origin: String = FAB, main: Boolean = true) {
            if (!data.has("nonce")) data.put("nonce", browser!!.id)
            coordinator.onWebMessage(data, origin, main, replies::add)
        }
        fun close() = coordinator.close()
    }
    private class Backend : PocketBackend {
        override var items = JSONArray().put(item().toJson())
        override var updated = JSONObject()
        override var fabAccount = JSONObject().put("connected", true).put("accountId", "fab-owner").put("displayName", "Fab fixture")
        override var country = "CN"
        override var fabTier = "personal"
        override var journalError = ""
        override val journal = ClaimJournal("{}")
        override val initialMessage = ""
        override val resetPending = false
        override val epic = object : EpicAccess {
            override fun connected() = false
            override fun accountId() = ""
            override fun displayName() = ""
            override fun cachedSnapshot(): JSONObject? = null
            override fun disconnect() = Unit
            override fun save(value: JSONObject) = Unit
            override fun authorizationUrl() = error("Fab fixture must not request Epic auth")
            override suspend fun exchange(code: String): JSONObject = error("No real auth")
            override suspend fun readOwnership(items: JSONArray): EpicOwnership = error("No Epic inventory in Fab fixture")
        }
        override suspend fun fetchPcPromotions(country: String): String = error("No PC network in Fab fixture")
        override fun scriptSource() = "// isolated fixture"
        override fun saveCache() = Unit
        override fun saveJournal() = true
        override fun setResetPending(pending: Boolean) = true
    }
    companion object {
        private const val FAB = "https://www.fab.com"
        private const val EPIC = "https://www.epicgames.com"
        private const val KEY = "fab:listing"
        private fun item() = CatalogItem(KEY, Category.FAB, "Fab fixture", "$FAB/listings/listing",
            id = "listing", status = Ownership.UNOWNED)
    }
}
