uniform samplerBuffer FieldSources;
uniform samplerBuffer FieldEdges;
uniform isamplerBuffer FieldIndices;
uniform samplerBuffer FieldPlanes;

ivec4 fieldCell(vec2 p, int plane) {
    vec4 info = texelFetch(FieldPlanes, plane * 3);
    ivec4 grid = ivec4(texelFetch(FieldPlanes, plane * 3 + 2));
    ivec2 cell = ivec2(floor(p / 4.0)) - grid.xy;
    if (any(lessThan(cell, ivec2(0))) || any(greaterThanEqual(cell, grid.zw))) return ivec4(0);
    int at = int(info.w) + (cell.y * grid.z + cell.x) * 4;
    return ivec4(texelFetch(FieldIndices, at).r, texelFetch(FieldIndices, at + 1).r,
                 texelFetch(FieldIndices, at + 2).r, texelFetch(FieldIndices, at + 3).r);
}

vec3 fieldWeights(vec2 p, vec2 a, vec2 b, vec2 c) {
    float denominator = (b.y-c.y)*(a.x-c.x)+(c.x-b.x)*(a.y-c.y);
    float wa = ((b.y-c.y)*(p.x-c.x)+(c.x-b.x)*(p.y-c.y))/denominator;
    float wb = ((c.y-a.y)*(p.x-c.x)+(a.x-c.x)*(p.y-c.y))/denominator;
    return vec3(wa, wb, 1.0-wa-wb);
}

bool fieldCovered(vec2 p, ivec4 cell) {
    for (int i = 0; i < cell.y; i++) {
        int source = texelFetch(FieldIndices, cell.x + i).r * 3;
        vec2 a = texelFetch(FieldSources, source).xy;
        vec2 b = texelFetch(FieldSources, source + 1).xy;
        vec2 c = texelFetch(FieldSources, source + 2).xy;
        if (all(greaterThanEqual(fieldWeights(p,a,b,c), vec3(-0.000001)))) return true;
    }
    return false;
}

float fieldCross(vec2 a, vec2 b) { return a.x*b.y-a.y*b.x; }
bool fieldCrossesBoundary(vec2 a, vec2 b, ivec4 cell) {
    vec2 direction = b-a;
    for (int i = 0; i < cell.w; i++) {
        vec4 edge = texelFetch(FieldEdges, texelFetch(FieldIndices, cell.z + i).r);
        vec2 other = edge.zw-edge.xy;
        float cross = fieldCross(direction,other);
        if (abs(cross) < 0.0000001) continue;
        float t = fieldCross(edge.xy-a,other)/cross;
        float s = fieldCross(edge.xy-a,direction)/cross;
        if (t > 0.000001 && t < 0.999999 && s >= -0.000001 && s <= 1.000001) return true;
    }
    return false;
}

vec2 fieldClosestEdge(vec2 p, vec2 a, vec2 b) {
    vec2 d=b-a;
    return a+d*clamp(dot(p-a,d)/max(dot(d,d),0.000000000001),0.0,1.0);
}

// R = the same conservative one-block fused danger field; G = exact point coverage.
vec2 computeVisionField(vec2 p, int plane) {
    ivec4 cell = fieldCell(p,plane);
    if (!fieldCovered(p,cell)) return vec2(0.0);
    float risk = 0.0;
    for (int i=0; i<cell.y; i++) {
        int source = texelFetch(FieldIndices,cell.x+i).r*3;
        vec4 a=texelFetch(FieldSources,source);
        if (a.w <= risk) break; // Source buckets are sorted by maximum danger.
        vec3 b=texelFetch(FieldSources,source+1).xyz, c=texelFetch(FieldSources,source+2).xyz;
        vec2 nearest=p;
        if (any(lessThan(fieldWeights(p,a.xy,b.xy,c.xy),vec3(0.0)))) {
            vec2 ab=fieldClosestEdge(p,a.xy,b.xy), bc=fieldClosestEdge(p,b.xy,c.xy), ca=fieldClosestEdge(p,c.xy,a.xy);
            nearest=dot(p-ab,p-ab)<=dot(p-bc,p-bc)?ab:bc;
            if (dot(p-ca,p-ca)<dot(p-nearest,p-nearest)) nearest=ca;
        }
        float distance=length(p-nearest);
        if (distance>=1.0) continue;
        float candidate=clamp(dot(fieldWeights(nearest,a.xy,b.xy,c.xy),vec3(a.z,b.z,c.z)),0.0,1.0)
                       *(1.0-smoothstep(0.0,1.0,distance));
        if (candidate<=risk) continue;
        if (distance>0.000001 && (!fieldCovered((p+nearest)*0.5,cell) || fieldCrossesBoundary(p,nearest,cell))) continue;
        risk=candidate;
    }
    return vec2(risk,1.0);
}
