package cn.run.freepocket.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPagePolicyTest {
    @Test fun allowsHttpsNavigationButRejectsLocalAndExecutableSchemes() {
        assertTrue(BrowserPagePolicy.canNavigate("https://www.epicgames.com/id/login?code=secret"))
        for (url in listOf("http://www.fab.com/", "file:///sdcard/token", "content://accounts/1",
            "javascript:alert(1)", "intent://login", "https:", "not a url")) {
            assertFalse(url, BrowserPagePolicy.canNavigate(url))
        }
    }

    @Test fun diagnosticsNeverDisplayAuthorizationQueryOrUserInfo() {
        assertEquals("www.epicgames.com", BrowserPagePolicy.displayHost(
            "https://user:secret@www.epicgames.com/id/redirect?code=authorization#token"))
        assertEquals("", BrowserPagePolicy.displayHost("not a valid url?code=secret"))
    }

    @Test fun displaysOnlyRecognizedNetworkCodeOrNumericFallback() {
        assertEquals("net::ERR_CONNECTION_RESET", BrowserPagePolicy.networkCode(
            "net::ERR_CONNECTION_RESET at https://www.epicgames.com/?code=secret", -6))
        assertEquals("WebView -2", BrowserPagePolicy.networkCode(
            "Unknown failure at https://www.fab.com/?code=secret", -2))
    }

    @Test fun automaticallyRetriesOnlySafeReadsAndOnlyOnce() {
        for (mode in listOf("auth", "discover", "syncFab")) {
            assertTrue(BrowserPagePolicy.shouldRetry(mode, -6, false))
            assertTrue(BrowserPagePolicy.shouldRetry(mode, -8, false))
            assertFalse(BrowserPagePolicy.shouldRetry(mode, -6, true))
        }
        for (mode in listOf("claim", "check", "manual", "authorizing")) {
            assertFalse(BrowserPagePolicy.shouldRetry(mode, -6, false))
        }
        assertFalse(BrowserPagePolicy.shouldRetry("discover", -11, false))
    }
}
