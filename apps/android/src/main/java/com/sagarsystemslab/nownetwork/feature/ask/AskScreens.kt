package com.sagarsystemslab.nownetwork.feature.ask

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.sagarsystemslab.nownetwork.designsystem.*
import com.sagarsystemslab.nownetwork.experience.number
import com.sagarsystemslab.nownetwork.experience.text
import com.sagarsystemslab.nownetwork.feature.common.*
import com.sagarsystemslab.nownetwork.model.GeoCenter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskComposerScreen(
    state: AskUiState,
    onName: (String)->Unit,
    onTarget: (GeoCenter)->Unit,
    onNeed: (AskNeed)->Unit,
    onStage: (AskStage)->Unit,
    onCoverage: ()->Unit,
    onPermissionDenied: ()->Unit,
    onResolve: ()->Unit,
    onBack: ()->Unit,
    onSearch: ()->Unit = {},
    onSelectPlace: (PlaceSuggestion)->Unit = {},
    onQuestion: (String)->Unit = {},
) {
    fun back() { when(state.stage) {
        AskStage.TARGET -> onBack()
        AskStage.NEED -> onStage(AskStage.TARGET)
        AskStage.PREVIEW -> onStage(AskStage.NEED)
    } }
    BackHandler { if(!state.busy) back() }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if(grants[Manifest.permission.ACCESS_FINE_LOCATION]==true) onCoverage() else onPermissionDenied()
    }
    val duration=nowMotionDuration(rememberNowMotionEnabled(),220)
    val focus = LocalFocusManager.current
    val scroll=androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(state.stage) { scroll.scrollToItem(0) }
    LazyColumn(Modifier.fillMaxSize().imePadding().testTag("ask-composer"),contentPadding=PaddingValues(16.dp),state=scroll,
        verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment=Alignment.CenterVertically) {
                IconButton({ back() },enabled=!state.busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back") }
                Column(Modifier.weight(1f)) {
                    Text("Ask about a place",style=NowType.TitleM,color=NowColors.Ink950)
                    Text("A clear question. Fresh proof.",style=NowType.BodyS,color=NowColors.Ink600)
                }
                LuminousIcon(Icons.Outlined.TravelExplore,Modifier.size(44.dp))
            }
            FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                AskStage.entries.forEachIndexed { index,stage ->
                    FilterChip(selected=state.stage==stage,onClick={ onStage(stage) },enabled=!state.busy &&
                        (stage==AskStage.TARGET || state.draft.targetReady && (stage!=AskStage.PREVIEW || state.draft.needReady)),
                        label={ Text("${index+1} · ${when(stage) { AskStage.TARGET -> "Place"; AskStage.NEED -> "Question"; AskStage.PREVIEW -> "Review" }}") })
                }
            }
        }
        when(state.stage) {
            AskStage.TARGET -> {
                item {
                    NowGlassCard(emphasized=true) {
                        NowSectionTitle("Where do you need an update?", "Search a place, street or full address.")
                        NowTextField(state.query,onName,"Place or address",Modifier.testTag("ask-place-search"),
                            supportingText="Search location or address",enabled=!state.busy,
                            keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),
                            keyboardActions=KeyboardActions(onSearch={ onSearch() }))
                        if(state.searching) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("Finding matching places…",style=NowType.BodyS,color=NowColors.Ink600)
                        }
                        state.suggestions.forEachIndexed { index,place ->
                            if(index>0) HorizontalDivider()
                            ExperienceRow(place.name,place.address,Icons.Outlined.LocationOn,
                                onClick={ focus.clearFocus(); onSelectPlace(place) },tag="ask-place-result-$index",compact=false)
                        }
                        state.searchMessage?.let { Text(it,style=NowType.BodyS,color=NowColors.Ink600) }
                        if(!state.draft.targetReady && state.query.trim().length>=3 && !state.searching) {
                            TextButton(onSearch,Modifier.fillMaxWidth()) { Text(if(state.searchMessage!=null) "Retry address search" else "Search places") }
                        }
                    }
                }
                item {
                    LiveMapCard(state.mapCenter,listOfNotNull(state.draft.target?.let {
                        LiveMapPin("ask-target",state.draft.name.ifBlank { "Selected place" },it,"UNKNOWN")
                    }),onPin={},onSearchArea=onTarget,onLocateArea=onTarget,onMapTap=onTarget,
                        searchAreaLabel="Choose this pin",minimumPanMeters=0f,animateCenterChanges=true)
                }
                item {
                    NowGlassCard(Modifier.animateContentSize(tween(duration)),emphasized=true) {
                        NowSectionTitle(if(state.draft.targetReady) state.draft.name else "Choose an exact spot",
                            if(state.draft.targetReady) "This is where the contributor will capture fresh proof." else "Select a search result, or tap the map. You can ask about a place far away.")
                        Text(state.draft.target?.let { "Pin selected · %.5f, %.5f".format(java.util.Locale.ROOT,it.latitude,it.longitude) }
                            ?: "No place selected yet",style=NowType.BodyS,color=NowColors.Ink600)
                        NowPrimaryButton("Choose what to ask",{ onStage(AskStage.NEED) },Modifier.fillMaxWidth(),enabled=state.draft.targetReady)
                    }
                }
            }
            AskStage.NEED -> {
                item { NowSectionTitle("What do you need to know?",state.draft.name) }
                AskNeed.entries.forEach { need -> item {
                    NowGlassCard(emphasized=state.draft.need==need) {
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            LuminousIcon(when(need) { AskNeed.PARKING -> Icons.Outlined.LocalParking; AskNeed.GATE -> Icons.Outlined.MeetingRoom; AskNeed.VISUAL -> Icons.Outlined.PhotoCamera; AskNeed.OTHER -> Icons.Outlined.QuestionAnswer },Modifier.size(44.dp))
                            Column(Modifier.weight(1f)) {
                                Text(need.title,style=NowType.TitleS,color=NowColors.Ink950)
                                Text(need.question,style=NowType.BodyS,color=NowColors.Ink600)
                            }
                            RadioButton(selected=state.draft.need==need,onClick={ onNeed(need) })
                        }
                        TextButton({ onNeed(need) },Modifier.fillMaxWidth()) { Text(if(state.draft.need==need) "Selected" else "Choose ${need.title.lowercase()}") }
                    }
                } }
                if(state.draft.need==AskNeed.OTHER) item {
                    NowGlassCard(emphasized=true) {
                        NowTextField(state.draft.customQuestion,onQuestion,"Your question",Modifier.testTag("ask-custom-question"),
                            supportingText="Ask something visible at this place, e.g. How long is the queue at the main entrance?",singleLine=false)
                        Text("${state.draft.customQuestion.length}/200 · A fresh photo, short video and on-site answer are required.",style=NowType.BodyS,color=NowColors.Ink600)
                    }
                }
                item { NowPrimaryButton("Preview request",{ focus.clearFocus(); onStage(AskStage.PREVIEW) },Modifier.fillMaxWidth(),enabled=state.draft.needReady) }
            }
            AskStage.PREVIEW -> {
                item {
                    NowGlassCard(emphasized=true) {
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            LuminousIcon(Icons.Outlined.FactCheck,Modifier.size(48.dp))
                            Column(Modifier.weight(1f)) { Text(state.draft.name,style=NowType.TitleM,color=NowColors.Ink950)
                                Text(state.draft.need?.title.orEmpty(),style=NowType.BodyS,color=NowColors.Ink600) }
                        }
                        Text(state.draft.question,style=NowType.TitleS,color=NowColors.Ink950)
                        HorizontalDivider()
                        Text("Fresh photo + short video + on-site location",style=NowType.BodyM,color=NowColors.Ink950)
                        Text("The contributor captures a photo and 3–15 second video at your pin. Verification and payout follow the usual request process.",style=NowType.BodyS,color=NowColors.Ink600)
                    }
                }
                item {
                    NowGlassCard(Modifier.animateContentSize(tween(duration))) {
                        Text("Live contributor coverage",style=NowType.TitleS,color=NowColors.Ink950)
                        val coverage=state.coverage
                        Text(when(coverage?.text("status")) {
                            "AVAILABLE" -> "${coverage.number("active_contributors")} available to cover this place"
                            "NO_COVERAGE" -> "No eligible contributors available right now"
                            else -> "Coverage hasn't been checked"
                        },style=NowType.BodyM,color=NowColors.Ink950)
                        Text("Contributors within 100 m of you are excluded in every direction. Only a recent, opted-in location counts. Coverage can change.",style=NowType.BodyS,color=NowColors.Ink600)
                        NowSecondaryButton(if(state.checking) "Checking coverage…" else "Check coverage",{
                            permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))
                        },Modifier.fillMaxWidth(),enabled=!state.checking && !state.busy)
                        Text("Your location only filters coverage; it doesn't move the target pin.",style=NowType.BodyS,color=NowColors.Ink600)
                    }
                }
                item {
                    NowPrimaryButton(if(state.busy) "Preparing your request…" else "Continue to reward",onResolve,Modifier.fillMaxWidth(),enabled=!state.busy && !state.checking)
                    Text("Choose the reward next. Nothing is charged here. An active request opens its existing state.",Modifier.padding(top=8.dp),style=NowType.BodyS,color=NowColors.Ink600)
                }
            }
        }
        state.notice?.let { message -> item { NowNotice(message) } }
    }
}

