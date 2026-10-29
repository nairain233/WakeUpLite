package love.nairain.wakeup

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.atomic.AtomicBoolean

internal class UpdateDialogHook(
    private val module: XposedInterface,
    private val isBlocked: () -> Boolean
) {
    fun install(targets: HookTargets) {
        targets.install(HookPoint.UPDATE_DIALOG.label, HookPoint.UPDATE_DIALOG) {
            val method = targets[HookPoint.UPDATE_DIALOG]
            val apkUrl = method.parameterTypes.single().getField("apkUrl")
            check(apkUrl.type == String::class.java) { "更新下载地址字段不匹配" }
            val logged = AtomicBoolean()
            hook(method) { chain ->
                val update = chain.getArg(0)
                if (!isBlocked() || update == null) {
                    return@hook chain.proceed()
                }
                if (logged.compareAndSet(false, true)) {
                    module.log(Log.INFO, ModuleMain.TAG, "Blocked update dialog")
                }
                // 走宿主无更新分支，保留手动检查的加载收尾和自动弹窗队列回调。
                val originalUrl = apkUrl.get(update)
                apkUrl.set(update, "")
                try {
                    chain.proceed()
                } finally {
                    apkUrl.set(update, originalUrl)
                }
            }
        }
    }
}
