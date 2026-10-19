# Horizon Alignment v1

后续范围预览增量见 `ground-plane-preview-v2.md`：Alt+H 高亮已从单个面扩展为命中平面附近的有界多格实际表面；本文件的姿态/选面控制合同不变。v1独立交付身份仍保留在其历史验收记录中。

## 已确认的产品合同

- `Ctrl+H` 默认开关持续、实时、平滑的地平面对齐；绑定必须可修改。
- `Alt+H` 默认按住进入临时无限 FREE，并预览准星实际命中的方块面；松开时以当前有效命中面的朝外世界法线作为玩家上方，开启/恢复持续对齐。它替代六方向循环，绑定必须可修改。
- 按住期间没有命中或目标消失时仍保持临时 FREE，但没有有效预览；无有效命中时松开，保留原先参考及开关状态，不猜测方向，也不提交已失效的旧目标。
- 所选朝外法线是玩家直立方向、对应地平面的法向；不是 camera forward 目标，也不是屏幕内固定旋转 90°。例如朝世界 X+ 看向方块的西侧面，提交的是朝外法线 X−。不修改实际重力场。
- 开启时只平滑消除相对该参考的侧倾，不把正常仰俯强制拉回零。
- 开启时允许鼠标以完整增益越过 ±86° 软边界，进入 86°–89° 的小幅缓冲区；20Hz corrector平滑回到±86°。±89°仍是最终连续路径保护，不允许越过所选参考的头顶或脚底而翻到对向轴；左右转向不设累计角度上限。
- 关闭后立即停止自动纠正，保留当下姿态，恢复已经验收的无限 FREE 控制，不做隐式回正。
- 继续只使用一个完整四元数作为姿态真值；不复活基础 FREE 内的 mandatory world-up/gauge/recovery，不新增第二份 LEVEL 姿态。
- 本轮只增加显式选面所需的命中检测与预览，不新增装备、燃料、自动贴地、重力修改或远程场同步，不旋转既有世界速度。Mouse 不增加惯性，Z/X 的原 FREE 惯性保持不变。
- 完整对齐命令组合键激活时不得意外触发 boost；单独 Ctrl 的现有加速响应不延迟。HUD/F3 区分所选方向、开关状态与自动纠正状态。
- 默认初始状态为 OFF、所选参考 Y+；这是本轮实现的保守初始值，避免激活 G 时自动改变已验收的 FREE 行为。

## 顾问与实现状态

指定 Pro 顾问：`https://chatgpt.com/c/6a9a8e74-b9e4-83e8-b333-41d48d86953d`。

2026-09-04 已通过内置浏览器确认 composer 为 Pro，发送真实源码问题包并补发选面修订。已读取完整回答（页面显示“思考了 23m 44s”、回复操作按钮出现、停止生成按钮消失）；回答最前方的选面修订明确废弃后文遗留的 axis-cycle 状态/测试。以下算法为待本地可执行交叉验证的采用方案，不把顾问意见冒充仓库运行证据。

实现已完成。独立新鲜全量构建24 suites / 156 tests，0 failures/errors/skipped；Standards/Spec 两轴 P0/P1/P2 均0。相同发布 JAR 已完成专服有序生命周期验证并单次替换 Z 客户端。实际 artifact identity、分层证据、已知预览范围与 GPU 步骤见 `horizon-alignment-v1-acceptance.md`；上一版 HUD 的用户实测“通过”与截图不证明本功能 GPU 通过。

## 采用方案与边界

