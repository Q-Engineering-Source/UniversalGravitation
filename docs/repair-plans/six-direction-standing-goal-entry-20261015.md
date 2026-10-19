# 六向真实站立：目标模式入口

状态：2026-10-15 已由用户在 Mainline 激活，并再次明确“将这段话作为目标模式使用”。来源为 Mainline 会话 `codex://threads/01a13369-2a8e-7142-9f84-6f52ea08b465` 本轮指令及激活回执：get_goal 为 null 后已创建限定六向站立的 active 目标，无预算。仅恢复本切片范围；原完整目标无关范围不恢复，未纳入本切片的合同要求保留，不因此取消或视为完成。动态结果仅以[唯一权威推进账本](../progress/ug-chb/推进计划.md)为准：已保存实际可安装候选；完整六向仍有NG，人工确认待测。此入口不重复维护版本和逐轮测试结果。

以下为已激活的执行正文：

---

请进入目标模式，把 UG 的六向真实站立做完。你是 Mainline Owner（会话 `01a13369-2a8e-7142-9f84-6f52ea08b465`），负责实现、集成、验证与交付。本条明确恢复下述范围的产品修复、必要构建与测试，不再停在准备回执；不恢复无关工作。沿用已确认合同，不重问三档，不自行设置 token 预算、时限或降低验收。没有死线不等于允许反复准备而不交付。

## 1. 阅读入口与目标状态

仓库：`D:/WI - Dev Workspace/UniversalGravitation-src/UniversalGravitation`。先读取适用 AGENTS 和当前写入/运行状态，再按需定位：

- `docs/ug-chb-capsule-execution-contract-v1.md`：六向站立、参考选择、磁力、形状切换、owner 与验收契约。
- `docs/repair-plans/player-floor-capsule-camera-20261009.md`：当前实现路径与后续修正；按相关章节增量读取，不全文重放历史。
- `docs/repair-plans/player-movement-ysm-20261007/01-目标与验收合同.md`，以及 `03-移动与尺寸修复.md`、`04-YSM可选适配.md`、`05-执行与验收.md`、`07-双面重力地板追加需求.md`：真实用户问题与对应验收。
- `docs/progress/ug-chb/推进计划.md`：唯一权威账本。历史 ACTIVE、旧候选 PASS 与旧百分比不能替代当前状态。

用现有目标工具读取当前目标。若原完整目标仍 paused，按工具真实能力处理：不要伪称已恢复，不用 complete 清掉未完成目标，也不要另建重复完整目标。用户本条已授权本切片实际执行；工具无法主动 resume 时说明控制状态与授权范围的区别，并继续本切片工作，不为同一授权再提问。

## 2. 本切片必须完成什么

交付可实际使用的 ±X、±Y、±Z 六向站立，不是仅旋转模型或改相机。将以下项目写入现有账本的本切片验收矩阵，分别记录实现、自动化、专服、真实客户端与用户确认：

