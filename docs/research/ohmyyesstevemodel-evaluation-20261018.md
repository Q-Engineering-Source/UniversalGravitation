# OhMyYesSteveModel 分支评估：UG / Cleanroom 1.12.2

日期：2026-10-18，Asia/Shanghai。范围：公开仓库、固定提交源码、构建配置与发布记录，以及本地 UIE 现有 YSM ABI 的只读核对。证据等级为 SOURCE；本次未构建或运行该分支，未做 Minecraft / Real GPU / 双玩家验收。

## 1. 判断与建议

**值得作为后续适配的研究对象，当前不适合直接替换现用 YSM 1.1.9。** 仓库已经存在真正的 `dev/1.12` 源码移植，不能再说“只有 1.12 移植计划”；但作者明确不建议投入生产，该分支的 Cleanroom 基线、native 依赖、包名和渲染 ABI 都与当前 UG 组合有差异。[1.12 固定提交](https://github.com/AndreaFrederica/OhMyYesSteveModel/commit/62004ccb7667c138e8408efaff82e80dc72f6ee8)、[1.12 README](https://github.com/AndreaFrederica/OhMyYesSteveModel/blob/62004ccb7667c138e8408efaff82e80dc72f6ee8/README.md)、[1.12 构建配置](https://github.com/AndreaFrederica/OhMyYesSteveModel/blob/62004ccb7667c138e8408efaff82e80dc72f6ee8/build.gradle)

对 UG 最有价值的是源码可查、显式渲染场景和可定位的 root / head 接入点。尚未看到现成的 UG 六向重力、身体中心旋转、完整身体局部头姿态或“纸娃娃预览默认直立”配置。以下接口分析不能当作这些需求已实现或已通过的证据。

当前移动 / 飞行修复继续按 Mainline 原切片推进；本评估不改变依赖，不升级 Cleanroom，不替换产物，不启动延期的姿态背包工作。YSM 渲染入口本身也不是 UG 移动预测、WASD 坐标转换或服务端同步问题的修复方案。

## 2. 分支身份：必须分开评估

本次实际查询 `git ls-remote --symref ... HEAD` 及所有 heads，得到：

| 分支 | 固定提交 | 当前技术位置 |
| --- | --- | --- |
| 默认 `feature/v3d-decoded-cache` | `25ae5414d6be9108c7724d96b21632cc4d34d78b` | Java 17、Minecraft 1.20.1 / Forge；含独立 runtime、JVM 与可选 native、多格式及 V3D 缓存方向。 |
| `dev/1.12` | `62004ccb7667c138e8408efaff82e80dc72f6ee8` | 实际 1.12.2 源码移植；Java 25、Cleanroom 0.6.13-alpha；本次 UG 可用性判断的主要对象。 |
| `dev/1.20` | `74c53b58b2f9b680a6c6dc5d5d41e7422fc000bb` | 另一独立分支；不作为 1.12 已具备能力的证据。 |

来源：[分支列表](https://github.com/AndreaFrederica/OhMyYesSteveModel/branches/all)、[默认分支构建配置](https://github.com/AndreaFrederica/OhMyYesSteveModel/blob/25ae5414d6be9108c7724d96b21632cc4d34d78b/build.gradle)、[1.12 构建配置](https://github.com/AndreaFrederica/OhMyYesSteveModel/blob/62004ccb7667c138e8408efaff82e80dc72f6ee8/build.gradle)。分支名会继续移动，后续复核须以完整 SHA 为准。

默认分支 README 的 1.12 计划和之后出现的 `dev/1.12` 代码不是矛盾：准确状态是“已经开始源码移植，仍未形成可直接采用的已验收版本”。1.12 tip commit 明确写了初始化 1.12.2、TODO/FIXME 和不建议生产使用；1.12 README 也保留未完成、API / schema / 协议 / cache 不稳定的声明。[默认 README](https://github.com/AndreaFrederica/OhMyYesSteveModel/blob/25ae5414d6be9108c7724d96b21632cc4d34d78b/README.md)、[1.12 tip](https://github.com/AndreaFrederica/OhMyYesSteveModel/commit/62004ccb7667c138e8408efaff82e80dc72f6ee8)、[1.12 README](https://github.com/AndreaFrederica/OhMyYesSteveModel/blob/62004ccb7667c138e8408efaff82e80dc72f6ee8/README.md)。

截至本次 GitHub Releases 查询，只找到预发布 `v3.0.0-dev.6`，资产面向 Minecraft 1.20.1，包括 YSM 与 `ysm-runtime-forge-0.1.1.jar`；没有找到对应的 1.12 发布包。这不证明作者本机没有私有构建，但不能把公开的 1.20 包装进我们的 1.12 实例。[Releases](https://github.com/AndreaFrederica/OhMyYesSteveModel/releases)、[dev.6 发布页](https://github.com/AndreaFrederica/OhMyYesSteveModel/releases/tag/v3.0.0-dev.6)。

## 3. 构建与运行依赖的实际差异

`dev/1.12/build.gradle` 第 35 行固定 Java 25，第 65–69 行启用 MCPForge legacy、Cleanroom `0.6.13-alpha` 和 `mcp_stable:39-1.12`。Java 25 与本项目方向一致；Cleanroom 版本与当前 0.6.8 不同。这是尚需验证的兼容差异，不能仅据版本差异断言必定不能运行，也不能据同为 1.12.2 就推定兼容。[构建配置](https://github.com/AndreaFrederica/OhMyYesSteveModel/blob/62004ccb7667c138e8408efaff82e80dc72f6ee8/build.gradle#L34-L69)。

该分支仍有独立 native 库的开发配置链。`gradle/native.gradle` / `docs/build.md` 从 `YSM_NATIVE_PATH`、`local.properties` 的 `ysm.native_path` 或资源目录取得平台库；未准备好库不能假定一条 Gradle 命令即可复现开发运行。构建脚本另引用本地 quickbuf、Atlas-Lib、firstperson 等输入，后续若构建必须绑定实际来源和身份。[native 配置](https://github.com/AndreaFrederica/OhMyYesSteveModel/blob/62004ccb7667c138e8408efaff82e80dc72f6ee8/gradle/native.gradle)、[构建指南](https://github.com/AndreaFrederica/OhMyYesSteveModel/blob/62004ccb7667c138e8408efaff82e80dc72f6ee8/docs/build.md)、[依赖声明](https://github.com/AndreaFrederica/OhMyYesSteveModel/blob/62004ccb7667c138e8408efaff82e80dc72f6ee8/build.gradle#L241-L295)。本次未解析或运行这些依赖，不报告构建成功或确定的构建失败。

默认分支的独立 `runtime/` 将 JVM 能力和可选 native 加速分开，V3D 解码缓存有实现与测试源码；但 `dev/1.12` 固定树没有该 `runtime/` 子树，也没有相同的 V3D cache / decoded workspace 模块。1.12 的 `BakedModelCache`、`BakedAnimationCache` 等本体缓存不能被等同于默认分支完整的新 runtime。**不能把默认分支“摆脱 native 强依赖、多格式、V3D 缓存”的全部优点算进 1.12 分支。** [默认 runtime](https://github.com/AndreaFrederica/OhMyYesSteveModel/blob/25ae5414d6be9108c7724d96b21632cc4d34d78b/runtime/README.md)、[V3D 实现](https://github.com/AndreaFrederica/OhMyYesSteveModel/blob/25ae5414d6be9108c7724d96b21632cc4d34d78b/runtime/v3d/src/main/java/cc/sirrus/ysmlib/v3d/V3dCache.java)、[1.12 固定树](https://github.com/AndreaFrederica/OhMyYesSteveModel/tree/62004ccb7667c138e8408efaff82e80dc72f6ee8)。

1.12 分支的支持文档仍有 Java 17 / Forge 47.4.3 描述，与同一提交实际 Java 25 / Cleanroom 配置不一致。因此不能把其中继承的测试数量和平台表当作该 1.12 分支的运行验收。[支持状态](https://github.com/AndreaFrederica/OhMyYesSteveModel/blob/62004ccb7667c138e8408efaff82e80dc72f6ee8/docs/status/support-and-verification.md)。查询两个固定提交的 GitHub checks / Actions 没有取得检查记录；这只代表本次没有观察到公开收据，不推断本机测试是否存在。[1.12 checks API](https://api.github.com/repos/AndreaFrederica/OhMyYesSteveModel/commits/62004ccb7667c138e8408efaff82e80dc72f6ee8/check-runs)、[1.12 Actions API](https://api.github.com/repos/AndreaFrederica/OhMyYesSteveModel/actions/runs?head_sha=62004ccb7667c138e8408efaff82e80dc72f6ee8)。

## 4. 方法级 UG 适配映射

以下全部指 `dev/1.12` 的固定提交 `62004ccb…`。

| 需求 | 实际类 / 方法 | 评估与缺口 |
| --- | --- | --- |
| YSM 根旋转 | `com.elfmcys.ysm.geckolib3.geo.GeoReplacedEntityRenderer.renderAnimatableEntity(...)`、`renderLivingAt(...)`、`applyRotations(EntityLivingBase,float,float,float)` | 第 71–72 行先定位再旋转；第 191–225 行保留原版 yaw、sleep / ladder 等处理。是可定位的接入点，但未提供 UG full-Q root 或身体中心 pivot 契约。 |
| 头身分离 | `AnimatableEntity.createAnimationEvent(...)`；`CustomHumanoidEntity.codeAnimation(...)` | 前者第 301–327 行读 body yaw 与 head yaw / pitch，并将 head yaw 限为 ±85°；后者第 65–89 行叠加 Head X / Y。不是完整 `inverse(Qbody) × Qhead` 解算，也没有 head roll 通道。 |
| 左上预览直立 | `RenderContext`；`RenderUtil.extractRenderContext()`；`RenderUtil.renderExtraPlayerEntity(...)` | 有 `inventory / paperDoll / offScreen` 等场景信号，可支持预览单独决策；尚无 UG 的“默认直立 / 可选跟随重力”配置。 |
| 扩展接口 | `RegisterRenderStateModifierEvent.addModifier(...)`；`RenderStateModifier.apply(...)`；`RenderModelEvent` / `RenderLayerEvent` | 有 API 声明；本次没有确认 modifier 从注册到运行调用的完整可达链，不能仅因接口存在就认定可以完全替代 Mixin。 |
| visual scale 与碰撞箱 | `GeoRenderData.widthScale / heightScale`；`CustomHumanoidEntity.getWidthScale / getHeightScale` | 是渲染输入。后续适配仍须保持展示比例与 UG 物理形状分离；本次未验证任何碰撞箱正确性。 |

源码：[GeoReplacedEntityRenderer](https://github.com/AndreaFrederica/OhMyYesSteveModel/blob/62004ccb7667c138e8408efaff82e80dc72f6ee8/src/main/java/com/elfmcys/ysm/geckolib3/geo/GeoReplacedEntityRenderer.java)、[AnimatableEntity](https://github.com/AndreaFrederica/OhMyYesSteveModel/blob/62004ccb7667c138e8408efaff82e80dc72f6ee8/src/main/java/com/elfmcys/ysm/geckolib3/model/AnimatableEntity.java)、[CustomHumanoidEntity](https://github.com/AndreaFrederica/OhMyYesSteveModel/blob/62004ccb7667c138e8408efaff82e80dc72f6ee8/src/main/java/com/elfmcys/ysm/client/entity/CustomHumanoidEntity.java)、[RenderContext](https://github.com/AndreaFrederica/OhMyYesSteveModel/blob/62004ccb7667c138e8408efaff82e80dc72f6ee8/src/main/java/com/elfmcys/ysm/geckolib3/geo/RenderContext.java)、[RenderUtil](https://github.com/AndreaFrederica/OhMyYesSteveModel/blob/62004ccb7667c138e8408efaff82e80dc72f6ee8/src/main/java/com/elfmcys/ysm/util/RenderUtil.java)、[API 声明](https://github.com/AndreaFrederica/OhMyYesSteveModel/tree/62004ccb7667c138e8408efaff82e80dc72f6ee8/src/main/java/com/elfmcys/ysm/api/rendering/v0)。

对比本地现有 UIE 源码，`MixinYsmPlayerBodyPose` / `YsmClassAbi` 仍精确定位旧 `com.elfmcys.yesstevemodel...GeoReplacedEntityRenderer`、`render(EntityLivingBase,IAnimatable,DDDFF)` 和 SRG root 方法 `func_77043_a`。新分支改为 `com.elfmcys.ysm...` 和 `renderAnimatableEntity(...)`。**不能仅修改版本白名单，或认为旧 Mixin 会自动兼容。** 本次 IntelliJ 确认 UG 项目索引有效；UIE 项目未在已打开索引中，因此对上述 UIE 文件改用精确路径 CLI 交叉核对。实际二进制兼容仍须以未来冻结 JAR 的 class / Mixin transform 结果为准。

本地核对文件：`D:/WI - Dev Workspace/NeoFontRender/addons/ui-enhancements/src/main/java/neofontrender/addons/mixin/MixinYsmPlayerBodyPose.java`、同根 `compat/YsmClassAbi.java`。历史需求 / ABI 指针见 [YSM 可选适配](../repair-plans/player-movement-ysm-20261007/04-YSM可选适配.md)，该旧规划不替代本次源码事实。

## 5. 本次发现的具体源码风险

### 5.1 行走动画状态表达式

`AnimatableEntity.getSwingMotionAniMathHelperreshold()` 第 167–168 行返回 `0.15f`；第 331 行：

```java
entityModelData.isMoving =
    limbSwingAmount <= -getSwingMotionAniMathHelperreshold()
    || limbSwingAmount <= getSwingMotionAniMathHelperreshold();
```

当前表达式等价于 `limbSwingAmount <= 0.15f`：输入 `0` 得 true，输入 `0.3` 得 false。静止 / 较大摆幅的分类与常见行走动画含义相反。这是可直接核对的表达式语义；本次未运行其动画消费链，也未在游戏中复现视觉结果，不能将它认定为我们现有 YSM 1.1.9 抽搐的根因。[固定源码](https://github.com/AndreaFrederica/OhMyYesSteveModel/blob/62004ccb7667c138e8408efaff82e80dc72f6ee8/src/main/java/com/elfmcys/ysm/geckolib3/model/AnimatableEntity.java#L301-L331)。

### 5.2 纸娃娃预览的异常恢复

`RenderUtil.renderExtraPlayerEntity` 第 480–507 行先设 `paperDoll=true`，push matrix / 关 shadow，调用 `renderEntity`，最后恢复。该方法没有 `try/finally` 包围这段借用状态：若渲染抛出异常，方法本身不能保证标记、矩阵与 shadow 正确恢复。这里是明确的异常路径风险，不是已复现的正常预览错误，也不能与同文件另一 GUI 方法已有的异常恢复混为一谈。[固定源码](https://github.com/AndreaFrederica/OhMyYesSteveModel/blob/62004ccb7667c138e8408efaff82e80dc72f6ee8/src/main/java/com/elfmcys/ysm/util/RenderUtil.java#L480-L507)。

### 5.3 公开接口声明与实际接线分开判断

1.12 树有 `RegisterRenderStateModifierEvent` 与 `RenderStateModifier`，但没有默认 feature 分支中的 `RenderStateModifiers` 实现类。已读 `AnimatableEntity.update / extractRenderData` 第 225–263 行也未见该调用。本次追踪范围不足以断言仓库所有扩展路径都不工作，因此状态为“接线未确认”，不是“接口已经可用”或“整个 API 已坏”。[1.12 API](https://github.com/AndreaFrederica/OhMyYesSteveModel/tree/62004ccb7667c138e8408efaff82e80dc72f6ee8/src/main/java/com/elfmcys/ysm/api/rendering/v0)、[状态提取](https://github.com/AndreaFrederica/OhMyYesSteveModel/blob/62004ccb7667c138e8408efaff82e80dc72f6ee8/src/main/java/com/elfmcys/ysm/geckolib3/model/AnimatableEntity.java#L225-L263)。

## 6. 后续接入应形成的窄接口

以下是本次评估建议，不是已授权实施任务，也不是声称上游已经提供的 API：

1. 提供一次渲染期间稳定的只读 body / head world quaternion、坐标基与 pivot 来源；以 UG / UIE 已接受状态作为来源，YSM 不取得物理移动权。
2. 在统一 root 变换入口应用身体姿态与中心补偿，让模型、持物和附着层继承一次变换；保留 sleep / riding / death 的明确委派。无重力围绕身体中心旋转不能靠换一个 yaw 参数成立。
3. 在代码动画前提供身体局部头姿态；地面普通行走与无重力手动姿态模式的头身规则按用户合同区分，不能全局放宽脖子角度。
4. 以显式 render context 控制世界 / paperDoll / inventory / offScreen；预览重力选项默认 false，关闭时保持正常预览朝向和动画。场景借用须支持嵌套、异常恢复，不能只依赖无保护的全局 boolean。
5. 若将来选择该分支，先固定 0.6.8 目标及 native / 本地依赖来源，再独立处理新 ABI。随后才谈原候选的六向碰撞箱对照、Free 中心旋转、左右持物、第一人称、预览、远端双玩家等运行验收。

当前推荐定位：**作为后续独立候选与接口设计参考；保持当前 YSM 1.1.9 修复线，不把此研究当作替换完成。** 这里没有 FPS、内存或并发测量，不能声称它比现版本更快、更省资源，或能够解决 UG 的粘脚 / 飞行回弹。

## 7. 本次交付边界

已完成固定 refs / 源码 / 构建配置 / 发布元数据核对，形成方法级兼容判断和具体 SOURCE 风险。未执行该项目 Gradle、native 构建、单元测试、客户端 / 专服 / GPU / 双玩家运行；未改 UG / UIE Java、配置、依赖或产物。作者的 README 与测试源码仅按其实际证据层引用，不升级为本地通过。
