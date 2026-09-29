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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sagarsystemslab.nownetwork.designsystem.NowColors
import com.sagarsystemslab.nownetwork.designsystem.NowSpacing
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
                vertical = NowSpacing.Space3,
            )
            .testTag("screen-evidence-capture"),
        verticalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                )
            }
            Text(
                text = "Capture evidence",
                style = NowType.TitleL,
                color = NowColors.Ink950,
            )
        }

        if (uiState.question.isNotBlank()) {
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
                        text = uiState.question,
                        style = NowType.TitleS,
                        color = NowColors.Ink950,
                    )
                    Text(
                        text = buildString {
                            append(uiState.stateType)
                            append(" proof")
                            if (uiState.mediaRequired) append(" · fresh photo")
                            if (uiState.locationRequired) append(" · precise location")
                        },
                        style = NowType.BodyS,
                        color = NowColors.Ink500,
                    )
                    uiState.expiresAt?.let {
                        Text(
                            text = "Capture window: $it",
                            style = NowType.BodyS,
                            color = NowColors.Ink500,
                        )
                    }
                }
            }
        }

        when (uiState.stage) {
            EvidenceCaptureStage.LOADING -> {
                EvidenceStatusCard(
                    title = "Loading proof requirements",
                    body = "Checking your active claim and any safely saved capture.",
                    progress = true,
                )
            }

            EvidenceCaptureStage.READY -> {
                EvidenceStatusCard(
                    title = "Fresh proof only",
                    body = if (uiState.locationRequired) {
                        "NOW uses your camera only for this refresh. Precise location is used only during this verification."
                    } else {
                        "NOW uses your camera only for the refresh you chose to verify."
                    },
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = ::requestCapturePermissions,
                ) {
                    Text("Start fresh capture")
                }
            }

            EvidenceCaptureStage.PREPARING -> {
                EvidenceStatusCard(
                    title = "Starting secure capture",
                    body = uiState.message
                        ?: "Binding a one-time challenge before the camera opens.",
                    progress = true,
                )
            }

            EvidenceCaptureStage.CAMERA -> {
                val path = uiState.localFilePath
                if (path == null) {
                    EvidenceStatusCard(
                        title = "Camera unavailable",
                        body = "The local evidence file was not prepared.",
                    )
                } else {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(3f / 4f),
                        shape = MaterialTheme.shapes.large,
                        color = NowColors.Ink950,
                    ) {
                        EvidenceCameraPreview(
                            modifier = Modifier.fillMaxSize(),
                            onControllerReady = { cameraController = it },
                            onCameraError = { onCameraError() },
                        )
                    }

                    uiState.message?.let { EvidenceInlineMessage(it) }

                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = cameraController != null,
                        onClick = {
                            val controller = cameraController ?: return@Button
                            controller.capture(
                                file = File(path),
                                onSuccess = onPhotoCaptured,
                                onError = { onCameraError() },
                            )
                        },
                    ) {
                        Text("Capture now")
                    }

                    if (uiState.locationRequired && !uiState.locationReady) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onRefreshLocation,
                        ) {
                            Text("Refresh precise location")
                        }
                    }
                }
            }

            EvidenceCaptureStage.PROCESSING -> {
                EvidenceStatusCard(
                    title = "Securing local evidence",
                    body = uiState.message
                        ?: "Computing integrity metadata before review.",
                    progress = true,
                )
            }

            EvidenceCaptureStage.REVIEW -> {
                uiState.localFilePath?.let { path ->
                    EvidencePreview(path)
                }

                EvidenceStatusCard(
                    title = "Review evidence",
                    body = "Confirm the image is usable and answer the live question before submission.",
                )

                when (uiState.stateType) {
                    "NUMERIC" -> {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = uiState.answer,
                            onValueChange = onAnswerChange,
                            label = { Text("Answer") },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                            ),
                            singleLine = true,
                        )
                    }

                    "BINARY" -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
                        ) {
                            if (uiState.answer == "YES") {
                                Button(
                                    modifier = Modifier.weight(1f),
                                    onClick = { onAnswerChange("YES") },
                                ) {
                                    Text("Yes")
                                }
                            } else {
                                OutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    onClick = { onAnswerChange("YES") },
                                ) {
                                    Text("Yes")
                                }
                            }

                            if (uiState.answer == "NO") {
                                Button(
                                    modifier = Modifier.weight(1f),
                                    onClick = { onAnswerChange("NO") },
                                ) {
                                    Text("No")
                                }
                            } else {
                                OutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    onClick = { onAnswerChange("NO") },
                                ) {
                                    Text("No")
                                }
                            }
                        }
                    }

                    "VISUAL" -> {
                        Text(
                            text = "The fresh image is the visual answer.",
                            style = NowType.BodyM,
                            color = NowColors.Ink600,
                        )
                    }
                }

                if (uiState.locationRequired) {
                    EvidenceStatusCard(
                        title = if (uiState.locationReady) {
                            "Location ready"
                        } else {
                            "Location required"
                        },
                        body = if (uiState.locationReady) {
                            uiState.locationSampleCount.toString() +
                                " capture-bound location sample(s) saved."
                        } else {
                            "Get a fresh precise location fix before submitting."
                        },
                    )
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onRefreshLocation,
                    ) {
                        Text("Refresh precise location")
                    }
                }

                uiState.message?.let { EvidenceInlineMessage(it) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onRecapture,
                    ) {
                        Text("Recapture")
                    }
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = uiState.canSubmit,
                        onClick = onSubmit,
                    ) {
                        Text("Submit evidence")
                    }
                }
            }

            EvidenceCaptureStage.SUBMITTING -> {
                EvidenceStatusCard(
                    title = "Submitting evidence",
                    body = uiState.message
                        ?: "Uploading the captured bytes and committing their hash.",
                    progress = true,
                )
            }

            EvidenceCaptureStage.QUEUED -> {
                EvidenceStatusCard(
                    title = "Evidence saved",
                    body = uiState.message
                        ?: "Submission will resume safely when connectivity is available.",
                )
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onRetry,
                ) {
                    Text("Check submission")
                }
            }

            EvidenceCaptureStage.SUBMITTED -> {
                EvidenceStatusCard(
                    title = "Evidence submitted",
                    body = buildString {
                        append(uiState.message ?: "Proof is committed.")
                        uiState.nextStep?.let {
                            append(" Next: ")
                            append(it.lowercase().replace('_', ' '))
                            append(".")
                        }
                    },
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onContinueVerification,
                ) {
                    Text("Check verification")
                }
            }

            EvidenceCaptureStage.EXPIRED -> {
                EvidenceStatusCard(
                    title = "Capture window expired",
                    body = uiState.message
                        ?: "This proof can no longer be submitted for the active claim.",
                )
            }

            EvidenceCaptureStage.ERROR -> {
                EvidenceStatusCard(
                    title = "Capture needs attention",
                    body = uiState.message
                        ?: "Evidence capture could not continue safely.",
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = ::requestCapturePermissions,
                ) {
                    Text("Try again")
                }
            }
        }

        Spacer(Modifier.height(NowSpacing.Space3))
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
        color = NowColors.SurfacePrimary,
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
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun EvidenceStatusCard(
    title: String,
    body: String,
    progress: Boolean = false,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = NowColors.SurfacePrimary,
        border = BorderStroke(1.dp, NowColors.BorderSubtle),
    ) {
        Row(
            modifier = Modifier.padding(NowSpacing.Space4),
            horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
            verticalAlignment = Alignment.Top,
        ) {
            if (progress) {
                CircularProgressIndicator()
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

@Composable
private fun EvidenceInlineMessage(message: String) {
    Text(
        text = message,
        style = NowType.BodyS,
        color = NowColors.Ink600,
    )
}