1. **参考与受力正确**：有效自然/指向性重力、地板与磁靴按既定资格选择确定的身体上方；合力与站立参考分开。有效场相互抵消不凭零合力切 Free。Personal G 意图不被模式切换、碰撞或纠正自动改写；Creative/Survival 磁性资格遵循合同。
2. **真实六向运动**：各方向的站立、落地、走跑斜行、起跳、踏步、潜行和边缘支撑走生产入口；同一次移动只有一个 owner、一次解算/提交与同一预算。潜行不产生额外实际切向位移；保持原版相应摩擦、跳跃与更新语义。
3. **形状与锚点正确**：真实 AABB/尺寸、身体、脚、眼点和 transport/revision 一致；水游技艺、爬行和缩放进入/退出不导致永久卡死或要求 kill/重登。受限空间中切换先检查路径与目标占位；失败保留安全状态，不假装已站立。
4. **进出六向模式安全**：覆盖地板/磁靴范围进出、换向、失去来源、自然模式恢复及必要 Cardinal↔Free 边界。在范围内跳跃/短暂离地保持应有六向 AABB；磁靴跳跃保护只阻止立即重附着，不取消范围内磁力。无有效参考不得凭空发布 Cardinal 参考。完成本切片所需的 Free 保持、碰撞和原生恢复依赖，不顺手重写任意姿态求解器。
5. **真实来源可用**：六向场、三轴 X/Y/Z 双面地板两端向心语义，以及合同规定的磁靴匹配、捕获、持续受力、脱离和来源生命周期须实际接入本切片路径，不以测试夹具代替生产接线。重力叠加去重、世界/区块卸载隔离不倒退。
6. **看见和操作的是同一身体**：通过 UIE 公共接口对齐 accepted 身体/头部/眼点、第一/第三人称相机、输入与 picking；本地和远端一致。覆盖无 YSM、关闭兼容、指定 YSM 1.1.9 开启后的六向必要呈现，根旋转只应用一次。完整任意姿态 YSM 扩展不自动升级为本切片前置，但已有路径不能回归。
7. **同步和恢复**：真实服务端权威 STATE 与客户端镜像一致，传送 ACK、重生、换维度、退出重入与来源失效后可恢复；旧连接/世代不能写回新状态，切换不重复发额度或退款。

## 3. 不扩大关键路径

F08 全矩阵正式性能与三层优化的最终验收、无关 KNK/载具修复、全局网络重构、未来模型版本、通用框架及文档美化不作为六向站立交付前置。它们仍保留在原完整合同，不能被删去或宣称通过。若其中某项有具体证据阻断本切片，只纳入解除阻断所需的最小责任修复，并记录原因；不能拿延期掩盖真实运行卡顿、数据完整性或核心碰撞缺陷。

## 4. 从当前失败继续，不重新起跑

当前已接受 R12 SOURCE：478 文件 / 6,041,892 bytes；canonical `A42E0744AC45DE61A957B07F8B64117666380B861B1D1A91BCE656EF5D61F231`。使用前核实当前目录和身份，保留 dirty/untracked；不能拿旧身份套新源码。

R13 对上述源码执行真实无过滤构建：173 XML / 1770 tests / 1 failure / 0 errors / 0 skips，自然 exit 1。唯一失败为 `purePersonalZeroGWithoutAnyReferenceRetainsSupportedFreeCapsule(Path)`，`PlayerPhysicsServerIntegrationTest.java:5699`：无有效 reference 的 Free successor 不应发布 effective Cardinal reference，实际 `bodyReferenceState` 非空。真实资格初始化与 Free 采样前置已过，后续 FreeCapsule/单 revision/owner 断言尚未执行。

`freeCardinalSelection` 最后无条件 `SAFE_NATIVE/Y_POS` 回退是待验证疑点，不能先写成已证明根因。追踪真实调用链，区分合法 native 恢复与无参考 Free，修复责任代码并重跑失败用例、受影响回归和完整构建；不删断言、不跳测试、不把不完整夹具修成假成功。

证据：`C:/GradleCaches/artifacts/ug-free-cardinal-owner-20261012-r1/mainline-integrated-build-r13-r12-source-retry-r1/runtime-trigger-r13-r1/run-receipt-r1.json`，18240 bytes，SHA-256 `95A8CAF839EF7407145DF45F24987753DF904EE358F35E7BC511F52D8CEA07C9`。R13 once 已消费，保留 NG；新运行用新身份。中间 JAR 不是通过构建的交付物。不要重跑未受修改影响的旧证据来充进度。

## 5. 执行权限与协作

### 5.1 hard 必须实际参与本切片

用户于 2026-10-15 明确要求完善规划并让 hard 参与。hard 会话：`codex://threads/01a0de5a-21a2-7960-970e-d19bfb0c6c44`；Mainline 保持唯一集成负责人，Docs 维护本规划。先前只读咨询已完成；现用户已激活本入口，以下协作随限定六向站立切片执行。

