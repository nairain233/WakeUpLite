package love.nairain.wakeup

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.atomic.AtomicBoolean

internal class RatingDialogHook(private val module: XposedInterface) {
    fun install(targets: HookTargets) {
        targets.install(HookPoint.RATING_DIALOG.label, HookPoint.RATING_DIALOG) {
            val logged = AtomicBoolean()
            hook(targets[HookPoint.RATING_DIALOG]) {
                if (logged.compareAndSet(false, true)) {
                    module.log(Log.INFO, ModuleMain.TAG, "Blocked rating dialog")
                }
                // 宿主以 false 表示未展示，弹窗队列会继续处理下一项。
                false
            }
        }
    }
}
