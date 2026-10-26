package com.difrancescogianmarco.arcore_flutter_plugin

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.difrancescogianmarco.arcore_flutter_plugin.flutter_models.FlutterArCoreHitTestResult
import com.difrancescogianmarco.arcore_flutter_plugin.flutter_models.FlutterArCoreNode
import com.difrancescogianmarco.arcore_flutter_plugin.models.destroyRecursively
import com.difrancescogianmarco.arcore_flutter_plugin.utils.ArCoreUtils
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.UnavailableException
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import io.github.sceneview.ar.scene.PlaneRenderer
import io.github.sceneview.gesture.GestureDetector
import io.github.sceneview.node.Node
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

open class BaseArCoreView(
        val activity: Activity,
        context: Context,
        messenger: BinaryMessenger,
        id: Int,
        protected val debug: Boolean,
        sessionFeatures: Set<Session.Feature> = emptySet()
) : PlatformView, MethodChannel.MethodCallHandler {

    private var activityLifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null
    protected val methodChannel: MethodChannel = MethodChannel(messenger, "arcore_flutter_plugin_$id")
    protected val mainScope = MainScope()
    protected var arSceneView: FlutterArSceneView? = null
    protected var nodeFactory: NodeFactory? = null
    protected val RC_PERMISSIONS = 0x123
    private val TAG: String = BaseArCoreView::class.java.name
    protected var isSupportedDevice = false
    protected var isTapRecognizerEnabled = false

    private val lifecycleOwner = ArCoreLifecycleOwner()
    private var cameraPermissionRequested = false
    private var userRequestedInstall = true
    private var isArCoreInstalled = false
    private var isSessionFailed = false
    private val pendingSessionActions = mutableListOf<(Session) -> Unit>()

    init {
        methodChannel.setMethodCallHandler(this)
        if (ArCoreUtils.checkIsSupportedDeviceOrFinish(activity)) {
            isSupportedDevice = true
            val sceneView = FlutterArSceneView(
                    context = context,
                    lifecycle = lifecycleOwner.lifecycle,
                    sessionFeatures = sessionFeatures,
                    sessionCameraConfig = ArCoreUtils::selectCameraConfig,
                    sessionConfiguration = { session, config -> configureSession(session, config) }
            )
            sceneView.planeRenderer.planeRendererMode = PlaneRenderer.PlaneRendererMode.RENDER_ALL
            sceneView.onSessionCreated = { session ->
                val actions = pendingSessionActions.toList()
                pendingSessionActions.clear()
                actions.forEach { it(session) }
            }
            sceneView.onSessionUpdated = { session, frame -> onSessionUpdated(session, frame) }
            sceneView.onSessionFailed = { exception -> onSessionFailed(exception) }
            sceneView.onGestureListener = object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent, node: Node?) {
                    if (isTapRecognizerEnabled) {
                        onSingleTap(e, node)
                    }
                }
            }
            arSceneView = sceneView
            nodeFactory = NodeFactory(activity.applicationContext, sceneView, mainScope, debug)
            setupLifeCycle()
            // Posted so that subclasses are fully constructed before the session starts.
            Handler(Looper.getMainLooper()).post { onResume() }
        }
    }

    protected fun debugLog(message: String?) {
        if (debug) {
            Log.i(TAG, message ?: "null")
        }
    }

    private fun setupLifeCycle() {
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

            override fun onActivityStarted(activity: Activity) {}

            override fun onActivityResumed(activity: Activity) {
                if (activity === this@BaseArCoreView.activity) {
                    debugLog("onActivityResumed")
                    onResume()
                }
            }

            override fun onActivityPaused(activity: Activity) {
                if (activity === this@BaseArCoreView.activity) {
                    debugLog("onActivityPaused")
                    onPause()
                }
            }

            override fun onActivityStopped(activity: Activity) {}

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {}
        }
        activity.application.registerActivityLifecycleCallbacks(callbacks)
        activityLifecycleCallbacks = callbacks
    }

    override fun getView(): View {
        return arSceneView ?: View(activity)
    }

    override fun dispose() {
        activityLifecycleCallbacks?.let { activity.application.unregisterActivityLifecycleCallbacks(it) }
        activityLifecycleCallbacks = null
        mainScope.cancel()
        if (arSceneView != null) {
            onPause()
            onDestroy()
        }
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {

    }

    /** Applies the session config. Called once the session is created. */
    protected open fun configureSession(session: Session, config: Config) {
        config.focusMode = Config.FocusMode.AUTO
    }

    /** Called on every new ARCore frame. */
    protected open fun onSessionUpdated(session: Session, frame: Frame) {}

    protected open fun onSingleTap(event: MotionEvent, node: Node?) {
        val tappedNode = node?.let { findNamedAncestor(it) }
        if (tappedNode != null) {
            debugLog(" onNodeTap " + tappedNode.name)
            methodChannel.invokeMethod("onNodeTap", tappedNode.name)
        } else {
            onPlaneTap(event)
        }
    }

    private fun onPlaneTap(event: MotionEvent) {
        debugLog(" onSingleTap")
        val frame = arSceneView?.frame ?: return
        if (frame.camera.trackingState != TrackingState.TRACKING) {
            return
        }
        val list = ArrayList<HashMap<String, Any>>()
        for (hit in frame.hitTest(event)) {
            val trackable = hit.trackable
            if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
                val translation = hit.hitPose.translation
                val rotation = hit.hitPose.rotationQuaternion
                list.add(FlutterArCoreHitTestResult(hit.distance, translation, rotation).toHashMap())
            }
        }
        methodChannel.invokeMethod("onPlaneTap", list)
    }

    private fun onSessionFailed(exception: Exception) {
        if (exception is UnavailableException) {
            ArCoreUtils.handleSessionException(activity, exception)
        } else {
            ArCoreUtils.displayError(activity, "Failed to create AR session", exception)
        }
    }

    /** Runs [action] now if the session exists, otherwise as soon as it is created. */
    protected fun withSession(action: (Session) -> Unit) {
        val session = arSceneView?.session
        if (session != null) {
            action(session)
        } else {
            pendingSessionActions += action
        }
    }

    open fun onResume() {
        val sceneView = arSceneView ?: return
        if (isSessionFailed || lifecycleOwner.isResumed) {
            return
        }

        if (!ArCoreUtils.hasCameraPermission(activity)) {
            if (!cameraPermissionRequested) {
                cameraPermissionRequested = true
                ArCoreUtils.requestCameraPermission(activity, RC_PERMISSIONS)
            }
            return
        }

        // If ARCore isn't installed, don't start the session.
        // The session will be started on the next resume once ARCore is installed.
        if (!isArCoreInstalled) {
            try {
                if (ArCoreApk.getInstance().requestInstall(activity, userRequestedInstall) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    // Ensures next invocation of requestInstall() will either return
                    // INSTALLED or throw an exception.
                    userRequestedInstall = false
                    return
                }
                isArCoreInstalled = true
            } catch (e: UnavailableException) {
                ArCoreUtils.handleSessionException(activity, e)
                return
            }
        }

        try {
            lifecycleOwner.resume()
            debugLog("Searching for surfaces")
        } catch (e: Exception) {
            // The camera couldn't be opened: stop rendering frames from a session that isn't running.
            isSessionFailed = true
            sceneView.session?.isResumed = false
            ArCoreUtils.displayError(activity, "Unable to get camera", e)
        }
    }

    fun onPause() {
        debugLog("onPause()")
        runCatching { lifecycleOwner.pause() }
    }

    open fun onDestroy() {
        val sceneView = arSceneView ?: return
        arSceneView = null
        nodeFactory = null
        pendingSessionActions.clear()
        runCatching {
            sceneView.childNodes.toList().forEach {
                sceneView.removeChildNode(it)
                it.destroyRecursively()
            }
        }
        runCatching { sceneView.dispose() }
        runCatching { lifecycleOwner.destroy() }
        debugLog("Goodbye arSceneView.")
    }

    fun findNodeByName(name: String?): Node? {
        if (name == null) {
            return null
        }
        fun find(nodes: Collection<Node>): Node? {
            for (node in nodes) {
                if (node.name == name) {
                    return node
                }
                find(node.childNodes)?.let { return it }
            }
            return null
        }
        return find(arSceneView?.childNodes ?: return null)
    }

    private fun findNamedAncestor(node: Node): Node? {
        var current: Node? = node
        while (current != null && current.name == null) {
            current = current.parent
        }
        return current
    }

    fun attachNodeToParent(node: Node, parentNodeName: String?) {
        if (parentNodeName != null) {
            debugLog(parentNodeName)
            val parentNode = findNodeByName(parentNodeName)
            if (parentNode != null) {
                parentNode.addChildNode(node)
            } else {
                node.destroyRecursively()
            }
        } else {
            debugLog("addNodeToSceneWithGeometry: NOT PARENT_NODE_NAME")
            arSceneView?.addChildNode(node)
        }
    }

    fun onAddNode(flutterArCoreNode: FlutterArCoreNode, result: MethodChannel.Result?) {
        debugLog(flutterArCoreNode.toString())
        val factory = nodeFactory ?: return
        factory.makeNode(flutterArCoreNode) { node, throwable ->
            if (arSceneView == null) {
                // Disposed while the node was loading
                node?.destroyRecursively()
                return@makeNode
            }
            debugLog("inserted ${node?.name}")
            if (node != null) {
                attachNodeToParent(node, flutterArCoreNode.parentNodeName)
                for (n in flutterArCoreNode.children) {
                    n.parentNodeName = flutterArCoreNode.name
                    onAddNode(n, null)
                }
                result?.success(null)
            } else if (throwable != null) {
                result?.error("onAddNode", throwable.localizedMessage, null)
            }
        }
    }

    fun removeNode(name: String, result: MethodChannel.Result?) {
        val node = findNodeByName(name)
        if (node != null) {
            removeNode(node)
        }
        result?.success(null)
    }

    fun removeNode(node: Node) {
        val parent = node.parent
        if (parent != null) {
            parent.removeChildNode(node)
        } else {
            arSceneView?.removeChildNode(node)
        }
        node.destroyRecursively()
        debugLog("removed ${node.name}")
    }
}