- 在 R13 的责任修复定案前，主动请 hard 独立核对参考选择、模式转换与 STATE 发布的调用链和不变量。发送具体失败、最小相关代码路径、当前假设和待决问题，不只转发整份历史账本。2026-10-15 此次只读审查已经回收，方法级结论见第 7 节；它不代表产品修复或运行验收通过。
- 出现参考/合力/Personal G/模式语义冲突、跨 UG/UIE/CHB 的责任边界不清、数学或碰撞安全路线选择时，直接找 hard，不要求先再失败一轮。
- 同一行为修复后仍失败、连续两轮只推进夹具而未验证目标行为、独立审阅与实现结论冲突，或准备流程开始重复而无新增证据时，由 Mainline 主动提交难题给 hard。次数是最迟触发点，不是等待额度。
- hard 返回根因证据、排除项、方法级建议、风险与最小验证矩阵；Mainline 核实建议并记录采用/不采用及理由，实施者落实修复，独立 reviewer 验证最终改动。顾问判断不替代测试，也不形成每轮机械审批门。
- 咨询期间推进无依赖冲突的源码核对、验收场景和产物清单；争议调用链暂不双写。hard 不可用时记录具体问题与边界，继续不依赖其决策的工作，不能全局空转。

### 5.2 写入与运行边界

你拥有 UG 本切片源代码、测试和必要权威记录的集成写入责任；可以安排文件范围互不冲突的实现及独立复核。遵守现行原生代理模型配置，优先复用可用执行者，不为角色名机械加人。UIE 必要适配沿既有跨仓授权与对应 owner 协调；CHB 用已核实公开 API，接口依赖确实缺失时记录最小缺口，不能未经协调另写库实现。

授权执行 Java 25 必要编译、回归、完整 build/package/ABI 门，以及既定隔离专服、真实 GPU 客户端和双玩家验证。读取 validation 与 multinode 指南，核对精确实例、世界、端口、进程、依赖及 Fugue + scalar。只管理本轮 UG 实例，不触碰 SEC 等其他 Java 进程，不清世界，不把 Q-Eng 原服当临时测试世界。

运行检查应服务于身份、隔离和可恢复性；同一事实已确认且未变化，不反复生成准备/审批链。遇 NG 修责任代码并重测；环境 BLOCKED 只阻塞相关链，继续独立工作。必要的新增人类决定一次说明原因，不能反复请求已由本条授权的构建与测试。

## 6. 尽快给可测候选，最后完成真实验收

### 6.1 交付顺序与可并行范围

| 切片 | 实际产出与退出条件 | 依赖与并行边界 |
| --- | --- | --- |
| S1 参考状态修复 | hard 语义核对；定位 R13 首因；责任代码修复；失败回归及后续未到断言实际执行通过 | 一名 writer 拥有参考选择/模式转换/STATE 相关调用链；不得另开第二人同时修该链 |
| S2 六向生产接线补齐 | 六方向运动、来源、尺寸/脚眼锚点、恢复逐项标明真实入口及缺口，修复并验证 | 与 S1 重叠文件串行；可并行只读核对 UIE/YSM 消费端和真实测试场景 |
| S3 可安装候选 | 同组合完整构建、必要包装/ABI/启动门通过，保存 JAR、依赖、配置与测试说明 | 不等待无关 F08 正式性能；每份候选保留失败/未测范围，不宣传全部修好 |
| S4 真实六向验收 | 专服、真实 GPU、双玩家、用户原始问题的逐场景证据，发现 NG 修复重测 | 客户端核查可独立准备；运行资源按实际冲突串行，证据必须对应当前候选 |
| S5 六向切片收口 | 独立复核、用户必要确认、保存交付物与限制；剩余原合同明确保留 | 六向通过不将原完整目标错误标记 complete |

