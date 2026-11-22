package io.github.nyzeep.orchestella.ui.oe3.glass

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Oe3RuntimeShaderPool 契约（W2-05 净室增量 A）。
 *
 * 测试路径选型：Robolectric 4.18 自带 ShadowNativeRuntimeShader（NATIVE 图形
 * 模式走真实 Skia 编译；`@Config sdk 35` 与本套件其余玻璃探针一致）——用
 * **真实 RuntimeShader** 断言复用语义，无需给池开编译函数缝。已实测确认：
 * 合法 AGSL 正常编译；非法 AGSL 抛 IllegalArgumentException（RuntimeException
 * 子类），异常回落契约因此可用真编译验证。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class Oe3RuntimeShaderPoolTest {

    /** 合法最小 AGSL：编译成本真实存在（真实 Skia SkSL 编译）。 */
    private val validSource = "uniform float2 extent; half4 main(float2 p) { return half4(1.0); }"

    /** 与 validSource 不同的另一个合法源（验证同 key 换源重编译）。 */
    private val otherValidSource =
        "uniform float2 extent; half4 main(float2 p) { return half4(0.0, 1.0, 0.0, 1.0); }"

    @Test
    fun sameKeySameSourceReturnsTheSameInstance() {
        // 命中且 source 逐字一致 → 复用同一实例（assertSame）。
        val first = Oe3RuntimeShaderPool.obtain("pool-test.same-key", validSource)
        val second = Oe3RuntimeShaderPool.obtain("pool-test.same-key", validSource)
        assertNotNull(first)
        assertSame(first, second)
    }

    @Test
    fun sameKeyDifferentSourceRecompiles() {
        // key 命中但 source 不同 → 重编译并覆盖缓存槽，返回不同实例。
        val first = Oe3RuntimeShaderPool.obtain("pool-test.recompile", validSource)
        val second = Oe3RuntimeShaderPool.obtain("pool-test.recompile", otherValidSource)
        assertNotNull(first)
        assertNotNull(second)
        assertNotSame(first, second)
    }

    @Test
    fun differentKeysWithSameSourceAreSeparateInstances() {
        // 不跨 key 共享：不同 key 同 source 各自独立编译。
        val first = Oe3RuntimeShaderPool.obtain("pool-test.key-a", validSource)
        val second = Oe3RuntimeShaderPool.obtain("pool-test.key-b", validSource)
        assertNotNull(first)
        assertNotNull(second)
        assertNotSame(first, second)
    }

    @Test
    fun invalidSourceReturnsNullWithoutPoisoningTheSlot() {
        // 契约（选定：内部吞掉返回 null）：AGSL 编译异常不向外抛，调用方拿到
        // null 按「折射不可用」回落 L1；失败不落缓存槽——同一 key 换上合法源
        // 立即可用，池不被毒化。
        val failed = Oe3RuntimeShaderPool.obtain(
            "pool-test.fallback",
            "this is not valid agsl !!! no main here",
        )
        assertNull(failed)
        val recovered = Oe3RuntimeShaderPool.obtain("pool-test.fallback", validSource)
        assertNotNull(recovered)
        assertSame(recovered, Oe3RuntimeShaderPool.obtain("pool-test.fallback", validSource))
    }
}
