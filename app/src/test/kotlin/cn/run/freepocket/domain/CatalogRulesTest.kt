package cn.run.freepocket.domain

import cn.run.freepocket.model.Availability
import cn.run.freepocket.model.CatalogIdentity
import cn.run.freepocket.model.CatalogItem
import cn.run.freepocket.model.Category
import cn.run.freepocket.model.FabTier
import cn.run.freepocket.model.Ownership
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CatalogRulesTest {
    private val now = Instant.parse("2026-10-16T12:00:00Z").toEpochMilli()
    private fun item(vararg ids: String) = CatalogItem("pc:ns:offer", Category.PC, "Game",
        "https://store.epicgames.com/en-US/p/game", id = "offer", namespace = "ns",
        catalogItems = ids.map { CatalogIdentity("ns", it) })
    private fun identity(id: String, owned: Boolean? = null) = JSONObject().put("namespace", "ns")
        .put("id", id).apply { if (owned != null) put("owned", owned) }
    private fun snapshot(owned: JSONArray = JSONArray(), checks: JSONArray = JSONArray()) =
        JSONObject().put("accountId", "account-a").put("checkedAt", now).put("owned", owned).put("checks", checks)
    private fun game() = JSONObject("""{"id":"offer","namespace":"ns","title":"Game",
        "catalogNs":{"mappings":[{"pageSlug":"game"}]},"items":[{"id":"part","namespace":"ns"}],
        "price":{"totalPrice":{"discountPrice":0,"fmtPrice":{"originalPrice":40}}},
        "promotions":{"promotionalOffers":[{"promotionalOffers":[{"startDate":"2026-10-15T15:00:00Z",
        "endDate":"2026-10-22T15:00:00Z","discountSetting":{"discountPercentage":0}}]}]}}""")
    private fun response(vararg games: JSONObject) = JSONObject().put("data", JSONObject().put("Catalog",
        JSONObject().put("searchStore", JSONObject().put("elements", JSONArray(games.toList())))))

    @Test fun modelReadsLegacyNumericPriceAndDatesAndWritesAutomationCompatibleIsoDates() {
        val wire = item("part").toJson().put("originalPrice", 40).put("start", now - 1)
            .put("end", "2026-10-22T15:00:00Z").put("checkedAt", now).put("cached", true)
            .put("ownershipAccount", "account-a").put("statusReason", "凭证").put("slug", "game")
        val parsed = CatalogItem.fromJson(wire)
        assertEquals("40", parsed.originalPrice)
        assertEquals(now - 1, parsed.start)
        assertEquals(Instant.parse("2026-10-22T15:00:00Z").toEpochMilli(), parsed.end)
        assertEquals("2026-10-16T11:59:59.999Z", parsed.toJson().getString("start"))
        assertEquals(parsed, CatalogItem.fromJson(parsed.toJson()))
        assertEquals(Category.ANDROID, Category.fromWire("android"))
        assertEquals(FabTier.PROFESSIONAL, FabTier.fromWire("professional"))
        assertThrows(IllegalArgumentException::class.java) { Category.fromWire("other") }
    }

    @Test fun malformedDatesAndUnknownSchemaAreExplicitFailures() {
        assertThrows(JSONException::class.java) { CatalogItem.fromJson(item().toJson().put("start", "invalid")) }
        assertThrows(JSONException::class.java) { CatalogRules.parsePc(JSONObject(), now) }
        assertThrows(JSONException::class.java) { CatalogRules.applyOwnership(emptyList(), JSONObject(), true, Category.PC) }
        assertThrows(JSONException::class.java) {
            CatalogRules.applyOwnership(emptyList(), snapshot().put("owned", JSONObject()), true, Category.PC)
        }
        assertThrows(JSONException::class.java) {
            CatalogRules.applyOwnership(emptyList(), snapshot().put("checkedAt", "1800000000000"), true, Category.PC)
        }
    }

    @Test fun queueRejectsFutureExpiredOwnedButCanVerifyUnknownOwnership() {
        val ready = item().copy(start = now - 1, end = now + 1)
        assertTrue(CatalogRules.claimableNow(ready, now))
        assertFalse(CatalogRules.claimableNow(ready.copy(start = now + 1), now))
        assertFalse(CatalogRules.claimableNow(ready.copy(end = now), now))
        assertFalse(CatalogRules.claimableNow(ready.copy(status = Ownership.OWNED), now))
        assertFalse(CatalogRules.claimableNow(ready.copy(status = Ownership.EXPIRED), now))
        assertFalse(CatalogRules.claimableNow(ready.copy(availability = Availability.EXPIRED), now))
        assertFalse(CatalogRules.claimableNow(item().copy(availability = Availability.UPCOMING), now))
        assertTrue(CatalogRules.claimableNow(ready.copy(availability = Availability.UPCOMING), now))
        assertEquals(Availability.ACTIVE, CatalogRules.availabilityAt(ready.copy(availability = Availability.UPCOMING), now))
        assertEquals(Availability.UPCOMING, CatalogRules.availabilityAt(ready.copy(start = now + 1), now))
        assertEquals(Availability.EXPIRED, CatalogRules.availabilityAt(ready.copy(end = now), now))
    }

    @Test fun pcIncludesActiveAndFutureButExcludesExpiredAndPaidActivePromotions() {
        assertEquals(Availability.ACTIVE, CatalogRules.parsePc(response(game()), now).single().availability)
        val before = Instant.parse("2026-10-14T00:00:00Z").toEpochMilli()
        assertEquals(Availability.UPCOMING, CatalogRules.parsePc(response(game()), before).single().availability)
        val paid = game().apply { getJSONObject("price").getJSONObject("totalPrice").put("discountPrice", 500) }
        assertTrue(CatalogRules.parsePc(response(paid), now).isEmpty())
        assertEquals(1, CatalogRules.parsePc(response(paid), before).size)
        assertTrue(CatalogRules.parsePc(response(game()), Instant.parse("2026-10-22T15:00:00Z").toEpochMilli()).isEmpty())
        assertTrue(CatalogRules.parsePc(response(game().put("promotions", JSONObject.NULL)), now).isEmpty())
        assertTrue(CatalogRules.parsePc(response(game().put("namespace", "")), now).isEmpty())
    }

    @Test fun pcChoosesActiveBeforeLaterPromotionAndAcceptsNumericDates() {
        val current = game()
        val promotion = current.getJSONObject("promotions").getJSONArray("promotionalOffers")
            .getJSONObject(0).getJSONArray("promotionalOffers").getJSONObject(0)
        val active = JSONObject(promotion.toString()).put("startDate", now - 100).put("endDate", now + 100)
        promotion.put("startDate", now + 200).put("endDate", now + 300)
        current.getJSONObject("promotions").put("upcomingPromotionalOffers",
            JSONArray().put(JSONObject().put("promotionalOffers", JSONArray().put(active))))
        val result = CatalogRules.parsePc(response(current), now).single()
        assertEquals(now - 100, result.start)
        assertEquals(Availability.ACTIVE, result.availability)
        promotion.put("discountSetting", JSONObject().put("discountPercentage", 50))
        active.put("startDate", "invalid")
        assertTrue(CatalogRules.parsePc(response(current), now).isEmpty())
    }

    @Test fun pcRetainsCatalogIdentityMediaAndDescriptionWithoutTreatingOfferAsInventory() {
        val current = game().put("description", "<p>Real description</p>").put("seller", JSONObject().put("name", "Publisher"))
            .put("keyImages", JSONArray().put(JSONObject().put("type", "OfferImageWide").put("url", "https://cdn1.epicgames.com/cover.png"))
                .put(JSONObject().put("type", "featuredMedia").put("url", "https://cdn1.epicgames.com/screen.png"))
                .put(JSONObject().put("type", "Screenshot").put("url", "https://cdn1.epicgames.com/screen.png"))
                .put(JSONObject().put("type", "Screenshot").put("url", "https://evil.test/screen.png")))
        val result = CatalogRules.parsePc(response(current), now).single()
        assertEquals("pc:ns:offer", result.key)
        assertEquals(listOf(CatalogIdentity("ns", "part")), result.catalogItems)
        assertEquals("40", result.originalPrice)
        assertEquals("Publisher", result.publisher)
        assertEquals("Real description", result.description)
        assertEquals("https://cdn1.epicgames.com/cover.png", result.cover)
        assertEquals(listOf("https://cdn1.epicgames.com/screen.png"), result.screenshots)
        current.getJSONObject("catalogNs").getJSONArray("mappings").getJSONObject(0).put("pageSlug", "https://evil.test/")
        assertThrows(JSONException::class.java) { CatalogRules.parsePc(response(current), now) }
    }

    @Test fun bundleRequiresEveryPartAndOnlyFreshExplicitNegativesPublishUnowned() {
        val bundle = item("a", "b")
        val data = snapshot(JSONArray().put(identity("a")), JSONArray().put(identity("b", false)))
        assertEquals(Ownership.PARTIAL, CatalogRules.applyOwnership(listOf(bundle), data, true, Category.PC).single().status)
        assertEquals(Ownership.UNKNOWN, CatalogRules.applyOwnership(listOf(bundle), data, false, Category.PC).single().status)
        data.getJSONArray("owned").put(identity("b"))
        assertEquals(Ownership.OWNED, CatalogRules.applyOwnership(listOf(bundle), data, false, Category.PC).single().status)
        val negative = snapshot(checks = JSONArray().put(identity("a", false)).put(identity("b", false)))
        assertEquals(Ownership.UNOWNED, CatalogRules.applyOwnership(listOf(bundle), negative, true, Category.PC).single().status)
        assertEquals(Ownership.UNKNOWN, CatalogRules.applyOwnership(listOf(bundle), negative, false, Category.PC).single().status)
    }

    @Test fun ownershipMatchesNamespaceAndCatalogIdAndKeepsPcAndroidFabIndependent() {
        val pc = item("pc-part")
        val mobile = item("android-part").copy(key = "android:game", category = Category.ANDROID)
        val fab = item().copy(key = "fab:asset", category = Category.FAB, status = Ownership.OWNED)
        val data = snapshot(JSONArray().put(identity("pc-part")), JSONArray().put(identity("pc-part", false)))
        val result = CatalogRules.applyOwnership(listOf(pc, mobile, fab), data, true, Category.PC)
        assertEquals(Ownership.OWNED, result[0].status)
        assertSame(mobile, result[1])
        assertSame(fab, result[2])
        assertEquals(Ownership.UNKNOWN, CatalogRules.applyOwnership(listOf(mobile), data, true, Category.ANDROID).single().status)
        data.getJSONArray("checks").put(identity("android-part", true))
        assertEquals(Ownership.OWNED, CatalogRules.applyOwnership(listOf(mobile), data, true, Category.ANDROID).single().status)
        val otherNamespace = snapshot(JSONArray().put(identity("pc-part").put("namespace", "other")))
        assertEquals(Ownership.UNKNOWN, CatalogRules.applyOwnership(listOf(pc), otherNamespace, true, Category.PC).single().status)
    }

    @Test fun missingIdsAndDirectGrantAbsenceStayUnknownAndAccountSwitchReplacesEvidence() {
        val data = snapshot()
        val missing = CatalogRules.applyOwnership(listOf(item()), data, true, Category.PC).single()
        assertEquals(Ownership.UNKNOWN, missing.status)
        assertTrue(missing.statusReason.isNotEmpty())
        val offerOnly = snapshot(JSONArray().put(identity("offer")))
        assertEquals(Ownership.UNKNOWN, CatalogRules.applyOwnership(listOf(item("part")), offerOnly, true, Category.PC).single().status)
        val owned = CatalogRules.applyOwnership(listOf(item("part")), snapshot(JSONArray().put(identity("part"))), false, Category.PC).single()
        assertEquals(Ownership.OWNED, owned.status)
        assertTrue(owned.cached)
        assertEquals(now, owned.checkedAt)
        val switched = snapshot(checks = JSONArray().put(identity("part", false))).put("accountId", "account-b")
        val result = CatalogRules.applyOwnership(listOf(owned), switched, true, Category.PC).single()
        assertEquals(Ownership.UNOWNED, result.status)
        assertEquals("account-b", result.ownershipAccount)
        assertFalse(result.cached)
    }

    /**
     * The fixture under app/src/test/resources/fixtures is the single sample shared with
     * tests/shared-fixture.test.cjs (PocketDomain.parsePc). Its dates are strict ISO-8601
     * UTC so Instant.parse and JS Date.parse agree; epoch-ms stays a cache-boundary concern.
     */
    @Test fun sharedFixtureKeepsKotlinAndJavaScriptParsersInAgreement() {
        val expected = JSONObject(readResource("fixtures/pc-promotions-expected.json"))
        val data = JSONObject(readResource("fixtures/pc-promotions.json"))
        val now = Instant.parse(expected.getString("now")).toEpochMilli()
        val items = CatalogRules.parsePc(data, now)
        val wanted = expected.getJSONArray("items")
        assertEquals(wanted.length(), items.size)
        for (index in 0 until wanted.length()) {
            val want = wanted.getJSONObject(index)
            val got = items[index]
            val label = want.getString("key")
            assertEquals(label, want.getString("key"), got.key)
            assertEquals(label, want.getString("title"), got.title)
            assertEquals(label, want.getString("availability"), got.availability.wire)
            assertEquals(label, want.getString("url"), got.url)
            assertEquals(label, want.getString("cover"), got.cover)
            val identities = want.getJSONArray("catalogItems")
            assertEquals(want.getString("key"), identities.length(), got.catalogItems.size)
            for (i in 0 until identities.length()) {
                assertEquals(label, identities.getJSONObject(i).getString("namespace"), got.catalogItems[i].namespace)
                assertEquals(label, identities.getJSONObject(i).getString("id"), got.catalogItems[i].id)
            }
        }
        val excluded = expected.getJSONArray("excludedTitles")
        for (i in 0 until excluded.length())
            assertTrue(excluded.getString(i), items.none { it.title == excluded.getString(i) })
    }

    private fun readResource(name: String): String =
        javaClass.classLoader.getResourceAsStream(name)!!.readBytes().toString(Charsets.UTF_8)
}
