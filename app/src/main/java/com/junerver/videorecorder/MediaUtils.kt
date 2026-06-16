package com.junerver.videorecorder

import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MediaUtils {
  const val MEDIA_TYPE_IMAGE: Int = 1
  const val MEDIA_TYPE_VIDEO: Int = 2

  fun getOutputMediaFile(
    type: Int,
    encoderOption: VideoEncoderOption = VideoEncoderOption.H264
  ): File? {
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
      MEDIA_TYPE_VIDEO -> {
        val extension = encoderOption.fileExtension
        Log.d(
          "MediaUtils",
          "Creating video file with extension: $extension (encoder=$encoderOption)"
        )
        File(mediaStorageDir.path + File.separator + "VID_" + timeStamp + ".$extension")
      }
      else -> null
    }
  }
}
