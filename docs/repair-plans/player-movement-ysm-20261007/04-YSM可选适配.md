# 04 YSM 六向与完整身体旋转的可选适配

> 状态：PLAN / NOT_DELIVERED。本文仍是当前修复合同的一部分。2026-10-11 对齐快照只记录已存在源码和下一步集成缺口，不表示 F05 已完成。活动进度只写唯一账本 `docs/progress/ug-chb/推进计划.md`；本次仅更新获授权的四份规划文档，不写产品 Java 或实例；冻结前 dirty/untracked 状态留在 artifact receipt。活动进度仍只写唯一账本，不在本文件建立第二份勾选台账。

UG 提供物理/pose、会话和联机状态来源；UIE 提供模型矩阵、相机、输入与 picking 展示。适配不写玩家 position/AABB，不更改重力、G 意图或碰撞 shape；YSM 视觉 scale 始终只是显示比例。

## 1. 当前源码身份与证据边界

本节区分共享 UG 主树、Root464 冻结候选、UIE 源码与实际编译输入。路径/哈希摘要在 `C:/GradleCaches/artifacts/ug-ysm-plan-sync-20261011-r1/source-index.json` 中；未列出的 checkout、产物和运行环境不由这些身份推定。

| 身份 | 2026-10-11 快照 | 证据边界 |
| --- | --- | --- |
| UG 共享主树 | `D:/WI - Dev Workspace/UniversalGravitation-src/UniversalGravitation`，HEAD `e0bf44489d36cfd944a31a28625783313655333d`；工作树含既有 dirty 与 untracked 内容 | 仅作当前共享源码快照；本轮只做文档候选，不写共享树。不得把它等同于 Root464 或最终验收候选。 |
| Root464 隔离候选 | `D:/WI - Dev Workspace/ug-free-owner-components-integration-20261011-r1/UniversalGravitation`；冻结组合包 `C:/GradleCaches/artifacts/ug-free-owner-components-integration-20261011-r1/combination-r1/complete-source464-post-validation.zip`，SHA-256 `2781B0A976D0F84715B72D90B76DAFA2B21CB18F415C5100D67B92A813C4A5DA` | 本轮重算包哈希，并核对 4 个关键 loose 源文件与包内身份一致。Root464 的来源和受限测试证据不能被描述成正式 owner、FML/专服、GPU 或双玩家验收。 |
| UIE 当前源码 | `D:/WI - Dev Workspace/NeoFontRender/addons/ui-enhancements`，HEAD `173677bffbc139a7240886d5af8a15d9920c919f`；Java 源码位于 `src/main/java/neofontrender/addons/` | 当前方法/API 存在性以此源码快照为准。UIE 源码、另一 checkout 的编译 class、UIE JAR 是不同身份。 |
| UIE 编译 class 输入 | `D:/WI - Dev Workspace/UG-UIE085-dev-baseline-C8789F51` 是实际 class 输入目录，含 `FlightApi.class` 和 `MixinYsmPlayerBodyPose.class` | 它不是上述 UIE 源码 checkout。只可据 class/JAR 身份说明编译输入；不能由相同版本号或 FlightApi 文件相同推定 Mixin 源码相同。最终 FML/GPU 候选须绑定实际完整 UIE JAR 与所用源码/class 身份。 |
| YSM 现场 JAR | `D:/0_Minecraft_Server/Wi_Server/Q-Eng-Server/Main-RingBeltSpaceStation-crl_0.6.8/mods/YSM-yesstevemodel-1.12.2-release-1.1.9.jar`，948,533 bytes，SHA-256 `5DFE1A06E058FF7EB006918E4EBC60C18C450FFC441CAC1A5CFF0766251F1D60` | 本轮重算一致；提供 YSM 1.1.9 的实际结构基线，不代表已经在当前 UIE 候选下启动或绘制验收。 |

