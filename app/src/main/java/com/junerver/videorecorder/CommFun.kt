package com.junerver.videorecorder

import android.app.Activity
import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import android.view.KeyEvent
import android.widget.Toast
import com.tbruyelle.rxpermissions2.RxPermissions

//传入权限与权限描述，在需要权限的功能打开之前调用
fun Activity.rxRequestPermissions(vararg permissions: String, describe: String, onGranted:()->Unit) {
    val keylistener = DialogInterface.OnKeyListener { _, keyCode, event ->
        keyCode == KeyEvent.KEYCODE_BACK && event.repeatCount == 0
    }
    var dialog = AlertDialog.Builder(this)
            .setTitle("权限申请")
            .setMessage("${describe}为必选项，开通后方可正常使用APP,请在设置中开启。")
            .setOnKeyListener(keylistener)
            .setCancelable(false)
            .setPositiveButton("去开启") { _, _ ->
//                JumpPermissionManagement.GoToSetting(this)
                finish()
            }
            .setNegativeButton("结束") { _, _ ->
                Toast.makeText(this, "${describe}权限未开启，不能使用该功能！", Toast.LENGTH_SHORT).show()
                finish()
            }
            .create()
    val rxPermissions = RxPermissions(this)
    //传递kotlin的可变长参数给Java的可变参数的时候需要使用修饰符 * ；这个修饰符叫做Speread Operator
    // 它只支持展开的Array 数组，不支持List集合，它只用于变长参数列表的实参，不能重载，它也不是运算符；
    rxPermissions.request(*permissions)
            .subscribe {granted ->
                if (granted) {
                    onGranted()
                } else {
                    dialog.show()
                }
            }
}