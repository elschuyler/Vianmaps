package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Milestone 1.1 — Unified Sky Blue & White Palette
val SkyBluePrimary = Color(0xFF0284C7)      // Vibrant clear sky blue (primary accent)
val SkyBlueLight = Color(0xFFE0F2FE)        // Very soft sky blue tint (selected container)
val SkyBlueBorder = Color(0xFFBAE6FD)       // Soft sky blue border
val SkyBlueDim = Color(0xFF0369A1)          // Deeper sky blue for high-contrast text

// Surfaces & Backgrounds
val SurfaceWhite = Color(0xFFFFFFFF)        // Pure crisp white
val SurfaceWhiteTranslucent = Color(0xF7FFFFFF) // 97% white for floating pills/popups
val SurfaceOffWhite = Color(0xFFF8FAFC)     // Subtle slate tint for secondary containers
val SurfaceBorderLight = Color(0x260F172A)  // Crisp subtle dark border for white cards (15% slate)

// Typography & Content Colors (Dark on White)
val TextDarkPrimary = Color(0xFF0F172A)     // Dark charcoal for high readability
val TextDarkSecondary = Color(0xFF475569)   // Slate gray for secondary labels
val TextDarkMuted = Color(0xFF64748B)       // Muted slate gray for small timestamps/badges

// Functional Indicators (aligned with Sky Blue palette)
val IndicatorActive = Color(0xFF0284C7)     // Active GPS / Network / Compass
val IndicatorInactive = Color(0xFF94A3B8)   // Inactive state slate
val IndicatorAlert = Color(0xFFE11D48)      // Clear red for explicitly blocked network
