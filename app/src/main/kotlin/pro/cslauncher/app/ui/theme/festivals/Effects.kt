package pro.cslauncher.app.ui.theme.festivals

import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun FestivalEffects(
    effectTypes: List<FestivalEffectType>,
    modifier: Modifier = Modifier,
    isDark: Boolean = true
) {
    if (effectTypes.isEmpty()) return

    val engine = remember { FestivalEffectsEngine() }

    DisposableEffect(engine) {
        onDispose { engine.release() }
    }

    val tapsEnabled = effectTypes.any(FestivalEffectType::supportsTapBurst)
    DisposableEffect(tapsEnabled) {
        val unsubscribe = if (tapsEnabled) {
            FestivalTapObserver.subscribe { x, y ->
                engine.burstAt(x, y)
            }
        } else null
        onDispose {
            unsubscribe?.invoke()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextureView(context).apply {
                isOpaque = false
                engine.attach(this)
            }
        }
    )

    LaunchedEffect(effectTypes, isDark) {
        engine.applyConfig(effectTypes, isDark)
    }

    LaunchedEffect(Unit) {
        engine.setActive(true)
    }
}
