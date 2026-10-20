package love.nairain.wakeup

import android.app.Activity
import android.app.AlertDialog
import android.widget.ProgressBar
import android.widget.TextView

internal class ScanDialog(
    activity: Activity,
    onAction: () -> Unit,
    onManualRestart: () -> Unit,
    onDismiss: () -> Unit
) {
    private val style = DialogStyle(activity)
    private val content = style.column()
    private lateinit var action: TextView
    val dialog: AlertDialog = style.show("DEX 扫描", "WakeUpLite", content, "准备扫描") {
        action = it
        it.setOnClickListener { onAction() }
    }.apply {
        setCancelable(false)
        setCanceledOnTouchOutside(false)
        setOnDismissListener { onDismiss() }
    }
    private val manualRestart = onManualRestart

    fun render(
        progress: ScanProgress,
        snapshot: ScanSnapshot?,
        busy: Boolean,
        failure: ScanIssue?,
        restarting: Boolean,
        restartMessage: String?
    ) {
        content.removeAllViews()
        when {
            restarting -> content.addView(style.description("等待重启"))
            failure != null -> content.addView(style.scanStatus("异常", false))
            snapshot != null -> {
                content.addView(style.scanStatus(if (snapshot.normal) "正常" else "异常", snapshot.normal))
                if (!snapshot.normal) content.addView(style.description("部分功能可能不可用"))
            }
            else -> content.addView(style.description(progress.stage))
        }
        val results = snapshot?.results ?: progress.results
        content.addView(style.description("已扫描 ${results.size}/${HookPoint.entries.size}，已定位 ${results.count { it.method != null }}"))
        content.addView(ProgressBar(style.context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = HookPoint.entries.size
            this.progress = results.size
            isIndeterminate = busy && progress.stage != "扫描路径"
            setPadding(24, 8, 24, 8)
        })
        HookPoint.entries.forEach { point ->
            val result = results.firstOrNull { it.point == point }
            if (result?.method != null || (result == null && failure == null)) return@forEach
            content.addView(style.scanPath(point.queryPath))
            content.addView(style.scanStatus(result?.error ?: "未完成扫描", false))
        }
        (snapshot?.issues ?: progress.issues).forEach { issue ->
            content.addView(style.scanPath(issue.path))
            content.addView(style.scanStatus(issue.reason, false))
        }
        failure?.let {
            content.addView(style.scanPath(it.path))
            content.addView(style.scanStatus(it.reason, false))
        }
        if (restarting) {
            content.addView(style.description(restartMessage ?: "正在重新打开 WakeUp…"))
            content.addView(style.row("手动重启", "打开应用详情，强行停止 WakeUp 后重新打开", onClick = manualRestart).view)
        } else if (snapshot != null && !busy && failure == null) {
            content.addView(style.description("扫描结果已缓存。"))
        }
        action.text = when {
            busy -> progress.stage
            restarting -> "重试重启"
            failure != null -> "重试"
            snapshot != null -> "确定并重启"
            else -> "重试扫描"
        }
        action.isEnabled = !busy
        action.alpha = if (busy) 0.5f else 1f
    }
}
