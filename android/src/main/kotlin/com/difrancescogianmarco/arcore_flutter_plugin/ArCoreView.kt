package com.difrancescogianmarco.arcore_flutter_plugin

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import com.difrancescogianmarco.arcore_flutter_plugin.flutter_models.FlutterArCoreNode
import com.difrancescogianmarco.arcore_flutter_plugin.flutter_models.FlutterArCorePose
import com.difrancescogianmarco.arcore_flutter_plugin.models.ArCoreNode
import com.difrancescogianmarco.arcore_flutter_plugin.models.RotatingNode
import com.google.ar.core.Config
import com.google.ar.core.Config.GeospatialMode
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.github.sceneview.ar.node.AnchorNode
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class ArCoreView(activity: Activity, context: Context, messenger: BinaryMessenger, id: Int, debug: Boolean, private val enableGeospatialMode: Boolean = false) : BaseArCoreView(activity, context, messenger, id, debug) {

    private var isUpdateListenerEnabled = false

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "init" -> {
                arScenViewInit(call, result)
            }
            "addArCoreNode" -> {
                debugLog(" addArCoreNode")
                val map = call.arguments as HashMap<String, Any>
                val flutterNode = FlutterArCoreNode(map)
                onAddNode(flutterNode, null)
                result.success(null)
            }
            "addArCoreNodeWithAnchor" -> {
                debugLog(" addArCoreNode")
                val map = call.arguments as HashMap<String, Any>
                val flutterNode = FlutterArCoreNode(map)
                addNodeWithAnchor(flutterNode, result)
            }
            "removeARCoreNode" -> {
                debugLog(" removeARCoreNode")
                val map = call.arguments as HashMap<String, Any>
                removeNode(map["nodeName"] as String, result)
            }
            "positionChanged" -> {
                debugLog(" positionChanged")

            }
            "rotationChanged" -> {
                debugLog(" rotationChanged")
                updateRotation(call, result)

            }
            "updateMaterials" -> {
                debugLog(" updateMaterials")
                updateMaterials(call, result)

            }
            "takeScreenshot" -> {
                debugLog(" takeScreenshot")
                takeScreenshot(call, result)

            }
            "dispose" -> {
                debugLog("Disposing ARCore now")
                dispose()
            }
            "resume" -> {
                debugLog("Resuming ARCore now")
                onResume()
            }
            "getTrackingState" -> {
                debugLog("1/3: Requested tracking state, returning that back to Flutter now")

                val trState = arSceneView?.frame?.camera?.trackingState
                debugLog("2/3: Tracking state is " + trState.toString())
                methodChannel.invokeMethod("getTrackingState", trState.toString())
            }
            "togglePlaneRenderer" -> {
                debugLog(" Toggle planeRenderer visibility" )
                arSceneView?.planeRenderer?.let { it.isVisible = !it.isVisible }
            }
            else -> {
            }
        }
    }

    override fun configureSession(session: Session, config: Config) {
        super.configureSession(session, config)
        if (enableGeospatialMode && session.isGeospatialModeSupported(GeospatialMode.ENABLED)) {
            config.geospatialMode = GeospatialMode.ENABLED
        }
    }

    override fun onSessionUpdated(session: Session, frame: Frame) {
        if (!isUpdateListenerEnabled || frame.camera.trackingState != TrackingState.TRACKING) {
            return
        }

        for (plane in frame.getUpdatedTrackables(Plane::class.java)) {
            if (plane.trackingState == TrackingState.TRACKING) {

                val pose = plane.centerPose
                val map: HashMap<String, Any> = HashMap<String, Any>()
                map["type"] = plane.type.ordinal
                map["centerPose"] = FlutterArCorePose(pose.translation, pose.rotationQuaternion).toHashMap()
                map["extentX"] = plane.extentX
                map["extentZ"] = plane.extentZ

                methodChannel.invokeMethod("onPlaneDetected", map)
            }
        }
        if (enableGeospatialMode) {
            val earth = session.earth
            if (earth?.trackingState == TrackingState.TRACKING) {
                val cameraGeospatialPose = earth.cameraGeospatialPose
                val map: HashMap<String, Any> = HashMap<String, Any>()
                map["latitude"] = cameraGeospatialPose.latitude
                map["longitude"] = cameraGeospatialPose.longitude
                map["altitude"] = cameraGeospatialPose.altitude
                map["eastUpSouthQuaternion"] = cameraGeospatialPose.eastUpSouthQuaternion
                map["horizontalAccuracy"] = cameraGeospatialPose.horizontalAccuracy
                map["orientationYawAccuracy"] = cameraGeospatialPose.orientationYawAccuracy
                map["verticalAccuracy"] = cameraGeospatialPose.verticalAccuracy

                methodChannel.invokeMethod("onCameraGeospatialPoseDetected", map)
            }
        }
    }

    private fun takeScreenshot(call: MethodCall, result: MethodChannel.Result) {
        try {
            val sceneView = arSceneView!!
            // Create a bitmap the size of the scene view.
            val bitmap: Bitmap = Bitmap.createBitmap(sceneView.width, sceneView.height,
                    Bitmap.Config.ARGB_8888)

            // Create a handler thread to offload the processing of the image.
            val handlerThread = HandlerThread("PixelCopier")
            handlerThread.start()
            // Make the request to copy.
            PixelCopy.request(sceneView, bitmap, { copyResult ->
                if (copyResult == PixelCopy.SUCCESS) {
                    try {
                        saveBitmapToDisk(bitmap)
                    } catch (e: IOException) {
                        e.printStackTrace();
                    }
                }
                handlerThread.quitSafely()
            }, Handler(handlerThread.looper))

        } catch (e: Throwable) {
            // Several error may come out with file handling or DOM
            e.printStackTrace()
        }
        result.success(null)
    }

    @Throws(IOException::class)
    fun saveBitmapToDisk(bitmap: Bitmap):String {
        val now = "rawScreenshot"
        val mPath: String =  Environment.getExternalStorageDirectory().toString() + "/DCIM/" + now + ".jpg"
        val mediaFile = File(mPath)
        debugLog(mediaFile.toString())
        val fileOutputStream = FileOutputStream(mediaFile)
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fileOutputStream)
        fileOutputStream.flush()
        fileOutputStream.close()
        return mPath
    }

    private fun arScenViewInit(call: MethodCall, result: MethodChannel.Result) {
        debugLog("arScenViewInit")
        val enableTapRecognizer: Boolean? = call.argument("enableTapRecognizer")
        isTapRecognizerEnabled = enableTapRecognizer == true

        val enableUpdateListener: Boolean? = call.argument("enableUpdateListener")
        isUpdateListenerEnabled = enableUpdateListener == true

        val enablePlaneRenderer: Boolean? = call.argument("enablePlaneRenderer")
        if (enablePlaneRenderer != null && !enablePlaneRenderer) {
            debugLog(" The plane renderer (enablePlaneRenderer) is set to " + enablePlaneRenderer.toString())
            arSceneView?.planeRenderer?.isVisible = false
        }

        result.success(null)
    }

    fun addNodeWithAnchor(flutterArCoreNode: FlutterArCoreNode, result: MethodChannel.Result) {
        val factory = nodeFactory ?: return

        factory.makeRenderable(flutterArCoreNode) { renderable, t ->
            if (t != null) {
                result.error("Make Renderable Error", t.localizedMessage, null)
                return@makeRenderable
            }
            val sceneView = arSceneView
            val myAnchor = sceneView?.session?.createAnchor(Pose(flutterArCoreNode.getPosition(), flutterArCoreNode.getRotation()))
            if (sceneView != null && myAnchor != null) {
                val anchorNode = AnchorNode(sceneView.engine, myAnchor)
                anchorNode.name = flutterArCoreNode.name
                renderable?.let { anchorNode.addChildNode(it) }

                debugLog("addNodeWithAnchor inserted ${anchorNode.name}")
                // Anchor poses are in world space and are only updated at the root of the scene.
                sceneView.addChildNode(anchorNode)

                for (node in flutterArCoreNode.children) {
                    node.parentNodeName = flutterArCoreNode.name
                    onAddNode(node, null)
                }
            }
            result.success(null)
        }
    }

    fun updateRotation(call: MethodCall, result: MethodChannel.Result) {
        val name = call.argument<String>("name")
        val node = findNodeByName(name) as? RotatingNode
        debugLog("rotating node:  $node")
        val degreesPerSecond = call.argument<Double?>("degreesPerSecond")
        debugLog("rotating value:  $degreesPerSecond")
        if (node != null && degreesPerSecond != null) {
            debugLog("rotating value:  ${node.degreesPerSecond}")
            node.degreesPerSecond = degreesPerSecond.toFloat()
        }
        result.success(null)
    }

    fun updateMaterials(call: MethodCall, result: MethodChannel.Result) {
        val name = call.argument<String>("name")
        val materials = call.argument<ArrayList<HashMap<String, *>>>("materials")!!
        val node = findNodeByName(name) as? ArCoreNode
        nodeFactory?.updateMaterial(node, materials[0])
        result.success(null)
    }
}
