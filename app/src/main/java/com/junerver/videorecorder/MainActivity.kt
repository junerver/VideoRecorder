package com.junerver.videorecorder

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.widget.Toast
import com.tbruyelle.rxpermissions2.RxPermissions
import kotlinx.android.synthetic.main.activity_main.*

class MainActivity : AppCompatActivity() {
    val REQUEST_VIDEO = 99

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        mBtnRecord.setOnClickListener {
            val rxPermissions = RxPermissions(this)
            rxPermissions.request(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.RECORD_AUDIO)
                    .subscribe { granted ->
                        if (granted) {
                            startActivityForResult(Intent(this@MainActivity, VideoRecordActivity::class.java), REQUEST_VIDEO)
                        } else {
                            Toast.makeText(this@MainActivity, "未授权", Toast.LENGTH_SHORT).show()
                        }
                    }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == REQUEST_VIDEO) {
                var path = data?.getStringExtra("path")
                var imgPath = data?.getStringExtra("imagePath")
                mTvResult.append("视频地址：\n\r $path \n\r缩略图地址：\n\r $imgPath")
            }
        }
    }
}
