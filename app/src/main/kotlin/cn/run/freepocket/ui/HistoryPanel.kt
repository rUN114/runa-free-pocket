package cn.run.freepocket.ui

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import cn.run.freepocket.R
import cn.run.freepocket.databinding.NativePanelBinding
import cn.run.freepocket.model.Category
import cn.run.freepocket.model.FabTier

internal class HistoryPanel(private val binding: NativePanelBinding, private val onAction: (PocketAction) -> Unit) {
    private var selectedId: String? = null
    private var rendered: PocketUiState? = null

    fun render(state: PocketUiState) {
        val previous = rendered
        if (previous != null && previous.history == state.history && previous.busy == state.busy &&
            previous.journalError == state.journalError && previous.items == state.items) return
        rendered = state
        val context = binding.root.context
        binding.title.setText(R.string.native_history_title)
        binding.body.removeAllViews()
        with(binding.body) {
            if (state.journalError.isNotBlank()) addText(state.journalError, error = true)
            if (state.history.isEmpty()) {
                addText(context.getString(R.string.native_history_empty))
                return
            }
            var selected = state.history.indexOfFirst { it.id == selectedId }
            if (selected < 0) selected = 0
            selectedId = state.history[selected].id
            val selector = Spinner(context).apply {
                minimumHeight = context.dp(48)
                contentDescription = context.getString(R.string.native_history_title)
                adapter = ArrayAdapter(context, R.layout.native_history_option, state.history.mapIndexed { index, run ->
                    context.getString(if (index == 0) R.string.native_history_latest else R.string.native_history_old,
                        localTime(run.startedAt), runStateLabel(run))
                })
                setSelection(selected)
            }
            addView(selector, LinearLayout.LayoutParams(-1, -2))
            val entries = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(entries)
            entries.showRun(state, state.history[selected])
            selector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    selectedId = state.history[position].id
                    entries.removeAllViews()
                    entries.showRun(state, state.history[position])
                }
            }
        }
    }

    private fun LinearLayout.showRun(state: PocketUiState, run: ClaimRunUi) {
        addText(context.getString(R.string.native_history_note))
        addText(context.getString(R.string.native_recover_note))
        // The presentation model supplies display names only; account identifiers stay outside the UI.
        if (run.owners.isNotBlank()) addText(run.owners)
        val tierLabel = when (run.fabTier) {
            FabTier.PERSONAL -> R.string.native_tier_personal
            FabTier.PROFESSIONAL -> R.string.native_tier_professional
            null -> R.string.native_tier_unknown
        }
        if (run.entries.any { it.category == Category.FAB }) addText(context.getString(R.string.native_history_tier, context.getString(tierLabel)))
        if (run.id == state.history.firstOrNull()?.id && run.recoverable()) {
            addAction(context.getString(R.string.native_resume), !state.busy && state.journalError.isBlank(), primary = true) { onAction(PocketAction.ResumeBatch) }
        }
        run.errors.forEach { (category, reason) ->
            val platform = when (category) {
                "epic" -> "Epic"
                "fab" -> "Fab"
                "pc" -> Category.PC.label(context)
                "android" -> Category.ANDROID.label(context)
                else -> context.getString(R.string.native_account)
            }
            addText(context.getString(R.string.native_entry_status, platform, reason), error = true)
        }
        run.entries.forEach { entry ->
            addText(entry.title, heading = true)
            addText(context.getString(R.string.native_entry_status, entry.category.label(context), entryStatusLabel(entry.status)))
            if (entry.reason.isNotBlank()) addText(entry.reason, error = entry.status in setOf("failed", "uncertain"))
            if (entry.status == "uncertain" && state.items.any { it.key == entry.key }) {
                addAction(context.getString(R.string.native_verify_official), !state.busy) { onAction(PocketAction.OpenOfficial(entry.key)) }
            }
        }
    }

    private fun runStateLabel(run: ClaimRunUi): String = binding.root.context.getString(when (run.state) {
        "paused" -> R.string.native_run_paused
        "completed", "done", "finished" -> if (run.entries.any { it.status == "uncertain" })
            R.string.native_run_verify_pending else R.string.native_run_done
        "working", "running" -> R.string.native_run_active
        else -> R.string.native_run_saved
    })
    private fun entryStatusLabel(status: String): String = binding.root.context.getString(when (status) {
        "pending" -> R.string.native_pending
        "working" -> R.string.native_working
        "claimed" -> R.string.native_claimed
        "owned" -> R.string.native_was_owned
        "skipped" -> R.string.native_skipped
        "failed" -> R.string.native_failed
        "uncertain" -> R.string.native_uncertain
        else -> R.string.native_unknown
    })
}
