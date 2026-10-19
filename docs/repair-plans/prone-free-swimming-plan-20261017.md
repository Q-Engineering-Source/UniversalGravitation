# 趴卧与自由方向游泳：方法级规划草案

状态：**DRAFT / 待用户确认增量范围与控制体验；本文件不授权实施。** 2026-10-17。

最新工作顺序：新增趴卧 / 自由游泳继续延期。SOURCE19 / saved candidate16仍为当前新版用户手测候选，WIP / CANDIDATE / KNOWN_NG，原清单 / save-time receipt / 产物不覆盖，旧14保历史，无部署 / 世界改动。SOURCE26夹具scoped-region-close曾SOURCE_NG、未执行；SOURCE27仅补修逐entry聚合边界，primary / suppressed保留，其余609同26、全部产品同25，c18 / V19不变，27独审无确定SOURCE blocker、26原报告保留。局部反射例外限定actual private map无public scoped API及canonical目录直接.mca / 同cache monitor / close成功才remove，避免全局flush与FML test bridge。run174只重run173失败getter1例，含teardown实际1P / 0skip、compile3P；其余5P未重复，run173 cleanup NG保原来源。原strict sleep已以run175启动，尚无结果 / strong receipt / full build / fresh native39 / candidate18交包或GPU / 真实验收OK。相机未合入，相机 / NFR / 水体合同不变，水文 / 新泳姿 / 无关F08和姿态背包 / 顾问继续延期。本Docs仅增量同步，状态以唯一账本为准。

