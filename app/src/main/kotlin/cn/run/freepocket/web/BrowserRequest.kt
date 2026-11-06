package cn.run.freepocket.web

import cn.run.freepocket.model.Category

data class BrowserRequest(
    val id: String,
    val url: String,
    val title: String,
    val category: Category,
    val mode: String,
    val position: Int,
    val total: Int,
    val script: String? = null,
    val autoRetryAllowed: Boolean
)
