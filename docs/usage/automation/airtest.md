# Airtest

[Airtest](https://airtest.netease.com/) 是网易出品的跨平台自动化测试框架。

## 连接配置

使用 `connect_device` API 连接：

```python
from airtest.core.api import connect_device

connect_device("Android:///127.0.0.1:10000")
```

支持全屏截图、点击、滑动等操作。
