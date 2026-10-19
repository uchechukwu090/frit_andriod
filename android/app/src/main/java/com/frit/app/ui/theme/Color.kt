package com.frit.app.ui.theme

import androidx.compose.ui.graphics.Color

// FRIT Warm Editorial Palette — from brand prototype
val FritCream        = Color(0xFFEBE8E0) // Main background (parchment)
val FritSurface      = Color(0xFFF5F1EA) // Card/surface layer
val FritEspresso     = Color(0xFF362F2F) // Primary dark (text, icons, orb)
val FritEspressoDark = Color(0xFF332C2B) // Slightly deeper espresso variant
val FritStroke       = Color(0xFF362F2F).copy(alpha = 0.18f) // Default border
val FritStrokeStrong = Color(0xFF362F2F).copy(alpha = 0.35f) // Emphasis border

// Typography
val FritTextPrimary   = Color(0xFF362F2F) // Main text
val FritTextSecondary = Color(0xFF7A6A60) // Muted / labels
val FritTextMuted     = Color(0xFFA89E96) // Placeholder / hints

// Semantic
val FritPositive = Color(0xFF4A7C59) // Positive delta (forest green)
val FritNegative = Color(0xFF9C3A2F) // Negative delta (terracotta red)
val FritAccent   = Color(0xFF362F2F) // Primary accent (espresso)

// Legacy aliases — keep so other files compile without changes
val FritObsidian      = FritCream        // was dark bg → now cream bg
val FritDeepCharcoal  = FritSurface
val FritGlass         = FritSurface.copy(alpha = 0.9f)
val FritGlassStroke   = FritStroke
val FritDiamondStroke = FritStrokeStrong
val FritNebulaCyan    = FritEspresso     // was cyan → now espresso
val FritNebulaViolet  = FritEspressoDark
val FritNebulaSilver  = FritTextSecondary
val FritChrome        = FritEspresso
val FritBackground    = FritCream
val FritCard          = FritSurface
val FritPrimary       = FritEspresso
val FritSecondary     = FritTextSecondary
val FritGhostTerminal = FritEspresso.copy(alpha = 0.05f)
