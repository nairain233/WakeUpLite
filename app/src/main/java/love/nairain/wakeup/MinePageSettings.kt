package love.nairain.wakeup

import android.content.Context

internal object MinePageSettings {
    enum class Item(
        val viewId: String,
        val title: String,
        val defaultVisible: Boolean = true,
        val isMenu: Boolean = false
    ) {
        PROFILE("mine_user_login_view", "登录与个人资料", false),
        VIP_CARD("ll_mine_vip_card", "会员卡片", false),
        FEEDBACK_CARD("bannerLayout", "吐槽卡片", false),
        COURSE_SETTINGS("ll_mine_tab_content_view_new_course_setting", "课表设置", isMenu = true),
        COLLECTION("ll_mine_tab_content_view_my_collection", "我的收藏", isMenu = true),
        VIP("ll_mine_tab_content_view_vip", "会员专属", isMenu = true),
        COURSES("ll_mine_tab_content_view_course", "我购买的课程", false, isMenu = true),
        QR_SCAN("ll_mine_tab_content_view_qr_scan", "电脑端扫码", false, isMenu = true),
        GLOBAL_SETTINGS("ll_mine_tab_content_view_global_setting", "全局设置", isMenu = true),
        SUPPORT("ll_mine_tab_content_view_kf", "帮助与反馈", false, isMenu = true),
        FAQ("ll_mine_tab_content_view_new_help_feedback", "常见问题", isMenu = true),
        MORE("ll_mine_tab_content_view_more", "更多", isMenu = true)
    }

    // 与底栏配置分开保存，未设置时延续原有默认显示状态。
    private fun preferences(context: Context) =
        context.getSharedPreferences("love.nairain.wakeup.mine_page", Context.MODE_PRIVATE)

    fun isVisible(context: Context, item: Item): Boolean =
        preferences(context).getBoolean(item.viewId, item.defaultVisible)

    fun setVisible(context: Context, item: Item, visible: Boolean) {
        preferences(context).edit().putBoolean(item.viewId, visible).apply()
    }
}
