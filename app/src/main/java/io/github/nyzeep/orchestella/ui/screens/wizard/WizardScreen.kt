package io.github.nyzeep.orchestella.ui.screens.wizard

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nyzeep.orchestella.InstanceCreationWizardModel
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.ui.design.tokens.OeShape
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3BackButton
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Button
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3ButtonStyle
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Card
import io.github.nyzeep.orchestella.ui.oe3.components.oe3Color
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.LocalOe3PageBottomPadding
import io.github.nyzeep.orchestella.ui.oe3.tokens.LocalOe3PageTopPadding
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Shapes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography
import io.github.nyzeep.orchestella.ui.shell.Oe3PageTransitionSpec
import io.github.nyzeep.orchestella.ui.shell.OeScaffold
import io.github.nyzeep.orchestella.ui.shell.oeScrollOffsetPx

/**
 * 新建实例向导（UI-12）：壳内二级页，`OeScaffold` 单标题「新建实例」+ 返回。
 *
 * 四步（与模型 `CreationStep` 一一对应，投影不改步序）：
 * 存储与挂载 → 网络与镜像源 → 硬件配额分配 → 启动任务与部署。
 *
 * 返回键语义（工单验收）：步骤内逐级回退；退出向导的返回需确认
 * （Compose AlertDialog，[OeShape.alert] 32dp）；创建进行中的退出沿用
 * 旧「创建仍在进行中」文案。旧页的取消按钮同路。
 *
 * 全部控件走 Oe3 token：卡片 [Oe3Card]（24dp）、胶囊按钮、UI-05 唯一滑块
 * [OeSlider]（见 WizardStepContents）、动效取
 * `Oe3ThemeValues.motion`。底部固定操作条：上一步 / 下一步 / 创建（主按钮胶囊）。
 */
