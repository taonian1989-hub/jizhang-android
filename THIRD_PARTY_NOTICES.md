# 代码与依赖

原生模型客户端、密钥加密、搜索规范化、受限WebView及构建脚本复用用户已有留白/研脉/智讯项目的 MIT 代码，已保留 LICENSE 声明。账本业务、账本加密快照、产品交互、界面和矢量图标为本项目新增。

APK 仅使用 Android 平台API，不包含运行期第三方JS包。构建工具采用 Google Android SDK Build Tools/Platform 与 Eclipse Temurin JDK，工具不随源码包分发，许可证以各自发行版为准。

本地JVM协议测试使用 org.json:json:20240303（Public Domain，测试专用，不打入APK）。Playwright CLI仅用于开发验收，不打入APK。应用未使用钱迹、YNAB或其他产品的界面素材与商标资产。
