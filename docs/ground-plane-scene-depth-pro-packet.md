# Read-only Pro Advisor Contract: scene-depth ground-plane preview

Status: blocked on 2026-09-04. The designated official ChatGPT Pro page showed `Internal Server Error` before a complete response. This packet includes the latest user changes: **64m picking distance and 128x128m surface coverage**. Earlier 32m / 17x17 requirements are superseded. Codex has not implemented or deployed this architecture.

Designated advisor: https://chatgpt.com/c/6a9a8e74-b9e4-83e8-b333-41d48d86953d

## Role and goal

You are a read-only technical advisor. Codex owns repository truth, implementation, Git, testing and acceptance. Do not assume workspace access, edit files, make external changes, or invent APIs. Please return a complete design based on the verified facts below.

The user explicitly requests replacing the existing world-geometry ground-plane highlight with a **scene-depth-derived surface composite**, inspired by the scanner wave in MekanismMoreMekaSuitModules. Your previous depth-range-biased mesh proposal is an unshipped historical candidate, not this architecture. Do not continue that old route or retain it as a silent fallback.

## Latest product contract

- Preserve the accepted single full-quaternion FREE camera, optional horizon alignment, Alt+H hold temporary FREE / fresh native block-face pick / release commit outward normal as UP, and Ctrl+H alignment master toggle.
- Change native picking reach to **64m**. Preview coverage is **128x128m**, centered at the native hit point, with half-extent64m on the two tangent axes. Reach and coverage are independent. Do not replace the screen-space pass with a128x128 CPU block scan.
- Color only visible surfaces on AND facing the plane defined by the native hit position and outward axis normal. Exclude different-height parallel platforms, walls merely intersecting the plane, opposite-facing surfaces and sky. Preserve the cyan translucent style; a grid is optional if it does not significantly expand implementation.
- Hide the overlay on invalid target/sample/session/focus or untrusted capture. Never use X-ray mesh rendering or a coplanar sheet fallback. No spherical wave, ore discovery, gear/fuel, gravity, velocity, input-controller or network changes.
- Be honest about depth-only limitations: no object identity and no complete non-depth-writing transparent occlusion. If a strict requirement needs a new public hook, identify the exact boundary rather than promise impossible correctness.

## Verified repository facts

- Minecraft1.12.2, Cleanroom0.6.8-alpha, Java25. UG links public UIE Camera API2 / Flight9 / Input1 compileOnly; no UIE classes are bundled. No new dependencies, reflection, UG camera/input/picking/terrain Mixins or UIE implementation imports are authorized.
- One Q maps local+Z forward,+Y up,+X left to world. CameraApi.measure(partialTicks).interactionTarget(MEASUREMENT, distance, false, false) supplies the native block hit. Current immutable Target carries sampleId, BlockPos, EnumFacing, reference and plane coordinate. RWL preview uses the same sample; alignment control remains client END.
- CameraApi public measure/project/screenRay/lens APIs are analytic. There is no public depth texture, framebuffer or render-pass handle. CameraProjection.depth is not scene-buffer depth. CameraFrame has pose/bases/positions but no matrices.
- shaderCompatibility() returns NONE/SHADERS_MOD/OPTIFINE from class PRESENCE, not active-pack state. shaderResolutionMultiplier()==1 does not prove inactive. The user's working client has OptiFine; do not disable the feature merely because OptiFine exists. The latest readable GPU log reports NVIDIA OpenGL4.6; active shader-pack identity is unconfirmed.
- RenderWorldLast follows opaque/cutout terrain, entities pass0, vanilla selection/damage, particles/weather, translucent terrain and entity pass1; it precedes the hand pass and its depth clear. Translucent terrain/pass1 use depthMask(false). Thus RWL depth is not terrain-only and cannot distinguish all transparent foreground content. No public terrain-only capture hook was found.
- Actual world vertices are relative to interpolated renderViewEntity FEET/proxy. Vanilla orientCamera and UIE CameraSetup already include eye/orbit/full-Q view transform. Capture actual same-frame GL model-view/projection/viewport/depth range; never add eye position twice or reconstruct from guessed FOV/near/far/Euler.
- Minecraft Framebuffer publicly exposes framebufferObject, framebufferTexture, depthBuffer, useDepth, framebufferWidth/Height, framebufferTextureWidth/Height and isStencilEnabled(). The actual implementation creates DEPTH_COMPONENT24 or DEPTH24_STENCIL8 renderbuffers. OpenGlHelper has FBO and shader operations. Validate capability and actual attachment/viewport/sample state before using a route; do not assume custom FBO/MSAA compatibility.
- Current ClientProxy owns the camera registration for process lifetime. The camera adapter is registered on the Forge bus and has close(), but close currently has no normal ClientProxy shutdown caller. Existing preview cleanup clears CPU state on tick/RWL lifecycle guards. There is no current client GPU resource-reload/world-unload cleanup; server-only WorldEvent handlers are not a client cleanup solution.
- The deployed v2 mesh JAR remains SHA256 9F1B9CAAEEAEB5FF4105EFA5DF647E239CB91CDA121EEB79EE64406203E17C0E. The unshipped mesh-bias candidate is7F47C5B6A58129271F997E7DD5E6EC5417FE0D8C3C047CBFA9C8F191E3668695 (171 unit tests passed). Neither proves any new scene-depth implementation.

