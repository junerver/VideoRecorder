# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

这是一个仿微信短视频录制的Android应用，支持短按拍照、长按录制视频功能。项目正在从旧的Camera API迁移到Camera2/CameraX，并从Java迁移到Kotlin。

## 构建和开发命令

### 基本构建命令
```bash
# 构建项目
./gradlew build

# 清理项目
./gradlew clean

# 编译Debug版本
./gradlew assembleDebug

# 编译Release版本
./gradlew assembleRelease

# 运行单元测试
./gradlew test

# 运行Android测试
./gradlew connectedAndroidTest
```

### Android Studio开发
- 使用Android Studio打开项目根目录
- 确保使用Java 8+ 和 Android SDK 33+
- 项目使用Kotlin 1.9.20

## 项目架构

### 核心组件
- **MainActivity**: 应用主入口，包含启动录制功能的按钮
- **VideoRecordActivity**: 核心录制界面，处理Camera操作、视频录制和拍照
- **MediaUtils**: 媒体文件处理工具类，负责文件创建、视频缩略图生成
- **CommFun**: 通用功能扩展，包含权限管理功能

### 技术架构
- **UI层**: 标准Android Activity布局，使用SurfaceView显示Camera预览
- **业务逻辑**: 在Activity中直接处理，包含状态管理和用户交互
- **媒体处理**: 使用MediaRecorder进行视频录制，MediaMetadataRetriever生成缩略图
- **权限管理**: 使用RxPermissions进行动态权限申请

### Camera迁移状态
- 当前分支: `camera2`
- 迁移进度: 正在从旧Camera API迁移到Camera2/CameraX
- 依赖库: androidx.camera:camera-* 1.4.0
- 注意: VideoRecordActivity中同时存在新旧Camera API的import，迁移尚未完成

## 关键技术栈

- **语言**: Kotlin 1.9.20
- **构建工具**: Android Gradle Plugin 7.4.2
- **SDK版本**: compileSdk 33, minSdk 21, targetSdk 30
- **Camera**: CameraX 1.4.0 (迁移中)
- **权限**: RxPermissions 2.0.1
- **UI组件**: MaterialProgressBar
- **编译器**: Kace Kotlin编译器插件

## 重要功能特性

### 录制功能
- 支持最大10秒视频录制
- 短按拍照，长按录制视频
- 自动生成视频缩略图
- 支持前后摄像头切换

### 文件管理
- 视频保存格式: MP4
- 图片保存格式: JPG
- 存储位置: `Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)/image/`
- 文件命名: VID_yyyyMMdd_HHmmss.mp4, IMG_yyyyMMdd_HHmmss.jpg

### 权限要求
- CAMERA: 相机权限
- RECORD_AUDIO: 录音权限
- WRITE_EXTERNAL_STORAGE: 存储权限
- READ_EXTERNAL_STORAGE: 读取存储权限

## 已知问题和解决方案

### MediaRecorder stop failed: -1007
在部分设备上可能遇到此异常，解决方案：
1. 确保MediaRecorder视频输出尺寸为系统支持尺寸
2. 保持Camera预览尺寸与MediaRecorder输出尺寸一致
3. 使用H264编码器替代MPEG_4_SP

### 录制时间过短崩溃
当录制时间过短时调用stop()可能导致异常，建议：
1. 使用try-catch捕获异常并提示用户
2. 确保录制时间至少1秒

## 开发注意事项

### Camera2迁移
- 注意VideoRecordActivity中混合使用新旧Camera API
- 迁移完成后应移除旧的Camera import
- 确保CameraX配置正确，特别是生命周期管理

### 权限处理
- 在Android 6.0+需要动态申请权限
- 使用CommFun.rxRequestPermissions()进行权限申请
- 确保在录制前已获得所有必要权限

### 文件提供者
- 使用FileProvider进行文件访问，适配Android N+
- 配置文件: res/xml/paths.xml
- 权限: com.junerver.videorecorder.fileprovider

### 性能优化
- 注意Camera资源的正确释放
- MediaRecorder使用完毕后需要stop()和release()
- 视频缩略图生成使用异步处理