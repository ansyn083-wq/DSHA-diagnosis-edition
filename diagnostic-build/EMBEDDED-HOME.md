# 首页内嵌聊天改动

以 DSHA-GPT-diag3-source.zip 为基线。首页继承已存在的 WebView 聊天宿主，首次只创建原生准备界面；当前服务鉴权就绪后在首页预先声明的容器加载聊天。普通进入聊天不再 startActivity 到 WebPreviewActivity，顶部设置持续可达。

保留现有来源校验、服务代次、上传下载、麦克风、草稿桥和未知状态冻结机制。内嵌模式中已退出的服务回到首页准备区，根级返回回桌面，错误面板的恢复入口打开启动详情。独立浏览器与 Gecko 降级仍沿用原流程；此改动不是纯原生聊天。

修正隐藏草稿输入在网页消费后再次写回旧草稿的问题；补充首页 onDestroy 时清理 handler，以及浏览器延迟创建时的生命周期空值保护。布局新增同窗聊天容器；singleTop 避免栈顶首页重复创建。

检查：资源 XML 与 Manifest 解析通过；node tools/test-chat-draft.mjs 通过。新增 DiagnosticPackageTest.homeOwnsBrowserContainerBeforeRuntimeIsReady，尚未执行 Android/Robolectric 测试。

当前环境缺 javac、完整 Android SDK/NDK/Gradle 缓存和离线构建输入。本次没有编译 APK；不能将前次 diag3 APK 当成本次内嵌版。tools/test-chat-entry.py 因 javac 缺失未执行。

后续必须在完整构建环境执行 DiagnosticPackageTest、WebSessionRecoveryTest、StoredWebSessionPrivateDirectoryTest 以及浏览器生命周期/导航测试，再生成使用原诊断密钥的 APK。真机验收：首次准备→同页聊天；后台返回不重载；设置返回保留 DOM；旋转保持页面；UNKNOWN 不重发；正常停止后回准备区；新服务代次不沿用旧权限；附件、录音、下载、输入法及草稿逐项检查。

首次运行环境缓存、安装幂等及旧服务接管沿用前版机制；本次未新增或声称解决旧服务识别问题。没有真机安装、性能或页面截图验收。
