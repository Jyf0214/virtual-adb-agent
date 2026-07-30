# 自动化集成

Virtual ADB Agent 兼容标准 ADB 协议，可以接入大多数使用 ADB 的自动化框架。

## MAA（MaaAssistantArknights）

[MAA](https://github.com/MaaAssistantArknights/MaaAssistantArknights) 是一款流行的明日方舟自动化助手，通过 ADB 控制设备。

### 连接配置

1. 启动 Virtual ADB Agent 并开启局域网模式（如模拟器场景则保持关闭）
2. 在 MAA 的连接设置中：
   - **ADB 路径**：选择系统 ADB 或自定义 ADB
   - **连接地址**：`127.0.0.1:10000`（本机）或 `<设备IP>:10000`（局域网）
   - **ADB 序列号**：`127.0.0.1:10000`

### 注意

- MAA 的滑动参数可能包含**负坐标**（如 `y: -55`），这在标准 ADB 中正常，但旧的实现会引发 `Path bounds must not be negative` 错误。**1.0.x 及以上版本已修复此问题**，坐标会被自动钳位到合法范围。
- 截图延迟取决于设备分辨率。建议在 MAA 的"截图设置"中调低压缩质量以提高帧率。

## 其他框架

| 框架 | 兼容性 | 备注 |
|------|--------|------|
| **Airtest** | 兼容 | 使用 `connect_device("Android:///127.0.0.1:10000")` |
| **Appium** | 兼容 | 需在 Desired Capabilities 中设置 `adbExecTimeout` 为较大值 |
| **uiautomator2** | 部分兼容 | `screencap` 和点击可用，但不支持 `app_install` |
| **ATX Server** | 部分兼容 | 截图和点击可用 |

## 多客户端连接

服务端支持多个 ADB 客户端同时连接。每个客户端独立处理请求，互不干扰。

```shell
# 终端 1
adb -s 127.0.0.1:10000 shell input tap 500 800

# 终端 2（同时）
adb -s 127.0.0.1:10000 shell wm size
```

两个命令可以并发执行，不会相互阻塞（得益于异步协程架构）。
