package net.rev.stealthandalert.client.renderer;

import net.minecraft.world.phys.Vec3;
import net.rev.stealthandalert.util.DetectionMath;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL33;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.lang.reflect.Proxy;
import foundry.veil.api.client.render.shader.program.ShaderProgram;

/** Small real-GPU smoke, deliberately independent of the full verifyVision suite. */
public final class VisionSmokeTests {
    private static int checks, program, color, uv;
    private static GpuVisionField.Binding activeBinding;
    private static final Path ROOT = Path.of("src/main/resources/assets/stealth_and_alert/pinwheel/shaders");
    public static void main(String[] args) throws Exception {
        var callback = GLFWErrorCallback.createPrint(System.err).set();
        if (!GLFW.glfwInit()) throw new AssertionError("GLFW initialization failed");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(32, 32, "Vision smoke", 0, 0);
        if (window == 0) throw new AssertionError("OpenGL context creation failed");
        try {
            GLFW.glfwMakeContextCurrent(window); GL.createCapabilities();
            compileComposite();
            int vao = GL33.glGenVertexArrays(); GL33.glBindVertexArray(vao);
            int buffer = GL33.glGenBuffers(); GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, buffer);
            GL33.glBufferData(GL33.GL_ARRAY_BUFFER, new float[]{-1,-1,0,3,-1,0,-1,3,0}, GL33.GL_STATIC_DRAW);
            int position = GL33.glGetAttribLocation(program,"Position");
            color = GL33.glGetAttribLocation(program,"Color"); uv = GL33.glGetAttribLocation(program,"UV0");
            GL33.glEnableVertexAttribArray(position); GL33.glVertexAttribPointer(position,3,GL33.GL_FLOAT,false,0,0);
            int target = GL33.glGenTextures(); GL33.glBindTexture(GL33.GL_TEXTURE_2D,target);
            GL33.glTexImage2D(GL33.GL_TEXTURE_2D,0,GL33.GL_RGBA8,32,32,0,GL33.GL_RGBA,GL33.GL_UNSIGNED_BYTE,(ByteBuffer)null);
            int framebuffer = GL33.glGenFramebuffers(); GL33.glBindFramebuffer(GL33.GL_FRAMEBUFFER,framebuffer);
            GL33.glFramebufferTexture2D(GL33.GL_FRAMEBUFFER,GL33.GL_COLOR_ATTACHMENT0,GL33.GL_TEXTURE_2D,target,0);
            check(GL33.glCheckFramebufferStatus(GL33.GL_FRAMEBUFFER)==GL33.GL_FRAMEBUFFER_COMPLETE,"composite framebuffer");
            GL33.glViewport(0,0,32,32);
            GL33.glUseProgram(program);
            float[] identity={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
            for(String name:List.of("ModelViewMat","ProjMat")) GL33.glUniformMatrix4fv(GL33.glGetUniformLocation(program,name),false,identity);
            uniform("DebugForceY",-9999); uniform("MaxOpacity",1);
            List<SurfaceVisionFusion.Triangle> input=new ArrayList<>();
            rectangle(input,0,0,2,2,0.85F); rectangle(input,1,0,5,2,0.2F);
            VisionFieldScene builder=new VisionFieldScene();
            var data=builder.build(input,Vec3.ZERO);
            check(data!=null,"GPU atlas allocation");
            double area=data.geometry().union().planes().stream().flatMap(p->p.coverage().stream()).mapToDouble(VisionSmokeTests::area).sum();
            near(area,10,0.000001,"coverage union area");
            try(GpuVisionField field=new GpuVisionField(VisionSmokeTests::resource)) {
                field.upload(data);
                check(GL33.glGetInteger(GL33.GL_CURRENT_PROGRAM)==program && GL33.glGetInteger(GL33.GL_DRAW_FRAMEBUFFER_BINDING)==framebuffer,"GL state restored");
                bind(field);
                int[] red=pixel(0.8F,0.5F,1),yellow=pixel(0.25F,0.5F,1),green=pixel(0.1F,0.5F,1);
                check(red[0]>245 && red[1]<65,"red palette");
                check(yellow[0]>245 && yellow[1]>210 && yellow[2]<40,"yellow palette");
                check(green[0]<65 && green[1]>195,"green palette");
                for(int tick:new int[]{199,200,201,599,600,601,800}) {
                    float risk=Math.round(DetectionMath.danger(tick)*255)/255F;
                    int[] actual=pixel(risk,0.1F,1),expected=tick<=200?red:tick<=600?yellow:green;
                    check(sameRGB(actual,expected),"color threshold at tick "+tick);
                }
                check(Arrays.equals(pixel(0.25F,0.1F,1),pixel(0.25F,0.9F,1)),"surface source-distance independence");
                uniform("MaxOpacity",0.1F); check(pixel(0.9F,0.5F,1)[3]<=26,"opacity limit"); uniform("MaxOpacity",1);
                check(!Arrays.equals(pixel(0.5F,0.1F,2),pixel(0.5F,0.9F,2)),"original beam fade");
                check(pixel(70/255F,70/255F,0)[0]==70,"original outline color");
                check(sameRGB(fieldPixel(data,1.5,1),red),"GPU overlap keeps strongest source");
                check(sameRGB(fieldPixel(data,4.5,1),yellow),"GPU low-risk single source");
                int[] before=fieldPixel(data,2-0.001,1),after=fieldPixel(data,2+0.001,1);
                check(sameRGB(before,after),"GPU ending-source seam");
                // Color-only changes must keep coverage mesh and layout identity.
                List<SurfaceVisionFusion.Triangle> recolored=input.stream().map(t->new SurfaceVisionFusion.Triangle(
                        new SurfaceVisionFusion.Vertex(t.a().position(),0.1F,0.5F),new SurfaceVisionFusion.Vertex(t.b().position(),0.1F,0.5F),
                        new SurfaceVisionFusion.Vertex(t.c().position(),0.1F,0.5F))).toList();
                var next=builder.build(recolored,Vec3.ZERO);
                check(next.geometry()==data.geometry(),"color update reuses geometry"); field.upload(next); bind(field);
                check(sameRGB(fieldPixel(next,1.5,1),green),"GPU numeric color refresh");
                VisionFieldScene thresholdBuilder=new VisionFieldScene();
                for(int tick:new int[]{200,201,600,601,800}) {
                    List<SurfaceVisionFusion.Triangle> thresholdInput=new ArrayList<>();
                    rectangle(thresholdInput,0,0,2,2,DetectionMath.danger(tick));
                    var thresholdData=thresholdBuilder.build(thresholdInput,Vec3.ZERO);field.upload(thresholdData);bind(field);
                    int[] expected=tick<=200?red:tick<=600?yellow:green;
                    for(double[] p:new double[][]{{0.15,0.15},{1.375,1.1},{1.85,1.85}})
                        check(sameRGB(fieldPixel(thresholdData,p[0],p[1]),expected),"atlas threshold at tick "+tick);
                }
                input.clear();
                rectangle(input,0,0,1,1,0.95F); rectangle(input,1.01,0,2.01,1,0.2F);
                var islands=new VisionFieldScene().build(input,Vec3.ZERO); field.upload(islands); bind(field);
                check(sameRGB(fieldPixel(islands,1.02,0.5),yellow),"sub-texel gap blocks color filtering");
                input.clear();
                rectangle(input,0,0,3,1,0.2F);rectangle(input,0,1,1,2,0.95F);
                rectangle(input,1.01,1,3,2,0.2F);rectangle(input,0,2,3,3,0.2F);
                var hole=new VisionFieldScene().build(input,Vec3.ZERO);field.upload(hole);bind(field);
                near(hole.geometry().union().planes().stream().flatMap(p->p.coverage().stream()).mapToDouble(VisionSmokeTests::area).sum(),8.99,0.000001,"narrow hole coverage retained");
                check(sameRGB(fieldPixel(hole,1.02,1.5),yellow),"hole blocks GPU color transfer");
                bind(null);
                check(sameRGB(pixel(0.1F,0.5F,1),green),"CPU fallback keeps sampler types valid");
                minecraftVertexFormatSmoke();
                gameDrawSmoke(field, red, yellow, green);
                check(GL33.glGetError()==GL33.GL_NO_ERROR,"OpenGL error-free smoke");
                activeBinding.close();activeBinding=null;
            }
            System.out.println("Vision smoke passed: "+checks+" checks; "+GL33.glGetString(GL33.GL_RENDERER));
        } finally {
            GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();callback.free();
        }
    }

