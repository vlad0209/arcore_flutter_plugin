package com.difrancescogianmarco.arcore_flutter_plugin

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.Pair
import com.difrancescogianmarco.arcore_flutter_plugin.flutter_models.FlutterArCoreNode
import com.difrancescogianmarco.arcore_flutter_plugin.flutter_models.FlutterArCorePose
import com.difrancescogianmarco.arcore_flutter_plugin.models.destroyRecursively
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.github.sceneview.ar.arcore.configure
import io.github.sceneview.ar.node.AnchorNode
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream

class ArCoreAugmentedImagesView(activity: Activity, context: Context, messenger: BinaryMessenger, id: Int, val useSingleImage: Boolean, debug: Boolean) : BaseArCoreView(activity, context, messenger, id, debug) {

    private val TAG: String = ArCoreAugmentedImagesView::class.java.name
    private var isInitialized = false
    // Augmented image and its associated center pose anchor, keyed by index of the augmented image in
    // the
    // database.
    private val augmentedImageMap = HashMap<Int, Pair<AugmentedImage, AnchorNode>>()

    override fun onSessionUpdated(session: Session, frame: Frame) {
        // If ARCore is not tracking yet, just return.
        if (!isInitialized || frame.camera.trackingState != TrackingState.TRACKING) {
            return
        }

        val updatedAugmentedImages = frame.getUpdatedTrackables(AugmentedImage::class.java)

        for (augmentedImage in updatedAugmentedImages) {
            when (augmentedImage.trackingState) {
                TrackingState.PAUSED -> {
                    val text = String.format("Detected Image %d", augmentedImage.index)
                    debugLog( text)
                }

                TrackingState.TRACKING -> {
                    debugLog( "${augmentedImage.name} ${augmentedImage.trackingMethod}")
                    if (!augmentedImageMap.containsKey(augmentedImage.index)) {
                        debugLog( "${augmentedImage.name} ASSENTE")
                        val centerPoseAnchor = augmentedImage.createAnchor(augmentedImage.centerPose)
                        val anchorNode = AnchorNode(arSceneView!!.engine, centerPoseAnchor)
                        augmentedImageMap[augmentedImage.index] = Pair.create(augmentedImage, anchorNode)
                    }

                    sendAugmentedImageToFlutter(augmentedImage)
                }

                TrackingState.STOPPED -> {
                    debugLog( "STOPPED: ${augmentedImage.name}")
                    augmentedImageMap.remove(augmentedImage.index)?.let { removeAnchorNode(it.second) }
                    val text = String.format("Removed Image %d", augmentedImage.index)
                    debugLog( text)
                }

                else -> {
                }
            }
        }
    }

