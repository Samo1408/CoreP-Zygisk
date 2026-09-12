#pragma once
#include <jni.h>
#include <cstdint>
#define ZYGISK_API_VERSION 2
namespace zygisk {
struct Api; struct AppSpecializeArgs; struct ServerSpecializeArgs;
class ModuleBase {
public:
    virtual void onLoad(Api*, JNIEnv*) {}
    virtual void preAppSpecialize(AppSpecializeArgs*) {}
    virtual void postAppSpecialize(const AppSpecializeArgs*) {}
    virtual void preServerSpecialize(ServerSpecializeArgs*) {}
    virtual void postServerSpecialize(const ServerSpecializeArgs*) {}
};
struct AppSpecializeArgs {
    jint &uid; jint &gid; jintArray &gids; jint &runtime_flags; jint &mount_external;
    jstring &se_info; jstring &nice_name; jstring &instruction_set; jstring &app_data_dir;
    jboolean *const is_child_zygote; jboolean *const is_top_app;
    jobjectArray *const pkg_data_info_list; jobjectArray *const whitelisted_data_info_list;
    jboolean *const mount_data_dirs; jboolean *const mount_storage_dirs;
    AppSpecializeArgs()=delete;
};
struct ServerSpecializeArgs {
    jint &uid; jint &gid; jintArray &gids; jint &runtime_flags;
    jlong &permitted_capabilities; jlong &effective_capabilities;
    ServerSpecializeArgs()=delete;
};
enum Option:int { FORCE_DENYLIST_UNMOUNT=0, DLCLOSE_MODULE_LIBRARY=1 };
enum StateFlag:uint32_t { PROCESS_GRANTED_ROOT=(1u<<0), PROCESS_ON_DENYLIST=(1u<<1) };
struct Api {
    int connectCompanion();
    int getModuleDir();
    void setOption(Option);
    uint32_t getFlags();
    void hookJniNativeMethods(JNIEnv*, const char*, JNINativeMethod*, int);
    void pltHookRegister(const char*, const char*, void*, void**);
    void pltHookExclude(const char*, const char*);
    bool pltHookCommit();
    // The internal table is provided by the Zygisk runtime. It must be
    // nameable by the registration trampoline below; keep the pointer opaque.
    // Opaque pointer to the Zygisk runtime API table. Kept public because the
    // registration trampoline initializes it before exposing Api to the module.
    void *impl = nullptr;
};
namespace internal {
struct module_abi {
    long api_version; ModuleBase *_this;
    void (*preAppSpecialize)(ModuleBase*,AppSpecializeArgs*);
    void (*postAppSpecialize)(ModuleBase*,const AppSpecializeArgs*);
    void (*preServerSpecialize)(ModuleBase*,ServerSpecializeArgs*);
    void (*postServerSpecialize)(ModuleBase*,const ServerSpecializeArgs*);
    explicit module_abi(ModuleBase*m):api_version(ZYGISK_API_VERSION),_this(m){
        preAppSpecialize=[](auto s,auto a){s->preAppSpecialize(a);};
        postAppSpecialize=[](auto s,auto a){s->postAppSpecialize(a);};
        preServerSpecialize=[](auto s,auto a){s->preServerSpecialize(a);};
        postServerSpecialize=[](auto s,auto a){s->postServerSpecialize(a);};
    }
};
struct api_table {
    void *_this; bool (*registerModule)(api_table*,module_abi*);
    void (*hookJniNativeMethods)(JNIEnv*,const char*,JNINativeMethod*,int);
    void (*pltHookRegister)(const char*,const char*,void*,void**);
    void (*pltHookExclude)(const char*,const char*);
    bool (*pltHookCommit)();
    int (*connectCompanion)(void*); void (*setOption)(void*,Option);
    int (*getModuleDir)(void*); uint32_t (*getFlags)(void*);
};
template<class T> void entry_impl(api_table*table,JNIEnv*env){
    static Api api; api.impl=reinterpret_cast<void *>(table);
    static T module; ModuleBase*m=&module; static module_abi abi(m);
    if(!table->registerModule(table,&abi))return; m->onLoad(&api,env);
}
}
inline int Api::connectCompanion(){auto t=reinterpret_cast<internal::api_table*>(impl);return t->connectCompanion?t->connectCompanion(t->_this):-1;}
inline int Api::getModuleDir(){auto t=reinterpret_cast<internal::api_table*>(impl);return t->getModuleDir?t->getModuleDir(t->_this):-1;}
inline void Api::setOption(Option o){auto t=reinterpret_cast<internal::api_table*>(impl);if(t->setOption)t->setOption(t->_this,o);}
inline uint32_t Api::getFlags(){auto t=reinterpret_cast<internal::api_table*>(impl);return t->getFlags?t->getFlags(t->_this):0;}
inline void Api::hookJniNativeMethods(JNIEnv*e,const char*c,JNINativeMethod*m,int n){auto t=reinterpret_cast<internal::api_table*>(impl);if(t->hookJniNativeMethods)t->hookJniNativeMethods(e,c,m,n);}
inline void Api::pltHookRegister(const char*r,const char*s,void*n,void**o){auto t=reinterpret_cast<internal::api_table*>(impl);if(t->pltHookRegister)t->pltHookRegister(r,s,n,o);}
inline void Api::pltHookExclude(const char*r,const char*s){auto t=reinterpret_cast<internal::api_table*>(impl);if(t->pltHookExclude)t->pltHookExclude(r,s);}
inline bool Api::pltHookCommit(){auto t=reinterpret_cast<internal::api_table*>(impl);return t->pltHookCommit&&t->pltHookCommit();}
}
#define REGISTER_ZYGISK_MODULE(clazz) extern "C" [[gnu::visibility("default")]] void zygisk_module_entry(zygisk::internal::api_table*t,JNIEnv*e){zygisk::internal::entry_impl<clazz>(t,e);}
#define REGISTER_ZYGISK_COMPANION(func) extern "C" [[gnu::visibility("default")]] void zygisk_companion_entry(int client){func(client);}
