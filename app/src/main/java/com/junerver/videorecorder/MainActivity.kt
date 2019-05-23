package com.junerver.videorecorder

import android.Manifest
import android.app.Activity
import android.content.Intent

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_main.*

class MainActivity : AppCompatActivity() {
    val REQUEST_VIDEO = 99

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        mBtnRecord.setOnClickListener {
            rxRequestPermissions(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.RECORD_AUDIO, describe = "相机、存储、录音") {
                startActivityForResult(Intent(this@MainActivity, VideoRecordActivity::class.java), REQUEST_VIDEO)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == REQUEST_VIDEO) {
                var path = data?.getStringExtra("path")
                var imgPath = data?.getStringExtra("imagePath")
                val type = data?.getIntExtra("type",-1)
                if (type == TYPE_VIDEO) {
                    mTvResult.text = "视频地址：\n\r$path \n\r缩略图地址：\n\r$imgPath"
                } else if (type == TYPE_IMAGE) {
                    mTvResult.text = "图片地址：\n\r$imgPath"
                }
            }
        }
    }
}
