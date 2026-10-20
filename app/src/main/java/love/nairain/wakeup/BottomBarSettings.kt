package love.nairain.wakeup

import android.content.Context

internal object BottomBarSettings {
    enum class Tab(val id: String, val title: String) {
        CALENDAR("calendar", "日程"),
        SCHEDULE("schedule", "课表"),
        LEARN("learn", "学习"),
        MINE("mine", "我的")
    }

    // 配置保存在宿主私有目录，与宿主自身的设置文件隔离。
    private fun preferences(context: Context) =
        context.getSharedPreferences("love.nairain.wakeup.bottom_bar", Context.MODE_PRIVATE)

    fun isVisible(context: Context, tabId: String): Boolean =
        preferences(context).getBoolean(tabId, true)

    fun setVisible(context: Context, tab: Tab, visible: Boolean) {
        preferences(context).edit().putBoolean(tab.id, visible).apply()
    }

    fun allHidden(context: Context): Boolean = Tab.entries.none { isVisible(context, it.id) }
}