@Composable
fun ContributorAvailabilityCard(state: AvailabilityUiState,onEnable: ()->Unit,onDisable: ()->Unit,onPermissionDenied: ()->Unit) {
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if(grants[Manifest.permission.ACCESS_FINE_LOCATION]==true) onEnable() else onPermissionDenied()
    }
    NowGlassCard(Modifier.testTag("contributor-availability"),emphasized=state.status==AvailabilityStatus.AVAILABLE) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            LuminousIcon(Icons.Outlined.Radar,Modifier.size(42.dp))
            Column(Modifier.weight(1f)) {
                Text("Available nearby",style=NowType.TitleS,color=NowColors.Ink950)
                Text(when(state.status) {
                    AvailabilityStatus.OFF -> "Off · choose when you're ready"
                    AvailabilityStatus.LOCATING -> "Getting your current location…"
                    AvailabilityStatus.AVAILABLE -> "On · covering places within 2 km"
                    AvailabilityStatus.LOW_ACCURACY -> "Location needs better accuracy"
                    AvailabilityStatus.PERMISSION_REQUIRED -> "Precise location permission needed"
                    AvailabilityStatus.OFFLINE -> "Connection or location unavailable"
                },style=NowType.BodyS,color=NowColors.Ink600)
            }
            Switch(state.enabled,{ enabled -> if(enabled) permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)) else onDisable() })
        }
        Text("Share temporary coverage while the app is open. Turns off in the background. Your exact position is never shown to requesters.",style=NowType.BodyS,color=NowColors.Ink600)
    }
}