    private fun sendAugmentedImageToFlutter(augmentedImage: AugmentedImage) {
        val map: HashMap<String, Any> = HashMap<String, Any>()
        map["name"] = augmentedImage.name
        map["index"] = augmentedImage.index
        map["extentX"] = augmentedImage.extentX
        map["extentZ"] = augmentedImage.extentZ
        map["centerPose"] = FlutterArCorePose.fromPose(augmentedImage.centerPose).toHashMap()
        map["trackingMethod"] = augmentedImage.trackingMethod.ordinal
        activity.runOnUiThread {
            methodChannel.invokeMethod("onTrackingImage", map)
        }
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (isSupportedDevice) {
            debugLog( call.method + "called on supported device")
            when (call.method) {
                "init" -> {
                    debugLog( "INIT AUGMENTED IMAGES")
                    arScenViewInit(call, result)
                }
                "load_single_image_on_db" -> {
                    debugLog( "load_single_image_on_db")
                    val map = call.arguments as HashMap<String, Any>
                    val singleImageBytes = map["bytes"] as? ByteArray
                    setupSession(singleImageBytes, true, null)
                }
                "load_multiple_images_on_db" -> {
                    debugLog( "load_multiple_image_on_db")
                    val map = call.arguments as HashMap<String, Any>
                    val dbByteMap = map["bytesMap"] as? Map<String, ByteArray>
                    setupSession(dbByteMap)
                }
                "load_augmented_images_database" -> {
                    debugLog( "LOAD DB")
                    val map = call.arguments as HashMap<String, Any>
                    val dbByteArray = map["bytes"] as? ByteArray
                    setupSession(dbByteArray, false, null)
                }
                "load_augmented_images_database_file" -> {
                    debugLog( "LOAD DB FILE")
                    val map = call.arguments as HashMap<String, Any>
                    val path = map["databasePath"] as String
                    setupSession(null, false, path)
                }
                "attachObjectToAugmentedImage" -> {
                    debugLog( "attachObjectToAugmentedImage")
                    val map = call.arguments as HashMap<String, Any>
                    val flutterArCoreNode = FlutterArCoreNode(map["node"] as HashMap<String, Any>)
                    val index = map["index"] as Int
                    if (augmentedImageMap.containsKey(index)) {
//                        val augmentedImage = augmentedImageMap[index]!!.first
                        val anchorNode = augmentedImageMap[index]!!.second
//                        setImage(augmentedImage, anchorNode)
//                        onAddNode(flutterArCoreNode, result)
                        val factory = nodeFactory ?: return
                        factory.makeNode(flutterArCoreNode) { node, throwable ->
                            debugLog( "inserted ${node?.name}")
                            val sceneView = arSceneView
                            if (node != null && sceneView != null) {
                                anchorNode.addChildNode(node)
                                if (!sceneView.childNodes.contains(anchorNode)) {
                                    sceneView.addChildNode(anchorNode)
                                }
                                result.success(null)
                            } else if (throwable != null) {
                                result.error("attachObjectToAugmentedImage error", throwable.localizedMessage, null)
                            }
                        }
                    } else {
                        result.error("attachObjectToAugmentedImage error", "Augmented image there isn't ona hashmap", null)
                    }
                }
                "removeARCoreNodeWithIndex" -> {
                    debugLog( "removeObject")
                    try {
                        val map = call.arguments as HashMap<String, Any>
                        val index = map["index"] as Int
                        removeAnchorNode(augmentedImageMap[index]!!.second)
                        augmentedImageMap.remove(index)
                        result.success(null)
                    } catch (ex: Exception) {
                        result.error("removeARCoreNodeWithIndex", ex.localizedMessage, null)
                    }
                }
                "dispose" -> {
                    debugLog( " updateMaterials")
                    dispose()
                }
                else -> {
                    result.notImplemented()
                }
            }
        } else {
            debugLog( "Impossible call " + call.method + " method on unsupported device")
            result.error("Unsupported Device", "", null)
        }
    }

    private fun arScenViewInit(call: MethodCall, result: MethodChannel.Result) {
        val enableTapRecognizer: Boolean? = call.argument("enableTapRecognizer")
        isTapRecognizerEnabled = enableTapRecognizer == true

        isInitialized = true

        val enablePlaneRenderer: Boolean? = call.argument("enablePlaneRenderer")
        if (enablePlaneRenderer != null && !enablePlaneRenderer) {
            debugLog(" The plane renderer (enablePlaneRenderer) is set to " + enablePlaneRenderer.toString())
            arSceneView?.planeRenderer?.isVisible = false
        }

        onResume()
        result.success(null)
    }

    private fun removeAnchorNode(anchorNode: AnchorNode) {
        if (arSceneView?.childNodes?.contains(anchorNode) == true) {
            removeNode(anchorNode)
        } else {
            anchorNode.destroyRecursively()
        }
    }

    fun setupSession(bytes: ByteArray?, useSingleImage: Boolean, imageDbPath: String?) {
        debugLog( "setupSession()")
        withSession { session ->
            try {
                session.configure { config ->
                    bytes?.let {
                        if (useSingleImage) {
                            if (!addImageToAugmentedImageDatabase(session, config, bytes)) {
                                throw Exception("Could not setup augmented image database")
                            }
                        } else {
                            if (!useExistingAugmentedImageDatabase(session, config, bytes)) {
                                throw Exception("Could not setup augmented image database")
                            }
                        }
                    }
                    imageDbPath?.let {
                        if (!loadAugmentedImageDatabaseFile(session, config, imageDbPath)) {
                            throw Exception("Could not setup augmented image database")
                        }
                    }
                }
            } catch (ex: Exception) {
                debugLog( ex.localizedMessage)
            }
        }
    }

