package com.difrancescogianmarco.arcore_flutter_plugin

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.android.filament.utils.KTX1Loader
import com.google.ar.core.CameraConfig
import com.google.ar.core.Config
import com.google.ar.core.Session
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.utils.readBuffer

/**
 * [ARSceneView] adapted to live inside a Flutter platform view.
 *
 * - Camera permission and ARCore install checks are done by the plugin, because SceneView registers
 *   activity result launchers, which is not allowed once the host activity is already started.
 * - The view is only destroyed when the platform view is disposed: Flutter may detach and re-attach
 *   the view (e.g. virtual display resize), which would otherwise destroy the engine.
 */
class FlutterArSceneView(
    context: Context,
    lifecycle: Lifecycle,
    sessionFeatures: Set<Session.Feature>,
    sessionCameraConfig: (Session) -> CameraConfig,
    sessionConfiguration: (Session, Config) -> Unit,
) : ARSceneView(
    context = context,
    sharedLifecycle = lifecycle,
    sessionFeatures = sessionFeatures,
    sessionCameraConfig = sessionCameraConfig,
    sessionConfiguration = sessionConfiguration,
) {

    private var isDisposing = false

    override val activity: ComponentActivity?
        get() = null

    init {
        // SceneView's light estimation multiplies the main light by the estimate on every new
        // frame (main and estimated light are the same node), so lit materials fade to black.
        lightEstimator = null
        // The default AR environment has no indirect light: use the neutral IBL without skybox
        // so the camera stream stays visible.
        environment = createEnvironment(
            engine = engine,
            indirectLight = KTX1Loader.createIndirectLight(
                engine,
                context.assets.readBuffer(fileLocation = "environments/neutral/neutral_ibl.ktx"),
            ).indirectLight,
            skybox = null,
        )
    }

    fun dispose() {
        isDisposing = true
        destroy()
    }

    override fun destroy() {
        if (isDisposing) {
            super.destroy()
        }
    }
}

/**
 * Lifecycle driven by the plugin so the AR session only starts once the camera permission is granted
 * and ARCore is installed.
 */
class ArCoreLifecycleOwner : LifecycleOwner {

    private val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
        get() = registry

    val isResumed: Boolean
        get() = registry.currentState == Lifecycle.State.RESUMED

    fun resume() {
        if (registry.currentState != Lifecycle.State.DESTROYED) {
            registry.currentState = Lifecycle.State.RESUMED
        }
    }

    fun pause() {
        if (registry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            registry.currentState = Lifecycle.State.STARTED
        }
    }

    fun destroy() {
        if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
