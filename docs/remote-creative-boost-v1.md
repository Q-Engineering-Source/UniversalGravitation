# Creative 联机推进 v1：使用与验收

> 历史 v1 合同。2026-09-06 起，G 开关意图、临时高度带、连续再入制动、距离额度、原生碰撞与重同步规则由 [remote-g-motion-v2.md](remote-g-motion-v2.md) 覆盖；下文的地形白名单、sample-count credit、incident STOP 和手动重新武装不再是当前要求。历史验收记录不因此升级为新候选已通过。

## 范围

用户已批准的实验版本：服务端许可、有限速度/时间额度、原版单次碰撞结果提交校验；客户端执行 FREE 加速、惯性与制动。它不逐步认证推进物理的来源，也不能证明获准客户端遵守每一步加速度。不是 GottaGoFast 全局移除检测。

现有单人/集成服务器路径保留。独立专服的这一例外只支持明确协商且获准的 Creative Personal Zero-G；没有自然重力场同步、Survival 装备/燃料、轴向高速、导航或 SS/DU 环境集成。未来产品合同仍在 `docs/boost-490.md`。

## 启用

专服 `config/unigrav.cfg` 的 `general` 类别：

```text
B:remote_creative_boost=true
D:remote_boost_maximum_speed=500.0
```

发行默认 `remote_creative_boost=false`，需要管理员明确启用。此数值是 m/s 安全上限，不是加速度。初始许可不高于最大 boost 目标490；已命名的外部 Binder 冲量最多提高至有效 hard ceiling。正数 `max_entity_speed` 若更小仍约束上限，不能用0取消远程协议的有限额度。

本项目隔离专服已按该配置准备，原配置备份留在本轮 evidence 目录；不改变其 `online-mode`、现有世界或其他项目实例。服务器地址/ready状态和最终产物以本轮记录为准，不因为目录已准备就认为正在运行。

客户端仍需要已有的 UG + UIE + Revo Font full + 定制 ModularUI 组合。不要把客户端 UIE/Font/ModularUI 装入专服；UG 此路径不依赖 UIE Server Companion。旧 peer 没有声明新通道时不发送未知新协议。

## 操作

1. 使用专用测试实例连接；切到创造模式，先退出原版创造飞行/鞘翅/骑乘状态。
2. 在已加载、仅普通原版空气/完整方块的测试空间按 G。先显示请求；只有收到服务器 ACTIVE 确认后才启用个人 G、相机与推力。
3. 默认 Left Control 推进，沿当前同一 Q.forward；九挡值与原控制设置不变，默认第5挡40 m/s，可逐挡升至490。W/S、A/D、Space/Shift 保持 FREE 本地轴机动；各按键继续可重绑。
4. 松开 boost 停止新 boost 推力、保留 G 与现有速度。再次按 G 自愿退出时释放其控制/重力覆盖，不直接清速度；CLOSING 仅保留移动额度和报告，之后正常交还。
5. 明确协议/权限/生命周期/未支持区域故障会显示安全制停原因，并记录日志。硬制停不自动重启；解决原因后再显式按 G。退出视角切换及真实手感仍须 GPU 验收。

GUI/失焦不产生新推力；ACTIVE/CLOSING 即使静止也每个客户端 tick 至多补一份 paired position sample，不依赖原版的20 tick静止报告周期。

## 服务端约束与刻意限制

- 新通道 `ugboost1`，固定版本/70-byte payload；保留旧 `unigrav` discriminator0冲击报告。REGISTER 从登录阶段按 NetworkManager 留存，Play attach 后才发 OFFER；HELLO 不等于推进许可，重复 HELLO 不重置会话。
- 每连接输入调度有界；每条 custom packet 独立切到游戏线程，与普通玩家包保持因果队列顺序。STOP/关闭/retire 的 generation barrier 使先前排队的包不能跨边界重新授权。
- 以服务端 tick 产生距离/样本额度，最多两 tick抖动容量，但每份位置样本仍至多一步。范数上限不是逐轴上限；数值误差额度是单次有限储备，不按包补发。
- 正常 close 使用独立静止窗口，不以客户端的 settled 标记单独证明静止；最终 READY 使用新 epoch，过期在途控制包丢弃而非惩罚性重启。
- 在扫描地形之前先拒绝非法/超额候选。当前仅支持通过 loaded-area 检查的 vanilla air/full-block 域；液体、web、非完整碰撞形状、modded block，以及未审计的可碰撞实体/状态会被拒绝。世界高度扩展/未加载空域等也不在已验收范围。
- 原版 `Entity.move` 仍只执行既有调用，不添加第二遍碰撞查询/副作用。最终提交坐标与 native clipping 结果不符时纠正并制停；不会因 Creative 跳过部分 vanilla `moved wrongly` 而把客户端点塞回墙后。细玻璃片/栅栏等被前置域检查拒绝，不等同于一整格厚石墙的 native clipping 测试。
- 已拥有的服务端 Binder 冲量发送一次全精度 m/s ADD，不累积 MP.motion 或触发其 vanilla velocityChanged；客户端按完整范数限制后再除20写入 motion。
- 无法证明来源的 owner tracker velocityChanged 当前采用明确 `EXTERNAL_VELOCITY` 制停并保留原版递送；不是完整 raw REPLACE 集成。爆炸不添加第二份合成冲量，但它和其他未审计速度写入可能使实验模式退出；不承诺保留这类外部覆盖前的高速动量或兼容任意第三方 motion writer。
- 硬纠正集中检查当前 handler/player/world/dimension 归属；死亡、换维度或 callback teleport 不会被旧预算坐标拉回。

## 诊断与证据

- `/ugboost status`（权限2）：真实开关、限速、登记/协商/拥有控制的连接数、最近挡位与 server tick。
- `/ugboost selftest`（权限2）：实际生产 codec/budget、目标类加载与 native AxisAlignedBB 算术检查，输出 `checks=6; headless budget/codec/native-AABB; not connected-player acceptance`。
- transition/STOP 日志包含会话 epoch、序列、原因、上限和 tick，不逐帧刷日志。客户端有对应 ACK/原因/制停提示。
- 最终源码、jar hash、构建和专服运行记录在 `.pensieve/.state/remote-boost-v1/verification.md`；未执行的门必须单独列出。

## GPU + 专服最小验收清单

两端必须先核对同一非dev UG JAR的 SHA-256，记录同一测试窗口和双方日志。只用项目隔离世界/客户端。

- 协商/G开关：READY→ACTIVE、拒绝非创造/飞行/骑乘、正常 CLOSING、静止完成后再次 G-on。
- 输入与九挡：任意 Q 的正向 boost、反向制动、松键惯性、降挡不硬清速度、GUI/失焦无新增推力、静止时不超时。
- 高速安全：逐档升至490，不从最高挡贴着障碍起步；已加载空气区直行，正撞/斜撞一格厚石墙，墙角、地面/天花板，核对客户端 clip 与服务端最终位置。
- 不支持域：未加载边界、液体/非完整块/modded block，检查明确提示和安全处理，不应穿越或自动重新推进。
- 生命周期/网络：重生、断线重连、换维度、传送/ACK、队列抖动和重复/过期包。真实对抗输入需单独测试，不以纯 helper 测试冒充。
- 外部速度：已命名 Binder ADD 的限速/一次应用，以及未知 tracker/explosion 的明确退出行为。
- 日志关联：对应 UUID/epoch/序列和时间戳、F3画面、必要录屏。服务器无人连接时的 selftest 不关闭任何真实玩家网络/视觉门。

Real GPU acceptance: not executed
