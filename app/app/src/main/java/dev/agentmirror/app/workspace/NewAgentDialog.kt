/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.agentmirror.app.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.agentmirror.app.ui.components.ProviderIcon
import dev.agentmirror.app.ui.theme.Dims
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.Radii
import dev.agentmirror.app.ui.theme.Spacing
import dev.agentmirror.app.ui.theme.TypeSizes

/** Bypass 勾选旁的一句说明（契约 092 §2；Pi 无旗，勾选不改变 argv）。 */
const val NEW_AGENT_BYPASS_HINT =
    "开启后跳过该 CLI 的权限审批。Pi 不支持此选项。"

/**
 * 新建 Agent 弹层：选工作区 / Provider / Bypass。
 *
 * Provider 用 088 §3 那套卡通图标卡片，不用裸 RadioButton。确认钮主色置底。
 * argv 组装仍走 [buildNewAgentArgv]（088 §7 服务端原样执行）。
 *
 * @contract
 * @pre ui 非空时已组合
 * @post 点确认只回调 onConfirm；未确认不发帧
 * @err none
 * @inv Pi 的 Bypass 勾选不改变 argv（无旗）
 */
@Composable
fun NewAgentDialog(
    ui: WorkspaceViewModel.NewAgentUi,
    onSelectCwd: (String) -> Unit,
    onSelectProvider: (String) -> Unit,
    onToggleBypass: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val p = LocalAppPalette.current
    Dialog(onDismissRequest = { if (!ui.inFlight) onCancel() }) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("new-agent-dialog"),
            shape = RoundedCornerShape(Radii.card),
            color = p.cardBackground,
            border = androidx.compose.foundation.BorderStroke(Dims.hairline, p.cardBorder),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
            ) {
                Text(
                    text = "新建 Agent",
                    color = p.titleText,
                    fontSize = TypeSizes.sheetTitle,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(
                        start = Spacing.lg,
                        end = Spacing.lg,
                        top = Spacing.lg,
                        bottom = Spacing.sm,
                    ),
                )
                Column(Modifier.padding(horizontal = Spacing.lg)) {
                    SectionLabel("Provider")
                    Spacer(Modifier.height(Spacing.xs))
                    ProviderCardGrid(
                        selectedId = ui.providerId,
                        onSelect = onSelectProvider,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                }
                if (ui.cwds.isNotEmpty()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .heightIn(max = 168.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = Spacing.lg),
                    ) {
                        SectionLabel("工作区")
                        Spacer(Modifier.height(Spacing.xs))
                        ui.cwds.forEach { cwd ->
                            CwdRow(
                                cwd = cwd,
                                selected = cwd == ui.cwd,
                                onClick = { onSelectCwd(cwd) },
                            )
                            Spacer(Modifier.height(Spacing.xs))
                        }
                    }
                }
                val err = ui.error
                if (!err.isNullOrEmpty()) {
                    Text(
                        text = err,
                        color = p.unknownChipText,
                        fontSize = TypeSizes.cardBody,
                        modifier = Modifier
                            .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
                            .testTag("new-agent-error"),
                    )
                }
                Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
                    BypassBlock(
                        enabled = ui.providerId != "pi",
                        checked = ui.bypass && ui.providerId != "pi",
                        onToggle = onToggleBypass,
                    )
                }
                HorizontalDivider(thickness = Dims.hairline, color = p.divider)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    DialogOutlineButton(
                        text = "取消",
                        onClick = onCancel,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("new-agent-cancel"),
                    )
                    DialogPrimaryButton(
                        text = "创建",
                        enabled = !ui.inFlight && ui.cwd.isNotEmpty() && ui.providerId.isNotEmpty(),
                        onClick = onConfirm,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("new-agent-ok"),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    val p = LocalAppPalette.current
    Text(
        text = text,
        color = p.metaText,
        fontSize = TypeSizes.headerMeta,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun CwdRow(cwd: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalAppPalette.current
    val shape = RoundedCornerShape(Radii.chip)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) p.sheetCurrentRowBg else Color.Transparent)
            .border(
                width = Dims.hairline,
                color = if (selected) p.accent else p.cardBorder,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .semantics { this.selected = selected }
            .testTag("new-agent-cwd")
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = cwd,
            color = if (selected) p.accent else p.rowTitleText,
            fontSize = TypeSizes.footnote,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProviderCardGrid(
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    val ids = NewAgentProviders.ids
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        ids.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                row.forEach { id ->
                    ProviderPickCard(
                        id = id,
                        selected = id == selectedId,
                        onClick = { onSelect(id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ProviderPickCard(
    id: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalAppPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(Radii.cardButton)
    Row(
        modifier = modifier
            .clip(shape)
            .background(
                when {
                    selected -> p.sheetCurrentRowBg
                    pressed -> p.rowPressed
                    else -> p.listBackground
                },
            )
            .border(
                width = if (selected) 1.5.dp else Dims.hairline,
                color = if (selected) p.accent else p.cardBorder,
                shape = shape,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics { this.selected = selected }
            .testTag("new-agent-provider-$id")
            .padding(horizontal = Spacing.xs, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderIcon(provider = id)
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = NewAgentProviders.displayName(id),
            color = if (selected) p.accent else p.rowTitleText,
            fontSize = TypeSizes.cardButton,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun BypassBlock(
    enabled: Boolean,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val p = LocalAppPalette.current
    val shape = RoundedCornerShape(Radii.cardButton)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.listBackground)
            .border(Dims.hairline, p.cardBorder, shape)
            .clickable(enabled = enabled) { onToggle(!checked) }
            .testTag("new-agent-bypass")
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = checked,
                onCheckedChange = { if (enabled) onToggle(it) },
                enabled = enabled,
                colors = CheckboxDefaults.colors(
                    checkedColor = p.accent,
                    uncheckedColor = p.metaText,
                    checkmarkColor = p.onAccent,
                    disabledCheckedColor = p.metaText,
                    disabledUncheckedColor = p.metaText,
                ),
            )
            Text(
                text = "Bypass 权限",
                color = if (enabled) p.rowTitleText else p.metaText,
                fontSize = TypeSizes.cardTitle,
                fontWeight = FontWeight.Medium,
            )
        }
        Text(
            text = NEW_AGENT_BYPASS_HINT,
            color = p.bodyText,
            fontSize = TypeSizes.footnote,
            modifier = Modifier
                .padding(start = 48.dp, end = Spacing.sm)
                .testTag("new-agent-bypass-hint"),
        )
    }
}

@Composable
private fun DialogOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalAppPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .height(Dims.cardButtonHeight)
            .clip(RoundedCornerShape(Radii.cardButton))
            .background(if (pressed) p.outlineButtonPressed else Color.Transparent)
            .border(Dims.hairline, p.outlineButtonBorder, RoundedCornerShape(Radii.cardButton))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = p.outlineButtonText,
            fontSize = TypeSizes.cardButton,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 主色确认钮，钉在弹层底栏（契约 092 §2）。
 *
 * @contract
 * @pre none
 * @post enabled 时回调 onClick
 * @err none
 */
@Composable
private fun DialogPrimaryButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalAppPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .height(Dims.cardButtonHeight)
            .clip(RoundedCornerShape(Radii.cardButton))
            .background(
                when {
                    !enabled -> p.sendDisabledBg
                    pressed -> p.accentContainerPressed
                    else -> p.accent
                },
            )
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) p.onAccent else p.sendDisabledFg,
            fontSize = TypeSizes.cardButton,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
