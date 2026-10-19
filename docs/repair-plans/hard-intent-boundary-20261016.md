# 六向目标剩余核心意图：hard只读方法边界

来源：hard会话01a0de5a-21a2-7960-970e-d19bfb0c6c44于2026-10-16向Mainline的实际只读回执。不是产品或运行通过记录；动态进度仍在唯一推进账本。完整背包/耗材与后续NFR GUI延期保持。

用户确认：G实操具有姿态背包，额外耗材抵抗真实重力；有重力近地可自由选择行走或保持飞行；无重力且无适用重力地板/磁力地板/磁力靴则悬停。长按Space脱离重力/磁力，悬停可选；短按仍正常跳跃，禁止立即重新附着。此处只实现当前六向必要核心意图，不扩成完整装备系统。

## 已核实的021边界

Policy.floorEligibility只表达personalG与配置资格，不表达walk/flight。Controls当前无mode/hover或hold操作身份。现jump上升沿和confirmed support、JUMP_RELEASE_PROTECTED只阻止立即重附着，不取消范围内吸引。Free当前保留acceptedVelocity并积分实际acceleration，不自动制动到0，不是完整hover；creative personalZeroG推进也不能冒称实际生存背包资格。

## 采用的最小责任边界

1. server Owned/Travel拥有独立LocomotionIntent、HoverIntent、effective mode/拒绝原因和DetachLatch。G意图、真实source/evidence、合力、Free/Cardinal形状、控制权限分开，不以零合力决定无reference。靠近地面不能改写FLIGHT；WALK须真实资格/支撑/占位许可，碰撞继续有效。
2. 一次实际输入采样；Controls可增加明确typed intent及press/detach身份，server按scope/connection/proposal/input sequence/单调requestId去重。STATE/OWNED回传accepted intent/effective mode/拒绝/脱附状态。模式先由实际公开命令/API/键位承载，无需当前NFR GUI。不得借G、LOCK/UNLOCK或boost隐式编码。
3. 长按以同press的新鲜held输入及真实owner simulation tick累积，不按packet数、重传数或客户端自报duration。阈值独立配置/边界测试，不复用jumpTicks=10。松开、失焦、死亡、scope/TP或输入超时取消hold；过期held不得触发。跨阈值消费一次。兼容短跳采用press立即正常jump，继续hold再脱附；同press不重复跳。若要求长按绝不先跳才需重新确认交互，本轮不擅自改变短跳延迟。
4. 短跳原保护保留，显式detach另外记录owner/correction/source/surface/request身份；挡重新选reference、Free→Cardinal、postcommit刷新与相关引磁约束消费，raw evidence继续保留。FLIGHT持续禁止自动acquire，显式WALK/attach且release已确认后才允许真实几何捕获；一次性detach至少release并离开原capture域再重入，不以数tick超时在原面重吸。邻接面也不能绕过FLIGHT。
5. hover是实际控制行为。静止zeroG可保Free，但有初速需同预算有界制动/速度目标控制，不直接velocity=0或锁position。带重力的hover须真实已授权控制能力，rawforce与补偿分开；hover off保真实重力/惯性。当前可用既有Creative测试资格；不得把G boolean包装成免费生存装备权限。完整生存装备/耗材延期，不能宣称其悬停交付。
6. mode/detach改变预测数学路径时必须使旧walk bootstrap合法退役并按实际资格重建；不让server飞行而client继续walk重放。真实已处理slot才给boundary/outcome，未处理请求不ACK；沿用018 owner/同预算和022共享basis，不新增camera/Q/移动writer。

## 最小必要验证

六frame短跳单次及原保护；hold阈值前/等于/后、重复/乱序/timeout/失焦/TP；同surface脱附多tick不重吸、邻面不绕FLIGHT、显式WALK仅几何许可后附着；有重力近地两种选择；地板/磁靴/无reference/合力相消分别核资格。zeroG静止和带初速hover开/关分开，gravity hover资格不足明确拒绝；新mode同步/重复请求/真实boundary/预测退役；同budget无退款，native碰撞仍阻挡。最后必须真实Space长短按、近地选择及姿态/速度轨迹，不用枚举单元测试冒称真实验收。
