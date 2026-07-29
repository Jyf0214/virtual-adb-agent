# 快速开始

## 安装

1. 从 [Releases](https://github.com/Jyf0214/virtual-adb-agent/releases) 下载最新 APK
2. 在 Android 设备上安装并打开

## 权限配置

应用需要两项系统权限才能正常工作：

### 1. 开启无障碍服务

1. 进入系统 **设置 → 无障碍 → Virtual ADB Agent**
2. 开启无障碍服务开关
3. 确认授权弹窗

### 2. 授权屏幕截取

1. 在应用主页点击 **「授权并启动」**
2. 在系统弹窗中点击 **「立即开始」**
3. 授权成功后服务自动启动

## 连接设备

### 方式一：本地连接（默认）

```shell
adb connect 127.0.0.1:10000
```

### 方式二：局域网连接

在应用内开启 **「局域网模式」**，然后：

```shell
adb connect <设备IP地址>:10000
```

## 常用命令

```shell
# 截图
adb -s 127.0.0.1:10000 exec-out screencap -p > screen.png

# 点击坐标 (x, y)
adb -s 127.0.0.1:10000 shell input tap 500 800

# 滑动
adb -s 127.0.0.1:10000 shell input swipe 500 1000 500 500 300

# 按键事件
adb -s 127.0.0.1:10000 shell input keyevent KEYCODE_HOME
adb -s 127.0.0.1:10000 shell input keyevent KEYCODE_BACK
adb -s 127.0.0.1:10000 shell input keyevent KEYCODE_APP_SWITCH

# 获取设备信息
adb -s 127.0.0.1:10000 shell wm size
adb -s 127.0.0.1:10000 shell getprop ro.product.model
```
