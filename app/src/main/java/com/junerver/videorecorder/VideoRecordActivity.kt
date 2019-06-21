package com.junerver.videorecorder

import android.app.Activity
import android.content.Intent
import android.hardware.Camera
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import kotlinx.android.synthetic.main.activity_video_record.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.*
import kotlin.concurrent.thread
import android.hardware.camera2.CameraAccessException
import android.R.attr.y
import android.R.attr.x
import android.content.Context
import android.opengl.ETC1.getHeight
import android.opengl.ETC1.getWidth
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.StreamConfigurationMap
import android.view.Display
import android.content.Context.WINDOW_SERVICE
import android.graphics.*
import android.hardware.camera2.CameraManager
import androidx.core.content.ContextCompat.getSystemService
import android.view.WindowManager
import java.io.BufferedOutputStream


public val TYPE_VIDEO = 0  //视频模式
public val TYPE_IMAGE = 1  //拍照模式

class VideoRecordActivity : AppCompatActivity() {

    private val TAG = "VideoRecordActivity"
    private var mStartedFlag = false //录像中标志
    private var mPlayFlag = false
    private lateinit var mRecorder: MediaRecorder
    private lateinit var mSurfaceHolder: SurfaceHolder
    private lateinit var mCamera: Camera
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
    private var playerReleaseEnable = false //回收palyer

    private var mType = TYPE_VIDEO //默认为视频模式

    //用于记录视频录制时长
    var handler = Handler()
    var runnable = object : Runnable {
        override fun run() {
            timer++
//            Log.d("计数器","$timer")
            if (timer < 100) {
                // 之所以这里是100 是为了方便使用进度条
                mProgress.progress = timer
                //之所以每一百毫秒增加一次计时器是因为：总时长的毫秒数 / 100 即每次间隔延时的毫秒数 为 100
                handler.postDelayed(this, maxSec * 10L)
            } else {
                //停止录制 保存录制的流、显示供操作的ui
                Log.d("到最大拍摄时间","")
                stopRecord()
                System.currentTimeMillis()
            }
        }

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_record)
        mMediaPlayer = MediaPlayer()

        var holder = mSurfaceview.holder
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
                mSurfaceHolder = holder!!
                mCamera.apply {
                    startPreview()
                    cancelAutoFocus()
                    // 关键代码 该操作必须在开启预览之后进行（最后调用），
                    // 否则会黑屏，并提示该操作的下一步出错
                    // 只有执行该步骤后才可以使用MediaRecorder进行录制
                    // 否则会报 MediaRecorder(13280): start failed: -19
                    unlock()
                }
                cameraReleaseEnable = true
            }

            override fun surfaceDestroyed(holder: SurfaceHolder?) {
                handler.removeCallbacks(runnable)
            }

            override fun surfaceCreated(holder: SurfaceHolder?) {
                try {
                    mSurfaceHolder = holder!!
                    //使用后置摄像头
                    mCamera = Camera.open(Camera.CameraInfo.CAMERA_FACING_BACK)
                    mCamera.apply {
                        setDisplayOrientation(90)//旋转90度
                        setPreviewDisplay(holder)
                        val params = mCamera.parameters
                        //注意此处需要根据摄像头获取最优像素，//如果不设置会按照系统默认配置最低160x120分辨率
                        val size = getPreviewSize()
                        params.apply {
                            setPictureSize(size.first, size.second)
                            jpegQuality = 100
                            pictureFormat = PixelFormat.JPEG
                            focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE//1连续对焦
                        }
                        parameters = params
                    }

                } catch (e: RuntimeException) {
                    //Camera.open() 在摄像头服务无法连接时可能会抛出 RuntimeException
                    finish()
                }

            }
        })
        mBtnRecord.setOnTouchListener { _, event ->
            Log.d("点击屏幕","${event.action}")
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

    override fun onStop() {
        super.onStop()
        if (mPlayFlag) {
            stopPlay()
        }
        if (mStartedFlag) {
            Log.d("页面stop","")
            stopRecord()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (recorderReleaseEnable) mRecorder.release()
        if (cameraReleaseEnable) {
            mCamera.stopPreview()
            mCamera.release()
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
                mCamera.apply {
                    lock()
                    stopPreview()
                    release()
                }
                cameraReleaseEnable = false
                mBtnPlay.visibility = View.VISIBLE
                MediaUtils.getImageForVideo(path) {
                    //获取到第一帧图片后再显示操作按钮
                    Log.d(TAG, "获取到了第一帧")
                    imgPath = it.absolutePath
                    mLlRecordOp.visibility = View.VISIBLE
                }
            } catch (e: java.lang.RuntimeException) {
                //当catch到RE时，说明是录制时间过短，此时将由录制改变为拍摄
                mType = TYPE_IMAGE
                Log.e("拍摄时间过短", e.message)
                mRecorder.apply {
                    reset()
                    release()
                }
                recorderReleaseEnable = false
                mCamera.takePicture(null, null, Camera.PictureCallback { data, camera ->
                    data?.let {
                        saveImage(it) { imagepath ->
                            Log.d(TAG, "转为拍照，获取到图片数据 $imagepath")
                            imgPath = imagepath
                            mCamera.apply {
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
                })
            }
        }
    }

    //播放录像
    private fun playRecord() {
        //修复录制时home键切出再次切回时无法播放的问题
        if (cameraReleaseEnable) {
            Log.d(TAG, "回收摄像头资源")
            mCamera.apply {
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
        mMediaPlayer = MediaPlayer.create(VideoRecordActivity@ this, uri)
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
            val newBitmap = PictureUtils.rotateBitmap(bitmap,90)
            imgFile.createNewFile()
            val os = BufferedOutputStream( FileOutputStream(imgFile))
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
        val availablePreviewSizes = mCamera.parameters.supportedPreviewSizes
        Log.e(TAG, "屏幕宽度 ${screenResolution.x}  屏幕高度${screenResolution.y}")
        for (previewSize in availablePreviewSizes) {
            Log.v(TAG, " PreviewSizes = $previewSize")
            mCameraPreviewWidth = previewSize.width
            mCameraPreviewHeight = previewSize.height
            val newDiffs = Math.abs(mCameraPreviewWidth - screenResolution.y) + Math.abs(mCameraPreviewHeight - screenResolution.x)
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
            Log.e(TAG, "${previewSize.width} ${previewSize.height}  宽度 $bestPreviewWidth 高度 $bestPreviewHeight")
        }
        Log.e(TAG, "最佳宽度 $bestPreviewWidth 最佳高度 $bestPreviewHeight")
        return Pair(bestPreviewWidth, bestPreviewHeight)
    }
}
