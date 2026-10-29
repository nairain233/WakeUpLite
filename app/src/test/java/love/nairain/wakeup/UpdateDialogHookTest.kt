package love.nairain.wakeup

import io.github.libxposed.api.XposedInterface
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class UpdateDialogHookTest {
    class Update(@JvmField var apkUrl: String?, val updateType: Int, val forceUp: Int)

    class Response {
        var completed = 0
        var dialogs = 0
        var loading = true

        fun accept(update: Update?) {
            if (update != null) {
                loading = false
                if (!update.apkUrl.isNullOrEmpty()) dialogs++
            }
            completed++
        }
    }

    private class Framework {
        var blocked = true
        var installed: XposedInterface.Hooker? = null
        val response = Response()
        val method = Response::class.java.getDeclaredMethod("accept", Update::class.java)
        val module = proxy<XposedInterface> { name, _ ->
            when (name) {
                "hook" -> proxy<XposedInterface.HookBuilder> { operation, args ->
                    check(operation == "intercept")
                    installed = args[0] as XposedInterface.Hooker
                    proxy<XposedInterface.HookHandle> { _, _ -> null }
                }
                "log" -> null
                else -> error("未预期的框架调用：$name")
            }
        }

        init {
            val targets = HookTargets(module, StartupStatus(module))
            targets.put(HookPoint.UPDATE_DIALOG, method)
            UpdateDialogHook(module) { blocked }.install(targets)
            checkNotNull(installed)
        }

        fun dispatch(update: Update?, fail: Boolean = false) {
            val chain = proxy<XposedInterface.Chain> { name, _ ->
                when (name) {
                    "getArg" -> update
                    "proceed" -> {
                        if (fail) throw IllegalStateException("模拟宿主处理失败")
                        response.accept(update)
                        null
                    }
                    else -> error("未预期的调用链操作：$name")
                }
            }
            checkNotNull(installed).intercept(chain)
        }
    }

    @Test fun blocksEveryUpdateTypeAndForceLevelWhileCompletingHostFlow() {
        val framework = Framework()
        listOf(0, 1, 2, 3).forEach { type ->
            listOf(0, 1).forEach { forced ->
                val update = Update("https://example.com/app.apk", type, forced)
                framework.dispatch(update)
                assertEquals("https://example.com/app.apk", update.apkUrl)
            }
        }
        assertEquals(0, framework.response.dialogs)
        assertEquals(8, framework.response.completed)
        assertFalse(framework.response.loading)
    }

    @Test fun readsSwitchForEachResponseAndRestoresOriginalBehaviorWhenDisabled() {
        val framework = Framework()
        val update = Update("https://example.com/app.apk", 2, 0)
        framework.dispatch(update)
        framework.blocked = false
        framework.dispatch(update)
        framework.blocked = true
        framework.dispatch(update)
        assertEquals(1, framework.response.dialogs)
        assertEquals(3, framework.response.completed)
    }

    @Test fun preservesNullAndEmptyResponses() {
        val framework = Framework()
        framework.dispatch(null)
        val update = Update(null, 0, 0)
        framework.dispatch(update)
        assertNull(update.apkUrl)
        update.apkUrl = ""
        framework.dispatch(update)
        assertEquals("", update.apkUrl)
        assertEquals(0, framework.response.dialogs)
        assertEquals(3, framework.response.completed)
    }

    @Test fun restoresResponseAndPropagatesHostFailure() {
        val framework = Framework()
        val update = Update("https://example.com/app.apk", 1, 0)
        assertThrows(IllegalStateException::class.java) { framework.dispatch(update, fail = true) }
        assertEquals("https://example.com/app.apk", update.apkUrl)
        assertEquals(0, framework.response.completed)
    }

    companion object {
        private inline fun <reified T> proxy(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                invoke(method.name, args.orEmpty())
            } as T
    }
}
