package com.kayx.manager.ui.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kayx.manager.ui.theme.LocalPalette

@Composable
fun IosLargeTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier.padding(horizontal = 20.dp), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = LocalPalette.current.fg)
}

/** iOS "inset grouped" section: small caps header, rounded group, footer note. */
@Composable
fun IosSection(
    header: String? = null,
    footer: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.fillMaxWidth()) {
        if (header != null) Text(header.uppercase(), Modifier.padding(start = 32.dp, bottom = 7.dp), fontSize = 13.sp, color = p.muted)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(shape)
                .background(p.groupFill).border(0.5.dp, p.separator, shape),
            content = content
        )
        if (footer != null) Text(footer, Modifier.padding(start = 32.dp, end = 32.dp, top = 7.dp), fontSize = 13.sp, color = p.muted)
    }
}

@Composable
fun IosDivider(inset: Dp = 16.dp) {
    Box(Modifier.padding(start = inset).fillMaxWidth().height(0.5.dp).background(LocalPalette.current.separator))
}

@Composable
fun IosRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconBg: Color = Color(0xFF007AFF),
    titleColor: Color = Color.Unspecified,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    val p = LocalPalette.current
    val base = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    Row(
        base.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Box(Modifier.size(29.dp).clip(RoundedCornerShape(7.dp)).background(iconBg), Alignment.Center) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(13.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, color = if (titleColor == Color.Unspecified) p.fg else titleColor)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = p.muted)
        }
        if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
        if (chevron) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.ChevronRight, null, tint = p.muted.copy(alpha = .7f), modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun IosSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Switch(
        checked, onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF34C759), checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color.White, uncheckedTrackColor = p.switchOff, uncheckedBorderColor = Color.Transparent
        )
    )
}

@Composable
fun IosSwitchRow(
    title: String, checked: Boolean, onChange: (Boolean) -> Unit,
    icon: ImageVector? = null, iconBg: Color = Color(0xFF007AFF), subtitle: String? = null
) {
    IosRow(title, subtitle = subtitle, icon = icon, iconBg = iconBg, onClick = { onChange(!checked) }) {
        IosSwitch(checked, onChange)
    }
}

/** iOS segmented control. */
@Composable
fun IosSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(p.segTrack).padding(2.dp)) {
        options.forEachIndexed { i, label ->
            val sel = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(7.dp))
                    .background(if (sel) p.segThumb else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 7.dp),
                Alignment.Center
            ) {
                Text(label, fontSize = 13.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium, color = p.fg, maxLines = 1)
            }
        }
    }
}

@Composable
fun IosSegmentedRow(title: String, options: List<String>, selected: Int, subtitle: String? = null, onSelect: (Int) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp)) {
        Text(title, fontSize = 17.sp, color = p.fg)
        if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = p.muted)
        Spacer(Modifier.height(9.dp))
        IosSegmented(options, selected, onSelect)
    }
}

/** iOS-style − / + stepper. */
@Composable
fun IosStepper(value: Int, onChange: (Int) -> Unit, range: IntRange) {
    val p = LocalPalette.current
    val canDown = value > range.first
    val canUp = value < range.last
    Row(Modifier.clip(RoundedCornerShape(8.dp)).background(p.segTrack), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.clickable(enabled = canDown) { onChange(value - 1) }.padding(horizontal = 14.dp, vertical = 6.dp)) {
            Icon(Icons.Default.Remove, null, tint = p.fg.copy(alpha = if (canDown) 1f else .3f), modifier = Modifier.size(18.dp))
        }
        Box(Modifier.width(1.dp).height(16.dp).background(p.separator))
        Box(Modifier.clickable(enabled = canUp) { onChange(value + 1) }.padding(horizontal = 14.dp, vertical = 6.dp)) {
            Icon(Icons.Default.Add, null, tint = p.fg.copy(alpha = if (canUp) 1f else .3f), modifier = Modifier.size(18.dp))
        }
    }
}
