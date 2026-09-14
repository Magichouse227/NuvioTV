package com.nuvio.tv.ui.screens.player

import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Bounded, identity-aware traversal for provider/Media3 Throwable graphs.
 *
 * Causes are visited before suppressed exceptions so the normal parser cause remains the most
 * relevant diagnostic. Suppressed exceptions are included because parsers can attach the useful
 * failure there. The identity set handles cycles and duplicate references; the node cap handles
 * hostile or unexpectedly large exception graphs.
 */
internal object ThrowableGraphTraversal {
    const val DEFAULT_MAX_NODES = 64

    fun walk(
        root: Throwable,
        maxNodes: Int = DEFAULT_MAX_NODES
    ): Sequence<Throwable> = sequence {
        val pending = ArrayDeque<Throwable>()
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        pending.addLast(root)
        val nodeLimit = maxNodes.coerceIn(0, DEFAULT_MAX_NODES)

        while (pending.isNotEmpty() && seen.size < nodeLimit) {
            val current = pending.removeFirst()
            if (!seen.add(current)) continue
            yield(current)

            current.cause?.let { pending.addLast(it) }
            for (suppressed in current.suppressed) {
                pending.addLast(suppressed)
            }
        }
    }
}