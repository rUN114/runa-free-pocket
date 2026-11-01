package cn.run.freepocket.ui

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import cn.run.freepocket.R
import cn.run.freepocket.domain.CatalogRules
import cn.run.freepocket.model.Availability
import cn.run.freepocket.model.CatalogItem
import cn.run.freepocket.model.Category
import cn.run.freepocket.model.Ownership
import java.text.DateFormat
import java.util.Date

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
internal fun localTime(value: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))
internal fun Category.label(context: Context): String = context.getString(when (this) {
    Category.PC -> R.string.native_pc
    Category.ANDROID -> R.string.native_android
    Category.FAB -> R.string.native_fab
})

internal fun CatalogItem.ownershipLabel(context: Context): String {
    val resource = when (status) {
        Ownership.OWNED -> if (category == Category.FAB) R.string.native_fab_owned else R.string.native_owned
        Ownership.UNOWNED -> R.string.native_unowned
        Ownership.PARTIAL -> R.string.native_partial
        Ownership.EXPIRED -> R.string.native_expired
        Ownership.ERROR -> R.string.native_status_error
        Ownership.UNKNOWN -> R.string.native_unknown
    }
    val label = context.getString(resource)
    return if (cached) context.getString(R.string.native_cached, label) else label
}

internal fun CatalogItem.deadlineLabel(context: Context, now: Long): String = when (CatalogRules.availabilityAt(this, now)) {
    Availability.EXPIRED -> context.getString(R.string.native_expired)
    Availability.UPCOMING -> context.getString(R.string.native_starts, start?.let(::localTime) ?: context.getString(R.string.native_deadline_unknown))
    Availability.ACTIVE -> end?.let {
        val remaining = it - now
        val (resource, unit) = when {
            remaining >= 86_400_000L -> R.string.native_remaining_days to 86_400_000L
            remaining >= 3_600_000L -> R.string.native_remaining_hours to 3_600_000L
            else -> R.string.native_remaining_minutes to 60_000L
        }
        val count = ((remaining + unit - 1) / unit).coerceAtLeast(1)
        context.getString(R.string.native_ends, context.getString(resource, count), localTime(it))
    } ?: context.getString(R.string.native_free)
}

internal fun ClaimRunUi.recoverable(): Boolean = state == "paused" || errors.isNotEmpty() || entries.any {
    it.status in setOf("pending", "working", "failed", "uncertain")
}

/** Shared native text/action roles for the scrollable detail, account and history screens. */
internal fun LinearLayout.addText(value: CharSequence, heading: Boolean = false, error: Boolean = false): TextView {
    val text = TextView(context, null, 0, if (heading) R.style.native_title else R.style.native_body)
    text.text = value
    text.setTextIsSelectable(!heading)
    if (error) text.setTextColor(context.getColor(R.color.native_error))
    addView(text, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(if (heading) 20 else 8) })
    return text
}

internal fun LinearLayout.addAction(label: String, enabled: Boolean, primary: Boolean = false, action: () -> Unit): Button {
    val button = Button(context).apply {
        minHeight = context.dp(48)
        minimumWidth = context.dp(48)
        textSize = 14f
        isAllCaps = false
        setTextColor(context.getColorStateList(if (primary) R.color.native_primary_text else R.color.native_button_text))
        backgroundTintList = context.getColorStateList(if (primary) R.color.native_primary_fill else R.color.native_surface_variant)
    }
    button.text = label
    button.isEnabled = enabled
    button.setOnClickListener { action() }
    addView(button, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) })
    return button
}

internal fun View.showWhen(visible: Boolean) { visibility = if (visible) View.VISIBLE else View.GONE }