Root464 冻结 XML 索引的 SHA-256 为 `28FD0AED1A78F3453D90371205AB56EC539DF43C9E2FCA3909E8185D27CC1844`。本轮读取并核验其 40 份 XML：`test` 与 `chbRuntimeTest` 各 20 个 suite、各报告 219 tests，failure/error/skipped/hash mismatch 均为 0。所选 suite 没有 YSM、WalkingBodyPose、UieFlightBodyPose 或 PlayerRenderPose 专项；这里的 runtime 指 CHB runtime-test task，不是游戏运行。Root 的有限接受为 SOURCE + selected UNIT，不升级成 F05 行为或整体验收。Root465 仍在进行，不能计为通过。

## 2. S5.1 现有 API 与身体变换

当前 UIE `FlightApi.API_VERSION=10`，已有 `getApiVersion()`、`registerBodyPoseProvider(ResourceLocation,int,FlightBodyPoseProvider)` 与 `queryBodyPose(AbstractClientPlayer,float)`。provider 返回 null 表示无活动展示 pose，不是物理状态提交接口。

`FlightBodyPose` 已具有 `Basis.LEGACY_AIRCRAFT_FORWARD_Z` 与 `Basis.MODEL_UP_Y`、`attitude`、`basis`、可选 `headWorldAttitude`。旧 `FlightBodyPose(FlightAttitude)` 构造器保留原 API 9 descriptor 和 local +Z 语义；`modelUpY(FlightAttitude)` 与 `modelUpY(bodyWorldAttitude,headWorldAttitude)` 表达 model-up-Y 姿态。

`flight/PlayerBodyRenderTransform` 已有 `fromFlightPose(FlightBodyPose)`、`resolve(FlightBodyPose,boolean)`、`modelToWorld()`、`ysmRootAttitude()`、`ysmBoneAngles()` / `ysmBoneAngles(FlightAttitude)`、`ysmBoneAnglesFromMinecraftLocalDegrees(...)`、`minecraftLocalAngles()` / `minecraftLocalAngles(FlightAttitude)`、`modelPointInWorld(...)`。不要再计划新增 `fromPhysicalBodyFrame` 或另一份 transform record。

保持根矩阵顺序 `M_root = T_render × Q_body × B_ysm × S_ysm`：`T_render` 是 renderer 的 camera-relative translation；当前 converter 把 legacy +Z basis 乘 `Rx(π/2)` 转到模型 +Y 基，YSM 固定基旋转为 `Ry(π)`，并把视觉 scale 保留为模型显示比例，不并入物理形状。头部角由 `Q_body^-1 × Q_head` 转到身体局部方向，再映射到 YSM yaw/pitch 符号。现有双角 Head seam 不自动推广成 roll；也不将模型 visual scale 当作玩家物理宽高。

UIE 已有 `PlayerBodyRenderTransformTest`、`PlayerBodyRenderScopeTest`、`FlightBodyPoseTest`；UG 源码已有 `UieFlightBodyPoseCompatibilityTest`。存在不等于本轮执行。本轮只做源码与文档校验，没有运行 Java 测试。

## 3. S5.2 姿态来源、网络和当前差异

UG 已有 `RevoUiSixDofCamera.bodyPose(AbstractClientPlayer,float)` 与 `SixDofBodyPresentation.sample/sampleCardinal`，默认模型姿态使用 model-up-Y。共享主树的 bodyPose 非本地玩家分支已调用 `PlayerRenderPoseMirror.sample`；本地已接受 Cardinal 分支调用 `sampleCardinal`。不要把共享主树的 Cardinal 本地分支说成 Root464 尚未修的缺陷。

Root464 在本地 bodyPose 中增加 accepted `FreeCapsule` 分支：只有本地相机 Free mode 活跃时调用 `sampleFree(snapshot,currentAttitude)`；`acceptedFreePoseAttitudes` 以 snapshot 中的 accepted physical Q 作 body、当前 view Q 作 head。`reportActivePose` 对 accepted Free snapshot 也用相同 body 来源。该实现没有 previous-Q/partialTicks 输入；这本身尚未被判定为 NG，须先明确连续姿态插值、模式切换和历史断开时的合同。

