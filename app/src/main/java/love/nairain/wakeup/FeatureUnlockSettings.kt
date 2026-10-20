package love.nairain.wakeup

import android.content.Context

internal object FeatureUnlockSettings {
    private fun preferences(context: Context) = context.getSharedPreferences(
        "love.nairain.wakeup.feature_unlock", Context.MODE_PRIVATE
    )

    fun isUnlocked(context: Context): Boolean = preferences(context).getBoolean("unlocked", false)

    fun unlock(context: Context) {
        preferences(context).edit().putBoolean("unlocked", true).apply()
    }
}