@Composable
fun WizardScreen(
    controller: WizardScreenModel,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = controller.state.value
    val context = LocalContext.current

    val showExitWhileCreating = rememberSaveable { mutableStateOf(false) }
    val showExitConfirm = rememberSaveable { mutableStateOf(false) }

    // 恢复提示一次性消费（旧 draftNotice Toast 语义）。
    LaunchedEffect(Unit) {
        controller.consumeDraftNotice()?.let { notice ->
            android.widget.Toast.makeText(context, notice, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // 返回键：步骤内逐级回退；退出需确认。取消按钮同路。
    val requestExit: () -> Unit = {
        if (state.isCreating) showExitWhileCreating.value = true else showExitConfirm.value = true
    }
    BackHandler {
        when {
            state.isCreating -> showExitWhileCreating.value = true
            state.canGoBack -> controller.goBack()
            else -> showExitConfirm.value = true
        }
    }

    // SAF 共享目录选择器（旧 OpenDocumentTree launcher，组合内持有）。
    val pickSharedDir = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: android.net.Uri? ->
        controller.onSharedDirPicked(uri)
    }
    val launchPicker: () -> Unit = { pickSharedDir.launch(null) }
    val stepScrollStates = InstanceCreationWizardModel.CreationStep.entries.associateWith {
        rememberScrollState()
    }

    OeScaffold(
        title = stringResource(R.string.wizard_page_title),
        modifier = modifier,
        leading = {
            Oe3BackButton(
                onBack = requestExit,
                contentDescription = stringResource(R.string.wizard_back_desc),
                testTag = "wizard:back",
            )
        },
        scrollOffsetPx = { stepScrollStates.getValue(state.step).oeScrollOffsetPx() },
    ) {
        Column(Modifier.fillMaxSize()) {
            WizardStepIndicator(
                stepIndex = state.stepIndex,
                totalSteps = state.totalSteps,
                stepName = stepName(state.step),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = Oe3Spacing.pageInset,
                        end = Oe3Spacing.pageInset,
                        top = LocalOe3PageTopPadding.current + Oe3Spacing.xs,
                    ),
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                // 步骤切换走 W2-03 fade-through 时间线（90ms 退场 + 20ms 留白 +
                // 210ms 进场 = 320ms）：离场步骤在 90ms 内淡出并轻移 25%，进场
                // 步骤延迟 110ms 后淡入滑入——两步透明度窗口零重叠，消除旧
                // 220ms 对称交叉淡化里的双页叠化。淡入同时遮蔽了滑动的延迟
                // 驻留窗（驻留期 alpha 为 0）。滚动状态提升在 stepScrollStates，
                // 步骤往返各自保留滚动位置；减动效下时长与延迟一并归零。
                val motion = Oe3ThemeValues.motion
                val enterSlideSpec = Oe3PageTransitionSpec.enterSlide(motion)
                val exitSlideSpec = Oe3PageTransitionSpec.exitSlide(motion)
                val enterFadeSpec = Oe3PageTransitionSpec.enterFade(motion)
                val exitFadeSpec = Oe3PageTransitionSpec.exitFade(motion)
                AnimatedContent(
                    targetState = state.step,
                    transitionSpec = {
                        val direction =
                            if (targetState.ordinal >= initialState.ordinal) 1 else -1
                        (
                            slideInHorizontally(enterSlideSpec) { fullWidth ->
                                Oe3PageTransitionSpec.slideOffsetX(fullWidth, direction)
                            } +
                                fadeIn(enterFadeSpec)
                            ) togetherWith (
                            slideOutHorizontally(exitSlideSpec) { fullWidth ->
                                Oe3PageTransitionSpec.exitSlideOffsetX(fullWidth, direction)
                            } +
                                fadeOut(exitFadeSpec)
                            )
                    },
                    label = "wizard-step",
                    modifier = Modifier.fillMaxSize(),
                ) { step ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(stepScrollStates.getValue(step))
                            .padding(
                                start = Oe3Spacing.pageInset,
                                end = Oe3Spacing.pageInset,
                                top = Oe3Spacing.md,
                            ),
                        verticalArrangement = Arrangement.spacedBy(Oe3Spacing.md),
                    ) {
                        state.deviceFloorRefusal?.let { refusal ->
                            Oe3Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("wizard:device-floor-refusal"),
                            ) {
                                Text(
                                    text = stringResource(R.string.error_device_floor_unsupported),
                                    style = Oe3Typography.style(Oe3TextRole.BodyStrong),
                                    color = androidx.compose.ui.res.colorResource(R.color.zone_red),
                                )
                                Spacer(Modifier.height(Oe3Spacing.xxs))
                                Text(
                                    text = refusal,
                                    style = Oe3Typography.style(Oe3TextRole.Caption),
                                    color = Oe3ThemeValues.colors.onSurfaceVariant.oe3Color(),
                                )
                            }
                        }
                        when (step) {
                            InstanceCreationWizardModel.CreationStep.STEP_STORAGE ->
                                StorageStepContent(
                                    state = state,
                                    controller = controller,
                                    onPickSharedDir = launchPicker,
                                )
                            InstanceCreationWizardModel.CreationStep.STEP_NETWORK_MIRROR ->
                                NetworkStepContent(state = state, controller = controller)
                            InstanceCreationWizardModel.CreationStep.STEP_HARDWARE ->
                                HardwareStepContent(state = state, controller = controller)
                            InstanceCreationWizardModel.CreationStep.STEP_STARTUP_TASKS ->
                                StartupStepContent(state = state, controller = controller)
                        }
                        // 滚动内容底部避让（坞保留 + 操作条已在避让区之上）。
                        Spacer(Modifier.height(Oe3Spacing.sm))
                    }
                }
            }

            WizardBottomBar(
                state = state,
                onPrev = controller::goBack,
                onNext = controller::goNext,
                onCreate = controller::startCreation,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // ---------- 对话框（全部 OeShape.alert 32dp；旧平台 AlertDialog 的去向） ----------

    val alertShape = RoundedCornerShape(OeShape.alert)

    // 旧 :742 confirmExitWhileCreating。
    if (showExitWhileCreating.value) {
        WizardAlertDialog(
            title = stringResource(R.string.wizard_exit_while_creating_title),
            text = stringResource(R.string.wizard_exit_while_creating_message),
            confirmLabel = stringResource(R.string.wizard_exit_while_creating_leave),
            dismissLabel = stringResource(R.string.wizard_exit_while_creating_stay),
            onConfirm = {
                showExitWhileCreating.value = false
                onExit()
            },
            onDismiss = { showExitWhileCreating.value = false },
            shape = alertShape,
        )
    }

    // 首次返回退出向导的确认（spec：32dp alert 档）。
    if (showExitConfirm.value) {
        WizardAlertDialog(
            title = stringResource(R.string.wizard_exit_confirm_title),
            text = stringResource(R.string.wizard_exit_confirm_message),
            confirmLabel = stringResource(R.string.wizard_exit_confirm_leave),
            dismissLabel = stringResource(R.string.wizard_exit_confirm_stay),
            onConfirm = {
                showExitConfirm.value = false
                onExit()
            },
            onDismiss = { showExitConfirm.value = false },
            shape = alertShape,
        )
    }

    // 旧 :1721/:1736/:1776/:1782 的 AVF 授权引导四相。
    when (val dialog = controller.authDialog.value) {
        WizardController.AuthDialog.None -> Unit
        WizardController.AuthDialog.ShizukuSteps -> WizardAlertDialog(
            title = stringResource(R.string.wizard_title),
            text = stringResource(R.string.wizard_shizuku_steps),
            confirmLabel = stringResource(R.string.shizuku_client_entry),
            otherLabels = listOf(
                stringResource(R.string.wizard_use_adb),
                stringResource(R.string.wizard_recheck_permissions),
            ),
            onConfirm = controller::authUseShizukuClient,
            onOther = listOf(controller::authChooseAdbFallback, controller::authDone),
            onDismiss = controller::dismissAuthDialog,
            shape = alertShape,
        )
        is WizardController.AuthDialog.AdbSteps -> WizardAlertDialog(
            title = stringResource(R.string.wizard_title),
            text = stringResource(R.string.wizard_adb_steps, dialog.command),
            confirmLabel = stringResource(R.string.wizard_authorization_done),
            otherLabels = listOf(
                stringResource(R.string.wizard_copy_command),
                stringResource(R.string.wizard_back_to_shizuku),
            ),
            onConfirm = controller::authDone,
            onOther = listOf(controller::authCopyCommand, controller::authBackToShizuku),
            onDismiss = controller::dismissAuthDialog,
            shape = alertShape,
        )
        WizardController.AuthDialog.AuthorizedSuccess -> WizardAlertDialog(
            title = stringResource(R.string.wizard_authorized_success_title),
            text = stringResource(R.string.wizard_authorized_success_msg),
            confirmLabel = stringResource(R.string.credentials_close),
            onConfirm = controller::dismissAuthDialog,
            onDismiss = controller::dismissAuthDialog,
            shape = alertShape,
        )
        WizardController.AuthDialog.VendorFailure -> WizardAlertDialog(
            title = stringResource(R.string.wizard_vendor_failure_title),
            text = stringResource(R.string.wizard_vendor_failure_steps),
            confirmLabel = stringResource(R.string.credentials_close),
            onConfirm = controller::dismissAuthDialog,
            onDismiss = controller::dismissAuthDialog,
            shape = alertShape,
        )
    }
}

// ---------------------------------------------------------------------------
// 步骤指示器
// ---------------------------------------------------------------------------

/**
 * 步骤指示器：4 段胶囊（当前步 `primary`、已完成步 `primaryContainer`、
 * 未到步 `surfaceHigh`），下方单行「第 x 步 / 共 4 步：名称」。段宽走
 * `weight(1f)`，任何宽度不截断（禁硬编码 dp 适配）。
 */
@Composable
fun WizardStepIndicator(
    stepIndex: Int,
    totalSteps: Int,
    stepName: String,
    modifier: Modifier = Modifier,
) {
    val colors = Oe3ThemeValues.colors
    Column(modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.xs)) {
            repeat(totalSteps) { index ->
                val position = index + 1
                val fill = when {
                    position == stepIndex -> colors.primary.oe3Color()
                    position < stepIndex -> colors.primaryContainer.oe3Color()
                    else -> colors.surfaceHigh.oe3Color()
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(Oe3Shapes.pill)
                        .background(fill)
                        .semantics {
                            contentDescription = "step $position of $totalSteps"
                        },
                )
            }
        }
        Spacer(Modifier.height(Oe3Spacing.xs))
        Text(
            text = stringResource(R.string.wizard_step_indicator, stepIndex, totalSteps, stepName),
            style = Oe3Typography.style(Oe3TextRole.Caption),
            color = colors.onSurfaceVariant.oe3Color(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun stepName(step: InstanceCreationWizardModel.CreationStep): String = when (step) {
    InstanceCreationWizardModel.CreationStep.STEP_STORAGE -> stringResource(R.string.wizard_step_storage_name)
    InstanceCreationWizardModel.CreationStep.STEP_NETWORK_MIRROR -> stringResource(R.string.wizard_step_network_name)
    InstanceCreationWizardModel.CreationStep.STEP_HARDWARE -> stringResource(R.string.wizard_step_hardware_name)
    InstanceCreationWizardModel.CreationStep.STEP_STARTUP_TASKS -> stringResource(R.string.wizard_step_startup_name)
}

// ---------------------------------------------------------------------------
// 底部固定操作条
// ---------------------------------------------------------------------------

/**
 * 底部固定操作条：上一步（Tonal）/ 下一步或创建（主按钮胶囊，fillMaxWidth）。
 * 第一步没有「上一步」——下一步占满整行。按钮可用性来自 [WizardUiState.canProceed]
 * 与 `canGoBack`（创建中一律禁用）。底边距走页底保留 token，任何导航形态
 * （浮动/固定坞）都不遮挡。
 */
@Composable
private fun WizardBottomBar(
    state: WizardUiState,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isLastStep = state.step == InstanceCreationWizardModel.CreationStep.STEP_STARTUP_TASKS
    val creating = state.isCreating
    val creationPhase = state.creation.phase
    val createLabel = when (creationPhase) {
        CreationPhase.Failed -> stringResource(R.string.wizard_create_retry)
        else -> stringResource(R.string.wizard_create_with_distro, creationDistroName(state))
    }
    Row(
        modifier = modifier
            .background(Color.Transparent)
            .padding(
                start = Oe3Spacing.pageInset,
                end = Oe3Spacing.pageInset,
                top = Oe3Spacing.sm,
                bottom = LocalOe3PageBottomPadding.current,
            ),
        horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.canGoBack && !creating) {
            Oe3Button(
                text = stringResource(R.string.wizard_btn_prev),
                onClick = onPrev,
                style = Oe3ButtonStyle.Tonal,
                modifier = Modifier.testTag("wizard:prev"),
            )
        }
        val primaryLabel = if (isLastStep) createLabel else stringResource(R.string.wizard_btn_next)
        val primaryEnabled = if (isLastStep) {
            state.canProceed && creationPhase != CreationPhase.Fetching &&
                creationPhase != CreationPhase.Provisioning
        } else {
            state.canProceed
        }
        val primaryAction = if (isLastStep) onCreate else onNext
        Oe3Button(
            text = primaryLabel,
            onClick = primaryAction,
            style = Oe3ButtonStyle.Pill,
            enabled = primaryEnabled,
            modifier = Modifier
                .weight(1f)
                .testTag(if (isLastStep) "wizard:create" else "wizard:next"),
        )
    }
}

@Composable
private fun creationDistroName(state: WizardUiState): String =
    state.storage.selectedDistro?.name ?: state.storage.selectedDistroId

// ---------------------------------------------------------------------------
// 通用对话框壳（32dp alert 档；文案与按钮由调用方给）
// ---------------------------------------------------------------------------

/**
 * 向导内全部对话框的公共壳：[OeShape.alert] 32dp 圆角、进入动效取
 * `motion.dialog` 语义的 250ms easeOutCubic（减动效归零）。主确认 + 可选
 * 两个次级动作（旧平台 AlertDialog 的三按钮形态）。
 */
@Composable
private fun WizardAlertDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    shape: RoundedCornerShape,
    dismissLabel: String? = null,
    otherLabels: List<String> = emptyList(),
    onOther: List<() -> Unit> = emptyList(),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = shape,
        title = {
            Text(
                text = title,
                style = Oe3Typography.style(Oe3TextRole.Section),
            )
        },
        text = {
            Text(
                text = text,
                style = Oe3Typography.style(Oe3TextRole.Body),
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = {
            if (dismissLabel != null) {
                TextButton(onClick = onDismiss) { Text(dismissLabel) }
            } else if (otherLabels.isNotEmpty()) {
                Row {
                    otherLabels.forEachIndexed { index, label ->
                        TextButton(
                            onClick = onOther.getOrElse(index) { onDismiss },
                        ) { Text(label) }
                    }
                }
            }
        },
    )
}
