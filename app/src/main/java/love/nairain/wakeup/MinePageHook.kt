package love.nairain.wakeup

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface
import java.util.WeakHashMap

internal class MinePageHook(private val module: XposedInterface) {
    private class PageState(val rootPaddingTop: Int) {
        val hostVisibility = mutableMapOf<MinePageSettings.Item, Int>()
    }

    // 值只保存原始可见性，不持有子视图，避免通过父视图间接阻止弱引用回收。
    private val pages = WeakHashMap<View, PageState>()

    fun install(targets: HookTargets) {
        targets.install("我的页面创建", HookPoint.MINE_CREATE) {
            hook(targets[HookPoint.MINE_CREATE]) { chain ->
                val root = chain.proceed() as? View
                if (root != null) applySafely(root)
                root
            }
        }

        targets.install("我的页面动态渲染", HookPoint.MINE_ROOT, HookPoint.MINE_RENDER) {
            val getRoot = targets[HookPoint.MINE_ROOT]
            val renderCards = targets[HookPoint.MINE_RENDER]
            hook(renderCards) { chain ->
                val root = try {
                    getRoot.invoke(chain.getArg(0)) as? View
                } catch (error: Exception) {
                    module.log(Log.ERROR, ModuleMain.TAG, "Failed to find mine page", error)
                    null
                }
                // 先还原宿主状态再渲染，避免把模块隐藏结果当成宿主原始状态。
                if (root != null) restoreHostVisibility(root)
                val result = chain.proceed()
                if (root != null) applySafely(root, captureHostVisibility = true)
                result
            }
        }
    }

    fun refresh() {
        pages.keys.toList().forEach { applySafely(it) }
    }

    private fun restoreHostVisibility(root: View) {
        try {
            val state = pages[root] ?: return
            findItems(root).forEach { (item, view) ->
                state.hostVisibility[item]?.let { view.visibility = it }
            }
        } catch (error: Exception) {
            module.log(Log.ERROR, ModuleMain.TAG, "Failed to restore mine page visibility", error)
        }
    }

    private fun applySafely(root: View, captureHostVisibility: Boolean = false) {
        try {
            val items = findItems(root)
            val state = pages.getOrPut(root) {
                root.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                    updateTopInset(view)
                }
                PageState(root.paddingTop)
            }
            items.forEach { (item, view) ->
                if (captureHostVisibility || item !in state.hostVisibility) {
                    state.hostVisibility[item] = view.visibility
                }
                view.visibility = if (MinePageSettings.isVisible(root.context, item)) {
                    state.hostVisibility.getValue(item)
                } else {
                    View.GONE
                }
            }
            updateTopInset(root)
            items.filterKeys { it.isMenu }.values
                .mapNotNull { it.parent as? ViewGroup }.distinct()
                .forEach(::updateMenuGroup)
        } catch (error: Exception) {
            module.log(Log.ERROR, ModuleMain.TAG, "Failed to apply mine page settings", error)
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun updateTopInset(root: View) {
        try {
            val state = pages[root] ?: return
            val profileId = root.resources.getIdentifier(
                MinePageSettings.Item.PROFILE.viewId, "id", TargetApp.PACKAGE_NAME
            )
            val profile = if (profileId == 0) null else root.findViewById<View>(profileId)
            var topInset = 0
            if (profile?.visibility == View.GONE) {
                // 资料区原本承担状态栏避让，隐藏后由整个滚动区域接续，滚动时也不侵入顶栏。
                topInset = (profile.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin
                    ?.coerceAtLeast(0) ?: 0
                val barId = root.resources.getIdentifier("mineBackBar", "id", TargetApp.PACKAGE_NAME)
                val bar = if (barId == 0) null else root.context.findActivity()?.findViewById<View>(barId)
                if (bar != null && bar.visibility == View.VISIBLE) {
                    topInset = if (root.isLaidOut && bar.isLaidOut) {
                        val rootPosition = IntArray(2)
                        val barPosition = IntArray(2)
                        root.getLocationInWindow(rootPosition)
                        bar.getLocationInWindow(barPosition)
                        maxOf(topInset, barPosition[1] + bar.height - rootPosition[1])
                    } else {
                        // 首次布局前使用宿主顶栏的最小高度，布局完成后再按实际位置校正。
                        val barTop = (bar.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
                        maxOf(topInset, barTop) + maxOf(bar.height, bar.minimumHeight)
                    }
                } else {
                    // 宿主返回顶栏高 48dp，无返回按钮时也预留相同高度，使两种页面对齐。
                    topInset += (48 * root.resources.displayMetrics.density).toInt()
                }
            }
            val paddingTop = state.rootPaddingTop + topInset
            if (root.paddingTop != paddingTop) {
                root.setPadding(root.paddingLeft, paddingTop, root.paddingRight, root.paddingBottom)
            }
        } catch (error: Exception) {
            module.log(Log.ERROR, ModuleMain.TAG, "Failed to update mine page top inset", error)
        }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> if (baseContext !== this) baseContext.findActivity() else null
        else -> null
    }

    private fun updateMenuGroup(group: ViewGroup) {
        var hasVisibleItem = false
        var divider: View? = null
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (child.javaClass == View::class.java && child.id == View.NO_ID) {
                // 每两个仍显示的菜单之间最多保留一条线，首尾不留分隔线。
                child.visibility = View.GONE
                if (hasVisibleItem && divider == null) divider = child
            } else if (child.visibility == View.VISIBLE) {
                if (hasVisibleItem) divider?.visibility = View.VISIBLE
                hasVisibleItem = true
                divider = null
            }
        }
        group.visibility = if (hasVisibleItem) View.VISIBLE else View.GONE
    }

    @SuppressLint("DiscouragedApi")
    private fun findItems(root: View): Map<MinePageSettings.Item, View> =
        MinePageSettings.Item.entries.mapNotNull { item ->
            val id = root.resources.getIdentifier(item.viewId, "id", TargetApp.PACKAGE_NAME)
            val view = if (id == 0) null else root.findViewById<View>(id)
            view?.let { item to it }
        }.toMap()
}
