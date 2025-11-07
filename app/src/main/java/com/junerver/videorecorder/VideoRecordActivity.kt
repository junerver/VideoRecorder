package com.junerver.videorecorder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.junerver.videorecorder.VideoEncoderOption.Companion.fromExtra
import io.reactivex.disposables.Disposable
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class VideoRecordActivity : AppCompatActivity() {

  private lateinit var previewView: TextureView
  private lateinit var btnRecord: Button
  private lateinit var btnSwitch: Button
  private lateinit var btnDone: Button
  private lateinit var tvStatus: TextView

  private lateinit var requestedOption: VideoEncoderOption

  private var cameraDevice: CameraDevice? = null
  private var captureSession: CameraCaptureSession? = null
  private var previewSurface: Surface? = null
  private var recordSurface: Surface? = null

  private var cameraThread: HandlerThread? = null
  private var cameraHandler: Handler? = null

  private var mediaCodec: MediaCodec? = null
  private var mediaMuxer: MediaMuxer? = null
  private var muxerVideoTrack = -1
  private var muxerStarted = false
  private var drainThread: Thread? = null
  private var drainLatch: CountDownLatch? = null

  private var videoFile: File? = null
  private var videoSize: Size = Size(1280, 720)
  private var currentCameraId: String = ""

  private var isRecording = false
  private var permissionsDisposable: Disposable? = null

  private val bufferInfo = MediaCodec.BufferInfo()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    requestedOption = fromExtra(intent.getStringExtra(EXTRA_ENCODER_OPTION))
    setContentView(R.layout.activity_video_record)

    previewView = findViewById(R.id.previewView)
    btnRecord = findViewById(R.id.btnRecord)
    btnSwitch = findViewById(R.id.btnSwitch)
    btnDone = findViewById(R.id.btnDone)
    tvStatus = findViewById(R.id.tvStatus)

    btnRecord.setOnClickListener {
      if (isRecording) {
        stopRecording()
      } else {
        startRecording()
      }
    }

    btnSwitch.setOnClickListener {
      if (isRecording) {
        Toast.makeText(this, "录制中无法切换摄像头", Toast.LENGTH_SHORT).show()
      } else {
        toggleCamera()
      }
    }

    btnDone.setOnClickListener {
      finishWithResult()
    }

    startCameraThread()
    ensurePermissionsAndStart()
  }

  override fun onDestroy() {
    super.onDestroy()
    permissionsDisposable?.dispose()
    stopRecordingInternal(restartPreview = false)
    closeCamera()
    stopCameraThread()
  }

  override fun onBackPressed() {
    if (isRecording) {
      stopRecording()
    }
    super.onBackPressed()
  }

  private fun ensurePermissionsAndStart() {
    permissionsDisposable = rxRequestPermissions(
      Manifest.permission.CAMERA,
      Manifest.permission.RECORD_AUDIO,
      describe = getString(R.string.main_codec_default)
    ) {
      startPreviewFlow()
    }
  }

  private fun startPreviewFlow() {
    if (previewView.isAvailable) {
      openCamera()
    } else {
      previewView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
          openCamera()
        }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
          return true
        }
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
      }
    }
  }

  private fun openCamera() {
    val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
    if (currentCameraId.isEmpty()) {
      currentCameraId = manager.cameraIdList.firstOrNull { id ->
        val characteristics = manager.getCameraCharacteristics(id)
        val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
        facing == CameraCharacteristics.LENS_FACING_BACK
      } ?: manager.cameraIdList.first()
    }

    val characteristics = manager.getCameraCharacteristics(currentCameraId)
    videoSize = selectVideoSize(characteristics)

    try {
      manager.openCamera(currentCameraId, stateCallback, cameraHandler)
    } catch (e: SecurityException) {
      Toast.makeText(this, "缺少相机权限", Toast.LENGTH_SHORT).show()
    }
  }

  private fun selectVideoSize(characteristics: CameraCharacteristics): Size {
    val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
      ?: return Size(1280, 720)
    val choices = (map as StreamConfigurationMap).getOutputSizes(SurfaceTexture::class.java) ?: return Size(1280, 720)
    val preferred = choices.filter { it.width <= 1920 }.minByOrNull { abs(it.width - 1280) } ?: choices.first()
    return preferred
  }

  private fun createPreviewSession() {
    val texture = previewView.surfaceTexture ?: return
    texture.setDefaultBufferSize(videoSize.width, videoSize.height)
    previewSurface = Surface(texture)

    cameraDevice?.createCaptureSession(listOf(previewSurface), object : CameraCaptureSession.StateCallback() {
      override fun onConfigured(session: CameraCaptureSession) {
        captureSession = session
        val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW) ?: return
        builder.addTarget(previewSurface!!)
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        session.setRepeatingRequest(builder.build(), null, cameraHandler)
        runOnUiThread {
          tvStatus.text = "预览已启动"
        }
      }

      override fun onConfigureFailed(session: CameraCaptureSession) {
        runOnUiThread {
          Toast.makeText(this@VideoRecordActivity, "预览启动失败", Toast.LENGTH_SHORT).show()
        }
      }
    }, cameraHandler)
  }

  private fun startRecording() {
    if (isRecording) return
    if (!requestedOption.isUsableOnDevice()) {
      Toast.makeText(this, "设备不支持 ${requestedOption.label}", Toast.LENGTH_SHORT).show()
      return
    }

    if (cameraDevice == null) {
      Toast.makeText(this, "相机未就绪", Toast.LENGTH_SHORT).show()
      return
    }

    videoFile = MediaUtils.getOutputMediaFile(MediaUtils.MEDIA_TYPE_VIDEO, requestedOption)
    if (videoFile == null) {
      Toast.makeText(this, "无法创建输出文件", Toast.LENGTH_SHORT).show()
      return
    }

    try {
      setupEncoder()
    } catch (e: Exception) {
      Log.e(TAG, "初始化编码器失败", e)
      Toast.makeText(this, "初始化编码器失败: ${e.message}", Toast.LENGTH_SHORT).show()
      releaseEncoder()
      return
    }

    isRecording = true
    btnRecord.text = "停止录制"
    btnDone.visibility = View.GONE
    tvStatus.text = "录制中..."

    cameraDevice?.createCaptureSession(listOfNotNull(previewSurface, recordSurface), object : CameraCaptureSession.StateCallback() {
      override fun onConfigured(session: CameraCaptureSession) {
        captureSession = session
        val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD) ?: return
        previewSurface?.let { builder.addTarget(it) }
        recordSurface?.let { builder.addTarget(it) }
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        session.setRepeatingRequest(builder.build(), null, cameraHandler)
      }

      override fun onConfigureFailed(session: CameraCaptureSession) {
        runOnUiThread {
          Toast.makeText(this@VideoRecordActivity, "录制会话创建失败", Toast.LENGTH_SHORT).show()
        }
        stopRecording()
      }
    }, cameraHandler)
  }

  private fun setupEncoder() {
    val format = MediaFormat.createVideoFormat(requestedOption.mime, videoSize.width, videoSize.height)
    format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
    format.setInteger(MediaFormat.KEY_BIT_RATE, requestedOption.defaultBitrate)
    format.setInteger(MediaFormat.KEY_FRAME_RATE, requestedOption.defaultFrameRate)
    format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

    mediaCodec = MediaCodec.createEncoderByType(requestedOption.mime)
    mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    recordSurface = mediaCodec?.createInputSurface()
    mediaCodec?.start()

    mediaMuxer = MediaMuxer(videoFile!!.absolutePath, requestedOption.containerFormat)
    muxerVideoTrack = -1
    muxerStarted = false
    drainLatch = CountDownLatch(1)
    drainThread = Thread { drainEncoderLoop() }.also { it.start() }
  }

  private fun stopRecording() {
    if (!isRecording) return
    stopRecordingInternal(restartPreview = true)
  }

  private fun stopRecordingInternal(restartPreview: Boolean) {
    if (!isRecording && mediaCodec == null) return
    isRecording = false
    runOnUiThread { tvStatus.text = "正在保存..." }

    try {
      captureSession?.stopRepeating()
      captureSession?.abortCaptures()
    } catch (_: Exception) {
    }
    captureSession?.close()
    captureSession = null

    try {
      //signalEndOfInputSurface
      mediaCodec?.signalEndOfInputStream()
    } catch (_: Exception) {
    }

    try {
      drainLatch?.await(3, TimeUnit.SECONDS)
    } catch (_: Exception) {
    }

    releaseEncoder()

    if (restartPreview) {
      createPreviewSession()
    }

    runOnUiThread {
      btnRecord.text = "开始录制"
      btnDone.visibility = View.VISIBLE
      tvStatus.text = "录制完成：${videoFile?.name ?: ""}"
    }
  }

  private fun drainEncoderLoop() {
    try {
      while (true) {
        val index = mediaCodec?.dequeueOutputBuffer(bufferInfo, 10_000) ?: break
        when {
          index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
            if (muxerStarted) continue
            val newFormat = mediaCodec?.outputFormat ?: continue
            muxerVideoTrack = mediaMuxer?.addTrack(newFormat) ?: -1
            mediaMuxer?.setOrientationHint(currentOrientation())
            mediaMuxer?.start()
            muxerStarted = true
          }
          index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
            if (!isRecording) {
              if (mediaCodec == null) break
            }
          }
          index >= 0 -> {
            val encodedData = mediaCodec?.getOutputBuffer(index) ?: continue
            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
              bufferInfo.size = 0
            }
            if (bufferInfo.size > 0 && muxerStarted && muxerVideoTrack >= 0) {
              encodedData.position(bufferInfo.offset)
              encodedData.limit(bufferInfo.offset + bufferInfo.size)
              mediaMuxer?.writeSampleData(muxerVideoTrack, encodedData, bufferInfo)
            }
            mediaCodec?.releaseOutputBuffer(index, false)
            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
              break
            }
          }
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "编码写入失败", e)
    } finally {
      drainLatch?.countDown()
    }
  }

  private fun releaseEncoder() {
    try {
      drainThread?.join(200)
    } catch (_: Exception) {
    }
    drainThread = null

    try {
      if (muxerStarted) {
        mediaMuxer?.stop()
      }
    } catch (_: Exception) {
    }
    mediaMuxer?.release()
    mediaMuxer = null
    muxerVideoTrack = -1
    muxerStarted = false

    try {
      mediaCodec?.stop()
    } catch (_: Exception) {
    }
    mediaCodec?.release()
    mediaCodec = null

    recordSurface?.release()
    recordSurface = null
  }

  private fun finishWithResult() {
    val filePath = videoFile?.absolutePath ?: return
    val data = Intent().apply {
      putExtra("path", filePath)
      putExtra("imagePath", "")
      putExtra("type", TYPE_VIDEO)
    }
    setResult(RESULT_OK, data)
    finish()
  }

  private fun toggleCamera() {
    val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val ids = manager.cameraIdList
    if (ids.size <= 1) {
      Toast.makeText(this, "只有一个摄像头", Toast.LENGTH_SHORT).show()
      return
    }
    val currentIndex = ids.indexOf(currentCameraId)
    val nextIndex = (currentIndex + 1) % ids.size
    currentCameraId = ids[nextIndex]
    closeCamera()
    openCamera()
  }

  private fun closeCamera() {
    try {
      captureSession?.close()
      captureSession = null
      cameraDevice?.close()
      cameraDevice = null
    } catch (_: Exception) {
    }
    previewSurface?.release()
    previewSurface = null
  }

  private fun startCameraThread() {
    cameraThread = HandlerThread("CameraThread").also {
      it.start()
      cameraHandler = Handler(it.looper)
    }
  }

  private fun stopCameraThread() {
    cameraThread?.quitSafely()
    try {
      cameraThread?.join()
    } catch (_: InterruptedException) {
    }
    cameraThread = null
    cameraHandler = null
  }

  private fun currentOrientation(): Int {
    val rotation = windowManager.defaultDisplay.rotation
    return when (rotation) {
      Surface.ROTATION_0 -> 90
      Surface.ROTATION_90 -> 0
      Surface.ROTATION_180 -> 270
      Surface.ROTATION_270 -> 180
      else -> 0
    }
  }

  private val stateCallback = object : CameraDevice.StateCallback() {
    override fun onOpened(camera: CameraDevice) {
      cameraDevice = camera
      createPreviewSession()
    }

    override fun onDisconnected(camera: CameraDevice) {
      cameraDevice?.close()
      cameraDevice = null
    }

    override fun onError(camera: CameraDevice, error: Int) {
      Toast.makeText(this@VideoRecordActivity, "打开相机失败", Toast.LENGTH_SHORT).show()
    }
  }

  companion object {
    const val EXTRA_ENCODER_OPTION = "extra_video_encoder_option"
    const val TYPE_VIDEO = 0
    const val TYPE_IMAGE = 1
    private const val TAG = "VideoRecordActivity"
  }
}
