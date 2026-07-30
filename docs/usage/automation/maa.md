# MAA（MaaAssistantArknights）

[MAA](https://github.com/MaaAssistantArknights/MaaAssistantArknights) 是一款流行的明日方舟自动化助手，通过 ADB 控制设备。

## 连接配置

1. 启动 Virtual ADB Agent 并开启局域网模式（如模拟器场景则保持关闭）
2. 在 MAA 的连接设置中：
   - **ADB 路径**：选择系统 ADB 或自定义 ADB
   - **连接地址**：`127.0.0.1:10000`（本机）或 `<设备IP>:10000`（局域网）
   - **ADB 序列号**：`127.0.0.1:10000`

## 注意事项

- MAA 的滑动参数可能包含负坐标（如 `y: -55`），这在标准 ADB 中正常。当前版本已修复此问题，坐标会被自动钳位到合法范围。
- 截图延迟取决于设备分辨率。建议在 MAA 的截图设置中调低压缩质量以提高帧率。
