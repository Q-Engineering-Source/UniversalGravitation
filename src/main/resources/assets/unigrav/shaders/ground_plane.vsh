#version 130

uniform sampler2D uDepthTex;
uniform mat4 uInverseClip;
uniform vec2 uViewport;
uniform vec3 uHitRelative;
uniform vec3 uNormal;
uniform vec3 uTangent0;
uniform vec3 uTangent1;
uniform vec2 uAnchorWindow;
uniform float uDepthNear;
uniform float uDepthFar;
uniform float uClearDepth;

flat out float vAnchorTrust;

const float CLEAR_EPSILON = 2.0 / 16777215.0;
const float PLANE_TOLERANCE = 1.0 / 64.0;

bool clearDepth(float depth) {
    return isnan(depth) || isinf(depth) || abs(depth - uClearDepth) <= CLEAR_EPSILON;
}

/* Three-by-three is a pixel-centre reconciliation probe, not a surface expansion. */
float anchorTrust() {
    if (uViewport.x <= 0.0 || uViewport.y <= 0.0 || uDepthNear == uDepthFar) return 0.0;
    vec2 base = floor(uAnchorWindow);
    float trusted = 0.0;
    for (int y = -1; y <= 1; ++y) {
        for (int x = -1; x <= 1; ++x) {
            vec2 local = base + vec2(float(x) + 0.5, float(y) + 0.5);
            if (local.x < 0.0 || local.y < 0.0 || local.x >= uViewport.x || local.y >= uViewport.y) continue;
            float depth = texture2D(uDepthTex, local / uViewport).r;
            if (clearDepth(depth)) continue;
            vec4 homogeneous = uInverseClip * vec4(2.0 * local / uViewport - 1.0,
                    2.0 * ((depth - uDepthNear) / (uDepthFar - uDepthNear)) - 1.0, 1.0);
            if (isnan(homogeneous.w) || isinf(homogeneous.w) || abs(homogeneous.w) <= 1.0e-10) continue;
            vec3 point = homogeneous.xyz / homogeneous.w;
            if (any(isnan(point)) || any(isinf(point))) continue;
            vec3 tangent = point - uHitRelative;
            vec4 lowerH = uInverseClip * vec4(2.0 * local / uViewport - 1.0,
                    2.0 * ((clamp(depth - 1.0 / 16777215.0, 0.0, 1.0) - uDepthNear) / (uDepthFar - uDepthNear)) - 1.0, 1.0);
            vec4 upperH = uInverseClip * vec4(2.0 * local / uViewport - 1.0,
                    2.0 * ((clamp(depth + 1.0 / 16777215.0, 0.0, 1.0) - uDepthNear) / (uDepthFar - uDepthNear)) - 1.0, 1.0);
            if (any(isnan(lowerH)) || any(isinf(lowerH))
                    || any(isnan(upperH)) || any(isinf(upperH))
                    || abs(lowerH.w) <= 1.0e-10 || abs(upperH.w) <= 1.0e-10) continue;
            vec3 lower = lowerH.xyz / lowerH.w;
            vec3 upper = upperH.xyz / upperH.w;
            if (any(isnan(lower)) || any(isinf(lower))
                    || any(isnan(upper)) || any(isinf(upper))) continue;
            float sigma = max(abs(dot(lower - point, uNormal)), abs(dot(upper - point, uNormal)));
            float tau = 1.0 / 1024.0 + 2.0 * sigma;
            if (!isnan(sigma) && !isinf(sigma) && tau < PLANE_TOLERANCE
                    && abs(dot(tangent, uNormal)) <= tau
                    && abs(dot(tangent, uTangent0)) <= 1.0
                    && abs(dot(tangent, uTangent1)) <= 1.0) trusted = 1.0;
        }
    }
    return trusted;
}

void main() {
    /* Compatibility-profile immediate fullscreen triangle: caller supplies clip vertices. */
    gl_Position = gl_Vertex;
    vAnchorTrust = anchorTrust();
}
