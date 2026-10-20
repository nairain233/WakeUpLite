package love.nairain.wakeup

import android.app.Activity
import android.app.AlertDialog
import android.os.CountDownTimer
import android.os.SystemClock
import android.widget.TextView
import java.util.Locale

internal object ModuleUsageNotice {
    const val WAIT_MS = 5_000L

    // 展示时机和确认状态由启动协调器统一管理，避免与扫描报告同时弹出。
    fun show(activity: Activity, accept: () -> Boolean, onDismiss: () -> Unit): AlertDialog {
        var timer: CountDownTimer? = null
        val style = DialogStyle(activity)
        val content = style.column().apply {
            addView(style.section("仅供学习使用"))
            addView(style.description("本模块仅供学习、研究与技术交流。请遵守适用法律法规及相关协议，尊重软件作者与其他权利人的合法权益。"))
            addView(style.section("使用前请审核源代码"))
            addView(style.description("请先审核本模块源代码，了解其实现方式及数据处理行为，确认实际行为符合你的预期、使用要求及适用规则后再继续。如有疑问或无法确认，请停用本模块。"))
            addView(style.description("本模块为独立第三方项目，与 WakeUp 官方无关联。使用前请备份重要课表与数据，并自行评估兼容性与使用风险。"))
            addView(style.description("请认真阅读以上说明，等待 5 秒后可点击继续。首次扫描或重新适配后需要重新确认。"))
        }
        lateinit var action: TextView
        val notice = style.show("使用须知", "WakeUpLite", content, "请阅读（5 秒）") { button ->
            action = button
            button.isEnabled = false
            button.alpha = 0.5f
        }
        notice.setCancelable(false)
        notice.setCanceledOnTouchOutside(false)
        notice.setOnDismissListener {
            timer?.cancel()
            timer = null
            onDismiss()
        }
        // 从弹窗真正显示后开始计时，仅点击继续才确认本轮缓存的使用须知。
        val countdown = NoticeCountdown(SystemClock.elapsedRealtime())
        action.setOnClickListener {
            // 同时校验单调时钟，防止倒计时结束前通过其他点击方式继续。
            if (countdown.ready(SystemClock.elapsedRealtime())) {
                if (accept()) notice.dismiss() else action.text = "保存失败，点击重试"
            }
        }
        timer = object : CountDownTimer(WAIT_MS, 250L) {
            override fun onTick(millisUntilFinished: Long) {
                action.text = String.format(Locale.SIMPLIFIED_CHINESE, "请阅读（%d 秒）",
                    countdown.secondsRemaining(SystemClock.elapsedRealtime()))
            }

            override fun onFinish() {
                action.text = "已知悉，继续"
                action.alpha = 1f
                action.isEnabled = true
            }
        }.start()
        return notice
    }
}
