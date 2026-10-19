# 数据模型与存储格式（v1）

这是整个应用的地基。格式一旦发布就要长期兼容，所以先把它定死，再写界面和引擎。

## 一、总原则

1. **树是唯一的执行单位**。每条规则 = 一个触发器 + 一棵动作树。别的地方都可以简化，树的递归结构不能砍。
2. **节点用 `type` 字段区分**，参数放在 `params` 里。加新类型的节点不破坏旧格式。
3. **存储文件带 `version` 字段**。将来升级格式，先读 version 再决定迁移路径，缺省按 1 处理。
4. **编辑器可以落后于模型**。界面上第一版只暴露「一个条件 + 两组动作」的 if/else，但存储格式从第一天起就是任意嵌套的树。这样以后做嵌套分支只改 UI，不动存储。

## 二、存储格式

一个 JSON 文件，`rules.json`，放在应用私有目录。写入用「写临时文件 + 原子改名」，防止写一半断电丢全部规则。

```json
{
  "version": 1,
  "rules": [ Rule, Rule, ... ]
}
```

### Rule

```json
{
  "id": "b1f7...（UUID）",
  "name": "回家静音",
  "enabled": true,
  "createdAt": 1792400000000,
  "updatedAt": 1792400000000,
  "trigger": Trigger,
  "tree": Node
}
```

### Trigger

```json
{ "type": "wifi_state", "params": { "watch": "connected" } }
```

v1 支持的触发器：

| type | params | 说明 |
| :--- | :--- | :--- |
| `wifi_state` | `watch`: `connected` / `disconnected` / `any` | Wi-Fi 连上或断开时触发 |
| `power_state` | `watch`: `connected` / `disconnected` / `any` | 插上或拔掉电源时触发 |
| `notification` | `pkg`（空为任意）、`label`（显示用）、`keyword`（空为不限） | 收到通知时触发，需要「通知使用权」 |
| `bluetooth_state` | `watch`、`mac`（空为任意设备）、`label`（显示用） | 蓝牙设备连上或断开时触发。Android 12 起读设备名需要 `BLUETOOTH_CONNECT` |
| `time_daily` | `hhmm`（"HH:mm"）、`days`（"1,3,5"，周一=1；空串=每天） | 每天固定时间触发，走 `AlarmManager`。没拿到精确闹钟权限时退化成 10 分钟窗口 |
| `manual` | 无 | 只能手动运行，用来测试 |

触发器只负责「什么时候跑」，不做判断。判断是树里的事。这个分离是故意的：
同一个触发器可以接不同条件的规则，逻辑不重复。

### Node（动作树节点）

三种节点，靠 `type` 区分：

```json
// 动作节点：真正干活的叶子
{ "type": "action", "action": "ringer_mode", "params": { "mode": "silent" } }

// if 节点：条件 + 两个分支
{
  "type": "if",
  "condition": Condition,
  "then":  [ Node, Node, ... ],
  "else":  [ Node, Node, ... ]
}

// seq 节点：按顺序执行子节点
{ "type": "seq", "children": [ Node, Node, ... ] }
```

`then` / `else` / `children` 是**节点数组**，元素可以是任意类型的节点，包括再嵌一层 `if`。
树的深度没有人为限制。

顶层 `tree` 直接就是一个 Node。最常见的形态是单个 `if`。

### Condition

条件有两种形态：叶子，和把叶子连起来的组。

```json
// 叶子
{ "type": "wifi_connected", "params": { "ssid": "Home-5G", "matchAny": false }, "negate": false }

// 组：all = 并且，any = 或者
{
  "type": "group",
  "op": "all",
  "negate": false,
  "children": [ Condition, Condition, ... ]
}
```

v1 支持的叶子条件：

| type | params | 说明 |
| :--- | :--- | :--- |
| `wifi_connected` | `ssid`（可选）、`matchAny`（bool） | 连着指定 SSID 或任意 Wi-Fi。读 SSID 需要定位权限，没给就只能判断 matchAny |
| `charging` | 无 | 正在充电 |
| `battery_below` | `pct`（int，0-100） | 电量低于阈值 |
| `battery_above` | `pct`（int，0-100） | 电量高于阈值 |
| `time_between` | `start` / `end`（"HH:mm"，允许跨零点） | 当前时间在区间内 |
| `day_of_week` | `days`（"1,3,5"，周一=1；空串=每天） | 今天是这几天的任意一天 |
| `screen_on` | 无 | 屏幕亮着且已唤醒 |
| `keyguard_locked` | 无 | 当前处于锁屏 |
| `dnd_on` | 无 | 免打扰不在「全部允许」状态 |
| `ringer_silent` / `ringer_vibrate` / `ringer_normal` | 无 | 判断当前铃声模式 |
| `dark_mode` | 无 | 系统处于深色模式 |
| `bt_connected` | 无 | 至少连着一个蓝牙设备（需要 BLUETOOTH_CONNECT） |
| `headset` | 无 | 插着有线耳机 |
| `app_foreground` | `pkg`、`label`（显示用） | 当前前台应用是它。优先用无障碍的事件流记下的包名，没开无障碍时退回使用情况统计 |

`negate` 是所有条件都有的开关，等价于「不满足」。比造一堆反向类型省事。
组上也能加 `negate`，意思是「这一组整体不成立」。

`children` 的元素同样可以是叶子或组，所以条件也是一棵树，深度不受限。
求值规则：

