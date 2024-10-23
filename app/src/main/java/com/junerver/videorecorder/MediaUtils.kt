package com.junerver.videorecorder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.AsyncTask
import android.os.Build
import android.os.Environment
import android.util.Log
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
   * 获取视频的第一帧图片（方形缩略图，适用于列表等场景）
   */
  suspend fun getImageForVideo(videoPath: String): File? = suspendCoroutine { continuation ->
    val mmr = MediaMetadataRetriever()
    try {
      if (videoPath.startsWith("http")) {
        //获取网络视频第一帧图片
        mmr.setDataSource(videoPath, mutableMapOf())
      } else {
        //本地视频
        mmr.setDataSource(videoPath)
      }
      val bitmap = mmr.frameAtTime

      // 检查是否成功获取到视频帧
      if (bitmap == null) {
        Log.w("MediaUtils", "无法获取视频帧，视频可能为空或格式不支持")
        mmr.release()
        continuation.resume(null)
        return@suspendCoroutine
      }

      //保存图片
      val f = getOutputMediaFile(MEDIA_TYPE_IMAGE)
      if (f?.exists() == true) {
        f.delete()
      }

      // 缩略图强制调整为480x480正方形，用于列表等场景
      val newBitmap = resizeBitmap(480f, 480f, bitmap)
      try {
        FileOutputStream(f).use {
          newBitmap.compress(Bitmap.CompressFormat.JPEG, 60, it)
          it.flush()
        }
        mmr.release()
        continuation.resume(f)
      } catch (e: IOException) {
        mmr.release()
        continuation.resumeWithException(e)
      }
    } catch (e: Exception) {
      mmr.release()
      Log.e("MediaUtils", "获取视频缩略图失败", e)
      continuation.resume(null)
    }
  }

  /**
   * 获取视频首帧Bitmap（保持原始宽高比，用于视频预览）
   * @param videoPath 视频路径
   * @return 首帧Bitmap
   */
  suspend fun getVideoFrameBitmap(videoPath: String): Bitmap? = suspendCoroutine { continuation ->
    val mmr = MediaMetadataRetriever()
    try {
      if (videoPath.startsWith("http")) {
        mmr.setDataSource(videoPath, mutableMapOf())
      } else {
        mmr.setDataSource(videoPath)
      }

      // 读取视频元数据
      val rotationStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
      val rotation = rotationStr?.toIntOrNull() ?: 0
      val widthStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
      val heightStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
      val videoWidth = widthStr?.toIntOrNull() ?: 0
      val videoHeight = heightStr?.toIntOrNull() ?: 0

      Log.d("MediaUtils", "视频信息: 宽=${videoWidth}, 高=${videoHeight}, 旋转=${rotation}度")

      val bitmap = mmr.frameAtTime
      mmr.release()

      if (bitmap == null) {
        Log.w("MediaUtils", "无法获取视频帧")
        continuation.resume(null)
        return@suspendCoroutine
      }

      // 计算缩略图尺寸，保持宽高比，限制最大边长为480px
      val maxEdge = 480f
      val originalWidth = bitmap.width.toFloat()
      val originalHeight = bitmap.height.toFloat()
      val scale = if (originalWidth > originalHeight) {
        maxEdge / originalWidth
      } else {
        maxEdge / originalHeight
      }
      val newWidth = (originalWidth * scale).coerceAtMost(maxEdge)
      val newHeight = (originalHeight * scale).coerceAtMost(maxEdge)

      Log.d("MediaUtils", "原始帧尺寸: ${bitmap.width}x${bitmap.height}, 缩略图尺寸: ${newWidth}x${newHeight}, 缩放比例: $scale")

      // 先缩放 bitmap
      val scaledBitmap = resizeBitmap(newWidth, newHeight, bitmap)

      // 注意：对于CameraX录制的视频，METADATA_KEY_VIDEO_ROTATION通常表示"需要旋转的角度"
      // 但实际帧数据可能已经是旋转后的，或者我们需要反向旋转
      // 实践中发现，大多数情况下不需要额外应用视频元数据中的旋转
      // 特别是在Android设备上竖屏录制的视频，frameAtTime返回的已经是正确方向的帧
      val finalBitmap = scaledBitmap

      Log.d("MediaUtils", "最终帧尺寸: ${finalBitmap.width}x${finalBitmap.height}")

      continuation.resume(finalBitmap)
    } catch (e: Exception) {
      mmr.release()
      Log.e("MediaUtils", "获取视频首帧失败", e)
      continuation.resume(null)
    }
  }

  private fun resizeBitmap(newWidth: Float, newHeight: Float, bitmap: Bitmap?): Bitmap {
    // 添加null检查
    if (bitmap == null) {
      Log.e("MediaUtils", "bitmap为null，无法调整大小")
      throw IllegalArgumentException("Bitmap cannot be null")
    }

    val matrix = Matrix()
    matrix.postScale(
      newWidth / bitmap.width,
      newHeight / bitmap.height
    )
    val newBitmap = Bitmap.createBitmap(
      bitmap, 0, 0, bitmap.width,
      bitmap.height, matrix, true
    )
    return newBitmap
  }
}