Mainline 唯一集成 owner：[01a13369-2a8e-7142-9f84-6f52ea08b465](codex://threads/01a13369-2a8e-7142-9f84-6f52ea08b465)。本草案由现有 XHigh / Docs 会话唯一写入；讨论对象为既有 [hard / Astra 顾问会话](codex://threads/01a0de5a-21a2-7960-970e-d19bfb0c6c44)。进度仍只维护于[推进计划](../progress/ug-chb/推进计划.md)，不在此建立第二套完成计数。

## 1. 已确认要求、建议与本轮权限

| 性质 | 内容 |
| --- | --- |
| 用户已明确 | 游泳使用和无重力一样可自由朝向的身体胶囊；胶囊总长度保持，长轴随身体姿态旋转。不能把 native swimming AABB 的短高度当身体胶囊总长。 |
| 用户已纠正 | “内切”不等于把身体压成近球；SOURCE01–04 的短 H sphere 路线撤回。内切参照须明确，不能假称长体包含于 Aqua 的世界 Y 短盒。 |
| 用户提出、尚待具体化 | “吸收水游技艺，自己实现趴卧、胶囊体自由方向游泳”。这是本次范围讨论输入，不是全模组移植、移除 Aqua 或双部署支持的批准。 |
| 本DRAFT权限 | “应当先规划，与 xhigh 和 astra 探讨”的规划记录保留；本DRAFT新增趴卧 / 自由游泳仍延期。既有移动 / 飞行已另获修复、必要构建和测试候选授权，不能用本DRAFT的延期阻止该窄范围推进。 |
| 用户已确认相机方向 | “替代原版所有视角，不做原版与NFR相机切换，全程采用六自由度相机，自行约束角度以贴合正常情况下的原版，后续入水、G的开关也会更平滑。”统一常驻Q，通过角度约束及约束切换表现正常视角与自由姿态，不再将“解锁”含义或相机二选一列为待决。 |
| 用户已确认未来水体接口要求 | 真水判定不能锁死原版水体，后续需可接水文系统、水位水池、可变海平面；届时可用指定长宽高的自定义水体区域测试进出水，类似区域重力测试。这是未来接口 / 验收扩展要求，不代表当前已实现或授权本轮开发水文。 |
| 不重复询问 | 不重开既有整个六向目标的三档预期，不再问已经决定的“胶囊总长是否保持”，不恢复旧额度分档或旧自动实施任务。 |

总长保持的本次作用域是自由方向游泳，以及用户若选入的趴卧长体。**不得据此把所有潜行、睡眠、滑翔的既有物理尺寸改成一律不变。** 共享数据拆分会触发这些模式的相邻回归，其原合同仍有效。G、hover、磁力 / 重力地板、零 G 推力资格及姿态背包均不顺手重定义。

本草案把“建议进入本轮的主干”与延期项具体列出，待用户选择后再成为增量合同；实现、测试、NG 修复重测、候选保存与真实验收都不能被设计文档代替。

## 2. 冻结输入与撤回证据

| 输入 | 已知身份与用途 |
| --- | --- |
| 球形方案前可靠父 | `D:/WI - Dev Workspace/ug-free-impact-consumer-overlay-20261017/source06/source`；full610 manifest `0F4FBAEC06C9C2066D5C12957B9E4492FC6C4C52B6588B43CD93E6D34EACB763`。后续若获准实施，应先从此父做明确差量组合，不把已撤回 sphere 的 21 项产品改动默认作为新基线。 |
| 撤回方案 | `D:/WI - Dev Workspace/ug-swim-inscribed-capsule-overlay-20261017/source04/source`；full611 `7514A2F583A08EFBFEF8E3EFE935AE90B1810AD221A08CF1AE71FCB76E3B56B4`。138 的 2393 PASS 仅保留旧输入事实，不证明本草案的长体合同。 |
| 139 原始运行 | `D:/WI - Dev Workspace/ug-mainline-candidate12-regression-build-20261017-138/UniversalGravitation/evidence/run139-raw01`；16 项 manifest `A794D78E2EE43291BFD069AA73EE59DAE88A1FCC421FD7618BAB63391F186DE5`。raw25 实际 21 PASS / 4 failures / 0 abort / 0 skip，已自然结束、LEASE RELEASED、Java 残留 0；没有后续运行授权延续。 |
| 候选取用 | 实际saved candidate.16仍为当前新版用户测试候选；[交包身份与限制](../evidence/flight-movement-recovery-20261017/candidate16-build01/manual-test-candidate-delivery.json)及[清单](../evidence/flight-movement-recovery-20261017/candidate16-build01/TEST-CHECKLIST.md)保原保存身份，WIP / CANDIDATE / KNOWN_NG、限独立实例移动 / 飞行手测。用户目录JAR为2,604,483B、SHA-256 8A2C9836BA00D45C14C8F9AF193B4D45DA387773ADA3D4A024A791B0ACA8824F；SOURCE27仅夹具修复、产品c18同25，run174原失败getter含teardown实际1P，其余5P保run173来源；run175原strict sleep运行中，无candidate18交包 / strong receipt / full build / fresh native39 / GPU或真实验收OK，旧NG保留。UG双端同一16 / Protocol19，CHB084 / 客户端UIE087沿原哈希；旧12 / 13 / 14仅保历史、旧UG163 TEMP .15不可分发，无运行服 / world部署，本Docs未复制 / 删除 / 替换产物。 |

本次 Docs 实读 source06 定点源码、139 completion / RESULT / 原始 result 指针及失败栈；没有重跑。IntelliJ 当前项目索引可用，已定位符号与一层调用关系。冻结目录不在 IDE project / library / SDK roots，IDE 明确拒绝读该目录，故冻结内容用文件定点读取。当前主树 `CapsulePlacementMath` 与 source06 字节不同，不能把当前索引行号套到冻结父；以下行号均标明 source06。

## 3. Aqua 参考范围与实际安装身份

参考源码固定为 embeddedt/aquaacrobatics 的 `4c96ac731c837979abc329942bb0d59df5ce5d96`，许可为 [Unlicense](https://raw.githubusercontent.com/embeddedt/aquaacrobatics/4c96ac731c837979abc329942bb0d59df5ce5d96/LICENSE)。直接参考姿态输入、转换条件与兼容职责；六向胶囊物理必须按 UG / CHB 的真实证明重做。

只读核得服务器文件 `D:/0_Minecraft_Server/Wi_Server/Q-Eng-Server/Main-RingBeltSpaceStation-crl_0.6.8/mods/AQ-AquaAcrobatics-1.15.4.jar`，182194 bytes，SHA-256 `506EE8E264C347FAD33302183B8F4DADDB05D5E5F75C0B6D833266C886E127CE`。这只绑定此服务器 JAR 身份；尚未核客户端 JAR、安装配置或该 JAR 与源码 commit 的逐类一致性。

| 参考入口 | 本次用途与限制 |
| --- | --- |
| [EntityPlayerMixin](https://raw.githubusercontent.com/embeddedt/aquaacrobatics/4c96ac731c837979abc329942bb0d59df5ce5d96/src/main/java/com/fuzs/aquaacrobatics/core/mixin/EntityPlayerMixin.java) 的 `updateSwimming`、`updatePose`、`recalculateSize`、`travel` | 参考入出泳、趴卧状态和水中运动行为。其原生 Y 向尺寸安装不能复制为自由长胶囊。还须避开其重叠尺寸、运动和眼点写入。 |
| [IPlayerResizeable.getSize(Pose)](https://raw.githubusercontent.com/embeddedt/aquaacrobatics/4c96ac731c837979abc329942bb0d59df5ce5d96/src/main/java/com/fuzs/aquaacrobatics/entity/player/IPlayerResizeable.java) | `getSize(Pose.STANDING)` 是水中无副作用读取姿态独立身体定义的候选入口，**尚非已接受 producer**。须核安装 API、缩放链及其真实结果，不临时站起来量身高。 |
| [ArtemisLibIntegration.updateSwimmingSize / getEyeFactor](https://raw.githubusercontent.com/embeddedt/aquaacrobatics/4c96ac731c837979abc329942bb0d59df5ce5d96/src/main/java/com/fuzs/aquaacrobatics/integration/artemislib/ArtemisLibIntegration.java) | 源码确实向高度属性安装游泳专用 modifier，并单独调整眼点因子。当前属性总值不能直接充当姿态独立身体缩放；不得重复乘缩放，也不得移除 modifier 后现场测量。 |
| [Keybindings](https://raw.githubusercontent.com/embeddedt/aquaacrobatics/4c96ac731c837979abc329942bb0d59df5ce5d96/src/main/java/com/fuzs/aquaacrobatics/util/Keybindings.java)、[PacketSendKey.Handler](https://raw.githubusercontent.com/embeddedt/aquaacrobatics/4c96ac731c837979abc329942bb0d59df5ce5d96/src/main/java/com/fuzs/aquaacrobatics/network/message/PacketSendKey.java) | 参考趴卧切换意图及服务端应用。现有默认键和启用状态不是本轮已批准的新键位；不能与既有 Z / C / Alt 控制相互抢占。 |
| [MovementConfig](https://raw.githubusercontent.com/embeddedt/aquaacrobatics/4c96ac731c837979abc329942bb0d59df5ce5d96/src/main/java/com/fuzs/aquaacrobatics/config/ConfigHandler.java) | 可作为兼容配置核对入口；不能只关 toggle 就认定其尺寸 / travel 重叠写入全停。 |

拟纳入主干：选定功能的姿态状态、输入、长体碰撞、基本身体模型与眼点吻合、真实水中运动、受限空间保姿，以及站立 / 既有零 G 的交接。高级动作混合、装饰、粒子 / 音效增强、水色 / 雾、物品和 Aqua 其他杂项延期；既有非相关功能不得静默丢失。基本模型位置正确性属于主干，不能全部归入“动画以后再做”。

## 4. 一次性用户决策包（建议，尚未确认）

身体胶囊总长保持已确认，不在此重问。下列选择只决定新增功能和可观察体验；Mainline 汇总一次请用户判断，不在每个切片重复询问。

统一常驻六自由度相机及按情境切换角度约束已确认；D2只保留延期中的水中身体意图 / 受阻体验细节，不再询问是否从原版相机切到NFR或“解锁”是什么意思。hard当前仅获camera路线只读咨询，该讨论不构成产品写入授权。

| 决策 | 推荐候选 | 备选及影响 |
| --- | --- | --- |
| D1 本次吸收范围与安装策略 | 当前整合环境保留 Aqua 其他功能，UG 单 owner 接管趴卧与自由方向游泳；明确停止 Aqua 对被接管玩家的重叠 pose / size / travel / eye 写入。 | 可选先只修既有长胶囊游泳、不新增趴卧；也可选目标环境移除 Aqua、UG 替代选定功能，但其余 Aqua 能力损失须明确。**不默认两种部署都交付，也不默认全模组移植。** |
| D2 水中身体控制（新增功能延期） | 在已确认的统一常驻Q相机内，look / 移动输入只产生有限转姿意图；bodyQ 经预测及服务端真实路径验证演进，正常头身跟随。正常原版式角度限制与入水 / G切换通过约束策略处理，不切换原版 / NFR相机。 | 独立身体按键、水中roll的具体输入及墙边转向被挡时的头身体验仍属延期功能细节，不能冒充相机主线尚待决定。bodyQ只能到已证明姿态，不能用视觉平滑掩盖穿模。 |
| D3 触发与阶段测试 | 参考现有 Aqua 习惯，以入水与冲刺意图激活自由泳；趴卧用可配置切换意图，矮通道中保姿，空间足够才起身。允许先验收真实趴卧阶段候选，再验收完整游泳；完整六向目标仍须最后收口。 | 可选择不同入出泳 / 趴卧触发，或两个功能完成后统一验收。推荐项的具体键位、速率与手感参数不是已批准值；阶段候选不等于完整目标 OK。 |

技术上的 profile producer、事务安排和字段去重由设计核验解决，不把每个实现细节都转成用户问题。若可信来源不足，只阻塞该接缝，不能据短盒猜尺寸，也不能把“明确拒绝接管”算作首次水中登录通过。

## 5. 数据职责：四类几何与姿态事实

以下类型与方法名均为**拟新增 / 拟调整**，不是声称源码已存在。优先在现有 module 和事务 seam 内实现，不增设平行 owner、全局尺寸 registry 或只有转发职责的接口层。

| 数据 | 职责与不变量 |
| --- | --- |
| `CollisionProfile(diameter, totalLength, sourceIdentity)`（拟新增） | 受信的物理身体定义，单位 m，`L >= D > 0`，保留数值有限域和 underflow 失败。趴卧 / 游泳改变 Q，不把 native pose 高度写回 L。真正合法缩放才重新取得 D / L 并走 resize admission；不取历史最大 H。来源身份绑定当前玩家、世界 / 会话、可信定义 / 缩放版本，不单凭客户端字段。 |
| `NativePoseRecord(rawWidth, rawHeight, rawEye, nativePose)`（拟新增或扩现有记录） | 保存 vanilla / Aqua / 缩放兼容所需真实原生记录，不能决定自由长胶囊 L；仍要校验安装值，不能为了分离而全面取消 native current 检查。 |
| accepted `bodyQ`、`lookQ`、`supportFrame` | bodyQ 控制身体长轴及模型；lookQ 表达视线；supportFrame 表达支撑 / 重力参考。责任分离不自动新增头身独立控制。趴卧时长轴在表面切线，腹侧对应接触法向，不能继续让 `bodyUp` 同时表示长轴和支撑上向。 |
| `BodyEyeAnchor` / transport recipe（拟新增职责） | `worldEye = C + bodyQ * localEye`，localEye 来自受信身体与姿态定义，实际算术计同 Work。raw native eye 只作兼容记录。transport 独立于 eye、胶囊端点和支撑点，必须确定派生与 TP / Mirror 规则；不使用“长体负端 + 短 native eye”。 |

几何定义：`r=D/2`，中心线半长 `h=(L-D)/2`，长轴由 accepted bodyQ 给出。这里“内切”可指随 bodyQ 旋转、由受信 D / L 定义的身体长盒，不是世界 Y 高约 0.6 的旧 native 盒。理想胶囊 AABB 半宽为 `h*abs(axis_i)+r`；实际存储端点 / RN、保守 capture bounds 和严格证明仍用现有数值实现，不将理想公式冒充存储证书。

原版有明确来源的站立基准可以进入可信定义，再按真实 scale 转换；禁止的是无来源地给所有身体写死 1.8，或把当前短 H / 历史 maxH 当基准。

## 6. 可信长径来源、首次接管与生命周期

拟新增的内部职责 seam：

```text
resolveTrustedCollisionProfile(actor, trustedScaleSnapshot)
  -> Resolved(profile, sourceIdentity) | Unavailable(reason, cause)
```

不接收客户端 diameter / length / radius 参数；读取过程不得切姿、临时站起、改 modifier、改 AABB 或触发一次探测性移动。

1. **Aqua 共存候选**：用 typed `IPlayerResizeable#getSize(Pose.STANDING)` 取得当前姿态无关定义的可能值，再核它与 Artemis / 其他已安装合法缩放提供者的组合。明确哪些 modifier 是 pose 专用，按有来源的读取计算取得 scale；不盲用总 heightScale、不双乘，也不删除游泳 modifier 改现场状态。实际安装 API / 字节码路径、服务端与客户端身份尚待验证。
2. **UG 替代候选**：自己的受信身体定义 producer 与当前合法缩放 producer。使用确定的原版定义是允许的；未知模组身体不能一概套默认长径。本轮只支持经确认的实际环境，不提前声称任意 Morph / 变身 / 未知缩放兼容。
3. **首次已游泳登录 / 换世界 / 重连**：必须能从当前可信 producer 独立重建，不要求先站起来，也不依赖前一连接的 accepted 长体。持久化记录若需要，只是经过玩家 / 来源版本 / 当前 scale 重验证的输入；旧 capability 和已提交 proof 不能跨会话复活。
4. **已有 accepted 长体**：姿态变更保持其 D / L；TP 重新定位不暗改尺寸，实际合法缩放同时取得新 profile 和 source identity，再证明新尺寸及完整过渡，失败保留旧 accepted 状态。退出游泳后恢复既有模式的原合同，不把此保持规则扩到所有 native pose。
5. **来源缺失**：明确拒绝此次 UG 长体接管，保留既有合法 owner / pose并返回可定位原因；不生成假球、不猜尺寸。这是失败责任，**不是常用首次水中重连验收已满足**；选定环境必须补齐真实来源后才可交付。

## 7. 已核实方法接缝与最小修改闭包

本表行号取可靠父 source06。`captureTrustedFreeResize` 是问题包中的功能简称；源码实际方法名为 `adoptTrustedFreeResizeProposal`，不可把简称当现有符号。

| 现有接缝 | 当前实读事实 | 拟修改职责与验证 |
| --- | --- | --- |
| `PlayerBodyShape.Dimensions`：`radius()` / `centerlineHalfLength()` / `eyeOffsetFromCenter()`；`FreeCapsule` 构造（17–60） | 同一 width / height / eye 决定物理半径、半轴及眼偏移。 | 枚举每个 `dimensions()` caller 的 geometry / native / eye 职责；Free 物理改读 CollisionProfile，native record及眼锚另归属。Cardinal 与原生其他 pose 的旧尺寸语义须明确保留，不通过默认“native=collision”伪迁移。 |
| `StoredRealizationLimits.derive`（49、54）；`StoredCapsuleGeometry.fromRecipe`（62）、`matchesRecipe` | 存储端点、半线、relation均绑定 Dimensions。 | 绑定受信 collision identity / D / L 和真实 C / Q；保原 RN / axis-plane / translation relation / 有限域，不能用 raw native H 换掉已接受半线。 |
| `CapsulePlacementMath.pose`（46、51）及 orientation / capture bounds | 读取 shape 尺寸作几何及存储包络。 | 仅消费物理 profile；继续用 caller 的 `StoredDyadic.Work`，不新增无收费计算、预算退款或另一个宽松 epsilon solver。 |
| `PlayerBodyPlacement` 构造（63–85）、`bodyUp()`（97）、`eye()`（147）、`physicalFoot()`（154） | Free eye 从长轴负端和 native eye 推导；bodyUp为Q的Y轴。 | 分离 body axis / support normal / eye anchor / transport；新 pose眼锚来自 accepted bodyQ。趴卧支撑另定义腹侧 contact，不能冒用长轴端 footSphere。 |
| `PlayerPhysicsServer.adoptTrustedFreeResizeProposal`（926；951–991） | controlled callback中观察的原生尺寸被用于可信 Free resize proposal。 | 区分 raw native pose 变化与真实身体 scale 变化；前者不能自动改 collision L。保 callback、owner、snapshot、connection / scope、world、correction / TP fence及原因传播。 |
| `PlayerPhysicsServer.NativePoseProfile.read`（1213–1219） | 只含 eye、蹲 / 睡 / 滑翔及 Artemis width / height属性，无 swim / bodyQ。 | 增加选定 pose 的受信语义与来源检查；raw 读数只作其职责，不能摄像机Q或当前总heightScale直接授权新物理身体。 |
| `FreeOwnerTransaction.geometryMatches`（549–569）及安装 / 恢复路径 | Actor native width / height / eye被要求与shape Dimensions吻合。 | 分别比较 accepted collision与stored实际胶囊，以及 native record与授权安装值；保bounds / transport / identity/current检查，不继续要求 collision L==native H，不全面取消native校验。 |
| `ModeTransitionPrepared.prepareCardinalSource`（67）、`prepareCardinalToFree`、`cardinalToFreeGeometry`（703–725） | 从actor短native尺寸建Free并走 `CardinalContainedCapsuleSource.prepare`。 | 真实 containment 才用旧快路。短盒到长体不满足时，走显式完整转换 path / target fit；不能伪Contained或把新长体当旧已接受几何。 |
| `ModeTransitionPrepared.prepareFreeTick`（428、432）、`trustedFreeResizeTarget`（602） | Free tick / resize进入当前事务规划。 | 在同一 Preparation 内准备有限pose变化、实际中心位移及合法scale，拒绝时无半安装。拟新增 `preparePoseTransition(...)` 优先复用此事务，不另建提交 owner。 |
| `PlayerPhysicsServer` TP实际几何重建（1574–1599及相邻Free路径） | 重新读actor尺寸、位置、correction / ID并发布accepted。 | TP重建同时校受信collision profile与native适配，验证目标几何，清理旧事务授权；新登录、死亡重生、换维度及重连独立绑定，不复用旧Q / profile / ACK。 |
| `PlayerPhysicsProtocol.encode`（594）、`decode`（852、857）、`putSnapshot`（1226）、`getSnapshotFrame`（1271）及 `PlayerPlacementCodec` | wire把width / height / eye、Q、实际stored geometry和proof绑在同一placement语义。 | 为新模型显式版本化，传已接受collision identity / 必要D/L、native record、bodyQ / anchor及原scope / revision。能精确派生的不重复发送；客户端只发意图。父VERSION18，撤回方案VERSION19，新版本号须以最终布局冻结，不能复用19冒充相同语义。 |
| `PlayerPhysicsClientMirror.prepare`（125；130–159）及 `PreparedMirror` 安装 / 回滚 | shape Dimensions直接落native width / height / eye，bounds / transport另从placement来。 | 完整预计算新raw适配 / collision / eye锚、绑定同revision再原子安装；失败保持旧actor与accepted。模型、眼点与collider不得跨一帧暴露半组合。 |
| `PlayerPhysicsClient` / Session、render mirror、UIE consumer | 本轮仅按Mainline提供接线事实定位，尚未逐条独立查全。 | 输入与预测使用已受信profile与意图；UIE继续持camera/input/picking/render注入。按新字段审完整STATE安装、ACK、呈现和远端tracking，不能只改Mirror就宣称闭环。具体新增方法名待设计收敛，不伪称已存在。 |

需要的内部职责接口候选（名字可在实施设计收敛时调整）：

```text
validatePoseIntent(accepted, intent, trustedEnvironment) -> AllowedIntent | Rejected
preparePoseTransition(tx, accepted, targetIntent, resolvedProfile) -> Preparation
resolveBodyEyeAnchor(profile, acceptedPose) -> local anchor
```

只允许匹配同一tx / accepted revision / world-current的Prepared提交一次。不得引入客户端可自签profile的入口，不给每个caller分配一套几何规则。

## 8. 转姿、趴卧支撑和水中动力学

建议状态职责分为站立、陆地趴卧、水中自由泳及既有其他模式；进入条件、支撑/介质和运动资格明确，不把“Free capsule”直接等同零G推力或水中权限。

- **转姿与起身**：认证从实际accepted起点到target的全部旋转、平移、resize和模式切换。只验target会漏头部弧线；只验绕固定中心旋转会漏支撑枢轴引起的center轨迹。可优先使用现有严格证明支持的有限顺序段，各段绑定真实前一证明端点、共享同一事务，全部完成才安装。无需先强制发明同时旋转平移solver；若顺序段不满足真实手感，再以证据决定路线。
- **枢轴建议**：起身保已验证支撑关系，生成真实center+Q路径；零G保身体中心旋转的既有合同。趴卧转姿具体采用支撑枢轴或中心路径尚属设计候选，不能先转再做未验证的补平移。
- **受阻**：空间不够保留旧姿态和明确的速度 / 接触责任；能趴行时继续正常移动，不自动缩球、强抬身体或每tick反复切姿。预算耗尽 / 未加载 / unresolved按既有失败语义处理，不扩预算或假通过。
- **腹侧支撑**：长轴端球不是趴卧的脚。需要按accepted bodyQ与supportFrame收集腹侧真实接触，决定可推进 / 摩擦 / 起身资格；六向表面均验证，不能因为侧躺就继承脚端 `.25m` 行走权限。
- **水中**：环境资格来自真实水判定，不能把原版水方块或单一Material硬编码为未来唯一来源。接口以后需能表达多来源水体、水池水位及可变海平面，并允许用指定长宽高的水体区域做进出水测试；这些扩展尚未实现，不纳入当前移动 / 飞行candidate.12的水文开发。新增游泳获准实施时，运动至少覆盖前后 / 侧向 / 上下意图、浮力、阻力、重力及入出水交接，水流 / Depth Strider等实际常用修饰按选定环境核对。几何姿态类似零G不等于复制零G动力学。Aqua原生world-Y travel不能与UG运动各执行一次。
- **头身与眼点**：相机统一常驻Q，以角度约束贴合正常原版视角，入水 / G开关由约束切换保持连续，不做原版 / NFR相机切换。look只产生身体目标意图，不直接赋accepted bodyQ；被墙阻挡时仅到已证明姿态。眼点始终取accepted bodyQ与可信局部anchor，不能跟未接受target Q穿墙。水中身体输入细节继续延期，陆地 / 六向表面沿既有头身限制。视觉插值只能在碰撞与呈现一致的范围内工作；已确认相机方向不自动授予全路线实施GO。

## 9. 客户端手感、网络与协议迁移

保持已确认“客户端手感优先”合同：对已建立的可信profile与许可意图，本地预测在首个可执行物理步立即处理移动、跳跃 / 游泳及转姿，不等待本次INPUT的服务端STATE返回。服务端验证、ACK与纠偏保其权威；客户端预测不得把自己生成的D/L写成accepted profile。无可信profile的首次接管先完成真实建立，不能通过猜尺寸伪造即时运动。

验收记录输入时间 / local tick、首个预测位移或动作、首个新server STATE时间；在约48ms真实FRP往返下检查首次反馈发生在对应回包前、且在首个可执行本地物理步。不把48ms当MSPT，不用固定双向延迟替代真实FRP / 抖动和用户手感。

STATE最小闭包是collision profile与存储几何身份、accepted bodyQ / pose、native适配记录、眼锚 / transport规则及既有scope / revision / TP fence。旧wire短H不能被新端解释成身体L；不兼容布局显式拒绝，任何兼容转换必须另有可信来源。若存在pose / profile持久化，再单列存储版本迁移，wire升版不能代替它。更新真实raw客户端fixture、packet宽度、decode预算、再编码一致性、正常FML与远端tracking；不能批量替换所有数字18或靠删断言过门。

## 10. 可实施切片与唯一写入责任（全部未获实施GO）

| 切片 | 完成内容 | 必要回归 / 出口 | owner范围建议 |
| --- | --- | --- | --- |
| P0 规划确认 | D1–D3与可信尺寸producer / native适配 / 眼锚 / 枢轴方案收敛，保留反例与未核点。 | 用户确认增量范围与控制体验；技术风险有明确验证入口。 | Docs唯一新规划写入，Mainline确认集成边界。 |
| P1 身体数据与协议闭包 | 从source06建隔离差量；分离物理profile / native record / anchor，接 geometry / server / wire / Mirror。 | 真实shape / codec / current / 首次已swim来源 / TP与缩放行为；未引入新功能前站立及既有Free回归。仅工程候选，不能叫游泳交付。 | kernel唯一物理 / server writer；Client owner可在明确不重叠路径改consumer。Protocol / snapshot等共享路径由Mainline指定一个writer，先冻结schema再并行。 |
| P2 陆地趴卧（仅D1选入） | UG-owned pose、腹侧支撑、真实入趴 / 起身路径、受阻保姿、眼点与基本模型。 | 六向表面、窄通道、转头 / 转姿受阻、低顶仍可趴行；同候选DS / GPU / 双玩家。若D3允许才提供阶段测试候选。 | 输入 / 呈现与物理通过冻结接口协作，不能同改巨型测试或Profile文件。 |
| P3 自由方向游泳 | 可信bodyQ意图、真实水动力、入出水 / 零G交接、实际缩放、TP / 重连、视线与身体模型。 | 下节真实路径及四个139 NG；实际选定Aqua共存或替代模式，不默认双模式。 | 分支集成仍由Mainline单一owner；跨CHB/UIE变更先明确边界，不能自行改库。 |
| P4 NG闭环与最终保存 | 修责任代码、复测失败及相邻影响，按同一源码 / 配置 / 依赖组合完整test、必要normalFML、check / build / package与原包装门。 | Dedicated Server、Real GPU、YSM、双玩家及用户FRP手感；保存身份绑定与已知限制。未过门只标CANDIDATE，不覆盖OK基线。 | Mainline最终验收与发布；本轮不启动任何一步。 |

不为并行而复制writer。几何、协议、Mirror共享数据强耦合，不能各自猜字段并行双写；测试作者与独立review应使用冻结输入。阶段后已通过且源码 / 接线未受影响的证据可做有范围继承，绝不能伪称当前新模型全量运行。

## 11. 最小可证伪验收矩阵（规划项，全部未执行）

| ID | 真实入口 / 反例 | 通过条件 |
| --- | --- | --- |
| B01 | 三组实际支持的合法缩放：站立进入趴卧 / 游泳、姿态旋转再退出 | 新模式内部D/L保持来源值；只有真实scale改变才resize；raw短H不写回L，无硬编码0.6 / 1.8。 |
| B02 | 首次登录已swim、无旧accepted长体，另做重连 / 换世界 / 死亡重生 | 可信producer独立重建长径，旧会话identity / Q / ACK不能复活；不临时站起测量。 |
| B03 | 真实Aqua standing-size入口与Artemis游泳modifier共同存在 | 精确区分pose因子与身体scale，无双乘、无现场删modifier；状态及尺寸raw读数无副作用。 |
| B04 | target空，但头部转姿弧被墙挡；支撑枢轴引起center移动 | 完整路径拒绝或只接受已证明前缀，不能端点空就穿墙；失败不露半姿态、同Work不退款。 |
| B05 | 六向表面入趴、腹侧接触、低顶起身失败后继续趴行 | 支撑基于腹侧及实际法向，不误用脚端球；原输入立即生效，无每tick抖动切姿。 |
| B06 | 水中上下 / 侧向 / 斜向移动、停止输入、入出水、水边受限起身 | 水资格、浮力 / 阻力 / 重力及模式交接真实；不复制零G免费推力，不自动改G / hover意图。 |
| B07 | 看天 / 看侧面、墙边body转向受阻、水平 / 倒置身体 | look不直接搬accepted collider；眼点取accepted bodyQ锚，正常头身限制成立，无扭到背后或模型先穿墙。 |
| B08 | TP实际SPacketPosLook、精确ID / nativeCorrection、STATE / Mirror / ACK及之后移动 | 全链使用真实包 / flags / ID与新geometry identity；不手造ID、不二次TP、不清pending逃验、不预setter高度。 |
| B09 | 真实resize、callback重入 / owner替换、budget不足 / current失效 | 原cause与suppressed保真、旧事务不能提交新owner；新尺寸全path / target fit；原预算门不放宽。 |
| B10 | 新wire布局、旧版本、非法客户端D/L或篡改profile / bodyQ | 正常字段往返raw一致，旧语义不误解码；不允许客户端签尺寸；decode费用与容量上限实际验证。 |
| B11 | 真实普通模型及YSM，六向 / 任意姿态，F3+B加实际胶囊调试覆盖 | 模型中心、身体长轴、眼点及实际胶囊对齐；native F3+B是AABB broad proxy，不能把它当胶囊表面。保留左上预览默认直立配置；远端第二玩家看到一致位置和姿态。 |
| B12 | 本地与约48ms真实FRP；走 / 跳 / 游泳 / 转姿和网络纠偏 | 首个可执行本地物理步反馈，不等对应回包；没有粘脚、抽搐、回弹或方向绝对化；记录实际输入 / 位移 / STATE时间，用户手感确认。 |
| B13 | 站立 / 六向潜行 / 睡眠 / 滑翔、raw native恢复、既有零G / G / hover | 保留各自旧合同，不把总长保持泛化到其他pose；共享字段拆分没有新回归。 |

自动化优先复用真实 `PlayerBodyPlacementTest`、`CapsulePlacementMathTest`、`CardinalCapsuleContainmentTest`、`PlayerPlacementCodecTest`、`PlayerPhysicsClientMirrorTest`、`ModeMovementContinuationTest`及server/raw客户端集成入口；新增profile / pose测试只验证真实职责 seam，不用源码contains或仅mock成功。

真实运行沿选定mod组合核验Fugue + Scalar、UG / CHB / UIE / YSM / Aqua身份及实际Mixin / AT / refmap。DS、真实GPU、双玩家分别记录，不用headless或测试桥代替。最终产物目录只放JAR；源码、manifest、NG及配置依赖快照保留仓库内。未完成全部必需门不得替换为整体OK，实际保存 / 部署权限另随后续Mainline GO，不从此DRAFT推导。

## 12. 139 四项已知自动NG：保留并纳入新组合

本次实读原始result的四个suppressed失败栈；下列仍为未修NG，不因球形路线撤回删除，也不预判新模型必然失败或自动修复。

| 方法：`PlayerPhysicsClientRawIntegrationTest` | 原始实际失败 | 后续责任与重测 |
| --- | --- | --- |
| `initialMobilityAdoptionRejectsHealthZeroProofAndAcceptsExactCanonicalRetransmission` | `assertCurrentUieProviders:1282` provider非null断言失败，经 `assertCanonicalSuspension:1123`。 | 新输入下核UIE provider生命周期 / 安装资格与真实cause，不删provider断言。 |
| `mobilityFreeToWalkRearmsSameCustodyWithAndWithoutPreviousPrediction` | 同provider断言失败。 | 与上项同根可能性须实证；保原两个入口，不能以其中一个替代另一方法。 |
| `teleportCorrectionAndPostCompletionAckRefusalSettleOnlyTheirExactDecode` | `assertFenced:1385`，在原方法492。 | 精确decode lease / TP / ACK责任与新profile布局共同回归，不能换成宽松成功。 |
| `serverUnmappedSuspensionKeepsClientCanonicalAndRearmsSameScopeWithRealFirstBoundary` | 原方法949，实际routed wire未携带预期held jump。 | 核真实输入→wire→suspension→rearm边界，保jump即时反馈和原assert，不伪sent / clear pending。 |

后续每项均需：绑定新冻结输入→实测→归因→修责任代码或纠正有合同依据的前提→重测失败及必要相邻回归。本轮不启动定位运行。

## 13. 顾问讨论收敛、修订与剩余未核点

| 讨论点 | XHigh初稿 / 顾问意见 / 当前DRAFT处理 |
| --- | --- |
| 模型与职责分离 | 一致同意稳定CollisionProfile、native record、bodyQ、lookQ、supportFrame分离。顾问指出raw native eye也不能继续当完整眼点权威，已加入可信局部anchor与transport职责。 |
| 首次已游泳来源 | 一致拒绝maxH / 短H逆推 / 无来源默认1.8。加入typed `getSize(STANDING)`待核入口及真实scale链；已确认Aqua会注入pose-specific Artemis modifier，不能盲读总heightScale。 |
| 水中视线体验 | XHigh最初举例“停止推进后自由转头”被收敛撤回为默认建议；顾问认可正常头身跟随更合当前范围。责任分离不等于新增自由观察；受阻时bodyQ / 眼锚必须维持accepted，边界体验列D2，不擅定速率。 |
| 全路径求解路线 | 顾问建议先评估现有证明支持的有限顺序段，避免把新同时求解器设为先决条件；采纳。若视觉 / 操控验收不合格，再基于真实机制讨论，不降低路径安全。 |
| 旧native pose范围 | Mainline要求本次长径保持不得泛化所有pose；已明确限定并将潜行 / 睡 / 滑翔设相邻回归。 |
| 分阶段交付 | 顾问建议先profile / wire工程片，再真实陆地趴卧、完整水游；作为D3候选，尚未得到用户对阶段出口的批准，不能降低完整目标。 |
| 模型身份与证据 | Mainline模型与强度未变；hard这次请求由工具显式指定 `model=gpt-6-astra`，`thinking`未覆盖，顾问确认收到该派发说明。宿主实际配置不可读取。此处记录本次请求的模型覆盖及消息来源，不以标题或模型名替代技术证据。 |

尚未核实：客户端Aqua实际JAR / 配置及与pinned源码的ABI；typed standing-size在全部选定scale组合的实际行为；新body/eye锚和多来源water资格与UIE的具体seam；Aqua渲染 / travel的精确停写接缝；真实转姿路径是否可由现有证明组合满足手感。统一常驻Q与角度约束切换已确认，不列入待决含义；多来源水体 / 动态水位 / 区域测试目前仅未来接口要求。其余技术门未标通过，不需重审全仓才能提交草案。

**趴卧 / 新游泳的本轮收口**：形成可审DRAFT和D1–D3选择，回传Mainline；讨论和文档完成不等于产品修复。上述新增功能等待用户对增量范围 / 控制体验确认，再进入相应实施切片。下节既有相机主线沿已确认方向规划，不重复提出原版 / NFR选择或整个目标的三档问题。

## 14. 下一常驻相机切片：同一rig、正常角度约束与连续交接

状态：**camera13 SOURCE01已冻结 / 源码独审有限接受 / camera145编译及headless5通过 / camera FML4与真实游戏未执行**。源码入口 `D:/WI - Dev Workspace/ug-persistent-player-camera-overlay-20261017/source01`；full613 / 8delta身份及145证据见[唯一账本](../progress/ug-chb/推进计划.md)，不把第2节游泳SOURCE06自动套作相机输入。初次仅SOURCE授权后，Mainline已另行推进145独立编译 / headless；此处记录已执行结果，不自动授权下一运行。相机不改candidate.12 / frozen141，不新增UIE相机实现。本Docs本轮只写规划与账本。candidate.12仍优先，本节不恢复短H sphere、F08、趴卧 / 新游泳或背包，也不设为candidate.12新交付前置。

### 14.1 核验来源与真实已有入口

依据既有[hard只读顾问](codex://threads/01a0de5a-21a2-7960-970e-d19bfb0c6c44)的定点源码评估及其对公开custom provider路线的纠正回执；后者取代“必须新增API / 把Q迁到UIE”的初步建议。Docs未独立重审产品代码。早期机制读取的UG根为本仓库，UIE根为 `D:/WI - Dev Workspace/NeoFontRender-UG-UIE085/addons/ui-enhancements/src/main/java/neofontrender/addons`，当时未冻结为候选；下表说明真实方法接缝，不单凭名称认定安装身份或完整语义。camera源码独审只有限接受于**精确具custom-frame / native-origin修复的UIE实物**，旧主树即使同API4也不等价。UIE087旧2500输入未展开submodule的NG及历史086结果保留；补全vendor的2818输入已在146 run03正常remap exit0，实际JAR独审1507 entries / 15 changed / 1492 equal、无增删。087实物已随移动候选保存，精确SHA与差分回执见唯一账本及交包记录；camera下一FML4运行仍须绑定该精确身份，当前尚未执行，不据移动交包或UIE构建称相机运行通过。

| 责任 / 文件 | 已有真实方法或字段 | 下一切片如何使用 |
| --- | --- | --- |
| UIE `api/camera/CameraProvider.java` | `acquire(CameraRigRequest, CameraProviderContext)`、`frame(CameraFrame, float)`、`ownsView()`、`requiresUiViewProxy()`；另有 `id()`、`priority()`、`supports(request)` | 复用provider仲裁及frame权威呈现合同。view proxy仍由UIE管理，UG不另改render entity。 |
| UIE `api/camera/CameraApi.java` | `registerProvider`、`registerEyeAnchorProvider`、`registerEntityPickGeometryProvider`、`acquire(CameraRigRequest)`、`getFrame(float)`、`isRenderOverrideActive`、`resolveCollision` | acquire先询问provider的supports / acquire，成功session记录为LoggingSession，才fallback backend；getFrame选择非null provider帧、记录ownsView并经modifier / observer→backend.applyFrame，已有base identity / sampleId / partialTicks缓存。可用公开custom provider，不须先新增API；resolveCollision的实际签名 / 行为须在冻结实现输入核实。 |
| UIE `api/camera/CameraRigRequest.java`、`CameraSession.java` | `DRONE_ID/drone`、`FREE_LOOK_ID/freeLook`、`CURSOR_LOOK_ID/cursorLook`、`SHOULDER_ID/shoulder`；自定义ResourceLocation / priority构造器；`isActive()`、`close()` | 缺口是尚无内建常驻受约束玩家rig合同和实现，自定义id本来可表达请求；无需复制CameraApi。session lease不等于物理owner。 |
| UIE `camera/CameraApiBackend.java` | `getFrame`→`CameraRuntime.frame`、`applyFrame`→`CameraRuntime.applyEvaluatedFrame`、`acquire`→`CameraRuntime.acquire` | 保留唯一backend写入通道；本轮只核桥接，不能声称runtime acquire / frame所有分支已审。 |
| UIE `camera/CameraRuntime.java`及 `CameraPresentationPolicy` | `applyEvaluatedFrame`、`usesDirectViewProxy`、`usesDetachedThirdPersonPresentation`、`suppressesVanillaThirdPersonDisplacement`、`beginNativeCameraRenderSample`、`clearNativeCameraRenderSample`、`captureNativeCameraAnchor`、`acquireApiViewProxy`；`usesNativeCameraAnchor` | native-anchor条件为override、authoritative、非builtInRig、非directViewProxy；复用pass样本与第三人称位移抑制。proxy获取会保存thirdPersonView并设0，不能据抑制列表未列proxy就断言必然双位移；本切片优先无proxy的player-anchored路线。 |
| UIE `camera/CameraRenderBridge.java` | `submitQuaternionView`、`submitNativeCameraView` | 复用viewBasis矩阵提交、front补偿及Forge pitch / yaw / roll归零，防止后续Euler重复旋转。睡眠 / 骑乘矩阵前缀尚未完整审过。 |
| UG `core/client/camera/RevoUiSixDofCamera.java` | `install(GravityEntityBinder)`；注册 `FlightApi.registerCameraTrackingProvider(adapter::tracking)`、`SixDofBodyPresentation.registerModelUpBodyPoseProvider(adapter::bodyPose)`、`CameraApi.registerEyeAnchorProvider(adapter.eyeAnchor)`、`CameraApi.registerEntityPickGeometryProvider(PlayerRenderPoseMirror.pickGeometry)`，另有capability / maneuver / input及boost / physicalControls / walkingViewBasis | 已有身体 / 眼锚 / picking入口继续供事实。当前install未注册常驻CameraProvider；下一步用现有registerProvider / custom request补入，不把不存在的新API写成已接好。 |
| UG 同类及 `SixDofCameraActivation.java`、`SixDofCameraSession.java` | `isActiveFor`、`deactivateInactiveCamera`、`suspendControls`、`clearAttitude`、`isDriftAuthorized`、`shouldActivate`、`update`、`unloadWorld`、`clear` | 当前optional zeroG门含personalZeroG、远端custody、sleep / dead / spectator / debug排除，且让位drone / freeLook / shoulder。迁移须分相机存在性、控制许可和推进资格，不能整体改成return true。现session以player / world对象identity界定，不冒充完整worldepoch协议。 |

### 14.2 唯一owner与拟修改 / 新增接口

**UG现有orientation是唯一mutable viewQ / lookQ积分与约束owner，输出不可变相机帧；UIE是唯一最终renderer。UG accepted bodyQ继续是物理事实。** 不在UIE再造一份相机Q与UG互相回写，不新增UG相机Mixin / GL / render entity写入。UIE公开custom CameraProvider路径已能承载本方案，不把Q迁移或新增CameraApi设为前置。drone / freeLook / shoulder仍须经UIE公开session与输入仲裁处理，不能借迁移自动新增自由观察功能。

下列新名字均为**拟新增 / 拟调整的方法级接口**；实施者先按冻结源码核签名和归属，可以在同等职责下沿用更合适的既有类型，不建立平行registry或每层转发的Service。

| 文件 / seam | 拟调整内容 | 可观察输出与责任边界 |
| --- | --- | --- |
| UG `RevoUiSixDofCamera.install` / 新内部 `PlayerConstrainedCameraProvider` | 用现有 `CameraApi.registerProvider`注册；以自定义 `ResourceLocation`及现有 `CameraRigRequest`构造器请求session，provider实现现有 `id / priority / supports / acquire / frame / ownsView / requiresUiViewProxy`。 | 无须新增PLAYER_ID / player()便捷工厂。一个player / worldepoch最多一个有效玩家session；requiresUiViewProxy=false；非pass-through权威frame、ownsView=true与有效session必须一致，不能只拿到session就宣称接管成功。 |
| UG 同provider的 `frame(CameraFrame fallback, float partialTicks)` | 从本样本已取样的orientation、受信eye、perspective与约束结果构造不可变CameraFrame；复用fallback的sample identity / context。 | 不在frame读鼠标 / 积分，不递归调用CameraApi.getFrame来构造自身frame；第三人称输出包含遮挡后orbit的最终position，不能只回eye。 |
| UG 现orientation及其内部约束职责 | 拟设 `updateConstrainedViewInput`、`applyViewConstraints`、`beginViewConstraintTransition`、`advanceViewConstraintTransition`、`applyAuthoritativeLookCorrection`、`resetCameraViewSession`，优先收在现有客户端camera模块。 | 保留唯一积分Q及约束状态 / 会话identity / 已处理输入和correction身份；accepted bodyQ独立。约束可用内部不可变 `PlayerViewConstraints`，不因此制造新跨仓API。 |
| 现有UG / UIE输入真实入口（handler名待定点核验） | 保留既有UG相机输入一次积分，接线到上述约束流程；经真实公开仲裁判定当前输入owner与GUI焦点。 | 同一delta只积分一次；一帧多次getFrame、多个pass、tracking回调不得重放。不能仅凭CameraApi保存LoggingSession宣称全局输入已互斥。 |
| UG `RevoUiSixDofCamera.isActiveFor`、`SixDofCameraActivation.shouldActivate`及清理方法 | 分相机存在性、身体控制、推进许可；保留受信eye / body / pick接口，正常与G属于同provider / session。 | 不以G或Flight cameraAllowed全开换接管；personalZeroG与装备资格仍约束推进。避免常驻高优先provider与仍活跃builtInRig叠跑破坏native-origin分支。 |
| UG `SixDofCameraSession.update / unloadWorld / clear`与权威纠正入口 | 窄补worldepoch / player生命周期关联；在真实纠正应用边界调用本模块的 `applyAuthoritativeLookCorrection`，包处理方法名由Client owner在冻结输入核实。 | 旧world / decode / session纠正不作用于新会话；纯位置TP不清Q；含权威朝向更正真正应用一次，不为平滑丢弃。 |
| UIE现有公共API的条件性窄修 | 默认复用，无预设产品改动。若真实native前缀 / 仲裁缺口不能用公开接口解决，提交最小反例和确切缺口，再交UIE owner窄修。 | 不能由缺便捷工厂推出须改API / 迁Q，不能调用UIE私有rig或为此复制渲染管线。 |

内部 `PlayerViewConstraints`至少明确normal / free等策略、参考frame来源及revision、头身允许关系、normal角度限域和重上锁过渡状态；不携带第二份积分Q。normal采用当前patched vanilla的实际合法角度 / 灵敏度语义为基准，实施前定点核实原版入口，不凭印象另写一套鼠标曲线。相机约束不签署物理bodyQ、碰撞路径、环境或推进资格。

### 14.3 三视角、约束与头身关系

1. **普通第一人称 / 后第三人称 / 前第三人称**：同一持久provider / session与lookQ，F5仅换perspective与偏移。前视180度是展示变换，不写lookQ / accepted bodyQ；距离位移和native front补偿各应用一次。provider必须输出遮挡后orbit的最终position，优先核验并复用公开 `CameraApi.resolveCollision`及现有计算接缝；不调用UIE私有rig。若native位移已抑制却只返回eye，第三人称会没有后移，须作为明确反例。
2. **正常角度**：按当前支撑 / 重力frame约束正常pitch、roll及头身跟随，保持正常行走的WASD和脖子范围。极点附近不经Euler回转再积分；q与-q表示连续，切线参考不突然翻转。正常观察不能悄悄变成头朝背后的自由头转。
3. **G开关 / 重力来源变化**：仅更新约束和控制许可，不换camera owner、不清Q、不额外消费输入。G意图仍只由用户改变。重上锁采用显式过渡状态，从当前Q连续收敛到合法域；完成有有限边界，期间物理bodyQ仍取真实accepted值。不能同时承诺任意初态零跳变和当刻完全满足新约束；过渡时长 / 角速边界须在实际调试记录并验收，不能无限延迟上锁，也不为此重复向用户提原版 / NFR选择。
4. **身体与视线**：正常地板 / 磁力支撑延续正常头身跟随；既有获准的无重力非G按键转体才允许头转身体不转。bodyQ变化在原物理路径验证后供camera / 模型消费。此处不实现姿态背包auto / 反推模块，也不重定义其已确认合同。

### 14.4 生命周期与单个render sample

| 事件 | 同一writer内的计划策略 | 必须保留的反例 |
| --- | --- | --- |
| 普通TP只改位置 | 更新当前可信eye / origin，不重置lookQ；禁止以重建camera session掩盖位置变化。 | 当前world内连续TP及TP后第一步鼠标输入。 |
| TP / 服务端更正包含朝向 | 在真实应用边界按当前会话与更正身份执行一次，明确相对 / 绝对flags；新的Q继续受当前策略处理。 | 重传、过期decode、前视展示180、相对朝向更正，均不二次旋转。 |
| 骑乘 / 下马 | 使用真实当前坐骑 / 玩家锚与参考策略；同writer保留玩家look状态并有序恢复，不开第二个原生视角writer。 | 坐骑转向、下马、F5与G交接，无偏移叠加；源码矩阵前缀先核。 |
| 睡眠 / 起床 | 同rig切到真实床锚 / 方向约束并保存恢复所需状态；不复制未核实的原生旋转前缀。 | 六向环境的眼锚、模型与起床首帧，禁止借sleep排除静默回原版owner。 |
| 死亡 / 重生 | 死亡呈现由UIE明确策略；旧实体的eye / body事实不能供新实体使用。重生建立新player identity，在真实初始化点确定Q来源。 | entity id复用、旧STATE / correction到达、同world重生。 |
| spectator / debug | UIE同writer内按各自实际控制语义仲裁；不沿用旧optional zeroG的简单return false使常驻相机失踪。 | spectator目标切换、debug进出，输入无双消费，恢复状态有明确来源。 |
| world unload / 断线 / 换世界 | `resetCameraViewSession`结束匹配epoch与lease，清样本 / 输入残量 / provider引用；新world重新acquire。 | 旧world迟到回调不能关闭新session；不能只比较复用entity id。 |
| drone / freeLook / shoulder等高层rig | 经UIE现有公开session / 输入仲裁处理；常驻玩家provider受控暂停 / 恢复，UG不置render entity。 | builtInRig不能与声明接管的玩家provider叠跑；LoggingSession不等于互斥证明。暂停不累积鼠标，恢复不重放delta / 旧原点。 |

`beginNativeCameraRenderSample`在同pass取得一次已评估frame；渲染、picking、ActiveRenderInfo、frustum及相关tracking消费相同sampleId / perspective / eye / basis。复用 `applyEvaluatedFrame`、`submitQuaternionView / submitNativeCameraView`，并由 `suppressesVanillaThirdPersonDisplacement`避免距离二次叠加；`clearNativeCameraRenderSample`必须在pass结束及异常退出清理。不同tick / render / pass不得因partialTicks相同复用旧原点。具体ActiveRenderInfo / frustum / 鼠标Mixin方法名及调用顺序尚未全链核验，必须在实现前定点补齐，不能据本规划声称已经接通。

### 14.5 执行顺序、验收及文件分工

| 顺序 | 有限实施范围 | 同一冻结组合的出口 |
| --- | --- | --- |
| C0 当前优先 | 完成candidate.12已分配修复、失败链重测、必要恢复 / 构建与候选保存。 | 仅按该候选实际能力报告；本节不成为新前置。 |
| C1 统一rig与普通三视角 | UG唯一camera writer用公开custom provider / request / session，保留现orientation唯一积分，补内部约束、正常F5与遮挡orbit；UIE复用唯一最终renderer。 | 正常走 / 跳 / 飞、F5、极点转视、第三人称避障、YSM及原版模型无双旋转 / 双位移；首个本地输入即时反馈。 |
| C2 G及约束连续 | 同rig切换G / 支撑 / 自由策略，验证普通头身与已授权独立头转，重上锁有限连续过渡。 | G开关 / 近地 / 脱离、六向地板WASD、头身极点、零G飞行连续；不改变物理G意图或推进权限。 |
| C3 生命周期与纠正 | 在同writer覆盖上一表事件和真实TP / correction / epoch路径；不另建render owner。 | 实际包 / flags / identity、world替换、骑乘 / 睡眠 / 重生 / spectator / debug，以及GUI焦点切换，无旧Q / delta / anchor复活。 |
| C4 冻结、NG重测与交付 | 冻结相容UIE + UG + CHB及选定mod组合，跑受影响行为与协议 / 包装门，再DS、Real GPU、双玩家、真实FRP和用户手感。 | 每项绑定源码 / dirty快照、配置、JAR SHA与证据；原NG保留并真实重测。通过构建仅可标候选，全部必需运行与人工门满足后才称相应档位OK。 |

Mainline已指定Client owner为camera13隔离SOURCE writer，实际源身份 / dirty快照和验收层级以冻结回执及唯一账本为准，本文不再另分配writer或启动第二实现者。相机切片不另写UIE；已确认的early-handshake PLAYER路由缺陷由Mainline另行分配UIE087唯一窄修writer，不与相机差量混写。下一相机运行须绑定新087精确实物，不能沿用旧主树或把145 headless当camera FML通过。若再出现跨仓缺口，先核owner / dirty / 快照再窄分配；不新增用户审批门，不阻塞candidate.12。

必需反例集中为：**一次mouse delta / 多次getFrame与多pass；前视180不污染Q；普通极点 / F5 / G / GUI进出；TP位置不重置Q及真实朝向更正一次；worldepoch旧回调；正常头身与自由body许可分离；六向WASD及相机 / pick / frustum同sample；YSM与原版模型 / F3+B和真实胶囊调试对照；本地与约48ms真实FRP无粘脚 / 抽搐 / 回弹。** 鼠标一次消费目前只是必需实现与验收项，hard未全链审其handler，不能写成现有保证。

未来水体只预留可替换provider及动态水位查询职责；身体浸没与眼点入水分别观测，指定XYZ区域测试留待后续水体切片。此处不实现水文、趴卧 / 新游泳。姿态背包及非本地顾问仍按唯一账本“姿态背包延期门槛与非本地顾问入口”执行：现有问题修完并相应验收后再开始，不因本相机规划提前咨询或实施。
