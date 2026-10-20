package love.nairain.wakeup

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.ResultReceiver

internal object HostRestarter {
    fun restart(activity: Activity): String? {
        try {
            val launch = activity.packageManager.getLaunchIntentForPackage(TargetApp.PACKAGE_NAME)?.component
                ?: return "无法取得启动入口，请手动重启 WakeUp。"
            // 先让独立进程的前台中转界面就绪，再结束宿主，避免闹钟被后台启动限制拦截。
            val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    if (resultCode == Activity.RESULT_OK) Process.killProcess(Process.myPid())
                }
            }
            activity.startActivity(Intent().setClassName(BuildConfig.APPLICATION_ID, RestartActivity::class.java.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(RestartActivity.EXTRA_LAUNCH, launch.flattenToString())
                .putExtra(RestartActivity.EXTRA_READY, receiver))
            return null
        } catch (error: Exception) {
            return "自动重启失败：${error.message ?: error.javaClass.simpleName}。请手动重启 WakeUp。"
        }
    }
}
