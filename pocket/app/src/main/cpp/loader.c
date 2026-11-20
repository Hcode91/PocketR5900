// PocketR5900 game loader.
//
// GameActivity is a NativeActivity whose library is this small loader. The
// converted game itself is a library compiled on the phone into the app's
// private storage, which NativeActivity cannot load by name, so the loader
// opens it from the path the app passes in POCKETR5900_GAME_LIB and forwards
// the activity to the game's own ANativeActivity_onCreate.
#include <android/log.h>
#include <android/native_activity.h>
#include <dlfcn.h>
#include <stdlib.h>

#define TAG "PocketR5900"

typedef void (*OnCreateFn)(ANativeActivity *, void *, size_t);

__attribute__((visibility("default")))
void ANativeActivity_onCreate(ANativeActivity *activity, void *savedState, size_t savedStateSize)
{
    const char *path = getenv("POCKETR5900_GAME_LIB");
    if (path == NULL || path[0] == '\0')
    {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "no game library was given");
        ANativeActivity_finish(activity);
        return;
    }

    void *game = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (game == NULL)
    {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "cannot load %s: %s", path, dlerror());
        ANativeActivity_finish(activity);
        return;
    }

    OnCreateFn onCreate = (OnCreateFn)dlsym(game, "ANativeActivity_onCreate");
    if (onCreate == NULL || onCreate == ANativeActivity_onCreate)
    {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "%s has no ANativeActivity_onCreate", path);
        ANativeActivity_finish(activity);
        return;
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "starting %s", path);
    onCreate(activity, savedState, savedStateSize);
}
