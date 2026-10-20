package love.nairain.wakeup

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

internal class DialogStyle(private val activity: Activity) {
    private val dark = activity.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val context = ContextThemeWrapper(activity, if (dark) {
        android.R.style.Theme_DeviceDefault_Dialog_Alert
    } else {
        android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
    })
    private val surface = Color.parseColor(if (dark) "#191A1E" else "#FAFAFC")
    private val primary = Color.parseColor(if (dark) "#E7E7ED" else "#1F1F25")
    private val secondary = Color.parseColor(if (dark) "#B9BBC3" else "#62656F")
    private val accent = Color.parseColor(if (dark) "#A8C7FA" else "#3367D6")
    private val outline = Color.parseColor(if (dark) "#36383F" else "#E2E4EB")

    data class Row(val view: View, val summary: TextView)

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private fun text(value: String, size: Float, color: Int) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    fun section(title: String) = text(title, 16f, accent).apply {
        setPadding(dp(24), dp(20), dp(24), dp(6))
        if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }

    fun description(value: String) = text(value, 16f, secondary).apply {
        setPadding(dp(24), dp(8), dp(24), dp(12))
    }

    fun scanStatus(value: String, normal: Boolean) = description(value).apply {
        setTextColor(Color.parseColor(if (normal) {
            if (dark) "#81C995" else "#137333"
        } else {
            if (dark) "#F28B82" else "#B3261E"
        }))
        setTypeface(typeface, Typeface.BOLD)
    }

    fun scanPath(value: String) = description(value).apply {
        textSize = 12f
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
    }

    fun divider() = View(context).apply {
        setBackgroundColor(outline)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { setMargins(dp(24), dp(12), dp(24), dp(4)) }
    }

    fun row(
        title: String,
        subtitle: String = "",
        link: Boolean = false,
        indicator: View? = null,
        showArrow: Boolean = true,
        onClick: (() -> Unit)? = null
    ): Row {
        val summary = text(subtitle, 14f, if (link) accent else secondary).apply {
            visibility = if (subtitle.isEmpty()) View.GONE else View.VISIBLE
            setPadding(0, dp(4), 0, 0)
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(24), dp(12), dp(24), dp(12))
            addView(column().apply {
                addView(text(title, 18f, primary))
                addView(summary)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val endView = indicator ?: if (onClick != null && showArrow) chevron() else null
            if (endView != null) addView(endView, LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                marginStart = dp(16)
            })
            if (onClick != null) {
                background = ripple()
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }
        return Row(row, summary)
    }

    fun switchRow(title: String, checked: Boolean, onChanged: (Boolean) -> Unit) = Switch(context).apply {
        text = title
        textSize = 16f
        setTextColor(primary)
        minHeight = dp(64)
        setPadding(dp(24), dp(12), dp(24), dp(12))
        switchPadding = dp(16)
        background = ripple()
        thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(accent, secondary)
        )
        trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(withAlpha(accent, 90), withAlpha(secondary, 65))
        )
        // 原生 Switch 覆盖整行，文字区域与右侧开关共用一次状态变更。
        isChecked = checked
        setOnCheckedChangeListener { _, visible -> onChanged(visible) }
    }

    fun chevron(): View = object : View(context) {
        private val path = Path()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            strokeWidth = dp(2).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            style = Paint.Style.STROKE
        }

        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            super.onSizeChanged(width, height, oldWidth, oldHeight)
            path.reset()
            path.moveTo(width * 0.4f, height * 0.25f)
            path.lineTo(width * 0.65f, height * 0.5f)
            path.lineTo(width * 0.4f, height * 0.75f)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawPath(path, paint)
        }
    }.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }

    // 保留原有方法签名，设置弹窗与带倒计时操作的弹窗共用同一实现。
    fun show(title: String, subtitle: String, content: View, closeLabel: String): AlertDialog =
        show(title, subtitle, content, closeLabel) {}

    fun show(
        title: String,
        subtitle: String,
        content: View,
        closeLabel: String,
        configureAction: (TextView) -> Unit
    ): AlertDialog {
        val root = object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val limit = (availableSize().second * 0.85f).toInt()
                val parentLimit = MeasureSpec.getSize(heightMeasureSpec)
                val height = if (parentLimit > 0) minOf(limit, parentLimit) else limit
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadius = dp(24).toFloat()
            }
            clipToOutline = true
            addView(column().apply {
                setPadding(dp(24), dp(24), dp(24), dp(12))
                addView(text(title, 26f, primary).apply {
                    setTypeface(typeface, Typeface.BOLD)
                    if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
                })
                addView(text(versionSummary(), 12f, secondary).apply { setPadding(0, dp(6), 0, 0) })
                addView(text(subtitle, 14f, secondary).apply { setPadding(0, dp(6), 0, 0) })
            })
            // 内容过高时只缩小滚动区，标题和底部操作始终可见。
            addView(ScrollView(context).apply {
                isFillViewport = false
                addView(content)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val dialog = AlertDialog.Builder(context).create().apply { setView(root, 0, 0, 0, 0) }
        root.addView(LinearLayout(context).apply {
            gravity = Gravity.END
            setPadding(dp(12), dp(4), dp(12), dp(12))
            addView(text(closeLabel, 16f, accent).apply {
                gravity = Gravity.CENTER
                minHeight = dp(56)
                minWidth = dp(80)
                setPadding(dp(12), 0, dp(12), 0)
                background = ripple()
                isFocusable = true
                setOnClickListener { dialog.dismiss() }
                configureAction(this)
            })
        })
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            decorView.setPadding(0, 0, 0, 0)
            setDimAmount(0.45f)
            setLayout(minOf(availableSize().first - dp(32), dp(480)), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return dialog
    }

    @Suppress("DEPRECATION")
    private fun versionSummary(): String {
        val hostVersion = try {
            val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                info.versionCode.toLong()
            }
            "${info.versionName ?: "未知"} ($code)"
        } catch (_: PackageManager.NameNotFoundException) {
            "未知"
        }
        return "模块版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n应用版本：$hostVersion"
    }

    private fun ripple() = RippleDrawable(
        ColorStateList.valueOf(withAlpha(accent, 35)), null, ColorDrawable(Color.WHITE)
    )

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    private fun availableSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = activity.windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            return (metrics.bounds.width() - insets.left - insets.right) to
                (metrics.bounds.height() - insets.top - insets.bottom)
        }
        val metrics = context.resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }
}
