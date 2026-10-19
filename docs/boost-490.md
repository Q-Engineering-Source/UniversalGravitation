# Boost 490：本项目独立切片

## 用户合同

2026-09-05 用户要求不等待 StellarSky，先完成 UG 自身的高速 boost 和专服验证，并明确要求实际联机 boost，不仅是 dedicated-server 加载测试。

已确认保留原五挡，再追加四个高速挡；默认保持第 5 挡、40 m/s。原有可重绑按键、Q.forward 方向、推力冲量、松开惯性、反向制动和滚转控制不因挡位扩充改变。

| Boost 挡位 | 目标速度 m/s | 名义推进加速度 m/s² |
| --- | ---: | ---: |
| 1 | 11.453746 | 22.907492 |
| 2 | 16 | 32 |
| 3 | 24 | 48 |
| 4 | 32 | 64 |
| 5（默认） | 40 | 80 |
| 6 | 80 | 160 |
| 7 | 160 | 320 |
| 8 | 320 | 640 |
| 9 | 490 | 980 |

加速度沿用既有 `targetSpeed / 0.5 s`。0.5 s 是无阻力名义比例，不保证 Minecraft 保留空气阻力/摩擦时从静止恰好半秒达目标。第 1 挡仍为标准地面原版疾跑参考的两倍。全局 `max_entity_speed`（默认 500 m/s）保持不变；用户配置更低时仍是最终安全上限。

HUD、F3 和动作栏显示 Boost 的实际总数 9；Roll 仍为五挡，默认第 3 挡。降挡不直接清除已有速度，超过当前目标速度时保持现有 limiter 的制动语义。

## 当前实现与证据边界

- 数值/UI 切片只扩充挡位、保持默认值并将实际 count 传入显示快照；不修改 quaternion、mouse、roll inertia、horizon correction、scene-depth preview 或既有严格球面 limiter。
- 无 active 6-DOF provider 时的旧 boost fallback 仍为 40 m/s、80 m/s²。本数值切片不暗中向其他状态开放最高挡。
- 初始固定 before 快照：`.pensieve/.state/boost-490/before/`；仓库 unborn `main`，`UNCOMMITTED`。最终构建和运行证据另记，不能从此需求文档推断通过。
- 用户同时要求的 remote boost 尚在单独设计；在实际实现及验证前，旧 integrated-only guard 仍存在，九挡数值更新本身不代表联机已支持。

## 联机设计的已核实前提

- UG 当前只有 discriminator 0 的 C2S self-impact 包；个人 G、完整 Q.forward、推进许可和速度状态均无远程协议。
- 服务端 binder 对 EntityPlayerMP 跳过 Δv；Minecraft 普通 CPacketPlayer 校验会拒绝无法解释的高速位移。不得通过删除所有移动校验或修改全局阈值为所有玩家放行。
- 实际 Cleanroom runtime 的 SPacketEntityVelocity 每轴钳制 ±3.9 blocks/tick（±78 m/s），不适合作为 490 m/s 的完整速度传输。
- UIE Server Companion 只提供姿态/roll 协议与策略，不提供 UG 物理授权、完整姿态向量或重力场同步。
- 联机应先围绕现有 Creative Personal Zero-G / Propulsion Permit 合同形成完整切片；Survival 背包、燃料和 SS 环境联动不在本轮范围。真实设计必须与 Pro 完整答复及源码交叉核验，不能以本节代替方案。
- 已向官方 ChatGPT 内置浏览器确认的 `6 Pro` 模式发送完整问题包：`https://chatgpt.com/c/6a9c1b46-f43c-83e8-b5a4-9e54e497eb55`。首份完整答复已回收：建议服务端许可/运动账本、客户端预测、保留原版位置提交；但可行性明确依赖真实 hook、碰撞模型和速度变更来源。它不是已完成的设计或实现证明。
- 后续事实核验发现 live `World.getCollisionBoxes` 会改写边界标记并触发 block/Forge callbacks，不能作为无副作用的重复影子模拟。已将完整碰撞/客户端时序、频道登记、速度来源和 GottaGoFast 源码调查补回同一顾问会话。两轮完整答复均已读取；第二轮撤回把完整影子 F 和通用速度来源账本作为下一小切片的建议，要求先由用户确认下述简化合同。网络实现仍未开始。事实快照见 `.pensieve/.state/boost-490/{network-hook-facts,collision-phase-facts}.md`。

