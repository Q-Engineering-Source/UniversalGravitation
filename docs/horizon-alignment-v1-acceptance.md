# Horizon Alignment v1 — 验收记录

## 构建与节点身份

- 开发仓库：`D:/WI - Dev Workspace/UniversalGravitation-src/UniversalGravitation`；branch `main`，无 HEAD，`UNCOMMITTED`。
- 本轮产品合同/采用方案：`docs/horizon-alignment-v1.md`。初始与候选 SHA 清单：`.pensieve/.state/alignment-v1/source-identity.json`；可用 before 与完整 after 快照在同目录。
- 源码开发、权威测试专服和 Z 盘 GPU 客户端目录保持分离。开发节点禁止 3D。
- 本轮标签：`ug-horizon-v1-20260904-CC5BB8CB`；构建没有独立 build-id schema，此标签是外部验收关联标签。
- Source / Static：独立 Standards、Spec 两轴均 P0/P1/P2 = 0/0/0。历史 gate 因无 HEAD 为 SKIPPED，按项目 pipeline 比较目标开始快照并审查新增文件；不是整仓库历史审查。
- Build / Unit / Integration：`gradlew.bat test --tests '*SixDof*' --no-daemon --offline` exit 0；随后 `gradlew.bat build --no-daemon --rerun-tasks --offline` exit 0，35s、9/9 tasks executed，24 suites / 156 tests，0 failures/errors/skipped。环境为 `GRADLE_USER_HOME=C:/GradleCaches`、JDK25.0.2、TEMP/TMP=`C:/GradleCaches/tmp`。
- 两项先前失败均已修正并重跑：预览的旧 MCP `Vec3d.addVector` 不存在，改现有构造器；测试误把 capped-then-exponential 的180°侧倾在5s后的残差要求为 `<1e-5°`，改独立闭式 oracle（正确约 `1.82214e-5°`），生产控制律未因此放宽。
- 发布 `build/libs/unigrav-0.1.0.jar`：250,779 bytes，SHA-256 `CC5BB8CB7A16CB3BE413198AA9F9320BDDC72982BE6C1FBA0472D9317EA56F11`，生成时间2026-09-04 18:31:44 Asia/Shanghai。131 class 均 major69，`neofontrender/**` bundled entries = 0。dev JAR不用于部署。
- 编译接口来自本地 `D:/WI - Dev Workspace/NeoFontRender/addons/ui-enhancements/build/classes/java/main`，UIE HEAD `af8b6967f8cb40d9e723c2799ece6c5e44f27ff0` 加既有未提交呈现修复；本轮未改/重建 UIE。CameraApi/FlightApi/InputApi class SHA 分别为 `A9B7A3BB4DEC085A5FDC2479BDB598A8C7611DAF964FDCB813F5C68165C9AE01` / `767C3216F82756BEF323162AE992672346C427F8FB7E67EACF11A54C7337DFBA` / `BEA9AA468A9AEC3BEF69946327FD45F388A358274B6CCFE2C95B3ED9BDE79A53`。
- Dedicated Server：同 SHA 部署到项目专服，隔离世界 `unigrav-alignment-v1-20260904-1833`；18:33:53 `Done (12.414s)`、8611监听（PID18744）、UG加载44项质量预设及世界场/接管。原 PTY session95332 执行 `unigrav sources`、`unigrav world get` 后18:34:13发送 `stop`；18:34:14保存玩家/世界/区块并卸载0/2维度，原进程 exit0，PID/监听均消失。保留测试包既有 AE2 缺签名的非致命 FML ERROR，不宣称零错误环境；无新 crash report、client-only loading failure。
- Artifact handoff：源 JAR、专服 `mods/unigrav-0.1.0.jar` 与 Z 规范客户端 `.minecraft/mods/unigrav-0.1.0.jar` 回读均为上述 SHA/长度。Z 单次替换成功。替换的是磁盘文件，未远程启动/重启GPU客户端；验收前仍须重启客户端加载本次 JAR。
- 可恢复备份及完整 server logs：`C:/GradleCaches/artifacts/UniversalGravitation-alignment-v1-20260904-183317/`。`server-before.jar`、`client-before.jar` 均保留上版 `8660C341...1DA32E5`；本次发布 JAR 和 `server-run.md` 同存。server-latest SHA `49975CD062CA0B5F221F5918B609C6FDA6DA2BDD3F66474EC86D3A03BD5FE167`，server-debug SHA `517835C02D8363E82C74A8CA06204C44FCA755E1E23EB9FC2160F7950F3138B4`。
- 本轮未创建 commit/push，未启动任何本机3D客户端；此前用户“通过”的截图仅属于上一 HUD，不算本次纠偏验收。

