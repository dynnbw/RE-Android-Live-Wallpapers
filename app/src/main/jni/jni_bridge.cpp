#include <jni.h>

/*
 * 四个壁纸的 VK 渲染器共用一个库（librebornvk.so），所以它们的 JNI 入口也在这里统一注册。
 *
 * 以前每个渲染器文件用 `extern "C" JNIEXPORT ... Java_com_reandroid_wallpaper_xxx_nYyy(...)`
 * 手写入口 —— 名字一长串、只差类名，加一个壁纸就要再抄一份。现在每个文件只给出
 * 静态函数 + 一张 JNINativeMethod 表（签名由 Java 侧的 native 声明反推），这里注册。
 *
 * 注册失败必须让加载失败：否则方法会变成"调用时才发现没实现"，而那时早就离开加载点了。
 */

bool registerGalaxyVk(JNIEnv* env);
bool registerFallVk(JNIEnv* env);
bool registerGrassVk(JNIEnv* env);
bool registerGalaxy4Vk(JNIEnv* env);

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    if (!registerGalaxyVk(env) || !registerFallVk(env) || !registerGrassVk(env)
            || !registerGalaxy4Vk(env)) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
