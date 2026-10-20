package love.nairain.wakeup

import android.content.pm.ApplicationInfo
import android.util.Log
import io.github.libxposed.api.XposedInterface
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier
import java.util.UUID

internal class DexKitResolver(
    private val module: XposedInterface,
    private val status: StartupStatus,
    private val classLoader: ClassLoader
) {
    private val data = mutableMapOf<HookPoint, MethodData>()
    private val results = linkedMapOf<HookPoint, PointResult>()
    private val callers = linkedSetOf<MethodReference>()
    private val issues = mutableListOf<ScanIssue>()
    private var progress: (ScanProgress) -> Unit = {}

    fun resolve(info: ApplicationInfo, fingerprint: String, onProgress: (ScanProgress) -> Unit): ScanSnapshot {
        progress = onProgress
        val started = android.os.SystemClock.elapsedRealtime()
        System.loadLibrary("dexkit")
        val paths = listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()
        val bridges = mutableListOf<DexKitBridge>()
        try {
            paths.forEach { path ->
                try {
                    bridges += DexKitBridge.create(path)
                } catch (error: Throwable) {
                    issues += ScanIssue(path, error.message ?: error.javaClass.simpleName)
                    status.failed(path, error)
                }
            }
            check(bridges.isNotEmpty()) { "没有可读取的宿主 DEX：${info.sourceDir}" }
            scan(bridges)
            emit("分析调用方")
            collectCallers()
        } finally {
            bridges.forEach { bridge ->
                try { bridge.close() } catch (error: Throwable) { status.failed("DexKitBridge.close", error) }
            }
        }
        module.log(Log.INFO, ModuleMain.TAG,
            "DexKit scan took ${android.os.SystemClock.elapsedRealtime() - started} ms")
        return ScanSnapshot(fingerprint, UUID.randomUUID().toString(), BootstrapPhase.RESULT,
            results.values.toList(), callers.toList(), issues.toList())
    }

    private fun emit(stage: String) {
        progress(ScanProgress(stage, results.values.toList(), issues.toList()))
    }

    private fun List<MethodData>.unique(path: String): MethodData {
        val candidates = distinctBy { it.descriptor }
        check(candidates.size == 1) {
            if (candidates.isEmpty()) "未找到：$path" else
                "匹配多个（${candidates.size}）：${candidates.take(20).joinToString { it.descriptor }}"
        }
        return candidates.single()
    }

    private fun scan(bridges: List<DexKitBridge>) {
        fun find(init: MethodMatcher.() -> Unit): List<MethodData> = bridges.flatMap { bridge ->
            bridge.findMethod { matcher(init) }
        }
        fun host(init: MethodMatcher.() -> Unit): List<MethodData> = bridges.flatMap { bridge ->
            bridge.findMethod { searchPackages(TargetApp.PACKAGE_NAME); matcher(init) }
        }
        fun MethodMatcher.exactStrings(vararg values: String) {
            usingStrings(values.toList(), StringMatchType.Equals)
        }
        fun locate(point: HookPoint, query: () -> List<MethodData>) {
            try {
                val candidate = query().unique(point.queryPath)
                val reference = MethodReference(candidate.descriptor, Modifier.isStatic(candidate.modifiers))
                // 扫描与缓存恢复使用同一条反射路径，避免保存下次无法恢复的结果。
                val method = CachedTargets.reflect(reference, classLoader) as java.lang.reflect.Method
                check(!Modifier.isAbstract(method.modifiers) && !Modifier.isNative(method.modifiers)) {
                    "方法为抽象或原生实现，无法安装 Hook：${candidate.descriptor}"
                }
                results[point] = PointResult(point, reference)
                data[point] = candidate
                module.log(Log.INFO, ModuleMain.TAG, "DexKit ${point.label}: ${candidate.descriptor}")
            } catch (error: Throwable) {
                results[point] = PointResult(point, error = error.message ?: error.javaClass.simpleName)
                status.failed(point.queryPath, error)
            }
            emit("扫描路径")
        }

        locate(HookPoint.VIP) {
            host {
                returnType = "boolean"
                paramTypes()
                addUsingField { field { name = "vipStatus"; type = "int" } }
                addUsingField { field { name = "vipEndTime"; type = "long" } }
            }.filter { Modifier.isStatic(it.modifiers) }
        }
        listOf(
            Triple(HookPoint.DARK_GET, "dark_model_", "boolean"),
            Triple(HookPoint.DARK_SET, "dark_model_", "void"),
            Triple(HookPoint.SIMPLE_GET, "simple_model_", "boolean"),
            Triple(HookPoint.SIMPLE_SET, "simple_model_", "void")
        ).forEach { (point, key, result) ->
            locate(point) {
                host {
                    declaredClass("${TargetApp.PACKAGE_NAME}.utils.ScheduleSpUtils")
                    returnType = result
                    when (point) {
                        HookPoint.DARK_SET -> paramTypes("int")
                        HookPoint.SIMPLE_SET -> paramTypes("boolean")
                        else -> paramTypes()
                    }
                    exactStrings(key)
                }.filterNot { Modifier.isStatic(it.modifiers) }
            }
        }
        listOf(
            Triple(HookPoint.COLD_AD, "coldSplash", "boolean"),
            Triple(HookPoint.HOT_AD, "hotSplash", "boolean"),
            Triple(HookPoint.BANNER_AD, "scheduleStream", "int")
        ).forEach { (point, adType, result) ->
            locate(point) {
                host {
                    returnType = result
                    paramTypes()
                    exactStrings("ADET_Norequest", adType, "admissionBlock", "vipUser")
                }.filter { Modifier.isStatic(it.modifiers) }
            }
        }

        val mine = "${TargetApp.PACKAGE_NAME}.aaa.fragment.MineFragment"
        locate(HookPoint.MINE_CREATE) {
            host {
                declaredClass(mine)
                name = "onCreateView"
                returnType = "android.view.View"
                paramTypes("android.view.LayoutInflater", "android.view.ViewGroup", "android.os.Bundle")
            }
        }
        locate(HookPoint.MINE_ROOT) {
            host {
                declaredClass(mine)
                returnType = "android.view.View"
                paramTypes()
                exactStrings("mContainerView")
            }
        }
        locate(HookPoint.MINE_RENDER) {
            host {
                declaredClass(mine)
                paramTypes(mine, null)
                exactStrings("VIP会员", "coverImageCos", "yyyy/MM/dd")
            }.filter { Modifier.isStatic(it.modifiers) }
        }

        val activity = "${TargetApp.PACKAGE_NAME}.schedule.ScheduleActivity"
        val bar = "${TargetApp.PACKAGE_NAME}.widget.MainAiTitleTabView"
        locate(HookPoint.TAB_LOAD) {
            host {
                declaredClass(bar)
                paramTypes("java.util.List")
                returnType = "void"
                exactStrings("assistant", "null cannot be cast to non-null type android.widget.RelativeLayout")
            }
        }
        locate(HookPoint.TAB_GET) {
            host {
                declaredClass(activity)
                returnType = bar
                paramTypes()
                exactStrings("aiTitleTab")
            }
        }
        locate(HookPoint.TAB_SELECT) {
            host {
                declaredClass(activity)
                returnType = "void"
                paramTypes("java.lang.String")
                exactStrings("tabId")
            }
        }
        locate(HookPoint.TAB_VISIBILITY) {
            host {
                declaredClass(activity)
                returnType = "void"
                paramTypes("boolean")
                exactStrings("schedule")
            }
        }
        locate(HookPoint.TAB_ID) {
            // 通过选择页签时的索引查找调用链区分标识和标题两个同签名 getter。
            val select = checkNotNull(data[HookPoint.TAB_SELECT]) { "未找到依赖路径：${HookPoint.TAB_SELECT.queryPath}" }
            val index = select.invokes.filter {
                it.paramTypeNames == listOf("java.lang.String") && it.returnTypeName == "int"
            }.unique("${select.descriptor} → (Ljava/lang/String;)I")
            val tabType = find {
                name = "toString"
                paramTypes()
                returnType = "java.lang.String"
                exactStrings("WakeUpTabItem(tabId=")
            }.unique("toString()Ljava/lang/String; [string=WakeUpTabItem(tabId=]").className
            index.invokes.filter {
                it.className == tabType && it.paramCount == 0 && it.returnTypeName == "java.lang.String"
            }
        }

        locate(HookPoint.SHORTCUT_GRID) {
            // 快捷区容器的构造器有独立的上下文签名，其类内仅有一个约束布局 getter。
            val owner = host {
                declaredClass { className("${TargetApp.PACKAGE_NAME}.schedule.", StringMatchType.StartsWith) }
                name = "<init>"
                paramTypes("android.content.Context")
                exactStrings("ctx", "getContext(...)")
            }.unique("${TargetApp.PACKAGE_NAME}.schedule.* → <init>(Context) [string=ctx,getContext(...)]").className
            host {
                declaredClass(owner)
                paramTypes()
                returnType = "androidx.constraintlayout.widget.ConstraintLayout"
            }
        }
        locate(HookPoint.MENU_CREATE) {
            host {
                declaredClass("${TargetApp.PACKAGE_NAME}.schedule.ScheduleFragment")
                name = "onViewCreated"
                paramTypes("android.view.View", "android.os.Bundle")
                returnType = "void"
            }
        }
    }

    private fun collectCallers() {
        val accessors = setOf(HookPoint.MINE_ROOT, HookPoint.TAB_ID, HookPoint.TAB_GET)
        data.filterKeys { it !in accessors }.forEach { (point, method) ->
            try {
                method.callers.distinctBy { it.descriptor }.forEach callerLoop@ { caller ->
                    // 类初始化器没有可供框架去优化的反射对象。
                    if (caller.isStaticInitializer) return@callerLoop
                    try {
                        val reference = MethodReference(caller.descriptor, Modifier.isStatic(caller.modifiers))
                        CachedTargets.reflect(reference, classLoader)
                        callers += reference
                    } catch (error: Throwable) {
                        issues += ScanIssue(caller.descriptor, error.message ?: error.javaClass.simpleName)
                        status.failed(caller.descriptor, error)
                    }
                }
            } catch (error: Throwable) {
                issues += ScanIssue(point.queryPath, error.message ?: error.javaClass.simpleName)
                status.failed(point.queryPath, error)
            }
        }
    }
}
