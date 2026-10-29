package love.nairain.wakeup

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

class ModuleMain : XposedModule() {
    private var isMainProcess = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        isMainProcess = param.processName == TargetApp.PACKAGE_NAME
        if (param.processName == TargetApp.PACKAGE_NAME) {
            log(Log.INFO, TAG, "WakeUpLite loaded: ${param.processName}")
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != TargetApp.PACKAGE_NAME || !param.isFirstPackage) return

        if (isMainProcess) {
            try {
                ModuleTelemetry.install(this)
            } catch (error: Throwable) {
                log(Log.ERROR, TAG, "Failed to install module analytics", error)
            }
        }

        val status = StartupStatus(this)
        status.attempt("启动状态监听") {
            StartupCoordinator(this, param.applicationInfo, param.classLoader, isMainProcess, status) {
                targets, canOpenMenu ->
                installFeatures(param.classLoader, targets, status, canOpenMenu)
            }.install()
        }
    }

    private fun installFeatures(
        classLoader: ClassLoader,
        targets: HookTargets,
        status: StartupStatus,
        canOpenMenu: () -> Boolean
    ) {
        // 不依赖宿主混淆后的全局上下文访问器；每次调用读取当前解锁状态。
        val isUnlocked = {
            status.application?.let { FeatureUnlockSettings.isUnlocked(it) } == true
        }
        VipHooks(this, isUnlocked) { status.application }.install(targets)
        AdBlockHooks(this, isUnlocked).install(targets)
        RatingDialogHook(this).install(targets)
        UpdateDialogHook(this) {
            status.application?.let { UpdateDialogSettings.isBlocked(it) } == true
        }.install(targets)

        val minePage = MinePageHook(this)
        minePage.install(targets)

        val bottomBar = BottomBarHook(this)
        bottomBar.install(targets)

        val shortcuts = ScheduleShortcutHook(this)
        shortcuts.install(classLoader, targets)

        ScheduleMenuHook(this, bottomBar::refresh, minePage::refresh, shortcuts::refresh, canOpenMenu)
            .install(targets)
    }

    companion object {
        const val TAG = "WakeUpLite"
    }
}
