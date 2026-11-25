package dev.fileagent.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Semantic colours for the whole app (from the design system's tokens). */
data class SuiColors(
    val page: Color,          // bg-weak-25
    val card: Color,          // bg-white-0
    val tile: Color,          // bg-weak-50
    val canvas: Color,        // bg-canvas
    val recess: Color,        // bg-recess
    val strong: Color,        // bg-strong-950 (user bubble)
    val onStrong: Color,      // text on strong / primary
    val primary: Color,       // primary-base (main button)
    val brand: Color,         // brand-base: small active marks only
    val brandLighter: Color,
    val brandDark: Color,
    val sidebar: Color,
    val sidebarItem: Color,
    val text: Color,          // text-strong-950
    val textSub: Color,       // text-sub-600
    val textSoft: Color,      // text-soft-400
    val stroke: Color,        // stroke-soft-200
    val accent: Color,        // focus ring, links
    val successLighter: Color,
    val successDark: Color,
    val errorLighter: Color,
    val errorDark: Color,
    val infoLighter: Color,
    val infoDark: Color,
)

// Light also uses plain neutral greys (no blue tint).
val SuiLight = SuiColors(
    page = Color(0xFFFAFAFA), card = Color(0xFFFFFFFF), tile = Color(0xFFF2F2F2),
    canvas = Color(0xFFF7F7F7), recess = Color(0xFFEFEFEF), strong = Color(0xFF1A1A1A),
    onStrong = Color(0xFFFFFFFF), primary = Color(0xFFD9570A), brand = Color(0xFFE5620F),
    brandLighter = Color(0xFFFFF0E6), brandDark = Color(0xFF8A3207), sidebar = Color(0xFFF4F4F4),
    sidebarItem = Color(0xFFFFFFFF), text = Color(0xFF1A1A1A), textSub = Color(0xFF5C5C5C),
    textSoft = Color(0xFF6B6B6B), stroke = Color(0xFFE5E5E5), accent = Color(0xFF335CFF),
    successLighter = Color(0xFFE3F7EC), successDark = Color(0xFF0B4627),
    errorLighter = Color(0xFFFFEBEC), errorDark = Color(0xFF8B1822),
    infoLighter = Color(0xFFEBF1FF), infoDark = Color(0xFF182F8B),
)

// Dark uses plain neutral greys (no blue tint), as chosen for the app.
val SuiDark = SuiColors(
    page = Color(0xFF121212), card = Color(0xFF1C1C1C), tile = Color(0xFF262626),
    canvas = Color(0xFF161616), recess = Color(0xFF0D0D0D), strong = Color(0xFFF2F2F2),
    onStrong = Color(0xFF161616), primary = Color(0xFFFF8A4C), brand = Color(0xFFFF8A4C),
    brandLighter = Color(0x24FF8A4C), brandDark = Color(0xFFFFB184), sidebar = Color(0xFF0F0F0F),
    sidebarItem = Color(0xFF222222), text = Color(0xFFF2F2F2), textSub = Color(0xFFB3B3B3),
    textSoft = Color(0xFF909090), stroke = Color(0xFF2E2E2E), accent = Color(0xFF6B95FF),
    successLighter = Color(0x1A1FC16B), successDark = Color(0xFF3EE089),
    errorLighter = Color(0x29FB3748), errorDark = Color(0xFFFF6875),
    infoLighter = Color(0x29476CFF), infoDark = Color(0xFF97BAFF),
)

val LocalSui = staticCompositionLocalOf { SuiLight }

/** Radii from the kit: controls 10, rows 12, cards 16, badges 4. */
object SuiShape {
    val control = RoundedCornerShape(12.dp)
    val row = RoundedCornerShape(12.dp)
    val card = RoundedCornerShape(20.dp)
    val badge = RoundedCornerShape(6.dp)
    val bubble = RoundedCornerShape(20.dp)
    val pill = RoundedCornerShape(50)
}

/** Type scale (Inter in the kit; system sans here). */
/**
 * Type scale, kept roomy for easy reading. Nothing the user reads is under 14sp,
 * and body lines get 24sp of height.
 */
object SuiType {
    val display = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp)
    val title = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
    val label = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
    val labelSm = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)
    val bodySm = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    val sub = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val mono = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, fontFamily = FontFamily.Monospace)
}

/** One spacing scale for every screen: 8pt grid. */
object SuiSpace {
    val page = 20.dp      // screen edge
    val section = 24.dp   // between sections
    val inner = 12.dp     // inside a section, between rows and chips
    val pad = 16.dp       // card padding
    val row = 56.dp       // list row height
    val tap = 48.dp       // button height
    val tapSm = 40.dp     // small button height
}

