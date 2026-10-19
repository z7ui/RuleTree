# 能力对照：三星「日常程序」 vs 小米自动任务 vs 规则树

复核时间 2026-10-19，从连接的三星 S25 Ultra 上重新拉了这两个包：

| 包 | 版本 | 来源 |
| :--- | :--- | :--- |
| `com.samsung.android.app.routines` | 5.0.03.22 | `/system/priv-app/Routines/Routines.apk`（79 MB） |
| `com.samsung.android.app.routineplus` | 1.1.28 | `/data/app/.../base.apk`（18 MB） |

和上一轮反编译的版本一致，所以上次的 `zh_strings.tsv`（2393 条中文资源）、`preload.txt`
（241 个内置能力类）仍然有效。这次补的是完整类表（525907 行包树），
确认「241 个类 / 34 个能力域」这个数字没有漏。

这份表只回答一个问题：**哪些能做，哪些做不了，做不了是缺什么。**

---

## 一、三星的 34 个能力域（按类路径实测）

```
advanced     二次确认 / 自定义弹窗 / 延迟
ai           图片处理 / 文本处理
appfunctions 日历 / 时钟（三星自家）
apps         应用动作 / 应用条件
bixby        语音
common       应用 / 蓝牙 / 联系人 / 关键词 / 通知 / 设置变化
communication 电话 / 短信 / 快速拒接
connecteddevice Android Auto
context      场景引擎 / 模型
controlflow  条件分支
date         日期时间运算
device       电池 / 折叠屏 / 有线耳机
display      亮度 / 外屏亮度 / 深色模式 / 字体大小 / 流畅度与分辨率 / 布局缩放 / 屏幕方向 / 超时 / 缩放
function     剪贴板 / 导航栏按钮 / 打开网址 / 单次振动
image        取图 / 显示图片
location     地理围栏
lockscreen   锁屏快捷方式 / 解锁
media        播放媒体 / Spotify
modeandroutine 模式
navigation   导航
network      飞行模式 / 蓝牙 / 热点 / 移动数据 / 音乐共享 / 网络模式 / NFC / Wi-Fi
notification 自定义通知 / 免打扰 / 收到通知 / 朗读通知
samsunghealth 健康
smartthings  智能家居
sound        分应用音量 / 声音模式与音量
time         睡眠时间表 / 特定时间 / 时间段
tts          朗读
weather      天气
```

---

## 二、能不能做：分三类

### A 类：公开 API 就能做（不需要 root）

| 能力 | 对应实现 | 状态 |
| :--- | :--- | :--- |
| 条件分支 | 自己的规则树 | ✅ 0.1 |
| 延迟 | `Thread.sleep`，上限 30 秒 | ✅ 0.2 |
| 二次确认 | 通知 + 倒计时 | ✅ 0.3 |
| 收到通知 | `NotificationListenerService` | ✅ 0.4 |
| 定时 / 时间段 | `AlarmManager` | ✅ 0.6 |
| 铃声模式、各路音量 | `AudioManager.setStreamVolume` | ✅ 0.7（补了铃声/闹钟/通知三路） |
| 免打扰 | `setInterruptionFilter` | ✅ 0.7 |
| 媒体播放 / 上一首 / 下一首 | `dispatchMediaKeyEvent` | ✅ 0.7 |
| 复制到剪贴板 | `ClipboardManager` | ✅ 0.7 |
| 打开网址 | `ACTION_VIEW` | ✅ 0.7 |
| 朗读 | `TextToSpeech` | ✅ 0.7 |
| 单次振动 | `Vibrator` | ✅ 0.7 |
| 手电筒 | `CameraManager.setTorchMode` | ✅ 0.7 |
| 返回 / 主屏 / 最近任务 / 锁屏 / 点击 / 滑动 | 无障碍 `performGlobalAction` + `dispatchGesture` | ✅ 0.7 |
| 等解锁 | `KeyguardManager` 轮询 | ✅ 0.7 |
| 发 HTTP 请求 | `HttpURLConnection` | ✅ 0.7 |
| 电量、充电、耳机、屏幕、锁屏、免打扰、铃声、深色模式、蓝牙、星期 | 各类系统查询 | ✅ 0.7（条件补到 16 种） |
| Wi-Fi 连上/断开、蓝牙连上/断开、电源 | 前台服务 + 广播 | ✅ 0.1–0.6 |

### B 类：要 root 或 WRITE_SECURE_SETTINGS

这些是「改系统开关」，Android 从 6.0 起就不给普通应用改了：

| 能力 | 为什么做不了 | 有 root 怎么做 |
| :--- | :--- | :--- |
| 飞行模式、移动数据、热点、NFC | 系统签名权限 | `svc data disable` / `cmd connectivity` / `cmd nfc` |
| Wi-Fi、蓝牙开关 | Android 10/13 起禁止应用直接开关 | `svc wifi disable` / `cmd bluetooth_manager disable` |
| 亮度、屏幕超时、自动旋转、字体大小、分辨率、缩放 | `WRITE_SECURE_SETTINGS` | `settings put system screen_brightness 128` |
| 深色模式（写） | 同上 | `cmd uimode night yes` |
| 分应用音量 | 没有公开 API | 改 audio policy，机型相关 |
| 强制停止 / 挂起应用 | `forceStopPackage` 是隐藏 API | `am force-stop <pkg>` |
| 锁定屏幕快捷方式 | 快捷方式属于启动器通道 | 已验过，`LauncherApps` 对第三方封闭 |
| 模式（mode） | 三星自家模式服务 | 无对应物，只能用铃声/勿扰近似 |

### C 类：三星生态专属，搬不过来

Bixby、SmartThings、Samsung Health、Spotify 控制、Android Auto、三星日历/时钟、
AI 图片与文本、天气、场景引擎。这些不是权限问题，是对方没有开放接口，
小米也没有对应服务。硬做只能变成「打开某个 App」，价值不大。

---

## 三、数量对账

| | 三星 | 小米自动任务 | 规则树 0.7 |
| :--- | :--- | :--- | :--- |
| 能力域 | 34 | 约 10（条件 26 / 动作 28） | 8（触发器 6 / 条件 16 / 动作 31） |
| 动作 | 200+（含生态） | 28 | 31 |
| 条件 | 100+ | 26 | 16 |
| 分支 | if/else 任意嵌套 | 无 | if/else 任意嵌套 |
| else 内容 | 任意动作 | 只能恢复默认 | 任意动作 |

把三星的 200 多个动作拆开看，A 类占不到三成，B 类约三成，剩下四成是生态。
**所以「全都能实现」是不成立的**，能落地的上限在 60–70 个动作左右，
其中要开 root 才能摸到 40 个以后。

规则树 0.7 的动作数已经超过小米自动任务（31 vs 28），条件数还差一半，
主要差在「应用在前台」「地理围栏」「健康数据」这几类。

---

## 四、下一步的边界

不引入 root 的话，还能补的只有这些：

- 应用在前台（要「使用情况访问」）
- 剪贴板内容变化触发（Android 10 起读剪贴板受限，只能读自己写进去的）
- 屏幕截图（无障碍 `takeScreenshot`，Android 11+）
- 触控宏录制（在无障碍手势之上加一层录制界面）
- 地理围栏（要后台定位，耗电，谨慎）

引入 root 之后，B 类那 20 多项系统开关可以一次补齐，
但那就不是「一个普通应用」了，装之前得先 root。

源数据：`apk-re/raw/preload_all.txt`（241 个类）、`apk-re/zh_strings.tsv`（2393 条中文资源）、
`apk-re/miui/autotask_zh.tsv`（小米 186 条）。
