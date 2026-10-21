package net.rev.stealthandalert.client.renderer;

import foundry.veil.api.client.render.shader.program.ShaderProgram;
import com.mojang.blaze3d.vertex.VertexBuffer;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryStack;
import java.nio.ByteBuffer;
import java.util.function.Function;

/** Render-thread-owned risk atlas. Source geometry, edge barriers and cell indices are texture buffers. */
public final class GpuVisionField implements AutoCloseable {
    private final Function<String, String> resources;
    private final int[] buffers = new int[4], textures = new int[4];
    private int program, framebuffer, atlas, vao, width, height;

    public GpuVisionField(Function<String, String> resources) { this.resources = resources; }
    public int atlas() { return atlas; }
    public int texture(int index) { return textures[index]; }

    public void upload(VisionFieldScene.Data data) {
        if (data.geometry().union().planes().isEmpty()) return;
        int maximum = GL33.glGetInteger(GL33.GL_MAX_TEXTURE_BUFFER_SIZE);
        if (data.sources().length / 4 > maximum || data.edges().length / 4 > maximum
                || data.planes().length / 4 > maximum || data.indices().length > maximum)
            throw new IllegalStateException("Vision texture-buffer capacity exceeded");
        try (SavedState ignored = new SavedState()) {
            ensureProgram();
            for (int i = 0; i < 4; i++) {
                if (buffers[i] == 0) buffers[i] = GL33.glGenBuffers();
                if (textures[i] == 0) textures[i] = GL33.glGenTextures();
                GL33.glActiveTexture(GL33.GL_TEXTURE0 + i);
                GL33.glBindBuffer(GL33.GL_TEXTURE_BUFFER, buffers[i]);
                if (i == 2) GL33.glBufferData(GL33.GL_TEXTURE_BUFFER, data.indices(), GL33.GL_DYNAMIC_DRAW);
                else GL33.glBufferData(GL33.GL_TEXTURE_BUFFER, i == 0 ? data.sources() : i == 1 ? data.edges() : data.planes(), GL33.GL_DYNAMIC_DRAW);
                GL33.glBindTexture(GL33.GL_TEXTURE_BUFFER, textures[i]);
                GL33.glTexBuffer(GL33.GL_TEXTURE_BUFFER, i == 2 ? GL33.GL_R32I : GL33.GL_RGBA32F, buffers[i]);
            }
            GL33.glActiveTexture(GL33.GL_TEXTURE4);
            GL33.glBindBuffer(GL33.GL_PIXEL_UNPACK_BUFFER, 0);
            if (atlas == 0) atlas = GL33.glGenTextures();
            GL33.glBindTexture(GL33.GL_TEXTURE_2D, atlas);
            if (width != data.geometry().width() || height != data.geometry().height()) {
                width = data.geometry().width(); height = data.geometry().height();
                GL33.glTexImage2D(GL33.GL_TEXTURE_2D, 0, GL33.GL_RG32F, width, height, 0, GL33.GL_RG, GL33.GL_FLOAT, (ByteBuffer) null);
                GL33.glTexParameteri(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_MIN_FILTER, GL33.GL_NEAREST);
                GL33.glTexParameteri(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_MAG_FILTER, GL33.GL_NEAREST);
                GL33.glTexParameteri(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_WRAP_S, GL33.GL_CLAMP_TO_EDGE);
                GL33.glTexParameteri(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_WRAP_T, GL33.GL_CLAMP_TO_EDGE);
            }
            if (framebuffer == 0) framebuffer = GL33.glGenFramebuffers();
            GL33.glBindFramebuffer(GL33.GL_DRAW_FRAMEBUFFER, framebuffer);
            GL33.glFramebufferTexture2D(GL33.GL_DRAW_FRAMEBUFFER, GL33.GL_COLOR_ATTACHMENT0, GL33.GL_TEXTURE_2D, atlas, 0);
            if (GL33.glCheckFramebufferStatus(GL33.GL_DRAW_FRAMEBUFFER) != GL33.GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Vision risk framebuffer is incomplete");
            GL33.glDisable(GL33.GL_BLEND);
            GL33.glDisable(GL33.GL_DEPTH_TEST);
            GL33.glDisable(GL33.GL_CULL_FACE);
            GL33.glDisable(GL33.GL_STENCIL_TEST);
            GL33.glDisable(GL33.GL_SCISSOR_TEST);
            GL33.glDisable(GL33.GL_FRAMEBUFFER_SRGB);
            GL33.glColorMask(true, true, true, true);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                GL33.glClearBufferfv(GL33.GL_COLOR, 0, stack.floats(0, 0, 0, 0));
            }
            if (vao == 0) vao = GL33.glGenVertexArrays();
            GL33.glBindVertexArray(vao);
            GL33.glUseProgram(program);
            String[] names = {"FieldSources", "FieldEdges", "FieldIndices", "FieldPlanes"};
            for (int i = 0; i < 4; i++) GL33.glUniform1i(GL33.glGetUniformLocation(program, names[i]), i);
            int planeUniform = GL33.glGetUniformLocation(program, "FieldPlane");
            for (int i = 0; i < data.geometry().tiles().size(); i++) {
                var tile = data.geometry().tiles().get(i);
                GL33.glViewport(tile.x(), tile.y(), tile.width(), tile.height());
                GL33.glUniform1i(planeUniform, i);
                GL33.glDrawArrays(GL33.GL_TRIANGLES, 0, 3);
            }
        }
    }

