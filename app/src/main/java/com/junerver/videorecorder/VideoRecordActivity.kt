package com.junerver.videorecorder

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.junerver.videorecorder.VideoEncoderOption.Companion.fromExtra
import com.junerver.videorecorder.gles.VideoEncoderCore
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class VideoRecordActivity : AppCompatActivity() {

  private lateinit var viewFinder: TextureView
  private lateinit var playbackSurface: SurfaceView
  private lateinit var thumbnailView: ImageView
  private lateinit var btnRecord: Button
  private lateinit var btnPlay: Button
  private lateinit var btnFlip: Button
  private lateinit var btnCancel: Button
  private lateinit var btnSubmit: Button
  private lateinit var recordPanel: LinearLayout
  private lateinit var operatePanel: LinearLayout
  private lateinit var progressBar: CircleProgressBar
  private lateinit var statusText: TextView

  private lateinit var requestedOption: VideoEncoderOption

  private var cameraController: Camera2Controller? = null
  private var codecRecorder: CodecRecorder? = null
  private var mediaPlayer: MediaPlayer? = null

  // 权限请求启动器
  private val requestPermissionsLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
  ) { permissions ->
    val allGranted = permissions.entries.all { it.value }
    if (allGranted) {
      startCameraController()
    } else {
      val dialog = AlertDialog.Builder(this)
        .setTitle("权限申请")
        .setMessage(
          "${
            getString(
              R.string.encoder_permission_rationale,
              requestedOption.label
            )
          }为必选项，开通后方可正常使用APP,请在设置中开启。"
        )
        .setCancelable(false)
        .setPositiveButton("去开启") { _, _ ->
          finish()
        }
        .setNegativeButton("结束") { _, _ ->
          Toast.makeText(
            this,
            "${
              getString(
                R.string.encoder_permission_rationale,
                requestedOption.label
              )
            }权限未开启，不能使用该功能！",
            Toast.LENGTH_SHORT
          ).show()
          finish()
        }
        .create()
      dialog.show()
    }
  }

  private var captureState = CaptureState.PREVIEW
  private var videoPath = ""
  private var imagePath = ""
  private var playbackSurfaceReady = false

  private val uiHandler = Handler(Looper.getMainLooper())
  private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
  private var touchStartTime = 0L
  private val longPressRunnable = Runnable { startVideoRecording() }
  private val progressRunnable = object : Runnable {
    override fun run() {
      // 检查是否正在录制或正在开始录制
      if (codecRecorder?.isRecording != true && !isRecordingStarting) {
        // 如果录制已停止且不是在开始过程中，停止进度更新
        isRecordingStarting = false
        return
      }
      val elapsed = System.currentTimeMillis() - recordingStartTs
      val percent = (elapsed * 100 / MAX_DURATION_MS).toInt().coerceAtMost(100)
      progressBar.progress = percent
      if (elapsed >= MAX_DURATION_MS) {
        stopVideoRecording()
      } else {
        uiHandler.postDelayed(this, PROGRESS_INTERVAL_MS)
      }
    }
  }
  private var recordingStartTs = 0L
  private var isRecordingStarting = false // 跟踪录制是否正在开始过程中

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    requestedOption = fromExtra(intent.getStringExtra(EXTRA_ENCODER_OPTION))
    setContentView(R.layout.activity_video_record)
    initViews()
    ensurePermissions()
  }

  override fun onDestroy() {
    super.onDestroy()
    coroutineScope.cancel()
    stopVideoRecording(force = true)
    stopPlayback()
    codecRecorder?.release()
    cameraController?.shutdown()
  }

  override fun onBackPressed() {
    when (captureState) {
      CaptureState.IMAGE_REVIEW, CaptureState.VIDEO_REVIEW -> resetToPreview(deleteFiles = false)
      else -> super.onBackPressed()
    }
  }

  private fun initViews() {
    viewFinder = findViewById(R.id.view_finder)
    playbackSurface = findViewById(R.id.mSurfaceview)
    thumbnailView = findViewById(R.id.mImageView)
    btnRecord = findViewById(R.id.mBtnRecord)
    btnPlay = findViewById(R.id.mBtnPlay)
    btnFlip = findViewById(R.id.mBtnFlip)
    btnCancel = findViewById(R.id.mBtnCancle)
    btnSubmit = findViewById(R.id.mBtnSubmit)
    recordPanel = findViewById(R.id.mLlRecordBtn)
    operatePanel = findViewById(R.id.mLlRecordOp)
    progressBar = findViewById(R.id.mProgress)
    statusText = findViewById(R.id.mTvPlayLoading)
    progressBar.max = 100

    statusText.visibility = View.INVISIBLE
    progressBar.visibility = View.INVISIBLE
    btnPlay.visibility = View.INVISIBLE
    operatePanel.visibility = View.INVISIBLE
    playbackSurface.visibility = View.GONE
    thumbnailView.visibility = View.GONE
    btnRecord.text = ""

    playbackSurface.holder.addCallback(object : SurfaceHolder.Callback {
      override fun surfaceCreated(holder: SurfaceHolder) {
        playbackSurfaceReady = true
      }

      override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
      override fun surfaceDestroyed(holder: SurfaceHolder) {
        playbackSurfaceReady = false
        stopPlayback()
      }
    })

    btnRecord.setOnTouchListener { _, event ->
      when (event.action) {
        MotionEvent.ACTION_DOWN -> {
          touchStartTime = System.currentTimeMillis()
          uiHandler.postDelayed(longPressRunnable, LONG_PRESS_THRESHOLD_MS)
          true
        }

        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
          uiHandler.removeCallbacks(longPressRunnable)
          if (codecRecorder?.isRecording == true) {
            stopVideoRecording()
          } else {
            val tapDuration = System.currentTimeMillis() - touchStartTime
            if (tapDuration < LONG_PRESS_THRESHOLD_MS) {
              capturePhotoFromPreview()
            }
          }
          true
        }

        else -> false
      }
    }

    btnPlay.setOnClickListener { playRecordedVideo() }
    btnFlip.setOnClickListener {
      if (codecRecorder?.isRecording == true) {
        Toast.makeText(this, R.string.record_switch_blocked, Toast.LENGTH_SHORT).show()
      } else {
        cameraController?.switchCamera()
      }
    }
    btnCancel.setOnClickListener { resetToPreview(deleteFiles = true) }
    btnSubmit.setOnClickListener {
      Log.d("VideoRecordActivity", "确认按钮被点击")
      finishWithResult()
    }
  }

  private fun ensurePermissions() {
    requestPermissionsLauncher.launch(
      arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
      )
    )
  }

  private fun startCameraController() {
    cameraController?.shutdown()
    cameraController =
      Camera2Controller(this, viewFinder, { /* ready */ }, requestedOption.containerFormat)
    cameraController?.start()
  }

  private fun startVideoRecording() {
    if (codecRecorder?.isRecording == true) return
    if (!requestedOption.isUsableOnDevice()) {
      Toast.makeText(this, R.string.main_codec_empty, Toast.LENGTH_SHORT).show()
      return
    }
    val controller = cameraController ?: return
    val outputFile =
      MediaUtils.getOutputMediaFile(MediaUtils.MEDIA_TYPE_VIDEO, requestedOption) ?: run {
        Toast.makeText(this, R.string.record_file_failed, Toast.LENGTH_SHORT).show()
        return
      }

    try {
      // 立即开始UI更新，给用户即时反馈
      isRecordingStarting = true
      startRecordingUI()

      // 在后台线程创建CodecRecorder
      Thread {
        try {
          val recorder = CodecRecorder(
            option = requestedOption,
            outputFile = outputFile,
            orientationHint = controller.currentOrientationHint,
            videoSize = controller.previewSize
          )
          val surface = recorder.startRecording()

          // 切换到主线程更新Camera session
          runOnUiThread {
            codecRecorder = recorder
            controller.startRecordingSession(surface)
            videoPath = outputFile.absolutePath
            captureState = CaptureState.PREVIEW

            // 立即捕获并保存首帧缩略图
            captureInitialThumbnail()
          }
        } catch (e: Exception) {
          runOnUiThread {
            Toast.makeText(
              this,
              getString(R.string.record_start_failed, e.message),
              Toast.LENGTH_SHORT
            ).show()
            stopRecordingUI()
            codecRecorder?.release()
            codecRecorder = null
            isRecordingStarting = false
          }
        }
      }.start()
    } catch (e: Exception) {
      Toast.makeText(this, getString(R.string.record_start_failed, e.message), Toast.LENGTH_SHORT)
        .show()
      codecRecorder?.release()
      codecRecorder = null
      isRecordingStarting = false
      stopRecordingUI()
    }
  }

  private fun stopVideoRecording(force: Boolean = false) {
    val recorder = codecRecorder ?: return
    isRecordingStarting = false // 确保停止录制时重置状态
    stopRecordingUI()
    cameraController?.stopRecordingSession()
    codecRecorder = null

    if (force) {
      recorder.stopRecording()
      recorder.release()
      resetToPreview(deleteFiles = false)
      return
    }

    // 立即切换到预览界面，提供即时UI响应
    showVideoReviewFallback()

    // 优化：立即显示UI，但延迟启用播放按钮直到文件真正可用
    showSavingState(false)  // 立即隐藏保存状态
    // 不立即启用播放按钮，等待后台操作完成
    btnPlay.visibility = View.INVISIBLE  // 初始隐藏播放按钮
    Log.d("VideoRecordActivity", "立即显示UI，等待后台保存操作完成后启用播放按钮")

    // 调试信息：检查各个View的状态
    Log.d("VideoRecordActivity", "View状态检查:")
    Log.d("VideoRecordActivity", "  operatePanel.visibility: ${operatePanel.visibility}")
    Log.d("VideoRecordActivity", "  btnSubmit.visibility: ${btnSubmit.visibility}")
    Log.d("VideoRecordActivity", "  btnSubmit.isEnabled: ${btnSubmit.isEnabled}")
    Log.d("VideoRecordActivity", "  btnCancel.visibility: ${btnCancel.visibility}")
    Log.d("VideoRecordActivity", "  progressBar.visibility: ${progressBar.visibility}")
    Log.d("VideoRecordActivity", "  playbackSurface.visibility: ${playbackSurface.visibility}")

    // 优化：使用事件驱动的回调机制，而不是固定等待
    val startTime = System.currentTimeMillis()
    Log.d("VideoRecordActivity", "开始设置编码器完成回调，startTime=$startTime")

    try {
      recorder.setOnRecordingComplete {
        val callbackTime = System.currentTimeMillis()
        Log.d(
          "VideoRecordActivity",
          "✅ 编码器完成回调被触发！callbackTime=$callbackTime, 总耗时=${callbackTime - startTime}ms"
        )
        Log.d("VideoRecordActivity", "回调线程: ${Thread.currentThread().name}")

        Thread {
          Log.d("VideoRecordActivity", "🚀 异步Thread开始执行，线程: ${Thread.currentThread().name}")
          val releaseStartTime = System.currentTimeMillis()
          Log.d(
            "VideoRecordActivity",
            "开始执行recorder.release()，releaseStartTime=$releaseStartTime"
          )

          recorder.release()

          val releaseEndTime = System.currentTimeMillis()
          val releaseDuration = releaseEndTime - releaseStartTime
          val totalDuration = releaseEndTime - startTime
          Log.d("VideoRecordActivity", "recorder.release()完成，耗时=${releaseDuration}ms")
          Log.d("VideoRecordActivity", "编码器异步处理完全完成，总耗时=${totalDuration}ms")

          // 检查文件状态
          val videoFile = File(videoPath)
          if (videoFile.exists()) {
            Log.d("VideoRecordActivity", "文件状态检查: 存在=true, 大小=${videoFile.length()}bytes")
          } else {
            Log.w("VideoRecordActivity", "文件状态检查: 存在=false")
          }

          Log.d("VideoRecordActivity", "准备在UI线程显示播放按钮")
          // 在编码器真正完成后，再启用播放按钮
          runOnUiThread {
            Log.d(
              "VideoRecordActivity",
              "🎯 UI线程执行中，准备显示播放按钮，当前线程: ${Thread.currentThread().name}"
            )
            btnPlay.visibility = View.VISIBLE
            Log.d(
              "VideoRecordActivity",
              "✅ 播放按钮已启用！总耗时=${totalDuration}ms，btnPlay.visibility=${btnPlay.visibility}"
            )
          }
          Log.d("VideoRecordActivity", "runOnUiThread调用完成")
        }.apply {
          Log.d("VideoRecordActivity", "异步Thread已创建并启动")
          start()
        }
        Log.d("VideoRecordActivity", "setOnRecordingComplete回调处理完成")
      }
      Log.d("VideoRecordActivity", "✅ 编码器完成回调设置成功")
    } catch (e: Exception) {
      Log.e("VideoRecordActivity", "❌ 设置编码器完成回调失败", e)
    }

    // 只发送停止信号，不阻塞等待
    Log.d("VideoRecordActivity", "准备调用recorder.stopRecording()，等待编码器完成回调...")
    recorder.stopRecording()
    Log.d("VideoRecordActivity", "recorder.stopRecording()调用完成，现在等待编码器回调...")
  }

  private fun startRecordingUI() {
    recordingStartTs = System.currentTimeMillis()
    progressBar.progress = 0
    progressBar.visibility = View.VISIBLE
    uiHandler.post(progressRunnable)
  }

  private fun stopRecordingUI() {
    uiHandler.removeCallbacks(progressRunnable)
    progressBar.visibility = View.INVISIBLE
    isRecordingStarting = false
  }

  private fun capturePhotoFromPreview() {
    val bitmap = viewFinder.bitmap ?: run {
      Toast.makeText(this, R.string.photo_capture_failed, Toast.LENGTH_SHORT).show()
      return
    }
    val imageFile = MediaUtils.getOutputMediaFile(MediaUtils.MEDIA_TYPE_IMAGE) ?: run {
      Toast.makeText(this, R.string.photo_capture_failed, Toast.LENGTH_SHORT).show()
      return
    }
    try {
      FileOutputStream(imageFile).use {
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
      }
      imagePath = imageFile.absolutePath
      showImageReview(imagePath)
    } catch (e: Exception) {
      Toast.makeText(this, R.string.photo_capture_failed, Toast.LENGTH_SHORT).show()
    }
  }

  private fun showImageReview(path: String) {
    captureState = CaptureState.IMAGE_REVIEW
    recordPanel.visibility = View.GONE
    operatePanel.visibility = View.VISIBLE
    btnPlay.visibility = View.INVISIBLE
    btnFlip.visibility = View.INVISIBLE  // 拍照预览时隐藏切换摄像头按钮
    thumbnailView.visibility = View.VISIBLE
    playbackSurface.visibility = View.GONE
    viewFinder.visibility = View.INVISIBLE
    thumbnailView.setImageBitmap(BitmapFactory.decodeFile(path))
  }

  private fun generateVideoThumbnail() {
    if (videoPath.isEmpty()) {
      resetToPreview(deleteFiles = false)
      return
    }
    val retriever = MediaMetadataRetriever()
    try {
      retriever.setDataSource(videoPath)
      val bitmap = retriever.frameAtTime
      if (bitmap != null) {
        val imageFile = MediaUtils.getOutputMediaFile(MediaUtils.MEDIA_TYPE_IMAGE)
        if (imageFile != null) {
          FileOutputStream(imageFile).use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)
          }
          imagePath = imageFile.absolutePath
          showVideoReview(imagePath)
          return
        }
      }
    } catch (_: Exception) {
    } finally {
      try {
        retriever.release()
      } catch (_: Exception) {
      }
    }
    showVideoReviewFallback()
  }

  private fun captureInitialThumbnail() {
    // 从Camera预览捕获当前帧作为缩略图
    val bitmap = viewFinder.bitmap
    if (bitmap != null) {
      Thread {
        try {
          val imageFile = MediaUtils.getOutputMediaFile(MediaUtils.MEDIA_TYPE_IMAGE)
          if (imageFile != null) {
            // 统一缩略图尺寸，避免缩放问题
            val targetSize = 480 // 统一的最大边长
            val scale = if (bitmap.width > bitmap.height) {
              targetSize.toFloat() / bitmap.width
            } else {
              targetSize.toFloat() / bitmap.height
            }
            val newWidth = (bitmap.width * scale).toInt()
            val newHeight = (bitmap.height * scale).toInt()

            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)

            FileOutputStream(imageFile).use { out ->
              scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            imagePath = imageFile.absolutePath

            // 在主线程更新预览显示
            runOnUiThread {
              if (captureState == CaptureState.VIDEO_REVIEW) {
                thumbnailView.setImageBitmap(scaledBitmap)
                thumbnailView.visibility = View.VISIBLE
              }
            }
          }
        } catch (e: Exception) {
          Log.e("VideoRecordActivity", "保存初始缩略图失败", e)
        }
      }.start()
    }
  }


  private fun showVideoReview(thumbnailPath: String) {
    captureState = CaptureState.VIDEO_REVIEW
    recordPanel.visibility = View.GONE
    operatePanel.visibility = View.VISIBLE
    btnPlay.visibility = View.VISIBLE
    thumbnailView.visibility = View.VISIBLE
    playbackSurface.visibility = View.GONE
    viewFinder.visibility = View.INVISIBLE
    thumbnailView.setImageBitmap(BitmapFactory.decodeFile(thumbnailPath))
  }

  private fun showVideoReviewFallback() {
    captureState = CaptureState.VIDEO_REVIEW
    recordPanel.visibility = View.GONE
    operatePanel.visibility = View.VISIBLE
    // 初始隐藏播放按钮，等待后台保存操作完成
    btnPlay.visibility = View.INVISIBLE
    btnFlip.visibility = View.INVISIBLE

    // 关键修复：保持viewFinder可见但透明，这样不会触发surface重建
    // 同时通过设置clickable=false避免拦截触摸事件
    viewFinder.visibility = View.INVISIBLE
    viewFinder.isClickable = false

    // 确保playbackSurface已经准备好（它应该在初始化时就设置了回调）
    // 如果surface还没准备好，设置为可见让它创建
    if (!playbackSurfaceReady) {
      Log.d("VideoRecordActivity", "playbackSurface未准备好，设置为可见让其创建")
      playbackSurface.visibility = View.VISIBLE
      // 稍等一下让surface创建完成
      uiHandler.postDelayed({
        Log.d(
          "VideoRecordActivity",
          "等待surface创建完成，playbackSurfaceReady=$playbackSurfaceReady"
        )
      }, 200)
    }

    // 如果已经有预先保存的缩略图，立即显示
    if (imagePath.isNotEmpty()) {
      val bitmap = BitmapFactory.decodeFile(imagePath)
      if (bitmap != null) {
        thumbnailView.setImageBitmap(bitmap)
        thumbnailView.visibility = View.VISIBLE
        Log.d("VideoRecordActivity", "显示预先保存的缩略图: $imagePath")
      } else {
        thumbnailView.visibility = View.GONE
      }
    } else {
      thumbnailView.visibility = View.GONE
    }

    Log.d(
      "VideoRecordActivity",
      "showVideoReviewFallback完成，确保playbackSurfaceReady=$playbackSurfaceReady"
    )
  }

  private fun resetToPreview(deleteFiles: Boolean) {
    stopPlayback()
    captureState = CaptureState.PREVIEW
    recordPanel.visibility = View.VISIBLE
    operatePanel.visibility = View.INVISIBLE
    btnPlay.visibility = View.INVISIBLE
    btnFlip.visibility = View.VISIBLE  // 恢复切换摄像头按钮
    thumbnailView.visibility = View.GONE
    playbackSurface.visibility = View.GONE
    viewFinder.visibility = View.VISIBLE
    statusText.visibility = View.INVISIBLE
    progressBar.visibility = View.INVISIBLE
    btnRecord.text = ""
    if (deleteFiles) {
      if (videoPath.isNotEmpty()) File(videoPath).delete()
      if (imagePath.isNotEmpty()) File(imagePath).delete()
    }
    videoPath = ""
    imagePath = ""
  }

  private fun playRecordedVideo() {
    Log.d("VideoRecordActivity", "播放按钮被点击")
    Log.d("VideoRecordActivity", "playbackSurfaceReady: $playbackSurfaceReady")
    Log.d("VideoRecordActivity", "videoPath: $videoPath")

    if (videoPath.isEmpty()) {
      Log.w("VideoRecordActivity", "视频文件路径为空")
      Toast.makeText(this, "视频文件不存在", Toast.LENGTH_SHORT).show()
      return
    }

    // 检查视频文件是否存在且可读
    val videoFile = File(videoPath)
    if (!videoFile.exists()) {
      Log.w("VideoRecordActivity", "视频文件不存在: $videoPath")
      Toast.makeText(this, "视频文件不存在", Toast.LENGTH_SHORT).show()
      return
    }

    if (!videoFile.canRead()) {
      Log.w("VideoRecordActivity", "视频文件不可读: $videoPath")
      Toast.makeText(this, "视频文件不可读", Toast.LENGTH_SHORT).show()
      return
    }

    // 检查文件大小，如果太小可能还在写入
    val fileSize = videoFile.length()
    Log.d("VideoRecordActivity", "视频文件大小: ${fileSize} bytes")
    if (fileSize < 1024) { // 小于1KB可能文件不完整
      Log.w("VideoRecordActivity", "视频文件太小，可能还在写入中: ${fileSize} bytes")
      Toast.makeText(this, "视频正在处理中，请稍后再试", Toast.LENGTH_SHORT).show()
      // 延迟500ms后重试
      uiHandler.postDelayed({
        playRecordedVideo()
      }, 500)
      return
    }

    // 如果Surface还没准备好，强制设置playbackSurface可见并等待
    if (!playbackSurfaceReady) {
      Log.w("VideoRecordActivity", "播放Surface未准备好，强制设置为可见并等待...")
      playbackSurface.visibility = View.VISIBLE

      // 等待更长时间让surface创建
      uiHandler.postDelayed({
        if (playbackSurfaceReady) {
          Log.d("VideoRecordActivity", "Surface已准备好，继续播放")
          playRecordedVideo() // 递归调用重新播放
        } else {
          Log.e("VideoRecordActivity", "Surface创建超时，无法播放")
          Toast.makeText(this, "播放器初始化失败，请重试", Toast.LENGTH_SHORT).show()
        }
      }, 500)
      return
    }

    Log.d("VideoRecordActivity", "开始播放视频: $videoPath")
    stopPlayback()
    playbackSurface.visibility = View.VISIBLE
    thumbnailView.visibility = View.GONE
    // 播放时隐藏播放按钮
    btnPlay.visibility = View.GONE

    mediaPlayer = MediaPlayer().apply {
      try {
        // 修复：使用文件路径而不是URI
        setDataSource(videoPath)
        setDisplay(playbackSurface.holder)
        setOnPreparedListener {
          Log.d("VideoRecordActivity", "视频准备完成，开始播放")
          start()
        }
        setOnCompletionListener {
          Log.d("VideoRecordActivity", "视频播放完成")
          stopPlayback()
          thumbnailView.visibility = View.VISIBLE
          // 播放完成后重新显示播放按钮
          btnPlay.visibility = View.VISIBLE
        }
        setOnErrorListener { _, what, extra ->
          Log.e("VideoRecordActivity", "视频播放出错: $what/$extra")
          Toast.makeText(this@VideoRecordActivity, "播放失败: $what/$extra", Toast.LENGTH_SHORT)
            .show()
          stopPlayback()
          // 出错时也要重新显示播放按钮
          btnPlay.visibility = View.VISIBLE
          true
        }
        prepareAsync()
        Log.d("VideoRecordActivity", "开始准备视频播放")
      } catch (e: Exception) {
        Log.e("VideoRecordActivity", "设置MediaPlayer失败", e)
        Toast.makeText(this@VideoRecordActivity, "播放器设置失败: ${e.message}", Toast.LENGTH_SHORT)
          .show()
        stopPlayback()
        // 异常情况下也要重新显示播放按钮
        btnPlay.visibility = View.VISIBLE
      }
    }
  }

  private fun stopPlayback() {
    mediaPlayer?.apply {
      try {
        stop()
      } catch (_: Exception) {
      }
      release()
    }
    mediaPlayer = null
    // 不要隐藏playbackSurface，保持Surface可用以支持重复播放
    // playbackSurface.visibility = View.GONE
  }

  /**
   * 智能检测视频文件是否真正可用并启用播放按钮
   * 替代硬编码的500ms延迟
   */
  private fun checkVideoFileAndEnablePlayback() {
    // 启动一个后台线程来检测文件就绪状态
    Thread {
      val startTime = System.currentTimeMillis()
      val maxWaitTime = 2000L // 最多等待2秒
      val checkInterval = 50L // 每50ms检查一次

      while (System.currentTimeMillis() - startTime < maxWaitTime) {
        if (isVideoFileReady(videoPath)) {
          // 文件就绪，立即启用播放按钮
          val elapsedTime = System.currentTimeMillis() - startTime
          runOnUiThread {
            btnPlay.visibility = View.VISIBLE
            // 拍摄完毕后不显示切换摄像头按钮
            // btnFlip.visibility = View.VISIBLE
            Log.d("VideoRecordActivity", "视频文件就绪，启用播放按钮，检测耗时: ${elapsedTime}ms")
          }
          return@Thread
        }

        // 短暂等待后再次检查
        Thread.sleep(checkInterval)
      }

      // 超时处理：即使文件可能不完美，也启用播放按钮
      runOnUiThread {
        btnPlay.visibility = View.VISIBLE
        // btnFlip.visibility = View.VISIBLE
        Log.w("VideoRecordActivity", "视频文件检测超时，强制启用播放按钮")
      }
    }.start()
  }

  /**
   * 检查视频文件是否真正准备好可以播放
   * @param videoPath 视频文件路径
   * @return true表示文件就绪，false表示还需要等待
   */
  private fun isVideoFileReady(videoPath: String): Boolean {
    if (videoPath.isEmpty()) return false

    val videoFile = File(videoPath)

    // 1. 检查文件是否存在
    if (!videoFile.exists()) {
      Log.d("VideoFileReady", "文件不存在: $videoPath")
      return false
    }

    // 2. 检查文件大小（至少要有几KB的数据）
    val fileSize = videoFile.length()
    if (fileSize < 4096) { // 小于4KB认为文件还不完整
      Log.d("VideoFileReady", "文件太小: ${fileSize} bytes")
      return false
    }

    // 3. 使用MediaMetadataRetriever进行实际检测
    return try {
      val retriever = MediaMetadataRetriever()
      retriever.setDataSource(videoPath)

      // 尝试获取基本信息
      val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
      val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
      val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)

      retriever.release()

      val isReady = !duration.isNullOrEmpty() && duration != "0" &&
          !width.isNullOrEmpty() && !height.isNullOrEmpty()

      Log.d(
        "VideoFileReady",
        "检测结果: duration=$duration, size=${width}x${height}, ready=$isReady"
      )
      isReady
    } catch (e: Exception) {
      Log.d("VideoFileReady", "MediaMetadataRetriever检测失败: ${e.message}")
      false
    }
  }

  private fun finishWithResult() {
    Log.d(
      "VideoRecordActivity",
      "finishWithResult() 被调用，captureState=$captureState, videoPath='$videoPath', imagePath='$imagePath'"
    )

    when (captureState) {
      CaptureState.IMAGE_REVIEW -> {
        if (imagePath.isEmpty()) {
          Log.e("VideoRecordActivity", "图片路径为空，无法返回结果")
          Toast.makeText(this, R.string.photo_capture_failed, Toast.LENGTH_SHORT).show()
          return
        }
        Log.d("VideoRecordActivity", "返回图片结果: $imagePath")
        setResult(Activity.RESULT_OK, Intent().apply {
          putExtra("path", imagePath)
          putExtra("imagePath", imagePath)
          putExtra("type", TYPE_IMAGE)
        })
        finish()
      }

      CaptureState.VIDEO_REVIEW -> {
        Log.d("VideoRecordActivity", "视频模式，检查videoPath: '$videoPath'")
        if (videoPath.isEmpty()) {
          Log.e("VideoRecordActivity", "视频路径为空，无法返回结果")
          Toast.makeText(this, R.string.record_file_failed, Toast.LENGTH_SHORT).show()
          return
        }

        // 额外检查：确保视频文件确实存在
        val videoFile = File(videoPath)
        if (!videoFile.exists()) {
          Log.e("VideoRecordActivity", "视频文件不存在: $videoPath")
          Toast.makeText(this, "视频文件不存在，请重新录制", Toast.LENGTH_SHORT).show()
          return
        }

        Log.d(
          "VideoRecordActivity",
          "返回视频结果: $videoPath (文件大小: ${videoFile.length()} bytes)"
        )
        setResult(Activity.RESULT_OK, Intent().apply {
          putExtra("path", videoPath)
          putExtra("imagePath", imagePath)
          putExtra("type", TYPE_VIDEO)
        })
        finish()
      }

      else -> {
        Log.w("VideoRecordActivity", "未知的captureState: $captureState，无法返回结果")
        Toast.makeText(this, R.string.record_nothing, Toast.LENGTH_SHORT).show()
      }
    }
  }

  private fun showSavingState(show: Boolean) {
    progressBar.isIndeterminate = show
    progressBar.visibility = if (show) View.VISIBLE else View.INVISIBLE
    btnRecord.isEnabled = !show
    btnFlip.isEnabled = !show
    btnCancel.isEnabled = !show
    btnSubmit.isEnabled = !show
  }

  private enum class CaptureState {
    PREVIEW, IMAGE_REVIEW, VIDEO_REVIEW
  }

  companion object {
    const val EXTRA_ENCODER_OPTION = "extra_video_encoder_option"
    const val TYPE_VIDEO = 0
    const val TYPE_IMAGE = 1
    private const val MAX_DURATION_MS = 10_000L
    private const val LONG_PRESS_THRESHOLD_MS = 100L
    private const val PROGRESS_INTERVAL_MS = 100L
  }
}

