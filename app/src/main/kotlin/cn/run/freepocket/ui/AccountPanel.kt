package cn.run.freepocket.ui

import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import cn.run.freepocket.R
import cn.run.freepocket.databinding.NativePanelBinding
import cn.run.freepocket.model.Category
import cn.run.freepocket.model.FabTier
import java.util.Locale

internal class AccountPanel(private val binding: NativePanelBinding, private val onAction: (PocketAction) -> Unit) {
    private var rendered: PocketUiState? = null
    private var countryInput: EditText? = null

    fun render(state: PocketUiState) {
        val previous = rendered
        if (previous != null && previous.epic == state.epic && previous.fab == state.fab && previous.busy == state.busy &&
            previous.country == state.country && previous.fabTier == state.fabTier) return
        // Busy/account refreshes must not discard a region the user has not saved.
        val draft = if (previous?.country == state.country) countryInput?.text?.toString() ?: state.country else state.country
        rendered = state
        val context = binding.root.context
        binding.title.setText(R.string.native_accounts_title)
        binding.body.removeAllViews()
        with(binding.body) {
            account(state.epic, Category.PC, R.string.native_epic_account, state.busy)
            account(state.fab, Category.FAB, R.string.native_fab_account, state.busy)
            addText(context.getString(R.string.native_country), heading = true)
            val country = EditText(context).apply {
                setText(draft)
                hint = context.getString(R.string.native_country_hint)
                contentDescription = context.getString(R.string.native_country)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                filters = arrayOf(InputFilter.LengthFilter(2))
                minHeight = context.dp(48)
                isEnabled = !state.busy
            }
            countryInput = country
            addView(country, LinearLayout.LayoutParams(-1, -2))
            addAction(context.getString(R.string.native_save_country), !state.busy) {
                val value = country.text.toString().trim().uppercase(Locale.ROOT)
                if (value.matches(Regex("[A-Z]{2}"))) onAction(PocketAction.CountryChanged(value))
                else country.error = context.getString(R.string.native_country_error)
            }
            addText(context.getString(R.string.native_fab_tier), heading = true)
            val tiers = listOf(null to R.string.native_tier_unset, FabTier.PERSONAL to R.string.native_tier_personal,
                FabTier.PROFESSIONAL to R.string.native_tier_professional)
            val group = RadioGroup(context)
            tiers.forEachIndexed { index, (tier, label) ->
                val radio = RadioButton(context).apply {
                    id = android.view.View.generateViewId()
                    setText(label)
                    minHeight = context.dp(48)
                    isEnabled = !state.busy
                    setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
                    tag = index
                }
                group.addView(radio, RadioGroup.LayoutParams(-1, -2))
                if (tier == state.fabTier) group.check(radio.id)
            }
            group.setOnCheckedChangeListener { _, checkedId ->
                val selected = group.findViewById<RadioButton>(checkedId)
                onAction(PocketAction.FabTierChanged(tiers[selected.tag as Int].first))
            }
            addView(group)
            addText(context.getString(R.string.native_tier_note))
        }
    }

    private fun LinearLayout.account(account: AccountUi, category: Category, heading: Int, busy: Boolean) {
        val service = if (category == Category.FAB) "Fab" else "Epic"
        addText(context.getString(heading), heading = true)
        addText(if (account.connected) account.displayName.ifBlank { context.getString(R.string.native_account_connected) } else context.getString(R.string.native_disconnected))
        if (account.checkedAt > 0) addText(context.getString(R.string.native_account_time, localTime(account.checkedAt)))
        if (account.error.isNotBlank()) addText(account.error, error = true)
        addAction(context.getString(if (account.connected) R.string.native_reauthorize else R.string.native_connect, service), !busy) {
            onAction(PocketAction.Connect(category))
        }
        if (account.connected) {
            addAction(context.getString(R.string.native_switch), !busy) { onAction(PocketAction.SwitchAccount(category)) }
            addAction(context.getString(R.string.native_logout), !busy) { onAction(PocketAction.Logout(category)) }
                .setTextColor(context.getColor(R.color.native_error))
        }
    }
}
