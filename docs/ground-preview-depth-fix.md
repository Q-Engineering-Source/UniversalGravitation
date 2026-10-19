# Ground preview depth/layer correction

## 现象与证据

用户回报v2范围功能通过，但高亮有图层冲突；提供的第一人称截图中，青色地面网格及填充出现宽断带，HUD/手部仍可见。原图保存在`.pensieve/.state/ground-preview-depth-fix/reported-banding.png`，SHA256 `DE67F4823C31BEB6960E80944C07DCF4B359461FC4DE05733ADB11FAC027B579`。单张图不证明动态闪烁或唯一根因；用户进一步区分断带与中央仪表交叠的回复尚待补充。

基线JAR为`9F1B9CAAEEAEB5FF4105EFA5DF647E239CB91CDA121EEB79EE64406203E17C0E` / 263286 bytes。规范Z客户端磁盘identity一致；客户端19:23:06–19:34:43运行窗口记录UG0.1.0、24mods、OpenGL4.6 NVIDIA596.36，无ERROR/FATAL并正常停止。已收到用户功能回执，但视觉缺陷尚未关闭。

## 完整Pro意见及采用边界

2026-09-04通过指定官方内置Pro会话发送源码事实包；页面显示“思考了12m38s”、完整回复与回复操作、停止生成按钮消失。完整意见已读取。顾问排序：共同深度竞争最符合现象，float打包前未相对化可能放大，继承GL状态/自定义shader仍属未知；共享线重复主要影响亮度，不能解释fill一起消失。

- CPU double中计算`(worldPoint - renderViewEntityFeetProxy) + outwardNormal * 0.002`，随后才交给FLOAT position channel；移除重复GL矩阵`-viewer`平移。同一组相对顶点供fill/line复用，不改CameraFrame/picking/Q。
- `POSITION_COLOR`经实际Cleanroom source核实为POSITION_3F+COLOR_4UB；`BufferBuilder.pos`在加内部translation后cast float。实现必须确认内部translation不会引入第二次offset；不直接改共享buffer的未知translation状态。
- 对正常forward `near<far + LESS/LEQUAL` 与 reversed-range `near>far + GREATER/GEQUAL`，使用包含相等的LEQUAL/GEQUAL比较，`far'=far+2^-20*(near-far)`，near不变。对fill和native GL_LINES统一生效，保持depth test、不写depth。
- 该bias的window-depth位移上界为`2^-20*abs(far-near)`；不是无条件“零穿透”，世界长度上界依赖投影和距离，GPU需检查近共面及明显前景遮挡。保留0.002几何nudge，不同时修改半径/网格去重/alpha/line-width/姿态。
- 捕获并恢复depth func/range、front/back polygon modes、offset fill/line/point enables和factor/units；draw期间polygon FILL、全部polygon offset关闭，避免fill和native lines继承不同规则。已核实GlStateManager提供fill enable/disable、doPolygonOffset与depthFunc，mode无cache，range及line/point offset无public cache wrapper。
- 主线程保守收紧：不支持或不一致的depth约定不猜符号，也不在GL_ALWAYS下画X-ray；跳过3D预览并在选面F3状态报告unsupported，选面真值及提交不变。自定义gl_FragDepth/后续composite可能绕过固定功能bias，若GPU仍失败须收集当帧实际状态，不盲加常数。

参考官方语义：[glDepthRange](https://registry.khronos.org/OpenGL-Refpages/gl4/html/glDepthRange.xhtml)、[glPolygonOffset](https://registry.khronos.org/OpenGL-Refpages/gl4/html/glPolygonOffset.xhtml)。native GL_LINES不能靠POLYGON_OFFSET_LINE修复，后者只作用于以line模式栅格化的polygon。

## 用户指定附属参考（已确认）

用户补充的准确仓库是`D:/WI - Dev Workspace/MekanismMoreMekaSuitModules`，模块`ore_visual_enhancement_unit`（矿物视觉增强单元），本地HEAD `e73e528158bb0fc7fbadd554f30ef63be546c587`，核查时worktree clean。本轮只读，未修改/构建/运行该附属。

- `src/main/java/moremekasuitmodules/client/OreVisualEnhancementRenderer.java:115` 是RWL入口；`:159` 对矿物cluster线框主动关闭深度测试，标签`:209`也关闭，所以矿物透视轮廓不能直接当作正常遮挡的地平面修复。
- 同文件`:218–271` 处理相邻矿块外轮廓及共享边消去，`:263–268` 在CPU中减观察坐标后提交顶点。当前UG候选已独立采用相同的“先相对化、后float打包”原则；尚未引入外轮廓去重，不把alpha变化混入本次深度修复。
- 同文件`:396–490` 是独立扫描波层：RenderTick START挂接scene depth texture，RWL时全屏pass读取该深度；`assets/moremekasuitmodules/shaders/ore_visual_scanner.fsh:25–42` 从depth重建可见场景位置并按扫描半径着色。它不是额外贴在每个方块上的共面quad。
- 此屏幕深度方案是另一种表面叠加架构，不等于当前已通过171项测试的轻量depth-range候选。采用它需另外处理UIE同帧投影/相机原点、目标平面与法线筛选、framebuffer/stencil ownership、shader兼容、resize/reload/退出时释放资源，不能直接复制该附属的FBO depth attachment替换或固定near/far公式。
- 源文件shader初始化`:515–536`会吞掉异常并禁用扫描波；effect期满`:396–415`才卸载depth texture，未找到统一reload/退出销毁shader路径。这些是移植时需重新设计的边界，不在本轮替用户修改附属仓库。

准确参考已核对。当前业务候选仍保持不变、尚未部署；需要区分“测试轻量修复当前断带”与“改为同类屏幕深度表面叠加”两种范围，不能将后者当成仅改一个GL偏移常数。

## 验证口径

本机禁3D；诊断技能的自动GPU复现环无法在本节点执行，当前使用用户截图作为人工失败基线。纯Java/recording测试只证明真实生产坐标提交、depth-plan方向/上界和状态恢复契约，不能证明这张图已经修复。

待验证：六法线/大正负坐标相对化与0.002符号、fill和line复用；forward/reversed/非默认range及unsupported分支；状态正常/异常恢复；旧控制文件hash及全量测试；同产物dedicated smoke和Z单次替换。随后由用户同场景静止/缓慢移动、远近及墙面GPU复测，检查断带消失且不穿墙，HUD/手部无状态污染。

Real GPU acceptance: not executed
