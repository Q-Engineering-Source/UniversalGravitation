# UG 玩家移动、尺寸生命周期与 YSM 姿态兼容修复规划

## 2026-10-11 文档对齐状态

04 已按当前 UIE API 10、YSM 可选 hook/config/ABI 实现和 Root464 冻结候选校正方法级计划；Free server read-only authority 与 head source 决策仍交当前 Server owner。全目录仍是 PLAN / NOT_DELIVERED，当前活动进度只看唯一账本 `docs/progress/ug-chb/推进计划.md`。这次是文档候选，没有执行 Java 测试、Mixin/FML 启动、专服、真实 GPU 或双玩家验收。

日期：2026-10-07，Asia/Shanghai。本文包是交给 UG 主会话的执行规划，当前保存的是 **PLAN**，不是已修复、已测试或可交付 OK 的声明。

指定执行会话：[UG Mainline 接手会话](codex://threads/01a1292c-33f1-77b1-8cc8-65f645c2b374)。2026-10-11 用户“新会话接手”，沿用完整合同；旧主会话错误现场及冻结 writer 回执见 C:/GradleCaches/artifacts/ug-main-handoff-stream-recovery-20261011-r1/TAKEOVER.md。

用户已确认：“本次预期为修复为止，不限时长，不代表可以无限拖延。”本轮最低完整交付就是下述修复全部成立并完成实际验收与保存。原修复未单独开立更高档；2026-10-08追加F08已选择最高档“索引聚合 + 压缩扫描缓存 + 局部增量发布”，见08。无需再次询问已经明确的修复预期和死线。

## 阅读顺序

1. **必须先读** [01-目标与验收合同.md](01-目标与验收合同.md)：范围、授权如何生效、主干、最终停止条件。
2. **必须读** [02-证据与方法地图.md](02-证据与方法地图.md)：真实源码入口、运行产物差异、已确认事实与待验证假设。
3. 修改移动、尺寸、传送或自动绑定时，完整读 [03-移动与尺寸修复.md](03-移动与尺寸修复.md)。按 S0→S1→S2→S3→S4 推进，每个切片先完成必要行为证明。
4. 修改模型姿态或跨 UIE 工程接口时，完整读 [04-YSM可选适配.md](04-YSM可选适配.md)。其中第2–6节是 2026-10-11 的现有 API/mixin/config 对齐与 S5.1–S5.5 后续计划；YSM 仍是完整合同中的必交付功能，不能把“已有源码”写成启用验收完成。
5. **开始执行和宣布完成之前必须读** [05-执行与验收.md](05-执行与验收.md)：持续推进规则、测试矩阵、环境、证据等级、保存要求。
6. [06-主会话目标模式提示词.md](06-主会话目标模式提示词.md) 是用户投递入口，不重复上述方法方案。

## 文档与状态的职责

本目录保存本轮规范和设计，不建立另一份活动进度账本。实际执行状态继续写入唯一账本 `docs/progress/ug-chb/推进计划.md`，附本目录和切片验收回执的指针。既有 HTML 按工程流程生成，不手改替代 Markdown。必要回执可保存于现有 `docs/progress/ug-chb/`，不在多个目录同步勾选同一完成状态。

长期工程约束继续由仓库 `AGENTS.md`、`docs/ug-chb-capsule-execution-contract-v1.md` 和当前用户全局约定承载。旧 Pensieve 的 PAUSED、额度分档、旧子代理配置均需按最新用户指令核对。本轮目标提示词由用户投递后，明确恢复本目录限定的修复；不恢复整个未完成的 M0–M6 项目。

研究原件见 [原版行走、GravityChanger 与 YSM 调查](../../research/native-walking-gravitychanger-ysm-20261007.md)。其源码、字节码与日志证据供引用，运行时缺口仍须执行会话补齐。

## 执行摘要

| 缺陷 | 本轮结果 | 首要责任入口 |
| --- | --- | --- |
| F01 行走中潜行真实前突及手感 | 潜行尺寸过渡不额外推进实际位置；步进顺序与原版基线相符，用户实玩认可 | `PlayerPhysicsServer.runNativePose`、`PlayerPhysicsStep.step`、`PlayerMovementRouter`、客户端输入/镜像 |
| F02 水游技艺、缩放药水改变碰撞箱后卡死 | 合法姿态/宽高变化可被权威状态接纳或明确恢复；无需 kill | `PlayerBodyShape`、`PlayerEnvironmentSampler`、`PlayerPhysicsServer`、`PlayerPhysicsClientMirror` |
| F03 传送确认失败后停止推进 | 精确 ACK 的成功、可恢复拒绝、异常均有明确结果，避免永久 pending | `observeNativeTeleport`、`confirmNativeTeleport`、`advanceOwned`、会话生命周期 |
| F04 零尺寸座位被自动重力绑定 | 实际座椅可使用，自动估算不向零体积 helper 构造非法质量 | `GravityEntityBinder.tryBindWith`、`refreshIfEstimated`、`BodyPhysics.estimate` |
| F05 YSM 六向/连续旋转可选兼容 | 开关有效，本地和远端身体姿态正确，物理状态与模型绘制职责明确 | UG 姿态来源 + UIE `FlightApi` + YSM 自有根渲染边界 |

主干顺序是先保住移动和状态生命周期，再接模型兼容，追加F07双面地板与F08最高档聚合，最后绑定同一最终产物完成专服、真实 GPU、双玩家与用户手感验收。F06队列/长tick性能仍按阻断证据纳入；已明确要求的F08是必需主干。

## 当前身份与命名约定

- UG Git 根：`D:/WI - Dev Workspace/UniversalGravitation-src/UniversalGravitation`。
- 制定规划时 HEAD：`111f5c99ea228067f882dfb9bbeb0de68cd77b5b`；工作树有既存未跟踪 `docs/`，必须保留。
- 2026-10-07调查时Q-Eng服务器UG为R99、本地缓存为R101 WIP；这是历史调查身份。当前candidate及F07/scalar冻结身份以唯一推进账本和实际哈希为准，不能混用验收结论。
- 命名与实现状态按各自日期阅读：2026-10-07 原文中的“拟新增”符号可能已在后续候选出现；本轮按 02 第5节和 04 的 2026-10-11 源码身份判断现有状态，不把历史方案当作当前未实现证明。
- 类型简称的包和绝对源码入口集中列于 02、04；方法位置可能随修改移动，应按符号核对，不能按旧行号盲改。
- 本规划的文件哈希、编码/链接及引用路径核对结果保存于`manifest.json`。它只标识规划产物，不是游戏修复验收回执。

2026-10-07/08 原规划制作与追加阶段的边界记录：当时没有修改 Java、Mixin、Gradle、配置、运行 JAR或世界，也没有启动游戏、专服，或自动向主会话发消息。该历史记录不描述 2026-10-11 文档对齐轮；本轮具体文档范围与未执行的验收见 04 和 manifest 的 `document_sync_receipt`。

## 2026-10-08 末尾追加：双面重力地板

**继续当前切片的阶段边界及宣布最终完成前必须读** [07-双面重力地板追加需求.md](07-双面重力地板追加需求.md)。用户新增F07/S6：同一地板的相对两面，各自在外侧提供指向该面的重力；水平放置时上方朝下、下方朝上，六向旋转时同样成立。本项纳入当前规划末尾的必交付主干，沿用既有验收与保存要求。原修复和当前writer的dirty保持；旧F01–F05列举不再是完整最终范围。

## 2026-10-08 追加：F08最高档聚合及用户安装验收

**修改floor index/scanner/flush前及宣布最终完成前必须读** [08-重力地板聚合与增量发布.md](08-重力地板聚合与增量发布.md)。F07正确生产scalar已冻结并被Mainline独立接纳，身份见08第2节；核该指针后实施S7三层优化、同数据等价和真实刷新入口perf runner，不重跑全部旧gate。空pending快返、dirty有效源相同NoChange、局部delta、同代共享cache/index一次发布、新generation新view及目录成员owner fence都属于本档主干。08已并入Mainline事务与runner链接审阅修订，最终核版放行后由现有writer实施；实时状态只读唯一账本。

阅读顺序追加07→08→更新后的05完成门；[06最新入口](06-主会话目标模式提示词.md#2026-10-08-f08最高档最新目标入口)用于续接当前目标，旧prompt保留为历史。本次规划会话只更新获授权文档，产品实现仍归现有writer。

最终非人工candidate完成后，由指定复制writer保存并核对最终JAR/依赖/证据身份，暂存到`D:/0_Minecraft_Server/Wi_Server/Q-Eng-Server/Q-Eng-mods`。用户安装并完成实际按键、视觉、手感和双玩家验收；暂存成功不等于整体OK。01的最终停止条件现在包含F01–F05、F07、F08，以及条件成立时的F06。

## 2026-10-09 本批独立统一规划入口

用户追加 G 极点回归、Alt+H 固定输入与身体/眼点/碰撞、地板吸引和自动参考（有效地板轴优先于手动轴，是否屏蔽按实际配置）、既有 CHB 胶囊查询的完整生产接入。Mainline 已与既有 XHigh/Astra 完成只读设计咨询并保存 [地板、身体姿态、胶囊碰撞与视点统一修复规划](../player-floor-capsule-camera-20261009.md)，其 PLAN v1 为本批方法级实施入口；本目录总合同、F07 双面和 F08 最高档继续被引用。当前进度仅写唯一推进账本，不重复执行旧回复或将局部 checkpoint 提升为全批完成。
