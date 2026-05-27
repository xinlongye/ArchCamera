# 更新日志

版本号与 `version.properties` 保持一致；发布前请同步更新本文件。

## [1.1.4] - 2026-05-27

### 修复

- 专业模式手动白平衡映射：`Camera3ARequestMapper` 接入 `DngWbModel`，优先基于 DNG 静态元数据矩阵计算 `COLOR_CORRECTION_GAINS`，降低预览偏色与色温映射不一致问题

### 改进

- 新增 `DngWbModel`：支持从 `SENSOR_FORWARD_MATRIX*`、`SENSOR_COLOR_TRANSFORM*`、`SENSOR_CALIBRATION_TRANSFORM*` 推导白平衡增益，并在缺少元数据时回退既有 Kelvin 估算链路
- `Camera3AResultParser` / `Camera3ADisplayValues` / `Camera3AFormatters` 新增白平衡来源标识（`DNG` / `FALLBACK`），HUD 可区分估算来源
- `Camera3ACapabilities` 增加 DNG 白平衡能力模型挂载，能力探测与请求映射链路打通

## [1.1.3] - 2026-05-26

### 新增

- 专业模式 `camera_3a` 面板：对焦距离、曝光补偿、ISO、快门、白平衡各行支持 A/M 切换与滑块调节
- `Camera3ASettings`、`Camera3ARequestMapper`、`Camera3AResultParser` 等：3A 参数映射、能力探测与 CaptureResult 解析
- 预览 HUD 显示当前 3A 读数（对焦、EV、ISO、快门、白平衡）
- `Camera3APanelController`：面板 UI 与 Camera2 重复请求联动

### 改进

- 3A 相关命名统一为 `camera_3a`（布局、字符串、文档）
- `preview_renderer.h` 移至 `app/src/main/cpp/include/`

### 已知问题

- 专业模式下手动白平衡预览画面显示异常，待后续更新修复

## [1.1.2] - 2026-05-24

### 新增

- Camera2 静态拍照：预览会话内增加 JPEG `ImageReader`，快门触发 `TEMPLATE_STILL_CAPTURE` 并写入存储
- `PhotoCaptureSaver`：按设置路径保存 JPEG、写入 EXIF（含方向与前摄镜像）、MediaStore 索引与媒体扫描
- `CaptureStreamSizeResolver`、`JpegOrientationHelper`、`StillCaptureImageListener`：拍照流尺寸解析、JPEG 方向与帧回调拆分

### 改进

- 主界面快门由占位改为真实拍照；成功后 Toast 并刷新底部图库缩略图
- 分辨率策略：拍照流标签变化时与预览流一并触发重启，保持 HUD 与开流一致
- 图库缩略图：`GalleryThumbGlide` 限制解码尺寸，避免 12MP 全图解码导致 OOM（网格与底部栏共用）

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
