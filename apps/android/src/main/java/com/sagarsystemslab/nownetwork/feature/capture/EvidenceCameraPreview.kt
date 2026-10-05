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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
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
    internal var camera: androidx.camera.core.Camera? = null
    val hasFlash: Boolean get() = camera?.cameraInfo?.hasFlashUnit() == true
    val zoomRatios: List<Float> get() {
        val state = camera?.cameraInfo?.zoomState?.value ?: return emptyList()
        return listOf(.5f, 1f, 2f).filter { it >= state.minZoomRatio && it <= state.maxZoomRatio }
    }
    fun zoom(ratio: Float, onError: () -> Unit) {
        val future = camera?.cameraControl?.setZoomRatio(ratio) ?: return
        future.addListener({ runCatching { future.get() }.onFailure { onError() } }, executor)
    }
    fun torch(enabled: Boolean, onError: () -> Unit) {
        val future = camera?.cameraControl?.enableTorch(enabled) ?: return
        future.addListener({ runCatching { future.get() }.onFailure { onError() } }, executor)
    }
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
    val latestReady by rememberUpdatedState(onControllerReady)
    val latestError by rememberUpdatedState(onCameraError)
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    val previewView = remember {
        PreviewView(context).apply {
            // TextureView follows Compose clipping, scrolling and route transitions.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
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
        var boundPreview: Preview? = null
        var disposed = false

        providerFuture.addListener(
            {
                try {
                    if (disposed) return@addListener
                    provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    boundPreview = preview
                    controller.camera = provider?.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture,
                    )
                    latestReady(controller)
                } catch (error: Throwable) {
                    latestError(error)
                }
            },
            executor,
        )

        onDispose {
            disposed = true
            controller.camera = null
            boundPreview?.let { provider?.unbind(it, imageCapture) }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier,
    )
}
