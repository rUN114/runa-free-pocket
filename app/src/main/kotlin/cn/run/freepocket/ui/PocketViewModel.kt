package cn.run.freepocket.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.run.freepocket.claim.ClaimCoordinator
import cn.run.freepocket.data.AndroidPocketBackend
import cn.run.freepocket.data.PocketBackend
import cn.run.freepocket.domain.CatalogRules
import cn.run.freepocket.model.CatalogItem
import cn.run.freepocket.model.Category
import cn.run.freepocket.model.FabTier
import cn.run.freepocket.web.BrowserRequest
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal class PocketViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(PocketUiState(message = "正在读取本期免费内容…"))
    val state: StateFlow<PocketUiState> = mutableState.asStateFlow()
    private val mutableBrowser = MutableStateFlow<BrowserRequest?>(null)
    val browser: StateFlow<BrowserRequest?> = mutableBrowser.asStateFlow()
    private val mutableBrowserStatus = MutableStateFlow("")
    val browserStatus: StateFlow<String> = mutableBrowserStatus.asStateFlow()
    private val mutableClearSession = MutableStateFlow(false)
    val clearSession: StateFlow<Boolean> = mutableClearSession.asStateFlow()
    private var backend: PocketBackend? = null
    private var coordinator: ClaimCoordinator? = null
    private var category = Category.PC
    private val selected = linkedSetOf<String>()
    private var hideOwned = false
    private var visible = false

    init {
        initialize()
        viewModelScope.launch {
            while (isActive) {
                delay(60000)
                if (visible) {
                    publish()
                    refreshIfDue()
                }
            }
        }
    }

    private fun initialize() {
        try {
            val data = AndroidPocketBackend(getApplication())
            backend = data
            val engine = ClaimCoordinator(data, viewModelScope, ::publish,
                { request ->
                    if (request?.id != mutableBrowser.value?.id) mutableBrowserStatus.value = ""
                    mutableBrowser.value = request
                }, { mutableBrowserStatus.value = it }, { mutableClearSession.value = true })
            coordinator = engine
            publish()
            engine.start()
        } catch (_: GeneralSecurityException) {
            initializationFailed("无法初始化本机加密授权存储，请重启应用后重试。")
        } catch (_: ProviderException) {
            initializationFailed("设备的加密服务暂不可用，请重启设备后重试。")
        } catch (_: IOException) {
            initializationFailed("应用资源或授权数据无法读取，请重新打开应用后重试。")
        } catch (_: JSONException) {
            initializationFailed("本机数据格式无法读取，请保留应用数据并检查记录。")
        }
    }

    private fun initializationFailed(reason: String) {
        mutableState.value = PocketUiState(message = reason, journalError = reason)
    }

    fun setVisible(value: Boolean) {
        visible = value
        if (value) refreshIfDue()
    }

    fun dispatch(action: PocketAction) {
        val engine = coordinator
        if (engine == null) {
            if (action == PocketAction.Refresh) initialize()
            return
        }
        val data = checkNotNull(backend)
        if (engine.busy) return
        when (action) {
            is PocketAction.CategoryChanged -> {
                category = action.category
                selected.clear()
                publish()
                val last = data.updated.optLong(category.wire)
                if (last == 0L || System.currentTimeMillis() - last >= 300000) send("refresh")
                else if (account(category).connected && currentItems().any { it.status.wire == "unknown" || it.cached }) send("check")
            }
            is PocketAction.ItemSelected -> {
                if (action.selected && currentItems().any { it.key == action.key && CatalogRules.claimableNow(it, System.currentTimeMillis()) }) selected.add(action.key)
                else selected.remove(action.key)
                publish()
            }
            is PocketAction.HideOwnedChanged -> { hideOwned = action.hidden; publish() }
            is PocketAction.SelectAll -> {
                selected.clear()
                if (action.selected) currentItems().filter { CatalogRules.claimableNow(it, System.currentTimeMillis()) }.forEach { selected.add(it.key) }
                publish()
            }
            PocketAction.Refresh -> send("refresh")
            PocketAction.Sync -> send("check")
            PocketAction.ClaimAll -> { selected.clear(); engine.command(JSONObject().put("action", "claimAll")) }
            PocketAction.ClaimSelected -> engine.command(JSONObject().put("action", "claim").put("category", category.wire)
                .put("keys", JSONArray(selected.filter { key -> currentItems().any { it.key == key && CatalogRules.claimableNow(it, System.currentTimeMillis()) } })))
            PocketAction.ResumeBatch -> engine.command(JSONObject().put("action", "resumeBatch"))
            is PocketAction.Connect -> send("login", action.category)
            is PocketAction.SwitchAccount -> { selected.clear(); send("switchAccount", action.category) }
            is PocketAction.Logout -> { selected.clear(); send("logout", action.category) }
            is PocketAction.CountryChanged -> if (action.country != data.country) engine.command(JSONObject().put("action", "country").put("country", action.country))
            is PocketAction.FabTierChanged -> if ((action.tier?.wire ?: "") != data.fabTier)
                engine.command(JSONObject().put("action", "fabTier").put("tier", action.tier?.wire ?: ""))
            is PocketAction.OpenOfficial -> engine.command(JSONObject().put("action", "open").put("key", action.key))
        }
    }

    private fun send(action: String, target: Category = category) {
        coordinator?.command(JSONObject().put("action", action).put("category", target.wire))
    }
    private fun currentItems() = mutableState.value.items.filter { it.category == category }
    private fun account(target: Category) = if (target == Category.FAB) mutableState.value.fab else mutableState.value.epic

    private fun publish() {
        val data = backend ?: return
        val engine = coordinator
        val items = (0 until data.items.length()).map { CatalogItem.fromJson(data.items.getJSONObject(it)) }
        val now = System.currentTimeMillis()
        val selectable = items.filter { it.category == category && CatalogRules.claimableNow(it, now) }.map { it.key }.toSet()
        selected.retainAll(selectable)
        val updated = Category.entries.filter { data.updated.has(it.wire) }.associateWith { data.updated.getLong(it.wire) }
        val epicSnapshot = data.epic.cachedSnapshot()
        val epic = AccountUi(data.epic.connected(), data.epic.displayName(), epicSnapshot?.optLong("checkedAt") ?: 0, engine?.epicError ?: "")
        val fab = AccountUi(data.fabAccount.optBoolean("connected"), data.fabAccount.optString("displayName"),
            data.fabAccount.optLong("checkedAt"), data.fabAccount.optString("error"))
        mutableState.value = PocketUiState(items, updated, category, selected.toSet(), hideOwned, data.country,
            data.fabTier.takeIf { it.isNotEmpty() }?.let(FabTier::fromWire), engine?.message ?: data.initialMessage,
            engine?.busy ?: false, epic, fab, history(data), data.journalError, now)
    }

    private fun history(data: PocketBackend): List<ClaimRunUi> {
        val runs = data.journal?.publicRuns() ?: return emptyList()
        return (0 until runs.length()).map { index ->
            val run = runs.getJSONObject(index)
            val owners = run.getJSONObject("owners")
            val names = listOf("epic", "fab").mapNotNull { platform ->
                owners.optJSONObject(platform)?.optString("name")?.takeIf { it.isNotEmpty() }
                    ?.let { "${if (platform == "epic") "Epic" else "Fab"}：$it" }
            }.joinToString("；")
            val entries = run.getJSONArray("entries")
            val errors = run.getJSONObject("errors")
            ClaimRunUi(run.getString("id"), run.getLong("startedAt"), run.getString("state"), names,
                (0 until entries.length()).map { i ->
                    val item = entries.getJSONObject(i)
                    ClaimEntryUi(item.getString("key"), item.getString("title"), Category.fromWire(item.getString("category")),
                        item.getString("status"), item.optString("reason"))
                }, errors.keys().asSequence().associateWith { errors.getString(it) },
                run.optString("fabTier").takeIf { it.isNotEmpty() }?.let(FabTier::fromWire))
        }
    }

    private fun refreshIfDue() {
        val engine = coordinator ?: return
        val data = backend ?: return
        if (engine.busy || mutableBrowser.value != null || !visible) return
        val last = data.updated.optLong(category.wire)
        if (last > 0 && System.currentTimeMillis() - last >= 300000) send("refresh")
    }
    fun webMessage(data: JSONObject, origin: String, main: Boolean, reply: (JSONObject) -> Unit) = coordinator?.onWebMessage(data, origin, main, reply)
    fun navigation(url: String) { coordinator?.onNavigation(url) }
    fun browserFailed(nonce: String) {
        if (mutableBrowser.value?.id == nonce)
            coordinator?.browserFailed(nonce, "官方页面地址无效或浏览器暂不可用，请返回后刷新活动重试。")
    }
    fun browserBack() { coordinator?.back() }
    fun browserRetry() { coordinator?.retry() }
    fun browserSkip() { coordinator?.skip() }
    fun sessionCleared(success: Boolean) { mutableClearSession.value = false; coordinator?.onSessionCleared(success) }
    override fun onCleared() { coordinator?.close(); super.onCleared() }
}
