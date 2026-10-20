package love.nairain.wakeup

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

internal data class MethodReference(val descriptor: String, val isStatic: Boolean)

internal data class PointResult(
    val point: HookPoint,
    val method: MethodReference? = null,
    val error: String? = null
)

internal data class ScanIssue(val path: String, val reason: String)

internal data class ScanProgress(
    val stage: String = "准备扫描",
    val results: List<PointResult> = emptyList(),
    val issues: List<ScanIssue> = emptyList()
)

internal enum class BootstrapPhase { RESULT, NOTICE, READY }
internal enum class StartupRoute { RESULT, RESTART, NOTICE, READY }

internal data class ScanSnapshot(
    val fingerprint: String,
    val generation: String,
    val phase: BootstrapPhase,
    val results: List<PointResult>,
    val callers: List<MethodReference>,
    val issues: List<ScanIssue>,
    val restartProcess: String = ""
) {
    val successful: Int get() = results.count { it.method != null }
    val normal: Boolean get() = successful == HookPoint.entries.size && issues.isEmpty()

    fun route(process: String): StartupRoute = when (phase) {
        BootstrapPhase.RESULT -> StartupRoute.RESULT
        BootstrapPhase.NOTICE -> if (restartProcess == process) StartupRoute.RESTART else StartupRoute.NOTICE
        BootstrapPhase.READY -> StartupRoute.READY
    }

    fun confirmRestart(process: String) = copy(phase = BootstrapPhase.NOTICE, restartProcess = process)
    fun acceptNotice(process: String): ScanSnapshot {
        check(route(process) == StartupRoute.NOTICE) { "必须在新的宿主进程中确认使用须知" }
        return copy(phase = BootstrapPhase.READY)
    }

    fun validate() {
        require(fingerprint.isNotEmpty() && generation.isNotEmpty())
        require(results.size == HookPoint.entries.size &&
            results.map { it.point }.toSet() == HookPoint.entries.toSet()) { "缓存定位点集合不完整" }
        require(results.all { (it.method != null) != !it.error.isNullOrBlank() }) { "缓存定位状态无效" }
        require(results.mapNotNull { it.method }.all { it.descriptor.isNotBlank() })
        require(phase == BootstrapPhase.RESULT || restartProcess.isNotEmpty())
    }
}

// 编码与 Android 文件存储分离，便于验证部分结果和异常输入。
internal object ScanSnapshotCodec {
    const val VERSION = 1
    private const val MAGIC = 0x574b4458
    private const val MAX_ITEMS = 4096

    fun write(snapshot: ScanSnapshot, stream: OutputStream) {
        snapshot.validate()
        DataOutputStream(stream).apply {
            writeInt(MAGIC)
            writeInt(VERSION)
            writeUTF(snapshot.fingerprint)
            writeUTF(snapshot.generation)
            writeUTF(snapshot.phase.name)
            writeUTF(snapshot.restartProcess)
            writeInt(snapshot.results.size)
            snapshot.results.forEach { result ->
                writeUTF(result.point.name)
                writeBoolean(result.method != null)
                if (result.method != null) writeMethod(result.method) else writeUTF(result.error.orEmpty())
            }
            writeInt(snapshot.callers.size)
            snapshot.callers.forEach { writeMethod(it) }
            writeInt(snapshot.issues.size)
            snapshot.issues.forEach { writeUTF(it.path); writeUTF(it.reason) }
            flush()
        }
    }

    fun read(stream: InputStream): ScanSnapshot = DataInputStream(stream).run {
        require(readInt() == MAGIC && readInt() == VERSION) { "缓存格式不匹配" }
        val fingerprint = readUTF()
        val generation = readUTF()
        val phase = BootstrapPhase.valueOf(readUTF())
        val restartProcess = readUTF()
        val results = List(readCount()) {
            val point = HookPoint.valueOf(readUTF())
            if (readBoolean()) PointResult(point, readMethod()) else PointResult(point, error = readUTF())
        }
        val callers = List(readCount()) { readMethod() }
        val issues = List(readCount()) { ScanIssue(readUTF(), readUTF()) }
        require(read() == -1) { "缓存包含额外数据" }
        ScanSnapshot(fingerprint, generation, phase, results, callers, issues, restartProcess).also { it.validate() }
    }

