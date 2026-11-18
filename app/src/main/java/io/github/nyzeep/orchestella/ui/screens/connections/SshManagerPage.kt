package io.github.nyzeep.orchestella.ui.screens.connections

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.ssh.SshProfile
import io.github.nyzeep.orchestella.ssh.SshProfileStore
import io.github.nyzeep.orchestella.ui.design.tokens.OeShape
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Button
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3ButtonStyle
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Card
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3EmptyState
import io.github.nyzeep.orchestella.ui.oe3.components.oe3Color
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3Icons
import io.github.nyzeep.orchestella.ui.oe3.motion.Oe3DialogEnterMotion
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography
import io.github.nyzeep.orchestella.ui.shell.OeScaffold
import io.github.nyzeep.orchestella.ui.shell.OeScaffoldAction

/**
 * Editable SSH profile fields, detached from storage so the form contract is
 * assertable without a Context.
 */
@Immutable
data class SshProfileDraft(
    val label: String,
    val host: String,
    val port: String,
    val username: String,
) {

    val portError: Boolean
        get() {
            val trimmed = port.trim()
            if (trimmed.isEmpty()) return false
            val value = trimmed.toIntOrNull() ?: return true
            return value !in 1..65535
        }

    /**
     * Applies the historical fallbacks: blank label/host/user fall back to the
     * page defaults, and an unparsable port falls back to 22. [base] keeps the
     * id and instance binding when editing.
     */
    fun toProfile(
        base: SshProfile?,
        instanceId: String?,
        defaultLabel: String,
    ): SshProfile {
        val resolvedLabel = label.trim().ifEmpty { defaultLabel }
        val resolvedHost = host.trim().ifEmpty { "127.0.0.1" }
        val resolvedPort = port.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: 22
        val resolvedUser = username.trim().ifEmpty { "root" }
        val profile = base ?: SshProfile(label = resolvedLabel, instanceId = instanceId)
        return profile.copy(
            label = resolvedLabel,
            host = resolvedHost,
            port = resolvedPort,
            username = resolvedUser,
        )
    }
}

object SshProfileDraftSpec {

    fun new(
        defaultLabel: String,
        defaultHost: String,
        defaultPort: Int,
        defaultUser: String,
    ): SshProfileDraft = SshProfileDraft(
        label = defaultLabel,
        host = defaultHost,
        port = defaultPort.toString(),
        username = defaultUser,
    )

    fun edit(profile: SshProfile): SshProfileDraft = SshProfileDraft(
        label = profile.label,
        host = profile.host,
        port = profile.port.toString(),
        username = profile.username,
    )
}

/** Page-local profile list + editor/dialog state; storage stays in [SshProfileStore]. */
class SshManagerPageState(
    private val store: SshProfileStore,
    private val defaultInstanceId: String?,
    private val defaultHost: String,
    private val defaultPort: Int,
    private val defaultUser: String,
    private val defaultLabel: String,
    private val defaultInstanceLabel: String,
) {

    var profiles by mutableStateOf(store.getAllProfiles())
        private set

    var editorVisible by mutableStateOf(false)
        private set
    var editing by mutableStateOf<SshProfile?>(null)
        private set
    var pendingDelete by mutableStateOf<SshProfile?>(null)
        private set

    init {
        ensureDefaultProfile()
    }

    private fun ensureDefaultProfile() {
        if (profiles.isNotEmpty() || defaultInstanceId == null) return
        val profile = SshProfile(
            label = defaultInstanceLabel,
            host = defaultHost,
            port = defaultPort,
            username = defaultUser,
            instanceId = defaultInstanceId,
        )
        store.saveOrUpdate(profile)
        profiles = store.getAllProfiles()
    }

    fun openNew() {
        editing = null
        editorVisible = true
    }

    fun openEdit(profile: SshProfile) {
        editing = profile
        editorVisible = true
    }

    fun dismissEditor() {
        editorVisible = false
        editing = null
    }

    fun save(draft: SshProfileDraft) {
        val base = editing
        store.saveOrUpdate(draft.toProfile(base, defaultInstanceId, defaultLabel))
        dismissEditor()
        profiles = store.getAllProfiles()
    }

    fun requestDelete(profile: SshProfile) {
        pendingDelete = profile
    }

    fun dismissDelete() {
        pendingDelete = null
    }

    fun delete(profile: SshProfile) {
        store.deleteProfile(profile.id)
        pendingDelete = null
        profiles = store.getAllProfiles()
    }
}

