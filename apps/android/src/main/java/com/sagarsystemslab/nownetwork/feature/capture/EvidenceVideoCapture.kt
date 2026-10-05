package com.sagarsystemslab.nownetwork.feature.capture

import android.media.MediaMetadataRetriever
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sagarsystemslab.nownetwork.designsystem.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CapturedVideo(val file: File, val startedMs: Long, val completedMs: Long, val durationMs: Long)

@Composable
fun EvidenceVideoCapture(file: File, onCaptured: (CapturedVideo) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    val scope = rememberCoroutineScope()
    val latestCaptured by rememberUpdatedState(onCaptured)
    val preview = remember { PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FIT_CENTER
    } }
    val capture = remember { VideoCapture.withOutput(Recorder.Builder()
        .setQualitySelector(QualitySelector.fromOrderedList(listOf(Quality.HD, Quality.SD),
            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
        .setTargetVideoEncodingBitRate(2_000_000).build()) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var ready by remember { mutableStateOf(false) }
    var finishing by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var message by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var active by remember { mutableStateOf(true) }
    var interrupted by remember { mutableStateOf(false) }
    var started by remember { mutableLongStateOf(0L) }
    var revision by remember { mutableIntStateOf(0) }

    DisposableEffect(owner, capture, retry) {
        active = true
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var boundPreview: Preview? = null
        var disposed = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && recording != null) {
                interrupted = true
                recording?.stop()
            }
        }
        owner.lifecycle.addObserver(observer)
        future.addListener({
            if (!disposed) try {
                provider = future.get()
                boundPreview = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                provider?.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, boundPreview!!, capture)
                ready = true
            } catch (_: Exception) { message = "Video camera could not start. Retry keeps your photo." }
        }, executor)
        onDispose {
            disposed = true; active = false; ready = false; interrupted = true; revision++
            recording?.close(); recording = null
            owner.lifecycle.removeObserver(observer)
            boundPreview?.let { provider?.unbind(it, capture) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Show the whole area", style = NowType.TitleM)
        Text("Slowly pan across the place. Record 3–15 seconds; the photo stays saved. Audio is off.", style = NowType.BodyM)
        Surface(shape = NowShapes.large, color = androidx.compose.ui.graphics.Color.Black,
            modifier = Modifier.fillMaxWidth().height(260.dp)) {
            AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
        }
        if (recording != null || finishing) {
            LinearProgressIndicator(progress = { (elapsed / 15000f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text(if (finishing) "Saving your video…" else "${elapsed / 1000}s / 15s · ${if(elapsed < 3100) "Keep recording" else "Ready to finish"}", style = NowType.LabelL)
        }
        message?.let { Text(it, color = NowColors.AgingText, style = NowType.BodyS) }
        if (!ready) NowSecondaryButton("Retry video camera", { message = null; retry++ }, Modifier.fillMaxWidth())
        NowPrimaryButton(if (recording == null) "Record short video" else "Stop recording", {
            if (recording != null) {
                finishing = true; recording?.stop()
            } else {
                file.parentFile?.mkdirs(); file.delete()
                message = null; interrupted = false; elapsed = 0; started = SystemClock.elapsedRealtime()
                val recordingRevision = ++revision
                val output = FileOutputOptions.Builder(file).setDurationLimitMillis(14_900)
                    .setFileSizeLimit(6L * 1024 * 1024).build()
                try {
                    recording = capture.output.prepareRecording(context, output).start(executor) { event ->
                        if (active && revision == recordingRevision) when (event) {
                            is VideoRecordEvent.Start -> Unit
                            is VideoRecordEvent.Status -> elapsed = event.recordingStats.recordedDurationNanos / 1_000_000
                            is VideoRecordEvent.Finalize -> {
                                recording = null; finishing = true
                                val completed = SystemClock.elapsedRealtime()
                                val start = started
                                val wasInterrupted = interrupted
                                val validFinish = !event.hasError() || event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED ||
                                    event.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
                                scope.launch {
                                    val duration = withContext(Dispatchers.IO) {
                                        runCatching {
                                            val metadata = MediaMetadataRetriever()
                                            try { metadata.setDataSource(file.absolutePath)
                                                metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                                            } finally { metadata.release() }
                                        }.getOrNull()
                                    }
                                    finishing = false
                                    if (validFinish && !wasInterrupted && duration != null && duration in 3000L..15000L && file.length() in 1L..6L*1024*1024) {
                                        latestCaptured(CapturedVideo(file,start,completed,duration))
                                    } else {
                                        file.delete()
                                        message = if (wasInterrupted) "Recording was interrupted. Your photo is safe; record the clip again."
                                            else "The clip must be 3–15 seconds. Please record it again."
                                    }
                                }
                            }
                            else -> Unit
                        }
                    }
                } catch (_: Exception) { recording = null; finishing = false; message = "Recording failed. Retry keeps your photo." }
            }
        }, Modifier.fillMaxWidth(), enabled = ready && !finishing && (recording == null || elapsed >= 3100))
    }
}
