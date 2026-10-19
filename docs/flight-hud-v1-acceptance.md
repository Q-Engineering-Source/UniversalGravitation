# UG Flight HUD v1 — 交付与 GPU 验收

## 本轮范围

第一版 HUD 只读显示完整姿态、速度、推进输入、boost 挡位/目标速度、roll 挡位/实际角速度。通过 UIE 公开的 `FlightApi.getRenderPose()` 和 `FlightHudCanvas` 接入，不依赖 electric-elytra，也不链接 UIE flight 内部 renderer。

- 保留 FREE 四元数控制、Mouse X/Y 即时响应、可重绑 roll 惯性和平移行为。
- UG 在 Forge `RenderGameOverlayEvent.Post(ALL)` 绘制，不取消 overlay/hand 事件，不启用 UIE 内置 Flight HUD，不改 UIE 用户配置。
- `six_dof_hud` 默认开启；关闭它只关闭仪表，不改变相机或推进。
- F3 开启时 HUD 暂时让位，避免后绘制的仪表覆盖诊断列；关闭 F3 后恢复。F1、GUI、暂停或失焦时同样不绘制，不修改控制状态。
- 俯仰标尺覆盖 360°；方位/俯仰/滚转是世界参考的显示量，不是自动回正目标。
- 数字 P 沿用 Minecraft pitch 的正向下俯约定；P/R 数字按一周归一化显示，经过 ±180° 时的数字换号不是相机控制跳变，也不是累计滚转量。OMEGA 则是实际带符号角速度。
- `THRUST INPUT` 表示推进输入请求，不保证当帧实际加速度；速度上限、碰撞和其他力仍可影响实际运动。
- boost `TARGET` 是已选挡位的配置目标；roll `OMEGA` 是现有滚转动力学实际角速度。两者不得混为一项。
- 不显示失速、襟翼、迎角、燃料或地形预警，不实现姿态修正/装备/燃料功能。

## 验证与产物身份

- Source / Static：独立 Standards 与 Spec 审查均为 P0/P1/P2 = 0/0/0；Spec 发现的 F3 遮挡已通过纯显示 gate 修正并复核。目标开始前的 quaternion/Mouse、roll dynamics、drift、boost 与物理按键算法保持原字节不变，既有 adapter 函数只新增 HUD 入口/读取，不重写控制路径。
- Build / Unit / Integration：F3 修正后独立执行 `./gradlew.bat build --no-daemon --rerun-tasks --offline`，exit 0、33s、9 tasks 全部 executed；XML 回读为 21 suites / 124 tests / 0 failures / 0 errors / 0 skipped，其中实际 production renderer 的 HUD 测试 13 项。此前启动的 pre-F3-fix 构建不作为最终产物。
- Dedicated Server：最终同 JAR 的原 stdin smoke 已通过并由独立 verifier 复核。新隔离世界 `unigrav-hud-v1-stdio-20260904`，17:12:24 到达 `Done (8.341s)`、监听 8611；17:12:36 从保留的 session 53526 发送 `stop`，出现 Saving players/worlds/chunks，17:12:37 卸载维度 0/2、exit 0；PID 12012 与监听均消失。保留一条测试包 AE2 缺少签名的非致命 FML ERROR，不宣称零错误环境。前一次遗漏会话句柄的 Ctrl-C fallback 仅作补充证据，不替代最终正常 stop 日志。
- Real GPU acceptance: not executed
- 仓库：`D:/WI - Dev Workspace/UniversalGravitation-src/UniversalGravitation`；branch `main`、HEAD 未建立、`UNCOMMITTED`。本轮基线/目标差异在 `.pensieve/.state/hud-v1/{initial-files.json,before,after}`；不能把旧 commit/旧 JAR 测试结果算作本轮结果。
- 发布产物：`build/libs/unigrav-0.1.0.jar`，212,118 bytes，SHA-256 `8660C341C592B4322560F6C73405B1A645ECBFF1839271F0F3A080B6D1DA32E5`；生成时间 `2026-09-04T16:58:30+08:00`，本轮关联标签 `ug-hud-v1-20260904-8660C341`。
- 制品审计：130 JAR entries、111 classes 全部 major 69；`neofontrender/**` 与 electric-elytra bundled entries 均为 0，3 个新增 HUD 顶层类及其嵌套类、原有相机适配器均在发布 JAR 内。
- 编译接口来源：本地 `D:/WI - Dev Workspace/NeoFontRender/addons/ui-enhancements/build/classes/java/main`；UIE source HEAD `af8b6967f8cb40d9e723c2799ece6c5e44f27ff0` 加既有未提交相机呈现修复。`FlightHudCanvas.class` SHA-256 `DA28F1D2E367A7E805D3B6CB214D5FB8CD67152630E74DD846D0F43E540FF323`；`FlightApi.class` SHA-256 `767C3216F82756BEF323162AE992672346C427F8FB7E67EACF11A54C7337DFBA`。本轮未修改/构建 UIE。

Java2D 预览已生成于 `build/reports/hud/`（320×240、854×480、1720×720）；主线程已查看布局。它们只证明生产绘制核心的二维布局，不包含 Minecraft 字体、UIE/GL 驱动和真实输入，不替代 GPU 门。

## 部署与恢复

