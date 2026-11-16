package io.github.nyzeep.orchestella.ui.oe3.components

import androidx.compose.ui.graphics.Color

/** ARGB role → Compose color, kept in one place so primitives read uniformly. */
internal fun Int.oe3Color(): Color = Color(this)
