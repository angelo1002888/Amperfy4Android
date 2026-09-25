/*
 * Amperfy4Android - an unofficial Android port of Amperfy
 * Copyright (c) 2026 angelo
 * Based on Amperfy for iOS, Copyright (c) 2019-2025 Maximilian Bauer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.amperfy.ui.components.swipe

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Controller to manage swipe state across multiple list items.
 */
class SwipeController {
    @OptIn(ExperimentalFoundationApi::class)
    private var currentOpenState: AnchoredDraggableState<DragAnchors>? = null

    // Reactive key of the item currently tracked as open.
    private val _currentOpenKey = mutableStateOf<Any?>(null)
    val currentOpenKey: Any? get() = _currentOpenKey.value

    // Track all items currently being programmatically closed.
    private val closingKeys = mutableSetOf<Any>()

    /**
     * Register a new swipe state when an item starts being swiped.
     * If another item is already open/opening/closing, close it first.
     */
    @OptIn(ExperimentalFoundationApi::class)
    fun onSwipeStart(
        key: Any,
        state: AnchoredDraggableState<DragAnchors>,
        scope: CoroutineScope
    ) {
        // Ignore stale callbacks from an item that is being programmatically closed.
        if (closingKeys.contains(key)) {
            return
        }

        val previousState = currentOpenState
        val previousKey = _currentOpenKey.value

        val previousIsNotCentered = previousState != null &&
            (previousState.currentValue != DragAnchors.Center || previousState.targetValue != DragAnchors.Center)

        if (previousKey != null && previousKey != key && previousState != null && previousIsNotCentered) {
            if (closingKeys.add(previousKey)) {
                scope.launch {
                    try {
                        previousState.animateTo(DragAnchors.Center)
                    } finally {
                        closingKeys.remove(previousKey)
                    }
                }
            }
        }

        // Update tracked state only when this item is actually not centered.
        if (state.currentValue != DragAnchors.Center || state.targetValue != DragAnchors.Center) {
            currentOpenState = state
            _currentOpenKey.value = key
        }
    }

    /**
     * Called when an item returns to center position.
     */
    @OptIn(ExperimentalFoundationApi::class)
    fun onSwipeEnd(key: Any) {
        if (_currentOpenKey.value == key) {
            currentOpenState = null
            _currentOpenKey.value = null
        }
    }

    /**
     * Close the currently open swipe item (if any).
     */
    @OptIn(ExperimentalFoundationApi::class)
    fun closeCurrentItem(scope: CoroutineScope) {
        val key = _currentOpenKey.value
        val state = currentOpenState

        if (key != null && state != null &&
            (state.currentValue != DragAnchors.Center || state.targetValue != DragAnchors.Center)
        ) {
            if (closingKeys.add(key)) {
                scope.launch {
                    try {
                        state.animateTo(DragAnchors.Center)
                    } finally {
                        closingKeys.remove(key)
                    }
                }
            }
        }

        currentOpenState = null
        _currentOpenKey.value = null
    }

    /**
     * Check if a specific item is currently open (reactive).
     */
    @Composable
    fun isItemOpenState(key: Any): Boolean {
        return _currentOpenKey.value == key
    }

    /**
     * Check if any item is currently open (reactive).
     */
    @Composable
    fun isAnyItemOpenState(): Boolean {
        return _currentOpenKey.value != null
    }

    /**
     * Clear the controller state (e.g., when the list is scrolled).
     */
    @OptIn(ExperimentalFoundationApi::class)
    fun reset(scope: CoroutineScope) {
        closeCurrentItem(scope)
    }
}

/**
 * Remember a SwipeController instance that survives recomposition.
 */
@Composable
fun rememberSwipeController(): SwipeController {
    return remember { SwipeController() }
}