/**
 * SSH connection manager secondary page.
 *
 * The list is the page body; creating/editing four fields is a page-local
 * sub-flow, so it opens a 32dp Compose dialog instead of a second full page
 * (spec §2.3 boundary: short forms stay light, lists get pages).
 */
@Composable
fun SshManagerPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    defaultInstanceId: String? = null,
    defaultHost: String = "127.0.0.1",
    defaultPort: Int = 2222,
    defaultUser: String = "root",
    onConnect: ((SshProfile) -> Unit)? = null,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val store = remember { SshProfileStore(context) }
    val defaultLabel = stringResource(R.string.connections_ssh_label_default)
    val defaultInstanceLabel = stringResource(
        R.string.connections_ssh_default_instance_label,
        defaultInstanceId.orEmpty(),
    )
    val state = remember(store, defaultInstanceId) {
        SshManagerPageState(
            store = store,
            defaultInstanceId = defaultInstanceId,
            defaultHost = defaultHost,
            defaultPort = defaultPort,
            defaultUser = defaultUser,
            defaultLabel = defaultLabel,
            defaultInstanceLabel = defaultInstanceLabel,
        )
    }

    val copyProfile: (SshProfile) -> Unit = { profile ->
        val command = profile.buildCliCommand()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("SSH Command", command))
        Toast.makeText(
            context,
            resources.getString(R.string.connections_ssh_copied, command),
            Toast.LENGTH_SHORT,
        ).show()
    }

    val connectProfile: (SshProfile) -> Unit = { profile ->
        if (onConnect != null) {
            onConnect(profile)
        } else {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(profile.buildUriString()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) {
                copyProfile(profile)
                Toast.makeText(
                    context,
                    R.string.connections_ssh_copy_fallback,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    SshManagerContent(
        profiles = state.profiles,
        onBack = onBack,
        onNew = state::openNew,
        onConnect = connectProfile,
        onCopy = copyProfile,
        onEdit = state::openEdit,
        onDelete = state::requestDelete,
        modifier = modifier,
    )

    if (state.editorVisible) {
        SshProfileEditorDialog(
            existing = state.editing,
            defaultHost = defaultHost,
            defaultPort = defaultPort,
            defaultUser = defaultUser,
            defaultLabel = defaultLabel,
            onDismiss = state::dismissEditor,
            onSave = state::save,
        )
    }

    state.pendingDelete?.let { profile ->
        Oe3DialogEnterMotion {
            AlertDialog(
                onDismissRequest = state::dismissDelete,
                shape = RoundedCornerShape(OeShape.alert),
                title = {
                    Text(
                        text = stringResource(R.string.connections_ssh_delete_title),
                        style = Oe3Typography.style(Oe3TextRole.Section),
                    )
                },
                text = {
                    Text(stringResource(R.string.connections_ssh_delete_message, profile.label))
                },
                confirmButton = {
                    TextButton(
                        onClick = { state.delete(profile) },
                        modifier = Modifier.testTag("connections:ssh:delete-confirm"),
                    ) { Text(stringResource(R.string.connections_delete)) }
                },
                dismissButton = {
                    TextButton(onClick = state::dismissDelete) {
                        Text(stringResource(R.string.connections_cancel))
                    }
                },
            )
        }
    }
}

@Composable
fun SshManagerContent(
    profiles: List<SshProfile>,
    onBack: () -> Unit,
    onNew: () -> Unit,
    onConnect: (SshProfile) -> Unit,
    onCopy: (SshProfile) -> Unit,
    onEdit: (SshProfile) -> Unit,
    onDelete: (SshProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    OeScaffold(
        title = stringResource(R.string.connections_ssh_title),
        modifier = modifier,
        leading = { ConnectionsBackButton(onBack = onBack) },
        actions = listOf(
            OeScaffoldAction(
                icon = Oe3Icons.Create,
                contentDescription = stringResource(R.string.connections_ssh_new),
                onClick = onNew,
                testTag = "connections:ssh:new-action",
            ),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = Oe3Spacing.pageInset,
                    end = Oe3Spacing.pageInset,
                    top = Oe3Spacing.sm,
                    bottom = Oe3Spacing.xxl,
                )
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Oe3Spacing.sm),
        ) {
            if (profiles.isEmpty()) {
                Oe3Card {
                    Oe3EmptyState(
                        title = stringResource(R.string.connections_ssh_empty_title),
                        description = stringResource(R.string.connections_ssh_empty_desc),
                        action = {
                            Oe3Button(
                                text = stringResource(R.string.connections_ssh_new),
                                onClick = onNew,
                                modifier = Modifier.testTag("connections:ssh:empty-new"),
                            )
                        },
                    )
                }
            } else {
                profiles.forEach { profile ->
                    SshProfileCard(
                        profile = profile,
                        onConnect = { onConnect(profile) },
                        onCopy = { onCopy(profile) },
                        onEdit = { onEdit(profile) },
                        onDelete = { onDelete(profile) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SshProfileCard(
    profile: SshProfile,
    onConnect: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = Oe3ThemeValues.colors
    Oe3Card {
        Text(
            text = profile.label,
            style = Oe3Typography.style(Oe3TextRole.BodyStrong),
            color = colors.onSurface.oe3Color(),
        )
        Text(
            text = profile.buildCliCommand(),
            style = Oe3Typography.style(Oe3TextRole.Mono),
            color = colors.primary.oe3Color(),
        )
        Spacer(Modifier.height(Oe3Spacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.xs)) {
            Oe3Button(
                text = stringResource(R.string.connections_ssh_connect),
                onClick = onConnect,
                style = Oe3ButtonStyle.Tonal,
                modifier = Modifier.weight(1f),
            )
            Oe3Button(
                text = stringResource(R.string.connections_ssh_copy),
                onClick = onCopy,
                style = Oe3ButtonStyle.Text,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.xs)) {
            Oe3Button(
                text = stringResource(R.string.connections_ssh_edit),
                onClick = onEdit,
                style = Oe3ButtonStyle.Text,
                modifier = Modifier.weight(1f),
            )
            Oe3Button(
                text = stringResource(R.string.connections_delete),
                onClick = onDelete,
                style = Oe3ButtonStyle.Text,
                modifier = Modifier
                    .weight(1f)
                    .testTag("connections:ssh:delete-${profile.id}"),
            )
        }
    }
}

@Composable
private fun SshProfileEditorDialog(
    existing: SshProfile?,
    defaultHost: String,
    defaultPort: Int,
    defaultUser: String,
    defaultLabel: String,
    onDismiss: () -> Unit,
    onSave: (SshProfileDraft) -> Unit,
) {
    val initial = remember(existing) {
        existing?.let(SshProfileDraftSpec::edit)
            ?: SshProfileDraftSpec.new(defaultLabel, defaultHost, defaultPort, defaultUser)
    }
    var label by remember(existing) { mutableStateOf(initial.label) }
    var host by remember(existing) { mutableStateOf(initial.host) }
    var port by remember(existing) { mutableStateOf(initial.port) }
    var username by remember(existing) { mutableStateOf(initial.username) }
    val draft = SshProfileDraft(label = label, host = host, port = port, username = username)

    Oe3DialogEnterMotion {
        AlertDialog(
            onDismissRequest = onDismiss,
            shape = RoundedCornerShape(OeShape.alert),
            title = {
                Text(
                    text = stringResource(
                        if (existing == null) R.string.connections_ssh_new_title
                        else R.string.connections_ssh_edit_title,
                    ),
                    style = Oe3Typography.style(Oe3TextRole.Section),
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Oe3Spacing.sm)) {
                    ConnectionsTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = stringResource(R.string.connections_ssh_field_label),
                        testTag = "connections:ssh:field-label",
                    )
                    ConnectionsTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = stringResource(R.string.connections_ssh_field_host),
                        testTag = "connections:ssh:field-host",
                    )
                    ConnectionsTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = stringResource(R.string.connections_ssh_field_port),
                        isError = draft.portError,
                        keyboardType = KeyboardType.Number,
                        testTag = "connections:ssh:field-port",
                    )
                    ConnectionsTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = stringResource(R.string.connections_ssh_field_user),
                        testTag = "connections:ssh:field-user",
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onSave(draft) },
                    enabled = !draft.portError,
                    modifier = Modifier.testTag("connections:ssh:save"),
                ) { Text(stringResource(R.string.connections_ssh_save)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.connections_cancel))
                }
            },
        )
    }
}
