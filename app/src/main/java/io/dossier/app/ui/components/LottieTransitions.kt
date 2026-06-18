package io.dossier.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import io.dossier.app.ui.theme.NeuralTheme
import kotlinx.coroutines.delay

/**
 * Lottie transition animations for the dossier UI. Four hand-authored minimal
 * amber loops tagged by intent:
 *  - compute     → processing / scan
 *  - investigate → analysis / discovery
 *  - search      → query / lookup
 *  - web         → network / report
 *
 * The JSON assets live in app/src/main/assets/. They're engineer-authored
 * geometric loops (not motion-designer illustrations) — clean, on-theme, and
 * loop seamlessly. Swap the JSON files to upgrade visuals without code changes.
 */
object LottieTags {
    const val COMPUTE = "compute"
    const val INVESTIGATE = "investigate"
    const val SEARCH = "search"
    const val WEB = "web"
}

/**
 * Plays a Lottie asset forever — for loading/analysis states.
 * @param tag one of [LottieTags]; resolves to "<tag>.json" in assets.
 */
@Composable
fun LottieLoop(
    tag: String,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp
) {
    val composition by rememberLottieComposition(
        LottieCompositionSpec.Asset("$tag.json")
    )
    LottieAnimation(
        composition = composition,
        iterations = LottieConstants.IterateForever,
        modifier = modifier.size(size),
        isPlaying = true
    )
}

/**
 * A full-screen transition overlay that plays a tagged Lottie once, then fades
 * out and calls [onFinished]. Used between screen navigations. Brief (~800ms)
 * so it feels lively without slowing the user down.
 *
 * @param activeTag the tag to play, or null to show nothing.
 * @param onFinished invoked once the one-shot play + fade-out completes.
 */
@Composable
fun LottieTransitionOverlay(
    activeTag: String?,
    onFinished: () -> Unit
) {
    AnimatedVisibility(
        visible = activeTag != null,
        enter = fadeIn(animationSpec = tween(150)),
        exit = fadeOut(animationSpec = tween(300))
    ) {
        if (activeTag == null) return@AnimatedVisibility

        val composition by rememberLottieComposition(
            LottieCompositionSpec.Asset("$activeTag.json")
        )
        var hasPlayed by remember(activeTag) { mutableStateOf(false) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NeuralTheme.BackgroundStart.copy(alpha = 0.92f)),
            contentAlignment = Alignment.Center
        ) {
            LottieAnimation(
                composition = composition,
                iterations = 1,
                isPlaying = true,
                modifier = Modifier.size(160.dp)
            )
        }

        // One-shot: after a brief play window, signal completion.
        LaunchedEffect(activeTag) {
            delay(850)
            hasPlayed = true
            onFinished()
        }
    }
}

/**
 * Picks a transition tag for a given destination route. Returns null for routes
 * that shouldn't trigger a transition overlay.
 */
fun transitionTagForRoute(route: String?): String? = when (route) {
    "identity" -> LottieTags.SEARCH
    "username_discovery" -> LottieTags.INVESTIGATE
    "scan" -> LottieTags.COMPUTE
    "report" -> LottieTags.WEB
    else -> null
}
