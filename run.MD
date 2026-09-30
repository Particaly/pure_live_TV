### android通用
  `flutter build apk`
### android分包
  `flutter build apk --split-per-abi`

### TV 一键构建（2 ABI x 2 渲染器 = 4 个 APK）
  `powershell -ExecutionPolicy Bypass -File tool\build_tv_apks.ps1`

等效于逐个执行（impeller / skia 各一次，每次产出 v7a + arm64 两个 APK）：
  `flutter build apk --release --flavor impeller --split-per-abi --target-platform android-arm,android-arm64`
  `flutter build apk --release --flavor skia --split-per-abi --target-platform android-arm,android-arm64`

产物复制到 `dist/`：
  `PureLive-TV-arm64-v8a-impeller.apk` / `PureLive-TV-arm64-v8a-skia.apk`
  `PureLive-TV-armeabi-v7a-impeller.apk` / `PureLive-TV-armeabi-v7a-skia.apk`

注意：
- 两个 flavor 共用同一 applicationId，同一设备装其一即可。
- 必须带 `--split-per-abi`；静态 gradle splits 会与 Flutter 插件注入的
  abiFilters 冲突（见 android/app/build.gradle.kts 注释）。
- 必须带 `--target-platform android-arm,android-arm64`，否则 split-per-abi
  默认还会产出 x86_64 包。
- minSdk 钉在 23（Android 6.0）。Flutter 3.47 默认 24 且构建时会打印一条
  "below the recommended" 警告，属预期。构建环境：Flutter 3.47.x stable、
  AGP 9、Java 17+；国内网络建议设 PUB_HOSTED_URL/FLUTTER_STORAGE_BASE_URL
  镜像。

### Android 6.0 (API 23) 兼容补丁（构建时自动执行，无需手动操作）
Flutter 3.47 的官方产物（引擎 so 与 embedding jar）都是按 minSdk 24 构建的，
直接在 Android 6.0 上会闪退。android/app/build.gradle.kts 在打包前自动完成
四层修复，升级 Flutter 后无需改动，会随新引擎版本自动重新应用：
1. **原生库补丁**（merge*NativeLibs 的 doLast）：
   - `__fwrite_chk`/`__write_chk`（API 24 的 fortify 包装函数）重定向到
     `fwrite`/`write`；
   - 清除 .gnu.version 符号版本要求（NDK 新版引入的 LIBC_N 标签，
     Android 6 的 libc 没有版本定义，带版本查找必然失败）；
   - `getifaddrs`/`freeifaddrs` 弱化（Android 7 才有，弱引用在旧系统解析为
     null，相关功能在 Dart 层有 API 24 守卫）；
   - 同时覆盖 64 位（arm64/x86_64）与 32 位（armeabi-v7a）ELF，打完逐库
     校验，仍有版本化导入或未重命名符号时构建直接失败（曾因 32 位节头
     偏移写错导致 v7a 包漏打补丁、真机启动闪退）。
2. **embedding 类替换**（patchFlutterEmbedding* 任务）：upstream 在 minSdk
   提到 24 时删除了 LocalizationPlugin 的 API 24 守卫，FlutterEngine 构造即
   崩。compat23patch/ 下的补丁源码恢复守卫后重新编译，替换进 embedding jar
   并接管对应的 maven 模块（含其 POM 声明的 androidx 传递依赖）。
3. **libvulkan 桩**（src/main/cpp/）：libmpv 声明 DT_NEEDED libvulkan.so
   （Android 7 才有），缺失时 MediaKitPlugin 注册抛 Error，穿透
   GeneratedPluginRegistrant 的 catch(Exception) 导致其后所有插件失效。
4. **Dart 层容错**：MediaKit.ensureInitialized / GlobalPlayerService.initialize
   失败不再中断启动与内核创建；NetworkInterface.list 两处调用加了 API 24
   守卫（getifaddrs 为 null 时原生调用无法被 Dart 捕获）。

Android 6.0 设备上的已知限制：
- media_kit（Mpv内核）不可用，请在 设置→播放器内核 切换为 Exo 或 IJK（IJK 仅
  含 ARM 原生库）；默认内核无需改动，仅影响 6.0 老设备。
- 6.0 的 libc 无 TLS 1.3 等新特性，个别 HTTPS 源可能需要回退，属系统限制。
- 升级安装时若 ReLinker 缓存了旧 libflutter.so（app_lib 目录），需卸载重装一次。
