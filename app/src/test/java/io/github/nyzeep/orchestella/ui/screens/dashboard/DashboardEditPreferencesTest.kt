package io.github.nyzeep.orchestella.ui.screens.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory stand-in for SharedPreferences; no Android runtime needed. */
class FakeDashboardEditStore : DashboardEditStore {
    val strings = mutableMapOf<String, String>()

    override fun getString(key: String): String? = strings[key]

    override fun putString(key: String, value: String) {
        strings[key] = value
    }
}

/**
 * UI-09 红绿切片 2：卡片布局持久化的纯 JVM 契约。
 *
 * 生产存储是应用的单一设置文件（`orchestella_settings`，与主题/设置共用）；
 * 这里用内存 store 验证编码、回读与容错——特别是「杀进程重进」等价物：
 * 同一个 store 上新建第二个状态实例必须读回第一个实例写入的顺序与隐藏集合。
 */
class DashboardEditPreferencesTest {

    @Test
    fun writeThroughSurvivesANewInstanceLikeAProcessRestart() {
        val store = FakeDashboardEditStore()
        val first = DashboardEditState(store).apply { setEditing(true) }
        first.hide(DashboardCardId.Metrics)
        first.moveVisible(0, 1) // Storage 挪到首位（Primary 之外可见槽 0 → 1）

        // 「杀进程重进」：同一存储上重新装配状态对象。
        val second = DashboardEditState(store)

        assertEquals(first.order.toList(), second.order.toList())
        assertEquals(first.hidden.toSet(), second.hidden.toSet())
        assertEquals(first.visible, second.visible)
        assertFalse(second.isEditing)
    }

    @Test
    fun missingKeysFallBackToTheShippedOrder() {
        val edit = DashboardEditState(FakeDashboardEditStore())

        assertEquals(DashboardCardId.entries.toList(), edit.order)
        assertTrue(edit.hidden.isEmpty())
    }

    @Test
    fun everyMutationIsWrittenThroughImmediately() {
        val store = FakeDashboardEditStore()
        val edit = DashboardEditState(store).apply { setEditing(true) }

        edit.hide(DashboardCardId.Recent)

        assertEquals(
            DashboardEditSpec.encodeHidden(listOf(DashboardCardId.Recent)),
            store.strings[DashboardEditSpec.KEY_HIDDEN],
        )
        edit.moveVisible(0, 1)

        assertEquals(
            DashboardEditSpec.encodeOrder(edit.order),
            store.strings[DashboardEditSpec.KEY_ORDER],
        )
    }

    @Test
    fun unknownOrderTokensAreDroppedAndMissingCardsAppended() {
        val store = FakeDashboardEditStore()
        store.strings[DashboardEditSpec.KEY_ORDER] = "PRIMARY,BOGUS,RECENT"

        val edit = DashboardEditState(store)

        // 未知 token 丢弃；缺失的卡按目录默认顺序补回，保证每张卡都在。
        assertEquals(
            listOf(DashboardCardId.Primary, DashboardCardId.Recent, DashboardCardId.Storage, DashboardCardId.Metrics),
            edit.order,
        )
    }

    @Test
    fun duplicateTokensAreDeduplicated() {
        val store = FakeDashboardEditStore()
        store.strings[DashboardEditSpec.KEY_ORDER] = "primary,PRIMARY,STORAGE"

        val edit = DashboardEditState(store)

        assertEquals(DashboardCardId.entries.size, edit.order.toSet().size)
        assertEquals(listOf(DashboardCardId.Primary, DashboardCardId.Storage), edit.order.take(2))
    }

    @Test
    fun blankOrGarbledOrderFallsBackToDefault() {
        val store = FakeDashboardEditStore()
        store.strings[DashboardEditSpec.KEY_ORDER] = " , ,"

        val edit = DashboardEditState(store)

        assertEquals(DashboardCardId.entries.toList(), edit.order)
    }

    @Test
    fun hiddenSetOnlyContainsKnownCards() {
        val store = FakeDashboardEditStore()
        store.strings[DashboardEditSpec.KEY_HIDDEN] = "METRICS,BOGUS"

        val edit = DashboardEditState(store)

        assertEquals(setOf(DashboardCardId.Metrics), edit.hidden.toSet())
    }

    @Test
    fun encodingIsStableAndRoundTripsThroughParse() {
        val order = listOf(DashboardCardId.Recent, DashboardCardId.Primary)
        val hidden = listOf(DashboardCardId.Storage, DashboardCardId.Metrics)

        assertEquals(
            order,
            DashboardEditSpec.parseOrder(DashboardEditSpec.encodeOrder(order)),
        )
        assertEquals(
            hidden.toSet(),
            DashboardEditSpec.parseHidden(DashboardEditSpec.encodeHidden(hidden)),
        )
    }

    @Test
    fun nullAndEmptyRawValuesParseToEmptyCollections() {
        assertTrue(DashboardEditSpec.parseOrder(null).isEmpty())
        assertTrue(DashboardEditSpec.parseOrder("").isEmpty())
        assertTrue(DashboardEditSpec.parseHidden(null).isEmpty())
        assertTrue(DashboardEditSpec.parseHidden("").isEmpty())
    }
}