private class Camera2Controller(
  private val context: Activity,
  private val textureView: TextureView,
  private val onReady: () -> Unit,
  private val containerFormat: Int = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
) {
  private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
  private val cameraThread = HandlerThread("CodecCameraThread").apply { start() }
  private val cameraHandler = Handler(cameraThread.looper)
  private var cameraDevice: CameraDevice? = null
  private var captureSession: CameraCaptureSession? = null
  private var previewSurface: Surface? = null
  private var recordingSurface: Surface? = null
  private var currentCameraIndex = 0
  private val cameraIds = manager.cameraIdList
  private var surfaceTextureListener: TextureView.SurfaceTextureListener? = null
  private var isActive = true

  var previewSize: Size = Size(1280, 720)
    private set

  val currentOrientationHint: Int
    get() {
      val rotation = context.windowManager.defaultDisplay.rotation
      val isFront = getLensFacing(currentCameraId()) == CameraCharacteristics.LENS_FACING_FRONT
      return when (rotation) {
        Surface.ROTATION_0 -> if (isFront) 270 else 90
        Surface.ROTATION_90 -> 0
        Surface.ROTATION_180 -> if (isFront) 90 else 270
        Surface.ROTATION_270 -> 180
        else -> 0
      }
    }

  fun start() {
    isActive = true
    if (textureView.isAvailable) {
      openCamera()
    } else {
      surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
          openCamera()
        }

        override fun onSurfaceTextureSizeChanged(
          surface: SurfaceTexture,
          width: Int,
          height: Int
        ) {
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
      }
      textureView.surfaceTextureListener = surfaceTextureListener
    }
  }

  fun shutdown() {
    isActive = false
    textureView.surfaceTextureListener = null
    stopRecordingSession()
    captureSession?.close()
    captureSession = null
    cameraDevice?.close()
    cameraDevice = null
    cameraThread.quitSafely()
  }

  fun switchCamera() {
    if (!isActive) return
    if (cameraIds.size <= 1) return
    currentCameraIndex = (currentCameraIndex + 1) % cameraIds.size
    stopRecordingSession()
    captureSession?.close()
    captureSession = null
    cameraDevice?.close()
    cameraDevice = null
    openCamera()
  }

  fun startRecordingSession(recordSurface: Surface) {
    if (!isActive) return
    recordingSurface = recordSurface
    buildSession(listOfNotNull(previewSurface, recordingSurface))
  }

  fun stopRecordingSession() {
    if (!isActive) return
    captureSession?.close()
    captureSession = null
    recordingSurface = null
    buildSession(listOfNotNull(previewSurface))
  }

  private fun openCamera() {
    if (!isActive) return
    try {
      manager.openCamera(currentCameraId(), object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
          cameraDevice = camera
          createPreviewSurface()
          buildSession(listOfNotNull(previewSurface))
          onReady()
        }

        override fun onDisconnected(camera: CameraDevice) {
          cameraDevice?.close()
          cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
          Toast.makeText(context, R.string.record_camera_unavailable, Toast.LENGTH_SHORT).show()
        }
      }, cameraHandler)
    } catch (_: SecurityException) {
      Toast.makeText(context, R.string.record_camera_unavailable, Toast.LENGTH_SHORT).show()
    }
  }

  private fun createPreviewSurface() {
    val texture = textureView.surfaceTexture ?: return
    previewSize = choosePreviewSize()
    texture.setDefaultBufferSize(previewSize.width, previewSize.height)

    // 为WebM容器应用旋转变换，解决VP8视频方向问题
    if (containerFormat == MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM) {
      applyWebMRotationTransform(texture)
    }

    previewSurface = Surface(texture)
  }

  private fun choosePreviewSize(): Size {
    val map = manager.getCameraCharacteristics(currentCameraId())
      .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    val choices = map?.getOutputSizes(SurfaceTexture::class.java) ?: return Size(1280, 720)
    return choices.minByOrNull { abs(it.width * it.height - 1280 * 720) } ?: choices.first()
  }

  private fun buildSession(targets: List<Surface>) {
    if (!isActive) return
    val device = cameraDevice ?: return
    if (targets.isEmpty()) return
    device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
      override fun onConfigured(session: CameraCaptureSession) {
        if (!isActive || cameraDevice == null) {
          session.close()
          return
        }

        // 关键修复：保存当前session引用
        captureSession = session

        try {
          val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            targets.forEach { addTarget(it) }
          }

          // 再次检查session是否仍然有效（防止切换摄像头时的竞态条件）
          if (captureSession == session && isActive) {
            session.setRepeatingRequest(requestBuilder.build(), null, cameraHandler)
          } else {
            session.close()
          }
        } catch (e: IllegalStateException) {
          // Session已关闭，忽略此错误（通常发生在快速切换摄像头时）
          Log.w("Camera2Controller", "Session已关闭，忽略setRepeatingRequest", e)
        } catch (e: Exception) {
          Log.e("Camera2Controller", "配置预览请求失败", e)
          session.close()
        }
      }

      override fun onConfigureFailed(session: CameraCaptureSession) {
        session.close()
        Toast.makeText(context, R.string.record_camera_unavailable, Toast.LENGTH_SHORT).show()
      }
    }, cameraHandler)
  }

  private fun getLensFacing(cameraId: String): Int {
    return manager.getCameraCharacteristics(cameraId)
      .get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
  }

  private fun currentCameraId(): String = cameraIds[currentCameraIndex]

  /**
   * 为WebM容器应用旋转变换，解决VP8视频方向问题
   * 注意：SurfaceTexture API不支持直接变换，这里记录日志供调试
   * 实际解决方案可能需要OpenGL渲染或其他方法
   */
  private fun applyWebMRotationTransform(texture: SurfaceTexture) {
    try {
      // 获取传感器方向
      val sensorOrientation = getSensorOrientation()
      val isFront = getLensFacing(currentCameraId()) == CameraCharacteristics.LENS_FACING_FRONT

      // 计算期望的旋转角度
      val expectedRotation = when {
        isFront -> {
          // 前置摄像头需要顺时针旋转
          when (sensorOrientation) {
            90 -> 90f    // 传感器90度，顺时针旋转90度
            270 -> -90f  // 传感器270度，逆时针旋转90度
            else -> 0f
          }
        }

        else -> {
          // 后置摄像头需要逆时针旋转
          when (sensorOrientation) {
            90 -> -90f   // 传感器90度，逆时针旋转90度
            270 -> 90f   // 传感器270度，顺时针旋转90度
            else -> 0f
          }
        }
      }

      if (expectedRotation != 0f) {
        Log.w(
          "Camera2Controller",
          "WebM容器需要旋转变换: $expectedRotation° (前置=$isFront, 传感器方向=$sensorOrientation)"
        )
        Log.w(
          "Camera2Controller",
          "注意：当前SurfaceTexture API不支持直接变换，可能需要OpenGL解决方案"
        )
      } else {
        Log.d(
          "Camera2Controller",
          "WebM容器无需旋转变换 (前置=$isFront, 传感器方向=$sensorOrientation)"
        )
      }
    } catch (e: Exception) {
      Log.e("Camera2Controller", "检测WebM旋转需求失败", e)
    }
  }

  /**
   * 获取摄像头传感器的方向
   */
  private fun getSensorOrientation(): Int {
    return try {
      val characteristics = manager.getCameraCharacteristics(currentCameraId())
      characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
    } catch (e: Exception) {
      Log.e("Camera2Controller", "获取传感器方向失败", e)
      0
    }
  }
}