    fun setupSession(bytesMap: Map<String, ByteArray>?) {
        debugLog( "setupSession()")
        withSession { session ->
            bytesMap?.let {
                addMultipleImagesToAugmentedImageDatabase(bytesMap, session)
            }
        }
    }

    private fun addMultipleImagesToAugmentedImageDatabase(
        bytesMap: Map<String, ByteArray>,
        session: Session
    ) {
        debugLog("addImageToAugmentedImageDatabase")

        mainScope.launch {
            withContext(Dispatchers.Main) {
                methodChannel.invokeMethod("onDatabaseLoadingStart", null)
            }
            try {
                val augmentedImageDatabase = withContext(Dispatchers.Default) {
                    val db = AugmentedImageDatabase(session)
                    val bitmaps = coroutineScope {
                        bytesMap.map { (key, bytes) ->
                            async(Dispatchers.Default) {
                                key to loadAugmentedImageBitmap(bytes)
                            }
                        }.awaitAll()
                    }

                    for ((key, bitmap) in bitmaps) {
                        try {
                            db.addImage(key, bitmap)
                        } catch (ex: Exception) {
                            debugLog("Image with title $key cannot be added. Exception: ${ex.localizedMessage}")
                        }
                    }
                    if (db.getNumImages() == 0) {
                        throw Exception("Could not setup augmented image database")
                    }
                    db
                }

                session.configure { config ->
                    config.augmentedImageDatabase = augmentedImageDatabase
                }
                methodChannel.invokeMethod("onDatabaseLoadingEnd", null)

            } catch (ex: Exception) {
                debugLog("Exception during DB setup: ${ex.localizedMessage}")
                withContext(Dispatchers.Main) {
                    methodChannel.invokeMethod("onDatabaseLoadingError", ex.localizedMessage)
                }
            }
        }
    }

    private fun addImageToAugmentedImageDatabase(session: Session, config: Config, bytes: ByteArray): Boolean {
        debugLog( "addImageToAugmentedImageDatabase")
        try {
            val augmentedImageBitmap = loadAugmentedImageBitmap(bytes) ?: return false
            val augmentedImageDatabase = AugmentedImageDatabase(session)
            augmentedImageDatabase.addImage("image_name", augmentedImageBitmap)
            config.augmentedImageDatabase = augmentedImageDatabase
            return true
        } catch (ex:Exception) {
            debugLog(ex.localizedMessage)
            return false
        }
    }

    private fun useExistingAugmentedImageDatabase(session: Session, config: Config, bytes: ByteArray): Boolean {
        debugLog( "useExistingAugmentedImageDatabase")
        return try {
            val inputStream = ByteArrayInputStream(bytes)
            val augmentedImageDatabase = AugmentedImageDatabase.deserialize(session, inputStream)
            config.augmentedImageDatabase = augmentedImageDatabase
            true
        } catch (e: IOException) {
            Log.e(TAG, "IO exception loading augmented image database.", e)
            false
        }
    }

    private fun loadAugmentedImageBitmap(bitmapdata: ByteArray): Bitmap? {
        debugLog( "loadAugmentedImageBitmap")
        try {
            return  BitmapFactory.decodeByteArray(bitmapdata, 0, bitmapdata.size)
        } catch (e: Exception) {
            Log.e(TAG, "IO exception loading augmented image bitmap.", e)
            return  null
        }
    }

    private fun loadAugmentedImageDatabaseFile(session: Session, config: Config, path: String): Boolean {
        return try {
            val file = File(path)
            val imageDatabase = FileInputStream(file).use {
                AugmentedImageDatabase.deserialize(session, it)
            }
            config.augmentedImageDatabase = imageDatabase
            true
        } catch (e: Exception) {
            Log.e(TAG, "IO exception loading augmented image file.", e)
            false
        }
    }
}
