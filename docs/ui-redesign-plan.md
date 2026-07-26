# my-tv-0 UI 重设计技术方案（液态玻璃版）

> 版本：v1.0 · 2026-07-26
> 已确认决策：方案 A 液态玻璃 / 青蓝焦点色 / 全部页面 / 设置页一步到位重构 / SurfaceView→TextureView / 字号沿用现有+大字模式 / 加焦点动效

---

## 1. 目标与范围

参照 iOS 26 Liquid Glass 风格重做全部 UI：

| 模块 | 现状 | 目标 |
|---|---|---|
| 频道菜单 | 两列实心色块 | 玻璃面板、圆角卡片、焦点发光、列表项加"当前节目"小字行 |
| 信息条 | 左右拼接矩形 | 玻璃圆角卡、频道号大字发光、EPG 进度条、"接下来" |
| 时钟/频道号 | 裸文字 | 玻璃胶囊 |
| 节目单 | 纯文字列表 | 玻璃面板、"可回看/直播中"徽标、频道头 |
| 设置页 | 单列长滚动 ScrollView | 居中大玻璃卡、左导航 4 分组 + 右内容区 |
| 线路切换 | 实心色块列表 | 玻璃面板、当前线路 ✓ |
| 播放器 OSD | 黑底时移条、裸音量条 | 玻璃时移条、音量胶囊 |
| 错误页 | 全屏黑底小字 | 居中玻璃卡 + 倒计时文案 |
| 数字选台 | 裸文字 | 玻璃胶囊 |
| 视频渲染 | SurfaceView | TextureView（供玻璃取景模糊） |

不改动：播放内核逻辑、直播源解析、回看逻辑、字号数值、大字模式机制、遥控按键流程。

---

## 2. 设计规范（Design Tokens）

### 2.1 色板（colors.xml 重建，旧色名保留兼容）

```xml
<!-- 焦点主色：青蓝 -->
<color name="accent">#5AC8FA</color>          <!-- 渐变起 -->
<color name="accent_deep">#0A84FF</color>     <!-- 渐变止 -->
<color name="accent_glow">#735AC8FA</color>   <!-- 发光 45% -->
<color name="focus_border">#E678D2FF</color>  <!-- 焦点描边 90% -->
<color name="focus_bg_start">#595AC8FA</color><!-- 焦点底 35% -->
<color name="focus_bg_end">#470A84FF</color>  <!-- 焦点底 28% -->

<!-- 玻璃面板 -->
<color name="glass_hi">#24FFFFFF</color>      <!-- 渐变高光 14% 白 -->
<color name="glass_mid">#0DFFFFFF</color>     <!-- 5% 白 -->
<color name="glass_lo">#8C141923</color>      <!-- 55% 深底 -->
<color name="glass_border">#38FFFFFF</color>  <!-- 描边 22% 白 -->
<color name="glass_inner_item">#29FFFFFF</color> <!-- 选中组底 16% -->

<!-- 文字 -->
<color name="text_primary">#FFFFFFFF</color>
<color name="text_secondary">#D9FFFFFF</color> <!-- 85% -->
<color name="text_tertiary">#A6FFFFFF</color>  <!-- 65% -->
<color name="text_dim">#73FFFFFF</color>       <!-- 45% -->

<!-- 状态 -->
<color name="badge_live">#30D158</color>       <!-- 直播中 -->
<color name="badge_catchup">#7AD0FF</color>    <!-- 可回看 -->
<color name="heart_on">#FF5F7A</color>
```

旧色名 `focus/blur/title_blur/description_blur/track_* / thumb_*` 指向新值，未改到的代码不崩。

### 2.2 尺寸/圆角/间距

| Token | 值 | 用途 |
|---|---|---|
| radius_panel | 24dp | 大面板 |
| radius_card | 16dp | 列表项/焦点卡 |
| radius_pill | 999dp | 胶囊（时钟/音量/徽标） |
| stroke_glass | 1dp | 面板描边 |
| stroke_focus | 1.5dp | 焦点描边 |
| pad_panel | 16dp | 面板内边距 |
| gap_item | 6dp | 列表项间距 |

