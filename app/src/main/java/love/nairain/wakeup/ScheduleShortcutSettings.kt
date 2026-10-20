package love.nairain.wakeup

import android.content.Context

internal object ScheduleShortcutSettings {
    enum class Item(val viewId: String, val title: String) {
        TIME("main_nav_time", "上课时间"),
        SETTINGS("main_nav_schedule_setting", "课表设置"),
        COURSES("main_nav_course", "已添课程"),
        WIDGET("main_nav_help", "小组件"),
        CONTACT("main_nav_about", "联系我们"),
        REMINDER("main_nav_feedback", "上课提醒"),
        NIGHT_MODE("main_nav_settings", "夜间 / 日间模式"),
        SIMPLE_MODE("main_nav_suda", "标准 / 简洁模式")
    }

    // 以稳定入口 ID 保存选择，不受日间、夜间和简洁模式切换后的名称影响。
    private fun preferences(context: Context) =
        context.getSharedPreferences("love.nairain.wakeup.schedule_shortcuts", Context.MODE_PRIVATE)

    fun isVisible(context: Context, item: Item): Boolean =
        preferences(context).getBoolean(item.viewId, true)

    fun setVisible(context: Context, item: Item, visible: Boolean) {
        preferences(context).edit().putBoolean(item.viewId, visible).apply()
    }
}
