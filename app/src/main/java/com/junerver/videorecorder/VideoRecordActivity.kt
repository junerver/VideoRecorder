package com.junerver.videorecorder

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.delay
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraInfoUnavailableException
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.util.Calendar
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min


public val TYPE_VIDEO = 0  //视频模式
public val TYPE_IMAGE = 1  //拍照模式

typealias CameraX = androidx.camera.core.Camera
typealias LumaListener = (luma: Double) -> Unit

class VideoRecordActivity : AppCompatActivity() {

  private val TAG = "VideoRecordActivity"
  private var mStartedFlag = false //录像中标志
  private var mPlayFlag = false
  private lateinit var mMediaPlayer: MediaPlayer
  private lateinit var path: String //最终视频路径
  private lateinit var imgPath: String //缩略图 或 拍照模式图片位置
  private var timer = 0 //计时器
  private val maxSec = 10 //视频总时长
  private var startTime: Long = 0L //起始时间毫秒
  private var stopTime: Long = 0L  //结束时间毫秒
  private var playerReleaseEnable = false //回收plyer
  private var mType = TYPE_VIDEO //默认为视频模式
  private var thumbnailGenerated = false //缩略图是否已生成

  // View成员变量
  private lateinit var mBtnCancle: Button
  private lateinit var mBtnFlip: Button
  private lateinit var mBtnPlay: Button
  private lateinit var mBtnRecord: Button
  private lateinit var mBtnSubmit: Button
  private lateinit var mLlRecordBtn: LinearLayout
  private lateinit var mLlRecordOp: LinearLayout
  private lateinit var mProgress: me.zhanghai.android.materialprogressbar.MaterialProgressBar
  private lateinit var mSurfaceview: SurfaceView
  private lateinit var view_finder: androidx.camera.view.PreviewView
  private lateinit var mImageView: ImageView
  private lateinit var mTvPlayLoading: TextView

  private val cameraExecutor = Executors.newSingleThreadExecutor()
  private lateinit var context:Context

  private var displayId: Int = -1
  private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
  private var preview: Preview? = null
  private var imageCapture: ImageCapture? = null
  private var imageAnalyzer: ImageAnalysis? = null
  private var camera: CameraX? = null
  private var cameraProvider: ProcessCameraProvider? = null
  private lateinit var windowManager: WindowManager

  // CameraX video recording
  private var videoCapture: VideoCapture<Recorder>? = null
  private var recording: Recording? = null
  private lateinit var recorder: Recorder

  // MediaRecorder for VP8 encoding
  private var mediaRecorder: MediaRecorder? = null
  private var useMediaRecorder = false  // Flag to switch between CameraX and MediaRecorder

  //用于记录视频录制时长
  var handler = Handler(Looper.getMainLooper())
  var runnable = object : Runnable {
    override fun run() {
      timer++
      if (timer < 100) {
        // 之所以这里是100 是为了方便使用进度条
        mProgress.progress = timer
        // 之所以每一百毫秒增加一次计时器是因为：总时长的毫秒数 / 100 即每次间隔延时的毫秒数 为 100
        handler.postDelayed(this, maxSec * 10L)
      } else {
        //停止录制 保存录制的流、显示供操作的ui
        Log.d("到最大拍摄时间", "")
        stopRecord()
      }
    }

  }

