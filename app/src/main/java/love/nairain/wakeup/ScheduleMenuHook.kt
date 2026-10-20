package love.nairain.wakeup

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.util.Log
import android.view.View
import io.github.libxposed.api.XposedInterface

internal class ScheduleMenuHook(
    private val module: XposedInterface,
    private val onBottomBarChanged: () -> Unit,
    private val onMinePageChanged: () -> Unit,
    private val onShortcutsChanged: () -> Unit,
    private val canOpenMenu: () -> Boolean
) {
    fun install(targets: HookTargets) {
        targets.install("模块设置入口", HookPoint.MENU_CREATE) {
            val onViewCreated = targets[HookPoint.MENU_CREATE]
            hook(onViewCreated) { chain ->
                val result = chain.proceed()
                try {
                    bindMenu(chain.getArg(0) as View)
                } catch (error: Exception) {
                    module.log(Log.ERROR, ModuleMain.TAG, "Failed to bind schedule menu", error)
                }
                result
            }
            module.log(Log.INFO, ModuleMain.TAG, "Schedule menu hook installed")
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun bindMenu(root: View) {
        // 按已核对的资源名解析按钮，避免依赖 6.5.4 的固定资源编号。
        val menuId = root.resources.getIdentifier("scheduleMore", "id", TargetApp.PACKAGE_NAME)
        val menu = if (menuId == 0) null else root.findViewById<View>(menuId)
        if (menu == null) {
            module.log(Log.WARN, ModuleMain.TAG, "scheduleMore not found; leaving schedule unchanged")
            return
        }

        var dialog: AlertDialog? = null
        menu.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit

            override fun onViewDetachedFromWindow(view: View) {
                dialog?.dismiss()
                dialog = null
            }
        })
        menu.setOnLongClickListener { view ->
            if (!canOpenMenu()) return@setOnLongClickListener true
            try {
                if (dialog?.isShowing != true) {
                    dialog = ModuleDialog.show(
                        view.context, onBottomBarChanged, onMinePageChanged, onShortcutsChanged
                    ) {
                        dialog = null
                    }
                    if (dialog != null) {
                        module.log(Log.INFO, ModuleMain.TAG, "Module dialog shown inside WakeUp")
                    }
                }
            } catch (error: RuntimeException) {
                module.log(Log.ERROR, ModuleMain.TAG, "Failed to show module dialog", error)
            }
            // 消费长按事件，避免松手后继续弹出宿主原菜单。
            true
        }
        module.log(Log.INFO, ModuleMain.TAG, "Long press bound to scheduleMore")
    }
}
