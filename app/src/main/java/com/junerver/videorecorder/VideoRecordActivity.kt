package com.junerver.videorecorder

import android.app.Activity
import android.content.Intent
import android.graphics.PixelFormat
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
import java.util.*

class VideoRecordActivity : AppCompatActivity() {


    private val TAG = "VideoRecordActivity"
    private var mStartedFlag = false //录像中标志
    private var mPlayFlag = false
    private lateinit var mRecorder: MediaRecorder
    private lateinit var mSurfaceHolder: SurfaceHolder
    private lateinit var mCamera: Camera
    private lateinit var mMediaPlayer: MediaPlayer
    private lateinit var path: String //最终视频路径
    private var timer = 0 //计时器
    private val maxSec = 10
    private lateinit var imgPath:String
    private var startTime:Long = 0L
    private var stopTime:Long = 0L
    private var cameraReleaseEnable = true  //回收摄像头
    private var recorderReleaseEnable = false  //回收recorder
    private var playerReleaseEnable = false //回收palyer


    //用于记录视频录制时长
    var handler = Handler()
    var runnable = object : Runnable {
        override fun run() {
            timer++
            if (timer < 100) {
                // 之所以这里是100 是为了方便使用进度条
                mProgress.progress = timer
                //之所以每一百毫秒增加一次计时器是因为：总时长的毫秒数 / 100 即每次间隔延时的毫秒数 为 100
                handler.postDelayed(this, 100)
            } else {
                //停止录制 保存录制的流、显示供操作的ui
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
                mCamera.startPreview()
                mCamera.cancelAutoFocus()
                // 关键代码 该操作必须在开启预览之后进行（最后调用），
                // 否则会黑屏，并提示该操作的下一步出错
                // 只有执行该步骤后才可以使用MediaRecorder进行录制
                // 否则会报 MediaRecorder(13280): start failed: -19
                mCamera.unlock()
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
                    //选装90度
                    mCamera.setDisplayOrientation(90)
                    mCamera.setPreviewDisplay(holder)
                    val parameters = mCamera.parameters
                    parameters.pictureFormat = PixelFormat.JPEG
                    parameters.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE//1连续对焦
                    mCamera.parameters = parameters
                } catch (e: RuntimeException) {
                    //开启摄像头失败
                    finish()
                }

            }
        })
        mBtnRecord.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                stopRecord()
            }
            if (event.action == MotionEvent.ACTION_DOWN) {
                startRecord()
            }
            false
        }
        mBtnPlay.setOnClickListener {
            playRecord()
        }
        mBtnCancle.setOnClickListener {
            stopPlay()
            var videoFile = File(path)
            if (videoFile.exists() && videoFile.isFile) {
                videoFile.delete()
            }
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
        mBtnSubmit.setOnClickListener {
            stopPlay()
            var intent = Intent()
            intent.putExtra("path", path)
            intent.putExtra("imagePath", imgPath)
            setResult(Activity.RESULT_OK,intent)
            finish()
        }
    }

    override fun onStop() {
        super.onStop()
        if (mPlayFlag) {
            stopPlay()
        }
        if (mStartedFlag) {
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
        if (!mStartedFlag) {
            mStartedFlag = true
            mLlRecordOp.visibility = View.INVISIBLE
            mBtnPlay.visibility = View.INVISIBLE
            mLlRecordBtn.visibility = View.VISIBLE
            mProgress.visibility = View.VISIBLE //进度条可见
            //开始计时
            handler.postDelayed(runnable, 1000)
            recorderReleaseEnable = true
            mRecorder = MediaRecorder()
            mRecorder.reset()
            mRecorder.setCamera(mCamera)
            // 这两项需要放在setOutputFormat之前
            mRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            mRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA)
            // Set output file format
            mRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            // 这两项需要放在setOutputFormat之后 IOS必须使用ACC
            mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            //使用MPEG_4_SP格式在华为P20 pro上停止录制时会出现
            //MediaRecorder: stop failed: -1007
            //java.lang.RuntimeException: stop failed.
            // at android.media.MediaRecorder.stop(Native Method)
            mRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)

            mRecorder.setVideoSize(640, 480)
            mRecorder.setVideoFrameRate(30)
            mRecorder.setVideoEncodingBitRate(3 * 1024 * 1024)
            mRecorder.setOrientationHint(90)
            //设置记录会话的最大持续时间（毫秒）
            mRecorder.setMaxDuration(30 * 1000)
            path = Environment
                    .getExternalStorageDirectory().path+File.separator+"Video"
            if (path != null) {
                var dir = File(path)
                if (!dir.exists()) {
                    dir.mkdir()
                }
                path = dir.absolutePath + "/" + getDate() + ".mp4"
                Log.d(TAG,"文件路径： $path")
                mRecorder.setOutputFile(path)
                mRecorder.prepare()
                mRecorder.start()
                startTime = System.currentTimeMillis()
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
            //延时确保录制时间大于1s
            if (stopTime-startTime<1100) {
                Thread.sleep(1100+startTime-stopTime)
            }
            mRecorder.stop()
            mRecorder.reset()
            mRecorder.release()
            recorderReleaseEnable = false
            mCamera.lock()
            mCamera.stopPreview()
            mCamera.release()
            cameraReleaseEnable = false
            mBtnPlay.visibility = View.VISIBLE
            MediaUtils.getImageForVideo(path) {
                //获取到第一帧图片后再显示操作按钮
                Log.d(TAG,"获取到了第一帧")
                imgPath=it.absolutePath
                mLlRecordOp.visibility = View.VISIBLE
            }
        }
    }

    //播放录像
    private fun playRecord() {
        //修复录制时home键切出再次切回时无法播放的问题
        if (cameraReleaseEnable) {
            Log.d(TAG,"回收摄像头资源")
            mCamera.lock()
            mCamera.stopPreview()
            mCamera.release()
            cameraReleaseEnable = false
        }
        playerReleaseEnable = true
        mPlayFlag = true
        mBtnPlay.visibility=View.INVISIBLE
        mMediaPlayer.reset()
        var uri = Uri.parse(path)
        mMediaPlayer = MediaPlayer.create(VideoRecordActivity@this, uri)
        mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC)
        mMediaPlayer.setDisplay(mSurfaceHolder)
        mMediaPlayer.setOnCompletionListener {
            //播放解释后再次显示播放按钮
            mBtnPlay.visibility =View.VISIBLE
        }
        try{
            mMediaPlayer.prepare()
        }catch (e:Exception){
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
}
