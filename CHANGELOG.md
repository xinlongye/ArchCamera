# 更新日志

版本号与 `version.properties` 保持一致；发布前请同步更新本文件。

## [1.1.1] - 2026-05-21

### 修复

- 切换前后摄后中间 HUD 的拍照/预览分辨率不刷新：改为立即应用分辨率策略，避免预览重启触发的布局防抖一直推迟更新
- 摄像头 ID 变化时即使预览流尺寸未变也会重新开流

### 改进

- 预览帧采集：`acquireNextImage` 逐帧排空并显式 `close`，减轻部分 Mali 设备上 `acquireLatestImage` 的 gralloc 问题
- YUV 转 NV21：使用 `ByteBuffer.duplicate()`，避免 `rewind` 干扰 Image 平面缓冲
- GLES 预览：双缓冲改为三缓冲，降低读写竞争
- 前摄旋转/镜像与 Android `computeRelativeRotation` 对齐，镜像在 GLES 中处理时补偿旋转
- `ImageReader` 最大图像数增至 8，为排空预留余量

## [1.1.0] - 2026-05-21

### 新增

- Camera2 预览管线：`ImageReader`（YUV_420_888）→ NV21 → OpenGL ES 3 原生着色器渲染（`PreviewGlSurfaceView` / `Camera2PreviewController`）
- 预览帧率 HUD（界面显示当前预览 fps）
- 预览分辨率标签持久化与流尺寸解析（`PreviewStreamSizeResolver`）
- 运行时相机权限申请与预览就绪后再开流

### 改进

- 预览布局与分辨率策略：防抖合并、layout pass 后再改几何，减少反复 open/close
- 分辨率选择逻辑与设置页联动优化
- 前摄镜像与显示旋转变换（`CameraPreviewTransform`）

### 构建

- NDK 增加 `preview_renderer`、`preview_jni`、`yuv_to_nv21` 源文件，链接 GLESv3

## [1.0.0] - 2026-05-20

- 初始开源提交：Camera2 预览/拍照、图库、图片查看器、设置与多机型兼容权限
