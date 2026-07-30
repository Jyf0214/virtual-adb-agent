# 命令参考

所有命令以 `adb -s <连接地址>` 前缀发起。

## 截图 `screencap`

```shell
# 使用 exec-out（推荐，返回原始数据）
adb -s 127.0.0.1:10000 exec-out screencap -p > screen.png

# 通过 shell 重定向（较慢）
adb -s 127.0.0.1:10000 shell screencap -p /sdcard/screen.png
adb -s 127.0.0.1:10000 shell cat /sdcard/screen.png
```

内部通过 **MediaProjection + ImageReader** 获取帧缓冲区并编码为 JPEG/PNG。

支持智能缩放：当设备分辨率超过 1080p 时自动按比例缩小，平衡速度与质量。

## 手势注入

### 点击

```shell
adb shell input tap <x> <y>
```

坐标以屏幕物理像素为单位。支持 1px–(宽/高-1px) 的边界钳位，防止越界。

### 滑动

```shell
adb shell input swipe <x1> <y1> <x2> <y2> [duration_ms]
```

- `duration_ms`：滑动持续时间，单位毫秒，默认 300ms
- 支持负坐标输入（自动钳位到合法范围），兼容 MAA 等自动化框架

### 按键

```shell
adb shell input keyevent <KEYCODE>
```

支持但不限于：

| 按键 | 效果 |
|------|------|
| `KEYCODE_HOME` | 返回桌面 |
| `KEYCODE_BACK` | 返回 |
| `KEYCODE_APP_SWITCH` | 最近任务 |
| `KEYCODE_VOLUME_UP` | 音量+ |
| `KEYCODE_VOLUME_DOWN` | 音量- |
| `KEYCODE_POWER` | 电源键（部分设备可能不响应） |
| `KEYCODE_MENU` | 菜单 |
| `KEYCODE_ENTER` | 确认/回车 |
| `KEYCODE_DEL` | 退格 |
| `KEYCODE_SPACE` | 空格 |

所有按键事件通过 **AccessibilityService** 的 `performGlobalAction` 或 `injectEvent` 实现。

## 设备信息

```shell
# 屏幕分辨率
adb shell wm size

# 屏幕密度
adb shell wm density

# 设备型号
adb shell getprop ro.product.model

# Android 版本
adb shell getprop ro.build.version.release

# SDK 版本
adb shell getprop ro.build.version.sdk

# 网络信息
adb shell ip addr show wlan0

# 电池状态
adb shell dumpsys battery
```

## 其他支持的命令

```shell
# 获取已安装应用列表
adb shell pm list packages

# 启动 Activity
adb shell am start -n com.package.name/.MainActivity

# 系统日志
adb shell logcat -d -t 100

# 剪贴板内容（部分 Android 版本受限）
adb shell content read --uri content://clipboard
```

## 不支持的命令

以下命令因无障碍服务限制而无法支持：

| 命令 | 原因 |
|------|------|
| `adb install` | 需要系统级文件传输通道 |
| `adb push/pull` | 需要系统级文件传输通道 |
| `adb shell input text` | 无障碍服务的文本注入仅支持部分输入法 |
| `adb backup` | 需要 USB 调试 |

需要文件传输时建议配合 `adb over TCP/IP`（System ADB）或 `scp` 等工具。
