package com.sagarsystemslab.nownetwork.feature.capture

import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.File

data class CapturedPhoto(
    val file: File,
    val captureStartedElapsedMs: Long,
    val captureCompletedElapsedMs: Long,
)

class EvidenceCameraController internal constructor(
    private val imageCapture: ImageCapture,
    private val executor: java.util.concurrent.Executor,
) {
    fun capture(
        file: File,
        onSuccess: (CapturedPhoto) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        file.parentFile?.mkdirs()
        val started = SystemClock.elapsedRealtime()
        val output = ImageCapture.OutputFileOptions.Builder(file).build()

        imageCapture.takePicture(
            output,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(
                    outputFileResults: ImageCapture.OutputFileResults,
                ) {
                    onSuccess(
                        CapturedPhoto(
                            file = file,
                            captureStartedElapsedMs = started,
                            captureCompletedElapsedMs = SystemClock.elapsedRealtime(),
                        ),
                    )
                }

                override fun onError(exception: ImageCaptureException) {
                    onError(exception)
                }
            },
        )
    }
}

@Composable
fun EvidenceCameraPreview(
    modifier: Modifier = Modifier,
    onControllerReady: (EvidenceCameraController) -> Unit,
    onCameraError: (Throwable) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(85)
            .build()
    }
    val controller = remember(imageCapture, executor) {
        EvidenceCameraController(imageCapture, executor)
    }

    DisposableEffect(lifecycleOwner, previewView, imageCapture) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null

        providerFuture.addListener(
            {
                try {
                    provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    provider?.unbindAll()
                    provider?.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture,
                    )
                    onControllerReady(controller)
                } catch (error: Throwable) {
                    onCameraError(error)
                }
            },
            executor,
        )

        onDispose {
            provider?.unbindAll()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier,
    )
}
