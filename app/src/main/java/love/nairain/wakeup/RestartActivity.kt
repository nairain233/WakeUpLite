package love.nairain.wakeup

import android.app.Activity
import android.content.Intent
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.view.Gravity
import android.widget.TextView

// 不提供桌面入口；仅由宿主内的确认按钮调用，在独立进程中接续重启。
class RestartActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 18f
            text = "正在重新打开 WakeUp…"
        })
    }

    @Suppress("DEPRECATION")
    override fun onPostResume() {
        super.onPostResume()
        if (started) return
        val ready = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_READY, ResultReceiver::class.java)
        } else intent.getParcelableExtra(EXTRA_READY) as? ResultReceiver
        if (ready == null) { finishAndRemoveTask(); return }
        val launch = ComponentName.unflattenFromString(intent.getStringExtra(EXTRA_LAUNCH).orEmpty())
            ?.takeIf { it.packageName == TargetApp.PACKAGE_NAME }
        if (launch == null) { showFailure("未找到 WakeUp 启动入口，请手动重新打开。"); return }
        started = true
        // 前台窗口建立后通知宿主退出，再以可见 Activity 的身份重新启动宿主。
        main.postDelayed({
            ready.send(RESULT_OK, null)
            main.postDelayed({
                try {
                    startActivity(Intent.makeRestartActivityTask(launch))
                    finishAndRemoveTask()
                } catch (error: RuntimeException) {
                    showFailure("自动重新打开失败，请手动打开 WakeUp。\n${error.message.orEmpty()}")
                }
            }, 500L)
        }, 200L)
    }

    private fun showFailure(message: String) {
        setContentView(TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 18f
            text = message
        })
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_READY = "love.nairain.wakeup.RESTART_READY"
        const val EXTRA_LAUNCH = "love.nairain.wakeup.RESTART_LAUNCH"
    }
}
