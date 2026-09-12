#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <unistd.h>
#include <string>
#include <string_view>
#include <shadowhook.h>
#include <lsplant.hpp>
#include "zygisk.hpp"

#define LOG_TAG "CorePatch-Zygisk"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM *g_vm = nullptr;
static zygisk::Api *g_api = nullptr;
static JNIEnv *g_env = nullptr;
static int g_module_dir_fd = -1;
static bool g_lsplant_ready = false;

extern "C" JNIEXPORT jobject JNICALL
Java_org_lsposed_corepatch_ZygiskHelper_nativeHook(JNIEnv *env, jclass, jobject target, jobject hooker, jobject callback) {
    if (!g_lsplant_ready) return nullptr;
    return lsplant::Hook(env, target, hooker, callback);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_lsposed_corepatch_ZygiskHelper_nativeDeoptimize(JNIEnv *env, jclass, jobject method) {
    if (!g_lsplant_ready) return JNI_FALSE;
    return lsplant::Deoptimize(env, method) ? JNI_TRUE : JNI_FALSE;
}

static void *resolve_art(std::string_view name) {
    static void *art = dlopen("libart.so", RTLD_NOW | RTLD_NOLOAD);
    if (!art) art = dlopen("libart.so", RTLD_NOW);
    if (!art) return nullptr;
    std::string s(name);
    return dlsym(art, s.c_str());
}

static void init_lsplant(JNIEnv *env) {
    if (g_lsplant_ready) return;

    int rc = shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false);
    if (rc != 0) {
        LOGE("shadowhook_init failed: %d", rc);
        return;
    }

    lsplant::InitInfo info{
        .inline_hooker = [](void *target, void *hooker) -> void * {
            void *backup = nullptr;
            auto stub = shadowhook_hook_func_addr(target, hooker, &backup);
            if (!stub) return nullptr;
            return backup;
        },
        .inline_unhooker = [](void *func) -> bool {
            return shadowhook_unhook(func) == 0;
        },
        .art_symbol_resolver = [](std::string_view sym) -> void * {
            return resolve_art(sym);
        },
        .art_symbol_prefix_resolver = nullptr,
        .generated_class_name = "CPZygiskHooker_",
        .generated_source_name = "CorePatchZygisk",
        .generated_field_name = "hooker",
        .generated_method_name = "{target}",
        .executable_memory_allocator = nullptr,
        .executable_memory_recycler = nullptr,
    };

    g_lsplant_ready = lsplant::Init(env, info);
    LOGI("LSPlant init: %s", g_lsplant_ready ? "OK" : "FAILED");
}

static bool call_bootstrap(JNIEnv *env, const std::string &apk) {
    if (apk.empty()) return false;

    jclass pcl = env->FindClass("dalvik/system/PathClassLoader");
    if (!pcl) { env->ExceptionClear(); return false; }

    jmethodID ctor = env->GetMethodID(pcl, "<init>",
        "(Ljava/lang/String;Ljava/lang/ClassLoader;)V");
    if (!ctor) { env->ExceptionClear(); return false; }

    jclass clz = env->FindClass("java/lang/ClassLoader");
    jmethodID getSystem = env->GetStaticMethodID(clz, "getSystemClassLoader",
                                                  "()Ljava/lang/ClassLoader;");
    jobject parent = env->CallStaticObjectMethod(clz, getSystem);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }

    jstring path = env->NewStringUTF(apk.c_str());
    jobject loader = env->NewObject(pcl, ctor, path, parent);
    if (env->ExceptionCheck() || !loader) { env->ExceptionClear(); return false; }

    jclass clsClass = env->FindClass("java/lang/Class");
    jmethodID forName = env->GetStaticMethodID(
        clsClass, "forName",
        "(Ljava/lang/String;ZLjava/lang/Class;)Ljava/lang/Class;");
    if (!forName) { env->ExceptionClear(); return false; }

    jstring name = env->NewStringUTF("org.lsposed.corepatch.ZygiskBootstrap");
    jclass bootstrap = (jclass)env->CallStaticObjectMethod(
        clsClass, forName, name, JNI_TRUE, loader);
    if (env->ExceptionCheck() || !bootstrap) {
        env->ExceptionClear();
        LOGE("failed to load ZygiskBootstrap from %s", apk.c_str());
        return false;
    }

    // Register JNI methods explicitly: the helper is loaded from a private DexClassLoader,
    // so relying on the VM's automatic native-library lookup is not reliable.
    jmethodID getClassLoader = env->GetMethodID(clsClass, "getClassLoader", "()Ljava/lang/ClassLoader;");
    jobject helperLoader = env->CallObjectMethod(bootstrap, getClassLoader);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }

    jstring helperName = env->NewStringUTF("org.lsposed.corepatch.ZygiskHelper");
    jclass helper = (jclass)env->CallStaticObjectMethod(
        clsClass, forName, helperName, JNI_FALSE, helperLoader);
    if (env->ExceptionCheck() || !helper) { env->ExceptionClear(); return false; }

    JNINativeMethod methods[] = {
        {"nativeHook", "(Ljava/lang/reflect/Executable;Ljava/lang/Object;Ljava/lang/reflect/Method;)Ljava/lang/reflect/Executable;",
         reinterpret_cast<void *>(Java_org_lsposed_corepatch_ZygiskHelper_nativeHook)},
        {"nativeDeoptimize", "(Ljava/lang/reflect/Method;)Z",
         reinterpret_cast<void *>(Java_org_lsposed_corepatch_ZygiskHelper_nativeDeoptimize)},
    };
    if (env->RegisterNatives(helper, methods, 2) != 0) {
        env->ExceptionClear();
        LOGE("RegisterNatives(ZygiskHelper) failed");
        return false;
    }

    jmethodID install = env->GetStaticMethodID(bootstrap, "installEarly", "()V");
    if (!install) { env->ExceptionClear(); return false; }
    env->CallStaticVoidMethod(bootstrap, install);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return false;
    }
    LOGI("ZygiskBootstrap loaded");
    return true;
}

class CorePatchZygisk final : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        g_api = api;
        g_env = env;
        env->GetJavaVM(&g_vm);
        init_lsplant(env);
    }

    void preServerSpecialize(zygisk::ServerSpecializeArgs *) override {
        if (!g_api || !g_env || !g_lsplant_ready) return;
        // The manager APK is overlaid into /system/priv-app by the same Magisk module.
        // This path remains visible after system_server specialization.
        const std::string apk = "/system/priv-app/CorePatch/CorePatch.apk";
        call_bootstrap(g_env, apk);
        // We deliberately keep the module loaded: LSPlant hooks remain active.
    }
};

REGISTER_ZYGISK_MODULE(CorePatchZygisk)