### 2.3 动效参数

| 动效 | 参数 |
|---|---|
| 焦点获得 | scaleX/Y 1.0→1.03，120ms，DecelerateInterpolator |
| 焦点失去 | scale 回 1.0，100ms |
| 面板出现 | alpha 0→1 + translationY 16dp→0，180ms |
| 面板消失 | alpha 1→0，120ms |

实现用 `view.animate()`，无第三方库；所有动效受"低配模式"降级开关控制（见 §4.4）。

---

## 3. 资源层改动清单

### 3.1 新增 drawable

| 文件 | 内容 |
|---|---|
| `bg_glass_panel.xml` | 渐变（135°, glass_hi→glass_mid→glass_lo）+ glass_border 描边 + 24dp 圆角 |
| `bg_glass_pill.xml` | 同上，999dp 圆角 |
| `bg_item_focus.xml` | 渐变（focus_bg_start→focus_bg_end）+ focus_border 1.5dp 描边 + 16dp 圆角 |
| `bg_item_normal.xml` | 透明底 16dp 圆角（占位保证尺寸一致） |
| `bg_item_selector.xml` | selector: state_focused→bg_item_focus, 否则 bg_item_normal |
| `bg_group_active.xml` | glass_inner_item 底 14dp 圆角（当前分组非焦点态） |
| `bg_badge_live.xml` / `bg_badge_catchup.xml` | 半透明底+描边胶囊 |
| `bg_progress_accent.xml` | 进度条 layer-list：轨道 20% 白 / 进度 accent 渐变，3dp 圆角 |
| `bg_input_box.xml` | 30% 黑底 + 15% 白描边 10dp 圆角（设置页 URL 框） |
| 图标 | `ic_source.xml`、`ic_display.xml`、`ic_play_setting.xml`、`ic_about.xml`（设置页导航，Material 风格 vector） |

发光效果说明：Android shape 无 box-shadow。焦点"外发光"用两层实现——焦点项自身 `bg_item_focus` + `elevation 8dp`（配 `outlineProvider`，Android 15 阴影可着色 `outlineSpotShadowColor=accent_glow`）。API<28 无彩色阴影则仅描边+渐变（可接受）。

### 3.2 themes.xml

`Theme.MyTV0` 增加 `android:windowBackground=@color/black`；SwitchCompat track/thumb 换 accent 系。

### 3.3 strings.xml（三语言目录同步）

新增：`setting_nav_source / setting_nav_play / setting_nav_display / setting_nav_about / badge_live / badge_catchup / now_playing / up_next / glass_effect`（低配模式开关文案）等。

---

## 4. 玻璃（模糊）技术方案

### 4.1 渲染路径改造：SurfaceView → TextureView

`player.xml` 的 PlayerView 加 `app:surface_type="texture_view"`。
- 收益：视频帧进入 View 合成树，可被截取用于模糊取景。
- 成本：多一次合成拷贝，Android 15/16 硬件上无感；顺带解决 SurfaceView 叠 UI 的 Z 序问题。
- 回退：若实测掉帧/花屏，`SP.glassBlur=false` 时可整体回退（见 4.4），面板走纯渐变仿玻璃，不依赖 TextureView（但 surface_type 保持 texture_view 不回切，避免两套路径）。

### 4.2 GlassBlur 实现（核心新类 `GlassBlurHelper.kt`）

**原理：小图放大即模糊 + 叠加渐变**，不依赖 RenderEffect，全 API 通用、成本极低：

```
面板显示时启动，500ms 周期：
1. bitmap = textureView.getBitmap(96, 54)      // 同步小尺寸抓帧，~1-2ms
2. 对 96x54 小图做 2 次 boxBlur(r=2)            // CPU 上微秒级
3. panel.backgroundTintBitmap = bitmap          // 面板底层 ImageView 显示，
   ImageView 用 centerCrop 放大（双线性插值天然强模糊）
4. 上层再叠 bg_glass_panel 渐变+描边
面板隐藏时停止定时器并回收 bitmap。
```

