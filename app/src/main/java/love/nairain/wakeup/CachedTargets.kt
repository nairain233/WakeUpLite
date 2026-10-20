package love.nairain.wakeup

import io.github.libxposed.api.XposedInterface
import org.luckypray.dexkit.wrap.DexMethod
import org.luckypray.dexkit.util.InstanceUtil
import java.lang.reflect.Executable
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal object CachedTargets {
    fun restore(
        snapshot: ScanSnapshot,
        classLoader: ClassLoader,
        module: XposedInterface,
        status: StartupStatus
    ): Pair<HookTargets, List<Executable>> {
        snapshot.validate()
        val targets = HookTargets(module, status)
        snapshot.results.forEach { result ->
            result.method?.let { reference ->
                val method = reflect(reference, classLoader) as Method
                check(!Modifier.isAbstract(method.modifiers) && !Modifier.isNative(method.modifiers))
                targets.put(result.point, method)
            }
        }
        val callers = snapshot.callers.map { reflect(it, classLoader) }
        return targets to callers
    }

    fun reflect(reference: MethodReference, classLoader: ClassLoader): Executable {
        val dex = DexMethod(reference.descriptor)
        val owner = classLoader.loadClass(dex.className)
        val parameters = dex.paramTypeNames.map { InstanceUtil.getClassInstance(classLoader, it) }.toTypedArray()
        // 只用反射恢复缓存，避免 DexKit 的原生反射兜底在缓存命中时加载 so。
        val member = if (dex.isConstructor) owner.getDeclaredConstructor(*parameters) else {
            check(dex.isMethod) { "不支持类初始化器：$dex" }
            val direct = owner.getDeclaredMethod(dex.name, *parameters)
            if (DexMethod(direct).toString() == reference.descriptor) direct else owner.declaredMethods.single {
                DexMethod(it).toString() == reference.descriptor
            }
        }
        val actual = if (member is Method) DexMethod(member) else DexMethod(member as Constructor<*>)
        check(actual.toString() == reference.descriptor) { "方法签名不符：$dex" }
        check(Modifier.isStatic(member.modifiers) == reference.isStatic) { "静态标记不符：$dex" }
        return member.apply { isAccessible = true }
    }
}
