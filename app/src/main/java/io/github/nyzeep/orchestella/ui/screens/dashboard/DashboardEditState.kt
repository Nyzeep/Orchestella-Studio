package io.github.nyzeep.orchestella.ui.screens.dashboard

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.annotation.StringRes
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.SettingsManager

/**
 * 工作台卡片身份（UI-09）。顺序、隐藏与重排都以这个身份为单位；
 * 展示层按身份渲染对应卡片，不携带任何数据，因此隐藏一张卡
 * 只是把它从显示列表里遮住，实例、存储与指标读数都原样保留。
 *
 * [labelRes] 是编辑态「恢复显示」行里的卡名，与卡内标题共用同一批文案
 * （Storage/Metrics/Recent 复用原分区标题字符串）。
 */
enum class DashboardCardId(@StringRes val labelRes: Int) {
    Primary(R.string.ui3_dashboard_card_primary),
    Storage(R.string.ui3_dashboard_section_storage),
    Metrics(R.string.ui3_dashboard_section_metrics),
    Recent(R.string.ui3_dashboard_section_recent),
}

/**
 * The tiny storage seam behind the dashboard edit state.
 *
 * Production uses [SharedPreferencesDashboardEditStore]; the JVM test uses an
 * in-memory map (see DashboardEditPreferencesTest), so persistence round-trips
 * are verified without Robolectric.
 */
interface DashboardEditStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

/** Reads/writes the app's single settings file, so every preference stays shared. */
class SharedPreferencesDashboardEditStore(context: Context) : DashboardEditStore {

    private val preferences =
        context.applicationContext.getSharedPreferences(SettingsManager.PREFS_NAME, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun putString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }
}

/**
 * 编码与解析的纯函数层：逗号分隔的身份名。解析时丢弃未知 token、去重；
 * [ensureCompleteOrder] 再保证顺序表恰好覆盖目录里的每张卡——新增卡种
 * 天然落在末尾，旧记录不需要迁移。token 匹配大小写不敏感，手工改动
 * 偏好文件也不会让整份布局失效。
 */
object DashboardEditSpec {
    const val KEY_ORDER = "ui3_dashboard_card_order"
    const val KEY_HIDDEN = "ui3_dashboard_hidden_cards"

    val DEFAULT_ORDER: List<DashboardCardId> = DashboardCardId.entries.toList()

    fun encodeOrder(order: Collection<DashboardCardId>): String = order.joinToString(",") { it.name }

    fun encodeHidden(hidden: Collection<DashboardCardId>): String = encodeOrder(hidden)

    /** Missing/blank input yields an empty list so callers can fall back to the default. */
    fun parseOrder(raw: String?): List<DashboardCardId> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(',')
            .mapNotNull { token -> token.trim().takeIf { it.isNotEmpty() } }
            .mapNotNull { token -> token.toCardIdOrNull() }
            .distinct()
    }

    /** 追加缺失的卡（按目录默认顺序）；空输入直接落回出厂顺序。 */
    fun ensureCompleteOrder(parsed: List<DashboardCardId>): List<DashboardCardId> {
        if (parsed.isEmpty()) return DEFAULT_ORDER
        return parsed + DEFAULT_ORDER.filterNot { it in parsed }
    }

    fun parseHidden(raw: String?): Set<DashboardCardId> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(',')
            .mapNotNull { token -> token.trim().takeIf { it.isNotEmpty() } }
            .mapNotNull { token -> token.toCardIdOrNull() }
            .toSet()
    }

    private fun String.toCardIdOrNull(): DashboardCardId? =
        DashboardCardId.entries.firstOrNull { it.name.equals(this, ignoreCase = true) }
}

