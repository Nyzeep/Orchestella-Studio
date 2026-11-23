package io.github.nyzeep.orchestella.ui.screens.connections

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.nyzeep.orchestella.ui.oe3.tokens.LocalOe3PageBottomPadding
import io.github.nyzeep.orchestella.ui.oe3.tokens.LocalOe3PageTopPadding
import io.github.nyzeep.orchestella.PortForwardingPresenter
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.runtime.contract.Protocol
import io.github.nyzeep.orchestella.runtime.network.PortForwardingManager
import io.github.nyzeep.orchestella.ui.design.tokens.OeShape
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Button
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3ButtonStyle
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Card
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3SectionHeader
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Chip
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3EmptyState
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3SwitchRow
import io.github.nyzeep.orchestella.ui.oe3.components.oe3Color
import io.github.nyzeep.orchestella.ui.oe3.motion.Oe3DialogEnterMotion
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography
import io.github.nyzeep.orchestella.ui.shell.OeScaffold
import io.github.nyzeep.orchestella.ui.shell.oeScrollOffsetPx
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** One rendered forwarding rule; [description] already carries the live status suffix. */
@Immutable
data class PortForwardingRuleRow(
    val id: String,
    val protocol: String,
    val hostPort: Int,
    val guestPort: Int,
    val description: String,
    val enabled: Boolean,
    val canToggle: Boolean,
    val savedDescription: String = "",
    val canEdit: Boolean = false,
)

/** Which input the presenter's validation message belongs to. */
enum class PortForwardingErrorField { HOST, GUEST, GENERAL }

@Immutable
data class PortForwardingPageUiState(
    val rules: List<PortForwardingRuleRow> = emptyList(),
    val hostPort: String = "",
    val guestPort: String = "",
    val description: String = "",
    val editingRuleId: String? = null,
    val hostPortError: String? = null,
    val guestPortError: String? = null,
    val generalError: String? = null,
    val hasLiveEngine: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
)

/**
 * Pure predicates of the port-forwarding page. Keeping them out of the
 * composable makes the empty-state and error-routing contract assertable
 * without a device (the old sheet pinned the same empty-state invariant).
 */
object PortForwardingPageSpec {

    /** Empty-state well appears only when no rule is persisted. */
    fun emptyStateVisible(ruleCount: Int): Boolean = ruleCount <= 0

    /**
     * Route host- and guest-port validation errors to their corresponding
     * fields. General errors belong to the form.
     */
    fun errorField(message: String?): PortForwardingErrorField {
        val text = message.orEmpty()
        return when {
            text.contains("Host port", ignoreCase = true) ||
                text.contains("宿主端口") ||
                text.contains("1024", ignoreCase = true) -> PortForwardingErrorField.HOST
            text.contains("Guest port", ignoreCase = true) -> PortForwardingErrorField.GUEST
            else -> PortForwardingErrorField.GENERAL
        }
    }
}

/**
 * View adapter for [PortForwardingPresenter].
 *
 * The presenter runs on a single-thread executor; its View callbacks must
 * therefore hop back to the main looper before touching Compose state — the
 * same discipline the deleted BottomSheet implementation used.
 */
