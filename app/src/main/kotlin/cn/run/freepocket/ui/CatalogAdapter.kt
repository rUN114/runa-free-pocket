package cn.run.freepocket.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import cn.run.freepocket.R
import cn.run.freepocket.databinding.NativeCatalogHeaderBinding
import cn.run.freepocket.databinding.NativeCatalogItemBinding
import cn.run.freepocket.databinding.NativeSectionBinding
import cn.run.freepocket.domain.CatalogRules
import cn.run.freepocket.model.Availability
import cn.run.freepocket.model.CatalogItem
import cn.run.freepocket.model.Category
import cn.run.freepocket.model.Ownership

internal class CatalogAdapter(
    private val images: NativeImageLoader,
    private val onAction: (PocketAction) -> Unit,
    private val onDetail: (String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private sealed interface Row {
        data object Header : Row
        data class Section(val label: String) : Row
        data class Item(val item: CatalogItem, val deadline: String, val claimable: Boolean) : Row
    }
    private var state = PocketUiState()
    private var rows: List<Row> = emptyList()
    private var categoryItems: List<CatalogItem> = emptyList()

    fun submit(value: PocketUiState, parent: ViewGroup) {
        state = value
        val context = parent.context
        categoryItems = value.items.filter { it.category == value.category }
        val shown = categoryItems.filter { !value.hideOwned || it.status != Ownership.OWNED }
        val now = value.now
        val active = shown.filter { CatalogRules.availabilityAt(it, now) != Availability.UPCOMING }
        val upcoming = shown.filter { CatalogRules.availabilityAt(it, now) == Availability.UPCOMING }
        rows = buildList {
            add(Row.Header)
            add(Row.Section(context.getString(R.string.native_active, active.size)))
            if (active.isEmpty()) {
                val empty = when {
                    value.hideOwned && categoryItems.any { it.status == Ownership.OWNED } -> R.string.native_empty_hidden
                    value.updated[value.category] == null -> R.string.native_empty_unloaded
                    else -> R.string.native_empty
                }
                add(Row.Section(context.getString(empty)))
            }
            active.forEach { add(Row.Item(it, it.deadlineLabel(context, now), CatalogRules.claimableNow(it, now))) }
            if (upcoming.isNotEmpty()) {
                add(Row.Section(context.getString(R.string.native_upcoming, upcoming.size)))
                upcoming.forEach { add(Row.Item(it, it.deadlineLabel(context, now), CatalogRules.claimableNow(it, now))) }
            }
        }
        notifyDataSetChanged()
    }

    fun isFullWidth(position: Int): Boolean = rows[position] !is Row.Item
    override fun getItemCount(): Int = rows.size
    override fun getItemViewType(position: Int): Int = when (rows[position]) { Row.Header -> 0; is Row.Section -> 1; is Row.Item -> 2 }
    override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (type) {
            0 -> HeaderHolder(NativeCatalogHeaderBinding.inflate(inflater, parent, false))
            1 -> SectionHolder(NativeSectionBinding.inflate(inflater, parent, false))
            else -> ItemHolder(NativeCatalogItemBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderHolder -> bindHeader(holder.binding)
            is SectionHolder -> holder.binding.root.text = (rows[position] as Row.Section).label
            is ItemHolder -> bindItem(holder.binding, rows[position] as Row.Item)
        }
    }

    private fun bindHeader(binding: NativeCatalogHeaderBinding) = with(binding) {
        val context = root.context
        val sceneResources = when (state.category) {
            Category.PC -> R.string.native_scene_pc to R.drawable.native_game_night
            Category.ANDROID -> R.string.native_scene_android to R.drawable.native_mobile_gift
            Category.FAB -> R.string.native_scene_fab to R.drawable.native_fab_workshop
        }
        sceneTitle.setText(sceneResources.first)
        scene.setImageResource(sceneResources.second)
        val account = if (state.category == Category.FAB) state.fab else state.epic
        val service = if (state.category == Category.FAB) "Fab" else "Epic"
        connection.text = if (account.connected) context.getString(R.string.native_connected, service,
            account.displayName.ifBlank { context.getString(R.string.native_account_connected) }) else context.getString(R.string.native_unconnected, service)
        connection.setTextColor(context.getColor(if (account.error.isNotEmpty()) R.color.native_error else R.color.native_on_surface_variant))
        updated.text = state.updated[state.category]?.let { context.getString(R.string.native_updated, localTime(it)) } ?: context.getString(R.string.native_not_updated)
        message.text = listOf(state.message, account.error).filter(String::isNotBlank).joinToString("\n")
        message.showWhen(message.text.isNotBlank())
        journalError.text = state.journalError
        journalError.showWhen(state.journalError.isNotBlank())
        refresh.isEnabled = !state.busy
        refresh.setOnClickListener { onAction(PocketAction.Refresh) }
        sync.isEnabled = !state.busy
        sync.setOnClickListener { onAction(PocketAction.Sync) }
        val canRecover = state.history.firstOrNull()?.recoverable() == true
        resume.showWhen(canRecover)
        resume.isEnabled = !state.busy && state.journalError.isBlank()
        resume.setOnClickListener { onAction(PocketAction.ResumeBatch) }
        val candidates = categoryItems.filter { CatalogRules.claimableNow(it, state.now) }
        selectAll.setOnCheckedChangeListener(null)
        selectAll.isChecked = candidates.isNotEmpty() && candidates.all { it.key in state.selected }
        selectAll.isEnabled = !state.busy && state.journalError.isBlank() && candidates.isNotEmpty()
        selectAll.setOnCheckedChangeListener { _, checked -> onAction(PocketAction.SelectAll(checked)) }
        hideOwned.setOnCheckedChangeListener(null)
        hideOwned.isChecked = state.hideOwned
        hideOwned.isEnabled = !state.busy
        hideOwned.setOnCheckedChangeListener { _, checked -> onAction(PocketAction.HideOwnedChanged(checked)) }
    }

    private fun bindItem(binding: NativeCatalogItemBinding, row: Row.Item) = with(binding) {
        val item = row.item
        val context = root.context
        title.text = item.title
        title.contentDescription = context.getString(R.string.native_detail_item, item.title)
        title.setOnClickListener { onDetail(item.key) }
        image.setOnClickListener { onDetail(item.key) }
        images.bind(image, imageStatus, item.cover, context.getString(R.string.native_cover, item.title))
        selected.setOnCheckedChangeListener(null)
        selected.isChecked = item.key in state.selected
        selected.isEnabled = !state.busy && state.journalError.isBlank() && row.claimable
        selected.contentDescription = context.getString(R.string.native_select_item, item.title)
        selected.setOnCheckedChangeListener { _, checked -> onAction(PocketAction.ItemSelected(item.key, checked)) }
        ownership.text = item.ownershipLabel(context)
        ownership.setTextColor(context.getColor(when (item.status) {
            Ownership.OWNED -> R.color.native_success
            Ownership.ERROR -> R.color.native_error
            else -> R.color.native_on_surface_variant
        }))
        deadline.text = row.deadline
        checked.text = item.checkedAt?.let { context.getString(
            if (item.cached) R.string.native_last_sync else R.string.native_checked, localTime(it)) } ?: ""
        checked.showWhen(checked.text.isNotEmpty())
        root.backgroundTintList = ColorStateList.valueOf(context.getColor(if (item.key in state.selected) R.color.native_surface_variant else R.color.native_surface))
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is ItemHolder) images.unbind(holder.binding.image)
    }
    private class HeaderHolder(val binding: NativeCatalogHeaderBinding) : RecyclerView.ViewHolder(binding.root)
    private class SectionHolder(val binding: NativeSectionBinding) : RecyclerView.ViewHolder(binding.root)
    private class ItemHolder(val binding: NativeCatalogItemBinding) : RecyclerView.ViewHolder(binding.root)
}