@Composable
fun SuiTheme(mode: String = "system", content: @Composable () -> Unit) {
    // The user can follow the phone, or force light or dark.
    val dark = when (mode) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    val c = if (dark) SuiDark else SuiLight
    // Material parts (switches, checkboxes, text fields) follow the kit: black primary, grey surfaces.
    val scheme = if (dark) darkColorScheme(
        primary = c.primary, onPrimary = c.onStrong, background = c.canvas, surface = c.card,
        onSurface = c.text, onSurfaceVariant = c.textSub, outline = c.stroke, outlineVariant = c.stroke,
        surfaceVariant = c.tile, error = c.errorDark, secondaryContainer = c.tile,
    ) else lightColorScheme(
        primary = c.primary, onPrimary = c.onStrong, background = c.canvas, surface = c.card,
        onSurface = c.text, onSurfaceVariant = c.textSub, outline = c.stroke, outlineVariant = c.stroke,
        surfaceVariant = c.tile, error = c.errorDark, secondaryContainer = c.tile,
    )
    CompositionLocalProvider(LocalSui provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

enum class SuiButtonKind { Primary, Secondary, Ghost }

/** sui-btn: one Primary per view, the rest Secondary or Ghost. Labels are verbs. */
@Composable
fun SuiButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: SuiButtonKind = SuiButtonKind.Secondary,
    enabled: Boolean = true,
) {
    val c = LocalSui.current
    val (bg, fg) = when (kind) {
        SuiButtonKind.Primary -> c.primary to c.onStrong
        SuiButtonKind.Secondary -> c.card to c.text
        SuiButtonKind.Ghost -> Color.Transparent to c.textSub
    }
    var m = modifier
    if (kind == SuiButtonKind.Primary && enabled) m = m.shadow(2.dp, SuiShape.control)
    m = m.clip(SuiShape.control).background(if (enabled) bg else c.tile)
    if (kind == SuiButtonKind.Secondary) m = m.border(1.dp, c.stroke, SuiShape.control)
    Box(
        m.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = SuiType.label, color = if (enabled) fg else c.textSoft)
    }
}

enum class BadgeTone { Soft, Success, Error, Info, Highlight }

/** sui-badge: tiny uppercase word. Status is always a word, never colour alone. */
@Composable
fun SuiBadge(text: String, tone: BadgeTone = BadgeTone.Soft) {
    val c = LocalSui.current
    val (bg, fg) = when (tone) {
        BadgeTone.Soft -> c.tile to c.textSub
        BadgeTone.Success -> c.successLighter to c.successDark
        BadgeTone.Error -> c.errorLighter to c.errorDark
        BadgeTone.Info -> c.infoLighter to c.infoDark
        BadgeTone.Highlight -> c.brandLighter to c.brandDark
    }
    Box(Modifier.clip(SuiShape.badge).background(bg).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text.uppercase(), style = SuiType.sub, color = fg)
    }
}

enum class SuiIconKind { Menu, Plus, Send, Close, Settings, Chat, Folder, Back, Trash, Chevron, Check, Phone, Server, Edit, Image, Doc, Video, Box, Speaker, Bolt, Lock, Undo }

