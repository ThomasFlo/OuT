package com.homestock.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/**
 * Vertical list whose rows can be reordered by long-pressing a drag handle.
 *
 * The reorder gesture is captured by the modifier handed to the caller via
 * [rowContent]; attach it to a drag-handle icon so the rest of the row stays
 * tappable (rename / delete / toggle…).
 *
 * Behavior:
 * * The list is mirrored into an internal mutable state while the user drags.
 *   Visual swaps happen live, on a half-height threshold, so the user sees
 *   the items rearrange in real time.
 * * On gesture end (or cancel) [onReorder] is called once with the new order;
 *   the caller is expected to persist it (typically via the server's
 *   bulk-reorder endpoint).
 * * If [items] is updated externally between drags (e.g. WebSocket refresh),
 *   the internal state resyncs.
 *
 * Backed by a regular [Column] rather than LazyColumn: items must all be
 * composed to know their heights for the threshold-based swap logic. Fine for
 * lists of dozens; do not use for thousands.
 */
@Composable
fun <T : Any> ReorderableColumn(
    items: List<T>,
    key: (T) -> Long,
    onReorder: (List<T>) -> Unit,
    modifier: Modifier = Modifier,
    rowContent: @Composable (item: T, dragHandle: Modifier) -> Unit,
) {
    var localItems by remember { mutableStateOf(items) }
    var draggedKey by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    val itemHeights = remember { mutableStateMapOf<Long, Int>() }

    // Resync with the source list whenever it changes, but only if we're not
    // mid-drag — otherwise an incoming WebSocket refresh would yank the items
    // out from under the user's finger.
    LaunchedEffect(items) {
        if (draggedKey == null) localItems = items
    }

    Column(modifier) {
        localItems.forEach { item ->
            val itemKey = key(item)
            val isDragged = draggedKey == itemKey
            Box(
                Modifier
                    .onSizeChanged { itemHeights[itemKey] = it.height }
                    .offset { IntOffset(0, if (isDragged) dragOffset.roundToInt() else 0) }
                    .zIndex(if (isDragged) 1f else 0f),
            ) {
                val handleModifier = Modifier.pointerInput(itemKey) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            draggedKey = itemKey
                            dragOffset = 0f
                        },
                        onDragEnd = {
                            draggedKey = null
                            dragOffset = 0f
                            onReorder(localItems)
                        },
                        onDragCancel = {
                            draggedKey = null
                            dragOffset = 0f
                            onReorder(localItems)
                        },
                        onDrag = { change, drag ->
                            change.consume()
                            dragOffset += drag.y
                            val currentIdx = localItems.indexOfFirst { key(it) == itemKey }
                            if (currentIdx < 0) return@detectDragGesturesAfterLongPress

                            // Swap downward — threshold is half the next item's height,
                            // and we subtract that height from the offset so the dragged
                            // item visually stays put across the swap.
                            if (dragOffset > 0 && currentIdx + 1 < localItems.size) {
                                val nextKey = key(localItems[currentIdx + 1])
                                val nextHeight = itemHeights[nextKey] ?: 0
                                if (nextHeight > 0 && dragOffset > nextHeight / 2f) {
                                    val swapped = localItems.toMutableList()
                                    swapped.add(currentIdx + 1, swapped.removeAt(currentIdx))
                                    localItems = swapped
                                    dragOffset -= nextHeight
                                }
                            }
                            // Swap upward — symmetric.
                            if (dragOffset < 0 && currentIdx > 0) {
                                val prevKey = key(localItems[currentIdx - 1])
                                val prevHeight = itemHeights[prevKey] ?: 0
                                if (prevHeight > 0 && -dragOffset > prevHeight / 2f) {
                                    val swapped = localItems.toMutableList()
                                    swapped.add(currentIdx - 1, swapped.removeAt(currentIdx))
                                    localItems = swapped
                                    dragOffset += prevHeight
                                }
                            }
                        },
                    )
                }
                rowContent(item, handleModifier)
            }
        }
    }
}
