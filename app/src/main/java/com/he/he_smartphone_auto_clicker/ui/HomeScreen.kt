package com.he.he_smartphone_auto_clicker.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.annotation.StringRes
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.he.he_smartphone_auto_clicker.R
import com.he.he_smartphone_auto_clicker.i18n.I18n
import com.he.he_smartphone_auto_clicker.ui.theme.HeBg
import com.he.he_smartphone_auto_clicker.ui.theme.HeBlack
import com.he.he_smartphone_auto_clicker.ui.theme.HeGold
import com.he.he_smartphone_auto_clicker.ui.theme.HeGoldSoft
import com.he.he_smartphone_auto_clicker.ui.theme.HeGray
import com.he.he_smartphone_auto_clicker.ui.theme.HeSurface
import com.he.he_smartphone_auto_clicker.ui.theme.HeSurfaceUp

@Composable
private fun trStr(@StringRes id: Int): String {
    val ctx = LocalContext.current
    val code = I18n.currentCode()
    return remember(code, id) { I18n.t(ctx, id) }
}

@Composable
fun HomeScreen(
    onSettings: () -> Unit,
    onCreate: () -> Unit,
    onLoad: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HeBg)
            .statusBarsPadding()
    ) {
        Text(
            text = trStr(R.string.app_name),
            color = HeGold,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.3.sp,
            modifier = Modifier
                .fillMaxWidth()
                .background(HeBlack)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 26.dp)
        ) {
            DefaultsCard(onClick = onSettings)
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(78.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CreateButton(
                    modifier = Modifier
                        .weight(1.65f)
                        .fillMaxHeight(),
                    onClick = onCreate
                )
                LoadButton(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    onClick = onLoad
                )
            }
        }
    }
}

@Composable
private fun DefaultsCard(onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(HeSurface)
            .border(1.dp, HeGold.copy(alpha = 0.7f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.ic_settings),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            colorFilter = ColorFilter.tint(HeGold)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = trStr(R.string.hub_defaults_title),
                color = HeGold,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.9.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = trStr(R.string.hub_defaults_sub),
                color = HeGray,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(text = "›", color = HeGoldSoft, fontSize = 22.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
private fun CreateButton(modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val shimmer = rememberInfiniteTransition(label = "createShimmer")
    val shift by shimmer.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1700, easing = LinearEasing, delayMillis = 2600),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerShift"
    )
    Row(
        modifier = modifier
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(HeGold, HeGoldSoft)))
            .goldShimmer(shift)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Image(
            painter = painterResource(R.drawable.ic_add),
            contentDescription = null,
            modifier = Modifier.size(30.dp),
            colorFilter = ColorFilter.tint(HeBlack)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = trStr(R.string.create_new),
                color = HeBlack,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = trStr(R.string.create_new_sub_short),
                color = HeBlack.copy(alpha = 0.62f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LoadButton(modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(HeSurfaceUp)
            .border(1.dp, HeGold, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Image(
            painter = painterResource(R.drawable.ic_box_open),
            contentDescription = null,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = trStr(R.string.load_preset),
                color = HeGold,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = trStr(R.string.load_preset_sub_short),
                color = HeGray,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun Modifier.goldShimmer(shift: Float): Modifier = drawWithContent {
    drawContent()
    val band = size.width * 0.34f
    val x = size.width * shift
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                Color.White.copy(alpha = 0.0f),
                Color.White.copy(alpha = 0.38f),
                Color.White.copy(alpha = 0.0f),
                Color.Transparent
            ),
            start = Offset(x - band, 0f),
            end = Offset(x + band, size.height)
        )
    )
}