/** Line icons: 20 unit grid, 1.6 stroke, round caps, current colour. */
@Composable
fun SuiIcon(kind: SuiIconKind, tint: Color, iconSize: Dp = 20.dp) {
    Canvas(Modifier.size(iconSize)) {
        val u = size.width / 20f
        val w = 1.6.dp.toPx()
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), strokeWidth = w, cap = StrokeCap.Round)
        val stroke = Stroke(width = w, cap = StrokeCap.Round)
        when (kind) {
            SuiIconKind.Menu -> { line(3f, 6f, 17f, 6f); line(3f, 10f, 17f, 10f); line(3f, 14f, 12f, 14f) }
            SuiIconKind.Plus -> { line(10f, 4f, 10f, 16f); line(4f, 10f, 16f, 10f) }
            SuiIconKind.Send -> { line(10f, 16f, 10f, 4f); line(5f, 9f, 10f, 4f); line(15f, 9f, 10f, 4f) }
            SuiIconKind.Close -> { line(5f, 5f, 15f, 15f); line(15f, 5f, 5f, 15f) }
            SuiIconKind.Back -> { line(16f, 10f, 4f, 10f); line(9f, 5f, 4f, 10f); line(9f, 15f, 4f, 10f) }
            SuiIconKind.Chevron -> { line(7f, 8f, 10f, 11f); line(13f, 8f, 10f, 11f) }
            SuiIconKind.Settings -> {
                drawCircle(tint, radius = 2.6f * u, style = stroke)
                drawCircle(tint, radius = 6.6f * u, style = stroke)
            }
            SuiIconKind.Chat -> {
                drawRoundRect(
                    tint, topLeft = Offset(3f * u, 4f * u),
                    size = androidx.compose.ui.geometry.Size(14f * u, 10f * u),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f * u), style = stroke,
                )
                line(7f, 14f, 6f, 17f)
            }
            SuiIconKind.Folder -> {
                line(3f, 6f, 8f, 6f); line(8f, 6f, 10f, 8f); line(10f, 8f, 17f, 8f)
                line(17f, 8f, 17f, 15f); line(17f, 15f, 3f, 15f); line(3f, 15f, 3f, 6f)
            }
            SuiIconKind.Speaker -> {
                line(3f, 8f, 6f, 8f); line(6f, 8f, 10f, 4f); line(10f, 4f, 10f, 16f); line(10f, 16f, 6f, 12f)
                line(6f, 12f, 3f, 12f); line(3f, 12f, 3f, 8f); line(13f, 7.5f, 14f, 10f); line(14f, 10f, 13f, 12.5f)
                line(15.5f, 5.5f, 17f, 10f); line(17f, 10f, 15.5f, 14.5f)
            }
            SuiIconKind.Bolt -> { line(11f, 2.5f, 5f, 11f); line(5f, 11f, 10f, 11f); line(10f, 11f, 9f, 17.5f); line(9f, 17.5f, 15f, 9f); line(15f, 9f, 10f, 9f); line(10f, 9f, 11f, 2.5f) }
            SuiIconKind.Lock -> {
                drawRoundRect(
                    tint, topLeft = Offset(4.5f * u, 9f * u),
                    size = androidx.compose.ui.geometry.Size(11f * u, 8f * u),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * u), style = stroke,
                )
                line(7f, 9f, 7f, 6.5f); line(13f, 9f, 13f, 6.5f)
                drawArc(tint, 180f, 180f, false, topLeft = Offset(7f * u, 3.5f * u),
                    size = androidx.compose.ui.geometry.Size(6f * u, 6f * u), style = stroke)
            }
            SuiIconKind.Undo -> {
                line(4f, 8f, 13f, 8f); line(4f, 8f, 7f, 5f); line(4f, 8f, 7f, 11f)
                drawArc(tint, -90f, 180f, false, topLeft = Offset(9f * u, 8f * u),
                    size = androidx.compose.ui.geometry.Size(8f * u, 8f * u), style = stroke)
                line(13f, 16f, 8f, 16f)
            }
            SuiIconKind.Image -> {
                drawRoundRect(
                    tint, topLeft = Offset(3f * u, 4f * u),
                    size = androidx.compose.ui.geometry.Size(14f * u, 12f * u),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * u), style = stroke,
                )
                line(3f, 13f, 7f, 9f); line(7f, 9f, 11f, 13f); line(11f, 13f, 13f, 11f); line(13f, 11f, 17f, 15f)
            }
            SuiIconKind.Doc -> {
                line(5f, 3f, 12f, 3f); line(12f, 3f, 15f, 6f); line(15f, 6f, 15f, 17f)
                line(15f, 17f, 5f, 17f); line(5f, 17f, 5f, 3f); line(8f, 10f, 12f, 10f); line(8f, 13f, 12f, 13f)
            }
            SuiIconKind.Video -> {
                drawRoundRect(
                    tint, topLeft = Offset(3f * u, 5f * u),
                    size = androidx.compose.ui.geometry.Size(14f * u, 10f * u),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * u), style = stroke,
                )
                line(9f, 8f, 12f, 10f); line(12f, 10f, 9f, 12f); line(9f, 12f, 9f, 8f)
            }
            SuiIconKind.Box -> {
                drawRoundRect(
                    tint, topLeft = Offset(4f * u, 3f * u),
                    size = androidx.compose.ui.geometry.Size(12f * u, 14f * u),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * u), style = stroke,
                )
                line(10f, 3f, 10f, 11f)
            }
            SuiIconKind.Check -> { line(4.5f, 10.5f, 8.5f, 14.5f); line(8.5f, 14.5f, 15.5f, 6f) }
            SuiIconKind.Phone -> {
                drawRoundRect(
                    tint, topLeft = Offset(6f * u, 3f * u),
                    size = androidx.compose.ui.geometry.Size(8f * u, 14f * u),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * u), style = stroke,
                )
                line(9f, 14.5f, 11f, 14.5f)
            }
            SuiIconKind.Server -> {
                drawRoundRect(
                    tint, topLeft = Offset(3.5f * u, 4f * u),
                    size = androidx.compose.ui.geometry.Size(13f * u, 5f * u),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f * u), style = stroke,
                )
                drawRoundRect(
                    tint, topLeft = Offset(3.5f * u, 11f * u),
                    size = androidx.compose.ui.geometry.Size(13f * u, 5f * u),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f * u), style = stroke,
                )
                line(6.5f, 6.5f, 7f, 6.5f); line(6.5f, 13.5f, 7f, 13.5f)
            }
            SuiIconKind.Edit -> { line(4f, 16f, 7f, 15.5f); line(7f, 15.5f, 15.5f, 7f); line(15.5f, 7f, 13f, 4.5f); line(13f, 4.5f, 4.5f, 13f); line(4.5f, 13f, 4f, 16f) }
            SuiIconKind.Trash -> {
                line(4f, 6f, 16f, 6f); line(8f, 6f, 8f, 4f); line(8f, 4f, 12f, 4f); line(12f, 4f, 12f, 6f)
                line(6f, 6f, 7f, 16f); line(7f, 16f, 13f, 16f); line(13f, 16f, 14f, 6f)
            }
        }
    }
}