/**
 * 工作台编辑态（UI-09）：卡片顺序 + 隐藏集合 + 编辑开关。
 *
 * - `order` 永远持有全部 [DashboardCardId]；`hidden` 只做遮罩，恢复回到原槽位
 *   ——「隐藏不是删除」由此成为结构保证，不依赖调用方自觉。
 * - 每次可见的顺序/隐藏变更都立即写穿到 [DashboardEditStore]（生产是
 *   `orchestella_settings`），进程被杀后重进仍然保留；编辑开关是会话态，
 *   不落盘，页面永远以浏览态开场。
 * - 顺序与隐藏的变更只在编辑态生效：浏览态下这些方法是无操作，
 *   误触不可能改变布局。
 *
 * [store] 为 null 时纯内存运行（预览与模型测试）。
 */
class DashboardEditState(store: DashboardEditStore? = null) {

    private var editingFlag by mutableStateOf(false)

    /** 编辑开关是会话态：只在内存，页面永远以浏览态开场。 */
    val isEditing: Boolean get() = editingFlag

    private val persistedOrder = mutableStateListOf<DashboardCardId>()
    private val persistedHidden = mutableStateListOf<DashboardCardId>()

    /** 全部卡片的显示顺序（含被隐藏的卡）。 */
    val order: List<DashboardCardId> get() = persistedOrder

    /** 被隐藏（遮住）的卡；不保序，语义只是集合。 */
    val hidden: List<DashboardCardId> get() = persistedHidden

    /** 当前实际渲染的卡片顺序。 */
    val visible: List<DashboardCardId> get() = persistedOrder.filterNot { it in persistedHidden }

    init {
        if (store == null) {
            persistedOrder.addAll(DashboardEditSpec.DEFAULT_ORDER)
        } else {
            persistedOrder.addAll(
                DashboardEditSpec.ensureCompleteOrder(
                    DashboardEditSpec.parseOrder(store.getString(DashboardEditSpec.KEY_ORDER)),
                ),
            )
            persistedHidden.addAll(DashboardEditSpec.parseHidden(store.getString(DashboardEditSpec.KEY_HIDDEN)))
        }
    }

    /** 进入/退出编辑态；编辑开关不落盘。 */
    fun setEditing(value: Boolean) {
        editingFlag = value
    }

    /**
     * 拖拽重排：把可见槽位 [fromVisibleIndex] 的卡挪到 [toVisibleIndex]。
     * 只数可见槽位；被隐藏的卡不参与。非编辑态或越界都是无操作。
     */
    fun moveVisible(fromVisibleIndex: Int, toVisibleIndex: Int) {
        if (!isEditing) return
        val vis = visible
        if (fromVisibleIndex !in vis.indices || toVisibleIndex !in vis.indices) return
        if (fromVisibleIndex == toVisibleIndex) return
        val movedId = vis[fromVisibleIndex]
        val targetId = vis[toVisibleIndex]
        val orderFrom = persistedOrder.indexOf(movedId)
        val orderTo = persistedOrder.indexOf(targetId)
        persistedOrder.removeAt(orderFrom)
        persistedOrder.add(orderTo, movedId)
        persistOrder()
    }

    /** 隐藏一张卡：从显示列表移除；卡与它的顺序槽都保留。 */
    fun hide(cardId: DashboardCardId) {
        if (!isEditing) return
        if (cardId in persistedHidden) return
        persistedHidden.add(cardId)
        persistHidden()
    }

    /** 恢复一张被隐藏的卡：回到它原本的顺序槽位。 */
    fun restore(cardId: DashboardCardId) {
        if (!isEditing) return
        if (cardId !in persistedHidden) return
        persistedHidden.remove(cardId)
        persistHidden()
    }

    private fun persistOrder() {
        orderStore?.putString(DashboardEditSpec.KEY_ORDER, DashboardEditSpec.encodeOrder(persistedOrder))
    }

    private fun persistHidden() {
        orderStore?.putString(DashboardEditSpec.KEY_HIDDEN, DashboardEditSpec.encodeHidden(persistedHidden))
    }

    private val orderStore: DashboardEditStore? = store
}
