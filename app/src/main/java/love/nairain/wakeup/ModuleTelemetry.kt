package love.nairain.wakeup

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object ModuleTelemetry {
    private const val ENDPOINT = "https://wakeup.nairain.love/api/report"
    private const val PREFERENCES = "love.nairain.wakeup.telemetry"
    private const val RETRY_DELAY_MS = 15 * 60 * 1000L
    private val zone = ZoneId.of("Asia/Shanghai")
    private val executor by lazy {
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "WakeUpLite-Telemetry").apply { isDaemon = true }
        }
    }
    @Volatile private var resumedActivities = 0
    private var nextAttemptAt = 0L

    fun install(module: XposedInterface) {
        val onCreate = Instrumentation::class.java.getDeclaredMethod(
            "callApplicationOnCreate", Application::class.java
        )
        module.hook(onCreate).intercept { chain ->
            val result = chain.proceed()
            val application = chain.getArg(0) as Application
            if (application.packageName == TargetApp.PACKAGE_NAME) {
                try {
                    observe(application)
                } catch (error: Exception) {
                    module.log(Log.WARN, ModuleMain.TAG, "Failed to observe module activity", error)
                }
            }
            result
        }
    }

    private fun observe(application: Application) {
        // 只在主进程安装；以前台 Activity 判定活跃，后台任务不计入日活。
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumedActivities++
                requestReport(application)
            }

            override fun onActivityPaused(activity: Activity) {
                resumedActivities = (resumedActivities - 1).coerceAtLeast(0)
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        // 处理宿主一直停留前台跨过午夜的情况，不唤醒设备、不启动独立服务。
        executor.scheduleWithFixedDelay({
            if (resumedActivities > 0) reportSafely(application)
        }, 15, 15, TimeUnit.MINUTES)
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    // 保留旧版已保存的统计选择，移除设置入口不会重新开启已关闭的统计。
    private fun isEnabled(context: Context): Boolean = preferences(context).getBoolean("enabled", true)

    private fun requestReport(context: Context) {
        executor.execute { reportSafely(context) }
    }

    private fun reportSafely(context: Context) {
        // 所有读取、请求和重试均在同一后台线程执行，失败不会影响宿主。
        try {
            if (!isEnabled(context) || SystemClock.elapsedRealtime() < nextAttemptAt) return
            val preferences = preferences(context)
            @Suppress("DEPRECATION")
            val host = context.packageManager.getPackageInfo(context.packageName, 0)
            val hostCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                host.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                host.versionCode.toLong()
            }
            val hostVersion = host.versionName ?: return
            val versionKey = "${BuildConfig.VERSION_NAME}:${BuildConfig.VERSION_CODE}:$hostVersion:$hostCode"
            val day = LocalDate.now(zone).toString()
            if (preferences.getString("last_success", null) == "$day:$versionKey") return

            val installationId = preferences.getString("installation_id", null)
                ?: UUID.randomUUID().toString().also {
                    // 先持久化随机标识；进程重启或请求重试仍使用同一值。
                    if (!preferences.edit().putString("installation_id", it).commit()) return
                }
            nextAttemptAt = SystemClock.elapsedRealtime() + RETRY_DELAY_MS
            val payload = JSONObject().apply {
                put("installationId", installationId)
                put("moduleVersion", BuildConfig.VERSION_NAME)
                put("moduleVersionCode", BuildConfig.VERSION_CODE)
                put("hostVersion", hostVersion)
                put("hostVersionCode", hostCode)
            }.toString().toByteArray(Charsets.UTF_8)
            if (!isEnabled(context)) return
            val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.instanceFollowRedirects = false
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json")
                connection.setFixedLengthStreamingMode(payload.size)
                connection.outputStream.use { it.write(payload) }
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return
                val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                    JSONObject(it.readText())
                }
                val serverDay = response.optString("day")
                if (response.optBoolean("ok") && serverDay.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                    preferences.edit().putString("last_success", "$serverDay:$versionKey").apply()
                }
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            // 网络不可用时等待下一次前台检查；不记录安装标识或请求内容。
            nextAttemptAt = SystemClock.elapsedRealtime() + RETRY_DELAY_MS
        }
    }
}
