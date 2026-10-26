package com.difrancescogianmarco.arcore_flutter_plugin.models

import com.google.android.filament.Engine
import io.github.sceneview.node.Node

/**
 * Node created from a Flutter ArCoreNode.
 *
 * Holds the node transform and name, while the visual content (shape, model or image) is an unnamed
 * child [renderableNode], so it can be replaced without touching the node hierarchy.
 */
open class ArCoreNode(engine: Engine) : Node(engine) {

    var renderableNode: Node? = null
        set(value) {
            field?.let {
                removeChildNode(it)
                it.destroyRecursively()
            }
            field = value
            value?.let { addChildNode(it) }
        }
}

fun Node.destroyRecursively() {
    childNodes.toList().forEach { it.destroyRecursively() }
    runCatching { destroy() }
}
