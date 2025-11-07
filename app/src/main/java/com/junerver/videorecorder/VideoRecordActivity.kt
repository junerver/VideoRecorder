package com.junerver.videorecorder

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.net.Uri
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
import androidx.appcompat.app.AppCompatActivity
import com.junerver.videorecorder.VideoEncoderOption.Companion.fromExtra
import io.reactivex.disposables.Disposable
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.*
import me.zhanghai.android.materialprogressbar.MaterialProgressBar

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
  private lateinit var progressBar: MaterialProgressBar
  private lateinit var statusText: TextView

  private lateinit var requestedOption: VideoEncoderOption

  private var permissionsDisposable: Disposable? = null
  private var cameraController: Camera2Controller? = null
  private var codecRecorder: CodecRecorder? = null
  private var mediaPlayer: MediaPlayer? = null

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
    permissionsDisposable?.dispose()
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
    btnSubmit.setOnClickListener { finishWithResult() }
  }

  private fun ensurePermissions() {
    permissionsDisposable = rxRequestPermissions(
      Manifest.permission.CAMERA,
      Manifest.permission.RECORD_AUDIO,
      describe = getString(R.string.encoder_permission_rationale, requestedOption.label)
    ) {
      startCameraController()
    }
  }

  private fun startCameraController() {
    cameraController?.shutdown()
    cameraController = Camera2Controller(this, viewFinder) { /* ready */ }
    cameraController?.start()
  }

  private fun startVideoRecording() {
    if (codecRecorder?.isRecording == true) return
    if (!requestedOption.isUsableOnDevice()) {
      Toast.makeText(this, R.string.main_codec_empty, Toast.LENGTH_SHORT).show()
      return
    }
    val controller = cameraController ?: return
    val outputFile = MediaUtils.getOutputMediaFile(MediaUtils.MEDIA_TYPE_VIDEO, requestedOption) ?: run {
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
          }
        } catch (e: Exception) {
          runOnUiThread {
            Toast.makeText(this, getString(R.string.record_start_failed, e.message), Toast.LENGTH_SHORT).show()
            stopRecordingUI()
            codecRecorder?.release()
            codecRecorder = null
            isRecordingStarting = false
          }
        }
      }.start()
    } catch (e: Exception) {
      Toast.makeText(this, getString(R.string.record_start_failed, e.message), Toast.LENGTH_SHORT).show()
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

    // 后台处理保存操作
    showSavingState(true)
    Thread {
      try {
        recorder.stopRecording()
      } catch (_: Exception) {
      } finally {
        recorder.release()
      }
      runOnUiThread {
        showSavingState(false)
        generateVideoThumbnailAsync()
      }
    }.start()
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

  private fun generateVideoThumbnailAsync() {
    if (videoPath.isEmpty()) return

    coroutineScope.launch {
      try {
        // 在IO线程执行耗时操作
        val bitmap = withContext(Dispatchers.IO) {
          MediaUtils.getVideoFrameBitmap(videoPath)
        }

        bitmap?.let { bmp ->
          // 在IO线程保存文件
          val imageFile = withContext(Dispatchers.IO) {
            val file = MediaUtils.getOutputMediaFile(MediaUtils.MEDIA_TYPE_IMAGE)
            file?.let { f ->
              try {
                FileOutputStream(f).use { out ->
                  bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                f.absolutePath
              } catch (e: Exception) {
                Log.e("VideoRecordActivity", "保存缩略图失败", e)
                null
              }
            }
          }

          imageFile?.let { path ->
            imagePath = path
            // 在主线程更新UI
            thumbnailView.setImageBitmap(bmp)
            thumbnailView.visibility = View.VISIBLE
          }
        }
      } catch (e: Exception) {
        Log.e("VideoRecordActivity", "异步生成缩略图失败", e)
      }
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
    btnPlay.visibility = View.VISIBLE
    thumbnailView.visibility = View.GONE
    playbackSurface.visibility = View.GONE
    viewFinder.visibility = View.INVISIBLE
  }

  private fun resetToPreview(deleteFiles: Boolean) {
    stopPlayback()
    captureState = CaptureState.PREVIEW
    recordPanel.visibility = View.VISIBLE
    operatePanel.visibility = View.INVISIBLE
    btnPlay.visibility = View.INVISIBLE
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
    if (!playbackSurfaceReady || videoPath.isEmpty()) return
    stopPlayback()
    playbackSurface.visibility = View.VISIBLE
    thumbnailView.visibility = View.GONE
    mediaPlayer = MediaPlayer().apply {
      setDataSource(this@VideoRecordActivity, Uri.parse(videoPath))
      setDisplay(playbackSurface.holder)
      setOnPreparedListener { start() }
      setOnCompletionListener {
        stopPlayback()
        thumbnailView.visibility = View.VISIBLE
      }
      setOnErrorListener { _, what, extra ->
        Toast.makeText(this@VideoRecordActivity, "播放失败: $what/$extra", Toast.LENGTH_SHORT).show()
        stopPlayback()
        true
      }
      prepareAsync()
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
    playbackSurface.visibility = View.GONE
  }

  private fun finishWithResult() {
    when (captureState) {
      CaptureState.IMAGE_REVIEW -> {
        if (imagePath.isEmpty()) {
          Toast.makeText(this, R.string.photo_capture_failed, Toast.LENGTH_SHORT).show()
          return
        }
        setResult(Activity.RESULT_OK, Intent().apply {
          putExtra("path", imagePath)
          putExtra("imagePath", imagePath)
          putExtra("type", TYPE_IMAGE)
        })
        finish()
      }
      CaptureState.VIDEO_REVIEW -> {
        if (videoPath.isEmpty()) {
          Toast.makeText(this, R.string.record_file_failed, Toast.LENGTH_SHORT).show()
          return
        }
        setResult(Activity.RESULT_OK, Intent().apply {
          putExtra("path", videoPath)
          putExtra("imagePath", imagePath)
          putExtra("type", TYPE_VIDEO)
        })
        finish()
      }
      else -> Toast.makeText(this, R.string.record_nothing, Toast.LENGTH_SHORT).show()
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
    private const val LONG_PRESS_THRESHOLD_MS = 300L
    private const val PROGRESS_INTERVAL_MS = 100L
  }
}

private class Camera2Controller(
  private val context: Activity,
  private val textureView: TextureView,
  private val onReady: () -> Unit
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
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
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
        captureSession = session
        val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
          set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
          targets.forEach { addTarget(it) }
        }
        session.setRepeatingRequest(requestBuilder.build(), null, cameraHandler)
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

  val isRecording: Boolean
    get() = drainThread != null

  fun startRecording(): Surface {
    val format = MediaFormat.createVideoFormat(option.mime, videoSize.width, videoSize.height).apply {
      setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
      setInteger(MediaFormat.KEY_BIT_RATE, option.defaultBitrate)
      setInteger(MediaFormat.KEY_FRAME_RATE, option.defaultFrameRate)
      setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
    }
    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    inputSurface = codec.createInputSurface()
    codec.start()
    muxer.setOrientationHint(orientationHint)
    drainLatch = CountDownLatch(1)
    drainThread = Thread { drainEncoderLoop() }.apply { start() }
    return inputSurface!!
  }

  fun stopRecording() {
    try {
      MediaCodec::class.java.getMethod("signalEndOfInputSurface").invoke(codec)
    } catch (_: Exception) {
    }
    try {
      drainLatch?.await(2, TimeUnit.SECONDS)
    } catch (_: Exception) {
    }
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
    inputSurface?.release()
    inputSurface = null
  }

  private fun drainEncoderLoop() {
    try {
      while (true) {
        val index = codec.dequeueOutputBuffer(bufferInfo, 10_000)
        when {
          index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
            if (muxerStarted) continue
            val newFormat = codec.outputFormat
            muxerVideoTrack = muxer.addTrack(newFormat)
            muxer.start()
            muxerStarted = true
          }
          index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
            if (drainLatch == null) break
          }
          index >= 0 -> {
            val encodedData = codec.getOutputBuffer(index) ?: continue
            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
              bufferInfo.size = 0
            }
            if (bufferInfo.size > 0 && muxerStarted && muxerVideoTrack >= 0) {
              encodedData.position(bufferInfo.offset)
              encodedData.limit(bufferInfo.offset + bufferInfo.size)
              muxer.writeSampleData(muxerVideoTrack, encodedData, bufferInfo)
            }
            codec.releaseOutputBuffer(index, false)
            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
          }
        }
      }
    } catch (e: Exception) {
      Log.e("CodecRecorder", "drain error", e)
    } finally {
      drainLatch?.countDown()
    }
  }
}
