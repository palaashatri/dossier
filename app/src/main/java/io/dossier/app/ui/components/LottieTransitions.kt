package io.dossier.app.ui.components

import android.provider.Settings
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import io.dossier.app.ui.theme.NeuralTheme
import kotlinx.coroutines.delay

object LottieTags {
    const val COMPUTE = "compute"
    const val INVESTIGATE = "investigate"
    const val SEARCH = "search"
    const val WEB = "web"
}

@Composable
private fun animationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) > 0f
    }
}

/** Decorative loading animation; hidden from accessibility traversal. */
@Composable
fun LottieLoop(
    tag: String,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp
) {
    val enabled = animationsEnabled()
    val composition by rememberLottieComposition(LottieCompositionSpec.Asset("$tag.json"))
    LottieAnimation(
        composition = composition,
        iterations = if (enabled) LottieConstants.IterateForever else 1,
        modifier = modifier.size(size).clearAndSetSemantics { },
        isPlaying = enabled
    )
}

/**
 * Brief decorative route transition. It is skipped entirely when Android's
 * animator scale is disabled and otherwise never blocks the task for 850ms.
 */
@Composable
fun LottieTransitionOverlay(
    activeTag: String?,
    onFinished: () -> Unit
) {
    val enabled = animationsEnabled()

    LaunchedEffect(activeTag, enabled) {
        if (activeTag != null) {
            if (enabled) delay(360)
            onFinished()
        }
    }

    AnimatedVisibility(
        visible = activeTag != null && enabled,
        enter = fadeIn(animationSpec = tween(90)),
        exit = fadeOut(animationSpec = tween(140))
    ) {
        val tag = activeTag ?: return@AnimatedVisibility
        val composition by rememberLottieComposition(LottieCompositionSpec.Asset("$tag.json"))
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NeuralTheme.BackgroundStart.copy(alpha = 0.9f))
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center
        ) {
            LottieAnimation(
                composition = composition,
                iterations = 1,
                isPlaying = true,
                modifier = Modifier.size(140.dp)
            )
        }
    }
}

fun transitionTagForRoute(route: String?): String? = when (route) {
    "identity" -> LottieTags.SEARCH
    "username_discovery" -> LottieTags.INVESTIGATE
    "scan" -> LottieTags.COMPUTE
    "report" -> LottieTags.WEB
    else -> null
}
