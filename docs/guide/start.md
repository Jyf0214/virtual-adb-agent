# 快速开始

## 安装

1. 从 [Releases](https://github.com/Jyf0214/virtual-adb-agent/releases) 下载最新 APK
2. 在 Android 设备上安装并打开

> 如果系统提示"未知来源安装"，请在设置中允许本次安装。

## 权限配置

应用需要 **两项系统权限** 才能正常工作。

### 1. 开启无障碍服务

```
设置 → 无障碍（辅助功能） → Virtual ADB Agent → 开启服务开关
```

无障碍服务用于手势注入（点击/滑动/按键）。开启时需要系统弹窗确认。

### 2. 授权屏幕截取

在应用主页点击 **「授权并启动」**，然后在系统弹窗中点击 **「立即开始」**。

屏幕截取用于 `screencap` 命令。如果你只需要手势控制不需要截图，也可以不授权。

### 权限检查

两项权限都开启后，应用主页顶部状态栏应显示：
- 无障碍服务已启用
- 屏幕截取服务已运行

## 连接设备

### 本地连接（默认）

应用启动后 TCP 服务自动监听 `127.0.0.1:10000`。

```shell
adb connect 127.0.0.1:10000
```

### 局域网连接

在应用内开启 **「局域网模式」**，然后使用设备局域网 IP 连接：

```shell
adb connect <设备IP>:10000
```

> 局域网模式下 ADB 连接**不加密**，建议仅在信任的网络中使用。详见[安全与 IP 过滤](/usage/configuration#安全与-ip-过滤)。

## 常用命令

```shell
# 截图
adb -s 127.0.0.1:10000 exec-out screencap -p > screen.png

# 点击 (x y)
adb -s 127.0.0.1:10000 shell input tap 500 800

# 滑动 (x1 y1 x2 y2 [时长ms])
adb -s 127.0.0.1:10000 shell input swipe 500 1000 500 500 300

# 按键事件
adb -s 127.0.0.1:10000 shell input keyevent KEYCODE_HOME
adb -s 127.0.0.1:10000 shell input keyevent KEYCODE_BACK
adb -s 127.0.0.1:10000 shell input keyevent KEYCODE_APP_SWITCH

# 获取设备信息
adb -s 127.0.0.1:10000 shell wm size
adb -s 127.0.0.1:10000 shell getprop ro.product.model
```

完整命令列表请参考[命令参考](/usage/commands)。

## 断开连接

```shell
adb disconnect 127.0.0.1:10000
```

或在应用主页点击 **「停止服务」**。

## 卸载

卸载前建议先关闭服务：

1. 在应用主页点击 **「停止服务」**
2. 进入 **设置 → 无障碍**，关闭 Virtual ADB Agent 服务开关
3. 正常卸载应用即可
