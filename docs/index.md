---
layout: home

hero:
  name: Virtual ADB Agent
  text: 无需 Root 的 Android ADB 代理
  tagline: 基于无障碍服务 + MediaProjection 的轻量级 TCP 桥接工具，通过标准 ADB 协议控制设备
  actions:
    - theme: brand
      text: 快速开始
      link: /guide/start
    - theme: alt
      text: GitHub 仓库
      link: https://github.com/Jyf0214/virtual-adb-agent

features:
  - title: 标准 ADB 协议
    details: 支持 adb connect 直连，兼容主流自动化框架
  - title: 无 Root 权限
    details: 基于 AccessibilityService + MediaProjection API，无需 Root
  - title: TCP 桥接
    details: 本地回环或局域网模式，默认端口 10000
  - title: 手势注入
    details: 点击、滑动、按键事件（主页 / 返回 / 最近任务）
  - title: 屏幕截图
    details: 智能缩放、旋转矫正、JPEG 质量控制
  - title: 开发者模式
    details: 调试截图保存、详细日志、分块传输监控
---
