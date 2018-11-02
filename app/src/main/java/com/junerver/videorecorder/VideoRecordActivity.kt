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
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import kotlinx.android.synthetic.main.activity_video_record.*
import java.io.File
import java.util.*

class VideoRecordActivity : AppCompatActivity() {

    private var mStartedFlag = false //录像中标志
    private var mRecorder: MediaRecorder?=null
    private lateinit var mSurfaceHolder: SurfaceHolder
    private var mCamera: Camera?=null
    private var mMediaPlayer: MediaPlayer?=null
    private lateinit var path: String //最终视频路径
    private var timer = 0 //计时器
    private val maxSec = 10
    private lateinit var imgPath:String


    //用于记录视频录制时长
    var handler = Handler()
    var runnable = object : Runnable {
        override fun run() {
            timer++
            if (timer < maxSec) {
                handler.postDelayed(this, 1000)
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
                mCamera?.startPreview()
                mCamera?.cancelAutoFocus()
                // 关键代码 该操作必须在开启预览之后进行（最后调用），
                // 否则会黑屏，并提示该操作的下一步出错
                // 只有执行该步骤后才可以使用MediaRecorder进行录制
                // 否则会报 MediaRecorder(13280): start failed: -19
                mCamera?.unlock()
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
                    mCamera?.setDisplayOrientation(90)
                    mCamera?.setPreviewDisplay(holder)
                    val parameters = mCamera?.parameters
                    parameters?.pictureFormat = PixelFormat.JPEG
                    parameters?.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE//1连续对焦
                    mCamera?.parameters = parameters
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

    override fun onPause() {
        super.onPause()
        stopPlay()
    }

    override fun onStop() {
        super.onStop()
        mRecorder?.release()
        mRecorder = null
        mCamera?.stopPreview()
        mCamera?.release()
        mCamera = null
        mMediaPlayer?.release()
        mMediaPlayer=null
    }

    //开始录制
    private fun startRecord() {
        if (!mStartedFlag) {
            mStartedFlag = true
            mLlRecordOp.visibility = View.INVISIBLE
            mBtnPlay.visibility = View.INVISIBLE
            mLlRecordBtn.visibility = View.VISIBLE
            //开始计时
            handler.postDelayed(runnable, 1000)
            mRecorder = MediaRecorder()
            mRecorder?.reset()
            mRecorder?.setCamera(mCamera)
            // 这两项需要放在setOutputFormat之前
            mRecorder?.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            mRecorder?.setVideoSource(MediaRecorder.VideoSource.CAMERA)
            // Set output file format
            mRecorder?.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            // 这两项需要放在setOutputFormat之后 IOS必须使用ACC
            mRecorder?.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            //使用MPEG_4_SP格式在华为P20 pro上停止录制时会出现
            //MediaRecorder: stop failed: -1007
            //java.lang.RuntimeException: stop failed.
            // at android.media.MediaRecorder.stop(Native Method)
            mRecorder?.setVideoEncoder(MediaRecorder.VideoEncoder.H264)

            mRecorder?.setVideoSize(640, 480)
            mRecorder?.setVideoFrameRate(30)
            mRecorder?.setVideoEncodingBitRate(3 * 1024 * 1024)
            mRecorder?.setOrientationHint(90)
            //设置记录会话的最大持续时间（毫秒）
            mRecorder?.setMaxDuration(30 * 1000)
            path = Environment
                    .getExternalStorageDirectory().path+File.separator+"Video"
            if (path != null) {
                var dir = File(path)
                if (!dir.exists()) {
                    dir.mkdir()
                }
                path = dir.absolutePath + "/" + getDate() + ".mp4"
                mRecorder?.setOutputFile(path)
                mRecorder?.prepare()
                mRecorder?.start()
            }
        }
    }

    //结束录制
    private fun stopRecord() {
        if (mStartedFlag) {
            mStartedFlag = false

            mBtnPlay.visibility = View.VISIBLE
            mLlRecordBtn.visibility = View.INVISIBLE

            handler.removeCallbacks(runnable)
            mRecorder?.stop()
            mRecorder?.reset()
            mRecorder?.release()
            mRecorder = null
            mCamera?.stopPreview()
            mCamera?.release()
            mCamera=null
            MediaUtils.getImageForVideo(path) {
                //获取到第一帧图片后再显示操作按钮
                imgPath=it.absolutePath
                mLlRecordOp.visibility = View.VISIBLE
            }
        }
    }

    //播放录像
    private fun playRecord() {
        mBtnPlay.visibility=View.INVISIBLE
        mMediaPlayer?.reset()
        var uri = Uri.parse(path)
        mMediaPlayer = MediaPlayer.create(VideoRecordActivity@this, uri)
        mMediaPlayer?.setAudioStreamType(AudioManager.STREAM_MUSIC)
        mMediaPlayer?.setDisplay(mSurfaceHolder)
        mMediaPlayer?.setOnCompletionListener {
            //播放解释后再次显示播放按钮
            mBtnPlay.visibility =View.VISIBLE
        }
        try{
            mMediaPlayer?.prepare()
        }catch (e:Exception){
            e.printStackTrace()
        }
        mMediaPlayer?.start()
    }

    //停止播放录像
    private fun stopPlay() {
        if (mMediaPlayer != null && mMediaPlayer!!.isPlaying) {
            mMediaPlayer!!.stop()
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
