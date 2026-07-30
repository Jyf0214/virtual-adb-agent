# 自动化集成

Virtual ADB Agent 兼容标准 ADB 协议，可以接入大多数使用 ADB 的自动化框架。

以下为各框架的详细集成指南：

- [MAA（MaaAssistantArknights）](/usage/automation/maa)
- [Airtest](/usage/automation/airtest)
- [Appium](/usage/automation/appium)
- [uiautomator2](/usage/automation/uiautomator2)

## 多客户端连接

服务端支持多个 ADB 客户端同时连接。每个客户端独立处理请求，互不干扰。

```shell
# 终端 1
adb -s 127.0.0.1:10000 shell input tap 500 800

# 终端 2（同时）
adb -s 127.0.0.1:10000 shell wm size
```

两个命令可以并发执行，不会相互阻塞（得益于异步协程架构）。
