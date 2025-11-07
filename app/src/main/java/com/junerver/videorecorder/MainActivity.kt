package com.junerver.videorecorder

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.MediaCodecList
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.junerver.videorecorder.VideoRecordActivity.Companion.TYPE_IMAGE

class MainActivity : AppCompatActivity() {
    val REQUEST_VIDEO = 99

    private lateinit var mBtnRecord: Button
    private lateinit var mTvResult: TextView
    private lateinit var mTvCodecSupport: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mBtnRecord = findViewById(R.id.mBtnRecord)
        mTvResult = findViewById(R.id.mTvResult)
        mTvCodecSupport = findViewById(R.id.mTvCodecSupport)

        mBtnRecord.setOnClickListener {
            showEncoderPicker()
        }

        showCodecSupport()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == REQUEST_VIDEO) {
                val path = data?.getStringExtra("path")
                val imgPath = data?.getStringExtra("imagePath")
                val type = data?.getIntExtra("type", -1)
                if (type == VideoRecordActivity.TYPE_VIDEO) {
                    mTvResult.text = "视频地址：\n$path \n缩略图地址：\n$imgPath"
                } else if (type == VideoRecordActivity.TYPE_IMAGE) {
                    mTvResult.text = "图片地址：\n$imgPath"
                }
            }
        }
    }

    private fun showEncoderPicker() {
        val options = VideoEncoderOption.availableOptions()
        val labels = options.map { it.label }.toTypedArray()
        if (options.isEmpty()) {
            Toast.makeText(this, R.string.main_codec_empty, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.encoder_picker_title)
            .setItems(labels) { _, index ->
                val selected = options.getOrElse(index) { VideoEncoderOption.H264 }
                requestPermissionsAndLaunch(selected)
            }
            .show()
    }

    private fun requestPermissionsAndLaunch(option: VideoEncoderOption) {
        val describe = getString(R.string.encoder_permission_rationale, option.label)
        rxRequestPermissions(
            Manifest.permission.CAMERA,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.RECORD_AUDIO,
            describe = describe
        ) {
            startRecorder(option)
        }
    }

    private fun startRecorder(option: VideoEncoderOption) {
        val intent = Intent(this@MainActivity, VideoRecordActivity::class.java).apply {
            putExtra(VideoRecordActivity.EXTRA_ENCODER_OPTION, option.extraValue)
        }
        startActivityForResult(intent, REQUEST_VIDEO)
    }

    private fun showCodecSupport() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            mTvCodecSupport.text = getString(R.string.main_codec_empty)
            return
        }

        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        val encoderLines = codecList.codecInfos
            .filter { it.isEncoder }
            .mapNotNull { info ->
                val videoTypes = info.supportedTypes.filter { it.startsWith("video/") }
                if (videoTypes.isEmpty()) {
                    null
                } else {
                    info.name to videoTypes
                }
            }

        val sb = StringBuilder()
        if (encoderLines.isEmpty()) {
            sb.appendLine(getString(R.string.main_codec_empty))
        } else {
            sb.appendLine(getString(R.string.main_codec_header))
            encoderLines.forEach { (name, types) ->
                sb.append("• ").append(name).append(" -> ")
                    .append(types.joinToString()).appendLine()
            }
        }

        val availableOptions = VideoEncoderOption.availableOptions()
        if (availableOptions.isNotEmpty()) {
            sb.appendLine().append("可选编码: ")
                .append(availableOptions.joinToString { it.label })
        }

        mTvCodecSupport.text = sb.toString().trim()
    }
}
