package com.example.mealplanner

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Two corner radii for the whole app, so a button next to a card never mixes
 * 12/14/16/18/20/22 dp. [MaterialTheme] uses [card] as its medium shape, so
 * Cards that do not set a shape match explicitly shaped ones.
 */
internal object AppShapes {
    /** Buttons, selectors, fields, thumbnails and other small controls. */
    val control = RoundedCornerShape(12.dp)

    /** Cards, panels, list rows, image headers and drag outlines around them. */
    val card = RoundedCornerShape(16.dp)
}

/** Shared screen rhythm; every list screen starts from these values. */
internal object AppLayout {
    val screenPadding = 16.dp

    /** Gap between cards/rows in a list screen. */
    val itemSpacing = 10.dp

    /** Gap between groups on settings and form pages. */
    val sectionSpacing = 16.dp
}

/** Title of a top-level tab. */
@Composable
internal fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold
    )
}

/** Heading of a sub-page or a major section inside one. */
@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
}
