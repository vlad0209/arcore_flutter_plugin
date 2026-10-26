package com.difrancescogianmarco.arcore_flutter_plugin.utils

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import androidx.core.app.ActivityCompat
import android.widget.Toast
import androidx.annotation.Nullable
import androidx.core.content.ContextCompat
import com.google.ar.core.exceptions.*
import java.util.*
import androidx.core.content.ContextCompat.getSystemService
import android.os.Build.VERSION_CODES
import com.google.ar.core.*
import com.google.ar.core.CameraConfig


class ArCoreUtils {


    companion object {

        private val TAG = ArCoreUtils::class.java.name
        private val MIN_OPENGL_VERSION = 3.0
        private val CAMERA_PERMISSION_CODE = 0
        private val CAMERA_PERMISSION = Manifest.permission.CAMERA

        /**
         * Selects the camera config used by the session: 30 fps camera capture without depth sensor.
         * Such a config is supported on all ARCore supported devices.
         */
        fun selectCameraConfig(session: Session): CameraConfig {
            val filter = CameraConfigFilter(session)
                    .setFacingDirection(session.cameraConfig.facingDirection)
                    .setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30))
                    .setDepthSensorUsage(EnumSet.of(CameraConfig.DepthSensorUsage.DO_NOT_USE))
            return session.getSupportedCameraConfigs(filter).firstOrNull() ?: session.cameraConfig
        }

        /** Check to see we have the necessary permissions for this app, and ask for them if we don't.  */
        fun requestCameraPermission(activity: Activity, requestCode: Int) {
            ActivityCompat.requestPermissions(
                    activity, arrayOf(Manifest.permission.CAMERA), requestCode)
        }

        /** Check to see we have the necessary permissions for this app.  */
        fun hasCameraPermission(activity: Activity): Boolean {
            return ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        }

        /** Check to see if we need to show the rationale for this permission.  */
        fun shouldShowRequestPermissionRationale(activity: Activity): Boolean {
            return ActivityCompat.shouldShowRequestPermissionRationale(
                    activity, Manifest.permission.CAMERA)
        }

        /** Launch Application Setting to grant permission.  */
        fun launchPermissionSettings(activity: Activity) {
            val intent = Intent()
            intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            intent.data = Uri.fromParts("package", activity.packageName, null)
            activity.startActivity(intent)
        }

        /**
         * Creates and shows a Toast containing an error message. If there was an exception passed in it
         * will be appended to the toast. The error will also be written to the Log
         */
        fun displayError(
                context: Context, errorMsg: String, @Nullable problem: Throwable?) {
            val tag = context.javaClass.simpleName
            val toastText: String
            if (problem != null && problem.message != null) {
                Log.e(tag, errorMsg, problem)
                toastText = errorMsg + ": " + problem.message
            } else if (problem != null) {
                Log.e(tag, errorMsg, problem)
                toastText = errorMsg
            } else {
                Log.e(tag, errorMsg)
                toastText = errorMsg
            }

            Handler(Looper.getMainLooper())
                    .post {
                        val toast = Toast.makeText(context, toastText, Toast.LENGTH_LONG)
                        toast.setGravity(Gravity.CENTER, 0, 0)
                        toast.show()
                    }
        }

        fun handleSessionException(
                activity: Activity, sessionException: UnavailableException) {

            val message: String
            if (sessionException is UnavailableArcoreNotInstalledException) {
                message = "Please install ARCore"
            } else if (sessionException is UnavailableApkTooOldException) {
                message = "Please update ARCore"
            } else if (sessionException is UnavailableSdkTooOldException) {
                message = "Please update this app"
            } else if (sessionException is UnavailableDeviceNotCompatibleException) {
                message = "This device does not support AR"
            } else {
                message = "Failed to create AR session"
                Log.e(TAG, "Exception: $sessionException")
            }
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        }

        /**
         * Returns false and displays an error message if SceneView can not run, true if SceneView can run
         * on this device.
         *
         *
         * SceneView requires Android N on the device as well as OpenGL 3.0 capabilities.
         *
         *
         * Finishes the activity if SceneView can not run
         */
        fun checkIsSupportedDeviceOrFinish(activity: Activity): Boolean {
            if (Build.VERSION.SDK_INT < VERSION_CODES.N) {
                Log.e(TAG, "ARCore requires Android N or later")
                Toast.makeText(activity, "ARCore requires Android N or later", Toast.LENGTH_LONG).show()
                activity.finish()
                return false
            }
            val openGlVersionString = (activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                    .deviceConfigurationInfo
                    .glEsVersion
            if (java.lang.Double.parseDouble(openGlVersionString) < MIN_OPENGL_VERSION) {
                Log.e(TAG, "ARCore requires OpenGL ES 3.0 later")
                Toast.makeText(activity, "ARCore requires OpenGL ES 3.0 or later", Toast.LENGTH_LONG)
                        .show()
                activity.finish()
                return false
            }
            return true
        }
    }
}