    private fun DataOutputStream.writeMethod(method: MethodReference) {
        writeUTF(method.descriptor)
        writeBoolean(method.isStatic)
    }

    private fun DataInputStream.readMethod() = MethodReference(readUTF(), readBoolean())
    private fun DataInputStream.readCount() = readInt().also {
        require(it in 0..MAX_ITEMS) { "缓存条目数超出限制" }
    }
}

internal val HookPoint.queryPath: String
    get() {
        val root = "Lcom/suda/yzune/wakeupschedule/"
        return when (this) {
            HookPoint.VIP -> "$root*;->?()Z [read=vipStatus:I,vipEndTime:J; static]"
            HookPoint.DARK_GET -> "${root}utils/ScheduleSpUtils;->?()Z [string=dark_model_]"
            HookPoint.DARK_SET -> "${root}utils/ScheduleSpUtils;->?(I)V [string=dark_model_]"
            HookPoint.SIMPLE_GET -> "${root}utils/ScheduleSpUtils;->?()Z [string=simple_model_]"
            HookPoint.SIMPLE_SET -> "${root}utils/ScheduleSpUtils;->?(Z)V [string=simple_model_]"
            HookPoint.COLD_AD -> "$root*;->?()Z [string=ADET_Norequest,coldSplash,admissionBlock,vipUser; static]"
            HookPoint.HOT_AD -> "$root*;->?()Z [string=ADET_Norequest,hotSplash,admissionBlock,vipUser; static]"
            HookPoint.BANNER_AD -> "$root*;->?()I [string=ADET_Norequest,scheduleStream,admissionBlock,vipUser; static]"
            HookPoint.MINE_CREATE -> "${root}aaa/fragment/MineFragment;->onCreateView(Landroid/view/LayoutInflater;Landroid/view/ViewGroup;Landroid/os/Bundle;)Landroid/view/View;"
            HookPoint.MINE_ROOT -> "${root}aaa/fragment/MineFragment;->?()Landroid/view/View; [string=mContainerView]"
            HookPoint.MINE_RENDER -> "${root}aaa/fragment/MineFragment;->?(MineFragment,?) [string=VIP会员,coverImageCos,yyyy/MM/dd; static]"
            HookPoint.TAB_LOAD -> "${root}widget/MainAiTitleTabView;->?(Ljava/util/List;)V [string=assistant,android.widget.RelativeLayout]"
            HookPoint.TAB_ID -> "L*;->toString()Ljava/lang/String; [string=WakeUpTabItem(tabId=] → ?(Ljava/lang/String;)I → ?()Ljava/lang/String;"
            HookPoint.TAB_GET -> "${root}schedule/ScheduleActivity;->?()Lcom/suda/yzune/wakeupschedule/widget/MainAiTitleTabView; [string=aiTitleTab]"
            HookPoint.TAB_VISIBILITY -> "${root}schedule/ScheduleActivity;->?(Z)V [string=schedule]"
            HookPoint.TAB_SELECT -> "${root}schedule/ScheduleActivity;->?(Ljava/lang/String;)V [string=tabId]"
            HookPoint.SHORTCUT_GRID -> "${root}schedule/*;-><init>(Landroid/content/Context;)V [string=ctx,getContext(...)] → ()Landroidx/constraintlayout/widget/ConstraintLayout;"
            HookPoint.MENU_CREATE -> "${root}schedule/ScheduleFragment;->onViewCreated(Landroid/view/View;Landroid/os/Bundle;)V"
        }
    }
