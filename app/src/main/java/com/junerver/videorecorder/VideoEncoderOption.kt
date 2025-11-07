package com.junerver.videorecorder

import android.media.MediaCodecList
import android.media.MediaMuxer
import android.os.Build

enum class VideoEncoderOption(
  val extraValue: String,
  val fileExtension: String,
  val label: String,
  val mime: String,
  val containerFormat: Int,
  val defaultBitrate: Int,
  val defaultFrameRate: Int,
  val minSdk: Int = Build.VERSION_CODES.LOLLIPOP
) {
  H264(
    extraValue = "h264",
    fileExtension = "mp4",
    label = "H264 / MP4",
    mime = "video/avc",
    containerFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
    defaultBitrate = 8_000_000,
    defaultFrameRate = 30
  ),
  HEVC(
    extraValue = "hevc",
    fileExtension = "mp4",
    label = "HEVC / MP4",
    mime = "video/hevc",
    containerFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
    defaultBitrate = 6_000_000,
    defaultFrameRate = 30,
    minSdk = Build.VERSION_CODES.LOLLIPOP
  ),
  VP8(
    extraValue = "vp8",
    fileExtension = "webm",
    label = "VP8 / WebM",
    mime = "video/x-vnd.on2.vp8",
    containerFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
    } else {
      MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
    },
    defaultBitrate = 4_000_000,
    defaultFrameRate = 30,
    minSdk = Build.VERSION_CODES.R
  );

  fun isUsableOnDevice(): Boolean {
    if (Build.VERSION.SDK_INT < minSdk) return false
    val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
    return codecList.codecInfos.any { info ->
      info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
    }
  }

  companion object {
    fun fromExtra(value: String?): VideoEncoderOption {
      val default = H264
      val target = values().firstOrNull { it.extraValue == value } ?: default
      return if (target.isUsableOnDevice()) target else default
    }

    fun availableOptions(): List<VideoEncoderOption> {
      return values().filter { it.isUsableOnDevice() }
    }
  }
}