## Exact reference inspected

Read-only repository: D:/WI - Dev Workspace/MekanismMoreMekaSuitModules, commit e73e528158bb0fc7fbadd554f30ef63be546c587.

OreVisualEnhancementRenderer and ore_visual_scanner.fsh contain two different visual paths:

1. Ore outlines/labels deliberately disable depth for X-ray. This is NOT the requested reference behavior.
2. A fullscreen scene-depth scanner wave replaces the main FBO depth attachment at RenderTick START, temporarily restores the old renderbuffer for compositing, then reattaches its depth texture. The fragment shader uses approximate (2+aspect)*near*far depth linearization, fixed near=.05, far=renderDistance*16, and normalized inverse-MVP corner rays. Shader failures are swallowed and resize/reload/disconnect cleanup is incomplete.

Borrow only the scene-depth surface-compositing method. Prefer a non-invasive copy into UG-owned resources. Do not copy attachment ownership changes, approximate linearization or incomplete lifecycle.

## Questions and required output

1. Select a small safe capture route (depth-texture copy or private depth-FBO blit). Specify exact event/read-versus-draw bindings, formats, viewport offsets, sample-count guards, and restoring foreign bindings. Prefer no foreign attachment replacement and no feedback loop. Define supported conditions and fail-closed diagnostics without pretending universal shader-pack support.
2. Provide exact same-frame unprojection using actual inverse projection/model-view, OpenGL column-major conventions and depth-range mapping into coordinates relative to viewer feet. Address singular matrices, clear depth, reversed/unsupported conventions, large world positions and finite precision at the expanded range. Give a narrow pure-Java matrix/data seam and GLSL130-compatible pseudocode.
3. Define plane-distance AND surface-facing masks, neighbor/derivative normal estimation, discontinuity handling, grazing-angle rejection, opposite faces, tangent coverage and optional minification-safe grid. Quantify what can be guaranteed. If uncertainty prevents distinguishing nearby surfaces, fade/reject; do not grow tolerance until other heights become colored.
4. Define full-screen composite state ownership: preserve destination alpha, read/draw FBO, viewport/scissor, active texture and bindings, program, depth/stencil/color masks, matrices/client vertex state, and exact restoration on exceptions. Fixed depth can be disabled only during the depth-masked color composite, never to draw X-ray geometry. Include deterministic creation/resize/reload/world-close cleanup and meaningful failure reporting.
5. Give implementation order and validation: pure projection/unprojection roundtrips, selected plane versus wall/other-height/foreground/sky/bounds fixtures, numerical/shader agreement boundaries, recording state/resource lifecycle tests and a minimal real-GPU checklist. State assumptions, invariants, failure modes, alternatives and unknowns.

The development host MUST NOT launch Minecraft3D or software-rendered acceptance. Compilation, pure tests and dedicated-server classloading smoke are available. Actual shader/render correctness requires the user's independent real-GPU host and attributable screenshots/logs/video. Do not conflate these gates.

Real GPU acceptance: not executed
