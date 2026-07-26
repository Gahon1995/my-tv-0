# my-tv-0 UI 重设计 — 话题恢复上下文（HANDOFF）

> 用途：话题挂起后恢复开发用。新会话直接引入本文件即可接上全部上下文。
> 最后更新：2026-07-27 00:20 · HEAD = `6729e19`（已全部 push 到 GitHub main）

---

## 1. 项目基础信息

| 项 | 值 |
|---|---|
| 本地源码 | `/var/minis/shared/my-tv-0/`（git 已配置，push 用 `https://Gahon1995:$GITHUB_TOKEN@github.com/Gahon1995/my-tv-0.git`） |
| 最新 APK | `/var/minis/shared/my-tv-0-apk/my-tv-0-glass-debug.apk`（12.7M debug 签名，**用户尚未实测此版**） |
| 技术方案 | `/var/minis/shared/my-tv-0/docs/ui-redesign-plan.md` |
| 设计稿 | `/var/minis/workspace/mytv-ui/`（final-1~5.html、menu-epg.html、glass.css，浏览器 1280x720 视口预览） |
| 开发流程 skill | `/var/minis/skills/mytv-dev/SKILL.md`（NAS 编译、CI 发版全流程） |

## 2. 已完成（四轮迭代，全部编译通过）

1. **液态玻璃全页面重做**（方案A/青蓝焦点色）：频道菜单、信息条(EPG进度条+接下来)、时钟/数字选台胶囊、节目单(直播中/可回看徽标+频道头)、线路面板、时移条、音量胶囊(百分比)、错误卡、设置页(左导航4分组 700x460)
2. **玻璃实现最终形态**：纯遮罩（80% 深底 #CC10161F + 渐变高光 + 描边，`GlassPanelLayout.onDraw` 绘制）。曾做过抓帧模糊方案，因播放卡顿已移除；PlayerView 保持 SurfaceView
3. **菜单第三列节目单**：频道列表右键展开，OK=播放/回看，左键收回；收藏改为长按 OK
4. **回看修复**：进度条改 EPG 绝对时间轴（流内 duration 不可信）；快进快退 `seekCatchup()` 重请求时间窗（伪直播流不可 seek）；失败快速重试 2 次自动回直播
5. **性能**：readEPG 匹配+gson 序列化移出主线程（**这是 UI 卡顿根因**）；OkHttp 超时 5s/15s；换台先 stop 旧流；logo 预载延迟 10s；换线路 500ms 退避
6. **其他**：默认简体中文（MyTVApplication + MainActivity 两处）、换台反转默认开、EPG 保留近 3 天（回看数据源）

## 3. ⏳ 等待用户实测反馈（恢复话题先问这些）

1. **UI 卡顿是否消失**（最新版已修主线程 EPG 问题）
   - 若仍卡 → 下一步 GPU 渲染分析（`adb shell dumpsys gfxinfo com.lizongying.mytv0` / systrace）
   - 候选嫌疑：FocusFx 动效、RecyclerView 全量 `notifyDataSetChanged`、GlassPanelLayout onDraw 频率
2. **回看**：时长显示是否正确、快进快退是否生效（seek 后 1-2s 缓冲属正常）、失败是否自动回直播
3. **交互**：右键节目单第三列、长按 OK 收藏、时钟 35% 透明度观感

## 4. 📋 未做/二期项

- [ ] 节目单日期分组胶囊（需 EPGXmlParser 按天分组重构，见方案文档 §5.4/§9）
- [ ] 大字模式 ×1.4 全屏走查（各新页面文字截断检查）
- [ ] 8h 挂机稳定性测试
- [x] ~~线路测速标签~~（用户确认不做）
- [x] ~~播放核心切换 exo/ijk/系统~~（已评估：Android 15/16 上 media3 最优，有软解开关兜底；若实测仍卡再议）

## 5. 🚀 发正式版流程（实测 OK 后执行）

```sh
cd /var/minis/shared/my-tv-0
git tag v1.4.0    # 版本号按需
git push https://Gahon1995:$GITHUB_TOKEN@github.com/Gahon1995/my-tv-0.git main --tags
# 5-6 分钟后查 GitHub Actions / Releases（自动编译签名上传，无需 NAS）
# 验证：GET /repos/Gahon1995/my-tv-0/releases  (Authorization: Bearer $GITHUB_TOKEN)
```

## 6. 关键代码位置速查

| 功能 | 位置 |
|---|---|
| 玻璃面板 | `view/GlassPanelLayout.kt`（onDraw 遮罩；底色可用 XML 属性 `glassBaseColor` 覆盖，如时钟 35%） |
| 焦点动效 | `view/FocusFx.kt`（scale 1.03 + panelIn；受 `SP.glassBlur` 开关控制） |
| 回看 seek | `models/TVModel.kt` → `seekCatchup()` / `catchupOrigBegin`；`PlayerFragment.kt` → `seekOffset()` / `catchupAbsPosition()` |
| 回看失败回直播 | `PlayerFragment.kt` → `onPlayerError` 内 `tv.isCatchup` 分支 |
| 菜单节目单 | `MenuEpgAdapter.kt` + `MenuFragment.onShowEpg()`（右键触发）；布局 `menu.xml` epg_col / `menu_epg_item.xml` |
| EPG 解析 | `models/EPGXmlParser.kt`（保留 3 天 + programme@channel 属性匹配） |
| EPG 名称匹配 | `MainViewModel.readEPG()`（IO 线程，主线程只 setEpg） |
| 设置页 | `setting.xml`（左导航+4分组）+ `SettingFragment.kt`（bindSwitch 统一装配）；样式在 `values/themes.xml` |
| 色板 | `values/colors.xml`（青蓝 accent 系，旧色名保留别名） |
| 玻璃开关 | `SP.glassBlur`（设置页·界面外观"玻璃特效"；现在只控制动效） |

## 7. NAS 编译速查（debug 验证用）

```sh
# 同步 → 编译 → 取回（详见 mytv-dev skill）
cd /var/minis/shared && tar czf /tmp/my-tv-0.tar.gz --exclude='my-tv-0/.git' my-tv-0
sshpass -p "$SSH_PASSWORD" scp -o StrictHostKeyChecking=no /tmp/my-tv-0.tar.gz Gahon@192.168.6.43:/vol1/1000/docker/android-build/workspace/
sshpass -p "$SSH_PASSWORD" ssh -o StrictHostKeyChecking=no Gahon@192.168.6.43 "cd /vol1/1000/docker/android-build/workspace && tar xzf my-tv-0.tar.gz && rm my-tv-0.tar.gz && docker rm mytv-build 2>/dev/null; docker run -d --name mytv-build -v /vol1/1000/docker/android-build/workspace:/workspace android-build:34 bash -c 'cd /workspace/my-tv-0 && chmod +x gradlew && ./gradlew assembleDebug --no-daemon > /workspace/gradle-build.log 2>&1'"
# ~40-50s 后：
sshpass -p "$SSH_PASSWORD" ssh -o StrictHostKeyChecking=no Gahon@192.168.6.43 "docker ps -a | grep mytv-build; tail -4 /vol1/1000/docker/android-build/workspace/gradle-build.log"
sshpass -p "$SSH_PASSWORD" scp -o StrictHostKeyChecking=no Gahon@192.168.6.43:/vol1/1000/docker/android-build/workspace/my-tv-0/app/build/outputs/apk/debug/app-debug.apk /var/minis/shared/my-tv-0-apk/my-tv-0-glass-debug.apk
```
