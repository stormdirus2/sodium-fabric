package me.jellysquid.mods.sodium.client.gl.shader;

import net.minecraft.util.Identifier;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class ShaderLoader {
    /**
     * Creates an OpenGL shader from GLSL sources. The GLSL source file should be made available on the classpath at the
     * path of `/assets/{namespace}/shaders/{path}`. User defines can be used to declare variables in the shader source
     * after the version header, allowing for conditional compilation with macro code.
     *
     * @param type The type of shader to create
     * @param name The identifier used to locate the shader source file
     * @param constants A list of constants for shader specialization
     * @return An OpenGL shader object compiled with the given user defines
     */
    public static GlShader loadShader(ShaderType type, Identifier name, ShaderConstants constants) {
        return new GlShader(type, name, getShaderSource(getShaderPath(name)), constants);
    }

    private static String getShaderPath(Identifier name) {
        return String.format("/assets/%s/shaders/%s", name.getNamespace(), name.getPath());
    }

    private static String getShaderSource(String path) {
        try (InputStream in = ShaderLoader.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new RuntimeException("Shader not found: " + path);
            }

            return IOUtils.toString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Could not read shader sources", e);
        }
    }
}