    public static Binding bind(ShaderProgram shader, GpuVisionField field) {
        return new Binding(shader, field);
    }

    /** The ShaderInstance adapter calls bindSamplers(), which replaces manually owned samplers
     * with Veil's missing texture. Uniforms and Binding are already set by the caller. */
    public static void draw(ShaderProgram shader, VertexBuffer buffer) {
        shader.bind();
        buffer.bind();
        buffer.draw();
    }

    /** Assign all mixed sampler types before Veil's first program bind/validation.
     * Own five reserved units temporarily; Veil must not reassign them while adapting the shader. */
    public static final class Binding implements AutoCloseable {
        private static final int FIRST_UNIT = 8;
        private final int active = GL33.glGetInteger(GL33.GL_ACTIVE_TEXTURE);
        private final int[] texture2D = new int[5], textureBuffer = new int[5], samplers = new int[5];
        private Binding(ShaderProgram shader, GpuVisionField field) {
            String[] names = {"FieldSources", "FieldEdges", "FieldIndices", "FieldPlanes", "FieldAtlas"};
            int previousProgram = GL33.glGetInteger(GL33.GL_CURRENT_PROGRAM);
            GL33.glUseProgram(shader.getProgram());
            try {
                for (int i = 0; i < names.length; i++) {
                    shader.removeTexture(names[i]);
                    int unit = FIRST_UNIT + i;
                    GL33.glActiveTexture(GL33.GL_TEXTURE0 + unit);
                    texture2D[i] = GL33.glGetInteger(GL33.GL_TEXTURE_BINDING_2D);
                    textureBuffer[i] = GL33.glGetInteger(GL33.GL_TEXTURE_BINDING_BUFFER);
                    samplers[i] = GL33.glGetInteger(GL33.GL_SAMPLER_BINDING);
                    GL33.glBindSampler(unit, 0);
                    int target = i == 4 ? GL33.GL_TEXTURE_2D : GL33.GL_TEXTURE_BUFFER;
                    GL33.glBindTexture(target, field == null ? 0 : i == 4 ? field.atlas : field.textures[i]);
                    GL33.glUniform1i(GL33.glGetUniformLocation(shader.getProgram(), names[i]), unit);
                }
            } finally {
                GL33.glUseProgram(previousProgram);
                GL33.glActiveTexture(active);
            }
        }
        @Override public void close() {
            for (int i = 0; i < 5; i++) {
                GL33.glActiveTexture(GL33.GL_TEXTURE0 + FIRST_UNIT + i);
                GL33.glBindTexture(GL33.GL_TEXTURE_2D, texture2D[i]);
                GL33.glBindTexture(GL33.GL_TEXTURE_BUFFER, textureBuffer[i]);
                GL33.glBindSampler(FIRST_UNIT + i, samplers[i]);
            }
            GL33.glActiveTexture(active);
        }
    }

    public static String expand(String source, String common) {
        return source.replace("#include stealth_and_alert:vision_field", common);
    }

    private void ensureProgram() {
        if (program != 0) return;
        String common = resources.apply("include/vision_field.glsl");
        int vertex = compile(GL33.GL_VERTEX_SHADER, resources.apply("program/vision_field.vsh"));
        int fragment = 0, created = 0;
        try {
            fragment = compile(GL33.GL_FRAGMENT_SHADER, expand(resources.apply("program/vision_field.fsh"), common));
            created = GL33.glCreateProgram();
            GL33.glAttachShader(created, vertex); GL33.glAttachShader(created, fragment);
            GL33.glLinkProgram(created);
            if (GL33.glGetProgrami(created, GL33.GL_LINK_STATUS) == 0) throw new IllegalStateException(GL33.glGetProgramInfoLog(created));
            program = created;
            created = 0;
        } finally {
            GL33.glDeleteShader(vertex);
            if (fragment != 0) GL33.glDeleteShader(fragment);
            if (created != 0) GL33.glDeleteProgram(created);
        }
    }

