package love.nairain.wakeup

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import io.github.libxposed.api.XposedInterface
import java.util.WeakHashMap

internal class BottomBarHook(private val module: XposedInterface) {
    private class BarState(val tabIds: List<String>, var hiddenByHost: Boolean = false)

    // 弱引用底栏，避免设置入口在 Activity 销毁后继续持有宿主视图。
    private val bars = WeakHashMap<ViewGroup, BarState>()

    fun install(targets: HookTargets) {
        // 全部隐藏时必须能保留课表入口，因此底栏规则作为同一依赖组安装。
        targets.install("底栏精简", HookPoint.TAB_LOAD, HookPoint.TAB_ID, HookPoint.TAB_GET,
            HookPoint.TAB_VISIBILITY, HookPoint.TAB_SELECT) {
            val tabId = targets[HookPoint.TAB_ID]
            hook(targets[HookPoint.TAB_LOAD]) { chain ->
                val result = chain.proceed()
                try {
                    val bar = chain.thisObject as ViewGroup
                    val items = chain.getArg(0) as List<*>
                    val state = BarState(items.map { tabId.invoke(it) as String })
                    bars[bar] = state
                    applySafely(bar, state)
                } catch (error: Exception) {
                    module.log(Log.ERROR, ModuleMain.TAG, "Failed to bind bottom bar", error)
                }
                result
            }

            val getBar = targets[HookPoint.TAB_GET]
            val updateVisibility = targets[HookPoint.TAB_VISIBILITY]
            hook(updateVisibility) { chain ->
                val result = chain.proceed()
                try {
                    val bar = getBar.invoke(chain.thisObject) as ViewGroup
                    bars[bar]?.let { state ->
                        // 同时保留宿主自身简洁模式的隐藏行为。
                        state.hiddenByHost = chain.getArg(0) as Boolean
                        applySafely(bar, state)
                    }
                } catch (error: Exception) {
                    module.log(Log.ERROR, ModuleMain.TAG, "Failed to update bottom bar visibility", error)
                }
                result
            }
            val selectTab = targets[HookPoint.TAB_SELECT]
            hook(selectTab) { chain ->
                val context = chain.thisObject as Context
                // 全部隐藏时保留课表页，确保仍能长按三点恢复底栏。
                if (BottomBarSettings.allHidden(context)) {
                    chain.proceed(arrayOf<Any>("schedule"))
                } else {
                    chain.proceed()
                }
            }
            module.log(Log.INFO, ModuleMain.TAG, "Bottom bar simplification installed")
        }
    }

    fun refresh() {
        bars.entries.toList().forEach { (bar, state) -> applySafely(bar, state) }
    }

    private fun applySafely(bar: ViewGroup, state: BarState) {
        try {
            apply(bar, state)
        } catch (error: Exception) {
            module.log(Log.ERROR, ModuleMain.TAG, "Failed to apply bottom bar settings", error)
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun apply(bar: ViewGroup, state: BarState) {
        val tabsId = bar.resources.getIdentifier("ll_tab", "id", TargetApp.PACKAGE_NAME)
        val tabs = bar.findViewById<LinearLayout>(tabsId) ?: return
        check(tabs.childCount == state.tabIds.size) { "Bottom bar item count changed" }
        val titleId = bar.resources.getIdentifier("tv_title", "id", TargetApp.PACKAGE_NAME)
        state.tabIds.forEachIndexed { index, id ->
            val item = tabs.getChildAt(index)
            item.visibility = if (BottomBarSettings.isVisible(bar.context, id)) View.VISIBLE else View.GONE
            // 保留子视图顺序和原有点击索引，只让剩余入口均分宽度。
            item.layoutParams = LinearLayout.LayoutParams(0, item.layoutParams.height, 1f)
            item.findViewById<View>(titleId)?.let { title ->
                title.layoutParams = title.layoutParams.apply { width = ViewGroup.LayoutParams.MATCH_PARENT }
            }
        }
        bar.visibility = if (state.hiddenByHost || BottomBarSettings.allHidden(bar.context)) {
            View.GONE
        } else {
            View.VISIBLE
        }
        bar.requestLayout()
    }
}
