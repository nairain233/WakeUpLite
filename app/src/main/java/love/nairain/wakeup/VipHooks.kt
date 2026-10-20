package love.nairain.wakeup

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

internal class VipHooks(
    private val module: XposedInterface,
    private val isUnlocked: () -> Boolean,
    private val getContext: () -> Context?
) {
    fun install(targets: HookTargets) {
        targets.install("本地会员", HookPoint.VIP) {
            val isVip = targets[HookPoint.VIP]
            val logged = AtomicBoolean()
            // 仅替换本地会员准入判断，保留真实账户与登录状态。
            hook(isVip) { chain ->
                if (!isUnlocked()) return@hook chain.proceed()
                if (logged.compareAndSet(false, true)) {
                    module.log(Log.INFO, ModuleMain.TAG, "Local VIP enabled without login")
                }
                true
            }
        }

        targets.install("深色模式保存", HookPoint.DARK_GET, HookPoint.DARK_SET) {
            installMode(
                targets[HookPoint.DARK_GET], targets[HookPoint.DARK_SET], "dark_mode"
            )
        }
        targets.install("简洁模式保存", HookPoint.SIMPLE_GET, HookPoint.SIMPLE_SET) {
            installMode(
                targets[HookPoint.SIMPLE_GET], targets[HookPoint.SIMPLE_SET], "simple_mode"
            )
        }
    }

    private fun HookTargets.Group.installMode(getter: Method, setter: Method, key: String) {
        check(getter.returnType == Boolean::class.javaPrimitiveType && setter.returnType == Void.TYPE)
        hook(getter) { chain ->
            if (!isUnlocked()) return@hook chain.proceed()
            val preferences = preferences()
            // 未使用模块保存过该设置时，沿用宿主原有选项。
            if (preferences != null && preferences.contains(key)) {
                preferences.getBoolean(key, false)
            } else {
                chain.proceed()
            }
        }
        hook(setter) { chain ->
            val result = chain.proceed()
            if (!isUnlocked()) return@hook result
            val value = chain.getArg(0)
            val enabled = when (value) {
                is Boolean -> value
                is Int -> value == 1
                else -> error("Unexpected mode value type")
            }
            // 同时保留原设置写入，让已登录用户继续拥有宿主自己的配置。
            preferences()?.edit()?.putBoolean(key, enabled)?.apply()
            module.log(Log.INFO, ModuleMain.TAG, "Saved local VIP mode $key: $enabled")
            result
        }
    }

    private fun preferences(): SharedPreferences? =
        getContext()?.getSharedPreferences(
            "love.nairain.wakeup.vip", Context.MODE_PRIVATE
        )
}
