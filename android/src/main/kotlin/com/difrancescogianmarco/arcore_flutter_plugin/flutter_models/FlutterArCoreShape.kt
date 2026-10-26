package com.difrancescogianmarco.arcore_flutter_plugin.flutter_models

import com.difrancescogianmarco.arcore_flutter_plugin.utils.DecodableUtils
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.CylinderNode
import io.github.sceneview.node.GeometryNode
import io.github.sceneview.node.SphereNode

class FlutterArCoreShape(map: HashMap<String, *>) {

    val dartType: String = map["dartType"] as String
    val materials: ArrayList<FlutterArCoreMaterial> = getMaterials(map["materials"] as ArrayList<HashMap<String, *>>)
    val radius: Float? = (map["radius"] as? Double)?.toFloat()
    val size = DecodableUtils.parseVector3(map["size"] as? HashMap<String, Any>) ?: Float3()
    val height: Float? = (map["height"] as? Double)?.toFloat()

    fun buildShape(engine: Engine, material: MaterialInstance): GeometryNode? {
        return when (dartType) {
            "ArCoreSphere" -> SphereNode(engine, radius = radius!!, center = SHAPE_CENTER, materialInstance = material)
            "ArCoreCube" -> CubeNode(engine, size = size, center = SHAPE_CENTER, materialInstance = material)
            "ArCoreCylinder" -> CylinderNode(engine, radius = radius!!, height = height!!, center = SHAPE_CENTER, materialInstance = material)
            else -> //TODO return exception
                null
        }
    }

    private fun getMaterials(list: ArrayList<HashMap<String, *>>): ArrayList<FlutterArCoreMaterial> {
        return ArrayList(list.map { map -> FlutterArCoreMaterial(map) })
    }

    override fun toString(): String {
        return "dartType: $dartType\nradius: $radius\nsize: $size\nheight: $height\nmaterial: ${materials[0].toString()}"
    }

    companion object {
        // Same shape offset the Sceneform ShapeFactory calls used, to keep node placement unchanged.
        private val SHAPE_CENTER = Float3(0.0f, 0.15f, 0.0f)
    }
}
