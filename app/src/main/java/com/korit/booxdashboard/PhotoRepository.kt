package com.korit.booxdashboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * 照片來源優先順序：
 * 1. 使用者長按照片區、透過 SAF 選過的資料夾（存在 SharedPreferences，跨重啟仍記得）
 * 2. 退回掃描裝置固定路徑 /sdcard/DCIM/Frame/（需要 READ_EXTERNAL_STORAGE）
 * 3. 都沒有就回傳 null，交給 DashboardRenderer 顯示「長按選擇資料夾」的提示
 */
object PhotoRepository {

    private const val PREFS_NAME = "boox_dashboard_prefs"
    private const val KEY_PHOTO_DIR_URI = "photo_dir_uri"

    private val LEGACY_PHOTO_DIR = File(Environment.getExternalStorageDirectory(), "DCIM/Frame")
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

    fun saveTreeUri(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PHOTO_DIR_URI, uri.toString())
            .apply()
    }

    private fun getTreeUri(context: Context): Uri? {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PHOTO_DIR_URI, null) ?: return null
        return Uri.parse(stored)
    }

    fun pickRandomPhoto(context: Context, targetWidth: Int): Bitmap? {
        val treeUri = getTreeUri(context)
        if (treeUri != null) {
            pickRandomFromTree(context, treeUri, targetWidth)?.let { return it }
        }
        return pickRandomFromLegacyFolder(targetWidth)
    }

    private fun pickRandomFromTree(context: Context, treeUri: Uri, targetWidth: Int): Bitmap? {
        val dir = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val images = dir.listFiles().filter { it.isFile && (it.type?.startsWith("image/") == true) }
        if (images.isEmpty()) return null

        val file = images.random()
        return try {
            context.contentResolver.openInputStream(file.uri)?.use { input ->
                decodeSampledBitmapFromBytes(input.readBytes(), targetWidth)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun pickRandomFromLegacyFolder(targetWidth: Int): Bitmap? {
        val files = LEGACY_PHOTO_DIR.listFiles { file ->
            file.isFile && file.extension.lowercase() in IMAGE_EXTENSIONS
        }
        if (files.isNullOrEmpty()) return null

        val file = files.random()
        return try {
            decodeSampledBitmapFromBytes(file.readBytes(), targetWidth)
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeSampledBitmapFromBytes(bytes: ByteArray, targetWidth: Int): Bitmap? {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
        if (boundsOptions.outWidth <= 0) return null

        var sampleSize = 1
        while (boundsOptions.outWidth / (sampleSize * 2) >= targetWidth) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
