package com.example.autobook.ui.design

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.example.autobook.systems.LauncherIconChoice

/** A common transition drives every role. First composition and display-mode changes start settled. */
@Composable
fun animatedBrandRoles(choice: LauncherIconChoice, dark: Boolean, reduceMotion: Boolean): BrandColorRoles {
    val state = remember(dark) { MutableTransitionState(choice) }
    state.targetState = choice
    val transition = updateTransition(state, label = "brand-choice")
    val duration = BrandMotion.duration(reduceMotion)
    val primary by transition.animateColor({ tween(duration) }, label = "brand-primary") { BrandColors.roles(it, dark).primary }
    val onPrimary by transition.animateColor({ tween(duration) }, label = "brand-on-primary") { BrandColors.roles(it, dark).onPrimary }
    val container by transition.animateColor({ tween(duration) }, label = "brand-container") { BrandColors.roles(it, dark).container }
    val onContainer by transition.animateColor({ tween(duration) }, label = "brand-on-container") { BrandColors.roles(it, dark).onContainer }
    val inverse by transition.animateColor({ tween(duration) }, label = "brand-inverse") { BrandColors.roles(it, dark).inversePrimary }
    return BrandColors.readable(BrandColorRoles(primary, onPrimary, container, onContainer, inverse))
}