- 2026-09-04T17:15:38+08:00，Z 盘规范客户端 `.minecraft/mods/unigrav-0.1.0.jar` 替换成功；长度与完整 SHA-256 均回读匹配上方最终产物。`.codex-new` 临时文件已不存在。
- 规范专服 `mods/unigrav-0.1.0.jar` 同样回读为该哈希；专服在 smoke 后已停止。当前 6-DOF 正向测试请在 GPU 节点的单人 Creative 测试世界执行，不要将已停止的专服误认为正在提供连接。
- 客户端前版（用户此前实测的 191,292 bytes、SHA `8F9C3F671DED50A453823B2FF90663E7FC818B4601D8121A982D2ECE28697BD4`）备份于 `C:/GradleCaches/artifacts/UniversalGravitation-hud-v1-client-before-20260904-1715.jar`；恢复也必须先关闭对应客户端并核验备份 SHA。
- 专服前版备份与初次 smoke 证据：`C:/GradleCaches/artifacts/UniversalGravitation-hud-v1-20260904-170058/`。
- 最终原 stdin smoke 的命令、退出记录及日志：`C:/GradleCaches/artifacts/UniversalGravitation-hud-v1-20260904-171237-stdio/`。`latest.log` SHA-256 `16828A5EE18BD1C2F31CE7AC0BDB1E0D342CE9438082AE21927E924D74A0CA8B`；`debug.log` SHA-256 `1E4FED0CC4E368D74239F2DBA9BD12A4EE897131EF5FF3C955DFB17BF38A1058`。
- 独立 verifier 的完整记录：`.pensieve/.state/hud-v1/verification.md`。本轮没有新增 Git commit/push，也没有修改 UIE 或原始 test-pack。

## 最小真实 GPU 验收步骤

本节由独立真实 GPU 节点执行。开发服务器禁止启动 Minecraft 3D 客户端。

1. **确认构建身份。** 关闭项目客户端后核对规范实例 `.minecraft/mods/unigrav-0.1.0.jar` 的 SHA-256 与本轮交付一致；记录 UIE/Revo Font/ModularUI 依赖版本与哈希。不同 artifact 的结果不得合并。保留现有世界、配置和日志，使用测试专用世界。
2. **显示与退出。** 在 Creative integrated server 中按当前绑定启用个人 G，确认出现 FREE 仪表；关闭 G 且恢复普通重力后消失。自然零重力按现有相机条件激活时，仪表不得错误报告已获得 Creative 平移授权。
3. **完整姿态。** 连续仰俯越过头顶及 ±180°，滚转至 90°/180°/270° 后做单轴和斜向鼠标输入，再连续多圈。确认俯仰标尺始终有刻度、方位/滚转方向与画面一致，HUD 没有驱动视角、隐式回正或改变 Mouse 手感。
4. **滚转数据。** 使用当前 roll 左右绑定及挡位增减键。确认挡位、最大角速度、带符号的 OMEGA 更新；松键继续惯性滚转并衰减，反向键可制动。仅移动鼠标时，roll omega 不被改变。
5. **速度和推进输入。** 从静止分别测试 WASD、Space/Shift、boost、boost 与平移并按。确认单位是 m/s，`THRUST INPUT` 与请求匹配；松开后请求结束但速度可以因惯性保留。boost 挡位变化更新目标速度，保持沿可见 forward 的原有加速行为。
6. **原版交互保留。** 在手持物品的第一/第三人称下确认准星、快捷栏、手部、选槽、攻击/使用和方块 picking 正常。记录现有 UIE HUD 隐藏设置；UG 仪表不得因为这些设置而额外隐藏上述元素，也不得改写设置。
7. **界面与窗口边界。** 测试 F1、F3、背包、聊天、ESC/暂停、失焦与恢复；切换 GUI scale 和窗口宽高比。确认 F3 开启时 HUD 隐藏、诊断列完整可读，关闭后恢复；GUI/暂停/失焦时无仪表残留或输入积累，准星区域和底部快捷栏保留可用空间。
8. **独立开关与会话。** 将 `six_dof_hud` 关闭后，确认 HUD 消失而 FREE 输入/惯性仍正常；再开启并测试退出世界、重新进入、死亡/重生和其他 UIE 相机模式的让位，旧玩家状态不得残留。

当前无远程 field-state sync，完整 6-DOF 正向行为必须使用 GPU 节点 integrated server。连接权威 dedicated server 的测试只覆盖当前 remote pass-through/common/network 边界；同样必须使用本轮确定 artifact，并同时保留双方日志。

## 回执所需证据

- 同一测试窗口的时区、开始/结束时间和 test/session 标签。
- 客户端实际 UG JAR SHA-256；联机时还要提供服务端实际 UG JAR SHA-256。
- `.minecraft/logs/latest.log`、本轮 debug log（如有）、crash report（如有）。
- 至少一段包含仪表与实际输入过程的录屏：正常、倒悬、越顶、roll 惯性、换挡、boost 以及退出 HUD。截图不能单独证明连续运动手感。
- 窗口分辨率、GUI scale、GPU/驱动信息，以及每个步骤的结果和异常时间点。

未取得上述真实节点证据时，状态保持 `Real GPU acceptance: not executed`。本目标的交付终点是代码/自动化/审查/发布产物与可重复验收包完成，不自行扩展到下一阶段。
