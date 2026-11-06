package cn.run.freepocket.web

/** Keeps the current read's status separate from its unfinished reminder. */
internal class BrowserReadStatus(private val reminder: String) {
    private var message = ""
    private var deadline: Long? = null
    private var overdue = false
    val text: String
        get() = if (overdue && message.isNotEmpty()) "$message\n$reminder"
            else if (overdue) reminder else message

    fun start(mode: String, now: Long) {
        clear()
        if (mode == "discover" || mode == "syncFab") deadline = now + 40000
    }

    fun update(message: String) { this.message = message }

    fun expire(now: Long): Boolean {
        val due = deadline ?: return false
        if (now < due) return false
        overdue = true
        deadline = null
        return true
    }

    fun clear() {
        message = ""
        deadline = null
        overdue = false
    }
}
