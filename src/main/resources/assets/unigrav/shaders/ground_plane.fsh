#version 130

uniform sampler2D uDepthTex;
uniform mat4 uInverseClip;
uniform vec2 uViewport;
uniform vec2 uViewportOrigin;
uniform vec3 uEyeRelative;
uniform vec3 uHitRelative;
uniform vec3 uNormal;
uniform vec3 uTangent0;
uniform vec3 uTangent1;
uniform float uDepthNear;
uniform float uDepthFar;
uniform float uClearDepth;

flat in float vAnchorTrust;

const float D24_STEP = 1.0 / 16777215.0;
const float CLEAR_EPSILON = 2.0 * D24_STEP;
const float RADIUS = 64.0;
const float FULL_COVERAGE_RADIUS = 56.0;
const float RECONSTRUCTION_GUARD = 328.0;
const float COS_20 = 0.9396926208;
const float COS_12 = 0.9781476007;

bool clearDepth(float depth) {
    return isnan(depth) || isinf(depth) || abs(depth - uClearDepth) <= CLEAR_EPSILON;
}

bool finiteValue(float value) { return !isnan(value) && !isinf(value); }
bool finiteVector(vec3 value) { return !any(isnan(value)) && !any(isinf(value)); }

vec3 unproject(vec2 localPixel, float depth, out bool valid) {
    float depthRange = uDepthFar - uDepthNear;
    if (uViewport.x <= 0.0 || uViewport.y <= 0.0 || abs(depthRange) <= 1.0e-10) {
        valid = false;
        return vec3(0.0);
    }
    float ndcDepth = 2.0 * ((depth - uDepthNear) / depthRange) - 1.0;
    vec4 homogeneous = uInverseClip * vec4(2.0 * localPixel / uViewport - 1.0, ndcDepth, 1.0);
    valid = finiteValue(homogeneous.w) && abs(homogeneous.w) > 1.0e-10;
    vec3 result = valid ? homogeneous.xyz / homogeneous.w : vec3(0.0);
    valid = valid && finiteVector(result);
    return result;
}

float coverageFade(vec3 point) {
    vec3 tangent = point - uHitRelative;
    float radius = length(vec2(dot(tangent, uTangent0), dot(tangent, uTangent1)));
    return finiteValue(radius) ? 1.0 - smoothstep(FULL_COVERAGE_RADIUS, RADIUS, radius) : 0.0;
}

bool neighbourOnPlane(vec3 point) {
    return length(point - uEyeRelative) <= RECONSTRUCTION_GUARD
            && abs(dot(point - uHitRelative, uNormal)) <= 2.0 / 64.0
            && coverageFade(point) > 0.0;
}

void main() {
    if (vAnchorTrust < 1.0) discard;
    vec2 local = floor(gl_FragCoord.xy - uViewportOrigin) + vec2(0.5);
    if (local.x < 1.0 || local.y < 1.0 || local.x >= uViewport.x - 1.0 || local.y >= uViewport.y - 1.0) discard;
    float centerDepth = texture2D(uDepthTex, local / uViewport).r;
    float leftDepth = texture2D(uDepthTex, (local + vec2(-1.0, 0.0)) / uViewport).r;
    float rightDepth = texture2D(uDepthTex, (local + vec2(1.0, 0.0)) / uViewport).r;
    float downDepth = texture2D(uDepthTex, (local + vec2(0.0, -1.0)) / uViewport).r;
    float upDepth = texture2D(uDepthTex, (local + vec2(0.0, 1.0)) / uViewport).r;
    if (clearDepth(centerDepth) || clearDepth(leftDepth) || clearDepth(rightDepth)
            || clearDepth(downDepth) || clearDepth(upDepth)) discard;
    bool validCenter; bool validLeft; bool validRight; bool validDown; bool validUp;
    vec3 center = unproject(local, centerDepth, validCenter);
    vec3 left = unproject(local + vec2(-1.0, 0.0), leftDepth, validLeft);
    vec3 right = unproject(local + vec2(1.0, 0.0), rightDepth, validRight);
    vec3 down = unproject(local + vec2(0.0, -1.0), downDepth, validDown);
    vec3 up = unproject(local + vec2(0.0, 1.0), upDepth, validUp);
    if (!validCenter || !validLeft || !validRight || !validDown || !validUp
            || length(center - uEyeRelative) > RECONSTRUCTION_GUARD) discard;
    float distance = abs(dot(center - uHitRelative, uNormal));
    bool validLower; bool validUpper;
    vec3 lower = unproject(local, clamp(centerDepth - D24_STEP, 0.0, 1.0), validLower);
    vec3 upper = unproject(local, clamp(centerDepth + D24_STEP, 0.0, 1.0), validUpper);
    if (!validLower || !validUpper) discard;
    float uncertainty = max(abs(dot(lower - center, uNormal)), abs(dot(upper - center, uNormal)));
    float requiredTolerance = 1.0 / 1024.0 + 2.0 * uncertainty;
    if (!finiteValue(uncertainty) || !finiteValue(requiredTolerance) || requiredTolerance >= 1.0 / 64.0
            || distance > requiredTolerance) discard;
    float coverage = coverageFade(center);
    if (coverage <= 0.0 || !neighbourOnPlane(left) || !neighbourOnPlane(right)
            || !neighbourOnPlane(down) || !neighbourOnPlane(up)) discard;
    vec3 horizontal = right - left;
    vec3 vertical = up - down;
    float horizontalLength = length(horizontal);
    float verticalLength = length(vertical);
    float leftLength = length(left - center);
    float rightLength = length(right - center);
    float downLength = length(down - center);
    float upLength = length(up - center);
    if (horizontalLength <= 0.0 || verticalLength <= 0.0 || horizontalLength > 2.0 || verticalLength > 2.0
            || leftLength <= 0.0 || rightLength <= 0.0 || downLength <= 0.0 || upLength <= 0.0
            || leftLength > 1.0 || rightLength > 1.0 || downLength > 1.0 || upLength > 1.0
            || max(leftLength, rightLength) / min(leftLength, rightLength) > 4.0
            || max(downLength, upLength) / min(downLength, upLength) > 4.0) discard;
    vec3 reconstruction = cross(horizontal, vertical);
    float area = length(reconstruction) / (horizontalLength * verticalLength);
    if (area <= 0.1) discard;
    reconstruction /= length(reconstruction);
    vec3 eyeOffset = uEyeRelative - center;
    float eyeLength = length(eyeOffset);
    if (!finiteValue(eyeLength) || eyeLength <= 1.0e-10) discard;
    vec3 toEye = eyeOffset / eyeLength;
    if (dot(reconstruction, toEye) < 0.0) reconstruction = -reconstruction;
    float normalAgreement = dot(reconstruction, uNormal);
    float facing = dot(uNormal, toEye);
    if (normalAgreement <= COS_20 || facing <= 0.05) discard;
    float alpha = coverage * smoothstep(COS_20, COS_12, normalAgreement)
            * smoothstep(0.05, 0.15, facing) * (1.0 - smoothstep(1.0 / 128.0, 1.0 / 64.0, requiredTolerance));
    if (!finiteValue(alpha) || alpha <= 0.0) discard;
    gl_FragColor = vec4(0.0, 1.0, 1.0, 0.28 * alpha);
}