    private static void compileComposite() {
        program=GL33.glCreateProgram();
        for(String suffix:List.of("vsh","fsh")) {
            int shader=GL33.glCreateShader(suffix.equals("vsh")?GL33.GL_VERTEX_SHADER:GL33.GL_FRAGMENT_SHADER);
            GL33.glShaderSource(shader,GpuVisionField.expand(resource("program/frustum."+suffix),resource("include/vision_field.glsl")));
            GL33.glCompileShader(shader);
            check(GL33.glGetShaderi(shader,GL33.GL_COMPILE_STATUS)!=0,GL33.glGetShaderInfoLog(shader));
            GL33.glAttachShader(program,shader);GL33.glDeleteShader(shader);
        }
        GL33.glLinkProgram(program);check(GL33.glGetProgrami(program,GL33.GL_LINK_STATUS)!=0,GL33.glGetProgramInfoLog(program));
    }
    private static void minecraftVertexFormatSmoke() {
        RenderSystem.initRenderThread();
        var format=DefaultVertexFormat.POSITION_TEX_COLOR;
        ByteBuffer vertices=BufferUtils.createByteBuffer(format.getVertexSize()*3).order(ByteOrder.nativeOrder());
        for(float[] p:new float[][]{{-1,-1,0},{3,-1,0},{-1,3,0}}) {
            vertices.putFloat(p[0]).putFloat(p[1]).putFloat(p[2]).putFloat(0.2F).putFloat(0.3F);
            vertices.put((byte)70).put((byte)70).put((byte)70).put((byte)255);
        }
        vertices.flip();
        GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER,GL33.glGenBuffers());
        GL33.glBufferData(GL33.GL_ARRAY_BUFFER,vertices,GL33.GL_STATIC_DRAW);
        format.setupBufferState();
        uniform("RiskColoring",0);
        int[] outline=readPixel();
        check(outline[0]==70 && outline[1]==70 && outline[2]==70 && outline[3]==255,"Minecraft vertex-format color binding");
        check(GL33.glGetAttribLocation(program,"Position")==0 && GL33.glGetAttribLocation(program,"UV0")==1
                && GL33.glGetAttribLocation(program,"Color")==2,"Minecraft vertex-format UV binding");
        format.clearBufferState();
    }
    private static String resource(String path) { try {return Files.readString(ROOT.resolve(path));}catch(Exception e){throw new IllegalStateException(e);} }
    private static void gameDrawSmoke(GpuVisionField field, int[] red, int[] yellow, int[] green) {
        List<SurfaceVisionFusion.Triangle> input = new ArrayList<>();
        float[] risks = {0.8F, 0.25F, 0.1F};
        for (int plane = 0; plane < risks.length; plane++) {
            List<SurfaceVisionFusion.Triangle> floor = new ArrayList<>();
            rectangle(floor, 0, 0, 2, 2, risks[plane]);
            for (var t : floor) input.add(new SurfaceVisionFusion.Triangle(
                    new SurfaceVisionFusion.Vertex(t.a().position().add(0, plane, 0), risks[plane], 0.5F),
                    new SurfaceVisionFusion.Vertex(t.b().position().add(0, plane, 0), risks[plane], 0.5F),
                    new SurfaceVisionFusion.Vertex(t.c().position().add(0, plane, 0), risks[plane], 0.5F)));
        }
        var data = new VisionFieldScene().build(input, Vec3.ZERO);
        check(data.geometry().tiles().size() == 3, "multiple floor planes");
        field.upload(data); bind(field);
        ShaderProgram handle = (ShaderProgram) Proxy.newProxyInstance(ShaderProgram.class.getClassLoader(), new Class<?>[]{ShaderProgram.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("bind")) { GL33.glUseProgram(program); return null; }
                    throw new AssertionError("Draw must retain owned samplers: " + method.getName());
                });
        for (int plane = 0; plane < data.geometry().tiles().size(); plane++) {
            var tile = data.geometry().tiles().get(plane);
            float u = (float) ((tile.x() + (1 - tile.minU()) * VisionFieldScene.PIXELS_PER_BLOCK) / data.geometry().width());
            float v = (float) ((tile.y() + (1 - tile.minV()) * VisionFieldScene.PIXELS_PER_BLOCK) / data.geometry().height());
            try (ByteBufferBuilder memory = new ByteBufferBuilder(256); VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC)) {
                BufferBuilder builder = new BufferBuilder(memory, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
                for (float[] p : new float[][]{{-1,-1,0},{3,-1,0},{-1,3,0}})
                    builder.addVertex(p[0], p[1], p[2]).setUv(u, v).setColor(0, 0, plane & 255, (plane >>> 8) & 255);
                buffer.bind(); buffer.upload(builder.buildOrThrow());
                uniform("RiskColoring", 3);
                GpuVisionField.draw(handle, buffer);
                int[] actual = readRenderedPixel();
                int source = data.geometry().union().planes().get(plane).sourceIndices().getFirst();
                int[] expected = input.get(source).a().danger() > 0.5F ? red : input.get(source).a().danger() > 0.2F ? yellow : green;
                check(sameRGB(actual, expected), "game VertexBuffer draw retains atlas and plane " + plane);
                VertexBuffer.unbind();
            }
        }
    }
    private static void bind(GpuVisionField field) {
        if(activeBinding!=null)activeBinding.close();
        ShaderProgram handle=(ShaderProgram)Proxy.newProxyInstance(ShaderProgram.class.getClassLoader(),new Class<?>[]{ShaderProgram.class},
                (proxy,method,args)->{if(method.getName().equals("getProgram"))return program;if(method.getName().equals("removeTexture"))return null;
                    throw new UnsupportedOperationException(method.getName());});
        activeBinding=GpuVisionField.bind(handle,field);
        GL33.glUseProgram(program);
        GL33.glValidateProgram(program);
        check(GL33.glGetProgrami(program,GL33.GL_VALIDATE_STATUS)!=0,"mixed sampler program validation: "+GL33.glGetProgramInfoLog(program));
    }
    private static int[] fieldPixel(VisionFieldScene.Data data,double u,double v) {
        var tile=data.geometry().tiles().getFirst();
        GL33.glVertexAttrib2f(uv,(float)((tile.x()+(u-tile.minU())*VisionFieldScene.PIXELS_PER_BLOCK)/data.geometry().width()),
                (float)((tile.y()+(v-tile.minV())*VisionFieldScene.PIXELS_PER_BLOCK)/data.geometry().height()));
        GL33.glVertexAttrib4f(color,0,0,0,0);uniform("RiskColoring",3);
        return readPixel();
    }
    private static int[] pixel(float risk,float distance,float mode) {
        uniform("RiskColoring",mode);GL33.glVertexAttrib4f(color,risk,distance,mode==0?risk:0,1);return readPixel();
    }
    private static int[] readPixel() {
        GL33.glDrawArrays(GL33.GL_TRIANGLES,0,3);
        return readRenderedPixel();
    }
    private static int[] readRenderedPixel() {
        ByteBuffer pixel=BufferUtils.createByteBuffer(4);
        GL33.glReadPixels(16,16,1,1,GL33.GL_RGBA,GL33.GL_UNSIGNED_BYTE,pixel);
        return new int[]{pixel.get(0)&255,pixel.get(1)&255,pixel.get(2)&255,pixel.get(3)&255};
    }
    private static void uniform(String name,float value) {GL33.glUniform1f(GL33.glGetUniformLocation(program,name),value);}
    private static boolean sameRGB(int[] a,int[] b){return a[0]==b[0]&&a[1]==b[1]&&a[2]==b[2];}
    private static double area(SurfaceVisionFusion.Triangle t){return t.b().position().subtract(t.a().position()).cross(t.c().position().subtract(t.a().position())).length()*0.5;}
    private static void rectangle(List<SurfaceVisionFusion.Triangle> input,double x0,double z0,double x1,double z1,float danger) {
        var a=new SurfaceVisionFusion.Vertex(new Vec3(x0,0,z0),danger,0.5F);var b=new SurfaceVisionFusion.Vertex(new Vec3(x1,0,z0),danger,0.5F);
        var c=new SurfaceVisionFusion.Vertex(new Vec3(x1,0,z1),danger,0.5F);var d=new SurfaceVisionFusion.Vertex(new Vec3(x0,0,z1),danger,0.5F);
        input.add(new SurfaceVisionFusion.Triangle(a,b,c));input.add(new SurfaceVisionFusion.Triangle(a,c,d));
    }
    private static void near(double a,double b,double tolerance,String message){check(Math.abs(a-b)<=tolerance,message+": "+a+" vs "+b);}
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}