每次阶段汇报列“已完成行为、当前失败、下一个可运行验证、可取用候选”，而非只列准备回执。S1/S2 修改影响到后续候选时重建并重测受影响场景，不机械重跑无关已接受历史。

### 6.2 六向验收矩阵

以下各类记录 ±X、±Y、±Z 六个方向的实际结果；一方向通过不能填满其余方向。测试夹具可验证算法，真实游戏场景验证生产接线及手感，两者分别记账。

| 场景组 | 必查行为 | 证据层 |
| --- | --- | --- |
| 常规运动 | 站立、落地、前后左右/斜行、走跑、跳跃、踏步、边缘潜行，位置无额外突进 | 自动化 + 专服/客户端实际行为 + 手感确认 |
| 来源与换向 | 地板、双面三轴、磁靴资格/捕获/脱离、有效来源抵消、跨边界及来源卸载 | 真实来源自动化 + 专服/客户端 |
| 尺寸与安全 | 水游技艺/爬行、缩放进入退出、受限空间、切换失败保留安全状态，无 kill/重登依赖 | 真实模组客户端与服务端对应证据 |
| Free/native 边界 | 无参考 Personal G、有效参考消失/恢复、跳跃保护期、无假 Cardinal、一次 revision/owner | 回归 + 必要真实转换场景 |
| 呈现与交互 | 身体/头/眼/手、相机、输入/picking，无 YSM及兼容关闭/开启，本地与他人 | 真实 GPU + 双玩家 |
| 生命周期 | 精确传送 ACK、重生、换维度、重连、旧世代拒绝与正常恢复 | 自动化 + 真实联机 |

### 6.3 候选与完成门

先形成能安装的同一组合候选，完成实际构建、依赖与包装检查及必要启动检查后立即保存并交付 CANDIDATE，不等无关 F08 正式性能。列清可测试功能、尚未通过场景和安装/回退清单；按既有授权暂存到 `D:/0_Minecraft_Server/Wi_Server/Q-Eng-Server/Q-Eng-mods`，由用户安装。暂存不等于生产替换，不覆盖运行世界。依赖、UG/UIE/CHB 哈希、源身份/必要 dirty 快照与候选绑定。

随后完成同候选六方向场景矩阵的必要专服、真实 GPU、双玩家验收和独立复核。不得以单元测试、headless、进入世界或截图代替真实行为与渲染证据。每个场景记录操作、预期、实际、候选身份与证据位置；原始潜行手感、实际水游技艺/缩放及六向模型需进入真实场景。自动完成可完成的部分，再让用户拿具体候选确认手感；等待用户回复不伪造通过。

只有本切片全部必需行为与验收通过、阻断缺陷为零、用户必要确认取得、产物及记录保存后，才声明“六向站立完成”。这不代表原完整 UG 目标完成；其余 F 项保持原状态。未通过时准确报告 NG/BLOCKED/待确认，不能因为耗时或已出包而收成 OK。

最终报告回答：六向分别能做什么、原始问题修复结果、实际执行的验证与失败修复、尚未执行项、UG/UIE/CHB 取用路径及哈希、安装/回退方式、用户还需确认什么。过程中优先报告可观察行为和可用产物，不用回执数量或准备工作量代替进展。

## 7. hard 方法级审查与执行补充（2026-10-15）

本节是 hard 只读审查的规划采用结论，不是已实施修复。hard 实算 R13 receipt SHA-256 与第 4 节一致；核对执行树 `D:/WI - Dev Workspace/ug-mainline-integrated-build-20261015-r15/UniversalGravitation` 与 R12 候选中 `PlayerPhysicsStep.java`、`PlayerPhysicsServer.java`、`PlayerPhysicsServerIntegrationTest.java` 三文件逐字节相同，未重算全部 478 文件 canonical。以下行号只定位这份冻结源码，修改后以符号为准。未启动构建、游戏或修改产品源码。

### 7.1 R13 竞争假设与责任边界

