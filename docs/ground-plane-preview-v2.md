# Ground-plane range preview v2

## 本轮边界

用户要求高亮目标地平面的方块表面作为范围预览，参考本地 Mekanism-CE 的范围扫描叠加层。本轮只扩大原 Alt+H 单面预览，不修改姿态算法、mouse/roll/boost/drift、选面提交或 Ctrl+H 总开关。

- 命中真值仍为 UIE public CameraApi 同次 render-frame 的 BLOCK hit/side/outward normal；射线范围保持32m。
- 平面由命中面真实世界坐标和朝向定义，不把所有平行但不同高度的表面算进来。
- 默认以命中方块为中心取17×17格(radius8)有界平面窗口，最多289候选；X/Y/Z 正负六个方向对称。半砖的半格高度、负坐标和整数边界必须保留。
- 只生成该平面上实际可确认的方块表面，空气/缺口/不同高度/未知模型不生成假面。窗口可包含不相连的同平面区域，不进行连通域/矿脉 BFS。
- 几何支持沿用原版完整立方体与原版 slab 的单碰撞 AABB边界；复杂/模组模型仍可选法线，但不冒充已支持的矩形面。
- 常驻对象与扫描量有界；只读已加载且 world-border 内的测试世界状态，不强制加载区块。相同目标每2个客户端世界tick刷新；换目标可立即刷新，MISS/结束选择/GUI/失焦/世界切换清理缓存。
- 多个面统一批量提交填充和线框，不对每格单独 push/pop GL 或 draw。保持正常 depth test、不写depth、向外微偏移及实际 GL状态恢复；顶点以实际 render-view entity 的插值 feet/proxy 坐标为原点，不重复扣 CameraFrame 的眼高/第三人称位移。

## 只读参考与取舍

- `D:/WI - Dev Workspace/Mekanism-CE-Unofficial-1.12.2/src/main/java/mekanism/common/content/gear/IBlastingItem.java:29`：Blasting 根据命中轴选择有限单层坐标范围。UG 独立实现对称窗口；不复用它的 player ray、target-side 推测、硬度/矿脉条件。
- 同仓库 `src/main/java/mekanism/client/render/RenderTickHandler.java:283`：调用 MekaTool 的候选集合并逐块高亮；其后续绘制是每个块完整selection volume，并非逐块目标单面。UG不复制这些实现或增加其依赖。
- 同仓库 `src/main/java/mekanism/common/content/gear/mekatool/ModuleBlastingUnit.java:109`：参考实现给扫描半径设显式上限。
- Digital Miner 的 `MinerVisualRenderer` 只画整个范围cuboid，不是本任务需要的实际表面枚举；JSON model selection/cache、vein BFS及按默认值恢复 GL状态也不引入UG。

## 验证与证据

本轮开始基线：上一发布 JAR `CC5BB8CB7A16CB3BE413198AA9F9320BDDC72982BE6C1FBA0472D9317EA56F11`，250779 bytes，156 tests。该数字是历史基线，不证明本轮修改通过。

### 最终验证

- Source / Static：Standards与Spec两轴最终P0/P1/P2均0。复核包含未知seed不阻断可信邻面、固定offset预算/溢出守卫、MISS/隐藏GUI/生命周期清理。原Q/orientation/HorizonAlignment/roll/boost/drift/inputstate与ClientBoostRouting/Ticker/KeyBindingState的9个关键业务文件与本轮开始SHA一致。
- Build / Unit / Integration：独立 `gradlew.bat test --tests '*SixDofGround*' --no-daemon --offline` exit0，22s；继而 `gradlew.bat build --no-daemon --rerun-tasks --offline` exit0，34s，9/9 tasks executed。新鲜25 suites / 164 tests，0 failures/errors/skipped；range纯测试新增8项，既有geometry3项。使用JDK25.0.2、`GRADLE_USER_HOME=C:/GradleCaches`、TEMP/TMP=`C:/GradleCaches/tmp`。
- Artifact：`build/libs/unigrav-0.1.0.jar`，263286 bytes，SHA-256 `9F1B9CAAEEAEB5FF4105EFA5DF647E239CB91CDA121EEB79EE64406203E17C0E`；生成时间2026-09-04 18:54:41 Asia/Shanghai。139 class均major69、无`neofontrender/**`打包。UIE依赖/编译接口沿用v1，本轮未改/重建UIE。
- Dedicated Server：相同artifact，实例固定项目测试专服，隔离世界`unigrav-ground-plane-v2-20260904-185612`。18:56:42达到`Done (9.421s)`、PID33500监听8611，UG加载44预设/世界场/接管。原PTY38588发送sources/world get/stop，18:57:14保存players/worlds/chunks，18:57:15卸载0/2维度并exit0（completion86173d）。最终PID和listener消失，无新crash。保留测试包既有AE2缺签名非致命FML ERROR，不宣称零错误环境。
- Handoff：源、项目专服`mods/unigrav-0.1.0.jar`、规范Z客户端`.minecraft/mods/unigrav-0.1.0.jar`回读长度/SHA完全一致；Z单次替换成功。没有替用户启动GPU客户端，仍需重启加载新JAR后真实验收。
- 备份/证据：`C:/GradleCaches/artifacts/UniversalGravitation-ground-plane-v2-20260904-185612/`，包含本轮发布JAR、server/client-before JAR（原CC5...56F11）、server-run.md和latest/debug/cleanmix日志。server-latest SHA `A2BEC0FD6522181E78D9F2955FFCC575871AC4FC99268B9EF8C08D371D6BD0D3`，server-debug SHA `650DA61AAD6A21EBC00A595507130042695CEB0FD9019DDEF815514CEA201508`。
- Repository：`D:/WI - Dev Workspace/UniversalGravitation-src/UniversalGravitation`，branch main，无HEAD，`UNCOMMITTED`；无独立build-id schema，本轮外部关联标签`ug-ground-plane-v2-20260904-9F1B9CAA`。基线/最终清单及快照在`.pensieve/.state/ground-plane-preview-v2/`。
- 本轮未创建Git提交，未修改全局/Pro规则或外部参考仓库，未启动本机3D客户端。

GPU验收重点：在墙、天花板、地板六个面上选面；观察多格范围与空洞、不同高度、上/下半砖；沿平面换目标、切换法线、MISS、暂停/失焦/退出；确认填充贴面、不透墙、不残留旧世界预览，并复核Alt+H release与既有姿态行为未变。保留同一artifact hash的日志、录屏与时间窗口。

Real GPU acceptance: not executed
