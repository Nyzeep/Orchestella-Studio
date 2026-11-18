package io.github.nyzeep.orchestella.ui.screens.connections

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.ui.design.tokens.OeShape
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3BackButton
import io.github.nyzeep.orchestella.ui.oe3.components.oe3Color
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes

/**
 * Connection-page identities used by both hosts (shell stack and
 * [io.github.nyzeep.orchestella.ConnectionPagesActivity]).
 *
 * The keys are the stack identity for [io.github.nyzeep.orchestella.ui.shell.OeBackStack];
 * tests pin uniqueness so a future route cannot silently reuse another page's key.
 */
object ConnectionsPage {

    const val SSH_KEY = "connections.ssh"
    const val PORT_FORWARDING_KEY = "connections.port_forwarding"

    /**
     * VM 硬件没有壳内路由（只从实例详情页经 [io.github.nyzeep.orchestella.ConnectionPagesActivity]
     * 进入），所以它不在栈键清单里；强行占位一个没人消费的 key 只会让
     * 「唯一性」测试锁住一个假路由（code-review 2026-11-18 删除）。
     */
    val allKeys: List<String> = listOf(SSH_KEY, PORT_FORWARDING_KEY)
}

/**
 * The one back affordance shared by the three connection pages.
 *
 * The page receives `onBack` from its host and passes that same lambda here:
 * the shell host wires it to `OeBackStack.pop`, the Activity host to
 * `finish()`, so the visible button and the system back key stay one behaviour.
 */
@Composable
fun ConnectionsBackButton(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Oe3BackButton(
        onBack = onBack,
        contentDescription = stringResource(R.string.connections_back_desc),
        modifier = modifier,
        testTag = "connections:back",
    )
}

/**
 * Connection-page text field: `surfaceHigh` fill, no stroke, 16dp radius
 * (`OeShape.md`, the design-system input step; see foundations/shape.md §2).
 *
 * Kept local instead of promoting a new shared primitive: UI-04 owns the
 * component library and this ticket only needs one consistent field.
 */
@Composable
fun ConnectionsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    val colors = Oe3ThemeValues.colors
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .then(testTag?.let { Modifier.testTag(it) } ?: Modifier),
        singleLine = true,
        label = { Text(label) },
        shape = RoundedCornerShape(OeShape.md),
        isError = isError,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        // shape.md §2.1：输入框是 surfaceHigh 底、无描边。Material3 的
        // Outlined 变体默认画一圈 outline，与规范冲突，所以这里把四条指示线
        // 全部置透明，只留填充与标签。
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colors.surfaceHigh.oe3Color(),
            unfocusedContainerColor = colors.surfaceHigh.oe3Color(),
            disabledContainerColor = colors.surfaceHigh.oe3Color(),
            errorContainerColor = colors.surfaceHigh.oe3Color(),
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
        ),
    )
}
