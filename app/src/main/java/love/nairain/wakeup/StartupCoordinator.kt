package love.nairain.wakeup

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.Executors

internal class StartupCoordinator(
    private val module: XposedInterface,
    private val info: ApplicationInfo,
    private val classLoader: ClassLoader,
    private val mainProcess: Boolean,
    private val status: StartupStatus,
    private val installFeatures: (HookTargets, () -> Boolean) -> Unit
) : Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "WakeUpLite-DexKit").apply { isDaemon = true }
    }
    // 使用真实进程启动标识；协调器重建或窗口切换不会被误判成进程重启。
    private val process = "${Process.myPid()}:${Process.getStartElapsedRealtime()}"
    private val cache = DexKitCache(info.dataDir)
    private var fingerprint = ""
    private var snapshot: ScanSnapshot? = null
    private var pendingWrite: ScanSnapshot? = null
    private var progress = ScanProgress()
    private var failure: ScanIssue? = null
    private var busy = false
    private var restartMessage: String? = null
    private var resumed: Activity? = null
    private var dialog: AlertDialog? = null
    private var report: ScanDialog? = null

    fun install() {
        status.install { app -> if (mainProcess) app.registerActivityLifecycleCallbacks(this) }
        try {
            fingerprint = calculateFingerprint()
            val cached = try { cache.read(fingerprint) } catch (error: Throwable) {
                status.failed(cache.file.path, error)
                null
            }
            if (cached != null) {
                if (cached.phase != BootstrapPhase.RESULT) {
                    // 在宿主创建页面前恢复 Hook；失败时丢弃本次缓存加载并重新扫描。
                    val (targets, callers) = CachedTargets.restore(cached, classLoader, module, status)
                    snapshot = cached
                    targets.deoptimize(callers)
                    installFeatures(targets) { snapshot?.route(process) == StartupRoute.READY }
                } else {
                    snapshot = cached
                }
                module.log(Log.INFO, ModuleMain.TAG, "DexKit cache hit: ${cached.successful}/${HookPoint.entries.size}")
                return
            }
        } catch (error: Throwable) {
            status.failed("缓存校验", error)
            snapshot = null
        }
        if (mainProcess) scan()
    }

    private fun calculateFingerprint() = ApkFingerprint.calculate(
        listOf(info.sourceDir) + info.splitSourceDirs.orEmpty(), module.moduleApplicationInfo.sourceDir
    )

    private fun scan() {
        if (busy) return
        busy = true
        failure = null
        snapshot = null
        progress = ScanProgress()
        render()
        worker.execute {
            try {
                fingerprint = calculateFingerprint()
                val result = DexKitResolver(module, status, classLoader).resolve(info, fingerprint) { update ->
                    main.post { progress = update; render() }
                }
                saveScan(result)
            } catch (error: Throwable) {
                status.failed(info.sourceDir, error)
                main.post {
                    busy = false
                    failure = ScanIssue(info.sourceDir, error.message ?: error.javaClass.simpleName)
                    render()
                }
            }
        }
    }

    private fun saveScan(result: ScanSnapshot) {
        main.post { progress = ScanProgress("保存缓存", result.results, result.issues); render() }
        try {
            cache.write(result)
            main.post {
                snapshot = result
                pendingWrite = null
                failure = null
                busy = false
                render()
            }
        } catch (error: Throwable) {
            status.failed(cache.file.path, error)
            main.post {
                pendingWrite = result
                failure = ScanIssue(cache.file.path, error.message ?: error.javaClass.simpleName)
                busy = false
                render()
            }
        }
    }

    private fun action() {
        if (busy) return
        val current = snapshot
        if (current == null) {
            val unsaved = pendingWrite
            if (unsaved != null) {
                busy = true
                failure = null
                render()
                worker.execute { saveScan(unsaved) }
            } else scan()
            return
        }
        val activity = resumed ?: return
        try {
            val next = current.confirmRestart(process)
            cache.write(next)
            snapshot = next
            failure = null
            restartMessage = null
            render()
            restartMessage = HostRestarter.restart(activity)
            render()
        } catch (error: Throwable) {
            status.failed(cache.file.path, error)
            failure = ScanIssue(cache.file.path, error.message ?: error.javaClass.simpleName)
            render()
        }
    }

    private fun acceptNotice(): Boolean = try {
        val next = checkNotNull(snapshot).acceptNotice(process)
        cache.write(next)
        snapshot = next
        true
    } catch (error: Throwable) {
        status.failed(cache.file.path, error)
        false
    }

    private fun render() {
        val activity = resumed ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        status.attempt("启动界面") {
            when (snapshot?.route(process)) {
                StartupRoute.READY -> closeDialog()
                // 启动页会自动跳转，避免先在启动页计时、进入课表后又重新等待五秒。
                StartupRoute.NOTICE -> if (dialog == null &&
                    activity.javaClass.name == "${TargetApp.PACKAGE_NAME}.schedule.ScheduleActivity") {
                    dialog = ModuleUsageNotice.show(activity, ::acceptNotice) { dialog = null }
                }
                else -> {
                    if (report == null) {
                        report = ScanDialog(activity, ::action, {
                            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${TargetApp.PACKAGE_NAME}")))
                        }) { dialog = null; report = null }
                        dialog = report!!.dialog
                    }
                    report?.render(progress, snapshot, busy, failure,
                        snapshot?.route(process) == StartupRoute.RESTART, restartMessage)
                }
            }
        }
    }

    private fun closeDialog() {
        dialog?.dismiss()
        dialog = null
        report = null
    }

    override fun onActivityResumed(activity: Activity) {
        if (resumed !== activity) closeDialog()
        resumed = activity
        activity.window.decorView.post { if (resumed === activity) render() }
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed === activity) { resumed = null; closeDialog() }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (resumed === activity) { resumed = null; closeDialog() }
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
}
