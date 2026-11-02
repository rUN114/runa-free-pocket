package cn.run.freepocket.web

import org.junit.Assert.*
import org.junit.Test

class BrowserReadStatusTest {
    @Test fun waitingUpdatesKeepDeadlineAndReminderAlongsideSpecificMessage() {
        val state = BrowserReadStatus("未完成提醒")
        state.start("discover", 1000)
        state.update("等待官网验证")
        assertFalse(state.expire(40999))
        assertTrue(state.expire(41000))
        assertEquals("等待官网验证\n未完成提醒", state.text)
        state.update("官网返回具体错误")
        assertEquals("官网返回具体错误\n未完成提醒", state.text)
    }

    @Test fun newRequestAndDisposalClearReminderAndDeadline() {
        val state = BrowserReadStatus("提醒")
        state.start("syncFab", 0)
        assertTrue(state.expire(40000))
        state.start("discover", 50000)
        assertEquals("", state.text)
        assertFalse(state.expire(89999))
        state.clear()
        assertFalse(state.expire(100000))
        assertEquals("", state.text)
        state.start("claim", 0)
        assertFalse(state.expire(100000))
    }
}
