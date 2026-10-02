package com.sagarsystemslab.nownetwork

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sagarsystemslab.nownetwork.designsystem.NowColors
import com.sagarsystemslab.nownetwork.designsystem.NowSpacing
import com.sagarsystemslab.nownetwork.designsystem.NowTheme
import com.sagarsystemslab.nownetwork.designsystem.NowType

class ExperienceLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            NowTheme {
                ExperienceLab()
            }
        }
    }
}

private enum class LabAvailability {
    READY,
    UPCOMING,
}

private data class LabScenario(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val availability: LabAvailability,
)

private val scenarios = listOf(
    LabScenario(
        id = "shell",
        title = "App shell & tabs",
        description = "Exercise the three-destination navigation contract and selected states.",
        icon = Icons.Outlined.TouchApp,
        availability = LabAvailability.READY,
    ),
    LabScenario(
        id = "theme",
        title = "Light / dark theme",
        description = "Theme tokens, semantic surfaces, status colors, and manual preference.",
        icon = Icons.Outlined.DarkMode,
        availability = LabAvailability.UPCOMING,
    ),
    LabScenario(
        id = "home",
        title = "NOW / Home",
        description = "Live, aging, stale, conflict, loading, empty, and offline home states.",
        icon = Icons.Outlined.Home,
        availability = LabAvailability.UPCOMING,
    ),
    LabScenario(
        id = "earn",
        title = "EARN & claim",
        description = "Opportunity cards, claim states, deadlines, proof requirements, and recovery.",
        icon = Icons.Outlined.WorkOutline,
        availability = LabAvailability.UPCOMING,
    ),
    LabScenario(
        id = "verification",
        title = "Verification & LIVE",
        description = "Evidence review, verification progress, verified value transition, and conflict.",
        icon = Icons.Outlined.Visibility,
        availability = LabAvailability.UPCOMING,
    ),
    LabScenario(
        id = "payment",
        title = "Payment & receipt",
        description = "Pending, checking, paid, retry, and durable receipt states.",
        icon = Icons.Outlined.Payments,
        availability = LabAvailability.UPCOMING,
    ),
)

@Composable
private fun ExperienceLab() {
    var selectedScenarioId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedScenario = scenarios.firstOrNull { it.id == selectedScenarioId }

    BackHandler(enabled = selectedScenario != null) {
        selectedScenarioId = null
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = NowColors.SurfaceCanvas,
        contentColor = NowColors.Ink950,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { innerPadding ->
        if (selectedScenario == null) {
            LabCatalog(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                onOpen = { scenario ->
                    selectedScenarioId = scenario.id
                },
            )
        } else {
            ScenarioScreen(
                scenario = selectedScenario,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                onBack = {
                    selectedScenarioId = null
                },
            )
        }
    }
}

@Composable
private fun LabCatalog(
    onOpen: (LabScenario) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = NowSpacing.PageHorizontal,
            top = NowSpacing.Space4,
            end = NowSpacing.PageHorizontal,
            bottom = NowSpacing.Space8,
        ),
        verticalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
    ) {
        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
                ) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = NowColors.Blue50,
                        border = BorderStroke(1.dp, NowColors.Blue100),
                    ) {
                        Text(
                            text = "DEBUG ONLY",
                            style = NowType.LabelM,
                            color = NowColors.Blue700,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                    Text(
                        text = "Experience Lab",
                        style = NowType.TitleM,
                        color = NowColors.Ink950,
                    )
                }

                Text(
                    text = "Phone-first acceptance surface for visual, interaction, state, and motion checks.",
                    style = NowType.BodyM,
                    color = NowColors.Ink600,
                )
                Text(
                    text = "Production screens are connected here as they are finalized; the release app does not expose this launcher.",
                    style = NowType.BodyS,
                    color = NowColors.Ink500,
                )
            }
        }

        item {
            HorizontalDivider(color = NowColors.BorderSubtle)
        }

        items(
            items = scenarios,
            key = { it.id },
        ) { scenario ->
            ScenarioCard(
                scenario = scenario,
                onClick = {
                    onOpen(scenario)
                },
            )
        }
    }
}

