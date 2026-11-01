package cn.run.freepocket.claim

import cn.run.freepocket.ClaimJournal
import cn.run.freepocket.StoreMessagePolicy
import cn.run.freepocket.data.PocketBackend
import cn.run.freepocket.domain.CatalogRules
import cn.run.freepocket.model.CatalogItem
import cn.run.freepocket.model.Category
import cn.run.freepocket.model.Ownership
import cn.run.freepocket.web.BrowserRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.security.GeneralSecurityException
import java.util.ArrayDeque
import java.util.UUID

/** Owns claim sequencing; the host supplies official browser transport and external I/O. */
internal class ClaimCoordinator(
    private val backend: PocketBackend,
    scope: CoroutineScope,
    private val onChanged: () -> Unit,
    private val onBrowserChanged: (BrowserRequest?) -> Unit,
    private val onBrowserStatus: (String) -> Unit,
    private val onClearSessionRequested: () -> Unit
) {
    private val lifetime = SupervisorJob(scope.coroutineContext[Job])
    private val work = CoroutineScope(scope.coroutineContext + lifetime)
    private val catalogue = ClaimCatalogue(backend)
    private val tasks = ArrayDeque<ClaimTask>()
    private var active: ClaimTask? = null
    private var manualItem: CatalogItem? = null
    private var verificationDelay: Job? = null
    private var resettingAccount = false
    private var reconnectCategory: Category? = null
    private var started = false
    private var closed = false
    private var completed = 0
    private var total = 0
    private var completionNote = ""
    private var waitingToVerify = false
    private val journal get() = backend.journal
    val busy: Boolean get() = active != null || resettingAccount
    var message = backend.initialMessage
        private set
    var epicError = ""
        private set
    fun start() {
        if (started || closed) return
        started = true
        if (backend.resetPending) { resetAccount(false, Category.PC); return }
        tasks.add(ClaimTask(TaskMode.DISCOVER, Category.PC))
        if (backend.epic.connected()) tasks.add(ClaimTask(TaskMode.SYNC_EPIC, Category.PC))
        beginQueue()
    }

    fun command(command: JSONObject) {
        if (busy || closed) return
        try {
            when (command.getString("action")) {
                "claimAll", "resumeBatch" -> {
                    if (!journalAvailable()) return
                    if (command.getString("action") == "claimAll") journal!!.start(backend.country, listOf("pc", "android", "fab"), null)
                    else journal!!.resume(backend.country)
                    if (saveJournal()) queueBatch()
                    return
                }
                "fabTier" -> {
                    val tier = command.getString("tier")
                    if (tier !in listOf("personal", "professional", "")) return
                    backend.fabTier = tier
                    publish()
                    return
                }
                "country" -> {
                    val country = command.getString("country")
                    if (country !in listOf("CN", "HK", "TW", "US", "JP", "GB", "DE")) return
                    backend.country = country
                    backend.items = JSONArray()
                    backend.updated = JSONObject()
                    tasks.add(ClaimTask(TaskMode.DISCOVER, Category.PC))
                    if (backend.epic.connected()) tasks.add(ClaimTask(TaskMode.SYNC_EPIC, Category.PC))
                    beginQueue()
                    return
                }
                "open" -> {
                    val item = catalogue.find(command.getString("key")) ?: return
                    manualItem = item
                    onBrowserChanged(BrowserRequest(UUID.randomUUID().toString(), item.url, item.title, item.category, "browse", 0, 0, autoRetryAllowed = false))
                    onBrowserStatus("官方商品页。返回列表后会重新同步拥有状态。")
                    return
                }
            }
            val category = Category.fromWire(command.getString("category"))
            when (command.getString("action")) {
                "switchAccount", "logout" -> { resetAccount(command.getString("action") == "switchAccount", category); return }
                "login" -> {
                    if (category == Category.FAB) { backend.fabAccount = JSONObject(); catalogue.invalidate(Category.FAB) }
                    else { backend.epic.disconnect(); epicError = ""; catalogue.invalidate(epic = true) }
                    tasks.add(ClaimTask(TaskMode.AUTH, category))
                    tasks.add(ClaimTask(TaskMode.DISCOVER, category))
                    tasks.add(syncTask(category))
                }
                "refresh" -> {
                    tasks.add(ClaimTask(TaskMode.DISCOVER, category))
                    if (connected(category)) tasks.add(syncTask(category))
                }
                "check" -> {
                    if (!connected(category)) tasks.add(ClaimTask(TaskMode.AUTH, category))
                    tasks.add(syncTask(category))
                }
                "claim" -> {
                    if (!journalAvailable()) return
                    val keys = command.getJSONArray("keys")
                    val selected = (0 until keys.length()).mapNotNull { catalogue.find(keys.getString(it)) }
                        .filter { it.category == category }.distinctBy { it.key }
                    if (selected.isEmpty()) return
                    val targets = JSONArray().apply {
                        for (item in selected) put(JSONObject().put("key", item.key).put("title", item.title).put("category", category.wire))
                    }
                    journal!!.start(backend.country, listOf(category.wire), targets)
                    if (saveJournal()) queueBatch()
                    return
                }
                else -> return
            }
            beginQueue()
        } catch (rejected: ClaimJournal.Rejected) {
            message = rejected.reason
            publish()
        } catch (_: JSONException) {
            fail("任务数据无法读取，请重新选择或刷新。")
        } catch (_: IllegalArgumentException) {
            fail("任务字段无法识别，请重新选择或刷新。")
        }
    }

    private fun journalAvailable(): Boolean {
        if (journal != null && backend.journalError.isEmpty()) return true
        message = backend.journalError.ifEmpty { "领取记录不可用，已停用自动领取。" }
        publish()
        return false
    }

    private fun connected(category: Category): Boolean = if (category == Category.FAB) backend.fabAccount.optBoolean("connected") else backend.epic.connected()
    private fun syncTask(category: Category, forClaim: Boolean = false) = ClaimTask(if (category == Category.FAB) TaskMode.SYNC_FAB else TaskMode.SYNC_EPIC, category, forClaim = forClaim)

    private fun queueBatch() {
        for (wire in journal!!.categories()) {
            val category = Category.fromWire(wire)
            tasks.add(ClaimTask(TaskMode.DISCOVER, category))
            tasks.add(ClaimTask(TaskMode.ENSURE_AUTH, category))
            tasks.add(syncTask(category, forClaim = true))
            tasks.add(ClaimTask(TaskMode.PLAN, category))
        }
        beginQueue()
    }

    private fun beginQueue() {
        manualItem = null
        completed = 0
        completionNote = ""
        total = tasks.size
        if (total > 0) next()
    }

    private fun next() {
        cancelPendingVerification()
        onBrowserChanged(null)
        active = tasks.poll()
        try {
            while (true) {
                val task = active ?: break
                if (task.mode == TaskMode.ENSURE_AUTH) {
                    if (!connected(task.category)) { task.mode = TaskMode.AUTH; break }
                } else if (task.mode == TaskMode.PLAN) {
                    if (!bindBatchAccount(task.category)) return
                    plan(task.category)
                    if (!saveJournal()) return
                } else if (task.item != null) {
                    if (prepareItem(task)) break
                    if (active !== task) return
                } else break
                completed++
                active = tasks.poll()
            }
            waitingToVerify = false
            if (active != null) { startActive(); return }
            if (journal?.running() == true) {
                journal!!.finish()
                if (!saveJournal()) return
                completionNote = "本次领取处理已结束，请查看领取记录；待核实项目不会重复自动下单。"
            }
            message = completionNote.ifEmpty { "活动已更新。连接账号后，拥有状态会自动同步。" }
            publish()
        } catch (_: JSONException) {
            fail("领取队列无法读取，请重新选择。")
        } catch (_: IllegalArgumentException) {
            fail("领取队列字段无法识别，请重新刷新。")
        }
    }

    private fun plan(category: Category) {
        journal!!.plan(category.wire, backend.items, System.currentTimeMillis())
        val entries = journal!!.work(category.wire)
        for (entry in entries.asReversed()) {
            val item = catalogue.find(entry.getString("key")) ?: continue
            val uncertain = entry.getString("status") == "uncertain"
            val precheck = !uncertain && entry.optBoolean("pageCheck")
            val mode = when {
                uncertain -> if (category == Category.FAB) TaskMode.CHECK else TaskMode.VERIFY_EPIC
                precheck -> TaskMode.CHECK
                else -> TaskMode.CLAIM
            }
            tasks.addFirst(ClaimTask(mode, category, item, precheck = precheck))
            total++
        }
    }

    private fun prepareItem(task: ClaimTask): Boolean {
        val item = catalogue.find(task.item!!.key)
        if (task.mode == TaskMode.CLAIM && item != null && (item.status !in listOf(Ownership.UNOWNED, Ownership.PARTIAL) || item.cached)) {
            if (journal?.running() == true) {
                journal!!.result(item.key, if (item.cached) "error" else item.status.wire, "拥有状态已变化，请先同步库存再重试")
                if (!saveJournal()) return false
            }
            return false
        }
        if (item != null && (task.mode != TaskMode.CLAIM || CatalogRules.claimableNow(item, System.currentTimeMillis()))) {
            task.item = item
            if (journal?.running() == true) {
                journal!!.working(item.key)
                if (!saveJournal()) return false
            }
            return true
        }
        if (journal?.running() == true) {
            journal!!.result(task.item!!.key, item?.status?.wire ?: "expired", "当前活动或拥有状态已变化")
            if (!saveJournal()) return false
        }
        return false
    }

    private fun startActive() {
        val task = active ?: return
        try {
            task.nonce = UUID.randomUUID().toString()
            if (task.item != null && journal?.running() == true) {
                if (!bindBatchAccount(task.category)) return
                if (task.category == Category.FAB) task.accountId = backend.fabAccount.getString("accountId")
            }
            when {
                task.mode in listOf(TaskMode.SYNC_EPIC, TaskMode.VERIFY_EPIC) -> {
                    onBrowserChanged(null)
                    message = "正在同步 Epic 账号的完整拥有凭证…"
                    publish()
                    requestEpicOwnership(task.nonce)
                }
                task.category == Category.PC && task.mode == TaskMode.DISCOVER -> {
                    onBrowserChanged(null)
                    message = "正在读取 Epic 电脑游戏官方活动…"
                    publish()
                    requestPcCatalogue(task.nonce)
                }
                else -> {
                    if (task.category == Category.FAB && task.mode == TaskMode.CLAIM) {
                        if (backend.fabTier.isEmpty()) { fail("请在账号与同步中明确选择 Fab 许可档位后重试。"); return }
                        val run = journal!!.current()!!
                        val previous = run.optString("fabTier")
                        if (previous.isNotEmpty() && previous != backend.fabTier) { fail("当前许可档位与原任务不一致，请恢复原档位或开始新任务。"); return }
                        run.put("fabTier", backend.fabTier)
                        task.fabTier = backend.fabTier
                        if (!saveJournal()) return
                    }
                    showActiveBrowser(task)
                    publish()
                }
            }
        } catch (_: IOException) {
            fail("官方网页适配脚本无法读取，请重新打开应用。")
        } catch (_: JSONException) {
            fail("任务数据无效，请重新刷新。")
        }
    }

    private fun showActiveBrowser(task: ClaimTask) {
        val fab = task.category == Category.FAB
        val url = task.item?.url ?: when (task.mode) {
            TaskMode.AUTH -> if (fab) "https://www.fab.com/login" else backend.epic.authorizationUrl()
            else -> if (fab) "https://www.fab.com/limited-time-free?lang=en" else "https://store.epicgames.com/en-US/free-games"
        }
        val title = task.item?.title ?: when (task.mode) {
            TaskMode.AUTH -> "连接官方账号"
            TaskMode.SYNC_FAB -> "读取 Fab 素材库"
            else -> "读取官方活动"
        }
        val script = backend.scriptSource() + "\nrunPocketTask(" + task.browserPayload(backend.country) + ");"
        onBrowserChanged(BrowserRequest(task.nonce, url, title, task.category, task.mode.wire, completed + 1, total, script,
            autoRetryAllowed = task.mode in listOf(TaskMode.AUTH, TaskMode.DISCOVER, TaskMode.SYNC_FAB) && task.readRetry == 0))
        message = "正在处理：$title"
    }

    private fun current(nonce: String): Boolean = !closed && active?.nonce == nonce

    private fun requestPcCatalogue(nonce: String) {
        val country = backend.country
        work.launch {
            try {
                val data = backend.fetchPcPromotions(country)
                if (!current(nonce)) return@launch
                catalogue.replace(Category.PC, CatalogRules.parsePc(JSONObject(data), System.currentTimeMillis()))
                backend.updated.put("pc", System.currentTimeMillis())
                finishCatalogue()
            } catch (_: IOException) {
                if (current(nonce)) fail("电脑活动读取失败，请检查网络后重试。")
            } catch (_: JSONException) {
                if (current(nonce)) fail("电脑活动返回格式无法识别，请稍后刷新。")
            } catch (_: IllegalArgumentException) {
                if (current(nonce)) fail("电脑商品字段无法识别，请稍后刷新。")
            }
        }
    }

    private fun finishCatalogue() {
        val category = active!!.category
        val cached = backend.epic.cachedSnapshot()
        if (cached != null && category != Category.FAB) catalogue.applyOwnership(cached, false, category)
        completed++
        next()
    }

    private fun acceptAuthorization(code: String, nonce: String) {
        work.launch {
            try {
                val session = backend.epic.exchange(code)
                if (!current(nonce)) return@launch
                backend.epic.save(session)
                epicError = ""
                completed++
                next()
            } catch (error: IOException) {
                if (current(nonce)) fail("账号授权请求或保存失败：${error.message}")
            } catch (_: GeneralSecurityException) {
                if (current(nonce)) fail("无法安全保存授权，请重新连接账号。")
            } catch (_: JSONException) {
                if (current(nonce)) fail("未取得有效的库存授权，请重新连接 Epic 账号。")
            }
        }
    }

    private fun requestEpicOwnership(nonce: String) {
        val task = active ?: return
        val requested = ClaimCatalogue.encode(catalogue.items().filter { it.category == task.category })
        work.launch {
            try {
                val result = backend.epic.readOwnership(requested)
                if (!current(nonce)) return@launch
                backend.epic.save(result.session)
                epicError = ""
                catalogue.applyOwnership(result.snapshot, true, task.category)
                if (task.forClaim && !bindBatchAccount(task.category)) return@launch
                completionNote = "拥有状态已同步：${backend.epic.displayName()}。已拥有的项目会跳过。"
                val key = task.item?.key
                if (key != null) {
                    var checked = catalogue.find(key) ?: throw JSONException("当前商品已不在目录中")
                    if (!task.precheck && checked.status != Ownership.OWNED) {
                        if (task.verifyAttempt == 0) {
                            task.verifyAttempt++
                            message = "订单已提交，等待拥有凭证更新…"
                            verificationDelay = work.launch { delay(3000); if (current(nonce)) requestEpicOwnership(nonce) }
                            publish()
                            return@launch
                        }
                        checked = checked.copy(status = Ownership.ERROR, statusReason = "订单尚未被账号拥有凭证确认")
                        catalogue.update(checked)
                        completionNote = "有订单尚未确认，请查看卡片状态后重试同步。"
                    }
                    if (!recordBatchItem(checked, checked.status.wire, checked.statusReason)) return@launch
                }
                completed++
                next()
            } catch (error: IOException) {
                if (current(nonce)) syncFailed(error.message.orEmpty())
            } catch (_: GeneralSecurityException) {
                if (current(nonce)) fail("无法安全保存账号会话，请重新连接 Epic。")
            } catch (error: JSONException) {
                if (current(nonce)) syncFailed(error.message.orEmpty())
            }
        }
    }

    fun onWebMessage(data: JSONObject, origin: String, main: Boolean, reply: (JSONObject) -> Unit) {
        if (!current(data.optString("nonce"))) return
        val task = active!!
        if (!StoreMessagePolicy.accepts(task.category.wire, task.mode.wire, data, origin, main)) return
        try { StoreMessagePolicy.validate(task.category.wire, data) } catch (_: JSONException) { return }
        try {
            when (data.getString("type")) {
                "licenseInvalidated" -> {
                    task.fabTierVerified = false
                    fail("商品页许可在领取过程中发生变化，已停止；请先核实订单结果。")
                }
                "actionReady" -> permitAction(task, data, reply)
                "authorized" -> {
                    val code = data.getString("code")
                    if (!code.matches(Regex("[a-zA-Z0-9_-]{16,1024}"))) return
                    task.mode = TaskMode.AUTHORIZING
                    onBrowserStatus("正在交换并保存官方库存授权…")
                    acceptAuthorization(code, task.nonce)
                }
                "authenticated" -> {
                    backend.fabAccount.put("connected", true).put("displayName", data.getString("displayName"))
                    completed++; next()
                }
                "ownership" -> {
                    acceptFabOwnership(data)
                    if (task.forClaim && !bindBatchAccount(task.category)) return
                    completed++; next()
                }
                "catalogue" -> {
                    catalogue.replace(task.category, ClaimCatalogue.decode(data.getJSONArray("items")))
                    backend.updated.put(task.category.wire, System.currentTimeMillis())
                    finishCatalogue()
                }
                "result" -> {
                    val key = task.item?.key ?: return
                    val status = data.getString("status")
                    val item = catalogue.find(key)?.copy(status = Ownership.fromWire(status), checkedAt = System.currentTimeMillis(), cached = false,
                        statusReason = if (task.category == Category.FAB) "Fab 素材库已核实" else "官方商品页面已核实")
                    if (item != null) catalogue.update(item)
                    if (!recordBatchItem(item, status, data.optString("message", "官方页面及库存已核实"))) return
                    completed++; next()
                }
                "checkoutComplete" -> retry()
                "ordered" -> if (!waitingToVerify) { waitingToVerify = true; onBrowserStatus("订单已提交，正在等待官方结果；只有拥有凭证确认后才会记为成功。") }
                "waiting" -> onBrowserStatus("${data.getString("message")}（${completed + 1}/$total）")
                "blocked" -> fail(data.getString("message"))
                "error" -> {
                    val reason = data.getString("message")
                    if (task.mode in listOf(TaskMode.SYNC_FAB, TaskMode.DISCOVER)) {
                        cancelPendingVerification()
                        if (task.mode == TaskMode.SYNC_FAB) markSyncError(reason) else completionNote = "活动读取失败：$reason"
                        onBrowserStatus("$reason。可重试读取、登录后重试，或返回列表。")
                    } else {
                        onBrowserStatus("$reason。请完成官方验证，再核实结果；也可跳过或返回列表。")
                        task.item?.let { catalogue.update(it.copy(status = Ownership.ERROR, statusReason = reason)) }
                    }
                    publish()
                }
            }
        } catch (_: JSONException) {
            fail("官方结果无法记录，请同步库存核实。")
        } catch (_: IllegalArgumentException) {
            fail("官方商品字段无法识别，请重新刷新。")
        } catch (_: IOException) {
            fail("官方网页适配脚本无法读取，请重新打开应用。")
        }
    }

    private fun permitAction(task: ClaimTask, data: JSONObject, reply: (JSONObject) -> Unit) {
        val item = task.item ?: return
        if (task.mode != TaskMode.CLAIM || journal?.running() != true) return
        if (!bindBatchAccount(task.category)) return
        val stage = data.getString("stage")
        if (task.category == Category.FAB) {
            if (stage == "product") {
                if (task.fabTier != data.getString("fabTier")) { fail("商品页许可与所选档位不一致，未打开结算。"); return }
                task.fabTierVerified = true
                journal!!.entry(item.key)!!.put("selectedFabTier", task.fabTier)
            } else if (!task.fabTierVerified) { fail("未取得本次商品页许可确认，未提交订单。"); return }
        }
        try { journal!!.reserve(item.key, stage) } catch (_: JSONException) {
            fail("此项目存在待核实操作或重复请求，请先在领取记录中核实。")
            return
        }
        if (!saveJournal()) return
        // The host installs this same-nonce script before delivering the permit to the page.
        if (task.category == Category.FAB && stage == "product") showActiveBrowser(task)
        reply(JSONObject().put("fabTierVerified", task.fabTierVerified).put("type", "actionPermit").put("nonce", task.nonce).put("stage", stage))
    }

    private fun recordBatchItem(item: CatalogItem?, status: String, reason: String): Boolean {
        if (journal?.running() != true) return true
        val task = active!!
        val key = task.item!!.key
        if (task.precheck && status == "unowned" && item != null && CatalogRules.claimableNow(item, System.currentTimeMillis())) {
            journal!!.prechecked(key)
            tasks.addFirst(ClaimTask(TaskMode.CLAIM, task.category, item))
            total++
        } else journal!!.result(key, status, reason)
        return saveJournal()
    }

    private fun acceptFabOwnership(data: JSONObject) {
        val acquired = data.getJSONArray("owned")
        val ids = (0 until acquired.length()).map { acquired.getString(it) }.toSet()
        val checkedAt = data.getLong("checkedAt")
        val accountId = data.getString("accountId")
        catalogue.store(catalogue.items().map { item ->
            if (item.category == Category.FAB) item.copy(status = if (item.id in ids) Ownership.OWNED else Ownership.UNOWNED,
                checkedAt = checkedAt, cached = false, statusReason = "Fab 已获取素材库核实", ownershipAccount = accountId) else item
        })
        backend.fabAccount = JSONObject().put("connected", true).put("displayName", data.getString("displayName"))
            .put("accountId", accountId).put("checkedAt", checkedAt)
        completionNote = "Fab 拥有状态已同步，已拥有的素材会跳过。"
    }

    private fun bindBatchAccount(category: Category): Boolean {
        if (journal?.running() != true) return true
        return try {
            journal!!.bind(category.wire, if (category == Category.FAB) backend.fabAccount.getString("accountId") else backend.epic.accountId(),
                if (category == Category.FAB) backend.fabAccount.getString("displayName") else backend.epic.displayName())
            true
        } catch (_: JSONException) {
            fail("当前账号未连接或与原领取记录不一致，请重新连接原账号，或开始新任务。")
            false
        }
    }

    private fun syncFailed(reason: String) {
        markSyncError(reason)
        val task = active!!
        if (task.forClaim || task.mode == TaskMode.VERIFY_EPIC) { fail(completionNote); return }
        completed++
        next()
    }

    private fun markSyncError(reason: String) {
        val category = active!!.category
        catalogue.store(catalogue.items().map { item ->
            if (item.category == category) item.copy(status = if (item.status == Ownership.OWNED) item.status else Ownership.ERROR, cached = true, statusReason = reason) else item
        })
        completionNote = "拥有状态同步失败：$reason"
        if (category == Category.FAB) backend.fabAccount.put("error", reason) else epicError = reason
    }

    fun onNavigation(url: String) {
        val uri = try { URI.create(url) } catch (_: IllegalArgumentException) { return }
        val task = active
        if (task?.category == Category.FAB && task.fabTierVerified) {
            val productPath = URI.create(task.item!!.url).path
            val product = uri.host == "www.fab.com" && uri.path == productPath
            val checkout = (uri.host == "www.fab.com" && uri.path == "/payment/web/purchase") ||
                (uri.host == "www.epicgames.com" && uri.path.orEmpty().startsWith("/payment/"))
            if (!product && !checkout) task.fabTierVerified = false
        }
        val login = uri.host == "www.epicgames.com" && uri.path.orEmpty().startsWith("/id/login")
        if (!login || !backend.epic.connected() || task?.mode == TaskMode.AUTH || task?.category == Category.FAB || manualItem?.category == Category.FAB) return
        backend.epic.disconnect()
        catalogue.invalidate(epic = true)
        publish()
        if (task != null) fail("Epic 网页登录已过期，请重新连接账号后继续领取。")
    }

    fun retry() {
        if (closed || resettingAccount) return
        val task = active ?: run { back(); return }
        if (task.mode == TaskMode.AUTHORIZING) return
        cancelPendingVerification()
        if (task.item != null) {
            task.mode = if (task.category == Category.FAB || task.item!!.catalogItems.isEmpty()) TaskMode.CHECK else TaskMode.VERIFY_EPIC
            task.fabTierVerified = false
        } else task.readRetry++
        waitingToVerify = false
        startActive()
    }

    fun skip() {
        val task = active ?: run { back(); return }
        if (task.mode !in listOf(TaskMode.CLAIM, TaskMode.CHECK)) { back(); return }
        try {
            if (journal?.running() == true && task.item != null) {
                journal!!.result(task.item!!.key, "error", "用户跳过，可在记录中重试未提交项目")
                if (!saveJournal()) return
            }
            completed++
            next()
        } catch (_: JSONException) { fail("无法记录跳过结果，请同步库存。") }
    }

    private fun fail(reason: String) {
        manualItem = null
        val task = active
        if (journal?.running() == true && task != null) {
            try {
                if (task.item != null) journal!!.result(task.item!!.key, "error", reason)
                else {
                    journal!!.categoryFailed(task.category.wire, reason)
                    tasks.removeIf { it.category == task.category }
                }
                if (!saveJournal()) return
                completionNote = reason
                completed++
                next()
                return
            } catch (_: JSONException) {
                backend.journalError = "领取记录无法更新，已停止。请同步库存核实结果。"
            }
        }
        stop()
        message = reason
        publish()
    }

    fun back() {
        if (closed || resettingAccount) return
        val returnCategory = if (active == null) manualItem?.category else null
        stop()
        if (returnCategory != null) {
            if (!connected(returnCategory)) tasks.add(ClaimTask(TaskMode.AUTH, returnCategory))
            tasks.add(syncTask(returnCategory))
            beginQueue()
        }
    }

    private fun stop() {
        manualItem = null
        if (journal?.running() == true) {
            journal!!.pause("已暂停；继续前会重新查库，待核实订单不会自动重下单")
            if (!saveJournal()) return
        }
        if (active != null) message = completionNote.ifEmpty { "本次处理已停止。未确认的订单可点击同步重新核实。" }
        clearWork()
        publish()
    }

    private fun clearWork() {
        cancelPendingVerification()
        lifetime.cancelChildren()
        tasks.clear()
        active = null
        onBrowserChanged(null)
    }

    private fun saveJournal(): Boolean {
        if (backend.saveJournal()) return true
        clearWork()
        message = "无法保存领取进度，已停止自动操作。请检查手机存储空间后重试。"
        publish()
        return false
    }

    private fun resetAccount(reconnect: Boolean, category: Category) {
        catalogue.invalidate()
        if (journal != null) {
            if (journal!!.running()) journal!!.pause("账号已退出，继续任务前必须登录原账号")
            if (!backend.saveJournal()) backend.journalError = "无法保存领取记录，已停用自动领取；退出登录仍会继续。请释放存储空间后重新打开。"
        }
        resettingAccount = true
        reconnectCategory = if (reconnect) category else null
        backend.epic.disconnect()
        backend.fabAccount = JSONObject()
        epicError = ""
        completionNote = ""
        manualItem = null
        clearWork()
        message = "正在退出旧账号并清理登录状态…"
        if (!backend.setResetPending(true)) {
            backend.journalError = "无法保存账号清理状态，已停用自动领取。请释放存储空间后重新打开。"
            reconnectCategory = null
        }
        publish()
        onClearSessionRequested()
    }

    fun onSessionCleared(success: Boolean) {
        if (closed || !resettingAccount) return
        if (!success || !backend.setResetPending(false)) {
            message = "登录状态清理未完成，已停止连接。请重新打开应用后重试。"
            reconnectCategory = null
            publish()
            return
        }
        resettingAccount = false
        message = "已退出 Epic 和 Fab。登录后会重新核实当前账号的拥有状态。"
        publish()
        val category = reconnectCategory ?: return
        reconnectCategory = null
        tasks.add(ClaimTask(TaskMode.AUTH, category))
        tasks.add(ClaimTask(TaskMode.DISCOVER, category))
        tasks.add(syncTask(category))
        beginQueue()
    }

    private fun cancelPendingVerification() { verificationDelay?.cancel(); verificationDelay = null }
    private fun publish() { backend.saveCache(); onChanged() }

    fun close() {
        if (closed) return
        if (journal?.running() == true) {
            journal!!.pause("应用已中断，继续前将重新核实库存")
            if (!backend.saveJournal()) {
                message = "无法保存领取进度，已停止自动操作。请检查手机存储空间后重试。"
                onChanged()
            }
        }
        clearWork()
        lifetime.cancel()
        backend.saveCache()
        closed = true
    }
}
