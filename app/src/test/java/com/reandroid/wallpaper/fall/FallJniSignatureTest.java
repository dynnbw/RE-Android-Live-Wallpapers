package com.reandroid.wallpaper.fall;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JNI 方法签名与 Java 声明对表。
 *
 * <p>{@code RegisterNatives} 里的签名是手写的字符串，和 Java 那边的参数表没有任何编译期联系。
 * 多一个少一个参数的表现是**打开壁纸直接崩溃**（{@code NoSuchMethodError}，而且发生在
 * {@code System.loadLibrary} 里，堆栈看上去和真正的出错点毫无关系）。
 *
 * <p>这里用反射从编译好的类算出描述符，再去 native 源码里核对 —— 纯 JVM，不需要设备。
 */
public class FallJniSignatureTest {

    private static final File JNI_SOURCE = new File("src/main/jni/fallvk_jni.cpp");

    @Test
    public void renderFrameSignatureMatchesTheJavaDeclaration() throws Exception {
        assertEquals(expectedSignature("nRenderFrame"), registeredSignature("nRenderFrame"));
    }

    /** 从编译后的 Java 类算出 {@code (参数)返回值} 描述符。 */
    private static String expectedSignature(String name) throws ClassNotFoundException {
        // initialize = false：静态块会 System.loadLibrary，JVM 上没有这个 .so
        Class<?> cls = Class.forName(
                "com.reandroid.wallpaper.fall.FallVKNative",
                false,
                FallJniSignatureTest.class.getClassLoader());
        Method found = null;
        for (Method method : cls.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                found = method;
            }
        }
        assertNotNull("Java 侧没有 native 方法 " + name, found);
        StringBuilder out = new StringBuilder("(");
        for (Class<?> type : found.getParameterTypes()) {
            out.append(descriptor(type));
        }
        return out.append(')').append(descriptor(found.getReturnType())).toString();
    }

    private static String descriptor(Class<?> type) {
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        if (type == short.class) return "S";
        if (type == byte.class) return "B";
        if (type == boolean.class) return "Z";
        if (type == char.class) return "C";
        if (type == void.class) return "V";
        if (type.isArray()) return "[" + descriptor(type.getComponentType());
        return "L" + type.getName().replace('.', '/') + ";";
    }

    /** native 源码里 kMethods 那一行登记的签名。 */
    private static String registeredSignature(String name) throws IOException {
        assertTrue(JNI_SOURCE + " 不存在", JNI_SOURCE.isFile());
        String source = new String(Files.readAllBytes(JNI_SOURCE.toPath()), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\"" + name + "\",\\s*\"([^\"]+)\"").matcher(source);
        assertTrue("native 那里没有登记 " + name, m.find());
        return m.group(1);
    }
}
