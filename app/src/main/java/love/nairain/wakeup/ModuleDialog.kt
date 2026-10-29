package love.nairain.wakeup

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast

internal object ModuleDialog {
    fun show(
        context: Context,
        onBottomBarChanged: () -> Unit,
        onMinePageChanged: () -> Unit,
        onShortcutsChanged: () -> Unit,
        onDismiss: () -> Unit = {}
    ): AlertDialog? {
        val activity = context.findActivity() ?: return null
        if (activity.isFinishing || activity.isDestroyed) return null
        return Session(activity, onBottomBarChanged, onMinePageChanged, onShortcutsChanged, onDismiss).show()
    }

    private class Session(
        private val activity: Activity,
        private val onBottomBarChanged: () -> Unit,
        private val onMinePageChanged: () -> Unit,
        private val onShortcutsChanged: () -> Unit,
        private val onDismiss: () -> Unit
    ) {
        private val style = DialogStyle(activity)
        private lateinit var main: AlertDialog
        private var child: AlertDialog? = null
        private lateinit var bottomRow: DialogStyle.Row
        private lateinit var mineRow: DialogStyle.Row
        private lateinit var shortcutsRow: DialogStyle.Row
        private var versionClicks = 0

        fun show(): AlertDialog {
            val content = style.column()
            content.addView(style.section("精简设置"))
            bottomRow = style.row("底栏精简", bottomSummary()) { showBottomBarSettings() }
            mineRow = style.row("我的页面精简", mineSummary()) { showMinePageSettings() }
            shortcutsRow = style.row("课表菜单精简", shortcutsSummary()) { showShortcutSettings() }
            content.addView(bottomRow.view)
            content.addView(mineRow.view)
            content.addView(shortcutsRow.view)
            content.addView(style.divider())
            content.addView(style.section("弹窗设置"))
            content.addView(style.switchRow("屏蔽更新弹窗", UpdateDialogSettings.isBlocked(activity)) { blocked ->
                UpdateDialogSettings.setBlocked(activity, blocked)
            })
            content.addView(style.description("开启后屏蔽所有新版本更新弹窗。设置自动保存，下次检查更新时生效。"))
            content.addView(style.divider())

            val defaults = style.column().apply {
                visibility = View.GONE
                addView(style.row("广告屏蔽", "底部广告、冷启动与后台返回开屏广告默认屏蔽").view)
                addView(style.row("本地会员", "免登录本地会员功能默认启用").view)
            }
            val arrow = style.chevron()
            val unlockedFeatures = style.column().apply {
                visibility = if (FeatureUnlockSettings.isUnlocked(activity)) View.VISIBLE else View.GONE
                addView(style.row("默认启用的功能", indicator = arrow) {
                    val expanded = defaults.visibility != View.VISIBLE
                    defaults.visibility = if (expanded) View.VISIBLE else View.GONE
                    arrow.rotation = if (expanded) 90f else 0f
                }.view)
                addView(defaults)
                addView(style.divider())
            }
            content.addView(unlockedFeatures)
            content.addView(style.section("关于"))
            content.addView(style.row(
                "版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", showArrow = false
            ) {
                if (!FeatureUnlockSettings.isUnlocked(activity)) {
                    versionClicks++
                    if (versionClicks == 1) {
                        Toast.makeText(activity, "再点一下试试", Toast.LENGTH_SHORT).show()
                    } else if (versionClicks == 10) {
                        FeatureUnlockSettings.unlock(activity)
                        unlockedFeatures.visibility = View.VISIBLE
                        Toast.makeText(activity, "喵喵喵", Toast.LENGTH_SHORT).show()
                    }
                }
            }.view)
            content.addView(style.row("开发者", "夜雨", link = true) {
                openLink("https://github.com/nairain233")
            }.view)
            content.addView(style.row("仓库", "Github", link = true) {
                openLink("https://github.com/nairain233/WakeUpLite")
            }.view)
            content.addView(style.row("赞赏", "支持开发者", link = true) {
                openLink("https://blog.nairain.love/sponsor/")
            }.view)

            main = style.show("设置", "WakeUpLite", content, "关闭")
            // 主弹窗统一管理二级弹窗，宿主视图销毁时不会留下孤立窗口。
            main.setOnDismissListener {
                child?.dismiss()
                child = null
                onDismiss()
            }
            return main
        }

        private fun showBottomBarSettings() {
            showSettings("底栏精简", "选择要显示的入口，全部关闭会隐藏整条底栏。设置自动保存并立即生效。") {
                BottomBarSettings.Tab.entries.forEach { tab ->
                    addView(style.switchRow(tab.title, BottomBarSettings.isVisible(activity, tab.id)) { visible ->
                        BottomBarSettings.setVisible(activity, tab, visible)
                        onBottomBarChanged()
                    })
                }
            }
        }

        private fun showMinePageSettings() {
            showSettings("我的页面精简", "开启显示，关闭隐藏并收起占位。设置自动保存并立即生效；开启的内容按原页面状态显示。") {
                MinePageSettings.Item.entries.forEach { item ->
                    addView(style.switchRow(item.title, MinePageSettings.isVisible(activity, item)) { visible ->
                        MinePageSettings.setVisible(activity, item, visible)
                        onMinePageChanged()
                    })
                }
            }
        }

        private fun showShortcutSettings() {
            showSettings("课表菜单精简", "选择三点菜单底部要显示的快捷入口，全部关闭会收起整个快捷区。模式入口名称随当前状态变化，设置自动保存并立即生效。") {
                ScheduleShortcutSettings.Item.entries.forEach { item ->
                    addView(style.switchRow(item.title, ScheduleShortcutSettings.isVisible(activity, item)) { visible ->
                        ScheduleShortcutSettings.setVisible(activity, item, visible)
                        onShortcutsChanged()
                    })
                }
            }
        }

        private fun showSettings(
            title: String,
            description: String,
            addRows: LinearLayout.() -> Unit
        ) {
            if (activity.isFinishing || activity.isDestroyed || child?.isShowing == true) return
            val content = style.column().apply {
                addView(style.description(description))
                addRows()
            }
            child = style.show(title, "WakeUpLite", content, "返回").apply {
                setOnDismissListener {
                    child = null
                    if (main.isShowing) {
                        bottomRow.summary.text = bottomSummary()
                        mineRow.summary.text = mineSummary()
                        shortcutsRow.summary.text = shortcutsSummary()
                    }
                }
            }
        }

        private fun bottomSummary(): String = "已显示 ${BottomBarSettings.Tab.entries.count {
            BottomBarSettings.isVisible(activity, it.id)
        }} / ${BottomBarSettings.Tab.entries.size} 项"

        private fun mineSummary(): String = "已显示 ${MinePageSettings.Item.entries.count {
            MinePageSettings.isVisible(activity, it)
        }} / ${MinePageSettings.Item.entries.size} 项"

        private fun shortcutsSummary(): String = "已显示 ${ScheduleShortcutSettings.Item.entries.count {
            ScheduleShortcutSettings.isVisible(activity, it)
        }} / ${ScheduleShortcutSettings.Item.entries.size} 项"

        private fun openLink(url: String) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(activity, "未找到可以打开链接的应用", Toast.LENGTH_SHORT).show()
            } catch (_: SecurityException) {
                Toast.makeText(activity, "无法打开链接，请检查宿主权限", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> if (baseContext !== this) baseContext.findActivity() else null
        else -> null
    }
}
