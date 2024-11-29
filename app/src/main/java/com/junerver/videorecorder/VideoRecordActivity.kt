package com.junerver.videorecorder

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.Camera
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraInfoUnavailableException
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProcessor
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.concurrent.futures.await
import androidx.core.content.ContentProviderCompat.requireContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.android.synthetic.main.activity_video_record.mBtnCancle
import kotlinx.android.synthetic.main.activity_video_record.mBtnFlip
import kotlinx.android.synthetic.main.activity_video_record.mBtnPlay
import kotlinx.android.synthetic.main.activity_video_record.mBtnRecord
import kotlinx.android.synthetic.main.activity_video_record.mBtnSubmit
import kotlinx.android.synthetic.main.activity_video_record.mLlRecordBtn
import kotlinx.android.synthetic.main.activity_video_record.mLlRecordOp
import kotlinx.android.synthetic.main.activity_video_record.mProgress
import kotlinx.android.synthetic.main.activity_video_record.mSurfaceview
import kotlinx.android.synthetic.main.activity_video_record.view_finder
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.Calendar
import java.util.concurrent.Executors
import kotlin.concurrent.thread
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
  private lateinit var mRecorder: MediaRecorder
  private lateinit var mSurfaceHolder: SurfaceHolder
  private var mCamera: Camera? = null
  private lateinit var mMediaPlayer: MediaPlayer
  private lateinit var dirPath: String //目标文件夹地址
  private lateinit var path: String //最终视频路径
  private lateinit var imgPath: String //缩略图 或 拍照模式图片位置
  private var timer = 0 //计时器
  private val maxSec = 10 //视频总时长
  private var startTime: Long = 0L //起始时间毫秒
  private var stopTime: Long = 0L  //结束时间毫秒
  private var cameraReleaseEnable = true  //回收摄像头
  private var recorderReleaseEnable = false  //回收recorder
  private var playerReleaseEnable = false //回收plyer
  private var mType = TYPE_VIDEO //默认为视频模式
  private lateinit var mHolder: SurfaceHolder

  private val cameraIds =
    arrayOf(Camera.CameraInfo.CAMERA_FACING_BACK, Camera.CameraInfo.CAMERA_FACING_FRONT)
  private var currentCameraIndex = 0

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
  @SuppressLint("ClickableViewAccessibility")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_video_record)
    mMediaPlayer = MediaPlayer()
    context = this

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
      setResult(Activity.RESULT_CANCELED)
      finish()
    }
    mBtnSubmit.setOnClickListener {
      stopPlay()
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
      setResult(Activity.RESULT_OK, intent)
      finish()
    }

  }

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
  private fun bindCameraUseCases() {

    // Get screen metrics used to setup camera for full screen resolution
    val metrics = windowManager.currentWindowMetrics.bounds
    Log.d(TAG, "Screen metrics: ${metrics.width()} x ${metrics.height()}")

    val screenAspectRatio = aspectRatio(metrics.width(), metrics.height())
    Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")

    val rotation = view_finder.display.rotation

    // CameraProvider
    val cameraProvider = cameraProvider
      ?: throw IllegalStateException("Camera initialization failed.")

    // CameraSelector
    val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

    // Preview
    preview = Preview.Builder()
      // We request aspect ratio but no resolution
      .setTargetAspectRatio(screenAspectRatio)
      // Set initial target rotation
      .setTargetRotation(rotation)
      .build()

    // ImageCapture
    imageCapture = ImageCapture.Builder()
      .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
      // We request aspect ratio but no resolution to match preview config, but letting
      // CameraX optimize for whatever specific resolution best fits our use cases
      .setTargetAspectRatio(screenAspectRatio)
      // Set initial target rotation, we will have to call this again if rotation changes
      // during the lifecycle of this use case
      .setTargetRotation(rotation)
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
        this, cameraSelector, preview, imageCapture, imageAnalyzer)

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
            Toast.makeText(context,
              "CameraState: Pending Open",
              Toast.LENGTH_SHORT).show()
          }
          CameraState.Type.OPENING -> {
            // Show the Camera UI
            Toast.makeText(context,
              "CameraState: Opening",
              Toast.LENGTH_SHORT).show()
          }
          CameraState.Type.OPEN -> {
            // Setup Camera resources and begin processing
            Toast.makeText(context,
              "CameraState: Open",
              Toast.LENGTH_SHORT).show()
          }
          CameraState.Type.CLOSING -> {
            // Close camera UI
            Toast.makeText(context,
              "CameraState: Closing",
              Toast.LENGTH_SHORT).show()
          }
          CameraState.Type.CLOSED -> {
            // Free camera resources
            Toast.makeText(context,
              "CameraState: Closed",
              Toast.LENGTH_SHORT).show()
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

  private var mHolderCallback: SurfaceHolder.Callback? = null



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
    if (recorderReleaseEnable) mRecorder.release()
    if (cameraReleaseEnable) {
      mCamera?.apply {
        stopPreview()
        release()
      }
    }
    if (playerReleaseEnable) {
      mMediaPlayer.release()
    }
  }

  //开始录制
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
      recorderReleaseEnable = true
      mRecorder = MediaRecorder().apply {
        reset()
        setCamera(mCamera)
        // 设置音频源与视频源 这两项需要放在setOutputFormat之前
        setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        setVideoSource(MediaRecorder.VideoSource.CAMERA)
        //设置输出格式
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        //这两项需要放在setOutputFormat之后 IOS必须使用ACC
        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)  //音频编码格式
        //使用MPEG_4_SP格式在华为P20 pro上停止录制时会出现
        //MediaRecorder: stop failed: -1007
        //java.lang.RuntimeException: stop failed.
        // at android.media.MediaRecorder.stop(Native Method)
        setVideoEncoder(MediaRecorder.VideoEncoder.H264)  //视频编码格式
        //设置最终出片分辨率
        setVideoSize(640, 480)
        setVideoFrameRate(30)
        setVideoEncodingBitRate(3 * 1024 * 1024)
        setOrientationHint(90)
        //设置记录会话的最大持续时间（毫秒）
        setMaxDuration(30 * 1000)
      }
      path = Environment.getExternalStorageDirectory().path + File.separator + "VideoRecorder"
      if (path != null) {
        var dir = File(path)
        if (!dir.exists()) {
          dir.mkdir()
        }
        dirPath = dir.absolutePath
        path = dir.absolutePath + "/" + getDate() + ".mp4"
        Log.d(TAG, "文件路径： $path")
        mRecorder.apply {
          setOutputFile(path)
          prepare()
          start()
        }
        startTime = System.currentTimeMillis()  //记录开始拍摄时间
      }
    }
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
//          方法1 ： 延时确保录制时间大于1s
//            if (stopTime-startTime<1100) {
//                Thread.sleep(1100+startTime-stopTime)
//            }
//            mRecorder.stop()
//            mRecorder.reset()
//            mRecorder.release()
//            recorderReleaseEnable = false
//            mCamera.lock()
//            mCamera.stopPreview()
//            mCamera.release()
//            cameraReleaseEnable = false
//            mBtnPlay.visibility = View.VISIBLE
//            MediaUtils.getImageForVideo(path) {
//                //获取到第一帧图片后再显示操作按钮
//                Log.d(TAG,"获取到了第一帧")
//                imgPath=it.absolutePath
//                mLlRecordOp.visibility = View.VISIBLE
//            }


