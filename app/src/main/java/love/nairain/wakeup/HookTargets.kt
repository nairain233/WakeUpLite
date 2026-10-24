package love.nairain.wakeup

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

internal enum class HookPoint(val label: String) {
    VIP("本地会员"), DARK_GET("深色模式读取"), DARK_SET("深色模式保存"),
    SIMPLE_GET("简洁模式读取"), SIMPLE_SET("简洁模式保存"),
    COLD_AD("冷启动广告"), HOT_AD("热启动广告"), BANNER_AD("底部广告"),
    RATING_DIALOG("好评弹窗"),
    MINE_CREATE("我的页面创建"), MINE_ROOT("我的页面根视图"), MINE_RENDER("我的页面渲染"),
    TAB_LOAD("底栏创建"), TAB_ID("底栏项目标识"), TAB_GET("底栏视图"),
    TAB_VISIBILITY("底栏可见性"), TAB_SELECT("底栏选择"),
    SHORTCUT_GRID("课表快捷区"), MENU_CREATE("模块设置入口")
}

internal class HookTargets(
    private val module: XposedInterface,
    private val status: StartupStatus
) {
    private val methods = mutableMapOf<HookPoint, Method>()

    operator fun get(point: HookPoint): Method = methods[point]
        ?: error("未解析：${point.label}")

    fun put(point: HookPoint, method: Method) {
        methods[point] = method.apply { isAccessible = true }
    }

    val size: Int get() = methods.size

    // 有依赖关系的方法必须全部解析成功，才允许安装对应规则。
    fun install(label: String, vararg required: HookPoint, action: Group.() -> Unit) {
        if (required.any { it !in methods }) {
            status.failed("$label：缺少定位结果")
            return
        }
        val group = Group(module)
        try {
            group.action()
            module.log(Log.INFO, ModuleMain.TAG, "Hook group installed: $label")
        } catch (error: Throwable) {
            // 同组后续 Hook 安装失败时撤销先前的 Hook，避免留下不完整的功能。
            group.handles.asReversed().forEach { handle ->
                status.attempt("撤销未完成的规则：$label") { handle.unhook() }
            }
            status.failed(label, error)
        }
    }

    class Group(private val module: XposedInterface) {
        internal val handles = mutableListOf<XposedInterface.HookHandle>()

        fun hook(method: Method, hooker: XposedInterface.Hooker) {
            handles += module.hook(method).intercept(hooker)
        }
    }

    fun deoptimize(callers: Collection<java.lang.reflect.Executable>) {
        callers.distinct().forEach { caller ->
            status.attempt("调用方去优化：$caller") {
                check(module.deoptimize(caller)) { "框架未能完成去优化" }
            }
        }
        module.log(Log.INFO, ModuleMain.TAG, "DexKit resolved $size/${HookPoint.entries.size} methods")
    }
}
