package cn.run.freepocket.tests

import android.app.Instrumentation
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import cn.run.freepocket.ClaimJournal
import cn.run.freepocket.claim.ClaimCoordinator
import cn.run.freepocket.data.EpicAccess
import cn.run.freepocket.data.EpicOwnership
import cn.run.freepocket.data.PocketBackend
import cn.run.freepocket.model.CatalogItem
import cn.run.freepocket.model.Category
import cn.run.freepocket.web.BrowserRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Isolated transport and storage: never creates an Activity, WebView or real backend. */
internal object NativeDeviceRecoveryChecks {
    private const val PREFS = "native-recovery-fixture"
    private const val ID = "native-recovery-material"
    private const val KEY = "fab:$ID"
    private const val FAB = "https://www.fab.com"

    fun seed(instrumentation: Instrumentation) {
        instrumentation.runOnMainSync {
            val prefs = instrumentation.targetContext.getSharedPreferences(PREFS, 0)
            check(prefs.edit().clear().commit()) { "Cannot reset isolated recovery fixture" }
            val fixture = Fixture(prefs)
            fixture.coordinator.command(JSONObject().put("action", "claim").put("category", "fab")
                .put("keys", JSONArray().put(KEY)))
            fixture.discoverAndSync()
            check(fixture.browser?.mode == "claim")
            for (stage in listOf("product", "submit")) {
                fixture.send(fixture.ready(stage))
                check(fixture.permits.last().getString("stage") == stage)
                // Read the actual durable payload in the reply path, before process death.
                check(JSONObject(prefs.getString("data", "")!!).getJSONArray("runs")
                    .getJSONObject(0).getJSONArray("entries").getJSONObject(0).getBoolean(stage + "Authorized"))
            }
            check(fixture.permits.size == 2)
            check(fixture.backend.journal.running())
            check(JSONObject(prefs.getString("data", "")!!).getJSONArray("runs").getJSONObject(0).getString("state") == "running")
            check(prefs.edit().putInt("seedPid", android.os.Process.myPid()).commit())
            instrumentation.sendStatus(1, Bundle().apply {
                putString("checkpoint", "native-recovery-ready")
                putString("testresult", JSONObject().put("fixture", PREFS).put("expectedInterrupted", true)
                    .put("permits", 2).put("runCompleted", false).toString())
            })
            // Deliberately do not close/pause: only the external force-stop ends this running fixture.
        }
        CountDownLatch(1).await(90, TimeUnit.SECONDS)
        error("External force-stop did not arrive within 90 seconds")
    }

    fun recover(instrumentation: Instrumentation) {
        instrumentation.runOnMainSync {
            val prefs = instrumentation.targetContext.getSharedPreferences(PREFS, 0)
            check(prefs.getInt("seedPid", -1) != android.os.Process.myPid()) { "Recovery must run in a new process" }
            val persisted = JSONObject(checkNotNull(prefs.getString("data", null)))
            check(persisted.getJSONArray("runs").getJSONObject(0).getString("state") == "running")
            val fixture = Fixture(prefs)
            check(fixture.backend.journal.current()!!.getString("state") == "paused")
            val originalReservations = persisted.getJSONObject("reservations").toString()
            try {
                fixture.coordinator.command(JSONObject().put("action", "resumeBatch"))
                fixture.discoverAndSync()
                check(fixture.browser?.mode == "check")
                for (stage in listOf("product", "submit")) fixture.send(fixture.ready(stage))
                check(fixture.permits.isEmpty())
                fixture.send(JSONObject().put("type", "result").put("status", "unowned"))
                val entry = fixture.backend.journal.entry(KEY)!!
                check(entry.getString("status") == "uncertain")
                check(entry.getBoolean("productAuthorized") && entry.getBoolean("submitAuthorized"))
                check(JSONObject(fixture.backend.journal.serialize()).getJSONObject("reservations").toString() == originalReservations)
                check(!fixture.coordinator.busy)
                check(fixture.backend.journal.current()!!.getString("state") == "done")
                instrumentation.sendStatus(1, Bundle().apply {
                    putString("testresult", JSONObject().put("fixture", PREFS).put("runCompleted", true)
                        .put("noNewPermits", true).put("newPermits", fixture.permits.size)
                        .put("status", entry.getString("status")).put("reservations", 1)
                        .put("productAuthorized", true).put("submitAuthorized", true).toString())
                })
            } finally {
                fixture.coordinator.close()
            }
        }
    }

