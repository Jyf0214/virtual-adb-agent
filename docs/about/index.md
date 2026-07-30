# 关于

## 从源码构建

### 前置要求

- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 17+
- Android SDK 34+
- Gradle 8.12+

### 构建步骤

```shell
git clone https://github.com/Jyf0214/virtual-adb-agent.git
cd virtual-adb-agent
gradle assembleDebug
```

APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`

### 代码检查

项目配置了静态分析工具链：

```shell
# Detekt 代码质量分析
gradle detekt

# Android Lint
gradle lint

# 完整构建 + 检查
gradle assembleDebug
```

## 开源许可

本项目基于 [AGPL-3.0](https://www.gnu.org/licenses/agpl-3.0.html) 许可协议发布。

## 致谢

- [AndroidX](https://developer.android.com/jetpack/androidx) — Jetpack Compose 等库
- [VitePress](https://vitepress.dev/) — 文档框架
- 所有参与测试和反馈的用户
