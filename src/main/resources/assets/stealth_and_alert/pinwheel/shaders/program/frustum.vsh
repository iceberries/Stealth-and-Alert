#version 330

// Match DefaultVertexFormat.POSITION_TEX_COLOR, independently of declaration order.
layout(location = 0) in vec3 Position;
layout(location = 2) in vec4 Color;
layout(location = 1) in vec2 UV0;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

// 诊断：>= -1000 时强制所有顶点 Y = DebugForceY（验证 ModelView/投影链路）
uniform float DebugForceY;

out vec4 vertexColor;
out vec2 fieldUV;
flat out int fieldPlane;

void main() {
    vec3 projectedPos = Position;

    // 诊断覆盖（默认 debugForceY=-9999 关闭）
    if (DebugForceY >= -1000.0) {
        projectedPos.y = DebugForceY;
    }

    gl_Position = ProjMat * ModelViewMat * vec4(projectedPos, 1.0);
    vertexColor = Color;
    fieldUV = UV0;
    fieldPlane = int(round(Color.b * 255.0)) + int(round(Color.a * 255.0)) * 256;
}
