# Virtual ADB Agent

一个轻量级的 Android 本地 TCP 代理服务，通过标准 ADB 有线协议实现 **无需 Root** 的设备控制能力。

## 功能特性

- **标准 ADB 协议** — 支持 `adb connect` 直连，兼容主流自动化框架
- **无 Root 权限** — 基于 AccessibilityService + MediaProjection API
- **手势注入** — 点击、滑动、按键事件（主页/返回/最近任务）
- **屏幕截图** — 支持智能缩放、旋转矫正、JPEG 质量控制
- **TCP 桥接** — 本地回环或局域网模式，默认端口 10000
- **开发者模式** — 调试截图保存、详细日志、分块传输监控

## 快速导航

- [快速开始](/guide/start) — 从安装到连接的全流程
- [GitHub 仓库](https://github.com/Jyf0214/virtual-adb-agent) — 源代码和问题反馈

## 许可证

本项目基于 **GNU Affero General Public License v3** 发布。
