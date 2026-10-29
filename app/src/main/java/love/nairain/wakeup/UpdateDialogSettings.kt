package love.nairain.wakeup

import android.content.Context

internal object UpdateDialogSettings {
    private fun preferences(context: Context) = context.getSharedPreferences(
        "love.nairain.wakeup.update_dialog", Context.MODE_PRIVATE
    )

    fun isBlocked(context: Context): Boolean = preferences(context).getBoolean("blocked", true)

    fun setBlocked(context: Context, blocked: Boolean) {
        preferences(context).edit().putBoolean("blocked", blocked).apply()
    }
}
