#define VK_USE_PLATFORM_ANDROID_KHR

#include <vulkan/vulkan.h>

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "shaders_spv.h"

namespace {

constexpr char kLogTag[] = "VulkanGrassSpike";
constexpr uint32_t kMaxFramesInFlight = 2;
constexpr uint32_t kBladeCount = 512;
constexpr uint32_t kGrassVerticesPerBlade = 36;
constexpr uint32_t kDandelionCount = 18;
constexpr uint32_t kParticleCount = 96;
constexpr uint64_t kAcquireTimeoutNs = 100'000'000ULL;

std::mutex gLifecycleMutex;
std::mutex gStatusMutex;
std::string gStatus = "Vulkan 尚未启动";
std::atomic<bool> gRunning{false};
std::atomic<bool> gPaused{false};
std::atomic<bool> gRecreateSwapchain{false};
std::atomic<int> gSeasonStep{0};
std::atomic<int> gTimeStep{0};
std::atomic<bool> gSyncWithLocalTime{true};
std::atomic<int> gRotationSeconds{300};
std::atomic<uint32_t> gSceneModeRevision{1};
std::thread gRenderThread;
ANativeWindow* gWindow = nullptr;
uint64_t gActiveOwner = 0;

void setStatus(const char* format, ...) {
    char buffer[2048];
    va_list arguments;
    va_start(arguments, format);
    std::vsnprintf(buffer, sizeof(buffer), format, arguments);
    va_end(arguments);

    {
        std::lock_guard<std::mutex> lock(gStatusMutex);
        gStatus = buffer;
    }
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "%s", buffer);
}

bool hasExtension(const std::vector<VkExtensionProperties>& extensions,
        const char* requested) {
    for (const VkExtensionProperties& extension : extensions) {
        if (std::strcmp(extension.extensionName, requested) == 0) {
            return true;
        }
    }
    return false;
}

const char* deviceTypeName(VkPhysicalDeviceType type) {
    switch (type) {
        case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU:
            return "integrated GPU";
        case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU:
            return "discrete GPU";
        case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU:
            return "virtual GPU";
        case VK_PHYSICAL_DEVICE_TYPE_CPU:
            return "CPU";
        default:
            return "other";
    }
}

struct SwapchainSupport {
    VkSurfaceCapabilitiesKHR capabilities{};
    std::vector<VkSurfaceFormatKHR> formats;
    std::vector<VkPresentModeKHR> presentModes;
};

struct ScenePushConstants {
    float time;
    float aspect;
    float seasonPhase;
    float hour;
    float windStrength;
    float bladeHeightScale;
};

struct LocalClock {
    int month = 1;
    float hour = 12.0f;
};

LocalClock readLocalClock() {
    std::time_t timestamp = std::time(nullptr);
    std::tm localTime{};
    localtime_r(&timestamp, &localTime);
    return {
            localTime.tm_mon + 1,
            static_cast<float>(localTime.tm_hour)
                    + static_cast<float>(localTime.tm_min) / 60.0f
                    + static_cast<float>(localTime.tm_sec) / 3600.0f,
    };
}

int seasonIndexForMonth(int month) {
    if (month >= 3 && month <= 5) {
        return 0;
    }
    if (month >= 6 && month <= 8) {
        return 1;
    }
    if (month >= 9 && month <= 11) {
        return 2;
    }
    return 3;
}

int timeSlotForHour(float hour) {
    if (hour >= 5.0f && hour < 9.0f) {
        return 0;
    }
    if (hour >= 9.0f && hour < 17.0f) {
        return 1;
    }
    if (hour >= 17.0f && hour < 20.0f) {
        return 2;
    }
    return 3;
}

const char* const kSeasonNames[] = {"春", "夏", "秋", "冬"};
const char* const kTimeNames[] = {"清晨", "白天", "傍晚", "夜晚"};
constexpr float kTimeSlotHours[] = {7.0f, 13.0f, 18.3f, 23.0f};

class VulkanRenderer {
public:
    bool init(ANativeWindow* window) {
        window_ = window;
        startedAt_ = std::chrono::steady_clock::now();
        lastFrameAt_ = startedAt_;
        initializeSceneState();

        setStatus("Vulkan: creating instance");
        if (!createInstance()) {
            return false;
        }
        if (!createSurface()) {
            return false;
        }
        if (!selectPhysicalDevice()) {
            return false;
        }
        if (!createDevice()) {
            return false;
        }

        setStatus("Vulkan: creating swapchain");
        if (!createSwapchainResources()) {
            return false;
        }
        if (!createRenderPass()) {
            return false;
        }
        if (!createPipelines()) {
            return false;
        }
        if (!createFramebuffers()) {
            return false;
        }
        if (!createCommandResources()) {
            return false;
        }
        if (!createSyncObjects()) {
            return false;
        }

        refreshStatusText();
        return true;
    }