    fun performance(instrumentation: Instrumentation) {
        instrumentation.runOnMainSync {
            val prefs = instrumentation.targetContext.getSharedPreferences("native-recovery-performance-fixture", 0)
            val journal = ClaimJournal("{}")
            journal.start("CN", listOf("fab"), null)
            journal.bind("fab", "fixture-account", "Fixture account")
            val catalogue = JSONArray()
            for (index in 0 until 180) catalogue.put(JSONObject().put("key", "fab:fixture-$index")
                .put("category", "fab").put("title", "Fixture material $index " + "x".repeat(270)).put("status", "unowned"))
            journal.plan("fab", catalogue, System.currentTimeMillis())
            val payload = journal.serialize()
            val times = mutableListOf<Double>()
            var failures = 0
            repeat(40) { index ->
                val start = SystemClock.elapsedRealtimeNanos()
                if (!prefs.edit().putString("data", payload).putInt("iteration", index).commit()) failures++
                times.add((SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0)
            }
            times.sort()
            instrumentation.sendStatus(1, Bundle().apply {
                putString("testresult", JSONObject().put("fixture", "native-recovery-performance-fixture")
                    .put("runCompleted", true).put("samples", 40).put("payloadBytes", payload.toByteArray(Charsets.UTF_8).size)
                    .put("medianMs", (times[19] + times[20]) / 2).put("p95Ms", times[37]).put("maxMs", times.last())
                    .put("saveFailures", failures).put("mainThread", Looper.myLooper() == Looper.getMainLooper())
                    .put("measurementScope", "Current emulator only; not physical-device performance").toString())
            })
            check(failures == 0) { "SharedPreferences commit failed $failures times" }
        }
    }

    private class Fixture(prefs: SharedPreferences) {
        val backend = Backend(prefs)
        var browser: BrowserRequest? = null
        val permits = mutableListOf<JSONObject>()
        val coordinator = ClaimCoordinator(backend, CoroutineScope(Dispatchers.Main), {}, { browser = it }, {}, {})
        fun ready(stage: String) = JSONObject().put("type", "actionReady").put("stage", stage).put("fabTier", "personal")
        fun send(data: JSONObject) {
            data.put("nonce", checkNotNull(browser).id)
            coordinator.onWebMessage(data, FAB, true) { permit ->
                val stage = permit.getString("stage")
                val stored = JSONObject(checkNotNull(backend.prefs.getString("data", null)))
                check(stored.getJSONArray("runs").getJSONObject(0).getJSONArray("entries").getJSONObject(0).getBoolean(stage + "Authorized"))
                check(stored.getJSONObject("reservations").length() == 1)
                permits.add(permit)
            }
        }
        fun discoverAndSync() {
            check(browser?.mode == "discover")
            send(JSONObject().put("type", "catalogue").put("items", catalogue()))
            check(browser?.mode == "syncFab")
            send(JSONObject().put("type", "ownership").put("accountId", "fixture-account")
                .put("displayName", "Fixture account").put("checkedAt", System.currentTimeMillis()).put("owned", JSONArray()))
        }
    }

    private fun catalogue() = JSONArray().put(CatalogItem(KEY, Category.FAB, "Recovery fixture material",
        "$FAB/listings/$ID", id = ID).toJson())

    private class Backend(val prefs: SharedPreferences) : PocketBackend {
        override var items = catalogue()
        override var updated = JSONObject()
        override var fabAccount = JSONObject().put("connected", true).put("accountId", "fixture-account").put("displayName", "Fixture account")
        override var country = "CN"
        override var fabTier = "personal"
        override var journalError = ""
        override val journal = ClaimJournal(prefs.getString("data", "{}")!!)
        override val initialMessage = ""
        override val resetPending = false
        override val epic = object : EpicAccess {
            override fun connected() = false
            override fun displayName() = ""
            override fun accountId() = ""
            override fun cachedSnapshot(): JSONObject? = null
            override fun disconnect() = Unit
            override fun save(value: JSONObject) = error("No real account writes in recovery fixture")
            override fun authorizationUrl(): String = error("No Epic authorization in recovery fixture")
            override suspend fun exchange(code: String): JSONObject = error("No network in recovery fixture")
            override suspend fun readOwnership(items: JSONArray): EpicOwnership = error("No Epic reads in recovery fixture")
        }
        override suspend fun fetchPcPromotions(country: String): String = error("No network in recovery fixture")
        override fun scriptSource() = "// synthetic recovery transport; never executed in a browser"
        override fun saveCache() = Unit
        override fun saveJournal() = prefs.edit().putString("data", journal.serialize()).commit()
        override fun setResetPending(pending: Boolean): Boolean = error("No session changes in recovery fixture")
    }
}
