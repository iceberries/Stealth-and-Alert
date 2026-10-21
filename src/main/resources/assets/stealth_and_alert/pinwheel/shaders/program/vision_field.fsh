#version 330
#include stealth_and_alert:vision_field
uniform int FieldPlane;
out vec2 fragColor;
void main() {
    vec4 info=texelFetch(FieldPlanes,FieldPlane*3);
    vec4 slot=texelFetch(FieldPlanes,FieldPlane*3+1);
    vec2 p=info.xy+(gl_FragCoord.xy-slot.xy)/info.z;
    fragColor=computeVisionField(p,FieldPlane);
}
