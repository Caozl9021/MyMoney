package io.github.mymoney.ui.design

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

/** A common transition drives every role. First composition and display-mode changes start settled. */
@Composable
fun animatedBrandRoles(theme: BrandTheme, dark: Boolean, reduceMotion: Boolean): BrandColorRoles {
    val state = remember(dark) { MutableTransitionState(theme) }
    state.targetState = theme
    val transition = rememberTransition(state, label = "brand-theme")
    val duration = BrandMotion.duration(reduceMotion)
    val spec = { tween<androidx.compose.ui.graphics.Color>(duration, easing = AbEasing.EaseOut) }
    val primary by transition.animateColor({ spec() }, label = "brand-primary") { BrandColors.roles(it, dark).primary }
    val onPrimary by transition.animateColor({ spec() }, label = "brand-on-primary") { BrandColors.roles(it, dark).onPrimary }
    val container by transition.animateColor({ spec() }, label = "brand-container") { BrandColors.roles(it, dark).container }
    val onContainer by transition.animateColor({ spec() }, label = "brand-on-container") { BrandColors.roles(it, dark).onContainer }
    val inverse by transition.animateColor({ spec() }, label = "brand-inverse") { BrandColors.roles(it, dark).inversePrimary }
    return BrandColors.readable(BrandColorRoles(primary, onPrimary, container, onContainer, inverse))
}
