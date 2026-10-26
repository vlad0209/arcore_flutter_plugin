package com.difrancescogianmarco.arcore_flutter_plugin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.difrancescogianmarco.arcore_flutter_plugin.flutter_models.FlutterArCoreImage
import com.difrancescogianmarco.arcore_flutter_plugin.flutter_models.FlutterArCoreNode
import com.difrancescogianmarco.arcore_flutter_plugin.flutter_models.FlutterArCoreShape
import com.difrancescogianmarco.arcore_flutter_plugin.models.ArCoreNode
import com.google.android.filament.MaterialInstance
import io.github.sceneview.SceneView
import io.github.sceneview.material.setColor
import io.github.sceneview.material.setMetallic
import io.github.sceneview.material.setReflectance
import io.github.sceneview.material.setRoughness
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import io.github.sceneview.node.GeometryNode
import io.github.sceneview.node.ImageNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.texture.ImageTexture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException

typealias NodeHandler = (ArCoreNode?, Throwable?) -> Unit
typealias RenderableHandler = (Node?, Throwable?) -> Unit

class NodeFactory(
    private val context: Context,
    private val sceneView: SceneView,
    private val coroutineScope: CoroutineScope,
    private val debug: Boolean
) {

    private val engine get() = sceneView.engine
    private val materialLoader get() = sceneView.materialLoader
    private val modelLoader get() = sceneView.modelLoader

    fun makeNode(flutterNode: FlutterArCoreNode, handler: NodeHandler) {
        if (debug) {
            Log.i(TAG, flutterNode.toString())
        }
        makeRenderable(flutterNode) { renderable, throwable ->
            if (throwable != null) {
                handler(null, throwable)
                return@makeRenderable
            }
            val node = flutterNode.buildNode(engine)
            node.renderableNode = renderable
            handler(node, null)
        }
    }

    /**
     * Builds the visual content of [flutterNode]. The handler receives a `null` node without error
     * when the node has nothing to render.
     */
    fun makeRenderable(flutterNode: FlutterArCoreNode, handler: RenderableHandler) {
        try {
            if (flutterNode.dartType == "ArCoreReferenceNode") {
                val localObject = flutterNode.object3DFileName
                val url = flutterNode.objectUrl
                when {
                    localObject != null -> loadModel(localObject, null, null, handler)
                    // Remote models used to be loaded with Sceneform's RenderableSource at half
                    // scale and recentered on the bottom of their bounding box.
                    url != null -> loadModel(url, REMOTE_MODEL_SCALE, Position(0.0f, -1.0f, 0.0f), handler)
                    else -> handler(null, null)
                }
            } else if (flutterNode.image != null) {
                handler(makeImage(flutterNode.image), null)
            } else {
                val material = makeMaterial(flutterNode.shape)
                handler(material?.let { flutterNode.shape?.buildShape(engine, it) }, null)
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Unable to make renderable", ex)
            handler(null, ex)
        }
    }

    fun updateMaterial(node: Node?, map: HashMap<String, *>) {
        val materialInstance = ((node as? ArCoreNode)?.renderableNode as? GeometryNode)
                ?.materialInstance ?: return

        val color = map[MATERIAL_COLOR] as? ArrayList<Int>
        val metallic = (map[MATERIAL_METALLIC] as? Double)?.toFloat()
        val roughness = (map[MATERIAL_ROUGHNESS] as? Double)?.toFloat()
        val reflectance = (map[MATERIAL_REFLECTANCE] as? Double)?.toFloat()

        if (metallic != null) {
            materialInstance.setMetallic(metallic)
        }
        if (roughness != null) {
            materialInstance.setRoughness(roughness)
        }
        if (reflectance != null) {
            materialInstance.setReflectance(reflectance)
        }
        if (color != null) {
            materialInstance.setColor(android.graphics.Color.argb(color[0], color[1], color[2], color[3]))
        }
    }

    private fun loadModel(fileLocation: String, scale: Float?, centerOrigin: Position?, handler: RenderableHandler) {
        coroutineScope.launch {
            try {
                val modelInstance = modelLoader.loadModelInstance(fileLocation)
                        ?: throw IOException("Unable to load model $fileLocation")
                val node = ModelNode(modelInstance, autoAnimate = false, centerOrigin = centerOrigin)
                scale?.let { node.scale = Scale(it, it, it) }
                handler(node, null)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Log.e(TAG, "Unable to load model $fileLocation", ex)
                handler(null, ex)
            }
        }
    }

    private fun makeImage(image: FlutterArCoreImage): Node {
        val decoded = BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)
                ?: throw IllegalArgumentException("Unable to decode image")
        val bitmap = Bitmap.createScaledBitmap(decoded, image.width, image.height, false)

        // Match the Sceneform ViewRenderable defaults: 250dp per meter, bottom center aligned.
        val density = context.resources.displayMetrics.density
        val width = image.width / density / DP_PER_METER
        val height = image.height / density / DP_PER_METER
        return ImageNode(
                materialLoader = materialLoader,
                bitmap = bitmap,
                size = Size(width, height, 0.0f),
                center = Position(0.0f, height / 2.0f, 0.0f)
        )
    }

    private fun makeMaterial(shape: FlutterArCoreShape?): MaterialInstance? {
        val material = shape?.materials?.firstOrNull() ?: return null
        val metallic = material.metallic / 100.0f
        val roughness = material.roughness / 100.0f
        val reflectance = material.reflectance / 100.0f

        val textureBytes = material.textureBytes
        if (textureBytes != null) {
            val bitmap = BitmapFactory.decodeByteArray(textureBytes, 0, textureBytes.size)
                    ?: throw IllegalArgumentException("Unable to decode texture")
            val texture = ImageTexture.Builder().bitmap(bitmap).build(engine)
            return materialLoader.createTextureInstance(texture, isOpaque = false, metallic, roughness, reflectance)
        }
        return materialLoader.createColorInstance(material.color, metallic, roughness, reflectance)
    }

    companion object {
        val TAG: String = NodeFactory::class.java.name

        private const val MATERIAL_COLOR = "color"
        private const val MATERIAL_METALLIC = "metallic"
        private const val MATERIAL_ROUGHNESS = "roughness"
        private const val MATERIAL_REFLECTANCE = "reflectance"

        private const val REMOTE_MODEL_SCALE = 0.5f
        private const val DP_PER_METER = 250.0f
    }
}
