package com.korit.booxdashboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * 照片候選來源會合併成同一個隨機池，每次刷新從池子裡隨機挑一張：
 * 1. 使用者用系統相片選擇器多選的照片（存 content:// uri，可能來自 Google 相簿等 App）
 * 2. 使用者長按照片區、透過 SAF 選過的資料夾
 * 3. 都沒設定的話，退回掃描裝置固定路徑 /sdcard/DCIM/Frame/（需要 READ_EXTERNAL_STORAGE）
 *
 * 候選清單只存「怎麼解出這張圖」的 lazy 動作，抽中才真的解碼，避免每次刷新都把
 * 所有候選圖片全部讀進記憶體（3GB RAM 的裝置禁不起這樣搞）。
 */
object PhotoRepository {

    private const val PREFS_NAME = "boox_dashboard_prefs"
    private const val KEY_PHOTO_DIR_URI = "photo_dir_uri"
    private const val KEY_PICKED_URIS = "picked_photo_uris"

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

    fun savePickedUris(context: Context, uris: List<Uri>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_PICKED_URIS, uris.map { it.toString() }.toSet())
            .apply()
    }

    private fun getPickedUris(context: Context): List<Uri> {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_PICKED_URIS, null) ?: return emptyList()
        return stored.map { Uri.parse(it) }
    }

    fun pickRandomPhoto(context: Context, targetWidth: Int): Bitmap? {
        val candidates = mutableListOf<() -> Bitmap?>()

        getPickedUris(context).forEach { uri ->
            candidates.add { decodeUri(context, uri, targetWidth) }
        }

        val treeUri = getTreeUri(context)
        val treeImages = treeUri?.let { uri ->
            DocumentFile.fromTreeUri(context, uri)
                ?.listFiles()
                ?.filter { it.isFile && (it.type?.startsWith("image/") == true) }
        }.orEmpty()

        if (treeImages.isNotEmpty()) {
            treeImages.forEach { doc ->
                candidates.add { decodeUri(context, doc.uri, targetWidth) }
            }
        } else {
            val files = LEGACY_PHOTO_DIR.listFiles { file ->
                file.isFile && file.extension.lowercase() in IMAGE_EXTENSIONS
            }.orEmpty()
            files.forEach { file ->
                candidates.add { decodeFile(file, targetWidth) }
            }
        }

        if (candidates.isEmpty()) return null
        return candidates.random().invoke()
    }

    private fun decodeUri(context: Context, uri: Uri, targetWidth: Int): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                decodeSampledBitmapFromBytes(input.readBytes(), targetWidth)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeFile(file: File, targetWidth: Int): Bitmap? {
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
