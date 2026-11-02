package cn.run.freepocket.tests

import android.app.Activity
import android.app.Instrumentation
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import cn.run.freepocket.R
import cn.run.freepocket.model.CatalogItem
import cn.run.freepocket.model.Category
import cn.run.freepocket.model.Ownership
import cn.run.freepocket.ui.*

/** Actions stay in memory and never reach account or claim services. */
object NativeUiReviewChecks {
    fun run(instrumentation: Instrumentation, host: Activity) {
        instrumentation.runOnMainSync {
            val actions = mutableListOf<PocketAction>()
            val pocket = NativePocketView(host, { actions.add(it) })
            val loader = NativeImageLoader(host)
            try {
                val item = CatalogItem("ui:active", Category.PC, "UI fixture", "https://store.epicgames.com/p/ui-fixture")
                val state = PocketUiState(items = listOf(item), now = 1_000L)
                host.setContentView(pocket.view)
                pocket.render(state)
                pocket.view.findViewById<View>(R.id.account).performClick()
                fun country() = descendants(pocket.view).filterIsInstance<EditText>().single()
                country().setText("US")
                pocket.render(state.copy(busy = true))
                check(country().text.toString() == "US" && !country().isEnabled) { "busy refresh lost country draft" }
                pocket.render(state.copy(epic = AccountUi(connected = true, displayName = "fixture")))
                check(country().text.toString() == "US" && country().isEnabled) { "account refresh lost country draft" }
                pocket.render(state.copy(country = "JP"))
                check(country().text.toString() == "JP") { "saved country did not replace draft" }
                pocket.handleBack()
                val tab = pocket.view.findViewById<View>(R.id.tab_pc).createAccessibilityNodeInfo()
                check(tab.isSelected && tab.className.toString() == "android.app.ActionBar\$Tab")
                tab.recycle()

                val parent = pocket.view.findViewById<RecyclerView>(R.id.catalog)
                val adapter = CatalogAdapter(loader, { actions.add(it) }) {}
                adapter.submit(state, parent)
                var fullRefreshes = 0
                val changed = mutableListOf<Int>()
                adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                    override fun onChanged() { fullRefreshes++ }
                    override fun onItemRangeChanged(start: Int, count: Int, payload: Any?) {
                        changed.addAll(start until start + count)
                    }
                })
                adapter.submit(state, parent)
                check(changed.isEmpty() && fullRefreshes == 0) { "identical state refreshed catalog" }
                adapter.submit(state.copy(message = "progress"), parent)
                check(changed == listOf(0)) { "message refreshed unchanged cards: $changed" }
                changed.clear()
                adapter.submit(state.copy(selected = setOf(item.key)), parent)
                check(2 in changed && fullRefreshes == 0) { "selection did not update item through diff" }
                val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(2))
                adapter.onBindViewHolder(holder, 2)
                val selected = holder.itemView.findViewById<CheckBox>(R.id.selected)
                check(selected.isChecked && selected.isEnabled)
                selected.performClick()
                check(actions.last() == PocketAction.ItemSelected(item.key, false))
                for (blocked in listOf(state.copy(busy = true), state.copy(journalError = "fixture journal failure"),
                    state.copy(items = listOf(item.copy(status = Ownership.OWNED))))) {
                    adapter.submit(blocked, parent)
                    adapter.onBindViewHolder(holder, 2)
                    check(!selected.isEnabled) { "ineligible item remained selectable" }
                }
                adapter.submit(state.copy(items = listOf(item.copy(end = 2_000))), parent)
                adapter.onBindViewHolder(holder, 2)
                check(selected.isEnabled)
                adapter.submit(state.copy(items = listOf(item.copy(end = 2_000)), now = 3_000), parent)
                adapter.onBindViewHolder(holder, 2)
                check(!selected.isEnabled) { "expired item remained selectable" }
                adapter.onViewRecycled(holder)
                pocket.render(state.copy(journalError = "fixture journal failure"))
                val catalog = parent.adapter as CatalogAdapter
                val header = catalog.onCreateViewHolder(parent, 0)
                catalog.onBindViewHolder(header, 0)
                check(header.itemView.findViewById<View>(R.id.journal_error).accessibilityLiveRegion == View.ACCESSIBILITY_LIVE_REGION_POLITE)
            } finally {
                pocket.close()
                loader.close()
            }
        }
        instrumentation.waitForIdleSync()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