Root464 的远端观察链须与本地物理姿态链分开描述：

- `PlayerPhysicsServer.presentationEligible` / `presentationBodyReference` 当前只接受 Cardinal；`presentationBodyReference` 依赖 `cardinalReference`。
- `PlayerRenderPoseTracker.poseState` 的 FREE 分支依赖有效 lease sample，报告 TTL 为 3 ticks。若 `acceptedReference` 不存在，body 取 lease sample 中上报的 body，而不是已证明由服务端独立发布的 accepted Free physical Q；若存在则使用 accepted lock 计算 body。因而现有事实不能写成“服务端已经向观察者发布 Free 物理 Q”。
- 有限审计确认这是 Free 呈现的只读 ABI/可达性前置；owner 尚未确认 accepted Free snapshot 的服务端身份来源，也未确认没有 live camera/report 时 head 的权威来源。尚未证明该情形在完整正式 Free owner 游戏路径可达，不报告为已复现游戏故障。

`PlayerRenderPoseProtocol` 已存在，`CHANNEL=ugpose1`、`VERSION=1`、`MAX_BYTES=160`；`Kind` 为 `HELLO/WELCOME/FREE_LEASE/FREE_POSE/POSE/REMOVE`，`Mode` 为 `CARDINAL/FREE`。真实 `Packet` identity/state fields 是 `kind, connectionToken, viewerRealm, worldRequestGeneration, playerId, entityId, dimension, worldEpoch, lifetime, sequence, physicalRevision, mode, cardinalFrame, body, head`；不另造身份字段。

已存在网络与生命周期入口：

| 类 | 当前已有入口 | 当前职责边界 |
| --- | --- | --- |
| `PlayerRenderPoseNetwork` | `initialize(...)`、`sendToServer(...)`、`sendTo(...)` | FML channel 初始化与明确方向的发送；不新增另一条发布通道。 |
| `PlayerRenderPoseTracker` | `install()`、`receive(Packet,NetHandlerPlayServer)`、`reconcileWorld(WorldServer)`、`onStartTracking(PlayerEvent.StartTracking)`、`onStopTracking(PlayerEvent.StopTracking)`、`onConnection(...)`、`onRegistration(...)`、`onDisconnect(...)`、`onWorldUnload(...)`、`stop()` | 服务端真实 tracking、握手/lease、有效期、scope/lifetime fence 与待处理实体生命周期。不存在计划中的 `publishAccepted`、`beginTracking` 或 `retire` 新 API。 |
| `PlayerRenderPoseMirror` | `install()`、`receive(Packet,INetHandler)`、`reportFreePose(Quaternion,Quaternion)`、`sample(AbstractClientPlayer,float)`、`onClientTick(...)`、`onWorldUnload(...)`、`onDisconnect(...)` | 观察者只读 render cache、客户端 report 与生命周期清理；不授予远端 movement custody。 |

`UnigravMod.preInit` 当前调用 `PlayerRenderPoseNetwork.initialize` 和 `PlayerRenderPoseTracker.install`；`ClientProxy.preInit` 安装 `PlayerRenderPoseMirror`。这是源码初始化 wiring，不等于当前组合的 FML Mixin/专服启动已证明。

## 4. S5.3 YSM 1.1.9 根和 Head seam

现场 JAR 的结构基线保留如下：

