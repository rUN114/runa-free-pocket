package cn.run.freepocket.ui

import cn.run.freepocket.model.CatalogItem
import cn.run.freepocket.model.Category
import cn.run.freepocket.model.FabTier

internal data class AccountUi(val connected: Boolean = false, val displayName: String = "", val checkedAt: Long = 0, val error: String = "")
internal data class ClaimEntryUi(val key: String, val title: String, val category: Category, val status: String, val reason: String)
internal data class ClaimRunUi(val id: String, val startedAt: Long, val state: String, val owners: String,
    val entries: List<ClaimEntryUi>, val errors: Map<String, String>, val fabTier: FabTier? = null,
    val fabCheckoutRecoverable: Boolean = false)
internal data class PocketUiState(
    val items: List<CatalogItem> = emptyList(), val updated: Map<Category, Long> = emptyMap(),
    val category: Category = Category.PC, val selected: Set<String> = emptySet(), val hideOwned: Boolean = false,
    val country: String = "CN", val fabTier: FabTier? = null, val message: String = "", val busy: Boolean = false,
    val epic: AccountUi = AccountUi(), val fab: AccountUi = AccountUi(), val history: List<ClaimRunUi> = emptyList(),
    val journalError: String = "", val now: Long = System.currentTimeMillis()
)

internal sealed interface PocketAction {
    data class CategoryChanged(val category: Category) : PocketAction
    data class ItemSelected(val key: String, val selected: Boolean) : PocketAction
    data class HideOwnedChanged(val hidden: Boolean) : PocketAction
    data class SelectAll(val selected: Boolean) : PocketAction
    data object Refresh : PocketAction
    data object Sync : PocketAction
    data object ClaimAll : PocketAction
    data object ClaimSelected : PocketAction
    data object ResumeBatch : PocketAction
    data class ResumeFabCheckout(val runId: String) : PocketAction
    data class Connect(val category: Category) : PocketAction
    data class SwitchAccount(val category: Category) : PocketAction
    data class Logout(val category: Category) : PocketAction
    data class CountryChanged(val country: String) : PocketAction
    data class FabTierChanged(val tier: FabTier?) : PocketAction
    data class OpenOfficial(val key: String) : PocketAction
}
