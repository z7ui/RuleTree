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
| `time_between` | `start` / `end`（"HH:mm"，允许跨零点） | 当前时间在区间内 |

`negate` 是所有条件都有的开关，等价于「不满足」。比造一堆反向类型省事。
组上也能加 `negate`，意思是「这一组整体不成立」。

`children` 的元素同样可以是叶子或组，所以条件也是一棵树，深度不受限。
求值规则：

- `all`：从左到右算，遇到第一个 false 立刻返回 false
- `any`：从左到右算，遇到第一个 true 立刻返回 true
- 空组按成立处理（一条规则只写了动作没写条件，本来就该跑）

界面上目前只展开一层组里的内容，再深的组用「点进去」的方式逐层编辑，
但存储和求值都是递归的，手写 JSON 放到多少层都能正确执行。

### Action（动作）

| action | params | 说明 |
| :--- | :--- | :--- |
| `ringer_mode` | `mode`: `silent` / `vibrate` / `normal` | 需要勿扰访问权限 |
| `media_volume` | `value`（0-100） | 设置媒体音量 |
| `open_app` | `pkg` | 打开应用 |
| `notify` | `title`、`text` | 发一条通知 |
| `delay` | `ms`（毫秒，上限 30000） | 等一会儿再继续。引擎在后台线程跑，不会卡界面 |
| `confirm` | `text`、`sec`（默认 15） | 发通知等用户点「执行」。超时或点「算了」会取消这一支剩下的动作 |

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
