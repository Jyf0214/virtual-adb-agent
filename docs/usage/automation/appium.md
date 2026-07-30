# Appium

[Appium](https://appium.io/) 是跨平台移动端自动化测试工具。

## 连接配置

在 Desired Capabilities 中设置：

```json
{
  "platformName": "Android",
  "appium:adbExecTimeout": 60000,
  "appium:adbPort": 10000,
  "appium:udid": "127.0.0.1:10000"
}
```

建议将 `adbExecTimeout` 设置为较大值，避免截图耗时导致超时。
