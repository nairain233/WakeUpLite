package love.nairain.wakeup

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.atomic.AtomicBoolean

internal class AdBlockHooks(
    private val module: XposedInterface,
    private val isUnlocked: () -> Boolean
) {
    fun install(targets: HookTargets) {
        installRule(targets, HookPoint.COLD_AD, false, "cold splash")
        installRule(targets, HookPoint.HOT_AD, false, "hot splash")
        installRule(targets, HookPoint.BANNER_AD, -1, "bottom banner")
    }

    private fun installRule(targets: HookTargets, point: HookPoint, blockedResult: Any, label: String) {
        targets.install(point.label, point) {
            val method = targets[point]
            val logged = AtomicBoolean()
            hook(method) { chain ->
                if (!isUnlocked()) return@hook chain.proceed()
                if (logged.compareAndSet(false, true)) {
                    module.log(Log.INFO, ModuleMain.TAG, "Blocked $label")
                }
                blockedResult
            }
            module.log(Log.INFO, ModuleMain.TAG, "Ad blocking installed: $label")
        }
    }
}
