package com.junerver.videorecorder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.AsyncTask
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import com.junerver.videorecorder.MediaUtils.getOutputMediaFile
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * https://www.jianshu.com/p/bd308c8371dd
 */
object MediaUtils {
  const val MEDIA_TYPE_IMAGE: Int = 1
  const val MEDIA_TYPE_VIDEO: Int = 2

  /**
   * Create a file Uri for saving an image or video
   */
  fun getOutputMediaFileUri(context: Context, type: Int): Uri {
    val uri: Uri? = null
    //适配Android N
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      FileProvider.getUriForFile(
        context,
        context.applicationContext.packageName + ".provider",
        getOutputMediaFile(type)!!
      )
    } else {
      Uri.fromFile(getOutputMediaFile(type))
    }
  }

  /**
   * Create a File for saving an image or video
   */
  fun getOutputMediaFile(type: Int): File? {
    require(type == MEDIA_TYPE_VIDEO || type == MEDIA_TYPE_IMAGE) {
      "错误的文件类型"
    }
    val mediaStorageDir = File(
      Environment.getExternalStoragePublicDirectory(
        Environment.DIRECTORY_PICTURES
      ), "image"
    )
    if (!mediaStorageDir.exists()) {
      if (!mediaStorageDir.mkdirs()) {
        return null
      }
    }
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
    return when (type) {
      MEDIA_TYPE_IMAGE -> File(mediaStorageDir.path + File.separator + "IMG_" + timeStamp + ".jpg")
      MEDIA_TYPE_VIDEO -> File(mediaStorageDir.path + File.separator + "VID_" + timeStamp + ".mp4")
      else -> null
    }
  }

  /**
   * 获取视频的第一帧图片
   */
  suspend fun getImageForVideo(videoPath: String): File? = suspendCoroutine { continuation ->
    val mmr = MediaMetadataRetriever()
    if (videoPath.startsWith("http")) {
      //获取网络视频第一帧图片
      mmr.setDataSource(videoPath, mutableMapOf())
    } else {
      //本地视频
      mmr.setDataSource(videoPath)
    }
    val bitmap = mmr.frameAtTime
    //保存图片
    val f = getOutputMediaFile(MEDIA_TYPE_IMAGE)
    if (f?.exists() == true) {
      f.delete()
    }
    val newBitmap = resizeBitmap(480f, 480f, bitmap)
    try {
      FileOutputStream(f).use {
        newBitmap.compress(Bitmap.CompressFormat.JPEG, 60, it)
        it.flush()
      }
      mmr.release()
    } catch (e: IOException) {
      continuation.resumeWithException(e)
    }
    continuation.resume(f)
  }

  private fun resizeBitmap(newWidth: Float, newHeight: Float, bitmap: Bitmap?): Bitmap {
    val matrix = Matrix()
    matrix.postScale(
      newWidth / bitmap!!.width,
      newHeight / bitmap.height
    )
    val newBitmap = Bitmap.createBitmap(
      bitmap, 0, 0, bitmap.width,
      bitmap.height, matrix, true
    )
    return newBitmap
  }
}
