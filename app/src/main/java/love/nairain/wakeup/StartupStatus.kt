package love.nairain.wakeup

import android.app.Application
import android.app.Instrumentation
import android.util.Log
import io.github.libxposed.api.XposedInterface

internal class StartupStatus(private val module: XposedInterface) {
    @Volatile var application: Application? = null
        private set

    fun failed(label: String, error: Throwable? = null) {
        module.log(Log.WARN, ModuleMain.TAG, "Startup degraded: $label", error)
    }

    fun attempt(label: String, action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            failed(label, error)
        }
    }

    fun install(onApplication: (Application) -> Unit = {}) {
        val create = Instrumentation::class.java.getDeclaredMethod(
            "callApplicationOnCreate", Application::class.java
        )
        module.hook(create).intercept { chain ->
            val app = chain.getArg(0) as Application
            if (app.packageName == TargetApp.PACKAGE_NAME && application == null) {
                // 在宿主 onCreate 前提供上下文，让启动时的模式判断也能读取模块配置。
                application = app
                attempt("启动流程监听") { onApplication(app) }
            }
            chain.proceed()
        }
    }
}
