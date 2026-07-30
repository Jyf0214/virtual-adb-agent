# 架构说明

Virtual ADB Agent 通过模拟 ADB 协议的 TCP 传输层，让标准 ADB 命令行工具可以将它视为一台远程设备。

## ADB 协议桥接

```
┌──────────────┐     TCP :10000     ┌──────────────────┐
│  adb client   │ ────────────────▶ │ Virtual ADB Agent │
│  (PC/Server)  │ ◀──────────────── │  (Android 设备)   │
└──────────────┘                   └──────────────────┘
       │                                    │
       │                                    ├──▶ 手势注入
       │                                    │     └─ AccessibilityService
       │                                    │
       │                                    ├──▶ 屏幕截图
       │                                    │     └─ MediaProjection → ImageReader
       │                                    │
       │                                    ├──▶ 设备信息
       │                                    │     └─ shell 命令直通
       │                                    │
       │                                    └──▶ TCP 日志通信
       │                                          └─ AppLogger
```

### 协议握手流程

ADB 协议使用四层消息：`CNXN` → `OPEN` → `WRTE` / `OKAY` → `CLSE`。

| 步骤 | 消息 | 说明 |
|------|------|------|
| 1 | `CNXN` | 客户端发起连接，协商版本和最大负载 |
| 2 | `OPEN` | 客户端打开流，传入 `shell:xxx` 或 `exec:xxx` |
| 3 | `WRTE` / `OKAY` | 数据传输，命令输出分块返回 |
| 4 | `CLSE` | 流关闭 |

### 同步与异步

命令处理分两种路径：

- **同步路径**（sync 服务）：`sync:` 流用于文件传输协商，直接在读取循环中处理
- **异步路径**（shell/exec 命令）：在独立协程中执行，不阻塞消息循环

异步设计避免了耗时命令（如截图、手势延时）导致连接超时断开。

## 截图管线

```
MediaProjection
     │
     ▼
 VirtualDisplay ──▶ ImageReader ──▶ Bitmap ──▶ JPEG/PNG 编码
     │                                            │
     │                                            ▼
     │                                        TCP 响应
     │
 DisplayManager（监听旋转事件，重建 VirtualDisplay）
```

- 使用 `RGBA_8888` 像素格式，保留最高质量
- 支持屏幕旋转自适应（重建 VirtualDisplay）
- 高于 1080p 时自动按比例缩放

## 手势注入管线

```
input tap/swipe/keyevent 命令
         │
         ▼
   命令路由 → 参数解析 → AccessibilityService
         │
         ├── tap     → GestureDescription (Path API)
         ├── swipe   → GestureDescription (Path API)
         └── keyevent → performGlobalAction / injectEvent
```

**技术限制：**

- Path API 要求坐标在 `[1, 屏幕尺寸-1]` 范围内（详见下方坐标钳位说明）
- 无障碍服务的 `GestureDescription` 不支持多点触控
- `injectEvent` 在部分 OEM 系统上可能被限制

## 安全设计

- **IP 过滤**：仅允许 A/B/C 类私网地址和本地回环地址连接
- **来源检查**：在 TCP accept 阶段拒绝公网来源，不进入 ADB 协议握手
- **无持久状态**：应用不存储连接历史或个人配置到云端