已通过的前置是实际 Creative、NEGOTIATED_INTENT ON、REPLACE binding/APPLY owner、non-cardinal Q 进入 Free、首次 STATE 无 Cardinal reference，以及真实 `sampleCurrentFreeEnvironment` 零加速度和空 face/boot/floor 证据。失败发生于消费姿态请求后，没有新 INPUT 的下一 tick。5699 后的 Free 类型、revision、scope/owner 断言仍未执行。

| 环节 | 冻结源码方法链 | 规划采用的判断 |
| --- | --- | --- |
| 进入物理步进 | `PlayerPhysicsServer.advanceOwned` → `PlayerPhysicsStep.stepOwned` → `stepFreeOwned` → `sampleFree` → `freeReferenceEvidence` → `freeCardinalSelection` | 同一 tick 的真实 evidence、owner 与模式必须对齐 |
| 选择参考 | `freeCardinalSelection`（约1261–1307）优先 environmentFrame、savedManual、新 Q；non-cardinal 新 Q 返回 empty；无新 Q 且无 standing target 最后仍构造 SAFE_NATIVE/Y_POS | 与 R13 高度吻合的源码根因候选，但尚未取得失败 tick 的精确分支轨迹。previousEffective 只用于 heading，不应凭此产生当前参考资格 |
| 安装状态 | `FreeReferenceModePrepared.prepare` → `FreeReferenceModeFinalizer.install` → `installCommittedOwnerReference` | 此链会安装目标几何和 owner reference，不只是 packet 标签 |
| 对外发布 | `PlayerPhysicsServer.publishAccepted`（3139–3157）发布 snapshot 与 `travel.cardinalReference()`；`PlayerBodyReference.FreeReference.cardinalState` 本应 empty | 禁止仅在发包处强制删除字段，使真实几何错误被隐藏 |

首轮验证通过现有测试入口或测试观察能力同时观察 tick/scope/revision、G 资格、ownerReference 类型、实际 shape/Q/尺寸、standing presence/target、manual/pending、所选 source/frame 和 STATE。先分清“真的安装 Cardinal”与“Free 附带错误参考”，避免新增永久诊断框架、反射或泛化日志。

最小产品修复优先限定 `PlayerPhysicsStep.freeCardinalSelection` 及确有必要的调用参数：AVAILABLE 证据没有当前有效 target、有效 manual 或明确允许的转换请求时，返回 `Optional.empty` 并沿既有 Free 路径。不能按 acceleration==0、Personal G==true 或无 INPUT 单独判定，因为场抵消仍可能有合法站立参考，G 开启也不排除合法参考。

原生恢复独立核查 `PlayerPhysicsServer.beginNativeRestore`、`restoreLockedBodyForNative`（2136）、`restoreFreeOwnerForNative`（2288）、`mintNativeRestoreAuthority`（2531）及 ACK 链。不得全局删除 SAFE_NATIVE。G_OFF、显式 unlock、骑乘/睡眠/旁观及自然场参考各有资格和恢复语义；普通 Free 路径若确需 native fallback，须由明确原因/资格触发，不能将缺乏参考冒充恢复授权。

相邻待决点：exact cardinal Q 当前也使用 SAFE_NATIVE。纯 view Q 是否足以授权物理 Cardinal 转换须对照合同明确；不为本失败顺手改变语义，也不默认为合法。出现 view intent 与 accepted physical 冲突时直接交 hard 核对。

### 7.2 首轮必须执行的验证

