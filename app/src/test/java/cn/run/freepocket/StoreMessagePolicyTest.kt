package cn.run.freepocket

import org.json.JSONObject
import org.json.JSONException
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreMessagePolicyTest {
    private fun accepts(category: String, mode: String, type: String, origin: String,
                        main: Boolean, stage: String = ""): Boolean =
        StoreMessagePolicy.accepts(category, mode, JSONObject().put("type", type).put("stage", stage), origin, main)

    @Test fun productActionsRequireTheMatchingStoreMainFrame() {
        assertTrue(accepts("fab", "claim", "actionReady", FAB, true, "product"))
        assertFalse(accepts("fab", "claim", "actionReady", FAB, false, "product"))
        assertFalse(accepts("fab", "claim", "actionReady", STORE, true, "product"))
        assertFalse(accepts("pc", "claim", "actionReady", STORE, true, "unknown"))
    }

    @Test fun legitimateEpicPaymentFramesRemainSupported() {
        for (category in listOf("pc", "android", "fab")) {
            for (type in listOf("actionReady", "ordered", "checkoutComplete", "blocked", "waiting")) {
                assertTrue(accepts(category, "claim", type, EPIC, false, "submit"))
            }
        }
        assertTrue(accepts("fab", "claim", "actionReady", FAB, false, "submit"))
    }

    @Test fun anotherStoreCannotConsumePermissionOrAdvanceTheQueue() {
        for (type in listOf("actionReady", "ordered", "checkoutComplete", "blocked", "waiting")) {
            assertFalse(accepts("pc", "claim", type, FAB, true, "submit"))
            assertFalse(accepts("fab", "claim", type, STORE, false, "submit"))
            assertFalse(accepts("pc", "claim", type, STORE, false, "submit"))
            assertFalse(accepts("pc", "claim", type, "https://evil.test", true, "submit"))
        }
    }

    @Test fun receiptsAndActionsCannotAffectReadOnlyTasks() {
        for (mode in listOf("auth", "discover", "syncFab", "check")) {
            for (type in listOf("actionReady", "ordered", "checkoutComplete", "blocked")) {
                assertFalse(accepts("fab", mode, type, FAB, true, "submit"))
            }
        }
    }

    @Test fun ownershipAndCatalogueResultsMustComeFromTheirTaskMainFrame() {
        assertTrue(accepts("fab", "syncFab", "ownership", FAB, true))
        assertFalse(accepts("fab", "claim", "ownership", FAB, true))
        assertFalse(accepts("fab", "syncFab", "ownership", FAB, false))
        assertTrue(accepts("android", "discover", "catalogue", STORE, true))
        assertFalse(accepts("android", "discover", "catalogue", EPIC, true))
        assertFalse(accepts("fab", "claim", "result", EPIC, false))
    }

    @Test fun authorizationStillRequiresItsOwnProviderMainFrame() {
        assertTrue(accepts("pc", "auth", "authorized", EPIC, true))
        assertFalse(accepts("fab", "auth", "authorized", EPIC, true))
        assertFalse(accepts("pc", "auth", "authorized", EPIC, false))
        assertTrue(accepts("fab", "auth", "authenticated", FAB, true))
        assertFalse(accepts("fab", "discover", "authenticated", FAB, true))
    }

    @Test fun malformedAndUnknownMessagesAreRejectedBeforeDispatch() {
        for (payload in listOf(JSONObject(), JSONObject().put("type", 1), JSONObject().put("type", "unknown"),
            JSONObject().put("type", "actionReady"))) {
            assertFalse(StoreMessagePolicy.accepts("fab", "claim", payload, FAB, true))
        }
    }

    @Test fun malformedPayloadsAreRejectedBeforeStateChanges() {
        for (data in listOf(JSONObject().put("type", "blocked"), JSONObject().put("type", "result"),
            JSONObject().put("type", "ownership").put("owned", org.json.JSONArray()),
            JSONObject().put("type", "catalogue").put("items", org.json.JSONArray().put(JSONObject())))) {
            assertThrows(JSONException::class.java) { StoreMessagePolicy.validate("fab", data) }
        }
    }

    @Test fun fabProductActionRequiresItsLicenseFieldBeforeDispatch() {
        val data = JSONObject().put("type", "actionReady").put("stage", "product")
        assertThrows(JSONException::class.java) { StoreMessagePolicy.validate("fab", data) }
    }

    @Test fun sameOriginCheckoutFramesRequireTheCheckoutPath() {
        for (type in listOf("actionReady", "ordered", "checkoutComplete", "waiting", "blocked")) {
            val message = JSONObject().put("type", type).put("stage", "submit").put("path", "/checkout")
            assertTrue(StoreMessagePolicy.accepts("pc", "claim", message, STORE, false))
            message.put("path", "/p/recommendation")
            assertFalse(StoreMessagePolicy.accepts("pc", "claim", message, STORE, false))
        }
    }

    companion object {
        private const val FAB = "https://www.fab.com"
        private const val STORE = "https://store.epicgames.com"
        private const val EPIC = "https://www.epicgames.com"
    }
}