class PortForwardingPageState(
    hasLiveEngine: Boolean = false,
    private val main: Handler = Handler(Looper.getMainLooper()),
) : PortForwardingPresenter.View {

    var state by mutableStateOf(PortForwardingPageUiState(hasLiveEngine = hasLiveEngine))
        private set

    override fun showLiveEngine(available: Boolean) {
        onMain { state = state.copy(hasLiveEngine = available) }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            main.post(block)
        }
    }

    override fun displayRules(rules: List<PortForwardingPresenter.RuleItemModel>) {
        onMain {
            state = state.copy(
                rules = rules.map { model ->
                    PortForwardingRuleRow(
                        id = model.id,
                        protocol = model.protocol.name,
                        hostPort = model.hostPort,
                        guestPort = model.guestPort,
                        description = model.description,
                        enabled = model.isEnabled,
                        canToggle = model.canToggle(),
                        savedDescription = model.savedDescription,
                        canEdit = model.canEdit(),
                    )
                },
                hostPortError = null,
                guestPortError = null,
                generalError = null,
            )
        }
    }

    override fun showValidationError(message: String?) {
        onMain {
            val text = message ?: "输入错误"
            state = when (PortForwardingPageSpec.errorField(text)) {
                PortForwardingErrorField.HOST ->
                    state.copy(hostPortError = text, guestPortError = null, generalError = null)
                PortForwardingErrorField.GUEST ->
                    state.copy(guestPortError = text, hostPortError = null, generalError = null)
                PortForwardingErrorField.GENERAL ->
                    state.copy(generalError = text, hostPortError = null, guestPortError = null)
            }
        }
    }

    override fun clearInputForm() {
        onMain {
            state = state.copy(
                hostPort = "",
                guestPort = "",
                description = "",
                editingRuleId = null,
                hostPortError = null,
                guestPortError = null,
                generalError = null,
            )
        }
    }

    override fun showSuccess(message: String ?) {
        if (message.isNullOrEmpty()) return
        onMain { state = state.copy(message = message, messageIsError = false) }
    }

    override fun showError(message: String ?) {
        if (message.isNullOrEmpty()) return
        onMain { state = state.copy(message = message, messageIsError = true) }
    }

    fun consumeMessage() {
        if (state.message != null) state = state.copy(message = null)
    }

    fun onHostPortChange(value: String) {
        state = state.copy(hostPort = value, hostPortError = null, generalError = null)
    }

    fun onGuestPortChange(value: String) {
        state = state.copy(guestPort = value, guestPortError = null, generalError = null)
    }

    fun onDescriptionChange(value: String) {
        state = state.copy(description = value)
    }

    fun editRule(rule: PortForwardingRuleRow) {
        state = state.copy(
            editingRuleId = rule.id,
            hostPort = rule.hostPort.toString(),
            guestPort = rule.guestPort.toString(),
            description = rule.savedDescription,
            hostPortError = null,
            guestPortError = null,
            generalError = null,
        )
    }
}

/**
 * Port-forwarding secondary page.
 *
 * Presenter calls run on a background thread. The adapter posts changes to main.
 */
@Composable
fun PortForwardingPage(
    instanceId: String,
    manager: PortForwardingManager,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val adapter = remember(instanceId, manager) { PortForwardingPageState() }
    val presenter = remember(instanceId, manager) {
        PortForwardingPresenter.forActiveSession(instanceId, manager)
    }
    val executor: ExecutorService = remember(presenter, lifecycle) {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "port-forwarding-page").apply { isDaemon = true }
        }
    }

    DisposableEffect(presenter, lifecycle) {
        presenter.attachView(adapter)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) runOffMain(executor) { presenter.loadRules() }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            presenter.detachView()
            executor.shutdown()
        }
    }

    val state = adapter.state
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        Toast.makeText(
            context,
            message,
            if (state.messageIsError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
        ).show()
        adapter.consumeMessage()
    }

    PortForwardingContent(
        state = state,
        onBack = onBack,
        onHostPortChange = adapter::onHostPortChange,
        onGuestPortChange = adapter::onGuestPortChange,
        onDescriptionChange = adapter::onDescriptionChange,
        onEditRule = adapter::editRule,
        onCancelEdit = adapter::clearInputForm,
        onAddRule = {
            runOffMain(executor) {
                val editingRuleId = state.editingRuleId
                if (editingRuleId == null) {
                    presenter.addRule(state.hostPort, state.guestPort, Protocol.TCP, state.description)
                } else {
                    presenter.updateRule(editingRuleId, state.hostPort, state.guestPort, state.description)
                }
            }
        },
        onToggleRule = { id, enabled ->
            runOffMain(executor) { presenter.toggleRule(id, enabled) }
        },
        onDeleteRule = { id ->
            runOffMain(executor) { presenter.deleteRule(id) }
        },
        modifier = modifier,
    )
}

private fun runOffMain(executor: ExecutorService, task: () -> Unit) {
    if (executor.isShutdown) return
    try {
        executor.execute(task)
    } catch (_: RejectedExecutionException) {
        // The page is disposing and the presenter has already detached.
    }
}