- 唯一 Q 将 local +Z/+Y/+X 映射为 forward/up/left；FREE 原方法逐操作不变。ON 才调用参考约束方法，核心接收单位世界法线，不知道方块/UIE/GUI。
- 参考几何：`s=n·f`、`c=|f-sn|`、`theta=atan2(s,c)`；正常情况下 `h=(f-sn)/c`、`left=n×h`、`up*=c*n-s*h`。精确/数值极点以当前 up 的赤道投影作 `h=-sign(s)*upProjected`，不用固定世界轴或第二份 heading。
- ON 鼠标保留原 sensitivity/invert/二维 45° cap。令 `A=R(n,-yaw/2)`，在 `A*Q` 的中间参考基上做 `thetaRaw=theta-pitch`，仅夹到 hard `[-89°,89°]`；soft 86°不参与鼠标clamp或增益。若起点继承在hard外，只允许保持或减小超限。`P=R(left,theta-thetaTarget)`，最终 `Q'=normalize(A*P*A*Q)`。这样 yaw 两段保持参考 pitch，中间 pitch 单调，不能先越极点后由合法 endpoint 蒙混通过；不截断 yaw。
- 纠正先 auto pitch 后 bank。`|theta|<=86°` 不自动回中；soft buffer以86°为目标、half-life0.25s/max30°/s。继承的89°–90° emergency先按half-life0.12s/max90°/s精确推进到hard边界，再把同一tick剩余dt交给soft profile，最终目标仍是86°。默认exact pole首个0.05s tick约到88.998°；activation edge本身不改Q。
- 两段pitch和bank都用 `dx/dt=-min(k*x,maxRate)` 的精确有界指数流，`k=ln(2)/halfLife`，无持久自动角速度；跨profile时计算到达hard边界的准确时间，保持标量dt subdivision。bank参数仍为half-life0.20s/max180°/s，dt cap0.10s。它们是待 GPU 验证的手感参数。
- 手动 Z/X 按住时暂停自动 bank（不暂停 ON 的soft/emergency auto pitch），manual dynamics 保持原样；松开后保留 manual coast 并恢复自动 bank。manual omega、auto bank rate、auto pitch rate分开诊断。
- Alt+H 物理 held 在首份 mouse sample 即覆盖为 FREE，不等 END latch；选择释放必须从当前 Q fresh pick，不能提交陈旧 preview。释放 edge 不改 Q，后续有效 tick 才作 bounded correction。
- UIE `CameraRuntime.frame` 按 render sample 缓存；`beginSample` 在 mouse 事件前执行。因此 mouse/END 只处理命令上升沿并保留待释放 selection，`RenderWorldLast` 在 UIE 已采样当前可见 Q 后 fresh pick 并提交参考。该 render 回调不修改 Q/omega/velocity；下一个有效 END tick 才纠正。预览和提交使用同次 CameraMeasurement，避免 END 读取旧缓存。
- GUI/pause/focus/input 不可用时取消 selection、不提交、清 preview/隐藏速率，恢复前重武装。世界/玩家/相机失活沿原有 Q 清理并把 alignment 关闭，参考可保留；不在新实例上意外恢复旧 ON。
- Ctrl+H 的完整物理 chord 在 boost provider 读取时即抑制 boost；不延迟裸 Ctrl，也不以 null plan 回退到 legacy boost。
- 预览用 UIE public CameraApi full-Q block hit，只显示有可靠几何的实际单面；不复制 Mekanism six-face/xray 绘制。1.12.2 无通用模型面 API，复杂形状不得用整包围盒冒充命中面。

## 验证计划

1. 纯输入状态：开关独立、选面上升沿/长按/释放提交、无命中持续 FREE、目标消失、无命中释放保留状态、同时组合键、暂停/失焦恢复的 held latch、可重绑语义。
2. 纯姿态行为：85°→88°和87°→88.5°完整进入soft buffer、89° hard guard、立即向内退出、86°内不回中、88°无输入单调回86°；六个参考方向及正负极点、180°对向up、初始倒悬、45°大输入与斜向输入、连续多圈yaw、激活/关闭/方向切换、跨profile dt划分；pitch回归固定meridian，bank纠正不改变forward，OFF与原FREE行为等价。
3. 输入整合：Ctrl+H 不额外 boost，Alt+H 不额外 roll；单独既有按键保持原行为，GUI/暂停/失焦无隐藏积分。
4. HUD/F3：状态、所选方向与自动/手动角速度口径可区分；仍保留准星、快捷栏、手部和 F3 让位策略。
5. 新鲜完整构建、独立 Standards/Spec 审查、发布 JAR SHA-256、同产物 dedicated-server 启动与原 stdin `stop` 保存/卸载/exit 0。
6. 按项目规则处理规范 Z 客户端的一次部署与回读，交付独立 GPU 节点测试步骤；开发服务器不启动 3D 客户端。

Real GPU acceptance: not executed

## 2026-09-05 soft-pitch increment

User accepted a soft-boundary overrun without a true90° crossing. The implemented combined candidate usesS86°/H89°, full immediate mouse gain, stateless soft/emergency correction and`autoPitch`diagnostics. Final automated result is28 suites/182 tests all zero; Standards/SpecP0/P1/P2=0/0/0; releaseSHA-256`E1E4DD6CC7FCAEAE225EC388A1D1C279CA43BD0340A42D6BB2D6B99F0DC4AAE1`. Dedicated qualified common pass; Z client was not changed because the one allowed attempt failed at directory access. See`.pensieve/.state/radial-preview-256/verification.md`. RealGPU for this candidate remains not executed.
