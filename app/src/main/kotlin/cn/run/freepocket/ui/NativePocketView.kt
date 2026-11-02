package cn.run.freepocket.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.GridLayoutManager
import cn.run.freepocket.R
import cn.run.freepocket.databinding.NativePanelBinding
import cn.run.freepocket.databinding.NativePocketBinding
import cn.run.freepocket.domain.CatalogRules
import cn.run.freepocket.model.Category
import cn.run.freepocket.model.Ownership

internal class NativePocketView(context: Context, private val onAction: (PocketAction) -> Unit) {
    private val themed = ContextThemeWrapper(context, R.style.native_pocket_theme)
    private val inflater = LayoutInflater.from(themed)
    private val root = FrameLayout(themed)
    private val main = NativePocketBinding.inflate(inflater, root, false)
    private val images = NativeImageLoader(context)
    private val catalog = CatalogAdapter(images, onAction, ::openDetail)
    private var state = PocketUiState()
    private var screen = Screen.CATALOG
    private var panel: NativePanelBinding? = null
    private var detail: ItemDetailPanel? = null
    private var accounts: AccountPanel? = null
    private var history: HistoryPanel? = null
    private var detailKey: String? = null
    val view: View get() = root

    private enum class Screen { CATALOG, DETAIL, ACCOUNTS, HISTORY }

    init {
        root.setBackgroundColor(themed.getColor(R.color.native_background))
        root.addView(main.root, FrameLayout.LayoutParams(-1, -1))
        val grid = GridLayoutManager(themed, if (context.resources.configuration.screenWidthDp >= 600) 2 else 1)
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = if (catalog.isFullWidth(position)) grid.spanCount else 1
        }
        main.catalog.layoutManager = grid
        main.catalog.adapter = catalog
        main.catalog.itemAnimator = null
        main.catalog.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val widthDp = (right - left) / themed.resources.displayMetrics.density
            val columns = when {
                widthDp >= 1000 -> 3
                widthDp >= 600 -> 2
                else -> 1
            }
            if (grid.spanCount != columns) grid.spanCount = columns
        }
        main.tabPc.setOnClickListener { onAction(PocketAction.CategoryChanged(Category.PC)) }
        main.tabAndroid.setOnClickListener { onAction(PocketAction.CategoryChanged(Category.ANDROID)) }
        main.tabFab.setOnClickListener { onAction(PocketAction.CategoryChanged(Category.FAB)) }
        listOf(main.tabPc, main.tabAndroid, main.tabFab).forEach { button ->
            button.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.app.ActionBar\$Tab"
                    info.isSelected = host.isSelected
                }
            }
        }
        main.account.setOnClickListener {
            accounts = AccountPanel(openPanel(Screen.ACCOUNTS), onAction).also { it.render(state) }
        }
        main.history.setOnClickListener {
            history = HistoryPanel(openPanel(Screen.HISTORY), onAction).also { it.render(state) }
        }
        main.claimAll.setOnClickListener { onAction(PocketAction.ClaimAll) }
        main.claimSelected.setOnClickListener { onAction(PocketAction.ClaimSelected) }
        render(state)
    }

    fun render(value: PocketUiState) {
        val categoryChanged = state.category != value.category
        state = value
        val tabs = listOf(Category.PC to main.tabPc, Category.ANDROID to main.tabAndroid, Category.FAB to main.tabFab)
        tabs.forEach { (category, button) ->
            val count = if (state.updated.containsKey(category)) state.items.count { it.category == category }.toString() else "—"
            button.text = themed.getString(R.string.native_tab_count, category.label(themed), count)
            button.isSelected = category == state.category
            button.isEnabled = !state.busy
            button.alpha = if (state.busy) 0.45f else 1f
            button.backgroundTintList = ColorStateList.valueOf(themed.getColor(if (button.isSelected) R.color.native_primary else R.color.native_surface_variant))
            button.setTextColor(themed.getColor(if (button.isSelected) R.color.native_on_primary else R.color.native_on_surface))
        }
        main.account.isEnabled = !state.busy
        catalog.submit(state, main.catalog)
        if (categoryChanged) main.catalog.scrollToPosition(0)
        val candidates = state.items.filter { it.category == state.category && CatalogRules.claimableNow(it, state.now) }
        val chosen = candidates.count { it.key in state.selected }
        main.selection.text = themed.getString(R.string.native_owned_count,
            state.items.count { it.category == state.category && it.status == Ownership.OWNED }, chosen)
        main.claimAll.isEnabled = !state.busy && state.journalError.isBlank()
        main.claimAll.setText(if (state.busy) R.string.native_working else R.string.native_claim_all)
        main.claimSelected.isEnabled = !state.busy && state.journalError.isBlank() && chosen > 0
        val account = if (state.category == Category.FAB) state.fab else state.epic
        main.claimSelected.text = if (state.busy) themed.getString(R.string.native_working) else themed.getString(
            if (account.connected) R.string.native_claim_selected else R.string.native_login_claim, chosen)
        when (screen) {
            Screen.DETAIL -> state.items.find { it.key == detailKey }?.let { detail?.render(it, state.busy, state.now) }
            Screen.ACCOUNTS -> accounts?.render(state)
            Screen.HISTORY -> history?.render(state)
            Screen.CATALOG -> Unit
        }
    }

    private fun openDetail(key: String) {
        val item = state.items.find { it.key == key } ?: return
        val binding = openPanel(Screen.DETAIL)
        detailKey = key
        detail = ItemDetailPanel(binding, images, onAction).also { it.render(item, state.busy, state.now) }
    }

    private fun openPanel(destination: Screen): NativePanelBinding {
        closePanel()
        screen = destination
        main.root.visibility = View.GONE
        val binding = NativePanelBinding.inflate(inflater, root, false)
        root.addView(binding.root, FrameLayout.LayoutParams(-1, -1))
        binding.back.setOnClickListener { handleBack() }
        panel = binding
        return binding
    }

    private fun closePanel() {
        detail?.close()
        detail = null
        accounts = null
        history = null
        detailKey = null
        panel?.let { root.removeView(it.root) }
        panel = null
    }

    fun handleBack(): Boolean {
        if (screen == Screen.CATALOG) return false
        closePanel()
        screen = Screen.CATALOG
        main.root.visibility = View.VISIBLE
        return true
    }

    fun close() {
        closePanel()
        main.catalog.adapter = null
        images.close()
    }
}
