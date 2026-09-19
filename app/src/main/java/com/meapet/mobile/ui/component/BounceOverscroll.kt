package com.meapet.mobile.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.launch

/**
 * iOS 风格弹性回弹（橡胶带）效果。
 *
 * 参考实现思路来自 sinasamaki《Overscroll animations in Jetpack Compose》：
 * - 拖动阶段：原始越界量线性累加，显示时经缓动曲线变换 → "越拉越难拉"的橡胶带手感；
 * - 松手阶段：以 fling 剩余速度作为初速，用柔和且无振荡的弹簧干净复位。
 *
 * 用法：用 [BounceOverscrollBox] 包裹任意可滚动内容即可。
 */
private val RubberBandEasing: Easing = CubicBezierEasing(0.5f, 0.5f, 1.0f, 0.25f)

/** 松手回弹使用的弹簧：低刚度 + 默认无振荡（NoBouncy），一次到位不晃动。 */
private val SettleSpring = spring<Float>(stiffness = Spring.StiffnessLow)

/** 拖动阶段位移与容器尺寸的比例上限（越大可拉动距离越长）。 */
private const val OVERSHOOT_SIZE_RATIO = 1.5f

@Composable
fun BounceOverscrollBox(
    modifier: Modifier = Modifier,
    orientation: Orientation = Orientation.Vertical,
    content: @Composable () -> Unit
) {
    val overscroll = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var offset by remember { mutableFloatStateOf(0f) }
    var containerLength by remember { mutableFloatStateOf(1f) }

    // 原始越界量 → 缓动变换 → 实际位移（橡胶带效果）
    LaunchedEffect(Unit) {
        snapshotFlow { overscroll.value }.collect { raw ->
            offset = RubberBandEasing.transform(raw / (containerLength * OVERSHOOT_SIZE_RATIO)) * containerLength
        }
    }

    val connection = remember {
        object : NestedScrollConnection {
            // 列表已到边界后仍未消费的滚动 → 累加越界量（用户拖动，需即时响应）
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                val delta = if (orientation == Orientation.Vertical) available.y else available.x
                if (delta != 0f) {
                    scope.launch { overscroll.snapTo(calculateOverscroll(overscroll.value, delta)) }
                }
                return Offset.Zero
            }

            // 已处于越界状态时，优先把滚动用于复位，避免列表提前滚动引发视觉跳变
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = if (orientation == Orientation.Vertical) available.y else available.x
                if (overscroll.value != 0f && source != NestedScrollSource.SideEffect) {
                    scope.launch { overscroll.snapTo(calculateOverscroll(overscroll.value, delta)) }
                    return available
                }
                return Offset.Zero
            }

            // 惯性滑动到达边界后：以剩余速度作为初速回弹复位
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val velocity = if (orientation == Orientation.Vertical) available.y else available.x
                overscroll.animateTo(
                    targetValue = 0f,
                    initialVelocity = velocity,
                    animationSpec = SettleSpring
                )
                return available
            }
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { size ->
                containerLength = (if (orientation == Orientation.Vertical) size.height else size.width)
                    .toFloat()
                    .coerceAtLeast(1f)
            }
            .graphicsLayer {
                if (orientation == Orientation.Vertical) translationY = offset else translationX = offset
            }
            .nestedScroll(connection)
    ) {
        content()
    }
}

/** 累加越界量，并防止正负翻转（避免动画跳到列表另一端）。 */
private fun calculateOverscroll(previous: Float, delta: Float): Float {
    val newValue = previous + delta
    return when {
        previous > 0 -> newValue.coerceAtLeast(0f)
        previous < 0 -> newValue.coerceAtMost(0f)
        else -> newValue
    }
}
