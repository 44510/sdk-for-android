package io.appwrite

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

object OS {
    fun requestPermission(
        activity: AppCompatActivity,
        permission: String,
        onGranted: () -> Unit = {},
        onDenied: () -> Unit = {},
        onShowRationale: () -> Unit = {},
    ) {
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            (permission == Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
        ) {
            onGranted()
            return
        }

        if (ContextCompat.checkSelfPermission(
                activity,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            onGranted()
        } else if (activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            onShowRationale()
        } else {
            requestPermissionLauncher(activity, onGranted, onDenied).launch(permission)
        }
    }

    private fun requestPermissionLauncher(
        activity: AppCompatActivity,
        onGranted: () -> Unit,
        onDenied: () -> Unit,
    ) = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted: Boolean ->
        if (isGranted) {
            onGranted()
        } else {
            onDenied()
        }
    }
}