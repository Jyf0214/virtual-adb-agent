# uiautomator2

[uiautomator2](https://github.com/openatx/uiautomator2) 是 Python 端的 Android 自动化库。

## 连接配置

```python
import uiautomator2 as u2

d = u2.connect("127.0.0.1:10000")
```

## 已知限制

| 操作 | 支持情况 |
|------|----------|
| `screencap` | 兼容 |
| 点击、滑动 | 兼容 |
| `app_install` | 不支持 |
| `app_push` / `app_pull` | 不支持 |

文件传输操作需要通过其他方式（如 `scp`）完成。
