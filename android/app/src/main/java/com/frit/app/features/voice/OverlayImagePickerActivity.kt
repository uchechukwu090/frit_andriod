package com.frit.app.features.voice

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import java.io.ByteArrayOutputStream

/**
 * OverlayImagePickerActivity — tiny trampoline that opens the system photo
 * picker on behalf of the floating overlay (services cannot use
 * rememberLauncherForActivityResult because they have no ActivityResultRegistry).
 *
 * Flow: overlay -> startActivity(this) -> system image picker -> onResult,
 * base64-encodes the selection, forwards it to the running overlay service, done.
 */
class OverlayImagePickerActivity : ComponentActivity() {

    private var launcher: ActivityResultLauncher<String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        launcher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            handlePickedImage(uri)
        }
        launcher?.launch("image/*")
    }

    private fun handlePickedImage(uri: Uri?) {
        if (uri != null) {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return run { finish() }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    val out = ByteArrayOutputStream()
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                    val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                    FritVoiceOverlayService.notifyImagePicked(b64)
                } else {
                    // Raw bitmap decode failed (e.g. HEIC) — forward original bytes
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    FritVoiceOverlayService.notifyImagePicked(b64)
                }
            } catch (e: Exception) {
                Log.e("OverlayImagePicker", "Image decode failed", e)
            }
        }
        finish()
    }
}