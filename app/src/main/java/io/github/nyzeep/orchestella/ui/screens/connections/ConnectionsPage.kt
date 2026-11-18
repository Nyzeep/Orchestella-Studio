package io.github.nyzeep.orchestella.ui.screens.connections

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
    const val VM_HARDWARE_KEY = "connections.vm_hardware"

    val allKeys: List<String> = listOf(SSH_KEY, PORT_FORWARDING_KEY, VM_HARDWARE_KEY)
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
    val description = stringResource(R.string.connections_back_desc)
    val color = Oe3ThemeValues.colors.onSurface.oe3Color()
    Box(
        modifier = modifier
            .size(Oe3Sizes.interactiveVisual)
            .testTag("connections:back")
            .clickable(role = Role.Button, onClick = onBack)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(Oe3Sizes.icon)) {
            val stroke = 1.75.dp.toPx()
            val nearX = size.width * 0.38f
            val farX = size.width * 0.62f
            drawLine(
                color = color,
                start = Offset(farX, size.height * 0.28f),
                end = Offset(nearX, size.height * 0.5f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = color,
                start = Offset(nearX, size.height * 0.5f),
                end = Offset(farX, size.height * 0.72f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
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
    OutlinedTextField(
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
    )
}
