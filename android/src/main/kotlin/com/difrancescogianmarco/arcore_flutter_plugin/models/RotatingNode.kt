package com.difrancescogianmarco.arcore_flutter_plugin.models

import com.google.android.filament.Engine
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion

/** Node rotating clockwise around its Y axis.  */
class RotatingNode(engine: Engine, var degreesPerSecond: Float = 90.0f) : ArCoreNode(engine) {

    private var lastFrameTimeNanos: Long? = null

    override fun onFrame(frameTimeNanos: Long) {
        super.onFrame(frameTimeNanos)

        val lastFrameTimeNanos = lastFrameTimeNanos
        this.lastFrameTimeNanos = frameTimeNanos
        if (lastFrameTimeNanos == null || degreesPerSecond == 0.0f) {
            return
        }

        val deltaSeconds = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000.0f
        quaternion *= Quaternion.fromAxisAngle(Y_AXIS, -degreesPerSecond * deltaSeconds)
    }

    companion object {
        private val Y_AXIS = Float3(0.0f, 1.0f, 0.0f)
    }
}