- API 31+ 增强：对放大后的底图再加 `RenderEffect.createBlurEffect(20f)`，消除放大纹理感。
- 每个玻璃面板 = `GlassPanelLayout`（自定义 FrameLayout）：内含 `blurLayer(ImageView)` + 内容区，对外只暴露 `attach(textureView)` / `start()` / `stop()`。
- 布局中以 `<com.lizongying.mytv0.view.GlassPanelLayout>` 替换原面板根节点，background 设 `bg_glass_panel`。

### 4.3 接线点

MainActivity 持有 playerFragment 的 TextureView 引用（PlayerFragment 暴露 `getVideoTexture()`），在 show/hide 各面板 Fragment 时调用对应 GlassPanelLayout 的 start/stop。OSD 类短时组件（音量/频道号/信息条）只在 VISIBLE 期间运行。

### 4.4 降级与开关

新增 `SP.glassBlur`（默认 true），设置页"界面外观"组暴露"玻璃特效"开关：
- ON：抓帧模糊底 + 动效
- OFF：GlassPanelLayout 不启动抓帧，仅渐变+描边（视觉八成相似）；焦点动效同时关闭 scale（保留颜色变化）
- 自动降级：连续 3 次 `getBitmap` 超 8ms 或返回 null → 本次会话自动置 OFF（日志记录）

---

## 5. 各页面改动明细

### 5.1 频道菜单（menu.xml / list_item.xml / group_item.xml + 3 个 Kotlin）

**menu.xml**：根 LinearLayout 内套 `GlassPanelLayout`（左右 margin 28dp/上下 28dp、pad 16dp），group 与 list 两个 RecyclerView 保持原 id/宽度机制（compactMenu 逻辑不动），背景色块删除。

**group_item.xml**：TextView 外包 14dp 圆角容器；三态：
- 焦点：`bg_item_focus` + 白字
- 当前分组非焦点：`bg_group_active` + 白字（新增状态，现在只有字色区别）
- 普通：透明 + text_tertiary

**list_item.xml**（结构变更）：
```
ConstraintLayout (bg_item_selector, 16dp 圆角, marginBottom 6dp)
 ├ icon (44x30dp, 6dp 圆角裁切: 外包 CardView 或 clipToOutline)
 ├ title (16sp, text_secondary)          ← 原字号不变
 ├ epgNow (12sp, text_dim, 新增)         ← 当前节目名
 ├ num (13sp, text_dim, 新增)            ← 频道号
 └ heart (原样, heart_on 色)
```

**ListAdapter.kt**：
- `focus()` 改为切换 selector 由系统处理 + `animate().scaleX/Y(1.03f)`；字色切 text_primary
- `onBindViewHolder` 新增：`tvModel.epg.value` 过滤当前时间段取节目名填 `epgNow`（无 EPG 隐藏该行，行高自适应）；`num` 填频道号
- EPG 变化刷新：MenuFragment 可见时对当前列表 `notifyItemRangeChanged`（EPG 已有 LiveData，menu 打开时数据基本就绪，不做逐项观察，避免 observer 泄漏）
- 大字模式：epgNow/num 同样走 `px2PxFontElder`

**GroupAdapter.kt**：三态背景切换逻辑（记录 currentPosition，bind 时判断）。

### 5.2 信息条（info.xml / InfoFragment.kt）

结构重做：
```
GlassPanelLayout (620dp 宽, bottom 36dp 居中)
 ├ logo (100x68dp, 14dp 圆角)
 └ 右列:
    ├ 行1: chno (26sp, accent, 阴影发光) + title (22sp, bold)
    ├ 行2: now_playing + 节目名 + 时段 ・ up_next + 下一节目 (14sp)
    └ 行3: ProgressBar (bg_progress_accent, 5dp)
```
InfoFragment.show() 逻辑增强：
- 从 `tvModel.epg.value` 取当前节目（beginTime≤now<endTime）→ 标题+时段+进度百分比；取下一条 → "接下来"
- 无 EPG：行2 显示"精彩節目"，行3 隐藏
- 显示期间每 30s 刷新一次进度（handler，隐藏时移除）
- 原 5s 自动隐藏、repeatInfo 逻辑保持

