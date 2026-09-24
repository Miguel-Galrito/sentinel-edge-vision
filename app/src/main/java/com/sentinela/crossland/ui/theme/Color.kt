package com.sentinela.crossland.ui.theme

import androidx.compose.ui.graphics.Color

// Fundo OLED puro e superfícies escuras
val CyberBlack = Color(0xFF000000)
val CyberObsidian = Color(0xFF0A0A0C)
val CyberSurface = Color(0xFF12141A)
val CyberSurfaceVariant = Color(0xFF1A1D24)

// Cores Glassmorphism translúcidas
val GlassBackground = Color(0xB312141A)       // 70% opacidade
val GlassBackgroundDeep = Color(0xD90D0F14)   // 85% opacidade
val GlassBorder = Color(0x2BFFFFFF)           // Borda subtil 17% branco
val GlassBorderAccent = Color(0x5900E676)     // Borda verde neon subtil
val GlassBorderCyan = Color(0x5900E5FF)       // Borda ciano subtil

// Paleta Cyber-Sentinel de Status e Deteção
val CyberEmerald = Color(0xFF00E676)          // Ativo / Nominal / Matrícula validada
val CyberCyan = Color(0xFF00E5FF)             // Retículo / OCR / Telemetria
val CyberAmber = Color(0xFFFFB300)            // Movimento / Calibração ROI / Atenção
val CyberCrimson = Color(0xFFFF1744)          // Alerta Crítico / Alvo Detetado
val CyberBlue = Color(0xFF2979FF)             // Ações secundárias / Diagnóstico

// Cores Legadas compatíveis com módulos existentes
val SurveillanceDarkBg = CyberObsidian
val SurveillanceSurface = CyberSurface
val SurveillanceCard = Color(0xFF1E222D)
val SurveillanceAccent = CyberEmerald
val SurveillanceGreenLight = Color(0xFF69F0AE)

val AlarmRed = CyberCrimson
val AlarmRedDark = Color(0xFFB71C1C)
val AlarmRedPulse = Color(0xFFFF5252)
val AlarmAmber = CyberAmber

val OpelGrey = Color(0xFFCFD8DC)
val OpelBlack = Color(0xFF1E1E1E)

// Tipografia e contraste
val TextPureWhite = Color(0xFFFFFFFF)
val TextPrimary = Color(0xFFECEFF1)
val TextSecondary = Color(0xFF90A4AE)
val TextMuted = Color(0xFF546E7A)
