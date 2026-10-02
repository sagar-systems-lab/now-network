package com.sagarsystemslab.nownetwork.feature.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.sagarsystemslab.nownetwork.BuildConfig
import com.sagarsystemslab.nownetwork.designsystem.NowColors
import com.sagarsystemslab.nownetwork.designsystem.NowSecondaryButton
import com.sagarsystemslab.nownetwork.designsystem.NowSpacing
import com.sagarsystemslab.nownetwork.designsystem.NowType

@Composable
fun SettingsScreen(
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = NowSpacing.PageHorizontal,
                vertical = NowSpacing.Space2,
            )
            .testTag("screen-settings"),
        verticalArrangement = Arrangement.spacedBy(NowSpacing.Space4),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = NowColors.Ink700,
                )
            }
            Text(
                text = "Settings",
                style = NowType.TitleM,
                color = NowColors.Ink950,
            )
        }

        SettingsSection(
            icon = Icons.Outlined.DarkMode,
            title = "Appearance",
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = if (darkTheme) "Dark theme" else "Light theme",
                        style = NowType.TitleS,
                        color = NowColors.Ink950,
                    )
                    Text(
                        text = "Manual choice is remembered across launches.",
                        style = NowType.BodyS,
                        color = NowColors.Ink500,
                    )
                }
                Switch(
                    checked = darkTheme,
                    onCheckedChange = onDarkThemeChange,
                    modifier = Modifier.semantics {
                        contentDescription = "Theme"
                        stateDescription = if (darkTheme) "Dark theme" else "Light theme"
                    },
                )
            }
        }

        SettingsSection(
            icon = Icons.Outlined.Lock,
            title = "Privacy & permissions",
        ) {
            Text(
                text = "Camera and precise location are requested only when a proof task needs them. NOW does not request them at app launch.",
                style = NowType.BodyM,
                color = NowColors.Ink600,
            )
            NowSecondaryButton(
                text = "Open app permissions",
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + context.packageName),
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SettingsSection(
            icon = Icons.Outlined.Info,
            title = "About",
        ) {
            SettingsRow(
                label = "App",
                value = "NOW Network",
            )
            SettingsRow(
                label = "Version",
                value = BuildConfig.VERSION_NAME,
            )
            SettingsRow(
                label = "Network",
                value = BuildConfig.SOLANA_CLUSTER,
            )
            Text(
                text = "Live physical-state verification and settlement.",
                style = NowType.BodyS,
                color = NowColors.Ink500,
            )
        }
    }
}

@Composable
private fun SettingsSection(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = NowColors.SurfacePrimary,
        border = BorderStroke(1.dp, NowColors.BorderSubtle),
    ) {
        Column(
            modifier = Modifier.padding(NowSpacing.Space4),
            verticalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = NowColors.Blue600,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = title,
                    style = NowType.TitleS,
                    color = NowColors.Ink950,
                )
            }
            HorizontalDivider(color = NowColors.BorderSubtle)
            content()
        }
    }
}

@Composable
private fun SettingsRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space4),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = NowType.BodyM,
            color = NowColors.Ink500,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = NowType.LabelL,
            color = NowColors.Ink800,
        )
    }
}
