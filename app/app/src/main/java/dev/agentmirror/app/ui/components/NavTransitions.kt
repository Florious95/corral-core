package dev.agentmirror.app.ui.components

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import dev.agentmirror.app.ui.theme.Motion

/**
 * 页面转场。配合 AnimatedContent 使用，⛔ 不引入 navigation-compose。
 *
 *   AnimatedContent(
 *       targetState = route,
 *       transitionSpec = { navTransition(direction) },
 *       label = "route",
 *   ) { r -> when (r) { ... } }
 *
 * direction 由你那边根据「进入下一层 / 返回 / 同层切 tab」给出。
 */
enum class NavDirection { Push, Pop, FadeThrough }

@OptIn(ExperimentalAnimationApi::class)
fun <S> AnimatedContentTransitionScope<S>.navTransition(direction: NavDirection): ContentTransform =
    when (direction) {
        // 进入下一层：新页面从右侧 28% 滑入 + 淡入
        NavDirection.Push -> (
            slideInHorizontally(
                animationSpec = tween(Motion.pushEnter, easing = Motion.emphasized),
                initialOffsetX = { (it * Motion.pushOffsetFraction).toInt() },
            ) + fadeIn(tween(Motion.pushEnter, easing = Motion.emphasized))
        ) togetherWith (
            slideOutHorizontally(
                animationSpec = tween(Motion.pushEnter, easing = Motion.emphasized),
                targetOffsetX = { -(it * 0.08f).toInt() },
            ) + fadeOut(tween(Motion.pushEnter / 2, easing = Motion.emphasized))
        )

        // 返回：反向，260ms，比进入更快 —— 回退不需要重新建立语境
        NavDirection.Pop -> (
            slideInHorizontally(
                animationSpec = tween(Motion.popEnter, easing = Motion.emphasized),
                initialOffsetX = { -(it * Motion.popOffsetFraction).toInt() },
            ) + fadeIn(tween(Motion.popEnter, easing = Motion.emphasized))
        ) togetherWith (
            slideOutHorizontally(
                animationSpec = tween(Motion.popEnter, easing = Motion.emphasized),
                targetOffsetX = { (it * 0.2f).toInt() },
            ) + fadeOut(tween(Motion.popEnter / 2, easing = Motion.emphasized))
        )

        // 同层 tab 切换：fade through —— 旧内容先淡出 90ms，新内容淡入 210ms + 上移 8dp + 0.985→1
        NavDirection.FadeThrough -> (
            fadeIn(
                animationSpec = tween(
                    durationMillis = (Motion.fadeThrough * (1f - Motion.fadeThroughOutFraction)).toInt(),
                    delayMillis = (Motion.fadeThrough * Motion.fadeThroughOutFraction).toInt(),
                    easing = Motion.emphasized,
                )
            ) + scaleIn(
                initialScale = Motion.fadeThroughScaleFrom,
                animationSpec = tween(
                    durationMillis = (Motion.fadeThrough * (1f - Motion.fadeThroughOutFraction)).toInt(),
                    delayMillis = (Motion.fadeThrough * Motion.fadeThroughOutFraction).toInt(),
                    easing = Motion.emphasized,
                ),
            )
        ) togetherWith fadeOut(
            tween(
                durationMillis = (Motion.fadeThrough * Motion.fadeThroughOutFraction).toInt(),
                easing = Motion.emphasized,
            )
        )
    }
