package com.difrancescogianmarco.arcore_flutter_plugin

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.difrancescogianmarco.arcore_flutter_plugin.models.destroyRecursively
import com.google.android.filament.MaterialInstance
import com.google.ar.core.AugmentedFace
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.github.sceneview.ar.node.AugmentedFaceNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.texture.ImageTexture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class ArCoreFaceView(activity: Activity, context: Context, messenger: BinaryMessenger, id: Int, debug: Boolean) : BaseArCoreView(activity, context, messenger, id, debug, setOf(Session.Feature.FRONT_CAMERA)) {

    private val TAG: String = ArCoreFaceView::class.java.name
    private var isAugmentedFacesEnabled = false
    private var faceMeshMaterial: MaterialInstance? = null
    private var faceModelFileLocation: String? = null
    private val faceNodeMap = HashMap<AugmentedFace, AugmentedFaceNode>()
    private val faceModelNodes = HashMap<AugmentedFaceNode, ModelNode>()

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if(isSupportedDevice){
            debugLog(call.method +"called on supported device")
            when (call.method) {
                "init" -> {
                    arScenViewInit(call, result)
                }
                "loadMesh" -> {
                    val map = call.arguments as HashMap<*, *>
                    val textureBytes = map["textureBytes"] as ByteArray
                    val skin3DModelFilename = map["skin3DModelFilename"] as? String
                    loadMesh(textureBytes, skin3DModelFilename)
                    result.success(null)
                }
                "dispose" -> {
                    debugLog( " updateMaterials")
                    dispose()
                }
                else -> {
                    result.notImplemented()
                }
            }
        }else{
            debugLog("Impossible call " + call.method + " method on unsupported device")
            result.error("Unsupported Device","",null)
        }
    }

    override fun configureSession(session: Session, config: Config) {
        super.configureSession(session, config)
        config.augmentedFaceMode = Config.AugmentedFaceMode.MESH3D
    }

    override fun onSessionUpdated(session: Session, frame: Frame) {
        if (!isAugmentedFacesEnabled || faceMeshMaterial == null) {
            return
        }
        val sceneView = arSceneView ?: return

        // Make new AugmentedFaceNodes for any new faces.
        for (face in session.getAllTrackables(AugmentedFace::class.java)) {
            if (!faceNodeMap.containsKey(face) && face.trackingState == TrackingState.TRACKING) {
                val faceNode = AugmentedFaceNode(sceneView.engine, face, faceMeshMaterial)
                sceneView.addChildNode(faceNode)
                faceNodeMap[face] = faceNode
                loadFaceModel(faceNode)
            }
        }

        // Remove any AugmentedFaceNodes associated with an AugmentedFace that stopped tracking.
        val iter = faceNodeMap.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.key.trackingState == TrackingState.STOPPED) {
                val faceNode = entry.value
                faceModelNodes.remove(faceNode)
                sceneView.removeChildNode(faceNode)
                faceNode.destroyRecursively()
                iter.remove()
            }
        }
    }

    fun loadMesh(textureBytes: ByteArray?, skin3DModelFilename: String?) {
        val sceneView = arSceneView ?: return

        // Load the face mesh texture.
        val bitmap = BitmapFactory.decodeByteArray(textureBytes, 0, textureBytes!!.size)
        if (bitmap == null) {
            Log.e(TAG, "Unable to decode face mesh texture")
        } else {
            val texture = ImageTexture.Builder().bitmap(bitmap).build(sceneView.engine)
            val material = sceneView.materialLoader.createImageInstance(texture)
            faceMeshMaterial = material
            // change assets on runtime
            faceNodeMap.values.forEach { it.meshNode.materialInstance = material }
        }

        // Load the face regions model, rendered at the center of the face.
        // Must be a glTF (.glb or .gltf) model: Sceneform .sfb files are no longer supported.
        if (skin3DModelFilename != null && skin3DModelFilename != faceModelFileLocation) {
            faceModelFileLocation = skin3DModelFilename
            faceNodeMap.values.forEach { loadFaceModel(it) }
        }
    }

    private fun loadFaceModel(faceNode: AugmentedFaceNode) {
        val fileLocation = faceModelFileLocation ?: return
        val sceneView = arSceneView ?: return
        mainScope.launch {
            try {
                val modelInstance = sceneView.modelLoader.loadModelInstance(fileLocation) ?: return@launch
                if (faceNodeMap.containsValue(faceNode) && fileLocation == faceModelFileLocation) {
                    val modelNode = ModelNode(modelInstance)
                    faceModelNodes.put(faceNode, modelNode)?.let {
                        faceNode.centerNode.removeChildNode(it)
                        it.destroyRecursively()
                    }
                    faceNode.centerNode.addChildNode(modelNode)
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Log.e(TAG, "Unable to load face model $fileLocation", ex)
            }
        }
    }

    private fun arScenViewInit(call: MethodCall, result: MethodChannel.Result) {
        val enableAugmentedFaces: Boolean? = call.argument("enableAugmentedFaces")
        if (enableAugmentedFaces != null && enableAugmentedFaces) {
            isAugmentedFacesEnabled = true
        }

        result.success(null)
    }

    override fun onDestroy() {
        isAugmentedFacesEnabled = false
        faceNodeMap.clear()
        faceModelNodes.clear()
        super.onDestroy()
    }

}
