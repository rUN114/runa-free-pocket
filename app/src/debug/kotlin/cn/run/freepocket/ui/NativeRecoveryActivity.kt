package cn.run.freepocket.ui

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.widget.TextView
import cn.run.freepocket.ClaimJournal
import cn.run.freepocket.claim.ClaimCoordinator
import cn.run.freepocket.data.EpicAccess
import cn.run.freepocket.data.EpicOwnership
import cn.run.freepocket.data.PocketBackend
import cn.run.freepocket.model.*
import cn.run.freepocket.web.BrowserRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil
import java.io.File
import java.security.MessageDigest

/** No WebView, network client, account store or production repository is created here. */
class NativeRecoveryActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("native-recovery-diagnostic", MODE_PRIVATE) }
    private var coordinator: ClaimCoordinator? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(TextView(this).apply { text = "独立恢复诊断 · 不连接官网" })
    }

    fun execute(mode: String): String {
        check(Looper.myLooper() == Looper.getMainLooper())
        val protectedBefore = protectedHashes()
        val result = when (mode) {
            "recoveryPrepare" -> prepare()
            "localRecoveryResume" -> resume()
            "commitBenchmark" -> benchmark()
            else -> error("Unknown recovery mode")
        }
        check(protectedBefore.toString() == protectedHashes().toString()) { "Protected application data changed during diagnostic" }
        return result.put("protectedDataUnchanged", true).put("mode", mode).put("passed", true).put("pid", Process.myPid())
            .put("fixtureOnly", true).put("ordersRequested", 0).toString(2)
    }

    private fun protectedHashes(): JSONObject {
        val root = File(applicationInfo.dataDir)
        val files = listOf("catalogue", "claim-journal", "epic-auth").map { File(root, "shared_prefs/$it.xml") } +
            File(root, "app_webview").walkTopDown().filter { it.isFile && it.name.startsWith("Cookies") }.toList()
        return JSONObject().apply {
            for (file in files.sortedBy { it.path }) {
                val hash = if (file.exists()) MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                    .joinToString("") { "%02x".format(it) } else "absent"
                put(file.relativeTo(root).path, hash)
            }
        }
    }

    private fun item(index: Int = 0) = CatalogItem("fab:diagnostic-$index", Category.FAB,
        "独立诊断素材 $index", "https://www.fab.com/listings/diagnostic-$index",
        id = "diagnostic-$index", status = Ownership.UNOWNED, end = 4102444800000L)

    private fun prepare(): JSONObject {
        val backend = DiagnosticBackend(ClaimJournal("{}"))
        var browser: BrowserRequest? = null
        var productPermits = 0
        val engine = ClaimCoordinator(backend, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), {}, {
            browser = it
        }, {}, { error("Preparation attempted session cleanup") })
        coordinator = engine
        fun message(value: JSONObject) {
            engine.onWebMessage(value.put("nonce", checkNotNull(browser).id), "https://www.fab.com", true) { permit ->
                check(permit.getString("type") == "actionPermit" && permit.getString("stage") == "product")
                val persisted = JSONObject(checkNotNull(prefs.getString("journal", null)))
                val persistedRun = persisted.getJSONArray("runs").getJSONObject(0)
                val reservation = "fab:diagnostic-owner:" + item().key
                check(persisted.getJSONObject("reservations").getString(reservation) == persistedRun.getString("id")) {
                    "Product permit arrived before durable reservation"
                }
                check(persistedRun.getJSONArray("entries").getJSONObject(0).getBoolean("productAuthorized"))
                productPermits++ // Fake transport records the permit; no script, WebView or order executes.
            }
        }
        engine.command(JSONObject().put("action", "claim").put("category", "fab").put("keys", JSONArray().put(item().key)))
        check(browser?.mode == "discover")
        message(JSONObject().put("type", "catalogue").put("items", backend.items))
        check(browser?.mode == "syncFab")
        message(JSONObject().put("type", "ownership").put("owned", JSONArray())
            .put("accountId", "diagnostic-owner").put("displayName", "诊断账号").put("checkedAt", System.currentTimeMillis()))
        check(browser?.mode == "claim")
        message(JSONObject().put("type", "actionReady").put("stage", "product").put("fabTier", "personal"))
        check(productPermits == 1 && engine.busy && backend.journal.running())
        val journal = backend.journal
        val run = journal.current()!!.getString("id")
        val reservations = JSONObject(journal.serialize()).getJSONObject("reservations").toString()
        check(prefs.edit().putString("expectedRun", run).putString("expectedReservations", reservations)
            .putInt("preparedPid", Process.myPid()).putString("protectedHashes", protectedHashes().toString()).commit())
        // Leave the real coordinator in flight until the driver's external force-stop.
        return JSONObject().put("runId", run).put("reservations", JSONObject(reservations)).put("inFlight", true)
            .put("productPermitsGrantedToFakeTransport", productPermits).put("reservationDurableBeforePermit", true)
    }

    private fun resume(): JSONObject {
        check(prefs.getString("protectedHashes", null) == protectedHashes().toString()) { "Protected application data changed across process restart" }
        val preparedPid = prefs.getInt("preparedPid", -1)
        check(preparedPid > 0 && preparedPid != Process.myPid()) { "External process kill was not observed" }
        val journal = ClaimJournal(checkNotNull(prefs.getString("journal", null)))
        val run = checkNotNull(prefs.getString("expectedRun", null))
        val reservations = checkNotNull(prefs.getString("expectedReservations", null))
        check(journal.current()!!.getString("id") == run)
        check(journal.current()!!.getString("state") == "paused")
        check(JSONObject(journal.serialize()).getJSONObject("reservations").toString() == reservations)
        val backend = DiagnosticBackend(journal)
        val modes = mutableListOf<String>()
        var browser: BrowserRequest? = null
        val engine = ClaimCoordinator(backend, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), {}, {
            browser = it
            if (it != null) { modes.add(it.mode); check(it.mode != "claim") { "Recovery entered claim" } }
        }, {}, { error("Recovery attempted session cleanup") })
        coordinator = engine
        engine.command(JSONObject().put("action", "resumeBatch"))
        fun message(value: JSONObject) {
            engine.onWebMessage(value.put("nonce", checkNotNull(browser).id), "https://www.fab.com", true) {
                error("Read-only recovery granted a page permission")
            }
        }
        check(browser?.mode == "discover")
        message(JSONObject().put("type", "catalogue").put("items", backend.items))
        check(browser?.mode == "syncFab")
        message(JSONObject().put("type", "ownership").put("owned", JSONArray())
            .put("accountId", "diagnostic-owner").put("displayName", "诊断账号").put("checkedAt", System.currentTimeMillis()))
        check(browser == null && !engine.busy) { "Recovery must finish after the Fab library read" }
        check(modes == listOf("discover", "syncFab"))
        check(journal.entry(item().key)!!.getString("status") == "uncertain")
        check(JSONObject(journal.serialize()).getJSONObject("reservations").toString() == reservations)
        check(journal.current()!!.getString("id") == run)
        return JSONObject().put("runId", run).put("reservations", JSONObject(reservations))
            .put("preparedPid", preparedPid).put("observedModes", JSONArray(modes)).put("permissionsGranted", 0)
    }

    private fun benchmark(): JSONObject {
        val journal = ClaimJournal("{}")
        val catalogue = JSONArray().apply { repeat(30) { put(item(it).toJson()) } }
        journal.start("CN", listOf("fab"), catalogue)
        journal.bind("fab", "diagnostic-owner", "诊断账号")
        journal.plan("fab", catalogue, System.currentTimeMillis())
        repeat(30) { journal.working(item(it).key); journal.reserve(item(it).key, "product") }
        val payload = journal.serialize()
        val benchmarkPrefs = getSharedPreferences("native-commit-benchmark-diagnostic", MODE_PRIVATE)
        fun sample(target: SharedPreferences, key: String, value: String): Double {
            val start = SystemClock.elapsedRealtimeNanos()
            check(target.edit().putString(key, value).commit()) { "Diagnostic commit failed" }
            return (SystemClock.elapsedRealtimeNanos() - start) / 1000000.0
        }
        // Each cold sample measures the first commit into a newly named diagnostic file.
        val token = SystemClock.elapsedRealtimeNanos()
        val cold = List(30) { index ->
            sample(getSharedPreferences("native-commit-cold-diagnostic-$token-$index", MODE_PRIVATE), "journal", payload)
        }
        val repeated = List(100) { sample(benchmarkPrefs, "journal", payload + " ".repeat(it % 2)) }
        fun stats(values: List<Double>): JSONObject {
            val sorted = values.sorted()
            return JSONObject().put("samples", sorted.size).put("p50Ms", sorted[ceil(sorted.size * .50).toInt()-1])
                .put("p95Ms", sorted[ceil(sorted.size * .95).toInt()-1]).put("maxMs", sorted.last())
        }
        val retainedJournal = ClaimJournal("{}")
        repeat(20) { run ->
            retainedJournal.start("CN", listOf("fab"), catalogue)
            retainedJournal.bind("fab", "diagnostic-history-owner-$run", "诊断历史账号")
            retainedJournal.plan("fab", catalogue, System.currentTimeMillis())
            repeat(30) { retainedJournal.working(item(it).key); retainedJournal.reserve(item(it).key, "product") }
            retainedJournal.pause("诊断保留历史")
        }
        val retainedPayload = retainedJournal.serialize()
        val retained = List(20) { sample(benchmarkPrefs, "retained-history", retainedPayload + " ".repeat(it % 2)) }
        fun serializationProfile(value: ClaimJournal): JSONObject {
            val samples = List(20) {
                val start = SystemClock.elapsedRealtimeNanos()
                val serialized = value.serialize()
                val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1000000.0
                check(serialized.isNotEmpty())
                elapsed
            }
            return stats(samples)
        }
        return JSONObject().put("mainThread", true).put("entries", 30).put("payloadBytes", payload.toByteArray().size)
            .put("serialization", JSONObject().put("singleRun", serializationProfile(journal))
                .put("retainedHistory", serializationProfile(retainedJournal)).put("totalSamples", 40)
                .put("scope", "ClaimJournal.serialize only; excludes edit/commit and UTF-8 encoding"))
            .put("coldDefinition", "first commit into a new diagnostic preferences file; not process-cold or OS-cache-cold")
            .put("cold", stats(cold)).put("repeated", stats(repeated)).put("retainedHistory", stats(retained).put("runs", 20).put("entriesPerRun", 30)
                .put("payloadBytes", retainedPayload.toByteArray().size))
            .put("totalSamples", cold.size + repeated.size + retained.size)
    }

    private inner class DiagnosticBackend(override val journal: ClaimJournal) : PocketBackend {
        override var items = JSONArray().put(item().toJson())
        override var updated = JSONObject()
        override var fabAccount = JSONObject().put("connected", true).put("accountId", "diagnostic-owner").put("displayName", "诊断账号")
        override var country = "CN"
        override var fabTier = "personal"
        override var journalError = ""
        override val initialMessage = ""
        override val resetPending = false
        override val epic = object : EpicAccess {
            override fun connected() = false
            override fun displayName() = error("Unexpected Epic access")
            override fun accountId() = error("Unexpected Epic access")
            override fun cachedSnapshot(): JSONObject? = null
            override fun disconnect() = error("Unexpected Epic access")
            override fun save(value: JSONObject) = error("Unexpected Epic access")
            override fun authorizationUrl() = error("Unexpected Epic access")
            override suspend fun exchange(code: String): JSONObject = error("Unexpected network access")
            override suspend fun readOwnership(items: JSONArray): EpicOwnership = error("Unexpected network access")
        }
        override suspend fun fetchPcPromotions(country: String): String = error("Unexpected network access")
        override fun scriptSource() = "// isolated diagnostic transport; never evaluated"
        override fun saveCache() = Unit
        override fun saveJournal() = prefs.edit().putString("journal", journal.serialize()).commit()
        override fun setResetPending(pending: Boolean): Boolean = error("Unexpected session reset")
    }
}
