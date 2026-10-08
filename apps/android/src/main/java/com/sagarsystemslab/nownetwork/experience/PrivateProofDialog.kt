package com.sagarsystemslab.nownetwork.experience

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.sagarsystemslab.nownetwork.designsystem.*
import java.io.ByteArrayOutputStream
import java.net.URL
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject

@Composable
fun PrivateProofDialog(proof: JsonObject, onRetry: () -> Unit = {}, onClose: () -> Unit) {
    var error by remember(proof) { mutableStateOf<String?>(null) }
    val bitmap by produceState<android.graphics.Bitmap?>(null,proof) {
        if(!proof.flag("available")) return@produceState
        try {
            value=withContext(Dispatchers.IO) {
                val url=URL(proof.text("url")); check(url.protocol=="https")
                val connection=url.openConnection().apply { connectTimeout=10_000; readTimeout=10_000; useCaches=false }
                val bytes=connection.getInputStream().use { input ->
                    val output=ByteArrayOutputStream(); val buffer=ByteArray(8192)
                    while(true) { val read=input.read(buffer); if(read<0) break; check(output.size()+read<=8*1024*1024); output.write(buffer,0,read) }; output.toByteArray()
                }
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                check(bounds.outWidth in 1..16000 && bounds.outHeight in 1..16000)
                val options=BitmapFactory.Options().apply { inSampleSize = proofImageSampleSize(bounds.outWidth, bounds.outHeight, 1200) }
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,options) ?: error("Unreadable proof")
            }
        } catch(cancel:CancellationException) {throw cancel} catch(_:Exception) {error="Photo could not load. Retry to request fresh access."}
    }
    AlertDialog(onDismissRequest=onClose,title={Text("Authorized evidence")},text={Column(Modifier.heightIn(max=500.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(proof.flag("loading")) CircularProgressIndicator(color=NowColors.Blue600)
        else if(!proof.flag("available")) { Text(proof.text("error").ifBlank { "No photo is available to this account." }); TextButton(onRetry) { Text("Retry preview") } }
        else if(error!=null) { Text(error!!); TextButton(onRetry) { Text("Retry photo") } }
        else if(bitmap!=null) { Image(bitmap!!.asImageBitmap(),"Submitted evidence photo",Modifier.fillMaxWidth().heightIn(max=420.dp),contentScale=ContentScale.Fit); Text("${if(proof.text("status") == "VERIFIED") "Verified" else "Submitted"} evidence · ${com.sagarsystemslab.nownetwork.feature.common.displayEventTime(proof.text("committed_at"))}",style=NowType.BodyS) }
        else CircularProgressIndicator(color=NowColors.Blue600)
        if(proof.flag("available") && proof.text("video_url").isNotBlank()) {
            com.sagarsystemslab.nownetwork.feature.common.EvidenceVideoPlayer(proof.text("video_url"))
        }
    }},confirmButton={TextButton(onClose) {Text("Done")}})
}
