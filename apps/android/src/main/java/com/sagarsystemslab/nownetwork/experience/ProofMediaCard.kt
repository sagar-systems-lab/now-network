package com.sagarsystemslab.nownetwork.experience

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.sagarsystemslab.nownetwork.designsystem.*

/** A request's exact proof, rather than the latest photograph of the same state. */
@Composable
fun ProofMediaCard(evidenceId: String, hasVideo: Boolean, onOpen: () -> Unit, compact: Boolean = false) {
    val source = LocalEvidencePhotoSource.current
    val bitmap by produceState<Bitmap?>(null, source, evidenceId) {
        if (source != null) value = source.load(evidenceId)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(if (compact) 100.dp else 152.dp)
            .clip(androidx.compose.material3.MaterialTheme.shapes.medium)
            .background(NowColors.SurfacePrimary).clickable(onClick = onOpen), contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(bitmap!!.asImageBitmap(), "Submitted evidence photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.PhotoCamera, null, tint = NowColors.Blue600)
                Text("Open submitted photo", style = NowType.BodyS, color = NowColors.Ink600)
            }
        }
        NowSecondaryButton(if (hasVideo) "View photo & video" else "View photo", onOpen, Modifier.fillMaxWidth())
    }
}