- `ReplacePlayerRenderEvent.onRender(RenderPlayerEvent.Pre)` 可取消原版 RenderPlayer 并直接进入 YSM renderer；`CustomPlayerRenderer.doRender(EntityPlayer,double,double,double,float,float)` 发布 `SpecialPlayerRenderEvent` 并进入自己的 geo renderer/layers。
- `GeoReplacedEntityRenderer.render(EntityLivingBase,IAnimatable,double,double,double,float,float)` 的 erased descriptor 为 `(Lnet/minecraft/entity/EntityLivingBase;Lcom/elfmcys/yesstevemodel/geckolib3/core/IAnimatable;DDDFF)V`。同类 `func_77043_a(EntityLivingBase,float,float,float)` descriptor 为 `(Lnet/minecraft/entity/EntityLivingBase;FFF)V`，是 YSM 自有根旋转 seam；sleep 分支独立，不能只 hook 父类 RenderLivingBase。
- `CustomPlayerModel.codeAnimation(...)` 从 `EntityModelData.headPitch/netHeadYaw` 驱动 Head bone。`SpecialPlayerRenderEvent` 只暴露 `getPlayer()`、`getCustomPlayer()`、`getModelId()`，没有根姿态 setter、partialTicks 或矩阵 post pair。

UIE 已存在 `mixin/MixinYsmPlayerBodyPose`：以 `@Pseudo`、字符串 target 和 `remap=false` 定位上述 renderer。`@WrapMethod` 使用精确 render erased descriptor；`nfrUi$renderWithSampledBodyPose` 在一次 YSM render 中采样一次。`nfrUi$replaceRootRotation` 用 `@WrapOperation` 替换 `func_77043_a`；`nfrUi$replaceHeadPitch` / `nfrUi$replaceHeadYaw` 包裹 `EntityModelData` 两个 `PUTFIELD`。这些注入约束 `require=1, expect=1, allow=1`。`nfrUi$mayOwnRoot` 只让 alive、非 sleeping、非 riding 玩家拥有 root；无 pose 时走原方法。

`PlayerBodyRenderScope.withSampledPose(Object,Supplier<FlightBodyPose>,RenderCall)` 在调用 provider 前 push 实体 fence，包括 inactive pose；一次 render 仅采样一次，try-with-resources 负责嵌套与异常时还原 scope。采样、render 或 hook 出错时不把失败转成成功：scope 由 try-with-resources 清理，原异常继续向上并保留 cause/stack；`MixinRenderPlayerFlightRoll` 对玩家角度的额外快照有 finally 恢复。YSM hook 本身没有对应的完整 GL matrix 栈异常恢复证据，仍须在最终候选记录为 NOT_RUN。

既有 `MixinRenderPlayerFlightRoll` 也复用该 scope；有完整 provider pose 时在原版 `applyRotations` 的 `HEAD` 使用 full quaternion root，不限于 elytra。无 provider pose 的已存在 elytra roll fallback 继续保留；因此旧文档“完整 Q 分支受 elytra gate 限制”已过时。最终候选还须证明 root quaternion 在其实际入口只应用一次；attached items、elytra 与 armor layers 继承同一 root，nameplate/shadow 和 render origin 保持各自 world 语义。源码行为仍待当前候选下的实际 Mixin transform 和真实渲染验证。

## 5. S5.4 可选加载与持久开关

`compat/YsmPoseConfig` 已使用 `config/neofontrender-ui-enhancements.toml` 的 `compat.ysmBodyPose.enabled` 键，默认 true，缓存读取，重启生效；不承诺运行中热切换。`YsmCompat implements ModCompat` 已注册到 `ModCompatRegistry`；其 `isActive()` 固定 true，是为了让 registry 对 YSM Mixin 执行 veto，而不是声明已安装 YSM。`UiEnhancementsMixinPlugin.shouldApplyMixin` 委托 registry。

`YsmClassAbi.inspect(ClassLoader)` 按 class resource/ASM 检查 ABI，结果为 `ABSENT/SUPPORTED/UNSUPPORTED`，并提供 version metadata；不靠硬链接初始化 YSM client renderer。非 client、YSM 缺席、ABI 不支持或配置关闭时不挂可选 Mixin；unsupported 会一次 warning，记录 mod id/version/ABI reason，并附带可用 cause。unexpected class resource read/parse 错误必须在责任边界明确失败，不能伪装为 ABSENT。

