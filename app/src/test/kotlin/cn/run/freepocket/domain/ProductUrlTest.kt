package cn.run.freepocket.domain

import cn.run.freepocket.model.CatalogItem
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test

class ProductUrlTest {
    @Test fun cachedListingCannotIntroduceNonOfficialOrMalformedProductAddresses() {
        for (url in listOf("http://www.fab.com/listings/id", "https://example.org/listings/id",
            "https://user@www.fab.com/listings/id", "https://www.fab.com:8443/listings/id",
            "https://www.fab.com/%invalid", "")) {
            assertThrows("Must reject $url", JSONException::class.java) {
                CatalogItem.fromJson(JSONObject().put("key", "fab:id").put("category", "fab")
                    .put("title", "Fixture").put("url", url))
            }
        }
    }
}
