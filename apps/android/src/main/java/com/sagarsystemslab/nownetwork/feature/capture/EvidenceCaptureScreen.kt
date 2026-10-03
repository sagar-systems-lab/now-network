package com.sagarsystemslab.nownetwork.feature.capture

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sagarsystemslab.nownetwork.designsystem.NowColors
import com.sagarsystemslab.nownetwork.designsystem.NowNotice
import com.sagarsystemslab.nownetwork.designsystem.NowNoticeTone
import com.sagarsystemslab.nownetwork.designsystem.NowPrimaryButton
import com.sagarsystemslab.nownetwork.designsystem.NowSecondaryButton
import com.sagarsystemslab.nownetwork.designsystem.NowSpacing
import com.sagarsystemslab.nownetwork.designsystem.NowStatusChip
import com.sagarsystemslab.nownetwork.designsystem.NowStatusTone
import com.sagarsystemslab.nownetwork.designsystem.NowTextField
import com.sagarsystemslab.nownetwork.designsystem.NowType
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun EvidenceCaptureScreen(
    uiState: EvidenceCaptureUiState,
    onBack: () -> Unit,
    onBeginCapture: () -> Unit,
    onPermissionDenied: () -> Unit,
    onPhotoCaptured: (CapturedPhoto) -> Unit,
    onCameraError: () -> Unit,
    onAnswerChange: (String) -> Unit,
    onRefreshLocation: () -> Unit,
    onRecapture: () -> Unit,
    onSubmit: () -> Unit,
    onRetry: () -> Unit,
    onContinueVerification: () -> Unit,
) {
    val context = LocalContext.current
    var cameraController by remember { mutableStateOf<EvidenceCameraController?>(null) }

    LaunchedEffect(uiState.stage, uiState.evidenceId) {
        if (uiState.stage == EvidenceCaptureStage.CAMERA) {
            cameraController = null
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val cameraGranted = result[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
        val locationGranted = !uiState.locationRequired ||
            result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

        if (cameraGranted && locationGranted) {
            onBeginCapture()
        } else {
            onPermissionDenied()
        }
    }

    fun requestCapturePermissions() {
        val permissions = buildList {
            add(Manifest.permission.CAMERA)
            if (uiState.locationRequired) {
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        val alreadyGranted = permissions.all { permission ->
            ContextCompat.checkSelfPermission(
                context,
                permission,
            ) == PackageManager.PERMISSION_GRANTED
        }

        if (alreadyGranted) {
            onBeginCapture()
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = NowSpacing.PageHorizontal,
                vertical = NowSpacing.Space2,
            )
            .testTag("screen-evidence-capture"),
        verticalArrangement = Arrangement.spacedBy(NowSpacing.Space4),
    ) {
        EvidenceTopBar(onBack = onBack)

        if (uiState.question.isNotBlank()) {
            EvidenceTaskCard(uiState)
        }

        when (uiState.stage) {
            EvidenceCaptureStage.LOADING -> {
                EvidenceProcessCard(
                    title = "Loading proof requirements",
                    body = "Checking your active claim and any capture already saved on this device.",
                    progress = true,
                )
            }

            EvidenceCaptureStage.READY -> {
                PermissionCard(uiState)
                NowPrimaryButton(
                    text = "Start fresh capture",
                    onClick = ::requestCapturePermissions,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            EvidenceCaptureStage.PREPARING -> {
                EvidenceProcessCard(
                    title = "Preparing secure capture",
                    body = uiState.message
                        ?: "Binding a one-time evidence challenge before the camera opens.",
                    progress = true,
                )
            }

            EvidenceCaptureStage.CAMERA -> {
                val path = uiState.localFilePath
                if (path == null) {
                    NowNotice(
                        title = "Camera unavailable",
                        body = "The local evidence file could not be prepared safely.",
                        tone = NowNoticeTone.ERROR,
                    )
                } else {
                    CameraCaptureSurface(
                        localFilePath = path,
                        controller = cameraController,
                        onControllerReady = { cameraController = it },
                        onCameraError = onCameraError,
                        onPhotoCaptured = onPhotoCaptured,
                        locationRequired = uiState.locationRequired,
                        locationReady = uiState.locationReady,
                        onRefreshLocation = onRefreshLocation,
                    )
                }
            }

            EvidenceCaptureStage.PROCESSING -> {
                EvidenceProcessCard(
                    title = "Securing local evidence",
                    body = uiState.message
                        ?: "Computing integrity metadata before the evidence can be reviewed.",
                    progress = true,
                )
            }

            EvidenceCaptureStage.REVIEW -> {
                EvidenceReview(
                    uiState = uiState,
                    onAnswerChange = onAnswerChange,
                    onRefreshLocation = onRefreshLocation,
                    onRecapture = onRecapture,
                    onSubmit = onSubmit,
                )
            }

            EvidenceCaptureStage.SUBMITTING -> {
                EvidenceProcessCard(
                    title = "Submitting evidence",
                    body = uiState.message
                        ?: "Uploading the captured bytes and committing their integrity metadata.",
                    progress = true,
                )
                NowNotice(
                    body = "Keep this screen open if possible. If connectivity drops, the saved evidence can resume safely.",
                    tone = NowNoticeTone.NEUTRAL,
                )
            }

            EvidenceCaptureStage.QUEUED -> {
                NowNotice(
                    title = "Upload paused · saved on this device",
                    body = uiState.message
                        ?: "Your capture is preserved locally. NOW will not ask you to recapture unless the evidence expires.",
                    tone = NowNoticeTone.WARNING,
                )
                NowSecondaryButton(
                    text = "Check submission",
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            EvidenceCaptureStage.SUBMITTED -> {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("evidence-submitted"),
                    shape = MaterialTheme.shapes.large,
                    color = NowColors.LiveSoft,
                    border = BorderStroke(1.dp, NowColors.LiveBorder),
                ) {
                    Column(
                        modifier = Modifier.padding(NowSpacing.Space4),
                        verticalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
                    ) {
                        Surface(
                            modifier = Modifier.size(46.dp),
                            shape = MaterialTheme.shapes.extraLarge,
                            color = NowColors.SurfacePrimary,
                            border = BorderStroke(1.dp, NowColors.LiveBorder),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.CheckCircle,
                                    contentDescription = null,
                                    tint = NowColors.LiveText,
                                    modifier = Modifier.size(25.dp),
                                )
                            }
                        }
                        Text(
                            text = "Evidence submitted",
                            style = NowType.TitleL,
                            color = NowColors.LiveText,
                        )
                        Text(
                            text = uiState.message
                                ?: "Your proof is committed and ready for verification.",
                            style = NowType.BodyM,
                            color = NowColors.Ink700,
                        )
                    }
                }

                NowPrimaryButton(
                    text = "Check verification",
                    onClick = onContinueVerification,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            EvidenceCaptureStage.EXPIRED -> {
                NowNotice(
                    title = "Capture window expired",
                    body = uiState.message
                        ?: "This evidence can no longer be submitted for the active claim.",
                    tone = NowNoticeTone.ERROR,
                )
            }

            EvidenceCaptureStage.ERROR -> {
                NowNotice(
                    title = "Capture needs attention",
                    body = uiState.message
                        ?: "Evidence capture could not continue safely.",
                    tone = NowNoticeTone.ERROR,
                )
                NowPrimaryButton(
                    text = "Try again",
                    onClick = ::requestCapturePermissions,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(NowSpacing.Space3))
    }
}

@Composable
private fun EvidenceTopBar(
    onBack: () -> Unit,
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
            text = "Capture evidence",
            style = NowType.TitleM,
            color = NowColors.Ink950,
            modifier = Modifier.semantics {
                heading()
            },
        )
    }
}

@Composable
private fun EvidenceTaskCard(
    uiState: EvidenceCaptureUiState,
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
            Text(
                text = uiState.question,
                style = NowType.TitleM,
                color = NowColors.Ink950,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
            ) {
                if (uiState.mediaRequired) {
                    NowStatusChip(
                        label = "PHOTO",
                        tone = NowStatusTone.INFO,
                        accessibilityLabel = "Fresh photo required",
                    )
                }
                if (uiState.locationRequired) {
                    NowStatusChip(
                        label = "LOCATION",
                        tone = NowStatusTone.INFO,
                        accessibilityLabel = "Location match required",
                    )
                }
            }

            uiState.expiresAt?.let { deadline ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Schedule,
                        contentDescription = null,
                        tint = NowColors.AgingText,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Evidence deadline · " + readableDeadline(deadline),
                        style = NowType.BodyS,
                        color = NowColors.AgingText,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    uiState: EvidenceCaptureUiState,
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
            RequirementRow(
                icon = Icons.Outlined.CameraAlt,
                title = "Camera",
                body = "Used only to capture fresh proof for this claim.",
            )
            if (uiState.locationRequired) {
                RequirementRow(
                    icon = Icons.Outlined.LocationOn,
                    title = "Precise location",
                    body = "Collected only during this verification and bound to the capture.",
                )
            }
            NowNotice(
                body = "Permissions are requested only when you start this task, not at app launch.",
                tone = NowNoticeTone.NEUTRAL,
            )
        }
    }
}

@Composable
private fun CameraCaptureSurface(
    localFilePath: String,
    controller: EvidenceCameraController?,
    onControllerReady: (EvidenceCameraController) -> Unit,
    onCameraError: () -> Unit,
    onPhotoCaptured: (CapturedPhoto) -> Unit,
    locationRequired: Boolean,
    locationReady: Boolean,
    onRefreshLocation: () -> Unit,
) {
    var zoom by remember { mutableStateOf(1f) }
    var torch by remember { mutableStateOf(false) }
    var controlError by remember { mutableStateOf<String?>(null) }
    Column(
        verticalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f),
            shape = MaterialTheme.shapes.large,
            color = Color(0xFF050816),
        ) {
            EvidenceCameraPreview(
                modifier = Modifier.fillMaxSize(),
                onControllerReady = onControllerReady,
                onCameraError = { _ ->
                    onCameraError()
                },
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            controller?.zoomRatios?.forEach { ratio ->
                androidx.compose.material3.FilterChip(zoom == ratio, { zoom = ratio; controller.zoom(ratio) { controlError = "Zoom is unavailable for this camera." } }, label = { Text("${ratio}×") })
            }
            Spacer(Modifier.weight(1f))
            if (controller?.hasFlash == true) androidx.compose.material3.FilterChip(torch, { torch = !torch; controller.torch(torch) { torch = false; controlError = "Flash is unavailable." } }, label = { Text(if(torch) "Flash on" else "Flash off") })
        }
        controlError?.let { Text(it, style = NowType.BodyS, color = NowColors.AgingText) }
        if (locationRequired) {
            NowNotice(
                body = if (locationReady) {
                    "Precise location is ready and will be bound to this capture."
                } else {
                    "Precise location is required before this evidence can be submitted."
                },
                tone = if (locationReady) {
                    NowNoticeTone.SUCCESS
                } else {
                    NowNoticeTone.WARNING
                },
            )

            if (!locationReady) {
                NowSecondaryButton(
                    text = "Refresh precise location",
                    onClick = onRefreshLocation,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        NowPrimaryButton(
            text = "Capture now",
            onClick = {
                controller?.capture(
                    file = File(localFilePath),
                    onSuccess = onPhotoCaptured,
                    onError = { onCameraError() },
                )
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = controller != null,
        )
    }
}

@Composable
private fun EvidenceReview(
    uiState: EvidenceCaptureUiState,
    onAnswerChange: (String) -> Unit,
    onRefreshLocation: () -> Unit,
    onRecapture: () -> Unit,
    onSubmit: () -> Unit,
) {
    uiState.localFilePath?.let { path ->
        EvidencePreview(path)
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(NowSpacing.Space1),
        ) {
            Text(
                text = "Review your evidence",
                style = NowType.TitleM,
                color = NowColors.Ink950,
            )
            Text(
                text = "Make sure the capture is clear, recent, and answers the live question.",
                style = NowType.BodyM,
                color = NowColors.Ink600,
            )
        }

        when (uiState.stateType) {
            "NUMERIC" -> {
                NowTextField(
                    value = uiState.answer,
                    onValueChange = onAnswerChange,
                    label = "Answer",
                    supportingText = "Enter the value visible in your fresh observation.",
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                    visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                )
            }

            "BINARY" -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
                ) {
                    if (uiState.answer == "YES") {
                        NowPrimaryButton(
                            text = "Yes",
                            onClick = { onAnswerChange("YES") },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        NowSecondaryButton(
                            text = "Yes",
                            onClick = { onAnswerChange("YES") },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    if (uiState.answer == "NO") {
                        NowPrimaryButton(
                            text = "No",
                            onClick = { onAnswerChange("NO") },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        NowSecondaryButton(
                            text = "No",
                            onClick = { onAnswerChange("NO") },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            "VISUAL" -> {
                NowNotice(
                    body = "The fresh image is the visual answer for this task.",
                    tone = NowNoticeTone.INFO,
                )
            }
        }

        if (uiState.locationRequired) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = NowColors.SurfacePrimary,
                border = BorderStroke(1.dp, NowColors.BorderSubtle),
            ) {
                Row(
                    modifier = Modifier.padding(NowSpacing.Space3),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
                ) {
                    Icon(
                        imageVector = if (uiState.locationReady) {
                            Icons.Outlined.Verified
                        } else {
                            Icons.Outlined.LocationOn
                        },
                        contentDescription = null,
                        tint = if (uiState.locationReady) {
                            NowColors.LiveText
                        } else {
                            NowColors.AgingText
                        },
                        modifier = Modifier.size(22.dp),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = if (uiState.locationReady) {
                                "Location ready"
                            } else {
                                "Location required"
                            },
                            style = NowType.TitleS,
                            color = NowColors.Ink950,
                        )
                        Text(
                            text = if (uiState.locationReady) {
                                uiState.locationSampleCount.toString() +
                                    " capture-bound location sample(s) saved."
                            } else {
                                "Get a fresh precise location fix before submitting."
                            },
                            style = NowType.BodyS,
                            color = NowColors.Ink500,
                        )
                    }
                }
            }

            NowSecondaryButton(
                text = "Refresh precise location",
                onClick = onRefreshLocation,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        uiState.message?.let { message ->
            NowNotice(
                body = message,
                tone = NowNoticeTone.NEUTRAL,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
        ) {
            NowSecondaryButton(
                text = "Recapture",
                onClick = onRecapture,
                modifier = Modifier.weight(1f),
            )
            NowPrimaryButton(
                text = "Submit evidence",
                onClick = onSubmit,
                modifier = Modifier
                    .weight(1f)
                    .testTag("submit-evidence"),
                enabled = uiState.canSubmit,
            )
        }
    }
}

@Composable
private fun EvidencePreview(path: String) {
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        key1 = path,
    ) {
        value = withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (
                bounds.outWidth / sample > 1_200 ||
                bounds.outHeight / sample > 1_200
            ) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
            }
            BitmapFactory.decodeFile(path, options)?.asImageBitmap()
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f),
        shape = MaterialTheme.shapes.large,
        color = Color(0xFF050816),
        border = BorderStroke(1.dp, NowColors.BorderSubtle),
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "Captured evidence",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = NowColors.Blue600,
                )
            }
        }
    }
}

@Composable
private fun RequirementRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = MaterialTheme.shapes.medium,
            color = NowColors.Blue50,
            border = BorderStroke(1.dp, NowColors.Blue100),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = NowColors.Blue600,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = NowType.LabelL,
                color = NowColors.Ink800,
            )
            Text(
                text = body,
                style = NowType.BodyS,
                color = NowColors.Ink500,
            )
        }
    }
}

@Composable
private fun EvidenceProcessCard(
    title: String,
    body: String,
    progress: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = NowColors.SurfacePrimary,
        border = BorderStroke(1.dp, NowColors.BorderSubtle),
    ) {
        Row(
            modifier = Modifier.padding(NowSpacing.Space4),
            horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
            verticalAlignment = Alignment.Top,
        ) {
            if (progress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = NowColors.Blue600,
                    trackColor = NowColors.Blue100,
                    strokeWidth = 3.dp,
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space1),
            ) {
                Text(
                    text = title,
                    style = NowType.TitleS,
                    color = NowColors.Ink950,
                )
                Text(
                    text = body,
                    style = NowType.BodyM,
                    color = NowColors.Ink600,
                )
            }
        }
    }
}

private fun readableDeadline(value: String): String =
    value
        .replace("T", " ")
        .removeSuffix("Z") + " UTC"
