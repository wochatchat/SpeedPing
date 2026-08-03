# SpeedPing

一个安卓桌面 **悬浮窗小工具**：在所有应用之上持续显示 **实时网速** + **网络可达性图标**。

- 直连环境 → 显示百度小图标
- 挂梯子环境 → 显示 Google 小图标
- 每 **30s** 自适应 ping（能连国内站点就 ping 百度，否则 ping Google），ping 不通则悬浮窗 **闪动变色** 提醒
- 启动页只做一件事：引导用户授予 **保活所需权限**（悬浮窗 / 电池白名单 / 通知 / 自启动）

> 设计与实现说明见下文。

## 功能一览

| 能力 | 行为 |
|------|------|
| 实时网速 | 每 1s 采样 `TrafficStats.getTotalRxBytes/TxBytes`，显示 `K/s` `M/s` |
| 可达性探测 | 每 30s 做 HTTP 请求；VPN 环境优先 Google，否则优先百度，第一个失败回退另一个 |
| 图标切换 | 直连成功 → 百度图标；梯子成功 → Google 图标；全部失败 → 警告图标 |
| 失败提醒 | 探测失败后悬浮窗背景与数字按 600ms 红色闪动，恢复后自动停止 |
| 保活 | 前台服务 + 电池白名单 + 通知渠道 + 厂商自启动跳转 |
| 拖动 | 长按悬浮窗可拖动；双击跳回引导页 |

## 保活权限引导（App UI）

启动后只显示一个引导页，按顺序引导：

1. **显示在其它应用上层** (`SYSTEM_ALERT_WINDOW`) — 悬浮窗必需
2. **关闭电池优化** — 前台服务连续运行必备
3. **允许通知** (Android 13+ `POST_NOTIFICATIONS`) — 前台服务通知必需
4. **允许自启动** — 小米/华为/OPPO/vivo 等厂商定制必需

全部确认后再点「启动悬浮窗」。

## 构建与签名

- **内置固定签名 keystore**（PKCS12，自签 RSA 2048，100 年有效期）放在 `keystore/`，随仓库提交
- GitHub Actions workflow 自动构建 **release APK** 并签名，归档到仓库的 `release/` 目录
- 构建产物 commit 回 `main` 的 `release/` 目录，版本号取 `run_id`

见 [`.github/workflows/build-release.yml`](.github/workflows/build-release.yml)。

## 目录

```
app/
  src/main/
    AndroidManifest.xml
    java/com/wochatchat/speedping/
      SpeedPingApp.kt
      NetUtil.kt              # 可达性探测
      PingResult.kt
      service/
        OverlayService.kt     # 悬浮窗 + 保活前台服务
        Traffic.kt            # 全局上下行字节统计包装
      ui/
        GuideActivity.kt      # 权限引导页
      receiver/
        BootReceiver.kt       # 开机自启
    res/                       # 布局/图标(SVG 矢量)/主题
keystore/                      # 固定签名材料
.github/workflows/             # 自动构建并签名 APK，归档到仓库
```