### 5.3 时钟 / 数字选台（time.xml / channel.xml）

文字外包 `bg_glass_pill` 胶囊容器（时钟右上 28dp；数字选台同位置层级错开：选台显示时时钟隐藏——现有 MainActivity 已有互斥逻辑，仅换皮）。字号不变。

### 5.4 节目单（program.xml / program_item.xml / ProgramAdapter / ProgramFragment）

**program.xml**：右侧 430dp `GlassPanelLayout`（上下 28dp），顶部新增频道头（logo+台名+副标题"节目单·支持回看"），下方 RecyclerView。
**program_item.xml**：
```
行卡 (bg_item_selector, 14dp 圆角)
 ├ time (14sp, text_dim, 等宽数字)
 ├ title (16sp)
 └ badge (11sp 胶囊, 新增): 可回看(badge_catchup)/直播中(badge_live)/无
```
**ProgramAdapter**：bind 时按 `epg.endTime < now && tvModel.supportsCatchup()` → 可回看徽标；`begin≤now<end` → 直播中徽标+行加 8% 白底；焦点态同全局。ViewHolder 增加 badge 绑定；Adapter 构造传入 `supportsCatchup`。
**ProgramFragment**：填充频道头 logo/台名。
**日期胶囊**：当前 EPG 数据模型（`EPG(title,beginTime,endTime)` 扁平列表）仅覆盖抓取范围，无按天分组结构。第一版**不做日期切换**，列表直接展示全部数据（通常含昨/今），滚动定位到当前节目（现有逻辑已做）。日期分组胶囊列入二期（需 EPGXmlParser 按天分组重构）。

### 5.5 设置页（setting.xml 重构 / SettingFragment.kt 重构）

**布局**（一步到位）：
```
setting (全屏, 点击空白关闭)
 └ GlassPanelLayout (900x600dp 居中, 横向)
    ├ side (220dp): app 图标+名+版本 / nav_source / nav_play / nav_display / nav_about
    └ content (ScrollView): 4 个分组容器 (仅当前分组 visible)
       ① 直播源: 直播源地址框(点击→SourcesFragment)、启动自动更新开关、EPG 说明行、远程配置按钮、默认频道行
       ② 播放: 软解开关、循环播放显示信息开关
       ③ 显示: 大字模式、显示频道号、显示时间、显示秒、频道反转、全部频道、紧凑菜单、进入收藏、玻璃特效(新)
       ④ 关于: 版本+检查更新、开机自启、恢复默认、赞赏、退出、GitHub 地址
```
**SettingFragment.kt**：
- 保留全部现有 SP 逻辑/权限流程/UpdateManager，仅重排到新 id
- 新增导航逻辑：nav 焦点/点击 → 切换 content 分组 visibility；DPAD_RIGHT 从 nav 进入内容区第一项，DPAD_LEFT 回 nav
- 开关行统一为"标题+副标题+SwitchCompat"行卡（新 `setting_item_switch.xml` include 复用，代码里循环装配，替代现在 12 个重复 XML 节点）
- 按钮统一玻璃行卡样式（焦点态同全局）
- 风险控制：所有 SP 读写逐项对照现版本迁移，迁移清单见 §8 测试表

### 5.6 线路切换（sources.xml / sources_item.xml / SourcesAdapter）

面板改 340dp 居中偏右 `GlassPanelLayout`，标题"线路切换·<台名>"。行卡：`线路 N` + URL 尾段灰字 + 当前线路 ✓（accent 色，替换现有 done 图标位）。焦点态同全局。测速标签不做（已确认）。

### 5.7 播放器 OSD（player.xml / PlayerFragment.kt）

