#version 330
#include stealth_and_alert:vision_field

in vec4 vertexColor;
in vec2 fieldUV;
flat in int fieldPlane;
uniform sampler2D FieldAtlas;
out vec4 fragColor;

// 0: original outline RGBA; 1: unified surface; 2: individual flashlight beam.
uniform float RiskColoring;
uniform float MaxOpacity;

vec3 dangerColor(float risk) {
    vec3 green = vec3(0.20, 0.80, 0.34);
    vec3 yellow = vec3(1.00, 0.86, 0.10);
    vec3 red = vec3(1.00, 0.16, 0.10);
    // danger = 120 / (120 + ticks), at 20 ticks per second.
    // Cut between the encoded 200/201 and 600/601 tick values in the 8-bit Color attribute.
    // This keeps exactly 10 s red and exactly 30 s yellow despite vertex quantization.
    if (risk >= 95.5 / 255.0) return red;
    if (risk >= 42.5 / 255.0) return yellow;
    return green;
}

float atlasDanger() {
    vec2 size=vec2(textureSize(FieldAtlas,0));
    vec4 info=texelFetch(FieldPlanes,fieldPlane*3);
    vec4 slot=texelFetch(FieldPlanes,fieldPlane*3+1);
    vec2 p=info.xy+(fieldUV*size-slot.xy)/info.z;
    vec2 pixel=fieldUV*size-0.5, fraction=fract(pixel);
    ivec2 base=ivec2(floor(pixel));
    ivec4 cell=fieldCell(p,fieldPlane);
    float risk=0.0, weight=0.0;
    // Filter only through the same covered surface. Tiny holes must not transmit color.
    for (int y=0; y<2; y++) for (int x=0; x<2; x++) {
        ivec2 coordinate=base+ivec2(x,y);
        if (any(lessThan(coordinate,ivec2(slot.xy))) || any(greaterThanEqual(coordinate,ivec2(slot.xy+slot.zw)))) continue;
        vec2 value=texelFetch(FieldAtlas,coordinate,0).rg;
        if (value.g<0.5) continue;
        vec2 q=info.xy+(vec2(coordinate)+0.5-slot.xy)/info.z;
        if (fieldCrossesBoundary(p,q,cell)) continue;
        float w=(x==0?1.0-fraction.x:fraction.x)*(y==0?1.0-fraction.y:fraction.y);
        risk+=w*value.r; weight+=w;
    }
    // Sub-texel geometry still uses the exact analytic field instead of disappearing.
    return weight>0.000001?risk/weight:computeVisionField(p,fieldPlane).r;
}

void main() {
    if (RiskColoring < 0.5) {
        fragColor = vertexColor;
        return;
    }
    float risk = clamp(vertexColor.r, 0.0, 1.0);
    if (RiskColoring > 2.5) risk=floor(clamp(atlasDanger(),0.0,1.0)*255.0+0.5)/255.0;
    vec3 color = dangerColor(risk);
    // Surface brightness depends on the shared risk field, never on its source observer.
    float alpha = min(MaxOpacity, mix(0.20, 0.36, risk));
    if (RiskColoring > 1.5 && RiskColoring < 2.5) {
        float distanceRatio = vertexColor.g;
        float base = mix(70.0, 240.0, distanceRatio) / 255.0;
        color = mix(vec3(base), color, 0.55 + 0.45 * risk);
        alpha = min(MaxOpacity, mix(90.0, 10.0, distanceRatio) / 255.0);
    }
    fragColor = vec4(color, alpha);
}
