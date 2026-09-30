/*
 * Android 6.0 (API 23) compatibility stub for libvulkan.so.
 *
 * The platform did not ship libvulkan.so until Android 7.0, but libmpv.so
 * (media_kit) declares DT_NEEDED on it and resolves vkGetInstanceProcAddr
 * eagerly. Without this stub the load fails with UnsatisfiedLinkError inside
 * MediaKitPlugin.onAttachedToEngine; being an Error, it escapes the generated
 * plugin registrant's catch(Exception) and every plugin registered after
 * media_kit stays dead (shared_preferences, sqflite, package_info, ...).
 *
 * Every entry point returns NULL / an error code, which callers treat as
 * "vulkan unavailable" and fall back to other render paths. On Android 7+
 * devices this stub shadows the real libvulkan for this process; the player
 * stack uses OpenGL there, so nothing regresses in practice.
 */

#include <stdint.h>

void *vkGetInstanceProcAddr(void *instance, const char *name) {
    (void) instance;
    (void) name;
    return 0;
}

void *vkGetDeviceProcAddr(void *device, const char *name) {
    (void) device;
    (void) name;
    return 0;
}

void *vkCreateAndroidSurfaceKHR(void *instance, void *createInfo, void *allocator, void *surface) {
    (void) instance;
    (void) createInfo;
    (void) allocator;
    (void) surface;
    return 0;
}

void vkDestroySurfaceKHR(void *instance, void *surface, void *allocator) {
    (void) instance;
    (void) surface;
    (void) allocator;
}

/* VkResult: negative values are errors. */
int32_t vkEnumeratePhysicalDevices(void *instance, uint32_t *count, void *devices) {
    (void) instance;
    (void) devices;
    if (count != 0) {
        *count = 0;
    }
    return -1; /* VK_ERROR_INITIALIZATION_FAILED */
}

int32_t vkGetPhysicalDeviceProperties2(void *device, void *properties) {
    (void) device;
    (void) properties;
    return -1;
}

int32_t vkGetPhysicalDeviceQueueFamilyProperties2(void *device, void *count, void *properties) {
    (void) device;
    (void) properties;
    if (count != 0) {
        *(uint32_t *) count = 0;
    }
    return -1;
}
