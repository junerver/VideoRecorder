package com.junerver.videorecorder

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
class MainActivity : AppCompatActivity() {
    val REQUEST_VIDEO = 99

    // View成员变量
    private lateinit var mBtnRecord: Button
    private lateinit var mTvResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化View
        mBtnRecord = findViewById(R.id.mBtnRecord)
        mTvResult = findViewById(R.id.mTvResult)

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