private class CodecRecorder(
  private val option: VideoEncoderOption,
  private val outputFile: File,
  private val orientationHint: Int,
  private val videoSize: Size
) {
  private val codec = MediaCodec.createEncoderByType(option.mime)
  private val bufferInfo = MediaCodec.BufferInfo()
  private val muxer = MediaMuxer(outputFile.absolutePath, option.containerFormat)
  private var muxerStarted = false
  private var muxerVideoTrack = -1
  private var drainThread: Thread? = null
  private var drainLatch: CountDownLatch? = null
  private var inputSurface: Surface? = null
  private var onRecordingComplete: (() -> Unit)? = null
  private var videoEncoderCore: VideoEncoderCore? = null

  val isRecording: Boolean
    get() = drainThread != null

  fun startRecording(): Surface {
    val isWebM = option.containerFormat == MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
    val isFrontCamera = orientationHint == 270  // 前置摄像头

    // 扩展OpenGL管道使用条件：
    // 1. WebM需要旋转（原有逻辑）
    // 2. 前置摄像头（修复镜像问题）
    val needsOpenGL = isFrontCamera || (isWebM && orientationHint != 0 && orientationHint != 180)

    val containerName = when (option.containerFormat) {
      MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4 -> "MP4"
      MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM -> "WebM"
      else -> "Unknown"
    }

    Log.d("CodecRecorder", "==================== 开始录制 ====================")
    Log.d("CodecRecorder", "容器格式: $containerName")
    Log.d("CodecRecorder", "orientationHint: $orientationHint")
    Log.d("CodecRecorder", "视频尺寸: ${videoSize.width}x${videoSize.height}")
    Log.d("CodecRecorder", "摄像头类型: ${if (isFrontCamera) "前置" else "后置"}")
    Log.d("CodecRecorder", "需要OpenGL管道: $needsOpenGL")
    Log.d("CodecRecorder", "=================================================")

    // 关键修复：使用OpenGL时需要交换宽高（因为旋转90度）
    val encoderWidth = if (needsOpenGL) videoSize.height else videoSize.width
    val encoderHeight = if (needsOpenGL) videoSize.width else videoSize.height

    Log.d("CodecRecorder", "编码器输出尺寸: ${encoderWidth}x${encoderHeight}")

    val format = MediaFormat.createVideoFormat(option.mime, encoderWidth, encoderHeight).apply {
      setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
      setInteger(MediaFormat.KEY_BIT_RATE, option.defaultBitrate)
      setInteger(MediaFormat.KEY_FRAME_RATE, option.defaultFrameRate)
      setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
    }
    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    inputSurface = codec.createInputSurface()
    codec.start()

    // 为MP4设置旋转提示（仅后置摄像头，MP4播放器支持良好）
    // 前置摄像头和WebM都通过OpenGL物理旋转，不依赖元数据
    if (!needsOpenGL && !isWebM) {
      try {
        muxer.setOrientationHint(orientationHint)
        Log.d("CodecRecorder", "MP4容器（后置摄像头），已设置旋转提示为 $orientationHint")
      } catch (e: Exception) {
        Log.w("CodecRecorder", "设置旋转提示失败（忽略）", e)
      }
    }

    drainLatch = CountDownLatch(1)
    drainThread = Thread { drainEncoderLoop() }.apply { start() }

    // 关键：根据是否需要OpenGL管道返回不同的Surface
    return try {
      if (needsOpenGL) {
        // 需要OpenGL管道（WebM旋转或前置摄像头镜像修复）
        Log.d("CodecRecorder", "✨ 开始创建OpenGL渲染管道（旋转+镜像修复）...")
        // 传递Camera输入尺寸，用于设置SurfaceTexture buffer和OpenGL viewport
        videoEncoderCore = VideoEncoderCore(
          inputSurface!!,
          orientationHint,
          videoSize.width,   // Camera输入宽度
          videoSize.height   // Camera输入高度
        )
        Log.d(
          "CodecRecorder",
          "VideoEncoderCore已创建（输入=${videoSize.width}x${videoSize.height}），准备初始化..."
        )
        videoEncoderCore!!.prepare()
        Log.d("CodecRecorder", "VideoEncoderCore初始化完成，获取SurfaceTexture...")
        val surfaceTexture = videoEncoderCore!!.getCameraSurfaceTexture()
        Log.d("CodecRecorder", "SurfaceTexture已获取，创建Surface...")
        val surface = Surface(surfaceTexture)
        Log.d("CodecRecorder", "✅ OpenGL渲染管道创建成功")
        surface
      } else {
        // 后置摄像头MP4：直接使用MediaCodec的Surface
        Log.d("CodecRecorder", "使用直连模式（无OpenGL）")
        inputSurface!!
      }
    } catch (e: Exception) {
      Log.e("CodecRecorder", "❌ 创建录制Surface失败", e)
      // 回退到直连模式
      Log.w("CodecRecorder", "回退到直连模式")
      videoEncoderCore?.release()
      videoEncoderCore = null
      inputSurface!!
    }
  }

  fun stopRecording() {
    Log.d("CodecRecorder", "stopRecording()被调用，准备发送结束信号")
    try {
      MediaCodec::class.java.getMethod("signalEndOfInputStream").invoke(codec)
      Log.d("CodecRecorder", "signalEndOfInputStream调用成功")
    } catch (e: Exception) {
      Log.w("CodecRecorder", "signalEndOfInputStream调用失败，尝试设置drainLatch为null来结束循环", e)
      // 如果signalEndOfInputStream不可用，设置drainLatch为null来结束循环
      drainLatch = null
    }
    try {
      Log.d("CodecRecorder", "开始等待drainLatch，最多等待1秒")
      // 优化：移除固定等待时间，改为监听真实完成事件
      // drainLatch会在drainEncoderLoop()完成时自动调用countDown()
      val completed = drainLatch?.await(1, TimeUnit.SECONDS) ?: false
      Log.d("CodecRecorder", "drainLatch等待完成，completed=$completed")
    } catch (e: Exception) {
      Log.e("CodecRecorder", "等待drainLatch时发生异常", e)
    }
  }

  fun setOnRecordingComplete(callback: () -> Unit) {
    Log.d("CodecRecorder", "设置编码器完成回调，当前线程: ${Thread.currentThread().name}")
    onRecordingComplete = callback
    Log.d("CodecRecorder", "编码器完成回调设置完成")
  }

  fun release() {
    try {
      drainThread?.join(100)
    } catch (_: Exception) {
    }
    drainThread = null
    drainLatch = null
    try {
      if (muxerStarted) muxer.stop()
    } catch (_: Exception) {
    }
    muxer.release()
    try {
      codec.stop()
    } catch (_: Exception) {
    }
    codec.release()

    // 释放OpenGL资源
    videoEncoderCore?.release()
    videoEncoderCore = null

    inputSurface?.release()
    inputSurface = null
  }

  private fun drainEncoderLoop() {
    Log.d("CodecRecorder", "drainEncoderLoop开始运行，线程: ${Thread.currentThread().name}")
    var frameCount = 0
    var firstPtsUs = -1L
    var lastPtsUs = 0L
    val fps = option.defaultFrameRate.takeIf { it > 0 } ?: 30
    val minStepUs = 1_000_000L / fps

    try {
      while (drainLatch != null) {
        val index = codec.dequeueOutputBuffer(bufferInfo, 10_000)
        when {
          index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
            if (muxerStarted) continue
            val newFormat = codec.outputFormat
            muxerVideoTrack = muxer.addTrack(newFormat)
            muxer.start()
            muxerStarted = true
            Log.d("CodecRecorder", "muxer已启动，视频轨道: $muxerVideoTrack")
          }

          index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
            if (drainLatch == null) break
          }

          index >= 0 -> {
            val encodedData = codec.getOutputBuffer(index) ?: continue

            // 忽略 codec config
            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
              bufferInfo.size = 0
              codec.releaseOutputBuffer(index, false)
              continue
            }

            // 检查流是否结束
            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
              Log.d("CodecRecorder", "收到流结束标志 (EOS)")
              // 在 break 之前，我们可能仍需要将这个带 EOS 标志的 buffer 写入
              // (尽管它的大小通常为 0)，以正确关闭 muxer
              // 但在这里，我们直接 break，在 finally 中处理 muxer.stop()
            } else {
              // ------ 关键修复：时间戳规范化 (开始) ------
              frameCount++ // 只有在不是配置帧和EOS时才计数
              val currentPtsUs = bufferInfo.presentationTimeUs

              if (firstPtsUs == -1L) {
                // 这是第一帧
                firstPtsUs = currentPtsUs
                Log.d("CodecRecorder", "第一帧时间戳 (firstPtsUs) 设置为: $firstPtsUs")
                lastPtsUs = 0L // 确保第一帧的时间戳为 0
              } else {
                // 计算规范化的时间戳（从0开始）
                val normalizedPtsUs = currentPtsUs - firstPtsUs

                // 确保时间戳单调递增
                if (normalizedPtsUs <= lastPtsUs) {
                  // 时间戳回退或相同，我们必须前进一点点
                  // (使用 minStepUs / 2 作为一个小的增量，确保它大于上一帧)
                  lastPtsUs += (minStepUs / 2)
                  Log.w(
                    "CodecRecorder",
                    "时间戳回退: $normalizedPtsUs <= $lastPtsUs (来自 $currentPtsUs). 调整为: $lastPtsUs"
                  )
                } else {
                  lastPtsUs = normalizedPtsUs
                }
              }
              // ------ 关键修复：时间戳规范化 (结束) ------
            }

            // 只在有可写数据且muxer已启动时写入
            if (bufferInfo.size > 0 && muxerStarted && muxerVideoTrack >= 0) {
              // 需要使用一个新的 BufferInfo，因为我们要写入修正后的时间戳 'lastPtsUs'
              val outBufInfo = MediaCodec.BufferInfo()
              // 关键：我们��计算出的 lastPtsUs 写入
              outBufInfo.set(bufferInfo.offset, bufferInfo.size, lastPtsUs, bufferInfo.flags)

              encodedData.position(bufferInfo.offset)
              encodedData.limit(bufferInfo.offset + bufferInfo.size)
              try {
                muxer.writeSampleData(muxerVideoTrack, encodedData, outBufInfo)
                // 调试日志：只记录前几帧
                if (frameCount <= 5) {
                  Log.d(
                    "CodecRecorder",
                    "帧$frameCount 写入: 原始 ${bufferInfo.presentationTimeUs} -> 修正 $lastPtsUs"
                  )
                }
              } catch (e: Exception) {
                Log.e("CodecRecorder", "写入 muxer 失败", e)
              }
            }

            codec.releaseOutputBuffer(index, false)

            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
              Log.d("CodecRecorder", "流结束，总帧数: $frameCount, 最后时间戳: $lastPtsUs")
              break
            }
          }
        }
      }
    } catch (e: Exception) {
      Log.e("CodecRecorder", "drain error", e)
    } finally {
      Log.d("CodecRecorder", "drainEncoderLoop进入finally块，准备调用回调")
      if (frameCount > 0) {
        val estimatedDuration = (lastPtsUs - firstPtsUs) / 1_000_000L
        Log.d(
          "CodecRecorder",
          "编码统计: 帧数=$frameCount, 开始=$firstPtsUs, 结束=$lastPtsUs, 估计时长=${estimatedDuration}秒"
        )
      }
      drainLatch?.countDown()
      Log.d("CodecRecorder", "drainLatch已countDown，准备调用onRecordingComplete回调")
      // 关键优化：在真正完成时调用回调
      onRecordingComplete?.invoke()
      Log.d("CodecRecorder", "onRecordingComplete回调调用完成")
    }
  }
}