## 已批准：创造模式联机首版的架构合同

用户已明确回复“按这个边界开始实现创造模式联机版”，批准方案 B 的实现边界。它是显式启用的 Creative-only 实验能力，由服务器确认连接/协议/当前权限、epoch、有限速度与 server-time 移动额度；客户端执行 Q.forward 加速、惯性与制动；单次原版 movement 调用后，在最终提交客户端位置前核对原生碰撞结果，保留传送 ACK 等既有检查。不是全局关闭 `moved too quickly`，也不是服务端逐步认证完整推进物理。批准不等于代码或实机验证已经完成。

必须明确接受的边界：

- 获准但不诚实的客户端仍可能在许可额度内伪造瞬时速度/转向或假装被动滑行；B 不证明运动的物理来源。完整 server-owned 推进轨迹需更大的 native-backed 玩家运动控制器。
- 普通松开 boost 保留 G 与动量；自愿关闭 G 释放其独占控制/重力并保留当前动量，进入不增长的 carried-allowance `CLOSING`。服务端不能证明这段仅是滑行，不能伪称已经禁止所有后续推力。
- 权限丢失、生命周期失效、未支持地形/边界或安全故障，若无法无缝交接，则执行明确报告的权威安全制停（正常位置纠正/ACK + 明确速度重置），不重置 quaternion、不在 ACK 后自动重启。已有跨维度/死亡/传送生命周期优先，不能纠正回旧世界。
- 只支持已审计的方块/实体与速度写入路径。UG knockback 使用明确一次性 ADD；已识别 tracker owner snapshot 只在受控玩家上处理；显式 velocityChanged replacement 需 raw/full-precision REPLACE 和暂停语义；未知 direct-motion writer 不是自动兼容。不得全局吞掉速度包。
- 从指定已加载测试区域开始，未纳入的液体/梯子/web/移动实体/custom callbacks 必须按明确规则暂停或制停，不能冒充支持所有地形。

Gate 0 已由上述明确确认关闭；开始具体 scoped Mixin/协议和实现。GottaGoFast 仍仅作只读参考，见 `docs/research/gotta-go-fast-server-movement.md`，未安装、未复制实现。生存装备/燃料、轴向高速、自动导航和 SS 集成不在本轮。

当前实现与使用/验收范围以 `docs/remote-creative-boost-v1.md` 为准；前述顾问提案中“尚未开始”的叙述属于当时设计阶段，不代表当前源码状态。实际采用的是有限许可版本，未知 tracker 写入走明确制停，而非通用 raw REPLACE/物理来源认证。

## 与未来自然漂移的分离

用户直接澄清：环带外自然累积的 30 m/s 只限制自然漂移贡献，不是无重力区的总移速上限，不应把主动 boost 或其他已有动量钳成 30 m/s。自然漂移与主动推力的合成、碰撞后状态与环境 query 在后续 SS/UG 联动中单独确定，本次不实现。

## 后续 Survival 高级模块：独立的轴向高速模式

用户进一步明确未来高级模块的外层空间/导航门槛、沿选定环带轴（当前X）的主推进、推进期锁定视角、小幅横向/竖向调整，以及松开推进后解锁视角并按设置自动反向制动或惯性滑移。完整产品记录及未定参数见 `.pensieve/short-term/decisions/2026-09-05-axis-locked-survival-transit.md`。

这不是对当前已实现 FREE Q.forward 九挡代码的追溯改写。Creative-only 实验联机的授权来自上一节单独记录的明确确认，不来自本节未来产品讨论；此处不启动生存装备、燃料、导航或轴向控制实现。

## 验收

- 纯测试直接调用生产 gear/boost helper 与显示接口，验证九挡映射、默认/上下界、按键 edge、490 m/s 有限性/不越限、反向制动、Q.forward 和实际 count。
- Build / Unit / Integration 后用本项目专用服验证确定 JAR；共同/服务端类加载、命令和生命周期证据与 remote movement 证据分开。
- 实际联机 boost 还需同一确定 artifact 的客户端与服务器、真实协议/移动校验证据及独立 GPU 节点操作。不得把无玩家开服记为高速联机通过。
- 不启动开发节点的任何 3D 客户端；不操作 SS 的测试目录、进程或共同部署包。

Real GPU acceptance: not executed