    private static int compile(int type, String source) {
        int shader = GL33.glCreateShader(type);
        GL33.glShaderSource(shader, source); GL33.glCompileShader(shader);
        if (GL33.glGetShaderi(shader, GL33.GL_COMPILE_STATUS) == 0) {
            String error = GL33.glGetShaderInfoLog(shader);
            GL33.glDeleteShader(shader);
            throw new IllegalStateException(error);
        }
        return shader;
    }

    @Override public void close() {
        for (int i = 0; i < 4; i++) {
            if (buffers[i] != 0) GL33.glDeleteBuffers(buffers[i]);
            if (textures[i] != 0) GL33.glDeleteTextures(textures[i]);
            buffers[i] = textures[i] = 0;
        }
        if (program != 0) GL33.glDeleteProgram(program);
        if (framebuffer != 0) GL33.glDeleteFramebuffers(framebuffer);
        if (atlas != 0) GL33.glDeleteTextures(atlas);
        if (vao != 0) GL33.glDeleteVertexArrays(vao);
        program = framebuffer = atlas = vao = width = height = 0;
    }

    /** Restore raw GL state exactly; Minecraft's cached state remains valid across atlas generation. */
    private static final class SavedState implements AutoCloseable {
        final int program = GL33.glGetInteger(GL33.GL_CURRENT_PROGRAM), vao = GL33.glGetInteger(GL33.GL_VERTEX_ARRAY_BINDING);
        final int draw = GL33.glGetInteger(GL33.GL_DRAW_FRAMEBUFFER_BINDING), active = GL33.glGetInteger(GL33.GL_ACTIVE_TEXTURE);
        final int buffer = GL33.glGetInteger(GL33.GL_TEXTURE_BUFFER);
        final int unpack = GL33.glGetInteger(GL33.GL_PIXEL_UNPACK_BUFFER_BINDING);
        final int[] viewport = new int[4], texture2D = new int[5], textureBuffer = new int[5];
        final int[] caps = {GL33.GL_BLEND, GL33.GL_DEPTH_TEST, GL33.GL_CULL_FACE, GL33.GL_STENCIL_TEST, GL33.GL_SCISSOR_TEST, GL33.GL_FRAMEBUFFER_SRGB};
        final boolean[] enabled = new boolean[caps.length], mask = new boolean[4];
        SavedState() {
            GL33.glGetIntegerv(GL33.GL_VIEWPORT, viewport);
            for (int i = 0; i < caps.length; i++) enabled[i] = GL33.glIsEnabled(caps[i]);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer values = stack.malloc(4);
                GL33.glGetBooleanv(GL33.GL_COLOR_WRITEMASK, values);
                for (int i = 0; i < 4; i++) mask[i] = values.get(i) != 0;
            }
            for (int i = 0; i < 5; i++) {
                GL33.glActiveTexture(GL33.GL_TEXTURE0 + i);
                texture2D[i] = GL33.glGetInteger(GL33.GL_TEXTURE_BINDING_2D);
                textureBuffer[i] = GL33.glGetInteger(GL33.GL_TEXTURE_BINDING_BUFFER);
            }
        }
        @Override public void close() {
            GL33.glUseProgram(program); GL33.glBindVertexArray(vao);
            GL33.glBindFramebuffer(GL33.GL_DRAW_FRAMEBUFFER, draw);
            GL33.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GL33.glBindBuffer(GL33.GL_TEXTURE_BUFFER, buffer);
            GL33.glBindBuffer(GL33.GL_PIXEL_UNPACK_BUFFER, unpack);
            GL33.glColorMask(mask[0], mask[1], mask[2], mask[3]);
            for (int i = 0; i < caps.length; i++) { if (enabled[i]) GL33.glEnable(caps[i]); else GL33.glDisable(caps[i]); }
            for (int i = 0; i < 5; i++) {
                GL33.glActiveTexture(GL33.GL_TEXTURE0 + i);
                GL33.glBindTexture(GL33.GL_TEXTURE_2D, texture2D[i]);
                GL33.glBindTexture(GL33.GL_TEXTURE_BUFFER, textureBuffer[i]);
            }
            GL33.glActiveTexture(active);
        }
    }
}