- **时移条** seek_overlay：改 900dp 居中 `GlassPanelLayout`（非全宽贴底），SeekBar 换 accent 样式（thumb 白圆+发光、track bg_progress_accent），"回看中"胶囊徽标；控制逻辑不动
- **音量**：icon+ProgressBar+数值 包进顶部居中玻璃胶囊（原约束改为胶囊容器整体显隐），进度 drawable 换 accent
- **loading**：进度圈 tint 改 accent

### 5.8 错误页（error.xml / ErrorFragment.kt）

全屏黑底改透明；中央 `GlassPanelLayout` 卡片：📡 图标(48dp) + msg(20sp) + 副行(14sp, text_dim)"30 秒后自动重试"+ 不确定进度圈(accent)。ErrorFragment.setMsg 增加副行参数（默认自动重试文案；播放彻底失败场景传入对应提示）。与 PlayerFragment 的 autoRecoverRunnable 文案联动（现有逻辑）。

---

## 6. 大字模式适配

- 所有新增 TextView 一律走 `px2PxFontElder`（观看链路）或 `px2PxFont`（设置页维持现状）
- 新增行高/图标尺寸走 `px2PxElder`
- 徽标/胶囊 padding 走 `px2Px`（不随大字放大，避免膨胀）
- 验收：elder ON 时频道列表、信息条、错误页、时移条文字 ×1.4 且无截断

---

## 7. 开发拆分与提交计划

每阶段独立 commit、可独立编译回退：

| # | 内容 | 涉及文件 | 预估 |
|---|---|---|---|
| P1 | 色板/drawable/themes/strings + GlassPanelLayout + GlassBlurHelper + SP.glassBlur + TextureView 切换 | res/*, view/GlassPanelLayout.kt, GlassBlurHelper.kt, SP.kt, player.xml | 基础层 |
| P2 | 频道菜单三件（含 EPG 小字行）+ 焦点动效工具 `FocusFx.kt` | menu/list_item/group_item.xml, List/GroupAdapter, MenuFragment | |
| P3 | 信息条 + 时钟 + 数字选台 | info/time/channel.xml, Info/ChannelFragment | |
| P4 | 节目单 + 线路切换 | program*/sources*.xml, Program*/Sources* | |
| P5 | 播放器 OSD + 错误页 + loading | player/error/loading.xml, Player/ErrorFragment | |
| P6 | 设置页重构 | setting.xml, setting_item_*.xml, SettingFragment | 最大单项 |
| P7 | 联调：NAS 编译 debug APK → 实测 → 修问题 → 打 tag CI 发版 | — | |

编译验证：每阶段完成后走 NAS Docker `assembleDebug`（增量 ~30-50s），P7 全量实测后 GitHub Actions 发正式版。

---

## 8. 风险与测试清单

| 风险 | 缓解 |
|---|---|
| TextureView 在部分盒子花屏/掉帧 | SP.glassBlur 关闭抓帧；若渲染本身异常，单独 commit 可 revert surface_type |
| 抓帧模糊性能 | 96x54 小图 + 自动降级（§4.4） |
| 设置页重构漏迁移开关 | 逐项迁移清单：channelReversal/channelNum/time/displaySeconds/bootStartup/repeatInfo/configAutoLoad/defaultLike/showAllChannels/compactMenu/softDecode/elderMode + 6 按钮 + remote/update 流程 |
| EPG 小字行引发列表卡顿 | bind 时同步读 LiveData 快照，不注册观察者；无 EPG 时 GONE |
| 焦点动效残留（scale 未复位） | FocusFx 统一管理，detach 时复位 |
| 大字模式布局爆版 | §6 验收项逐屏检查 |

**实测清单（P7）**：换台/数字选台/收藏/分组循环、菜单打开关闭 10 次内存无涨、回看进入退出、时移条操作、断网自动恢复卡片、设置页全部开关生效+重启生效项、大字模式全屏走查、玻璃开关 ON/OFF 对比、8h 挂机播放。

---

## 9. 明确不做（本期）

- 节目单日期分组胶囊（EPG 数据层重构，二期）
- 线路测速标签（已确认不做）
- 真·实时逐帧模糊（成本收益比差，抓帧方案已足够）
- 手机竖屏适配（App 定位电视横屏）
