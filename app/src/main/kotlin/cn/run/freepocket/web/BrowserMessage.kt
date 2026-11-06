package cn.run.freepocket.web

import androidx.webkit.WebMessageCompat
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

@Throws(JSONException::class)
internal fun parseWebMessage(message: WebMessageCompat): JSONObject {
    if (message.type != WebMessageCompat.TYPE_STRING) throw JSONException("网页消息必须是文本")
    val text = message.data ?: throw JSONException("网页消息缺少文本")
    val tokens = JSONTokener(text)
    val value = tokens.nextValue()
    if (value !is JSONObject || tokens.nextClean() != '\u0000') throw JSONException("网页消息必须是单个 JSON 对象")
    return value
}
