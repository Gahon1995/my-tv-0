# 远端配置中心接入说明（开发者向）

配套服务端：[my-tv-server](https://github.com/Gahon1995/my-tv-server)（接口协议见其 `docs/TECH_DESIGN.md`）。
本文说明客户端侧的实现，便于后续维护。

## 数据流

```
App 启动 / 设置服务器地址 / 恢复默认
        │
        ▼
MainViewModel.updateConfig()
        │
        ├─► RemoteConfigManager.fetchAndApply()
        │       GET {server}/api/v1/config（带 If-None-Match）
        │       200 → 解析 + 写缓存文件 remote_config.json + 存 ETag
        │       304 → 读本地缓存
        │       失败 → 读本地缓存；仍无 → 什么都不做（内置默认兜底）
        │       然后 apply()：按覆盖策略写入 SP / 合并源列表
        │
        └─► 原有逻辑：configAutoLoad 时 importFromUrl(SP.configUrl) + updateEPG()
```

服务器地址为空时 `fetchAndApply()` 直接返回，一切行为与未接入前完全一致。

## 覆盖策略（远端只是"默认值层"）

取值优先级：**用户手动设置 > 远端配置 > 内置默认**。

| SP 标记 | 置位时机（仅用户主动操作） | 效果 |
| --- | --- | --- |
| `userOverrideConfig` | 网页导入源（`/api/import-uri`、`/api/import-text`）、源列表中手动切换源 | 远端不再改写 `SP.configUrl` |
| `userOverrideEpg` | 网页设置 EPG（`/api/epg`） | 远端不再改写 `SP.epg` |

注意：`importFromUrl` 成功后会写 `SP.configUrl`，但**不置位**覆盖标记——
远端配置触发的自动导入也走这条路径，置位必须只发生在用户主动入口。

设置中的「恢复默认」（`SettingFragment` clear 按钮）调用 `SP.clearUserOverrides()`
清除标记并清空 ETag，随后 `updateConfig()` 重新拉取远端配置作为新的默认值。
**服务器地址本身不随恢复默认清除。**

## 各消费点

| 配置 | 位置 | 行为 |
| --- | --- | --- |
| 直播源 | `RemoteConfigManager.apply` → `Sources.mergeRemoteSources` | 远端源仅追加进列表（不动当前选中）；未被覆盖时首个远端源作为 `SP.configUrl` |
| EPG | 同上 | 未被覆盖时写 `SP.epg` |
| 台标 | `SP.logoBaseUrl` → `MainViewModel.preloadLogo` | 远端地址优先拼 `{name}.png`，内置 fanmingming 地址兜底；无本地设置入口 |
| 版本更新 | `UpdateManager.getRelease` | 远端 `update` 字段优先（含 changelog，展示在更新弹窗）；否则回退 GitHub `version.json` |

## 相关文件

- `RemoteConfigManager.kt` — 拉取/缓存/应用（新增）
- `data/RemoteConfig.kt` — 聚合配置数据类，字段与服务端 JSON 一致（新增）
- `SP.kt` — `remoteConfigServer` / `remoteConfigEtag` / `logoBaseUrl` / `userOverride*`
- `SimpleServer.kt` — `/api/remote-server` 端点（网页设置服务器地址）+ 覆盖标记置位
- `res/raw/index.html` — 远程配置网页新增「遠程配置中心地址」输入项
- `layout/setting.xml` + `SettingFragment.kt` — 设置界面显示服务器地址、恢复默认逻辑