  @RequiresApi(Build.VERSION_CODES.R)
  @SuppressLint("ClickableViewAccessibility", "MissingPermission")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_video_record)
    mMediaPlayer = MediaPlayer()
    context = this
    windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // 初始化View
    initViews()

    mBtnFlip.isEnabled = false
    mBtnFlip.setOnClickListener {
      lensFacing = if (CameraSelector.LENS_FACING_FRONT == lensFacing) {
        CameraSelector.LENS_FACING_BACK
      } else {
        CameraSelector.LENS_FACING_FRONT
      }
      // Re-bind use cases to update selected camera
      bindCameraUseCases()
    }
    mBtnRecord.setOnTouchListener { _, event ->
      if (event.action == MotionEvent.ACTION_DOWN) {
        startRecord()
      }
      if (event.action == MotionEvent.ACTION_UP) {
        stopRecord()
      }
      true
    }
    mBtnPlay.setOnClickListener {
      // 防止重复点击：如果已经在播放或正在准备，则忽略
      if (mPlayFlag) {
        Log.d(TAG, "正在播放中，忽略重复点击")
        return@setOnClickListener
      }
      playRecord()
    }
    mBtnCancle.setOnClickListener {
      if (mType == TYPE_VIDEO) {
        stopPlay()
        var videoFile = File(path)
        if (videoFile.exists() && videoFile.isFile) {
          videoFile.delete()
        }
      } else {
        //拍照模式
        val imgFile = File(imgPath)
        if (imgFile.exists() && imgFile.isFile) {
          imgFile.delete()
        }
      }

      // 重新初始化到预览状态
      resetToPreview()

      setResult(Activity.RESULT_CANCELED)
      finish()
    }
    mBtnSubmit.setOnClickListener {
      stopPlay()

      // 对于视频模式，异步等待缩略图生成完成
      if (mType == TYPE_VIDEO && !thumbnailGenerated) {
        Log.w(TAG, "缩略图还在生成中，等待完成")

        // 显示等待提示
        Toast.makeText(this@VideoRecordActivity, "等待缩略图生成...", Toast.LENGTH_SHORT).show()

        // 异步等待缩略图生成，最多等待5秒
        lifecycleScope.launch {
          val waitStartTime = System.currentTimeMillis()
          var waitedTime = 0L

          while (!thumbnailGenerated && waitedTime < 5000) {
            delay(100)
            waitedTime = System.currentTimeMillis() - waitStartTime
          }

          Log.d(TAG, "等待缩略图生成完成，用时: ${waitedTime}ms, 生成状态: $thumbnailGenerated")

          // 提交结果
          submitResult()
        }
      } else {
        // 直接提交结果
        submitResult()
      }
    }

    // 初始化CameraX
    lifecycleScope.launch {
      setUpCamera()
    }
  }

  // 提交结果的方法
  private fun submitResult() {
    runOnUiThread {
      var intent = Intent().apply {
        putExtra("path", path)
        putExtra("imagePath", imgPath)
        putExtra("type", mType)
      }
      if (mType == TYPE_IMAGE) {
        //删除一开始创建的视频文件
        var videoFile = File(path)
        if (videoFile.exists() && videoFile.isFile) {
          videoFile.delete()
        }
      }

      Log.d(TAG, "提交结果: path=$path, imagePath=$imgPath, type=$mType")
      setResult(Activity.RESULT_OK, intent)
      finish()
    }
  }

  @RequiresApi(Build.VERSION_CODES.R)
  private suspend fun setUpCamera() {
    cameraProvider = ProcessCameraProvider.getInstance(context).await()

    // Select lensFacing depending on the available cameras
    lensFacing = when {
      hasBackCamera() -> CameraSelector.LENS_FACING_BACK
      hasFrontCamera() -> CameraSelector.LENS_FACING_FRONT
      else -> throw IllegalStateException("Back and front camera are unavailable")
    }

    // Enable or disable switching between cameras
    updateCameraSwitchButton()

    // Build and bind the camera use cases
    bindCameraUseCases()
  }

  /** Enabled or disabled a button to switch cameras depending on the available cameras */
  private fun updateCameraSwitchButton() {
    try {
      mBtnFlip?.isEnabled = hasBackCamera() && hasFrontCamera()
    } catch (exception: CameraInfoUnavailableException) {
      mBtnFlip?.isEnabled = false
    }
  }

  /** Returns true if the device has an available back camera. False otherwise */
  private fun hasBackCamera(): Boolean {
    return cameraProvider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ?: false
  }

  /** Returns true if the device has an available front camera. False otherwise */
  private fun hasFrontCamera(): Boolean {
    return cameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ?: false
  }

  /** Declare and bind preview, capture and analysis use cases */
  @RequiresApi(Build.VERSION_CODES.R)
  private fun bindCameraUseCases() {

    // Get screen metrics used to setup camera for full screen resolution
    val metrics = windowManager.currentWindowMetrics.bounds
    Log.d(TAG, "Screen metrics: ${metrics.width()} x ${metrics.height()}")

    val screenAspectRatio = aspectRatio(metrics.width(), metrics.height())
    Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")

    // 安全获取旋转角度
    val rotation = try {
      if (view_finder.display != null) {
        view_finder.display.rotation
      } else {
        // 如果display为null，使用windowManager获取
        windowManager.defaultDisplay.rotation
      }
    } catch (e: Exception) {
      Log.w(TAG, "无法获取显示旋转角度，使用默认值", e)
      Surface.ROTATION_0
    }

    // CameraProvider
    val cameraProvider = cameraProvider
      ?: throw IllegalStateException("Camera initialization failed.")

    // CameraSelector
    val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

    // 使用简单的旋转角度设置，避免复杂计算导致的错误
    val finalRotation = rotation
    Log.d(TAG, "设备旋转: $rotation, 使用旋转角度: $finalRotation")

    // Preview
    preview = Preview.Builder()
      .build()
      .also {
        it.setSurfaceProvider(view_finder.surfaceProvider)
      }

    // ImageCapture
    imageCapture = ImageCapture.Builder()
      .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
      // 设置目标旋转角度，与设备传感器方向匹配
      .setTargetRotation(finalRotation)
      .build()

    // ImageAnalysis
    imageAnalyzer = ImageAnalysis.Builder()
      // We request aspect ratio but no resolution
      .setTargetAspectRatio(screenAspectRatio)
      // Set initial target rotation, we will have to call this again if rotation changes
      // during the lifecycle of this use case
      .setTargetRotation(rotation)
      .build()
      // The analyzer can then be assigned to the instance
      .also {
        it.setAnalyzer(cameraExecutor, LuminosityAnalyzer { luma ->
          // Values returned from our analyzer are passed to the attached listener
          // We log image analysis results here - you should do something useful
          // instead!
          Log.d(TAG, "Average luminosity: $luma")
        })
      }

    // VideoCapture with Recorder
    val qualitySelector = QualitySelector.from(
      Quality.HD,
      FallbackStrategy.higherQualityOrLowerThan(Quality.HD)
    )
    recorder = Recorder.Builder()
      .setQualitySelector(qualitySelector)
      .build()
    videoCapture = VideoCapture.withOutput(recorder)

    // Must unbind the use-cases before rebinding them
    cameraProvider.unbindAll()

    if (camera != null) {
      // Must remove observers from the previous camera instance
      removeCameraStateObservers(camera!!.cameraInfo)
    }

    try {
      // A variable number of use-cases can be passed here -
      // camera provides access to CameraControl & CameraInfo
      camera = cameraProvider.bindToLifecycle(
        this, cameraSelector, preview, imageCapture, videoCapture)

      // Attach the viewfinder's surface provider to preview use case
      preview?.setSurfaceProvider(view_finder.surfaceProvider)
      observeCameraState(camera?.cameraInfo!!)
    } catch (exc: Exception) {
      Log.e(TAG, "Use case binding failed", exc)
    }
  }

  private fun removeCameraStateObservers(cameraInfo: CameraInfo) {
    cameraInfo.cameraState.removeObservers(this)
  }

  private fun observeCameraState(cameraInfo: CameraInfo) {
    cameraInfo.cameraState.observe(this) { cameraState ->
      run {
        when (cameraState.type) {
          CameraState.Type.PENDING_OPEN -> {
            // Ask the user to close other camera apps
            Log.d(TAG, "CameraState: Pending Open")
          }
          CameraState.Type.OPENING -> {
            // Show the Camera UI
            Log.d(TAG, "CameraState: Opening")
          }
          CameraState.Type.OPEN -> {
            // Setup Camera resources and begin processing
            Log.d(TAG, "CameraState: Open")
          }
          CameraState.Type.CLOSING -> {
            // Close camera UI
            Log.d(TAG, "CameraState: Closing")
          }
          CameraState.Type.CLOSED -> {
            // Free camera resources
            Log.d(TAG, "CameraState: Closed")
          }
        }
      }

      cameraState.error?.let { error ->
        when (error.code) {
          // Open errors
          CameraState.ERROR_STREAM_CONFIG -> {
            // Make sure to setup the use cases properly
            Toast.makeText(context,
              "Stream config error",
              Toast.LENGTH_SHORT).show()
          }
          // Opening errors
          CameraState.ERROR_CAMERA_IN_USE -> {
            // Close the camera or ask user to close another camera app that's using the
            // camera
            Toast.makeText(context,
              "Camera in use",
              Toast.LENGTH_SHORT).show()
          }
          CameraState.ERROR_MAX_CAMERAS_IN_USE -> {
            // Close another open camera in the app, or ask the user to close another
            // camera app that's using the camera
            Toast.makeText(context,
              "Max cameras in use",
              Toast.LENGTH_SHORT).show()
          }
          CameraState.ERROR_OTHER_RECOVERABLE_ERROR -> {
            Toast.makeText(context,
              "Other recoverable error",
              Toast.LENGTH_SHORT).show()
          }
          // Closing errors
          CameraState.ERROR_CAMERA_DISABLED -> {
            // Ask the user to enable the device's cameras
            Toast.makeText(context,
              "Camera disabled",
              Toast.LENGTH_SHORT).show()
          }
          CameraState.ERROR_CAMERA_FATAL_ERROR -> {
            // Ask the user to reboot the device to restore camera function
            Toast.makeText(context,
              "Fatal error",
              Toast.LENGTH_SHORT).show()
          }
          // Closed errors
          CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> {
            // Ask the user to disable the "Do Not Disturb" mode, then reopen the camera
            Toast.makeText(context,
              "Do not disturb mode enabled",
              Toast.LENGTH_SHORT).show()
          }
        }
      }
    }
  }


  private fun aspectRatio(width: Int, height: Int): Int {
    val previewRatio = max(width, height).toDouble() / min(width, height)
    if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
      return AspectRatio.RATIO_4_3
    }
    return AspectRatio.RATIO_16_9
  }

  /**
   * 修正图片旋转角度
   * @param imagePath 图片路径
   * @return 修正后的Bitmap，失败返回null
   */
  private fun fixImageRotation(imagePath: String): Bitmap? {
    return try {
      val bitmap = BitmapFactory.decodeFile(imagePath)
      if (bitmap == null) {
        Log.w(TAG, "无法解码图片: $imagePath")
        return null
      }

      // 获取当前设备旋转角度
      val rotation = try {
        if (view_finder.display != null) {
          view_finder.display.rotation
        } else {
          windowManager.defaultDisplay.rotation
        }
      } catch (e: Exception) {
        Surface.ROTATION_0
      }

      // 计算旋转角度
      val rotateAngle = when (rotation) {
        Surface.ROTATION_0 -> 90f
        Surface.ROTATION_90 -> 0f
        Surface.ROTATION_180 -> -90f
        Surface.ROTATION_270 -> 180f
        else -> 90f
      }

      Log.d(TAG, "设备旋转: $rotation, 图片旋转角度: $rotateAngle")

      val matrix = Matrix()
      matrix.postRotate(rotateAngle)
      val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

      // 回收原Bitmap
      if (rotatedBitmap != bitmap) {
        bitmap.recycle()
      }

      rotatedBitmap
    } catch (e: Exception) {
      Log.e(TAG, "修正图片旋转失败", e)
      null
    }
  }




  override fun onStop() {
    super.onStop()
    if (mPlayFlag) {
      stopPlay()
    }
    if (mStartedFlag) {
      Log.d("页面stop", "")
      stopRecord()
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    // 停止录制（如果正在录制）
    if (useMediaRecorder) {
      releaseMediaRecorder()
    } else {
      recording?.close()
      recording = null
    }

    // 释放MediaPlayer
    if (this::mMediaPlayer.isInitialized) {
      try {
        if (mMediaPlayer.isPlaying) {
          mMediaPlayer.stop()
        }
        mMediaPlayer.reset()
        mMediaPlayer.release()
      } catch (e: Exception) {
        Log.w(TAG, "释放MediaPlayer时出现异常", e)
      }
    }

    // 关闭camera executor
    cameraExecutor.shutdown()
  }

  //初始化View
  private fun initViews() {
    mBtnCancle = findViewById(R.id.mBtnCancle)
    mBtnFlip = findViewById(R.id.mBtnFlip)
    mBtnPlay = findViewById(R.id.mBtnPlay)
    mBtnRecord = findViewById(R.id.mBtnRecord)
    mBtnSubmit = findViewById(R.id.mBtnSubmit)
    mLlRecordBtn = findViewById(R.id.mLlRecordBtn)
    mLlRecordOp = findViewById(R.id.mLlRecordOp)
    mProgress = findViewById(R.id.mProgress)
    mSurfaceview = findViewById(R.id.mSurfaceview)
    view_finder = findViewById(R.id.view_finder)
    mImageView = findViewById(R.id.mImageView)
    mTvPlayLoading = findViewById<TextView>(R.id.mTvPlayLoading)

    // 设置SurfaceView回调
    mSurfaceview.holder.addCallback(object : SurfaceHolder.Callback {
      override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "Surface已创建")
        // Surface创建后立即预热
        prewarmSurfaceIfNeeded()
      }

      override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "Surface尺寸变化: ${width}x${height}")
        // Surface变化后预热
        prewarmSurfaceIfNeeded()
      }

      override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "Surface已销毁")
        // 如果正在播放，停止播放
        if (mPlayFlag) {
          stopPlay()
        }
      }
    })
  }

  //重置到预览状态
  private fun resetToPreview() {
    // 重置UI状态
    mType = TYPE_VIDEO
    mLlRecordOp.visibility = View.INVISIBLE
    mLlRecordBtn.visibility = View.VISIBLE
    view_finder.visibility = View.VISIBLE
    mImageView.visibility = View.INVISIBLE
    mBtnFlip.visibility = View.VISIBLE
    mBtnRecord.isEnabled = true
    mBtnRecord.isClickable = true

    // 重置缩略图生成状态
    thumbnailGenerated = false
    imgPath = ""
  }

  //使用CameraX拍照
  private fun takePicture() {
    val imageCapture = imageCapture ?: return

    // 创建输出文件
    val imgFile = MediaUtils.getOutputMediaFile(MediaUtils.MEDIA_TYPE_IMAGE) ?: return
    val outputOptions = ImageCapture.OutputFileOptions.Builder(imgFile).build()

    // 拍照
    imageCapture.takePicture(
      outputOptions,
      ContextCompat.getMainExecutor(this),
      object : ImageCapture.OnImageSavedCallback {
        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
          imgPath = imgFile.absolutePath
          mType = TYPE_IMAGE
          Log.d(TAG, "照片已保存: $imgPath")

          // 修正图片旋转
          val correctedBitmap = fixImageRotation(imgPath)

          runOnUiThread {
            // 显示拍摄的照片
            view_finder.visibility = View.INVISIBLE
            mImageView.visibility = View.VISIBLE
            if (correctedBitmap != null) {
              mImageView.setImageBitmap(correctedBitmap)
            } else {
              mImageView.setImageBitmap(BitmapFactory.decodeFile(imgPath))
            }

            // 隐藏切换摄像头按钮
            mBtnFlip.visibility = View.INVISIBLE

            mBtnPlay.visibility = View.INVISIBLE
            mLlRecordOp.visibility = View.VISIBLE
            mBtnRecord.isEnabled = true
            mBtnRecord.isClickable = true
          }
        }

        override fun onError(exc: ImageCaptureException) {
          Log.e(TAG, "拍照失败: ${exc.message}", exc)
          runOnUiThread {
            Toast.makeText(context, "拍照失败: ${exc.message}", Toast.LENGTH_SHORT).show()
            mBtnRecord.isEnabled = true
            mBtnRecord.isClickable = true
          }
        }
      }
    )
  }

  //开始录制
  @RequiresPermission(Manifest.permission.RECORD_AUDIO)
  private fun startRecord() {
    timer = 0
    if (!mStartedFlag) {
      mStartedFlag = true
      mLlRecordOp.visibility = View.INVISIBLE
      mBtnPlay.visibility = View.INVISIBLE
      mLlRecordBtn.visibility = View.VISIBLE
      mProgress.visibility = View.VISIBLE //进度条可见
      //开始计时
      handler.postDelayed(runnable, maxSec * 10L)
      startTime = System.currentTimeMillis()  //记录开始拍摄时间

      // 使用MediaRecorder with VP8 encoding for WebM format
      useMediaRecorder = true
      val videoFile = MediaUtils.getOutputMediaFile(MediaUtils.MEDIA_TYPE_VIDEO)
      if (videoFile != null) {
        path = videoFile.absolutePath.replace(".mp4", ".webm")  // Change extension to webm
        Log.d(TAG, "视频文件路径: $path")

        try {
          setupMediaRecorder()
          startMediaRecorder()
        } catch (e: Exception) {
          Log.e(TAG, "启动MediaRecorder失败: ${e.message}", e)
          // Fallback to CameraX if MediaRecorder fails
          useMediaRecorder = false
          startCameraXRecording(videoFile)
        }
      } else {
        // Fallback to CameraX if file creation fails
        useMediaRecorder = false
        val fallbackFile = MediaUtils.getOutputMediaFile(MediaUtils.MEDIA_TYPE_VIDEO)
        if (fallbackFile != null) {
          startCameraXRecording(fallbackFile)
        }
      }
    }
  }

  // Setup MediaRecorder with VP8 encoder and WebM format
  @SuppressLint("UnsafeOptInUsageError")
  private fun setupMediaRecorder() {
    val mr = MediaRecorder()
    mediaRecorder = mr

    // Get current display rotation for orientation
    val rotation = try {
      if (view_finder.display != null) {
        view_finder.display.rotation
      } else {
        windowManager.defaultDisplay.rotation
      }
    } catch (e: Exception) {
      Surface.ROTATION_0
    }

    Log.d(TAG, "Setting up MediaRecorder with rotation: $rotation")

    // For WebM format, we use WEBM format
    // Note: Not all devices support VP8 encoding, might need fallback
    try {
      mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
    } catch (e: Exception) {
      Log.w(TAG, "Video source SURFACE not available, trying CAMERA", e)
      mr.setVideoSource(MediaRecorder.VideoSource.CAMERA)
    }

    mr.setAudioSource(MediaRecorder.AudioSource.MIC)

    // Set output format to WebM
    mr.setOutputFormat(MediaRecorder.OutputFormat.WEBM)

    // Set video encoder to VP8
    mr.setVideoEncoder(MediaRecorder.VideoEncoder.VP8)

    // Set audio encoder to Opus (standard for WebM)
    mr.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)

    // Set video encoding bit rate
    mr.setVideoEncodingBitRate(2000000)  // 2 Mbps

    // Set video frame rate
    mr.setVideoFrameRate(30)

    // Get screen dimensions for video size
    val metrics = windowManager.currentWindowMetrics.bounds
    val videoWidth = min(metrics.width(), metrics.height())
    val videoHeight = max(metrics.width(), metrics.height())
    mr.setVideoSize(videoWidth, videoHeight)

    // Set orientation hint based on device rotation
    // For portrait recording, set appropriate rotation
    val orientationHint = when (rotation) {
      Surface.ROTATION_0 -> 90  // Portrait
      Surface.ROTATION_90 -> 0  // Landscape
      Surface.ROTATION_180 -> 270  // Portrait upside down
      Surface.ROTATION_270 -> 180  // Landscape upside down
      else -> 90
    }
    mr.setOrientationHint(orientationHint)
    Log.d(TAG, "Orientation hint set to: $orientationHint degrees")

    // Set output file
    mr.setOutputFile(path)
  }

  // Start MediaRecorder recording
  @SuppressLint("UnsafeOptInUsageError")
  private fun startMediaRecorder() {
    val mr = mediaRecorder ?: return

    try {
      mr.prepare()
      mr.start()
      Log.d(TAG, "MediaRecorder started successfully")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start MediaRecorder: ${e.message}", e)
      releaseMediaRecorder()
      throw e
    }
  }

  // Fallback to CameraX recording
  @RequiresPermission(Manifest.permission.RECORD_AUDIO)
  private fun startCameraXRecording(videoFile: File) {
    Log.d(TAG, "Falling back to CameraX recording")
    val outputOptions = FileOutputOptions.Builder(videoFile).build()

    recording = recorder.prepareRecording(this, outputOptions)
      .withAudioEnabled()
      .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
        when (recordEvent) {
          is VideoRecordEvent.Start -> {
            Log.d(TAG, "录制开始")
          }
          is VideoRecordEvent.Finalize -> {
            if (!recordEvent.hasError()) {
              Log.d(TAG, "录制完成: ${recordEvent.outputResults.outputUri}")
            } else {
              Log.e(TAG, "录制出错: ${recordEvent.error}")
              recording?.close()
              recording = null
            }
          }
        }
      }
  }

  // Release MediaRecorder resources
  private fun releaseMediaRecorder() {
    mediaRecorder?.apply {
      try {
        stop()
        release()
      } catch (e: Exception) {
        Log.w(TAG, "Error releasing MediaRecorder", e)
      }
    }
    mediaRecorder = null
  }

  //结束录制
  private fun stopRecord() {
    if (mStartedFlag) {
      mStartedFlag = false
      mBtnRecord.isEnabled = false
      mBtnRecord.isClickable = false

      mLlRecordBtn.visibility = View.INVISIBLE
      mProgress.visibility = View.INVISIBLE

      handler.removeCallbacks(runnable)
      stopTime = System.currentTimeMillis()

      val recordingDuration = stopTime - startTime

      // 如果录制时间过短（小于1秒），转为拍照模式
      if (recordingDuration < 1000) {
        Log.d(TAG, "录制时间过短，转为拍照模式")

        if (useMediaRecorder) {
          // Stop MediaRecorder
          releaseMediaRecorder()
        } else {
          // Stop CameraX recording
          recording?.stop()
          recording = null
        }

        // 删除录制的视频文件
        val videoFile = File(path)
        if (videoFile.exists()) {
          videoFile.delete()
        }

        // 执行拍照
        takePicture()
      } else {
        // 正常停止录制
        if (useMediaRecorder) {
          // Stop MediaRecorder
          releaseMediaRecorder()
        } else {
          // Stop CameraX recording
          recording?.stop()
          recording = null
        }

        // 延迟一点时间确保视频文件完全写入
        lifecycleScope.launch {
          delay(500) // 等待500ms确保视频文件写入完成

          // 生成视频首帧Bitmap，重试3次
          var frameBitmap: Bitmap? = null
          var retryCount = 0
          val maxRetries = 3

          while (retryCount < maxRetries && frameBitmap == null) {
            try {
              Log.d(TAG, "尝试生成视频首帧，第${retryCount + 1}次")
              frameBitmap = MediaUtils.getVideoFrameBitmap(path)

              if (frameBitmap != null) {
                Log.d(TAG, "视频首帧生成成功: ${frameBitmap.width}x${frameBitmap.height}")
                break
              } else {
                Log.w(TAG, "第${retryCount + 1}次生成首帧失败")
                frameBitmap = null
              }
            } catch (e: Exception) {
              Log.e(TAG, "第${retryCount + 1}次生成首帧异常", e)
            }

            if (frameBitmap == null && retryCount < maxRetries - 1) {
              delay(1000) // 重试前等待1秒
            }
            retryCount++
          }

          // 使用方形缩略图作为最终保存的缩略图（用于列表显示）
          var imageFile: File? = null
          try {
            imageFile = MediaUtils.getImageForVideo(path)
            imgPath = imageFile?.absolutePath ?: ""
            thumbnailGenerated = imageFile != null
          } catch (e: Exception) {
            Log.e(TAG, "生成方形缩略图失败", e)
          }

          // 显示视频首帧（保持宽高比）
          runOnUiThread {
            view_finder.visibility = View.INVISIBLE
            mImageView.visibility = View.VISIBLE

            if (frameBitmap != null) {
              Log.d(TAG, "显示视频首帧: ${frameBitmap.width}x${frameBitmap.height}")
              mImageView.setImageBitmap(frameBitmap)
            } else {
              Log.w(TAG, "视频首帧生成失败，已重试$maxRetries 次")
              // 降级：使用方形缩略图
              if (imageFile != null && imageFile.exists()) {
                val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                if (bitmap != null) {
                  mImageView.setImageBitmap(bitmap)
                }
              }
            }

            // 隐藏切换摄像头按钮
            mBtnFlip.visibility = View.INVISIBLE

            // 显示播放按钮和操作按钮
            mBtnPlay.visibility = View.VISIBLE
            mLlRecordOp.visibility = View.VISIBLE
            mBtnRecord.isEnabled = true
            mBtnRecord.isClickable = true

            // 立即预热Surface
            prewarmSurfaceIfNeeded()
          }
        }
      }
    }
  }

  // 预热Surface
  private fun prewarmSurfaceIfNeeded() {
    if (mImageView.visibility == View.VISIBLE && mBtnPlay.visibility == View.INVISIBLE) {
      // 正在显示视频缩略图，预热Surface
      val holder = mSurfaceview.holder
      if (holder.surface != null && holder.surface.isValid) {
        Log.d(TAG, "Surface已准备就绪，可以播放视频")
        // Surface准备好了，显示播放按钮
        runOnUiThread {
          mBtnPlay.visibility = View.VISIBLE
        }
      }
    }
  }

  // 显示播放Loading
  private fun showPlayLoading() {
    mBtnPlay.visibility = View.INVISIBLE
    mTvPlayLoading.visibility = View.VISIBLE
  }

  // 隐藏播放Loading
  private fun hidePlayLoading() {
    mTvPlayLoading.visibility = View.INVISIBLE
  }

  //播放录像
  private fun playRecord() {
    try {
      playerReleaseEnable = true
      mPlayFlag = true  // 立即设置，防止重复点击
      mBtnPlay.visibility = View.INVISIBLE

      // 切换到SurfaceView显示视频
      mImageView.visibility = View.INVISIBLE
      mSurfaceview.visibility = View.VISIBLE

      // 检查Surface是否可用
      if (mSurfaceview.holder.surface == null || !mSurfaceview.holder.surface.isValid) {
        Log.e(TAG, "Surface不可用，显示Loading并等待")
        showPlayLoading()
        // 等待Surface准备好
        lifecycleScope.launch {
          var retryCount = 0
          val maxRetries = 10
          while (retryCount < maxRetries) {
            delay(100) // 每100ms检查一次
            retryCount++
            if (mSurfaceview.holder.surface != null && mSurfaceview.holder.surface.isValid) {
              Log.d(TAG, "Surface已准备好，开始播放")
              hidePlayLoading()
              // 继续播放逻辑
              startVideoPlayback()
              return@launch
            }
          }
          // 等待超时
          Log.e(TAG, "Surface等待超时")
          hidePlayLoading()
          resetPlaybackUI()
          Toast.makeText(this@VideoRecordActivity, "显示Surface未准备好，请重试", Toast.LENGTH_SHORT).show()
        }
        return
      }

      // 检查文件是否存在
      if (!File(path).exists()) {
        Log.e(TAG, "视频文件不存在: $path")
        Toast.makeText(this, "视频文件不存在: $path", Toast.LENGTH_SHORT).show()
        resetPlaybackUI()
        return
      }

      // Surface已准备好，直接开始播放
      startVideoPlayback()
    } catch (e: Exception) {
      Log.e(TAG, "播放视频时发生异常", e)
      Toast.makeText(this, "播放视频时发生异常: ${e.message}", Toast.LENGTH_SHORT).show()
      resetPlaybackUI()
      mPlayFlag = false
    }
  }

  // 开始视频播放（Surface已确认准备好）
  private fun startVideoPlayback() {
    try {
      // 释放之前的MediaPlayer
      if (this::mMediaPlayer.isInitialized) {
        try {
          if (mMediaPlayer.isPlaying) {
            mMediaPlayer.stop()
          }
          mMediaPlayer.reset()
          mMediaPlayer.release()
        } catch (e: Exception) {
          Log.w(TAG, "释放之前的MediaPlayer时出现异常", e)
        }
      }

      // 创建新的MediaPlayer实例
      val tempPlayer = MediaPlayer()
      tempPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC)
      tempPlayer.setDataSource(this, Uri.parse(path))
      tempPlayer.setDisplay(mSurfaceview.holder)
      tempPlayer.setOnCompletionListener {
        Log.d(TAG, "视频播放完成")
        resetPlaybackUI()
        mPlayFlag = false
        tempPlayer.release()
      }
      tempPlayer.setOnErrorListener { _, what, extra ->
        Log.e(TAG, "MediaPlayer播放错误: what=$what, extra=$extra")
        Toast.makeText(this@VideoRecordActivity, "视频播放失败: $what-$extra", Toast.LENGTH_SHORT).show()
        resetPlaybackUI()
        mPlayFlag = false
        tempPlayer.release()
        true
      }
      tempPlayer.setOnPreparedListener {
        Log.d(TAG, "视频准备完成，开始播放")
        tempPlayer.start()
      }
      tempPlayer.prepareAsync()

      // 更新成员变量
      mMediaPlayer = tempPlayer

    } catch (e: Exception) {
      Log.e(TAG, "播放视频时发生异常", e)
      Toast.makeText(this, "播放视频时发生异常: ${e.message}", Toast.LENGTH_SHORT).show()
      resetPlaybackUI()
      mPlayFlag = false
    }
  }

  // 重置播放相关UI状态
  private fun resetPlaybackUI() {
    mPlayFlag = false
    mBtnPlay.visibility = View.VISIBLE
    mSurfaceview.visibility = View.INVISIBLE
    mImageView.visibility = View.VISIBLE
  }

  //停止播放录像
  private fun stopPlay() {
    try {
      if (this::mMediaPlayer.isInitialized) {
        if (mMediaPlayer.isPlaying) {
          mMediaPlayer.stop()
        }
        mMediaPlayer.reset()
      }
      mPlayFlag = false
    } catch (e: Exception) {
      Log.e(TAG, "停止播放时发生异常", e)
    }
  }

  /**
   * 获取系统时间
   * @return
   */
  fun getDate(): String {
    var ca = Calendar.getInstance()
    var year = ca.get(Calendar.YEAR)           // 获取年份
    var month = ca.get(Calendar.MONTH)         // 获取月份
    var day = ca.get(Calendar.DATE)            // 获取日
    var minute = ca.get(Calendar.MINUTE)       // 分
    var hour = ca.get(Calendar.HOUR)           // 小时
    var second = ca.get(Calendar.SECOND)       // 秒
    return "" + year + (month + 1) + day + hour + minute + second
  }



  /**
   * Our custom image analysis class.
   *
   * <p>All we need to do is override the function `analyze` with our desired operations. Here,
   * we compute the average luminosity of the image by looking at the Y plane of the YUV frame.
   */
  private class LuminosityAnalyzer(listener: LumaListener? = null) : ImageAnalysis.Analyzer {
    private val frameRateWindow = 8
    private val frameTimestamps = ArrayDeque<Long>(5)
    private val listeners = ArrayList<LumaListener>().apply { listener?.let { add(it) } }
    private var lastAnalyzedTimestamp = 0L
    var framesPerSecond: Double = -1.0
      private set

    /**
     * Helper extension function used to extract a byte array from an image plane buffer
     */
    private fun ByteBuffer.toByteArray(): ByteArray {
      rewind()    // Rewind the buffer to zero
      val data = ByteArray(remaining())
      get(data)   // Copy the buffer into a byte array
      return data // Return the byte array
    }

    /**
     * Analyzes an image to produce a result.
     *
     * <p>The caller is responsible for ensuring this analysis method can be executed quickly
     * enough to prevent stalls in the image acquisition pipeline. Otherwise, newly available
     * images will not be acquired and analyzed.
     *
     * <p>The image passed to this method becomes invalid after this method returns. The caller
     * should not store external references to this image, as these references will become
     * invalid.
     *
     * @param image image being analyzed VERY IMPORTANT: Analyzer method implementation must
     * call image.close() on received images when finished using them. Otherwise, new images
     * may not be received or the camera may stall, depending on back pressure setting.
     *
     */
    override fun analyze(image: ImageProxy) {
      // If there are no listeners attached, we don't need to perform analysis
      if (listeners.isEmpty()) {
        image.close()
        return
      }

      // Keep track of frames analyzed
      val currentTime = System.currentTimeMillis()
      frameTimestamps.addFirst(currentTime)

      // Compute the FPS using a moving average
      while (frameTimestamps.size >= frameRateWindow) frameTimestamps.removeLast()
      val timestampFirst = frameTimestamps.firstOrNull() ?: currentTime
      val timestampLast = frameTimestamps.lastOrNull() ?: currentTime
      framesPerSecond = 1.0 / ((timestampFirst - timestampLast) /
          frameTimestamps.size.coerceAtLeast(1).toDouble()) * 1000.0

      // Analysis could take an arbitrarily long amount of time
      // Since we are running in a different thread, it won't stall other use cases

      lastAnalyzedTimestamp = frameTimestamps.first()

      // Since format in ImageAnalysis is YUV, image.planes[0] contains the luminance plane
      val buffer = image.planes[0].buffer

      // Extract image data from callback object
      val data = buffer.toByteArray()

      // Convert the data into an array of pixel values ranging 0-255
      val pixels = data.map { it.toInt() and 0xFF }

      // Compute average luminance for the image
      val luma = pixels.average()

      // Call all listeners with new value
      listeners.forEach { it(luma) }

      image.close()
    }
  }

  companion object {
    private const val TAG = "CameraXBasic"
    private const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
    private const val PHOTO_TYPE = "image/jpeg"
    private const val RATIO_4_3_VALUE = 4.0 / 3.0
    private const val RATIO_16_9_VALUE = 16.0 / 9.0
  }
}