//          方法2 ： 捕捉异常改为拍照
      try {
        mRecorder.apply {
          stop()
          reset()
          release()
        }
        recorderReleaseEnable = false
        mCamera?.apply {
          lock()
          stopPreview()
          release()
        }
        cameraReleaseEnable = false
        mBtnPlay.visibility = View.VISIBLE
        lifecycleScope.launch {
          val imageFile = MediaUtils.getImageForVideo(path)
          imgPath = imageFile?.absolutePath ?: ""
          mLlRecordOp.visibility = View.VISIBLE
        }
      } catch (e: java.lang.RuntimeException) {
        //当catch到RE时，说明是录制时间过短，此时将由录制改变为拍摄
        mType = TYPE_IMAGE
        Log.e("拍摄时间过短", e.message!!)
        mRecorder.apply {
          reset()
          release()
        }
        recorderReleaseEnable = false
        mCamera?.takePicture(null, null) { data, _ ->
          data?.let {
            saveImage(it) { imagePath ->
              Log.d(TAG, "转为拍照，获取到图片数据 $imagePath")
              imgPath = imagePath
              mCamera?.apply {
                lock()
                stopPreview()
                release()
              }
              cameraReleaseEnable = false
              runOnUiThread {
                mBtnPlay.visibility = View.INVISIBLE
                mLlRecordOp.visibility = View.VISIBLE
              }
            }
          }
        }
      }
    }
  }

  //播放录像
  private fun playRecord() {
    //修复录制时home键切出再次切回时无法播放的问题
    if (cameraReleaseEnable) {
      Log.d(TAG, "回收摄像头资源")
      mCamera?.apply {
        lock()
        stopPreview()
        release()
      }
      cameraReleaseEnable = false
    }
    playerReleaseEnable = true
    mPlayFlag = true
    mBtnPlay.visibility = View.INVISIBLE

    mMediaPlayer.reset()
    var uri = Uri.parse(path)
    mMediaPlayer = MediaPlayer.create(this, uri)
    mMediaPlayer.apply {
      setAudioStreamType(AudioManager.STREAM_MUSIC)
      setDisplay(mSurfaceHolder)
      setOnCompletionListener {
        //播放解释后再次显示播放按钮
        mBtnPlay.visibility = View.VISIBLE
      }
    }
    try {
      mMediaPlayer.prepare()
    } catch (e: Exception) {
      e.printStackTrace()
    }
    mMediaPlayer.start()
  }

  //停止播放录像
  private fun stopPlay() {
    if (mMediaPlayer.isPlaying) {
      mMediaPlayer.stop()
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
   * @Description
   * @Author Junerver
   * Created at 2019/5/23 15:13
   * @param data   从摄像头拍照回调获取的字节数组
   * @param onDone 保存图片完毕后的回调函数
   * @return
   */
  fun saveImage(data: ByteArray, onDone: (path: String) -> Unit) {
    thread {
      //方式1 ：java nio 保存图片 保存后的图片存在0度旋转角
//            val imgFileName = "IMG_" + getDate() + ".jpg"
//            val imgFile = File(dirPath + File.separator + imgFileName)
//            val outputStream = FileOutputStream(imgFile)
//            val fileChannel = outputStream.channel
//            val buffer = ByteBuffer.allocate(data.size)
//            try {
//                buffer.put(data)
//                buffer.flip()
//                fileChannel.write(buffer)
//            } catch (e: IOException) {
//                Log.e("写图片失败", e.message)
//            } finally {
//                try {
//                    outputStream.close()
//                    fileChannel.close()
//                    buffer.clear()
//                } catch (e: IOException) {
//                    Log.e("关闭图片失败", e.message)
//                }
//            }

      //方式2： bitmap保存 将拍摄结果旋转90度
      val imgFileName = "IMG_" + getDate() + ".jpg"
      val imgFile = File(dirPath + File.separator + imgFileName)
      val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
      val newBitmap = PictureUtils.rotateBitmap(bitmap, 90)
      imgFile.createNewFile()
      val os = BufferedOutputStream(FileOutputStream(imgFile))
      newBitmap.compress(Bitmap.CompressFormat.JPEG, 100, os)
      os.flush()
      os.close()
      val degree = PictureUtils.getBitmapDegree(imgFile.absolutePath)
      Log.d("图片角度为：", "$degree")
      onDone(imgFile.absolutePath)
    }
  }

  //从底层拿camera支持的previewsize，完了和屏幕分辨率做差，diff最小的就是最佳预览分辨率
  private fun getPreviewSize(): Pair<Int, Int> {
    var bestPreviewWidth: Int = 1920
    var bestPreviewHeight: Int = 1080
    var mCameraPreviewWidth: Int
    var mCameraPreviewHeight: Int
    var diffs = Integer.MAX_VALUE
    val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val display = windowManager.defaultDisplay
    val screenResolution = Point(display.width, display.height)
    val availablePreviewSizes = mCamera?.parameters?.supportedPreviewSizes
    Log.e(TAG, "屏幕宽度 ${screenResolution.x}  屏幕高度${screenResolution.y}")
    if (availablePreviewSizes != null) {
      for (previewSize in availablePreviewSizes) {
        Log.v(TAG, " PreviewSizes = $previewSize")
        mCameraPreviewWidth = previewSize.width
        mCameraPreviewHeight = previewSize.height
        val newDiffs =
          Math.abs(mCameraPreviewWidth - screenResolution.y) + Math.abs(mCameraPreviewHeight - screenResolution.x)
        Log.v(TAG, "newDiffs = $newDiffs")
        if (newDiffs == 0) {
          bestPreviewWidth = mCameraPreviewWidth
          bestPreviewHeight = mCameraPreviewHeight
          break
        }
        if (diffs > newDiffs) {
          bestPreviewWidth = mCameraPreviewWidth
          bestPreviewHeight = mCameraPreviewHeight
          diffs = newDiffs
        }
        Log.e(
          TAG,
          "${previewSize.width} ${previewSize.height}  宽度 $bestPreviewWidth 高度 $bestPreviewHeight"
        )
      }
    }
    Log.e(TAG, "最佳宽度 $bestPreviewWidth 最佳高度 $bestPreviewHeight")
    return Pair(bestPreviewWidth, bestPreviewHeight)
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