该兼容只影响 UIE client renderer，不修改 UG common 物理与网络权威，不让 UIE/YSM 类进入 UG common/server 依赖。纯专服必须能在没有 UIE/YSM 的组合下独立启动；需要用实际专服候选验证，不从 client source 推断。

## 6. S5.5 剩余集成主干与验收

本轮不重做 Root464 已有 Free 逻辑；继续按当前 Mainline 冻结候选、真实编译输入和最终被接受文件收口。顺序如下：

1. 在 FML/GPU 前固定同一个 UG 源码/测试与完整 UIE JAR/class 输入身份；区分当前 NeoFontRender 源码、UG-UIE085 class 输入、Root464 ZIP 和尚未完成的 Root465。未绑定的 UIE JAR 不能由 API 10 或版本号代替。
2. 先由 Server owner 决定并提供 accepted Free 的只读呈现 ABI，至少绑定真实 connection、physics scope、lifetime、revision 和 accepted physical snapshot。明确没有有效 live head/report 时 head 的权威来源与失效语义。Tracker 后续只消费此只读 ABI，不取得 movement custody；在 ABI 产出前，不以只改 `SixDofBodyPresentation` 或 Tracker 代替缺失的服务端 authority。
3. 针对 Root464 源码核实本地 Free camera-active gate、FREE lease/report TTL、accepted physical body Q 与 live head Q 的来源及 partialTicks/模式切换策略。它们是需按合同补证的条件路径，不等于已确认故障；没有 runtime reproduction 时保留 SOURCE/NOT_RUN 边界。
4. 将当前最终候选绑定到实际 YSM Mixin transform/配置结果、上述 API/pose/mirror 专项测试的真实 XML 和测试结果。UIE `PlayerBodyRenderTransformTest` 与 `PlayerBodyRenderScopeTest`、`FlightBodyPoseTest` 和 UG `UieFlightBodyPoseCompatibilityTest` 是已存在的执行入口，须绑定当前候选的实际 XML；方法存在或 class 存在不代表通过。数值回归应核对身份/六向 up、90°/180°、组合与连续 roll、q/-q 等价、head-local、render translation、手性、乘序及度/弧度。历史 `YsmMixinTransformTest` NoSuchMethodError XML 若未绑定该候选，只保留为历史失败，不能被其他通过替代，也不能当作本候选结果。
5. 若本候选触发 NG，由 Mainline owner 在其已授权代码范围修复，再运行失败用例、受影响回归和该档所需验收；最后以同一冻结候选完成 dedicated/FML、真实 GPU、双玩家和用户手感验证并保存源码、JAR、配置及回执身份。

F05 required matrix 仍包括：

- 本地第三人称和两名玩家间的远端观察；六个 cardinal 朝向；连续 yaw、pitch、roll；进入/退出重力控制、模式切换、teleport/respawn 与生命周期恢复。
- 普通站立、潜行、行走/跑动、游泳；YSM 模型切换、自身/他人模型显示开关、不同 visual scale、头部 look；常用持物、elytra、装备层。
- 第一人称手臂和常用视角独立列项：先验证兼容没有引入错位，若实际六向 body/eye 问题出现，再修复相应必要入口。`FIRST_PERSON_HEAD_POS` 是模型局部 Y，不可直接当世界 eyeHeight。
- 无 YSM、YSM 存在但兼容关闭、启用且 ABI 支持、unsupported ABI；纯 dedicated server 不带 UIE/YSM。另查死亡、sleeping、riding、respawn、dimension change、disconnect/world unload 的恢复。
- 真实用户 GPU 渲染下逐项记录通过/失败/未执行和用户实玩手感。矩阵/状态单测、ABI 结构检查、selected UNIT、dedicated 启动、专服联机、真实 GPU 是不同证据层；任何 required 路径未执行都不能说 F05 完成。