    bool drawFrame() {
        updateSceneState();
        if (gRecreateSwapchain.exchange(false)) {
            if (!recreateSwapchain()) {
                return false;
            }
        }

        VkFence frameFence = inFlightFences_[currentFrame_];
        VkResult result = vkWaitForFences(
                device_, 1, &frameFence, VK_TRUE, UINT64_MAX);
        if (result != VK_SUCCESS) {
            setStatus("ERROR: vkWaitForFences failed (%d)", static_cast<int>(result));
            return false;
        }

        uint32_t imageIndex = 0;
        result = acquireNextImageKHR_(
                device_,
                swapchain_,
                kAcquireTimeoutNs,
                imageAvailableSemaphores_[currentFrame_],
                VK_NULL_HANDLE,
                &imageIndex);
        if (result == VK_TIMEOUT) {
            return true;
        }
        if (result == VK_ERROR_OUT_OF_DATE_KHR) {
            return recreateSwapchain();
        }
        if (result == VK_SUBOPTIMAL_KHR) {
            gRecreateSwapchain.store(true);
        } else if (result != VK_SUCCESS) {
            setStatus("ERROR: vkAcquireNextImageKHR failed (%d)", static_cast<int>(result));
            return false;
        }

        result = vkResetFences(device_, 1, &frameFence);
        if (result != VK_SUCCESS) {
            setStatus("ERROR: vkResetFences failed (%d)", static_cast<int>(result));
            return false;
        }

        VkCommandBuffer commandBuffer = commandBuffers_[currentFrame_];
        vkResetCommandBuffer(commandBuffer, 0);
        if (!recordCommandBuffer(commandBuffer, imageIndex)) {
            return false;
        }

        VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        VkSemaphore waitSemaphore = imageAvailableSemaphores_[currentFrame_];
        VkSemaphore signalSemaphore = renderFinishedSemaphores_[currentFrame_];
        VkSubmitInfo submitInfo{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        submitInfo.waitSemaphoreCount = 1;
        submitInfo.pWaitSemaphores = &waitSemaphore;
        submitInfo.pWaitDstStageMask = &waitStage;
        submitInfo.commandBufferCount = 1;
        submitInfo.pCommandBuffers = &commandBuffer;
        submitInfo.signalSemaphoreCount = 1;
        submitInfo.pSignalSemaphores = &signalSemaphore;
        result = vkQueueSubmit(queue_, 1, &submitInfo, frameFence);
        if (result != VK_SUCCESS) {
            setStatus("ERROR: vkQueueSubmit failed (%d)", static_cast<int>(result));
            return false;
        }

        VkPresentInfoKHR presentInfo{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
        presentInfo.waitSemaphoreCount = 1;
        presentInfo.pWaitSemaphores = &signalSemaphore;
        presentInfo.swapchainCount = 1;
        presentInfo.pSwapchains = &swapchain_;
        presentInfo.pImageIndices = &imageIndex;
        result = queuePresentKHR_(queue_, &presentInfo);
        if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_SUBOPTIMAL_KHR
                || gRecreateSwapchain.exchange(false)) {
            if (!recreateSwapchain()) {
                return false;
            }
        } else if (result != VK_SUCCESS) {
            setStatus("ERROR: vkQueuePresentKHR failed (%d)", static_cast<int>(result));
            return false;
        }

        currentFrame_ = (currentFrame_ + 1) % kMaxFramesInFlight;
        return true;
    }

    void cleanup() {
        if (device_ != VK_NULL_HANDLE) {
            vkDeviceWaitIdle(device_);
        }
        destroySwapchainResources();
        destroyPipeline();
        if (renderPass_ != VK_NULL_HANDLE) {
            vkDestroyRenderPass(device_, renderPass_, nullptr);
            renderPass_ = VK_NULL_HANDLE;
        }
        for (VkSemaphore semaphore : imageAvailableSemaphores_) {
            vkDestroySemaphore(device_, semaphore, nullptr);
        }
        imageAvailableSemaphores_.clear();
        for (VkSemaphore semaphore : renderFinishedSemaphores_) {
            vkDestroySemaphore(device_, semaphore, nullptr);
        }
        renderFinishedSemaphores_.clear();
        for (VkFence fence : inFlightFences_) {
            vkDestroyFence(device_, fence, nullptr);
        }
        inFlightFences_.clear();
        if (commandPool_ != VK_NULL_HANDLE) {
            vkDestroyCommandPool(device_, commandPool_, nullptr);
            commandPool_ = VK_NULL_HANDLE;
        }
        if (device_ != VK_NULL_HANDLE) {
            vkDestroyDevice(device_, nullptr);
            device_ = VK_NULL_HANDLE;
        }
        if (surface_ != VK_NULL_HANDLE) {
            vkDestroySurfaceKHR(instance_, surface_, nullptr);
            surface_ = VK_NULL_HANDLE;
        }
        if (instance_ != VK_NULL_HANDLE) {
            vkDestroyInstance(instance_, nullptr);
            instance_ = VK_NULL_HANDLE;
        }
        window_ = nullptr;
    }

private:
    void initializeSceneState() {
        LocalClock clock = readLocalClock();
        seasonTargetIndex_ = seasonIndexForMonth(clock.month);
        seasonPhase_ = static_cast<float>(seasonTargetIndex_);
        autoHour_ = clock.hour;
        hour_ = autoHour_;
        timeTargetIndex_ = timeSlotForHour(hour_);
        syncWithLocalTime_ = gSyncWithLocalTime.load();
        rotationSeconds_ = std::max(10, gRotationSeconds.load());
        sceneModeRevision_ = gSceneModeRevision.load();
    }

    void updateSceneState() {
        auto now = std::chrono::steady_clock::now();
        float deltaSeconds = std::chrono::duration<float>(now - lastFrameAt_).count();
        lastFrameAt_ = now;
        deltaSeconds = std::clamp(deltaSeconds, 0.0f, 0.1f);

        uint32_t sceneModeRevision = gSceneModeRevision.load();
        bool syncWithLocalTime = gSyncWithLocalTime.load();
        int rotationSeconds = std::max(10, gRotationSeconds.load());
        if (sceneModeRevision != sceneModeRevision_
                || syncWithLocalTime != syncWithLocalTime_
                || rotationSeconds != rotationSeconds_) {
            bool modeChanged = syncWithLocalTime != syncWithLocalTime_;
            sceneModeRevision_ = sceneModeRevision;
            syncWithLocalTime_ = syncWithLocalTime;
            rotationSeconds_ = rotationSeconds;
            manualSeason_ = false;
            manualTime_ = false;
            rotationAccumulator_ = 0.0f;
            if (modeChanged && !syncWithLocalTime_) {
                LocalClock clock = readLocalClock();
                seasonTargetIndex_ = seasonIndexForMonth(clock.month);
                timeTargetIndex_ = timeSlotForHour(clock.hour);
            }
        }

        int seasonStep = gSeasonStep.exchange(0);
        if (seasonStep != 0) {
            manualSeason_ = true;
            seasonTargetIndex_ = (seasonTargetIndex_ + seasonStep) % 4;
            if (seasonTargetIndex_ < 0) {
                seasonTargetIndex_ += 4;
            }
        }

        int timeStep = gTimeStep.exchange(0);
        if (timeStep != 0) {
            if (!manualTime_) {
                timeTargetIndex_ = timeSlotForHour(hour_);
            }
            manualTime_ = true;
            timeTargetIndex_ = (timeTargetIndex_ + timeStep) % 4;
            if (timeTargetIndex_ < 0) {
                timeTargetIndex_ += 4;
            }
        }

        LocalClock clock = readLocalClock();
        if (syncWithLocalTime_) {
            if (!manualSeason_) {
                seasonTargetIndex_ = seasonIndexForMonth(clock.month);
            }
            if (!manualTime_) {
                autoHour_ = clock.hour;
            }
        } else {
            rotationAccumulator_ += deltaSeconds;
            while (rotationAccumulator_ >= static_cast<float>(rotationSeconds_)) {
                rotationAccumulator_ -= static_cast<float>(rotationSeconds_);
                manualSeason_ = false;
                manualTime_ = false;
                timeTargetIndex_ = (timeTargetIndex_ + 1) % 4;
                if (timeTargetIndex_ == 0) {
                    seasonTargetIndex_ = (seasonTargetIndex_ + 1) % 4;
                }
            }
        }

        float seasonDifference = static_cast<float>(seasonTargetIndex_) - seasonPhase_;
        while (seasonDifference > 2.0f) {
            seasonDifference -= 4.0f;
        }
        while (seasonDifference < -2.0f) {
            seasonDifference += 4.0f;
        }
        seasonPhase_ += seasonDifference * std::min(1.0f, deltaSeconds * 3.2f);
        seasonPhase_ = std::fmod(seasonPhase_, 4.0f);
        if (seasonPhase_ < 0.0f) {
            seasonPhase_ += 4.0f;
        }
        if (std::fabs(seasonDifference) < 0.0015f) {
            seasonPhase_ = static_cast<float>(seasonTargetIndex_);
        }

        float targetHour = (!syncWithLocalTime_ || manualTime_)
                ? kTimeSlotHours[timeTargetIndex_]
                : autoHour_;
        float hourDifference = targetHour - hour_;
        while (hourDifference > 12.0f) {
            hourDifference -= 24.0f;
        }
        while (hourDifference < -12.0f) {
            hourDifference += 24.0f;
        }
        hour_ += hourDifference * std::min(1.0f, deltaSeconds * 1.8f);
        hour_ = std::fmod(hour_, 24.0f);
        if (hour_ < 0.0f) {
            hour_ += 24.0f;
        }
        if (std::fabs(hourDifference) < 0.001f) {
            hour_ = targetHour;
        }

        statusRefreshAccumulator_ += deltaSeconds;
        if (statusRefreshAccumulator_ >= 0.5f) {
            statusRefreshAccumulator_ = 0.0f;
            refreshStatusText();
        }
    }

    void refreshStatusText() {
        uint32_t apiMajor = VK_API_VERSION_MAJOR(properties_.apiVersion);
        uint32_t apiMinor = VK_API_VERSION_MINOR(properties_.apiVersion);
        uint32_t apiPatch = VK_API_VERSION_PATCH(properties_.apiVersion);
#if defined(__aarch64__)
        const char* abi = "arm64-v8a";
#elif defined(__arm__)
        const char* abi = "armeabi-v7a";
#else
        const char* abi = "unknown";
#endif
        int seasonIndex = seasonTargetIndex_;
        int timeIndex = timeSlotForHour(hour_);
        const char* seasonSource = manualSeason_
                ? "手动"
                : (syncWithLocalTime_ ? "同步" : "轮转");
        const char* timeSource = manualTime_
                ? "手动"
                : (syncWithLocalTime_ ? "同步" : "轮转");
        char modeDescription[64];
        if (syncWithLocalTime_) {
            std::snprintf(modeDescription, sizeof(modeDescription),
                    "跟随本地时间");
        } else {
            std::snprintf(modeDescription, sizeof(modeDescription),
                    "每 %d 秒轮转", rotationSeconds_);
        }
        setStatus(
                "READY\nGPU: %s\nVulkan API: %u.%u.%u | driver 0x%08x\n"
                "Type: %s | ABI: %s | MSAA: %ux\n"
                "Swapchain: %ux%u | 更新: %s\n"
                "季节: %s (%s) | 时段: %s (%s)\n"
                "草叶 %u | 蒲公英 %u | 粒子 %u\n"
                "轻点左半屏切换季节，右半屏切换时段",
                properties_.deviceName,
                apiMajor,
                apiMinor,
                apiPatch,
                properties_.driverVersion,
                deviceTypeName(properties_.deviceType),
                abi,
                static_cast<uint32_t>(sampleCount_),
                swapchainExtent_.width,
                swapchainExtent_.height,
                modeDescription,
                kSeasonNames[seasonIndex],
                seasonSource,
                kTimeNames[timeIndex],
                timeSource,
                kBladeCount,
                kDandelionCount,
                kParticleCount);
    }

    bool createInstance() {
        uint32_t extensionCount = 0;
        VkResult result = vkEnumerateInstanceExtensionProperties(
                nullptr, &extensionCount, nullptr);
        if (result != VK_SUCCESS || extensionCount == 0) {
            return fail("vkEnumerateInstanceExtensionProperties", result);
        }
        std::vector<VkExtensionProperties> extensions(extensionCount);
        result = vkEnumerateInstanceExtensionProperties(
                nullptr, &extensionCount, extensions.data());
        if (result != VK_SUCCESS) {
            return fail("vkEnumerateInstanceExtensionProperties", result);
        }
        if (!hasExtension(extensions, VK_KHR_SURFACE_EXTENSION_NAME)
                || !hasExtension(extensions, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME)) {
            setStatus("ERROR: Android Vulkan surface extensions are unavailable");
            return false;
        }

        VkApplicationInfo applicationInfo{VK_STRUCTURE_TYPE_APPLICATION_INFO};
        applicationInfo.pApplicationName = "A65 Vulkan Grass Spike";
        applicationInfo.applicationVersion = VK_MAKE_VERSION(0, 11, 0);
        applicationInfo.pEngineName = "A65CanvasPort";
        applicationInfo.engineVersion = VK_MAKE_VERSION(0, 1, 0);
        applicationInfo.apiVersion = VK_API_VERSION_1_0;

        const char* enabledExtensions[] = {
                VK_KHR_SURFACE_EXTENSION_NAME,
                VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
        };
        VkInstanceCreateInfo createInfo{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
        createInfo.pApplicationInfo = &applicationInfo;
        createInfo.enabledExtensionCount = 2;
        createInfo.ppEnabledExtensionNames = enabledExtensions;
        result = vkCreateInstance(&createInfo, nullptr, &instance_);
        if (result != VK_SUCCESS) {
            return fail("vkCreateInstance", result);
        }
        return true;
    }

    bool createSurface() {
        auto createAndroidSurface = reinterpret_cast<PFN_vkCreateAndroidSurfaceKHR>(
                vkGetInstanceProcAddr(instance_, "vkCreateAndroidSurfaceKHR"));
        if (createAndroidSurface == nullptr) {
            setStatus("ERROR: vkCreateAndroidSurfaceKHR is unavailable");
            return false;
        }

        VkAndroidSurfaceCreateInfoKHR createInfo{
                VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
        createInfo.window = window_;
        VkResult result = createAndroidSurface(
                instance_, &createInfo, nullptr, &surface_);
        if (result != VK_SUCCESS) {
            return fail("vkCreateAndroidSurfaceKHR", result);
        }
        return true;
    }

    bool selectPhysicalDevice() {
        uint32_t deviceCount = 0;
        VkResult result = vkEnumeratePhysicalDevices(
                instance_, &deviceCount, nullptr);
        if (result != VK_SUCCESS || deviceCount == 0) {
            setStatus("ERROR: no Vulkan physical device found (%d)",
                    static_cast<int>(result));
            return false;
        }

        std::vector<VkPhysicalDevice> devices(deviceCount);
        result = vkEnumeratePhysicalDevices(instance_, &deviceCount, devices.data());
        if (result != VK_SUCCESS) {
            return fail("vkEnumeratePhysicalDevices", result);
        }

        for (VkPhysicalDevice candidate : devices) {
            uint32_t queueFamilyCount = 0;
            vkGetPhysicalDeviceQueueFamilyProperties(
                    candidate, &queueFamilyCount, nullptr);
            std::vector<VkQueueFamilyProperties> families(queueFamilyCount);
            vkGetPhysicalDeviceQueueFamilyProperties(
                    candidate, &queueFamilyCount, families.data());

            uint32_t selectedQueue = UINT32_MAX;
            for (uint32_t index = 0; index < queueFamilyCount; index++) {
                if ((families[index].queueFlags & VK_QUEUE_GRAPHICS_BIT) == 0) {
                    continue;
                }
                VkBool32 supportsPresent = VK_FALSE;
                vkGetPhysicalDeviceSurfaceSupportKHR(
                        candidate, index, surface_, &supportsPresent);
                if (supportsPresent == VK_TRUE) {
                    selectedQueue = index;
                    break;
                }
            }
            if (selectedQueue == UINT32_MAX) {
                continue;
            }

            uint32_t extensionCount = 0;
            vkEnumerateDeviceExtensionProperties(
                    candidate, nullptr, &extensionCount, nullptr);
            std::vector<VkExtensionProperties> extensions(extensionCount);
            vkEnumerateDeviceExtensionProperties(
                    candidate, nullptr, &extensionCount, extensions.data());
            if (!hasExtension(extensions, VK_KHR_SWAPCHAIN_EXTENSION_NAME)) {
                continue;
            }

            physicalDevice_ = candidate;
            queueFamilyIndex_ = selectedQueue;
            vkGetPhysicalDeviceProperties(physicalDevice_, &properties_);
            return true;
        }

        setStatus("ERROR: no Vulkan device with graphics, present and swapchain support");
        return false;
    }

    bool createDevice() {
        float queuePriority = 1.0f;
        VkDeviceQueueCreateInfo queueInfo{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        queueInfo.queueFamilyIndex = queueFamilyIndex_;
        queueInfo.queueCount = 1;
        queueInfo.pQueuePriorities = &queuePriority;

        const char* enabledExtensions[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
        VkDeviceCreateInfo createInfo{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
        createInfo.queueCreateInfoCount = 1;
        createInfo.pQueueCreateInfos = &queueInfo;
        createInfo.enabledExtensionCount = 1;
        createInfo.ppEnabledExtensionNames = enabledExtensions;

        VkResult result = vkCreateDevice(
                physicalDevice_, &createInfo, nullptr, &device_);
        if (result != VK_SUCCESS) {
            return fail("vkCreateDevice", result);
        }
        vkGetDeviceQueue(device_, queueFamilyIndex_, 0, &queue_);

        createSwapchainKHR_ = reinterpret_cast<PFN_vkCreateSwapchainKHR>(
                vkGetDeviceProcAddr(device_, "vkCreateSwapchainKHR"));
        destroySwapchainKHR_ = reinterpret_cast<PFN_vkDestroySwapchainKHR>(
                vkGetDeviceProcAddr(device_, "vkDestroySwapchainKHR"));
        getSwapchainImagesKHR_ = reinterpret_cast<PFN_vkGetSwapchainImagesKHR>(
                vkGetDeviceProcAddr(device_, "vkGetSwapchainImagesKHR"));
        acquireNextImageKHR_ = reinterpret_cast<PFN_vkAcquireNextImageKHR>(
                vkGetDeviceProcAddr(device_, "vkAcquireNextImageKHR"));
        queuePresentKHR_ = reinterpret_cast<PFN_vkQueuePresentKHR>(
                vkGetDeviceProcAddr(device_, "vkQueuePresentKHR"));
        if (createSwapchainKHR_ == nullptr || destroySwapchainKHR_ == nullptr
                || getSwapchainImagesKHR_ == nullptr
                || acquireNextImageKHR_ == nullptr || queuePresentKHR_ == nullptr) {
            setStatus("ERROR: required swapchain entry points are unavailable");
            return false;
        }
        return true;
    }

    SwapchainSupport querySwapchainSupport() const {
        SwapchainSupport support;
        vkGetPhysicalDeviceSurfaceCapabilitiesKHR(
                physicalDevice_, surface_, &support.capabilities);
        uint32_t formatCount = 0;
        vkGetPhysicalDeviceSurfaceFormatsKHR(
                physicalDevice_, surface_, &formatCount, nullptr);
        support.formats.resize(formatCount);
        if (formatCount > 0) {
            vkGetPhysicalDeviceSurfaceFormatsKHR(
                    physicalDevice_, surface_, &formatCount, support.formats.data());
        }
        uint32_t modeCount = 0;
        vkGetPhysicalDeviceSurfacePresentModesKHR(
                physicalDevice_, surface_, &modeCount, nullptr);
        support.presentModes.resize(modeCount);
        if (modeCount > 0) {
            vkGetPhysicalDeviceSurfacePresentModesKHR(
                    physicalDevice_, surface_, &modeCount, support.presentModes.data());
        }
        return support;
    }

    VkSurfaceFormatKHR chooseSurfaceFormat(
            const std::vector<VkSurfaceFormatKHR>& formats) const {
        for (const VkSurfaceFormatKHR& format : formats) {
            if (format.format == VK_FORMAT_B8G8R8A8_UNORM
                    && format.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                return format;
            }
        }
        for (const VkSurfaceFormatKHR& format : formats) {
            if (format.format == VK_FORMAT_R8G8B8A8_UNORM
                    && format.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                return format;
            }
        }
        return formats.front();
    }

    VkSampleCountFlagBits chooseSampleCount() const {
        VkSampleCountFlags supported = properties_.limits.framebufferColorSampleCounts;
        const VkSampleCountFlagBits candidates[] = {
                VK_SAMPLE_COUNT_4_BIT,
                VK_SAMPLE_COUNT_2_BIT,
        };
        for (VkSampleCountFlagBits candidate : candidates) {
            if ((supported & candidate) == 0) {
                continue;
            }
            VkImageFormatProperties imageProperties{};
            VkResult result = vkGetPhysicalDeviceImageFormatProperties(
                    physicalDevice_,
                    swapchainFormat_,
                    VK_IMAGE_TYPE_2D,
                    VK_IMAGE_TILING_OPTIMAL,
                    VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
                    0,
                    &imageProperties);
            if (result == VK_SUCCESS
                    && (imageProperties.sampleCounts & candidate) != 0) {
                return candidate;
            }
        }
        return VK_SAMPLE_COUNT_1_BIT;
    }

    uint32_t findMemoryType(uint32_t typeBits,
            VkMemoryPropertyFlags requiredProperties) const {
        VkPhysicalDeviceMemoryProperties memoryProperties{};
        vkGetPhysicalDeviceMemoryProperties(physicalDevice_, &memoryProperties);
        for (uint32_t index = 0; index < memoryProperties.memoryTypeCount; index++) {
            if ((typeBits & (1u << index)) == 0) {
                continue;
            }
            if ((memoryProperties.memoryTypes[index].propertyFlags
                    & requiredProperties) == requiredProperties) {
                return index;
            }
        }
        return UINT32_MAX;
    }

    bool createMultisampleTargets() {
        if (sampleCount_ == VK_SAMPLE_COUNT_1_BIT) {
            return true;
        }

        VkImageCreateInfo imageInfo{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
        imageInfo.imageType = VK_IMAGE_TYPE_2D;
        imageInfo.format = swapchainFormat_;
        imageInfo.extent = {swapchainExtent_.width, swapchainExtent_.height, 1};
        imageInfo.mipLevels = 1;
        imageInfo.arrayLayers = 1;
        imageInfo.samples = sampleCount_;
        imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
        imageInfo.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        VkResult result = vkCreateImage(
                device_, &imageInfo, nullptr, &multisampleImage_);
        if (result != VK_SUCCESS) {
            return fail("vkCreateImage (MSAA)", result);
        }

        VkMemoryRequirements memoryRequirements{};
        vkGetImageMemoryRequirements(device_, multisampleImage_, &memoryRequirements);
        uint32_t memoryTypeIndex = findMemoryType(
                memoryRequirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (memoryTypeIndex == UINT32_MAX) {
            setStatus("ERROR: no device-local memory for MSAA image");
            return false;
        }

        VkMemoryAllocateInfo allocateInfo{
                VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        allocateInfo.allocationSize = memoryRequirements.size;
        allocateInfo.memoryTypeIndex = memoryTypeIndex;
        result = vkAllocateMemory(
                device_, &allocateInfo, nullptr, &multisampleMemory_);
        if (result != VK_SUCCESS) {
            return fail("vkAllocateMemory (MSAA)", result);
        }
        result = vkBindImageMemory(
                device_, multisampleImage_, multisampleMemory_, 0);
        if (result != VK_SUCCESS) {
            return fail("vkBindImageMemory (MSAA)", result);
        }

        VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        viewInfo.image = multisampleImage_;
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = swapchainFormat_;
        viewInfo.components.r = VK_COMPONENT_SWIZZLE_IDENTITY;
        viewInfo.components.g = VK_COMPONENT_SWIZZLE_IDENTITY;
        viewInfo.components.b = VK_COMPONENT_SWIZZLE_IDENTITY;
        viewInfo.components.a = VK_COMPONENT_SWIZZLE_IDENTITY;
        viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        viewInfo.subresourceRange.baseMipLevel = 0;
        viewInfo.subresourceRange.levelCount = 1;
        viewInfo.subresourceRange.baseArrayLayer = 0;
        viewInfo.subresourceRange.layerCount = 1;
        result = vkCreateImageView(
                device_, &viewInfo, nullptr, &multisampleImageView_);
        if (result != VK_SUCCESS) {
            return fail("vkCreateImageView (MSAA)", result);
        }
        return true;
    }

    void destroyMultisampleTargets() {
        if (multisampleImageView_ != VK_NULL_HANDLE) {
            vkDestroyImageView(device_, multisampleImageView_, nullptr);
            multisampleImageView_ = VK_NULL_HANDLE;
        }
        if (multisampleImage_ != VK_NULL_HANDLE) {
            vkDestroyImage(device_, multisampleImage_, nullptr);
            multisampleImage_ = VK_NULL_HANDLE;
        }
        if (multisampleMemory_ != VK_NULL_HANDLE) {
            vkFreeMemory(device_, multisampleMemory_, nullptr);
            multisampleMemory_ = VK_NULL_HANDLE;
        }
    }

    bool createSwapchainResources() {
        SwapchainSupport support = querySwapchainSupport();
        if (support.formats.empty() || support.presentModes.empty()) {
            setStatus("ERROR: surface has no formats or present modes");
            return false;
        }

        VkSurfaceFormatKHR surfaceFormat = chooseSurfaceFormat(support.formats);
        swapchainFormat_ = surfaceFormat.format;
        swapchainColorSpace_ = surfaceFormat.colorSpace;
        sampleCount_ = chooseSampleCount();

        VkExtent2D extent = support.capabilities.currentExtent;
        if (extent.width == UINT32_MAX || extent.height == UINT32_MAX) {
            int windowWidth = ANativeWindow_getWidth(window_);
            int windowHeight = ANativeWindow_getHeight(window_);
            extent.width = static_cast<uint32_t>(std::max(windowWidth, 1));
            extent.height = static_cast<uint32_t>(std::max(windowHeight, 1));
            extent.width = std::clamp(extent.width,
                    support.capabilities.minImageExtent.width,
                    support.capabilities.maxImageExtent.width);
            extent.height = std::clamp(extent.height,
                    support.capabilities.minImageExtent.height,
                    support.capabilities.maxImageExtent.height);
        }
        if (extent.width == 0 || extent.height == 0) {
            setStatus("ERROR: Vulkan surface has zero size");
            return false;
        }
        swapchainExtent_ = extent;

        uint32_t imageCount = support.capabilities.minImageCount + 1;
        if (support.capabilities.maxImageCount > 0
                && imageCount > support.capabilities.maxImageCount) {
            imageCount = support.capabilities.maxImageCount;
        }

        VkCompositeAlphaFlagBitsKHR compositeAlpha =
                VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
        if ((support.capabilities.supportedCompositeAlpha
                & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR) == 0) {
            compositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
            if ((support.capabilities.supportedCompositeAlpha
                    & VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR) == 0) {
                for (uint32_t bit = 1; bit <= VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR;
                        bit <<= 1) {
                    if ((support.capabilities.supportedCompositeAlpha & bit) != 0) {
                        compositeAlpha = static_cast<VkCompositeAlphaFlagBitsKHR>(bit);
                        break;
                    }
                }
            }
        }

        VkSwapchainCreateInfoKHR createInfo{
                VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
        createInfo.surface = surface_;
        createInfo.minImageCount = imageCount;
        createInfo.imageFormat = swapchainFormat_;
        createInfo.imageColorSpace = swapchainColorSpace_;
        createInfo.imageExtent = extent;
        createInfo.imageArrayLayers = 1;
        createInfo.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        createInfo.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        createInfo.preTransform = support.capabilities.currentTransform;
        createInfo.compositeAlpha = compositeAlpha;
        createInfo.presentMode = VK_PRESENT_MODE_FIFO_KHR;
        createInfo.clipped = VK_TRUE;

        VkResult result = createSwapchainKHR_(
                device_, &createInfo, nullptr, &swapchain_);
        if (result != VK_SUCCESS) {
            return fail("vkCreateSwapchainKHR", result);
        }

        uint32_t actualImageCount = 0;
        getSwapchainImagesKHR_(device_, swapchain_, &actualImageCount, nullptr);
        swapchainImages_.resize(actualImageCount);
        result = getSwapchainImagesKHR_(
                device_, swapchain_, &actualImageCount, swapchainImages_.data());
        if (result != VK_SUCCESS) {
            return fail("vkGetSwapchainImagesKHR", result);
        }

        swapchainImageViews_.resize(actualImageCount, VK_NULL_HANDLE);
        for (uint32_t index = 0; index < actualImageCount; index++) {
            VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
            viewInfo.image = swapchainImages_[index];
            viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
            viewInfo.format = swapchainFormat_;
            viewInfo.components.r = VK_COMPONENT_SWIZZLE_IDENTITY;
            viewInfo.components.g = VK_COMPONENT_SWIZZLE_IDENTITY;
            viewInfo.components.b = VK_COMPONENT_SWIZZLE_IDENTITY;
            viewInfo.components.a = VK_COMPONENT_SWIZZLE_IDENTITY;
            viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            viewInfo.subresourceRange.baseMipLevel = 0;
            viewInfo.subresourceRange.levelCount = 1;
            viewInfo.subresourceRange.baseArrayLayer = 0;
            viewInfo.subresourceRange.layerCount = 1;
            result = vkCreateImageView(
                    device_, &viewInfo, nullptr, &swapchainImageViews_[index]);
            if (result != VK_SUCCESS) {
                return fail("vkCreateImageView", result);
            }
        }
        if (!createMultisampleTargets()) {
            return false;
        }
        return true;
    }

    bool createRenderPass() {
        VkAttachmentDescription attachments[2]{};
        attachments[0].format = swapchainFormat_;
        attachments[0].samples = sampleCount_;
        attachments[0].loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        attachments[0].storeOp = sampleCount_ == VK_SAMPLE_COUNT_1_BIT
                ? VK_ATTACHMENT_STORE_OP_STORE
                : VK_ATTACHMENT_STORE_OP_DONT_CARE;
        attachments[0].stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        attachments[0].stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        attachments[0].initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        attachments[0].finalLayout = sampleCount_ == VK_SAMPLE_COUNT_1_BIT
                ? VK_IMAGE_LAYOUT_PRESENT_SRC_KHR
                : VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

        uint32_t attachmentCount = 1;
        if (sampleCount_ != VK_SAMPLE_COUNT_1_BIT) {
            attachments[1].format = swapchainFormat_;
            attachments[1].samples = VK_SAMPLE_COUNT_1_BIT;
            attachments[1].loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
            attachments[1].storeOp = VK_ATTACHMENT_STORE_OP_STORE;
            attachments[1].stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
            attachments[1].stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
            attachments[1].initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            attachments[1].finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
            attachmentCount = 2;
        }

        VkAttachmentReference colorReference{};
        colorReference.attachment = 0;
        colorReference.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

        VkAttachmentReference resolveReference{};
        resolveReference.attachment = 1;
        resolveReference.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

        VkSubpassDescription subpass{};
        subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        subpass.colorAttachmentCount = 1;
        subpass.pColorAttachments = &colorReference;
        if (sampleCount_ != VK_SAMPLE_COUNT_1_BIT) {
            subpass.pResolveAttachments = &resolveReference;
        }

        VkSubpassDependency dependency{};
        dependency.srcSubpass = VK_SUBPASS_EXTERNAL;
        dependency.dstSubpass = 0;
        dependency.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependency.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependency.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

        VkRenderPassCreateInfo createInfo{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
        createInfo.attachmentCount = attachmentCount;
        createInfo.pAttachments = attachments;
        createInfo.subpassCount = 1;
        createInfo.pSubpasses = &subpass;
        createInfo.dependencyCount = 1;
        createInfo.pDependencies = &dependency;
        VkResult result = vkCreateRenderPass(
                device_, &createInfo, nullptr, &renderPass_);
        if (result != VK_SUCCESS) {
            return fail("vkCreateRenderPass", result);
        }
        return true;
    }

    bool createShaderModule(const unsigned char* bytes, size_t size,
            VkShaderModule* module) {
        if (size == 0 || (size % sizeof(uint32_t)) != 0) {
            setStatus("ERROR: invalid SPIR-V shader size");
            return false;
        }
        std::vector<uint32_t> code(size / sizeof(uint32_t));
        std::memcpy(code.data(), bytes, size);
        VkShaderModuleCreateInfo createInfo{
                VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
        createInfo.codeSize = code.size() * sizeof(uint32_t);
        createInfo.pCode = code.data();
        VkResult result = vkCreateShaderModule(
                device_, &createInfo, nullptr, module);
        if (result != VK_SUCCESS) {
            return fail("vkCreateShaderModule", result);
        }
        return true;
    }

    bool createPipelineLayout() {
        VkPushConstantRange pushRange{};
        pushRange.stageFlags = VK_SHADER_STAGE_VERTEX_BIT
                | VK_SHADER_STAGE_FRAGMENT_BIT;
        pushRange.offset = 0;
        pushRange.size = sizeof(ScenePushConstants);
        VkPipelineLayoutCreateInfo layoutInfo{
                VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        layoutInfo.pushConstantRangeCount = 1;
        layoutInfo.pPushConstantRanges = &pushRange;
        VkResult result = vkCreatePipelineLayout(
                device_, &layoutInfo, nullptr, &pipelineLayout_);
        if (result != VK_SUCCESS) {
            return fail("vkCreatePipelineLayout", result);
        }
        return true;
    }

    bool createGraphicsPipeline(const unsigned char* vertexBytes,
            size_t vertexSize, const unsigned char* fragmentBytes,
            size_t fragmentSize, bool alphaBlend, VkPipeline* pipeline) {
        VkShaderModule vertexModule = VK_NULL_HANDLE;
        VkShaderModule fragmentModule = VK_NULL_HANDLE;
        if (!createShaderModule(vertexBytes, vertexSize, &vertexModule)) {
            return false;
        }
        if (!createShaderModule(fragmentBytes, fragmentSize, &fragmentModule)) {
            vkDestroyShaderModule(device_, vertexModule, nullptr);
            return false;
        }

        VkPipelineShaderStageCreateInfo stages[2] = {};
        stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
        stages[0].module = vertexModule;
        stages[0].pName = "main";
        stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
        stages[1].module = fragmentModule;
        stages[1].pName = "main";

        VkPipelineVertexInputStateCreateInfo vertexInput{
                VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
        VkPipelineInputAssemblyStateCreateInfo inputAssembly{
                VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
        inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

        VkPipelineViewportStateCreateInfo viewportState{
                VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
        viewportState.viewportCount = 1;
        viewportState.scissorCount = 1;

        VkPipelineRasterizationStateCreateInfo rasterizer{
                VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
        rasterizer.polygonMode = VK_POLYGON_MODE_FILL;
        rasterizer.cullMode = VK_CULL_MODE_NONE;
        rasterizer.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
        rasterizer.lineWidth = 1.0f;

        VkPipelineMultisampleStateCreateInfo multisampling{
                VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
        multisampling.rasterizationSamples = sampleCount_;

        VkPipelineColorBlendAttachmentState blendAttachment{};
        blendAttachment.blendEnable = alphaBlend ? VK_TRUE : VK_FALSE;
        blendAttachment.srcColorBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
        blendAttachment.dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
        blendAttachment.colorBlendOp = VK_BLEND_OP_ADD;
        blendAttachment.srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
        blendAttachment.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
        blendAttachment.alphaBlendOp = VK_BLEND_OP_ADD;
        blendAttachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT
                | VK_COLOR_COMPONENT_G_BIT
                | VK_COLOR_COMPONENT_B_BIT
                | VK_COLOR_COMPONENT_A_BIT;
        VkPipelineColorBlendStateCreateInfo colorBlend{
                VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
        colorBlend.attachmentCount = 1;
        colorBlend.pAttachments = &blendAttachment;

        VkDynamicState dynamicStates[] = {
                VK_DYNAMIC_STATE_VIEWPORT,
                VK_DYNAMIC_STATE_SCISSOR,
        };
        VkPipelineDynamicStateCreateInfo dynamicState{
                VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
        dynamicState.dynamicStateCount = 2;
        dynamicState.pDynamicStates = dynamicStates;

        VkGraphicsPipelineCreateInfo pipelineInfo{
                VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
        pipelineInfo.stageCount = 2;
        pipelineInfo.pStages = stages;
        pipelineInfo.pVertexInputState = &vertexInput;
        pipelineInfo.pInputAssemblyState = &inputAssembly;
        pipelineInfo.pViewportState = &viewportState;
        pipelineInfo.pRasterizationState = &rasterizer;
        pipelineInfo.pMultisampleState = &multisampling;
        pipelineInfo.pColorBlendState = &colorBlend;
        pipelineInfo.pDynamicState = &dynamicState;
        pipelineInfo.layout = pipelineLayout_;
        pipelineInfo.renderPass = renderPass_;
        pipelineInfo.subpass = 0;

        VkResult result = vkCreateGraphicsPipelines(
                device_, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, pipeline);
        vkDestroyShaderModule(device_, vertexModule, nullptr);
        vkDestroyShaderModule(device_, fragmentModule, nullptr);
        if (result != VK_SUCCESS) {
            return fail("vkCreateGraphicsPipelines", result);
        }
        return true;
    }

    bool createPipelines() {
        if (pipelineLayout_ == VK_NULL_HANDLE && !createPipelineLayout()) {
            return false;
        }
        if (!createGraphicsPipeline(sky_vert_spv, sky_vert_spv_len,
                    sky_frag_spv, sky_frag_spv_len, false, &skyPipeline_)) {
            return false;
        }
        if (!createGraphicsPipeline(grass_vert_spv, grass_vert_spv_len,
                    grass_frag_spv, grass_frag_spv_len, true, &grassPipeline_)) {
            return false;
        }
        if (!createGraphicsPipeline(dandelion_vert_spv, dandelion_vert_spv_len,
                    dandelion_frag_spv, dandelion_frag_spv_len, true,
                    &dandelionPipeline_)) {
            return false;
        }
        if (!createGraphicsPipeline(particle_vert_spv, particle_vert_spv_len,
                    particle_frag_spv, particle_frag_spv_len, true,
                    &particlePipeline_)) {
            return false;
        }
        return true;
    }

    bool createFramebuffers() {
        swapchainFramebuffers_.resize(
                swapchainImageViews_.size(), VK_NULL_HANDLE);
        for (size_t index = 0; index < swapchainImageViews_.size(); index++) {
            VkImageView attachments[2]{};
            uint32_t attachmentCount = 0;
            if (sampleCount_ == VK_SAMPLE_COUNT_1_BIT) {
                attachments[0] = swapchainImageViews_[index];
                attachmentCount = 1;
            } else {
                attachments[0] = multisampleImageView_;
                attachments[1] = swapchainImageViews_[index];
                attachmentCount = 2;
            }
            VkFramebufferCreateInfo createInfo{
                    VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
            createInfo.renderPass = renderPass_;
            createInfo.attachmentCount = attachmentCount;
            createInfo.pAttachments = attachments;
            createInfo.width = swapchainExtent_.width;
            createInfo.height = swapchainExtent_.height;
            createInfo.layers = 1;
            VkResult result = vkCreateFramebuffer(
                    device_, &createInfo, nullptr, &swapchainFramebuffers_[index]);
            if (result != VK_SUCCESS) {
                return fail("vkCreateFramebuffer", result);
            }
        }
        return true;
    }

    bool createCommandResources() {
        VkCommandPoolCreateInfo poolInfo{
                VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        poolInfo.queueFamilyIndex = queueFamilyIndex_;
        VkResult result = vkCreateCommandPool(
                device_, &poolInfo, nullptr, &commandPool_);
        if (result != VK_SUCCESS) {
            return fail("vkCreateCommandPool", result);
        }

        commandBuffers_.resize(kMaxFramesInFlight, VK_NULL_HANDLE);
        VkCommandBufferAllocateInfo allocateInfo{
                VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        allocateInfo.commandPool = commandPool_;
        allocateInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        allocateInfo.commandBufferCount = kMaxFramesInFlight;
        result = vkAllocateCommandBuffers(
                device_, &allocateInfo, commandBuffers_.data());
        if (result != VK_SUCCESS) {
            return fail("vkAllocateCommandBuffers", result);
        }
        return true;
    }

    bool createSyncObjects() {
        VkSemaphoreCreateInfo semaphoreInfo{
                VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;

        imageAvailableSemaphores_.resize(kMaxFramesInFlight, VK_NULL_HANDLE);
        renderFinishedSemaphores_.resize(kMaxFramesInFlight, VK_NULL_HANDLE);
        inFlightFences_.resize(kMaxFramesInFlight, VK_NULL_HANDLE);
        for (uint32_t frame = 0; frame < kMaxFramesInFlight; frame++) {
            VkResult result = vkCreateSemaphore(
                    device_, &semaphoreInfo, nullptr, &imageAvailableSemaphores_[frame]);
            if (result != VK_SUCCESS) {
                return fail("vkCreateSemaphore", result);
            }
            result = vkCreateSemaphore(
                    device_, &semaphoreInfo, nullptr, &renderFinishedSemaphores_[frame]);
            if (result != VK_SUCCESS) {
                return fail("vkCreateSemaphore", result);
            }
            result = vkCreateFence(
                    device_, &fenceInfo, nullptr, &inFlightFences_[frame]);
            if (result != VK_SUCCESS) {
                return fail("vkCreateFence", result);
            }
        }
        return true;
    }

    bool recordCommandBuffer(VkCommandBuffer commandBuffer, uint32_t imageIndex) {
        VkCommandBufferBeginInfo beginInfo{
                VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        VkResult result = vkBeginCommandBuffer(commandBuffer, &beginInfo);
        if (result != VK_SUCCESS) {
            return fail("vkBeginCommandBuffer", result);
        }

        VkClearValue clearValue{};
        clearValue.color.float32[0] = 0.045f;
        clearValue.color.float32[1] = 0.30f;
        clearValue.color.float32[2] = 0.55f;
        clearValue.color.float32[3] = 1.0f;

        VkRenderPassBeginInfo renderPassInfo{
                VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
        renderPassInfo.renderPass = renderPass_;
        renderPassInfo.framebuffer = swapchainFramebuffers_[imageIndex];
        renderPassInfo.renderArea.offset = {0, 0};
        renderPassInfo.renderArea.extent = swapchainExtent_;
        renderPassInfo.clearValueCount = 1;
        renderPassInfo.pClearValues = &clearValue;
        vkCmdBeginRenderPass(
                commandBuffer, &renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);

        VkViewport viewport{};
        viewport.x = 0.0f;
        viewport.y = 0.0f;
        viewport.width = static_cast<float>(swapchainExtent_.width);
        viewport.height = static_cast<float>(swapchainExtent_.height);
        viewport.minDepth = 0.0f;
        viewport.maxDepth = 1.0f;
        VkRect2D scissor{};
        scissor.offset = {0, 0};
        scissor.extent = swapchainExtent_;
        vkCmdSetViewport(commandBuffer, 0, 1, &viewport);
        vkCmdSetScissor(commandBuffer, 0, 1, &scissor);
        float elapsedSeconds = std::chrono::duration<float>(
                std::chrono::steady_clock::now() - startedAt_).count();
        ScenePushConstants pushConstants{};
        pushConstants.time = elapsedSeconds;
        pushConstants.aspect = static_cast<float>(swapchainExtent_.width)
                / static_cast<float>(swapchainExtent_.height);
        pushConstants.seasonPhase = seasonPhase_;
        pushConstants.hour = hour_;
        pushConstants.windStrength = 1.0f;
        pushConstants.bladeHeightScale = 1.0f;
        vkCmdPushConstants(
                commandBuffer,
                pipelineLayout_,
                VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                0,
                sizeof(pushConstants),
                &pushConstants);

        vkCmdBindPipeline(
                commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, skyPipeline_);
        vkCmdDraw(commandBuffer, 3, 1, 0, 0);

        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                dandelionPipeline_);
        vkCmdDraw(commandBuffer, 12, kDandelionCount, 0, 0);

        vkCmdBindPipeline(
                commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, grassPipeline_);
        vkCmdDraw(commandBuffer, kGrassVerticesPerBlade, kBladeCount, 0, 0);

        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                particlePipeline_);
        vkCmdDraw(commandBuffer, 6, kParticleCount, 0, 0);
        vkCmdEndRenderPass(commandBuffer);

        result = vkEndCommandBuffer(commandBuffer);
        if (result != VK_SUCCESS) {
            return fail("vkEndCommandBuffer", result);
        }
        return true;
    }

    bool recreateSwapchain() {
        if (device_ == VK_NULL_HANDLE) {
            return false;
        }
        vkDeviceWaitIdle(device_);
        VkFormat previousFormat = swapchainFormat_;
        VkSampleCountFlagBits previousSampleCount = sampleCount_;
        destroySwapchainResources();
        if (!createSwapchainResources()) {
            return false;
        }
        if (swapchainFormat_ != previousFormat
                || sampleCount_ != previousSampleCount) {
            destroyPipeline();
            if (renderPass_ != VK_NULL_HANDLE) {
                vkDestroyRenderPass(device_, renderPass_, nullptr);
                renderPass_ = VK_NULL_HANDLE;
            }
            if (!createRenderPass() || !createPipelines()) {
                return false;
            }
        }
        if (!createFramebuffers()) {
            return false;
        }
        return true;
    }

    void destroySwapchainResources() {
        for (VkFramebuffer framebuffer : swapchainFramebuffers_) {
            if (framebuffer != VK_NULL_HANDLE) {
                vkDestroyFramebuffer(device_, framebuffer, nullptr);
            }
        }
        swapchainFramebuffers_.clear();
        for (VkImageView imageView : swapchainImageViews_) {
            if (imageView != VK_NULL_HANDLE) {
                vkDestroyImageView(device_, imageView, nullptr);
            }
        }
        swapchainImageViews_.clear();
        destroyMultisampleTargets();
        if (swapchain_ != VK_NULL_HANDLE) {
            destroySwapchainKHR_(device_, swapchain_, nullptr);
            swapchain_ = VK_NULL_HANDLE;
        }
        swapchainImages_.clear();
    }

    void destroyPipeline() {
        VkPipeline* pipelines[] = {
                &skyPipeline_,
                &grassPipeline_,
                &dandelionPipeline_,
                &particlePipeline_,
        };
        for (VkPipeline* pipeline : pipelines) {
            if (*pipeline != VK_NULL_HANDLE) {
                vkDestroyPipeline(device_, *pipeline, nullptr);
                *pipeline = VK_NULL_HANDLE;
            }
        }
        if (pipelineLayout_ != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device_, pipelineLayout_, nullptr);
            pipelineLayout_ = VK_NULL_HANDLE;
        }
    }

    bool fail(const char* operation, VkResult result) {
        setStatus("ERROR: %s failed (%d)", operation, static_cast<int>(result));
        return false;
    }

    ANativeWindow* window_ = nullptr;
    VkInstance instance_ = VK_NULL_HANDLE;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice_ = VK_NULL_HANDLE;
    VkPhysicalDeviceProperties properties_{};
    VkDevice device_ = VK_NULL_HANDLE;
    uint32_t queueFamilyIndex_ = UINT32_MAX;
    VkQueue queue_ = VK_NULL_HANDLE;

    PFN_vkCreateSwapchainKHR createSwapchainKHR_ = nullptr;
    PFN_vkDestroySwapchainKHR destroySwapchainKHR_ = nullptr;
    PFN_vkGetSwapchainImagesKHR getSwapchainImagesKHR_ = nullptr;
    PFN_vkAcquireNextImageKHR acquireNextImageKHR_ = nullptr;
    PFN_vkQueuePresentKHR queuePresentKHR_ = nullptr;

    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    std::vector<VkImage> swapchainImages_;
    std::vector<VkImageView> swapchainImageViews_;
    std::vector<VkFramebuffer> swapchainFramebuffers_;
    VkFormat swapchainFormat_ = VK_FORMAT_UNDEFINED;
    VkColorSpaceKHR swapchainColorSpace_ = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    VkExtent2D swapchainExtent_{};
    VkSampleCountFlagBits sampleCount_ = VK_SAMPLE_COUNT_1_BIT;
    VkImage multisampleImage_ = VK_NULL_HANDLE;
    VkDeviceMemory multisampleMemory_ = VK_NULL_HANDLE;
    VkImageView multisampleImageView_ = VK_NULL_HANDLE;
    VkRenderPass renderPass_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline skyPipeline_ = VK_NULL_HANDLE;
    VkPipeline grassPipeline_ = VK_NULL_HANDLE;
    VkPipeline dandelionPipeline_ = VK_NULL_HANDLE;
    VkPipeline particlePipeline_ = VK_NULL_HANDLE;
    VkCommandPool commandPool_ = VK_NULL_HANDLE;
    std::vector<VkCommandBuffer> commandBuffers_;
    std::vector<VkSemaphore> imageAvailableSemaphores_;
    std::vector<VkSemaphore> renderFinishedSemaphores_;
    std::vector<VkFence> inFlightFences_;
    uint32_t currentFrame_ = 0;
    std::chrono::steady_clock::time_point startedAt_{};
    std::chrono::steady_clock::time_point lastFrameAt_{};
    float seasonPhase_ = 0.0f;
    int seasonTargetIndex_ = 0;
    bool manualSeason_ = false;
    float autoHour_ = 12.0f;
    float hour_ = 12.0f;
    int timeTargetIndex_ = 1;
    bool manualTime_ = false;
    bool syncWithLocalTime_ = true;
    int rotationSeconds_ = 300;
    float rotationAccumulator_ = 0.0f;
    uint32_t sceneModeRevision_ = 0;
    float statusRefreshAccumulator_ = 0.0f;
};

void stopRendererLocked() {
    gPaused.store(false);
    gRunning.store(false);
    if (gRenderThread.joinable()) {
        gRenderThread.join();
    }
    if (gWindow != nullptr) {
        ANativeWindow_release(gWindow);
        gWindow = nullptr;
    }
    gActiveOwner = 0;
    setStatus("Vulkan: renderer stopped");
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_a65_grasswallpapers_vulkan_VulkanNative_nativeStart(
        JNIEnv* env, jclass, jobject surface, jlong ownerId) {
    if (ownerId <= 0) {
        setStatus("ERROR: nativeStart received invalid owner id");
        return;
    }
    std::lock_guard<std::mutex> lock(gLifecycleMutex);
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) {
        setStatus("ERROR: ANativeWindow_fromSurface returned null");
        return;
    }

    stopRendererLocked();
    gWindow = window;
    gActiveOwner = static_cast<uint64_t>(ownerId);
    gPaused.store(false);
    gRecreateSwapchain.store(false);
    gSeasonStep.store(0);
    gTimeStep.store(0);
    gRunning.store(true);
    setStatus("Vulkan: starting renderer owner=%lld",
            static_cast<long long>(ownerId));
    gRenderThread = std::thread([window] {
        VulkanRenderer renderer;
        if (!renderer.init(window)) {
            setStatus("render thread: init failed");
            renderer.cleanup();
            gRunning.store(false);
            return;
        }

        while (gRunning.load()) {
            if (gPaused.load()) {
                std::this_thread::sleep_for(std::chrono::milliseconds(40));
                continue;
            }
            if (!renderer.drawFrame()) {
                setStatus("render thread: drawFrame failed");
                break;
            }
        }
        renderer.cleanup();
        gRunning.store(false);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_a65_grasswallpapers_vulkan_VulkanNative_nativeStop(
        JNIEnv*, jclass, jlong ownerId) {
    std::lock_guard<std::mutex> lock(gLifecycleMutex);
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
            "nativeStop owner=%lld active=%llu",
            static_cast<long long>(ownerId),
            static_cast<unsigned long long>(gActiveOwner));
    if (gActiveOwner != static_cast<uint64_t>(ownerId)) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag,
                "Ignoring stale nativeStop owner=%lld active=%llu",
                static_cast<long long>(ownerId),
                static_cast<unsigned long long>(gActiveOwner));
        return;
    }
    stopRendererLocked();
}

extern "C" JNIEXPORT void JNICALL
Java_com_a65_grasswallpapers_vulkan_VulkanNative_nativeResize(
        JNIEnv*, jclass, jlong ownerId, jint, jint) {
    std::lock_guard<std::mutex> lock(gLifecycleMutex);
    if (gActiveOwner != static_cast<uint64_t>(ownerId)) {
        return;
    }
    gRecreateSwapchain.store(true);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_a65_grasswallpapers_vulkan_VulkanNative_nativeSetPaused(
        JNIEnv*, jclass, jlong ownerId, jboolean paused) {
    std::lock_guard<std::mutex> lock(gLifecycleMutex);
    if (gActiveOwner != static_cast<uint64_t>(ownerId)) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag,
                "Ignoring stale nativeSetPaused owner=%lld active=%llu",
                static_cast<long long>(ownerId),
                static_cast<unsigned long long>(gActiveOwner));
        return gActiveOwner == 0 ? 0 : -1;
    }
    if (!gRunning.load()) {
        return 0;
    }
    gPaused.store(paused == JNI_TRUE);
    return 1;
}

extern "C" JNIEXPORT void JNICALL
Java_com_a65_grasswallpapers_vulkan_VulkanNative_nativeConfigureSceneMode(
        JNIEnv*, jclass, jboolean syncWithLocalTime,
        jint rotationSeconds) {
    bool sync = syncWithLocalTime == JNI_TRUE;
    int seconds = std::clamp(
            static_cast<int>(rotationSeconds), 10, 24 * 60 * 60);
    if (gSyncWithLocalTime.load() == sync
            && gRotationSeconds.load() == seconds) {
        return;
    }
    gSyncWithLocalTime.store(sync);
    gRotationSeconds.store(seconds);
    gSceneModeRevision.fetch_add(1);
}

extern "C" JNIEXPORT void JNICALL
Java_com_a65_grasswallpapers_vulkan_VulkanNative_nativeCycleSeason(
        JNIEnv*, jclass) {
    gSeasonStep.fetch_add(1);
}

extern "C" JNIEXPORT void JNICALL
Java_com_a65_grasswallpapers_vulkan_VulkanNative_nativeCycleTime(
        JNIEnv*, jclass) {
    gTimeStep.fetch_add(1);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_a65_grasswallpapers_vulkan_VulkanNative_nativeGetStatus(
        JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(gStatusMutex);
    return env->NewStringUTF(gStatus.c_str());
}