1. 原 `purePersonalZeroGWithoutAnyReferenceRetainsSupportedFreeCapsule(Path)` 从入口执行到底，包含 5699 后实际 shape、revision+1、scope、owns/drivesMotion 和实际几何断言；增加连续无新 INPUT 多 tick，不只验证一个 tick。
2. 对照自然参考恢复、有效场等强抵消、六方向环境参考、savedManual 恢复/清除、显式 unlock/G_OFF、失源和 reference unavailable 安全保持。
3. 对照旋转/目标占位受阻、resize 球形/非法胶囊尺寸、重入 correction 与预算只消费一次。
4. 复用现有相邻测试：`ownedFreeNativePoseFixedGetterRestoresAnchorAndConsumesEnterExit`、`nativeCorrectionAndRecoveryDiscardAttitudeIntent`、`successfulFreePoseRestoreStopsAtReentrantNativeCorrectionAndContinuesAfterConfirm`、`freeNativeConfirmRechecksAuthorityAfterSameBooleanSleepingGetterReentry`、`freeExplicitUnlockConfirmsActualNativeCorrectionToSafeCardinal`、`survivalInputCannotGrantFreeVerticalPropulsion`。这些是应执行清单，不是本次已通过清单。
5. 失败用例和受影响回归通过后执行完整 build/check/包装门，保存新候选；不重复无关旧矩阵，不将 headless 提升为 DS/GPU。

### 7.3 按生产调用链集成与并行责任

P0 先消除 R13 阻断；P1 贯通“真实场/磁性装备采样 → 参考选择与独立受力 → 单 owner 六向几何/运动提交 → STATE → 客户端镜像 → UIE 呈现”；P2 补齐六向运动、尺寸与换向安全。不能先把各模块分别标 SOURCE 完成，再无限等待集成。现有局部记录不证明全部生产接线只差测试。

| 工作范围 | 唯一写入边界与接口约束 |
| --- | --- |
| A 物理主链 | Mainline 指定一名 writer：`PlayerPhysicsStep`、必要 Server/finalizer 和巨型 `PlayerPhysicsServerIntegrationTest`；同一测试文件不交多名 writer |
| B 真实来源 | `FloorWorldFields`、`PlayerEnvironmentSampler`、`BootStandingEvidence` 与专属测试；修改 sampler 前与 A 冻结接口和实际证据语义 |
| C 客户端 | mirror、UG UIE adapter 与独立测试；协议修改先与 A 定边界，UIE 仓由其对应 owner，CHB 消费已核实 API |
| D 验收准备 | 只读场景矩阵、隔离实例、依赖/配置核对；用户执行入口生效前不启动实例 |

具体 writer 身份与文件清单在实际派发前确认；此表不是对任何现有 writer 的自动接管。可用候选出口仍按第 6 节执行，不等待 F08 正式性能。

### 7.4 六方向逐行记录格式

运行记录使用 X+、X-、Y+、Y-、Z+、Z- 六行；每行分别核验真实来源/合力、站走跑跳踏步潜行、真实尺寸/脚眼、进出源/受阻/Free/native、STATE/本地/tracking/相机交互，以及无 YSM/关闭/1.1.9 开启的必要呈现。每行分别列自动化、专服、真实 GPU、双玩家、用户确认五类实际结果，缺失填 NOT_RUN，不能压成一个总 PASS。

跨行共享场景单列：传送 ACK、重生/换维度/重连与旧 scope、跳跃防重吸附期间持续施力、Creative/Survival 磁性资格、X/Y/Z 双面两端、低 TPS 与未加载边界。共享测试不抹去六个方向的独立结果。重编任何组合成员后更新候选身份并按影响面重验。

### 7.5 主动升级 hard 的最迟时机

首次出现 shape/owner/reference/STATE 真值矛盾，或修复涉及 sampler/step/native/协议不变量时即咨询；一次责任修复后同首因仍 NG，或失败位置迁移却缺少闭合解释时就升级，不等连续多轮。数学、旋转包络、预算、重入原子性、UIE/CHB 合同冲突和验收范围争议同样直接提交。

问题包固定包含候选身份、最小真实入口、预期/实际、失败证据、方法链、竞争假设与待裁决问题。Mainline 记录采用结论及落实验证；独立 reviewer 仍保持独立。本节补充第 5 节，并将“主动介入”提前到首次实质性矛盾，而不是只统计失败次数。
