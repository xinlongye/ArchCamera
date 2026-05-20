# ArchCamera

基于 Android Camera2 API 的相机应用，面向多种机型提供兼容的权限申请、预览、拍照、图库与设置能力。

## 功能概览

| 界面 | 说明 |
|------|------|
| 相机预览 | 前后摄切换、拍照、预览/拍照比例、普通/专业模式、多焦段（后摄）、3A 手动调节 |
| 图库 | 网格浏览已拍照片，点击进入查看器 |
| 图片查看器 | 全屏查看、缩放、HUD 展示 EXIF |
| 设置 | 预览/拍照分辨率、保存路径、前摄镜像开关 |

### 相机预览要点

- **比例**：1:1、3:4、9:16、全屏；预览所见即所得
- **模式**：普通模式自动 3A；专业模式（仅后摄）可手动调节对焦、曝光补偿、ISO、快门、白平衡
- **焦段**：后摄多摄像头时支持超广/主摄/长焦等切换
- **布局**：预览层全屏底层绘制，宽度贴齐屏幕，高度随比例变化

更完整的功能与交互说明见 [`docs/功能描述.md`](docs/功能描述.md)。

## 技术栈

- **语言**：Java 11
- **相机**：Camera2
- **UI**：View Binding、Material、ConstraintLayout
- **原生**：CMake + NDK（`armeabi-v7a` / `arm64-v8a`），集成 OpenCV / GLM 头文件（`app/src/main/cpp`）
- **其他**：Glide（图库缩略图）、ExifInterface（元数据）

## 环境要求

- Android Studio（推荐最新稳定版）
- JDK 11+
- Android SDK：**minSdk 34**，**targetSdk 36**，**compileSdk 36**
- 已配置 `local.properties` 中的 SDK 路径（该文件不纳入版本库）

## 构建与运行

```bash
# 克隆后于项目根目录
./gradlew assembleDebug

# 安装到已连接设备
./gradlew installDebug
```

或在 Android Studio 中打开工程，同步 Gradle 后直接 Run。

## 项目结构

```
app/src/main/java/com/cam/archcamera/
├── preview/          # 主界面：预览、拍照、模式与比例
├── camera/           # Camera2 与分辨率选择
├── gallery/          # 图库
├── imageviewer/      # 图片查看与 EXIF
├── settings/         # 设置与偏好
└── util/             # 通用工具
```

## 权限

应用按需申请相机、存储/媒体读取等权限，以适配不同 Android 版本与机型。

## 许可证

尚未声明开源许可证；如需对外分发请自行补充 LICENSE。