/** 36dp icon button. Primary = the send button. */
@Composable
fun SuiIconButton(
    kind: SuiIconKind,
    onClick: () -> Unit,
    label: String,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val c = LocalSui.current
    val bg = when {
        !enabled && primary -> c.tile
        primary -> c.primary
        else -> Color.Transparent
    }
    val tint = when {
        !enabled -> c.textSoft
        primary -> c.onStrong
        else -> c.textSub
    }
    Box(
        Modifier.size(36.dp).clip(SuiShape.control).background(bg)
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { SuiIcon(kind, tint) }
}

/**
 * Rounded pill button used across the redesign. Primary = light on dark (the one main action),
 * Danger = soft red for destructive actions, otherwise outlined.
 */
@Composable
fun PillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    danger: Boolean = false,
    small: Boolean = false,
    enabled: Boolean = true,
) {
    val c = LocalSui.current
    val bg = when {
        !enabled -> c.tile
        primary -> c.primary
        danger -> c.errorLighter
        else -> Color.Transparent
    }
    val fg = when {
        !enabled -> c.textSoft
        primary -> c.onStrong
        danger -> c.errorDark
        else -> c.text
    }
    var m = modifier.height(if (small) SuiSpace.tapSm else SuiSpace.tap).clip(SuiShape.pill).background(bg)
    if (!primary && !danger && enabled) m = m.border(1.dp, c.stroke, SuiShape.pill)
    Row(
        m.clickable(enabled = enabled, onClick = onClick).padding(horizontal = if (small) 16.dp else 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        // One line, always: a label that does not fit shrinks a little rather than wrapping.
        Text(
            label, color = fg, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            style = if (small) SuiType.labelSm else SuiType.label.copy(fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium),
        )
    }
}

/** 40dp soft tile holding a Material icon, for list rows. */
@Composable
fun IconTile(icon: ImageVector, tint: Color? = null, bg: Color? = null, size: Dp = 40.dp) {
    val c = LocalSui.current
    Box(
        Modifier.size(size).clip(SuiShape.row).background(bg ?: c.tile),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = tint ?: c.textSub, modifier = Modifier.size(size * 0.5f)) }
}

/** Round tick used in lists: filled when on, outlined when off. */
@Composable
fun RoundTick(on: Boolean, some: Boolean = false) {
    val c = LocalSui.current
    Box(
        Modifier.size(28.dp).clip(CircleShape)
            .background(if (on) c.primary else Color.Transparent)
            .border(1.5.dp, if (on || some) c.primary else c.stroke, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Icon(Icons.Rounded.Check, contentDescription = null, tint = c.onStrong, modifier = Modifier.size(16.dp))
        else if (some) Box(Modifier.size(width = 12.dp, height = 2.dp).background(c.primary))
    }
}

/** A grouped list card: rows separated by hairlines inset past the icon. */
@Composable
fun ListCard(content: @Composable ColumnScope.() -> Unit) {
    val c = LocalSui.current
    Column(Modifier.fillMaxWidth().clip(SuiShape.card).background(c.card), content = content)
}

@Composable
fun ListDivider(inset: Dp = SuiSpace.pad + 40.dp + SuiSpace.inner) {
    val c = LocalSui.current
    Box(Modifier.fillMaxWidth().padding(start = inset).height(1.dp).background(c.stroke))
}

/** One list row: leading tile, title, optional meta, trailing content. 56dp minimum. */
@Composable
fun ListRow(
    title: String,
    meta: String? = null,
    leading: @Composable () -> Unit,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val c = LocalSui.current
    var m = Modifier.fillMaxWidth().heightIn(min = SuiSpace.row)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Row(
        m.padding(horizontal = SuiSpace.pad, vertical = SuiSpace.inner),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(SuiSpace.inner))
        Column(Modifier.weight(1f)) {
            Text(title, style = SuiType.label, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (meta != null) Text(meta, style = SuiType.bodySm, color = c.textSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(SuiSpace.inner))
        trailing()
    }
}

/** Small grey section heading above a card. */
@Composable
fun SectionLabel(text: String) {
    val c = LocalSui.current
    Text(text, style = SuiType.sub, color = c.textSoft, modifier = Modifier.padding(start = 4.dp, bottom = SuiSpace.inner))
}
