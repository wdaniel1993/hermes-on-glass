package dev.wallner.hermesonglass.glasses.data

import android.app.Activity
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper

/**
 * Wraps the CXR-L `AuthorizationHelper` setup.
 *
 * CXR-L talks to `com.rokid.sprite.aiapp` (versionCode >= 100000) via AIDL —
 * if the app is missing or too old we surface a setup error in the HUD
 * before any media calls. The Activity invokes [requestAuthorization] when
 * the user accepts the setup flow; results come back through
 * `onActivityResult` and are parsed via [AuthorizationHelper.parseAuthorizationResult].
 */
class CxrLBootstrap {

    /**
     * Returns null when CXR-L can be initialised; returns a user-facing
     * error string when the Rokid AI app is missing or too old.
     */
    fun checkRequirements(activity: Activity): String? {
        return if (!AuthorizationHelper.INSTANCE.isRequiredRokidAppInstalled(activity)) {
            "Rokid AI app (com.rokid.sprite.aiapp) is missing or out of date. " +
                "Camera, audio, and HUD-push features need version 100000+ installed."
        } else {
            null
        }
    }

    fun requestAuthorization(activity: Activity, requestCode: Int = AUTH_REQUEST_CODE) {
        AuthorizationHelper.INSTANCE.requestAuthorization(activity, requestCode)
    }

    companion object {
        const val AUTH_REQUEST_CODE: Int = 0xCA11
    }
}
