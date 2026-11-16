package io.github.nyzeep.orchestella.ui.screens.distros

import androidx.annotation.StringRes
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.UmlRootfsCardState
import io.github.nyzeep.orchestella.runtime.distro.DistroCatalogRegistry
import io.github.nyzeep.orchestella.runtime.distro.DistroManifest
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3DistroIcons
import java.util.Locale

/**
 * 发行版页的纯数据状态。
 *
 * 页面不读 Android Context，也不发起下载/安装：[from] 只把
 * [DistroCatalogRegistry] 的清单折算成渲染输入，CTA 由 [DistrosActions]
 * 回调给接线方（UI3-12）。目录顺序就是网格顺序，不做二次排序。
 */
data class DistrosUiState(
    val distros: List<DistroCardUi>,
    val uml: UmlRootfsUi? = null,
) {
    val isEmpty: Boolean get() = distros.isEmpty()

    companion object {
        /**
         * @param catalog 官方 rootfs 目录；默认取进程内单例。
         * @param umlModel UML rootfs 卡的状态机输出；null 表示本次不展示该组。
         * @param compileBridgeWired READY 文案的 flag 事实，由接线方传入
         *   `BuildConfig.UML_COMPILE_BRIDGE`，页面不自行读取构建常量。
         */
        fun from(
            catalog: DistroCatalogRegistry = DistroCatalogRegistry.getInstance(),
            umlModel: UmlRootfsCardState.CardModel? = null,
            compileBridgeWired: Boolean = false,
        ): DistrosUiState = DistrosUiState(
            distros = catalog.listAll().map(DistroCardUi::from),
            uml = umlModel?.let { UmlRootfsUi.from(it, compileBridgeWired) },
        )
    }
}

/**
 * 一张折叠/展开的发行版卡。
 *
 * [name] 优先用 UI3-03 的品牌表，未知 id 回退到首字母大写，保证目录新增
 * 发行版时仍有可读名称；[capability] 与 [descriptionRes] 是页面文案，
 * 未知 id 为 null（不猜能力、不编描述），图标由 [Oe3DistroIcons.of] 回退。
 */
data class DistroCardUi(
    val id: String,
    val name: String,
    val version: String,
    val architecture: String,
    val sizeBytes: Long,
    val sha256: String,
    val capability: DistroCapability?,
    @StringRes val descriptionRes: Int?,
) {
    /** SHA 摘要只展示前 8 位；完整值留在 [sha256] 供接线方校验。 */
    val shaSummary: String get() = sha256.take(8)

    companion object {
        fun from(manifest: DistroManifest): DistroCardUi {
            val id = manifest.id()
            val copy = DistrosCopy.of(id)
            return DistroCardUi(
                id = id,
                name = Oe3DistroIcons.brandOf(id)?.displayName
                    ?: id.replaceFirstChar { it.uppercase() },
                version = manifest.version(),
                architecture = manifest.architecture(),
                sizeBytes = manifest.sizeBytes(),
                sha256 = manifest.sha256(),
                capability = copy?.capability,
                descriptionRes = copy?.descriptionRes,
            )
        }
    }
}

/**
 * 折叠卡上的唯一能力 chip。词汇比票面示例多两档，因为 Fedora / openSUSE /
 * AlmaLinux / Rocky 既不是 LTS 也不是滚动发布，硬套会写出不实文案。
 */
enum class DistroCapability(@StringRes val labelRes: Int) {
    Lts(R.string.ui3_distros_capability_lts),
    Rolling(R.string.ui3_distros_capability_rolling),
    Minimal(R.string.ui3_distros_capability_minimal),
    Current(R.string.ui3_distros_capability_current),
    Enterprise(R.string.ui3_distros_capability_enterprise),
}

/** 一个发行版的页面文案。未知 id 没有这一项，页面不猜能力也不编描述。 */
data class DistroCopy(
    @StringRes val descriptionRes: Int,
    val capability: DistroCapability,
)

/** 每个官方 id 的页面文案；目录新增未知 id 时返回 null，页面保持诚实留白。 */
internal object DistrosCopy {

    private val byId: Map<String, DistroCopy> = mapOf(
        "ubuntu" to DistroCopy(R.string.ui3_distros_description_ubuntu, DistroCapability.Lts),
        "debian" to DistroCopy(R.string.ui3_distros_description_debian, DistroCapability.Minimal),
        "fedora" to DistroCopy(R.string.ui3_distros_description_fedora, DistroCapability.Current),
        "arch" to DistroCopy(R.string.ui3_distros_description_arch, DistroCapability.Rolling),
        "opensuse" to DistroCopy(R.string.ui3_distros_description_opensuse, DistroCapability.Enterprise),
        "almalinux" to DistroCopy(R.string.ui3_distros_description_almalinux, DistroCapability.Enterprise),
        "rocky" to DistroCopy(R.string.ui3_distros_description_rocky, DistroCapability.Enterprise),
    )

    fun of(id: String): DistroCopy? = byId[id.trim().lowercase(Locale.ROOT)]
}

/**
 * UML rootfs 卡的单开组外状态。字段与 [UmlRootfsCardState.CardModel] 一一
 * 对应；[visible] 承载 DISABLED 的“零入口”，页面据此整组不渲染。
 */
data class UmlRootfsUi(
    val visible: Boolean,
    val phase: UmlRootfsPhase,
    /** 0..100；[PROGRESS_INDETERMINATE] 表示连接中。 */
    val progressPercent: Int,
    val failureReason: String?,
    val extractedEntries: Int,
    val alreadyVerified: Boolean,
    val compileBridgeWired: Boolean,
) {
    val isDownloading: Boolean get() = phase == UmlRootfsPhase.Downloading

    companion object {
        const val PROGRESS_INDETERMINATE = -1

        fun from(
            model: UmlRootfsCardState.CardModel,
            compileBridgeWired: Boolean,
        ): UmlRootfsUi = UmlRootfsUi(
            visible = model.phase() != UmlRootfsCardState.Phase.DISABLED,
            phase = UmlRootfsPhase.of(model.phase()),
            progressPercent = model.progressPercent(),
            failureReason = model.failureReason(),
            extractedEntries = model.extractedEntries(),
            alreadyVerified = model.alreadyVerified(),
            compileBridgeWired = compileBridgeWired,
        )
    }
}

enum class UmlRootfsPhase {
    Disabled,
    NotReady,
    Downloading,
    Ready,
    Failed;

    companion object {
        /**
         * Kotlin 2.2 对 Java 枚举做穷尽检查：新增
         * [UmlRootfsCardState.Phase] 常量会让这里编译失败，必须先补状态与文案。
         */
        fun of(phase: UmlRootfsCardState.Phase): UmlRootfsPhase = when (phase) {
            UmlRootfsCardState.Phase.DISABLED -> Disabled
            UmlRootfsCardState.Phase.NOT_READY -> NotReady
            UmlRootfsCardState.Phase.DOWNLOADING -> Downloading
            UmlRootfsCardState.Phase.READY -> Ready
            UmlRootfsCardState.Phase.FAILED -> Failed
        }
    }
}

/**
 * 单开手风琴。纯函数，页面只保存一个 id：打开新卡得到新 id（旧卡自然
 * 收起），点同一张卡得到 null（全部收起）。
 */
object DistrosAccordion {
    fun toggle(openId: String?, targetId: String): String? =
        if (openId == targetId) null else targetId
}
