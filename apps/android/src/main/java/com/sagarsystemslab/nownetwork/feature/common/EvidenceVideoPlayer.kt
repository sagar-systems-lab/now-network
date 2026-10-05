package com.sagarsystemslab.nownetwork.feature.common

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sagarsystemslab.nownetwork.designsystem.*

@Composable
fun EvidenceVideoPlayer(source: String) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var playing by remember(source) { mutableStateOf(false) }
    var prepared by remember(source) { mutableStateOf(false) }
    var message by remember(source) { mutableStateOf<String?>(null) }
    val video = remember(source) { VideoView(context).apply {
        setMediaController(MediaController(context).also { it.setAnchorView(this) })
        setOnCompletionListener { playing = false }
        setOnErrorListener { _, _, _ -> playing=false; prepared=false; message="Video expired or unavailable. Reopen the proof to retry."; true }
    } }
    DisposableEffect(video,owner) {
        val observer = LifecycleEventObserver { _,event ->
            if(event == Lifecycle.Event.ON_STOP) { video.pause(); playing=false }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); video.stopPlayback() }
    }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Short video proof",style=NowType.TitleS)
        AndroidView(factory={video},Modifier.fillMaxWidth().height(220.dp))
        NowSecondaryButton(if(playing) "Pause video" else "Play video", {
            if(playing) { video.pause(); playing=false }
            else {
                val uri=Uri.parse(source)
                if(uri.scheme !in listOf("file","https")) message="Video is unavailable."
                else {
                    message=null; playing=true
                    if(prepared) video.start()
                    else {
                        video.setOnPreparedListener {
                            prepared=true; it.setVolume(0f,0f)
                            if(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) video.start() else playing=false
                        }
                        video.setVideoURI(uri)
                    }
                }
            }
        },Modifier.fillMaxWidth())
        message?.let { Text(it,style=NowType.BodyS,color=NowColors.AgingText) }
    }
}
