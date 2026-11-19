package app.openmusic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.openmusic.core.LyricsTimeline
import app.openmusic.core.ParsedLyrics

/** Only renders lyrics read from the playing file's own metadata; never remote data. */
@Composable
internal fun EmbeddedLyricsPanel(
    lyrics: ParsedLyrics,
    positionMs: Long,
    modifier: Modifier = Modifier,
) {
    val lines = lyrics.timedLines
    val selected = LyricsTimeline.activeIndex(lines, positionMs)
    val listState = rememberLazyListState()
    LaunchedEffect(selected, lines) {
        if (selected >= 0) listState.animateScrollToItem((selected - 1).coerceAtLeast(0))
    }
    Column(
        modifier.background(MusicPalette.elevated, RoundedCornerShape(18.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "TESTO DAL FILE LOCALE",
            style = MaterialTheme.typography.labelSmall,
            color = MusicPalette.muted,
        )
        Spacer(Modifier.height(12.dp))
        if (lines.isEmpty()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    lyrics.plainText,
                    color = MusicPalette.text,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(lines) { index, line ->
                    Text(
                        line.text,
                        color = if (index == selected) MusicPalette.primary
                            else MusicPalette.muted,
                        fontWeight = if (index == selected) FontWeight.Bold
                            else FontWeight.Normal,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