## 最小 Real GPU 验收

先关闭本项目客户端，核验 `.minecraft/mods/unigrav-0.1.0.jar` 的 SHA-256 与本次交付完全一致。保留已有世界/配置，使用可丢弃的 Creative integrated-server 测试世界。当前 remote client 无 field-state sync，专服联机只能验收 common/network pass-through，不能替代下列 G 正向行为。

1. 进入 G，确认默认 `ALIGN OFF Y+`；mouse 单轴/斜向、跨头顶/脚底、Z/X 惯性/反向制动、boost 仍是已接受的 FREE 行为。
2. Ctrl+H 开启，确认状态提示 ON；普通仰俯不会被拉回水平，左右可连续转多圈。缓慢从约85°推入86°–89°缓冲区：鼠标在86°不应停顿或减益，89°仍阻止越过头顶/脚底；停止输入后应平滑回到86°，而不是0°。反向输入立即向内，不等待回弹状态。该限制相对所选面法线，不固定为世界Y。
3. ON 下按 Z/X 主动滚转，按住时不被自动消侧倾抵消；松开后保留手动惯性并平滑回正。F3 中 manual omega 与 `autoBank`/`autoPitch` 独立，mouse 不给 manual omega 加惯性。
4. 按住 Alt+H，确认立即临时 FREE，可以越顶倒悬及任意斜向旋转。对准方块面时 HUD 显示 `SELECT <axis>`，移到天空显示 `SELECT --`，天空中仍保持 FREE。
5. 分别选六个方向的面并松开，确认所选面的朝外法线成为 UP，持续对齐开启。朝 X+ 射线命中 WEST 面必须选 X−。松开 MISS 则保留原参考及 ON/OFF；不能提交之前看过的旧面。
6. 正对新地面（尤其 f≈−normal）松开：释放本身不跳Q，默认首个0.05s有效tick平滑到约88.998°，随后继续向86°软目标回归并消除bank，不突然翻到对向法线。对Y±/X±/Z±，以及原来roll=0/90/180°重复。
7. 第一/第三人称下测试普通原版完整方块和上下半砖高亮：填充/边框应贴在真实命中面，不能偏移眼高或第三人称距离，不透墙、不高亮整箱六面。范围32m；楼梯、复杂/模组方块仍可选法线，但第一版不保证矩形面高亮，HUD 候选提示仍可使用。
8. Ctrl 单按保持 boost；完整 Ctrl+H 不同时 boost，松 H 保留 Ctrl 后 boost 恢复。新命令在控制设置中可重绑。确认原 Z/X、gear、WASD/Space/Shift/boost 均未被新的默认 Alt+H 组合误触发。
9. 选面中打开 GUI、暂停、失焦、退出/换世界或让位给其他 UIE 相机：取消选择，不能把失焦当松键提交，恢复后须释放并重新按住；不能积累隐藏角速度。G 失活/新玩家状态下对齐 OFF，参考可保留。
10. 测试 F1/F3/不同 GUI scale、准星/手部/快捷栏保留，以及关闭 `six_dof_hud` 后相机/对齐仍可操作。预览结束后不得残留 GL 状态或透明度/深度异常。

## 回执与证据

记录测试窗口（含时区）、本轮标签、UG JAR SHA-256、UIE/Revo Font/ModularUI 依赖身份、GPU/驱动与分辨率。保留规范实例 `.minecraft/logs/latest.log`、debug/crash report（如有），以及包含上述输入过程和 F3 对齐数据的录屏。专服联机门需同窗口双方日志和同 SHA 证明。单张截图不能证明连续纠偏手感。

Real GPU acceptance: not executed
