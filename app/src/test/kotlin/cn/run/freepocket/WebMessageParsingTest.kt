package cn.run.freepocket

import androidx.webkit.WebMessageCompat
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WebMessageParsingTest {
    @Test fun acceptsJsonTextWithoutChangingItsFields() {
        val parsed = cn.run.freepocket.web.parseWebMessage(WebMessageCompat("""{"action":"refresh","category":"fab"}"""))
        assertEquals("refresh", parsed.getString("action"))
        assertEquals("fab", parsed.getString("category"))
    }

    @Test fun rejectsNullTextAsAMessageFormatError() {
        assertThrows(JSONException::class.java) {
            cn.run.freepocket.web.parseWebMessage(WebMessageCompat(null as String?))
        }
    }

    @Test fun rejectsBinaryMessagesBeforeReadingStringData() {
        assertThrows(JSONException::class.java) {
            cn.run.freepocket.web.parseWebMessage(WebMessageCompat(byteArrayOf(1, 2)))
        }
    }

    @Test fun rejectsMalformedJsonAndNonObjectPayloads() {
        for (payload in listOf("", "not-json", "[]")) {
            assertThrows(JSONException::class.java) {
                cn.run.freepocket.web.parseWebMessage(WebMessageCompat(payload))
            }
        }
    }
}
