package love.nairain.wakeup

import android.annotation.SuppressLint
import android.util.Log
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.util.WeakHashMap

internal class ScheduleShortcutHook(private val module: XposedInterface) {
    private val grids = WeakHashMap<ViewGroup, Unit>()
    private lateinit var constraints: Map<String, Field>
    private lateinit var widthMode: Field
    private lateinit var widthPercent: Field
    private lateinit var cardType: Class<*>

    fun install(classLoader: ClassLoader, targets: HookTargets) {
        targets.install("课表快捷区精简", HookPoint.SHORTCUT_GRID) {
            val parameters = classLoader.loadClass("androidx.constraintlayout.widget.ConstraintLayout\$LayoutParams")
            cardType = classLoader.loadClass("com.google.android.material.card.MaterialCardView")
            constraints = listOf(
                "startToStart", "startToEnd", "endToStart", "endToEnd",
                "topToTop", "topToBottom", "bottomToTop", "bottomToBottom"
            ).associateWith(parameters::getField)
            widthMode = parameters.getField("matchConstraintDefaultWidth")
            widthPercent = parameters.getField("matchConstraintPercentWidth")
            hook(targets[HookPoint.SHORTCUT_GRID]) { chain ->
                val result = chain.proceed()
                (result as? ViewGroup)?.let { grid ->
                    grids[grid] = Unit
                    applySafely(grid)
                }
                result
            }
            module.log(Log.INFO, ModuleMain.TAG, "Schedule shortcut simplification installed")
        }
    }

    fun refresh() {
        grids.keys.toList().forEach(::applySafely)
    }

    private fun applySafely(grid: ViewGroup) {
        try {
            apply(grid)
        } catch (error: Exception) {
            module.log(Log.ERROR, ModuleMain.TAG, "Failed to apply schedule shortcut settings", error)
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun apply(grid: ViewGroup) {
        val items = ScheduleShortcutSettings.Item.entries.map { item ->
            val id = grid.resources.getIdentifier(item.viewId, "id", TargetApp.PACKAGE_NAME)
            val view = if (id == 0) null else grid.findViewById<View>(id)
            check(view != null && view.parent === grid) { "Schedule shortcut not found: ${item.viewId}" }
            item to view
        }
        val visible = items.filter { ScheduleShortcutSettings.isVisible(grid.context, it.first) }
            .map { it.second }
        items.forEach { (_, view) ->
            val parameters = view.layoutParams as ViewGroup.MarginLayoutParams
            // 保留原视图及点击监听，只重排约束；隐藏项不再参与原来的两行依赖。
            constraints.values.forEach { it.setInt(parameters, -1) }
            // 每格固定占快捷区宽度的四分之一，最后一行不足四项时在末尾留空。
            widthMode.setInt(parameters, 2)
            widthPercent.setFloat(parameters, 0.25f)
            parameters.width = 0
            parameters.setMargins(0, 0, 0, 0)
            parameters.marginStart = 0
            parameters.marginEnd = 0
            view.visibility = if (view in visible) View.VISIBLE else View.GONE
            constraints.getValue("startToStart").setInt(parameters, 0)
            constraints.getValue("topToTop").setInt(parameters, 0)
            view.layoutParams = parameters
        }
        val rows = visible.chunked(4)
        rows.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { column, view ->
                val parameters = view.layoutParams as ViewGroup.MarginLayoutParams
                constraints.getValue("startToStart").setInt(parameters, if (column == 0) 0 else -1)
                constraints.getValue("startToEnd").setInt(parameters, row.getOrNull(column - 1)?.id ?: -1)
                constraints.getValue("topToTop").setInt(parameters, if (rowIndex == 0) 0 else -1)
                constraints.getValue("topToBottom").setInt(parameters, rows.getOrNull(rowIndex - 1)?.first()?.id ?: -1)
                constraints.getValue("bottomToBottom").setInt(parameters, if (rowIndex == rows.lastIndex) 0 else -1)
                parameters.topMargin = if (rowIndex == 0) 0 else (24 * grid.resources.displayMetrics.density).toInt()
                view.layoutParams = parameters
            }
        }
        val visibility = if (visible.isEmpty()) View.GONE else View.VISIBLE
        grid.visibility = visibility
        // 快捷区独占一张卡片，全部隐藏时一起收起卡片背景及其外边距。
        (grid.parent as? ViewGroup)?.takeIf {
            cardType.isInstance(it) && it.childCount == 1 && it.getChildAt(0) === grid
        }?.visibility = visibility
        grid.requestLayout()
    }
}