/**
 * Stateless body used by the page and the screenshot baseline.
 *
 * Delete is a dangerous action: the row only raises a Compose `AlertDialog`
 * (32dp `OeShape.alert`) and the presenter call happens after confirmation, so
 * the destructive step never becomes a silent tap.
 */
@Composable
fun PortForwardingContent(
    state: PortForwardingPageUiState,
    onBack: () -> Unit,
    onHostPortChange: (String) -> Unit,
    onGuestPortChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onAddRule: () -> Unit,
    onToggleRule: (String, Boolean) -> Unit,
    onDeleteRule: (String) -> Unit,
    modifier: Modifier = Modifier,
    onEditRule: (PortForwardingRuleRow) -> Unit = {},
    onCancelEdit: () -> Unit = {},
) {
    val colors = Oe3ThemeValues.colors
    var pendingDelete by remember { mutableStateOf<PortForwardingRuleRow?>(null) }
    val scrollState = rememberScrollState()

    OeScaffold(
        title = stringResource(R.string.connections_port_forwarding_title),
        modifier = modifier,
        leading = { ConnectionsBackButton(onBack = onBack) },
        scrollOffsetPx = { scrollState.oeScrollOffsetPx() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(
                    start = Oe3Spacing.pageInset,
                    end = Oe3Spacing.pageInset,
                    top = LocalOe3PageTopPadding.current + Oe3Spacing.sm,
                    bottom = LocalOe3PageBottomPadding.current,
                ),
            verticalArrangement = Arrangement.spacedBy(Oe3Spacing.sm),
        ) {
            Text(
                text = stringResource(
                    if (state.hasLiveEngine) R.string.connections_pf_live_hint
                    else R.string.connections_pf_no_engine_hint,
                ),
                style = Oe3Typography.style(Oe3TextRole.Caption),
                color = colors.onSurfaceVariant.oe3Color(),
            )

            Oe3SectionHeader(title = stringResource(R.string.connections_pf_section_rules))

            if (PortForwardingPageSpec.emptyStateVisible(state.rules.size)) {
                Oe3Card(contentPadding = PaddingValues(0.dp)) {
                    Oe3EmptyState(
                        title = stringResource(R.string.connections_pf_empty),
                        description = stringResource(R.string.port_forwarding_empty),
                    )
                }
            } else {
                state.rules.forEach { rule ->
                    PortForwardingRuleCard(
                        rule = rule,
                        onToggle = { enabled -> onToggleRule(rule.id, enabled) },
                        onEdit = { onEditRule(rule) },
                        onDelete = { pendingDelete = rule },
                    )
                }
            }

            if (state.editingRuleId == null) {
                Spacer(Modifier.height(Oe3Spacing.xs))
                Oe3Card {
                    Text(
                        text = stringResource(R.string.connections_pf_section_add),
                        style = Oe3Typography.style(Oe3TextRole.BodyStrong),
                        color = colors.onSurface.oe3Color(),
                    )
                    Spacer(Modifier.height(Oe3Spacing.sm))
                    PortForwardingFields(
                        state, onHostPortChange, onGuestPortChange, onDescriptionChange,
                    )
                    Spacer(Modifier.height(Oe3Spacing.md))
                    Oe3Button(
                        text = stringResource(R.string.port_forwarding_add_rule),
                        onClick = onAddRule,
                        modifier = Modifier.fillMaxWidth().testTag("connections:pf:add"),
                    )
                }
            }
        }
    }

    if (state.editingRuleId != null) {
        Oe3DialogEnterMotion {
            AlertDialog(
                onDismissRequest = onCancelEdit,
                shape = RoundedCornerShape(OeShape.alert),
                title = { Text(stringResource(R.string.connections_pf_edit_title)) },
                text = {
                    Column {
                        PortForwardingFields(
                            state, onHostPortChange, onGuestPortChange, onDescriptionChange,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = onAddRule,
                        modifier = Modifier.testTag("connections:pf:edit-save"),
                    ) { Text(stringResource(R.string.connections_pf_save)) }
                },
                dismissButton = {
                    TextButton(onClick = onCancelEdit) {
                        Text(stringResource(R.string.connections_cancel))
                    }
                },
            )
        }
    }

    pendingDelete?.let { rule ->
        Oe3DialogEnterMotion {
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                shape = RoundedCornerShape(OeShape.alert),
                title = {
                    Text(
                        text = stringResource(R.string.connections_pf_delete_title),
                        style = Oe3Typography.style(Oe3TextRole.Section),
                    )
                },
                text = {
                    Text(
                        text = stringResource(
                            R.string.connections_pf_delete_message,
                            rule.protocol,
                            rule.hostPort,
                            rule.guestPort,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingDelete = null
                            onDeleteRule(rule.id)
                        },
                        modifier = Modifier.testTag("connections:pf:delete-confirm"),
                    ) { Text(stringResource(R.string.connections_delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) {
                        Text(stringResource(R.string.connections_cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun PortForwardingFields(
    state: PortForwardingPageUiState,
    onHostPortChange: (String) -> Unit,
    onGuestPortChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
) {
    val colors = Oe3ThemeValues.colors
    Row(horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.sm)) {
        ConnectionsTextField(
            value = state.hostPort,
            onValueChange = onHostPortChange,
            label = stringResource(R.string.port_forwarding_host_port),
            modifier = Modifier.weight(1f),
            isError = state.hostPortError != null,
            keyboardType = KeyboardType.Number,
            testTag = "connections:pf:host",
        )
        ConnectionsTextField(
            value = state.guestPort,
            onValueChange = onGuestPortChange,
            label = stringResource(R.string.port_forwarding_guest_port),
            modifier = Modifier.weight(1f),
            isError = state.guestPortError != null,
            keyboardType = KeyboardType.Number,
            testTag = "connections:pf:guest",
        )
    }

    val formError = state.hostPortError ?: state.guestPortError ?: state.generalError
    if (formError != null) {
        Spacer(Modifier.height(Oe3Spacing.xs))
        Text(
            text = formError,
            style = Oe3Typography.style(Oe3TextRole.Caption),
            color = colors.error.oe3Color(),
            modifier = Modifier.testTag("connections:pf:error"),
        )
    }

    Spacer(Modifier.height(Oe3Spacing.sm))
    Row(horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.xs)) {
        Oe3Chip(label = "TCP", selected = true)
        Oe3Chip(label = "UDP", selected = false, enabled = false)
    }
    Spacer(Modifier.height(Oe3Spacing.sm))
    ConnectionsTextField(
        value = state.description,
        onValueChange = onDescriptionChange,
        label = stringResource(R.string.port_forwarding_desc_hint),
        testTag = "connections:pf:desc",
    )
}

@Composable
private fun PortForwardingRuleCard(
    rule: PortForwardingRuleRow,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = Oe3ThemeValues.colors
    Oe3Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Oe3Chip(label = rule.protocol, selected = true)
        }
        Spacer(Modifier.height(Oe3Spacing.xs))
        Text(
            text = stringResource(
                R.string.connections_pf_rule_ports,
                rule.hostPort,
                rule.guestPort,
            ),
            style = Oe3Typography.style(Oe3TextRole.Mono),
            color = colors.onSurface.oe3Color(),
        )
        if (rule.description.isNotEmpty()) {
            Text(
                text = rule.description,
                style = Oe3Typography.style(Oe3TextRole.Caption),
                color = colors.onSurfaceVariant.oe3Color(),
            )
        }
        Oe3SwitchRow(
            title = stringResource(R.string.connections_pf_enabled),
            checked = rule.enabled,
            onCheckedChange = onToggle,
            enabled = rule.canToggle,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            if (rule.canEdit) {
                Oe3Button(
                    text = stringResource(R.string.connections_pf_edit),
                    onClick = onEdit,
                    style = Oe3ButtonStyle.Text,
                    modifier = Modifier.testTag("connections:pf:edit-${rule.id}"),
                )
            }
            Oe3Button(
                text = stringResource(R.string.connections_delete),
                onClick = onDelete,
                style = Oe3ButtonStyle.Text,
                modifier = Modifier.testTag("connections:pf:delete-${rule.id}"),
            )
        }
    }
}
