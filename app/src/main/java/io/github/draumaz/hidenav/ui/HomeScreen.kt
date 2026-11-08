package io.github.draumaz.hidenav.ui

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.draumaz.hidenav.BuildConfig
import io.github.draumaz.hidenav.Prefs
import io.github.draumaz.hidenav.R
import io.github.draumaz.hidenav.ShellBridge
import io.github.draumaz.hidenav.ShizukuState

@Composable
fun HomeScreen(bridge: ShellBridge, prefs: Prefs) {
    val state by bridge.state.collectAsStateWithLifecycle()
    val settings by prefs.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val ready = state == ShizukuState.READY

    fun toggleFeedback(on: Boolean) =
        haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)

    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            // Only scrolls if the content can't fit (e.g. large font scale); the footer stays pinned.
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                Header()

                SectionLabel(stringResource(R.string.section_shizuku))
                ShizukuRow(state) {
                    when (state) {
                        ShizukuState.NEEDS_PERMISSION -> bridge.requestPermission()
                        ShizukuState.NOT_RUNNING -> openShizuku(context)
                        else -> Unit
                    }
                }

                SectionLabel(stringResource(R.string.section_navigation))
                SettingRow(
                    icon = Icons.Rounded.VisibilityOff,
                    iconShape = MaterialShapes.Cookie7Sided,
                    title = stringResource(R.string.hide_pill),
                    subtitle = stringResource(R.string.hide_pill_detail),
                    shape = segmentShape(0, 3),
                    enabled = ready,
                    checked = settings.hidePill,
                    onCheckedChange = { on ->
                        toggleFeedback(on)
                        prefs.update { it.copy(hidePill = on) }
                    },
                )
                SettingRow(
                    icon = Icons.Rounded.Home,
                    iconShape = MaterialShapes.Cookie6Sided,
                    title = stringResource(R.string.show_on_home),
                    subtitle = stringResource(
                        if (settings.hidePill) R.string.show_on_home_detail else R.string.show_on_home_needs_hide
                    ),
                    shape = segmentShape(1, 3),
                    enabled = ready && settings.hidePill,
                    checked = settings.showOnHome,
                    onCheckedChange = { on ->
                        toggleFeedback(on)
                        prefs.update { it.copy(showOnHome = on) }
                    },
                )
                SettingRow(
                    icon = Icons.Rounded.ImageSearch,
                    iconShape = MaterialShapes.Cookie9Sided,
                    title = stringResource(R.string.keep_cts),
                    subtitle = stringResource(
                        if (settings.hidePill) R.string.keep_cts_detail else R.string.keep_cts_needs_hide
                    ),
                    shape = segmentShape(2, 3),
                    enabled = ready && settings.hidePill,
                    checked = settings.keepCircleToSearch,
                    onCheckedChange = { on ->
                        toggleFeedback(on)
                        prefs.update { it.copy(keepCircleToSearch = on) }
                    },
                )

                SectionLabel(stringResource(R.string.section_notes))
                NoteCard(
                    listOf(
                        R.string.note_space_title to R.string.note_space,
                        R.string.note_shizuku_title to R.string.note_shizuku,
                        R.string.note_cts_title to R.string.note_cts,
                    ),
                )
            }

            Footer()
        }
    }
}

@Composable
private fun Footer() {
    val linkStyle = TextLinkStyles(
        SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline),
    )
    val appName = stringResource(R.string.app_name)
    val version = stringResource(R.string.about_version, BuildConfig.VERSION_NAME)
    Text(
        buildAnnotatedString {
            withLink(LinkAnnotation.Url(REPO_URL, linkStyle)) { append(appName) }
            append(" $version")
        },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 8.dp),
    )
}

private const val REPO_URL = "https://github.com/draumaz/hidenav"

@Composable
private fun Header() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Cookie12Sided.toShape()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_launcher_monochrome),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(48.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMediumEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(R.string.subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ShizukuRow(state: ShizukuState, onClick: () -> Unit) {
    val (icon, title, detail) = when (state) {
        ShizukuState.READY -> Triple(Icons.Rounded.CheckCircle, R.string.shizuku_ready, R.string.shizuku_ready_detail)
        ShizukuState.CONNECTING -> Triple(Icons.Rounded.HourglassTop, R.string.shizuku_connecting, R.string.shizuku_connecting_detail)
        ShizukuState.NEEDS_PERMISSION -> Triple(Icons.Rounded.Key, R.string.shizuku_permission, R.string.shizuku_permission_detail)
        ShizukuState.NOT_RUNNING -> Triple(Icons.Rounded.PowerSettingsNew, R.string.shizuku_not_running, R.string.shizuku_not_running_detail)
        ShizukuState.UNSUPPORTED -> Triple(Icons.Rounded.ErrorOutline, R.string.shizuku_unsupported, R.string.shizuku_unsupported_detail)
    }
    SettingRow(
        icon = icon,
        iconShape = MaterialShapes.Clover4Leaf,
        title = stringResource(title),
        subtitle = stringResource(detail),
        shape = segmentShape(0, 1),
        highlighted = state == ShizukuState.READY,
        onClick = onClick,
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    iconShape: RoundedPolygon,
    title: String,
    subtitle: String,
    shape: RoundedCornerShape,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    checked: Boolean? = null,
    onCheckedChange: (Boolean) -> Unit = {},
    onClick: () -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val rowModifier = if (checked != null) {
        Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            interactionSource = interaction,
            indication = ripple(),
            onValueChange = onCheckedChange,
        )
    } else Modifier
    Surface(
        onClick = onClick,
        enabled = enabled && checked == null,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceBright,
        interactionSource = interaction,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp)
            .pressScale(interaction),
    ) {
        Row(rowModifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            val alpha = if (enabled) 1f else 0.38f
            val badge = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
            val onBadge = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
            Box(
                Modifier
                    .size(44.dp)
                    .graphicsLayer { this.alpha = alpha }
                    .background(badge, iconShape.toShape()),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = onBadge, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(
                Modifier
                    .weight(1f)
                    .graphicsLayer { this.alpha = alpha },
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (checked != null) {
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = checked,
                    onCheckedChange = null,
                    enabled = enabled,
                    thumbContent = if (checked) {
                        { Icon(Icons.Rounded.Check, null, Modifier.size(SwitchDefaults.IconSize)) }
                    } else null,
                )
            }
        }
    }
}

@Composable
private fun NoteCard(notes: List<Pair<Int, Int>>) {
    Surface(
        shape = segmentShape(0, 1),
        color = MaterialTheme.colorScheme.surfaceBright,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            notes.forEachIndexed { index, (title, body) ->
                if (index > 0) Spacer(Modifier.height(12.dp))
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun segmentShape(index: Int, count: Int, outer: Dp = 24.dp, inner: Dp = 6.dp): RoundedCornerShape {
    val top = if (index == 0) outer else inner
    val bottom = if (index == count - 1) outer else inner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

@Composable
private fun Modifier.pressScale(interaction: MutableInteractionSource, pressed: Float = 0.98f): Modifier {
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (isPressed) pressed else 1f,
        MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "pressScale",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Opens whichever Shizuku build is installed, so the user can start it. */
private fun openShizuku(context: Context) {
    val intent = SHIZUKU_PACKAGES.firstNotNullOfOrNull { context.packageManager.getLaunchIntentForPackage(it) }
        ?: return
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private val SHIZUKU_PACKAGES = listOf("kerneldroid.nightzuku", "moe.shizuku.privileged.api")