@Composable
private fun ScenarioCard(
    scenario: LabScenario,
    onClick: () -> Unit,
) {
    val ready = scenario.availability == LabAvailability.READY

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = ready,
                role = Role.Button,
                onClick = onClick,
            ),
        shape = MaterialTheme.shapes.large,
        color = NowColors.SurfacePrimary,
        border = BorderStroke(1.dp, NowColors.BorderSubtle),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(NowSpacing.Space4),
            horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = RoundedCornerShape(10.dp),
                color = if (ready) NowColors.Blue50 else NowColors.Ink100,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = scenario.icon,
                        contentDescription = null,
                        tint = if (ready) NowColors.Blue600 else NowColors.Ink400,
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space1),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = scenario.title,
                        style = NowType.TitleS,
                        color = if (ready) NowColors.Ink950 else NowColors.Ink600,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (ready) "READY" else "UPCOMING",
                        style = NowType.LabelM,
                        color = if (ready) NowColors.LiveText else NowColors.Ink500,
                    )
                }
                Text(
                    text = scenario.description,
                    style = NowType.BodyS,
                    color = NowColors.Ink500,
                )
            }
        }
    }
}

@Composable
private fun ScenarioScreen(
    scenario: LabScenario,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(horizontal = NowSpacing.Space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back to Experience Lab",
                    tint = NowColors.Ink700,
                )
            }
            Text(
                text = scenario.title,
                style = NowType.TitleM,
                color = NowColors.Ink950,
            )
        }

        HorizontalDivider(color = NowColors.BorderSubtle)

        if (scenario.id == "shell") {
            ShellScenario(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(NowSpacing.PageHorizontal),
            )
        }
    }
}

@Composable
private fun ShellScenario(
    modifier: Modifier = Modifier,
) {
    var selectedIndex by remember { mutableIntStateOf(0) }
    val labels = listOf("NOW", "EARN", "ACTIVITY")
    val icons = listOf(
        Icons.Outlined.Home,
        Icons.Outlined.WorkOutline,
        Icons.Outlined.CheckCircle,
    )

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(NowSpacing.Space4),
    ) {
        Spacer(Modifier.height(NowSpacing.Space2))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = NowColors.SurfacePrimary,
            border = BorderStroke(1.dp, NowColors.BorderSubtle),
        ) {
            Column(
                modifier = Modifier.padding(NowSpacing.Space4),
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
            ) {
                Text(
                    text = "Interaction smoke test",
                    style = NowType.TitleS,
                    color = NowColors.Ink950,
                )
                Text(
                    text = "Tap each destination. The selected state changes immediately without navigating or loading.",
                    style = NowType.BodyM,
                    color = NowColors.Ink600,
                )
                Text(
                    text = "Selected: ${labels[selectedIndex]}",
                    style = NowType.LabelM,
                    color = NowColors.Blue700,
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = NowColors.SurfacePrimary,
            border = BorderStroke(1.dp, NowColors.BorderSubtle),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
            ) {
                labels.forEachIndexed { index, label ->
                    val selected = index == selectedIndex
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clickable(
                                role = Role.Tab,
                                onClick = {
                                    selectedIndex = index
                                },
                            )
                            .semantics {
                                this.selected = selected
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 34.dp, height = 28.dp)
                                .background(
                                    color = if (selected) {
                                        NowColors.Blue50
                                    } else {
                                        NowColors.SurfacePrimary
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = icons[index],
                                contentDescription = null,
                                tint = if (selected) NowColors.Blue600 else NowColors.Ink500,
                                modifier = Modifier.size(21.dp),
                            )
                        }
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) NowColors.Blue600 else NowColors.Ink500,
                        )
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = NowColors.InfoSoft,
            border = BorderStroke(1.dp, NowColors.InfoBorder),
        ) {
            Text(
                text = "This is a harness smoke test. Production components will replace lab-only previews as each UI surface is finalized.",
                style = NowType.BodyS,
                color = NowColors.InfoText,
                modifier = Modifier.padding(NowSpacing.Space3),
            )
        }

        Button(
            onClick = {
                selectedIndex = (selectedIndex + 1) % labels.size
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Outlined.Science,
                contentDescription = null,
            )
            Spacer(Modifier.size(8.dp))
            Text("Cycle selected tab")
        }
    }
}
