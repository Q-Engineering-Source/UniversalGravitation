# Scene-depth ground-plane preview

## Confirmed request and scope

用户明确要求替换为基于场景深度的表面叠加，且只叠加目标地平面的面。保留Alt+H hold FREE/fresh face selection/release commit、Ctrl+H toggle、六法线与既有Q/Mouse/roll/boost/drift控制。2026-09-05最新增量将选面距离从64m提高到256m，并把128m直径的方形截断改成柔边圆形：半径56m内完整、56–64m平滑淡出，exact hit为圆心。射线决定选面距离，圆形窗口决定可见同平面表面的覆盖范围；不扩展为整屏地形染色或矿物透视，也不恢复逐格CPU扫描。完整增量见`ground-plane-radial-256.md`。

目标面指命中点所定义的实际平面及朝向，不只是任意平行表面；需要排除其他高度、与平面相交的墙面和清空深度/天空。球形扫描动画、矿物发现/服务端扫描/S2C和其它装备不在本轮。

这条路径替代几何贴面网格及尚未部署的depth-range候选，不双轨常驻或静默fallback到旧几何路径。旧候选7F47C5B6...3668695仅保留历史快照，其171 tests不能替代新路径验收。当前实际Z/专服仍是9F1B9CAA...E17C0E。

## Repository facts for Pro design

- UIE public CameraApi v2 / Flight v9 / Input v1可提供同帧frame/pose/analytic lens与原生block hit，但没有scene-depth texture/FBO/render-pass公开接口；不能把CameraProjection.depth视为真实深度缓冲。
- Shader compatibility enum只有NONE/SHADERS_MOD/OPTIFINE，而且实现按class存在判断类型，不能当作光影包当前启用的证据。当前用户客户端有OptiFine但没有已确认的active shader-pack信息。
- RWL在opaque/cutout terrain、entities pass0、selection/damage/particles/weather以及translucent terrain/pass1 entities之后、手部之前。Scene depth不是terrain-only；translucent层默认depthMask(false)，故深度不完整表达透明遮挡，也没有物体身份。
- 一般Forge RWL没有terrain-only capture hook；本轮不新增UG相机/地形Mixin，也不假定存在UIE深度句柄。是否需要额外公共接缝必须由真实事实确认，不编造API。
- 正确世界几何原点是renderViewEntity插值feet/proxy；UIE/原版已经把eye/orbit/full-Q变换放进实际view矩阵。反投影需使用真实同帧投影/矩阵/viewport/depth约定，不复制参考附属固定near/far及aspect修正公式。
- 准确只读参考为MekanismMoreMekaSuitModules中的OreVisualEnhancementRenderer与ore_visual_scanner.fsh；其扫描波会改挂主FBO深度附件，并以屏幕深度着色。只借鉴方法，不直接复制侵入式attachment替换、异常吞掉或不足的资源生命周期。

## Design gate / validation

2026-09-04咨询历史：指定官方ChatGPT Pro会话一度显示`Internal Server Error`，当时按项目fail-closed暂停。用户随后在网页端恢复咨询并授权只读监看。13:33 UTC读取到完整回复（页面显示思考21m7s及回复操作），监看结束；用户随后明确“推进”，恢复实现。原始回复位于指定会话，前半17×17方案由后半128×128修订覆盖；不能将顾问答复直接记作工程或GPU验证。

采用Pro主体方案：标准单采样主FBO的D24/D24S8深度精确复制到UG自有纹理/FBO，以实际同帧矩阵反投影，使用有硬上限的精度容差、目标平面距离、四邻域表面朝向和切向窗口共同筛选。仅青色透明fill，首版不加细网格。游戏原有深度/stencil附件不替换、不清空；失败不回退到旧几何。由native hit附近3×3深度样本做GPU端同帧一致性检查，不持续CPU读回。

参数更新：答复仍沿用32m射线，本项目服从用户最新64m要求；128×128m切向窗口以exact hit为中心，半边长64m。沿用顾问给出的几何上界方法，重建安全半径相应设160m（64+64√2<155m并保留余量），不继续使用答复基于32m推得的128m上限。该值仅排除不可信重建坐标，不扩大选面射线。平面容差仍硬限1/64m，精度不足时渐隐/拒绝，不染其他高度。

2026-09-05当前增量覆盖上一段历史参数：选面256m通过同一UIE measurement的四段64m native pick完成；预览为半径64m圆盘而不是方形；重建guard相应为328m。该guard仅排除不可信坐标，不扩大选面射线或深度容差。平面容差仍硬限1/64m，精度不足时渐隐/拒绝，不染其他高度，也不强制加载客户端区块或增加render distance。

已知限制：深度没有block/entity身份，共面同向实体可能被着色；玻璃、水、粒子等不写depth的前景不提供完整遮挡；任意shader pack不能由局部一致性检查证明兼容。首版按实际FBO/attachment/format/sample/clip约定fail-closed，不因仅安装OptiFine而禁用。exact block provenance或完整透明遮挡若成为绝对要求，需要新增公开上游渲染接口，不能由本路径假装实现。

资源生命周期：选面结束或MISS时排队释放尺寸相关的深度纹理/FBO，已链接program在当前世界/context内缓存，避免每次准星短暂离开方块就重新编译shader。资源重载、离开世界/断线或adapter close会连同program一起失效；RenderTick在没有RWL的菜单中也处理删除。标准Display重建以已核验的drawable实例识别，旧context资源名只丢弃、不会在新context误删；不在JVM shutdown hook中执行GL。

2026-09-04开发交付：Standards与Spec最终P0/P1/P2均为0；最终新鲜build9/9任务执行，170 tests/27 suites全部通过。发布JAR290427 bytes，SHA-256为`D206B70A39585637D2F2CFA0253F268CBE97CA2B42B84D200EE2FF6DF1EBFEC8`，源/专服/Z客户端回读一致。专服已验证Done(8.893s)、原stdin stop保存/卸载/exit0，最终PID与监听均消失；测试包原有AE2签名ERROR单独保留，不声明零错误环境。没有启动本机3D客户端。

详细命令、session、产物、备份及层级证据见`.pensieve/.state/scene-depth-plane/verification.md`，独立GPU步骤见`docs/ground-plane-scene-depth-acceptance.md`。本机未执行GLSL compiler/driver shader link，真正画面和GPU行为未验收；构建与专服通过不能替代它。新反馈“过俯仰以纠偏而非输入截断”本轮按用户允许保持现状，不改变已通过的控制路径。

Real GPU acceptance: not executed
