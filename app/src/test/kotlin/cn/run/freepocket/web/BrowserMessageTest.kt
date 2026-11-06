package cn.run.freepocket.web

import androidx.webkit.WebMessageCompat
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BrowserMessageTest {
    @Test fun acceptsAnObjectWithTrailingWhitespaceWithoutChangingFields() {
        val value = parseWebMessage(WebMessageCompat("""{"nonce":"request-1","type":"waiting"}""" + "  \n"))
        assertEquals("request-1", value.getString("nonce"))
        assertEquals("waiting", value.getString("type"))
    }

    @Test fun rejectsNullAndBinaryMessagesBeforeReadingText() {
        for (message in listOf(WebMessageCompat(null as String?), WebMessageCompat(byteArrayOf(1)))) {
            assertThrows(JSONException::class.java) { parseWebMessage(message) }
        }
    }

    @Test fun rejectsNonObjectsAndTrailingPayload() {
        for (text in listOf("", "not-json", "[]", "null", "{}{}", "{}secret")) {
            assertThrows(JSONException::class.java) { parseWebMessage(WebMessageCompat(text)) }
        }
    }
}