- `all`：从左到右算，遇到第一个 false 立刻返回 false
- `any`：从左到右算，遇到第一个 true 立刻返回 true
- 空组按成立处理（一条规则只写了动作没写条件，本来就该跑）

界面上目前只展开一层组里的内容，再深的组用「点进去」的方式逐层编辑，
但存储和求值都是递归的，手写 JSON 放到多少层都能正确执行。

`children` 的**顺序就是求值顺序**。短路规则下顺序有意义：把最容易判否的条件放前面，
能少算几次。界面上长按行首的把手可以拖动调顺序，改完直接写回这个数组。

### Action（动作）

| action | params | 说明 |
| :--- | :--- | :--- |
| `ringer_mode` | `mode`: `silent` / `vibrate` / `normal` | 需要勿扰访问权限 |
| `media_volume` | `value`（0-100） | 设置媒体音量 |
| `volume_ring` / `volume_alarm` / `volume_notification` | `value`（0-100） | 分别设铃声音量、闹钟音量、通知音量 |
| `dnd_on` / `dnd_off` | 无 | 免打扰开关，需要勿扰访问权限 |
| `vibrate` | `ms`（毫秒） | 振一下 |
| `media_play_pause` / `media_next` / `media_prev` | 无 | 派发媒体键 |
| `torch_on` / `torch_off` | 无 | 手电筒，不需要 CAMERA 权限 |
| `open_url` | `url` | 用浏览器打开网址 |
| `copy_text` | `text` | 写入剪贴板 |
| `tts` | `text` | 用系统 TTS 朗读，机器上没装引擎就跳过 |
| `http_request` | `url`、`body`（空=GET，非空=POST） | 超时 10 秒，返回码写进日志 |
| `open_app` | `pkg` | 打开应用 |
| `notify` | `title`、`text` | 发一条通知 |
| `g_back` / `g_home` / `g_recents` / `g_notifications` / `g_quick_settings` / `g_lock` | 无 | 无障碍全局动作：返回、主屏、最近任务、通知栏、快捷设置、锁屏 |
| `g_tap` | `x`、`y` | 无障碍合成点击 |
| `g_swipe` | `x1`、`y1`、`x2`、`y2` | 无障碍合成滑动，固定 300ms |
| `screenshot` | 无 | 无障碍截屏，存到 `Pictures/RuleTree/`，Android 11 以上 |
| `gesture_seq` | `steps`（数组） | 录制下来的手势序列。每步 `{type:tap,x,y}` 或 `{type:swipe,x1,y1,x2,y2,ms}`，都带 `delay` |
| `sys_airplane` / `sys_mobile_data` / `sys_wifi` / `sys_bluetooth` / `sys_nfc` / `sys_location` | `mode`: `on` / `off` | 系统开关，走 root 的 `svc` / `cmd`；定位和深色模式在有 `WRITE_SECURE_SETTINGS` 时直接写设置 |
| `sys_hotspot` | `mode`、`ssid`、`pass` | 个人热点，开的时候要名字和密码 |
| `sys_brightness` / `sys_timeout` / `sys_auto_rotate` / `sys_font_scale` / `sys_density` / `sys_resolution` | `value` / `sec` / `mode` / `w`+`h` | 显示相关，优先直接写设置，失败退回 root |
| `sys_stay_awake` / `sys_screen_off` | `mode` / 无 | 充电时不息屏；立刻熄屏 |
| `force_stop` | `pkg` | `am force-stop`，要 root |
| `shell` | `cmd` | 任意 shell 命令，root 跑，超时 15 秒 |
| `delay` | `ms`（毫秒，上限 30000） | 等一会儿再继续。引擎在后台线程跑，不会卡界面 |
| `confirm` | `text`、`sec`（默认 15） | 发通知等用户点「执行」。超时或点「算了」会取消这一支剩下的动作 |
| `wait_unlock` | `sec`（默认 60） | 锁屏时先停住，解锁后继续；超时也往下走 |
| `stop` | 无 | 主动结束这一支，后面的动作都不执行 |

## 三、完整示例

「连上家里 Wi-Fi 就静音，否则恢复正常」：

```json
{
  "version": 1,
  "rules": [
    {
      "id": "demo-0001",
      "name": "回家静音",
      "enabled": true,
      "createdAt": 1792400000000,
      "updatedAt": 1792400000000,
      "trigger": { "type": "wifi_state", "params": { "watch": "any" } },
      "tree": {
        "type": "if",
        "condition": {
          "type": "wifi_connected",
          "params": { "ssid": "Home-5G", "matchAny": false },
          "negate": false
        },
        "then": [
          { "type": "action", "action": "ringer_mode", "params": { "mode": "silent" } }
        ],
        "else": [
          { "type": "action", "action": "ringer_mode", "params": { "mode": "normal" } }
        ]
      }
    }
  ]
}
```

Wi-Fi 状态一变就触发：连着 Home-5G 走 then 静音；连到别的网络或断了走 else 恢复。

## 四、兼容性策略

- 新增 `type` / `action`：直接加。读到不认识的类型，引擎按「跳过」处理并记日志，不报错。
- 字段只加不改不删。参数变更时保留旧字段、新字段用默认值补。
- `version` 从 1 开始。写迁移函数 `migrate(json) -> json`，读时执行，写时永远写当前版本号。
- 导入导出直接用这份 JSON，格式公开，别的工具也能读写。
