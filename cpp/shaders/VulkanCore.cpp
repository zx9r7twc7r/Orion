#define VK_ENABLE_BETA_EXTENSIONS
#define VK_USE_PLATFORM_WIN32_KHR

#include <jni.h>
#include <vulkan/vulkan.h>
#include <windows.h>
#include <atomic>
#include <array>
#include <deque>
#include <unordered_map>
#include <unordered_set>
#include <cstdio>
#include <cstring>
#include <cmath>
#include <mutex>
#include <sl.h>
#include <sl_helpers_vk.h>
#include <sl_matrix_helpers.h>
#include <sl_core_types.h>
#include "sl_reflex.h"
#include "sl_pcl.h"
#include <stdexcept>
#include "sl_dlss.h"
#include "sl_dlss_d.h"
#include "sl_dlss_g.h"
#include <fstream>
#include <iostream>
#include <string>
#include <utility>
#include <vector>
#include <algorithm>
#include <cstdint>
#include <limits>
#include <sstream>
#include <chrono>
#include <iomanip>

// Some older Vulkan SDK headers used by the Java runtime expose ray tracing
// but predate VK_EXT_opacity_micromap.  Keep the ABI declarations local so
// the native backend can still build against those SDKs.
#ifndef VK_EXT_opacity_micromap
#define VK_EXT_opacity_micromap 1
VK_DEFINE_NON_DISPATCHABLE_HANDLE(VkMicromapEXT)
typedef enum VkMicromapTypeEXT {
    VK_MICROMAP_TYPE_OPACITY_MICROMAP_EXT = 0,
} VkMicromapTypeEXT;
typedef enum VkBuildMicromapModeEXT {
    VK_BUILD_MICROMAP_MODE_BUILD_EXT = 0,
} VkBuildMicromapModeEXT;
typedef enum VkOpacityMicromapFormatEXT {
    VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT = 2,
} VkOpacityMicromapFormatEXT;
typedef VkFlags VkBuildMicromapFlagsEXT;
typedef VkFlags VkMicromapCreateFlagsEXT;
typedef struct VkMicromapUsageEXT {
    uint32_t count;
    uint32_t subdivisionLevel;
    uint32_t format;
} VkMicromapUsageEXT;
typedef struct VkMicromapBuildInfoEXT {
    VkStructureType sType;
    const void* pNext;
    VkMicromapTypeEXT type;
    VkBuildMicromapFlagsEXT flags;
    VkBuildMicromapModeEXT mode;
    VkMicromapEXT dstMicromap;
    uint32_t usageCountsCount;
    const VkMicromapUsageEXT* pUsageCounts;
    const VkMicromapUsageEXT* const* ppUsageCounts;
    VkDeviceOrHostAddressConstKHR data;
    VkDeviceOrHostAddressKHR scratchData;
    VkDeviceOrHostAddressConstKHR triangleArray;
    VkDeviceSize triangleArrayStride;
} VkMicromapBuildInfoEXT;
typedef struct VkMicromapCreateInfoEXT {
    VkStructureType sType;
    const void* pNext;
    VkMicromapCreateFlagsEXT createFlags;
    VkBuffer buffer;
    VkDeviceSize offset;
    VkDeviceSize size;
    VkMicromapTypeEXT type;
    VkDeviceAddress deviceAddress;
} VkMicromapCreateInfoEXT;
typedef struct VkMicromapBuildSizesInfoEXT {
    VkStructureType sType;
    const void* pNext;
    VkDeviceSize micromapSize;
    VkDeviceSize buildScratchSize;
    VkBool32 discardable;
} VkMicromapBuildSizesInfoEXT;
typedef struct VkPhysicalDeviceOpacityMicromapPropertiesEXT {
    VkStructureType sType;
    void* pNext;
    uint32_t maxOpacity2StateSubdivisionLevel;
    uint32_t maxOpacity4StateSubdivisionLevel;
} VkPhysicalDeviceOpacityMicromapPropertiesEXT;
typedef struct VkAccelerationStructureTrianglesOpacityMicromapEXT {
    VkStructureType sType;
    void* pNext;
    VkIndexType indexType;
    VkDeviceOrHostAddressConstKHR indexBuffer;
    VkDeviceSize indexStride;
    uint32_t baseTriangle;
    uint32_t usageCountsCount;
    const VkMicromapUsageEXT* pUsageCounts;
    const VkMicromapUsageEXT* const* ppUsageCounts;
    VkMicromapEXT micromap;
} VkAccelerationStructureTrianglesOpacityMicromapEXT;
typedef struct VkMicromapTriangleEXT {
    uint32_t dataOffset;
    uint16_t subdivisionLevel;
    uint16_t format;
} VkMicromapTriangleEXT;
typedef VkResult (VKAPI_PTR *PFN_vkCreateMicromapEXT)(VkDevice,const VkMicromapCreateInfoEXT*,const VkAllocationCallbacks*,VkMicromapEXT*);
typedef void (VKAPI_PTR *PFN_vkDestroyMicromapEXT)(VkDevice,VkMicromapEXT,const VkAllocationCallbacks*);
typedef void (VKAPI_PTR *PFN_vkCmdBuildMicromapsEXT)(VkCommandBuffer,uint32_t,const VkMicromapBuildInfoEXT*);
typedef void (VKAPI_PTR *PFN_vkGetMicromapBuildSizesEXT)(VkDevice,VkAccelerationStructureBuildTypeKHR,const VkMicromapBuildInfoEXT*,VkMicromapBuildSizesInfoEXT*);
static constexpr VkStructureType VK_STRUCTURE_TYPE_MICROMAP_BUILD_INFO_EXT = static_cast<VkStructureType>(1000396000);
static constexpr VkStructureType VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPACITY_MICROMAP_PROPERTIES_EXT = static_cast<VkStructureType>(1000396006);
static constexpr VkStructureType VK_STRUCTURE_TYPE_MICROMAP_CREATE_INFO_EXT = static_cast<VkStructureType>(1000396007);
static constexpr VkStructureType VK_STRUCTURE_TYPE_MICROMAP_BUILD_SIZES_INFO_EXT = static_cast<VkStructureType>(1000396008);
static constexpr VkStructureType VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_TRIANGLES_OPACITY_MICROMAP_EXT = static_cast<VkStructureType>(1000396009);
static constexpr VkBufferUsageFlags VK_BUFFER_USAGE_MICROMAP_BUILD_INPUT_READ_ONLY_BIT_EXT = 0x00800000;
static constexpr VkBufferUsageFlags VK_BUFFER_USAGE_MICROMAP_STORAGE_BIT_EXT = 0x01000000;
static constexpr VkBuildMicromapFlagsEXT VK_BUILD_MICROMAP_PREFER_FAST_TRACE_BIT_EXT = 0x00000001;
static constexpr VkPipelineCreateFlags VK_PIPELINE_CREATE_RAY_TRACING_OPACITY_MICROMAP_BIT_EXT = 0x01000000;
#endif

// The Minecraft runtime's Vulkan headers can predate the ratified EXT SER
// declarations.  This block is only used for a read-only physical-device
// capability query; it does not enable the extension on the live device.
#ifndef VK_EXT_ray_tracing_invocation_reorder
#define VK_EXT_ray_tracing_invocation_reorder 1
typedef struct VkPhysicalDeviceRayTracingInvocationReorderFeaturesEXT {
    VkStructureType sType;
    void* pNext;
    VkBool32 rayTracingInvocationReorder;
} VkPhysicalDeviceRayTracingInvocationReorderFeaturesEXT;
typedef enum VkRayTracingInvocationReorderModeEXT {
    VK_RAY_TRACING_INVOCATION_REORDER_MODE_NONE_EXT = 0,
    VK_RAY_TRACING_INVOCATION_REORDER_MODE_REORDER_EXT = 1,
} VkRayTracingInvocationReorderModeEXT;
typedef struct VkPhysicalDeviceRayTracingInvocationReorderPropertiesEXT {
    VkStructureType sType;
    void* pNext;
    VkRayTracingInvocationReorderModeEXT rayTracingInvocationReorderReorderingHint;
    uint32_t maxShaderBindingTableRecordIndex;
} VkPhysicalDeviceRayTracingInvocationReorderPropertiesEXT;
static constexpr VkStructureType
    VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_INVOCATION_REORDER_FEATURES_EXT =
        static_cast<VkStructureType>(1000582000);
static constexpr VkStructureType
    VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_INVOCATION_REORDER_PROPERTIES_EXT =
        static_cast<VkStructureType>(1000582001);
static constexpr const char*
    VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME =
        "VK_EXT_ray_tracing_invocation_reorder";
#endif
#include "deps/streamline/include/sl_core_types.h"


// =====================================================
// Internal Beta runtime path fix
//
// All native runtime files are resolved relative to the
// directory that actually contains rtx_backend.dll.
//
// This removes any dependency on the launcher/process
// working directory.
// =====================================================

extern "C" IMAGE_DOS_HEADER __ImageBase;


static std::string GetRTXBackendDirectory()
{
    static const std::string cachedDirectory = []() -> std::string
    {
        std::vector<char> buffer(
            32768,
            '\0'
        );


        const DWORD length =
            GetModuleFileNameA(
                reinterpret_cast<HMODULE>(
                    &__ImageBase
                ),
                buffer.data(),
                static_cast<DWORD>(
                    buffer.size()
                )
            );


        if(
            length == 0 ||
            length >= buffer.size()
        )
        {
            fprintf(
                stderr,
                "[RUNTIME PATH] GetModuleFileNameA failed error=%lu\n",
                GetLastError()
            );

            fflush(stderr);

            return {};
        }


        std::string modulePath(
            buffer.data(),
            length
        );


        const std::string::size_type slash =
            modulePath.find_last_of(
                "\\/"
            );


        if(slash == std::string::npos)
        {
            fprintf(
                stderr,
                "[RUNTIME PATH] invalid backend module path=%s\n",
                modulePath.c_str()
            );

            fflush(stderr);

            return {};
        }


        return modulePath.substr(
            0,
            slash
        );
    }();


    return cachedDirectory;
}


static bool IsAbsoluteWindowsPath(
    const char* path
)
{
    if(
        !path ||
        path[0] == '\0'
    )
    {
        return false;
    }


    // Drive path: C:\...
    if(
        path[0] != '\0' &&
        path[1] == ':' &&
        (
            path[2] == '\\' ||
            path[2] == '/'
        )
    )
    {
        return true;
    }


    // UNC / rooted path.
    return
        path[0] == '\\' ||
        path[0] == '/';
}


static std::string ResolveRTXRuntimePath(
    const char* path
)
{
    if(
        !path ||
        path[0] == '\0'
    )
    {
        return {};
    }


    if(IsAbsoluteWindowsPath(path))
    {
        return std::string(path);
    }


    const std::string baseDirectory =
        GetRTXBackendDirectory();


    if(baseDirectory.empty())
    {
        // Defensive fallback only.
        return std::string(path);
    }


    return
        baseDirectory +
        "\\" +
        path;
}


static std::ofstream rtLogFile(
    "D:\\RTX\\rt_debug.log",
    std::ios::app
);


static void RTLog(
    const std::string& msg
)
{
    auto now =
        std::chrono::system_clock::now();


    auto t =
        std::chrono::system_clock::to_time_t(now);


    rtLogFile
        << "[CPP] "
        << std::put_time(
            std::localtime(&t),
            "%H:%M:%S"
        )
        << " "
        << msg
        << "\n";

    // Rebuild and streaming events can arrive in bursts. Keep diagnostics
    // buffered so filesystem flush latency never lands on the render thread.
    // The stream is flushed automatically on shutdown/process teardown.
}

static bool g_isInWorld = false;
static const uint32_t MAX_TEXTURES = 4096;
static uint32_t g_currentAlbedoIndex = 0;
static uint32_t g_currentNormalIndex = 0;
static uint32_t g_currentSpecularIndex = 0;

struct VTRTexture {
    VkImage image;
    VkDeviceMemory memory;
    VkImageView view;
    VkSampler sampler;
};
// === 新增：PBR 物理图集全局变量 ===
static VkImage g_normalAtlasImage = VK_NULL_HANDLE;
static VkDeviceMemory g_normalAtlasMemory = VK_NULL_HANDLE;
static VkImageView g_normalAtlasView = VK_NULL_HANDLE;
static VkImage g_specularAtlasImage = VK_NULL_HANDLE;
static VkDeviceMemory g_specularAtlasMemory = VK_NULL_HANDLE;
static VkImageView g_specularAtlasView = VK_NULL_HANDLE;
// 标记是否有新的 PBR 图集指针到达，等待在渲染管线空闲时更新描述符
static bool g_pbrAtlasesDirty = false;

static std::vector<VTRTexture> g_albedoTextures;
static std::vector<VTRTexture> g_normalTextures;
static std::vector<VTRTexture> g_specularTextures;

static VkInstance g_instance=VK_NULL_HANDLE;
static VkPhysicalDevice g_physicalDevice=VK_NULL_HANDLE;
static VkDevice g_device=VK_NULL_HANDLE;
static VkQueue g_queue=VK_NULL_HANDLE;
static uint32_t g_queueFamily=UINT32_MAX;


// =====================================================
// Perf-L10.0 - non-blocking RT dispatch GPU timestamps
// =====================================================
// Timestamp results are read only when a ring slot has completed.  No
// query read uses VK_QUERY_RESULT_WAIT_BIT, so diagnostics cannot add a
// CPU/GPU synchronization point to the measured frame.
// Disabled for normal play: this is diagnostic-only and each sample otherwise
// adds query commands, a driver poll, timestamp formatting and a forced log
// flush.  Set to true only while collecting a targeted RT timing capture.
constexpr bool PERF_L10_ENABLE_RT_TIMESTAMPS = false;
// Section rebuilds can be triggered by animated entities every few frames.
// Keep their detailed file logging opt-in so it never adds CPU work there.
constexpr bool PERF_L10_ENABLE_SECTION_REBUILD_LOGS = false;
constexpr uint32_t PERF_L10_RT_TIMESTAMP_RING_SIZE = 8u;

struct PerfL10RTTimestampSlot
{
    bool pending = false;
    uint64_t frameSerial = 0u;
    uint32_t renderWidth = 0u;
    uint32_t renderHeight = 0u;
};

static VkQueryPool g_perfL10RtTimestampPool = VK_NULL_HANDLE;
static double g_perfL10TimestampPeriodNs = 0.0;
static uint64_t g_perfL10FrameSerial = 0u;

// Section uploads arrive in bursts while Minecraft streams chunks. Rebuilding
// BLAS/TLAS for every burst drains the RT queue repeatedly and turns loading
// into a frame-time cliff. Keep the current AS visible while dirty uploads
// coalesce, then rebuild a single combined batch at a bounded cadence.
constexpr uint64_t SECTION_REBUILD_MIN_FRAME_INTERVAL = 6u;
constexpr uint64_t SECTION_REBUILD_QUIET_FRAMES = 10u;
constexpr uint64_t SECTION_REBUILD_MAX_DEFERRAL_FRAMES = 60u;
constexpr size_t SECTION_REBUILD_MAX_SECTIONS_PER_BATCH = 24u;
static uint64_t g_nextSectionRebuildFrame = 0u;
static uint64_t g_firstPendingSectionFrame = 0u;
static uint64_t g_lastObservedSectionRevision = 0u;

static PerfL10RTTimestampSlot
    g_perfL10RtTimestampSlots[PERF_L10_RT_TIMESTAMP_RING_SIZE]{};


static void destroyPerfL10RtTimestampPool()
{
    if(g_perfL10RtTimestampPool != VK_NULL_HANDLE && g_device != VK_NULL_HANDLE)
    {
        vkDestroyQueryPool(
            g_device,
            g_perfL10RtTimestampPool,
            nullptr
        );
    }

    g_perfL10RtTimestampPool = VK_NULL_HANDLE;
    g_perfL10TimestampPeriodNs = 0.0;

    for(PerfL10RTTimestampSlot& slot : g_perfL10RtTimestampSlots)
    {
        slot = {};
    }
}


static bool ensurePerfL10RtTimestampPool()
{
    if(g_perfL10RtTimestampPool != VK_NULL_HANDLE)
    {
        return true;
    }

    if(g_device == VK_NULL_HANDLE || g_physicalDevice == VK_NULL_HANDLE)
    {
        return false;
    }

    VkQueryPoolCreateInfo createInfo{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};
    createInfo.queryType = VK_QUERY_TYPE_TIMESTAMP;
    createInfo.queryCount = PERF_L10_RT_TIMESTAMP_RING_SIZE * 2u;

    const VkResult result = vkCreateQueryPool(
        g_device,
        &createInfo,
        nullptr,
        &g_perfL10RtTimestampPool
    );

    if(result != VK_SUCCESS)
    {
        g_perfL10RtTimestampPool = VK_NULL_HANDLE;

        RTLog(
            "[PERF L10 RT] timestampQueryPoolCreate=" +
            std::to_string(static_cast<int>(result))
        );

        return false;
    }

    VkPhysicalDeviceProperties properties{};
    vkGetPhysicalDeviceProperties(
        g_physicalDevice,
        &properties
    );

    g_perfL10TimestampPeriodNs =
        static_cast<double>(properties.limits.timestampPeriod);

    RTLog(
        "[PERF L10 RT] timestampQueryPool=ready periodNs=" +
        std::to_string(g_perfL10TimestampPeriodNs)
    );

    return true;
}


static void pollPerfL10RtTimestampSlot(
    uint32_t slotIndex
)
{
    PerfL10RTTimestampSlot& slot =
        g_perfL10RtTimestampSlots[slotIndex];

    if(!slot.pending || g_perfL10RtTimestampPool == VK_NULL_HANDLE)
    {
        return;
    }

    uint64_t results[4]{};

    const VkResult result = vkGetQueryPoolResults(
        g_device,
        g_perfL10RtTimestampPool,
        slotIndex * 2u,
        2u,
        sizeof(results),
        results,
        sizeof(uint64_t) * 2u,
        VK_QUERY_RESULT_64_BIT |
        VK_QUERY_RESULT_WITH_AVAILABILITY_BIT
    );

    if(
        result != VK_SUCCESS ||
        results[1] == 0u ||
        results[3] == 0u
    )
    {
        return;
    }

    const uint64_t tickDelta =
        results[2] >= results[0]
            ? results[2] - results[0]
            : 0u;

    const double gpuMilliseconds =
        static_cast<double>(tickDelta) *
        g_perfL10TimestampPeriodNs /
        1000000.0;

    std::stringstream log;
    log
        << "[PERF L10 RT]"
        << " frame=" << slot.frameSerial
        << " gpuMs=" << std::fixed << std::setprecision(3)
        << gpuMilliseconds
        << " dispatch=" << slot.renderWidth << "x" << slot.renderHeight;

    RTLog(log.str());

    slot = {};
}


static int32_t beginPerfL10RtTimestampScope(
    VkCommandBuffer commandBuffer,
    uint32_t renderWidth,
    uint32_t renderHeight
)
{
    if constexpr(!PERF_L10_ENABLE_RT_TIMESTAMPS)
    {
        (void)commandBuffer;
        (void)renderWidth;
        (void)renderHeight;
        return -1;
    }

    if(!ensurePerfL10RtTimestampPool())
    {
        return -1;
    }

    const uint32_t slotIndex =
        static_cast<uint32_t>(
            g_perfL10FrameSerial %
            PERF_L10_RT_TIMESTAMP_RING_SIZE
        );

    pollPerfL10RtTimestampSlot(slotIndex);

    PerfL10RTTimestampSlot& slot =
        g_perfL10RtTimestampSlots[slotIndex];

    // The GPU is more than one ring behind.  Do not overwrite an unfinished
    // query and do not wait for it; the current frame simply has no sample.
    if(slot.pending)
    {
        return -1;
    }

    vkCmdResetQueryPool(
        commandBuffer,
        g_perfL10RtTimestampPool,
        slotIndex * 2u,
        2u
    );

    vkCmdWriteTimestamp(
        commandBuffer,
        VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
        g_perfL10RtTimestampPool,
        slotIndex * 2u
    );

    slot.pending = true;
    slot.frameSerial = g_perfL10FrameSerial;
    slot.renderWidth = renderWidth;
    slot.renderHeight = renderHeight;

    return static_cast<int32_t>(slotIndex);
}


static void endPerfL10RtTimestampScope(
    VkCommandBuffer commandBuffer,
    int32_t slotIndex
)
{
    if(slotIndex < 0 || g_perfL10RtTimestampPool == VK_NULL_HANDLE)
    {
        return;
    }

    vkCmdWriteTimestamp(
        commandBuffer,
        VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
        g_perfL10RtTimestampPool,
        static_cast<uint32_t>(slotIndex) * 2u + 1u
    );
}

static VkCommandPool g_rtCommandPool=VK_NULL_HANDLE;

static uint32_t g_sbtBaseAlignment = 0;

static VkBuffer g_sbtBuffer=VK_NULL_HANDLE;
// === 新增：Camera UBO 全局变量与结构体 ===
// 严格遵守 16 字节对齐的 UBO 内存布局
struct CameraUBO {
    float viewInverse[16];     // 64 bytes
    float projInverse[16];     // 64 bytes
    uint32_t vertexBufferAddrLo; // 4 bytes
    uint32_t vertexBufferAddrHi; // 4 bytes
    uint32_t frameCount;
    uint32_t padding;

    float cameraNear;
    float cameraFar;
    float cameraFov;
    float cameraParamsPadding;

    uint32_t historyReadIndex;
    uint32_t historyValid;
    uint32_t historyPadding0;
    uint32_t historyPadding1;

    uint32_t perfAoNearSamples;
    uint32_t perfAoMidSamples;
    uint32_t perfGiBounces;
    uint32_t perfSunShadowSamples;

    uint32_t perfLocalLightRisCandidates;
    uint32_t perfLocalLightAreaSamples;
    uint32_t perfWaterReflectionInterval;
    uint32_t perfGlossyFullDistance;

    float prevViewInverse[16];
    float prevProjInverse[16];

    float historyPrevJitterX;
    float historyPrevJitterY;
    float historyParamsPadding0;
    float historyParamsPadding1;

    float playerX;
    float playerY;
    float playerZ;
    float playerInWater;

    float playerVelocityX;
    float playerVelocityY;
    float playerVelocityZ;
    float playerHorizontalSpeed;

    // Eclipse-style persistent water wave field.  The position is the real
    // entity position, never the render camera position.
    float waterSurfaceY;
    float waterSimOriginX;
    float waterSimOriginZ;
    float waterSimTexelWorldSize;

    uint32_t waterSimReadLayer;
    uint32_t waterSimEnabled;
    uint32_t waterSimImpulsePending;
    uint32_t cameraInWater;

    float waterSimImpulseX;
    float waterSimImpulseZ;
    float waterSimImpulseStrength;
    float waterSimImpulseSurfaceY;

    // GLSL CameraProperties.weatherParams (offset 432).
    // x = overworld rain strength [0,1]
    // y = overworld thunder strength [0,1]
    // z = dimension code (1.0 = overworld, 2.0 = nether, 3.0 = end)
    // w = reserved
    float weatherParams[4];

    // Runtime volumetric-cloud controls. Keep this at the end so older
    // shaders that only read the original CameraUBO prefix remain compatible.
    float cloudParams[40];
    float skyParams[4];
};

static VkBuffer g_cameraUboBuffer = VK_NULL_HANDLE;
static VkDeviceMemory g_cameraUboMemory = VK_NULL_HANDLE;
static void* g_cameraUboMapped = nullptr;
static sl::float4x4 g_prevCameraViewToWorld{};
static sl::float4x4 g_prevCameraViewToClip{};

static bool g_hasPrevCameraMatrices = false;
static VkDeviceMemory g_sbtMemory=VK_NULL_HANDLE;
static VkDeviceSize g_sbtSize=0;
static VkDeviceAddress g_sbtDeviceAddress=0;
static uint32_t g_raygenOffset=0,g_restirFinalRaygenOffset=0,g_hitOffset=0,g_missOffset=0,g_handleSizeAligned=0;

static VkStridedDeviceAddressRegionKHR g_raygenRegion{};
static VkStridedDeviceAddressRegionKHR g_restirFinalRaygenRegion{};
static VkStridedDeviceAddressRegionKHR g_missRegion{};
static VkStridedDeviceAddressRegionKHR g_hitRegion{};
static VkStridedDeviceAddressRegionKHR g_callableRegion{};
static void updateRTOutputDescriptor();

// ============================================================================
// Eclipse-style local player-water simulation.  It is deliberately a normal
// field only: water hit geometry and all existing optical transport remain
// untouched.  A separate wavefront-area caustic map reads this field but does
// not feed back into it or displace the water geometry.
// ============================================================================
static constexpr uint32_t WATER_SIM_RESOLUTION = 512u;
static constexpr float WATER_SIM_WORLD_SIZE = 32.0f;
static constexpr float WATER_SIM_TEXEL_WORLD_SIZE =
    WATER_SIM_WORLD_SIZE / static_cast<float>(WATER_SIM_RESOLUTION);
static constexpr float WATER_SIM_UPDATE_SECONDS = 1.0f / 30.0f;

static VkImage g_waterSimImage = VK_NULL_HANDLE;
static VkDeviceMemory g_waterSimMemory = VK_NULL_HANDLE;
static VkImageView g_waterSimView = VK_NULL_HANDLE;
static VkSampler g_waterSimSampler = VK_NULL_HANDLE;
static VkDescriptorSetLayout g_waterSimDescriptorSetLayout = VK_NULL_HANDLE;
static VkDescriptorPool g_waterSimDescriptorPool = VK_NULL_HANDLE;
static VkDescriptorSet g_waterSimDescriptorSet = VK_NULL_HANDLE;
static VkPipelineLayout g_waterSimPipelineLayout = VK_NULL_HANDLE;
static VkPipeline g_waterSimPipeline = VK_NULL_HANDLE;
static VkImageLayout g_waterSimLayout = VK_IMAGE_LAYOUT_UNDEFINED;
static uint32_t g_waterSimReadLayer = 0u;
static bool g_waterSimNeedsClear = true;
static bool g_waterSimPlayerStateValid = false;
static bool g_waterSimPlayerWasInWater = false;
static bool g_waterSimPendingImpulse = false;
static float g_waterSimPendingImpulseX = 0.0f;
static float g_waterSimPendingImpulseZ = 0.0f;
static float g_waterSimPendingImpulseStrength = 0.0f;
static float g_waterSimLastSurfaceY = 0.0f;
static bool g_waterSimOriginValid = false;
static float g_waterSimOriginX = 0.0f;
static float g_waterSimOriginZ = 0.0f;
static std::chrono::steady_clock::time_point g_waterSimLastUpdate{};
static bool g_waterSimLastUpdateValid = false;
static float g_waterSimKeepAliveSeconds = 0.0f;
static bool g_waterSimLoggedAwaitingWater = false;
static bool g_waterSimLoggedFirstDispatch = false;
static bool g_waterSimLoggedSampleContract = false;

// The CameraUBO padding lane is otherwise unused.  Keep a monotonic optical
// wave clock here so water motion is independent of the Minecraft day/night
// phase and independent of render frame rate.
static const std::chrono::steady_clock::time_point g_waterWaveEpoch =
    std::chrono::steady_clock::now();

// Evan-style receiver caustics are evaluated as a compact local wavefront
// density map.  RG stores natural-wave density in R and natural+interaction
// density in G, so vertically separate water surfaces cannot inherit the
// player's simulated ripple caustics.
static constexpr uint32_t WATER_CAUSTIC_RESOLUTION = 256u;
// Caustics sample the same 30 Hz ripple state as the water simulation, so
// recording them on every render frame only repeats identical work.
static constexpr float WATER_CAUSTIC_UPDATE_SECONDS = 1.0f / 30.0f;
static constexpr uint32_t WATER_DETAIL_NORMAL_RESOLUTION = 256u;
static constexpr uint32_t WATER_DETAIL_NORMAL_MIP_LEVELS = 9u;
// The Tyndall cloud field is static GPU data. It is deliberately separate from
// Minecraft's reloadable atlas so resource-pack swaps cannot invalidate these
// descriptors.  RGBA carries independent base/detail channels; the second
// volume carries decorrelation/curl displacement.
static constexpr uint32_t CLOUD_SHAPE_NOISE_RESOLUTION = 128u;
static constexpr uint32_t CLOUD_CURL_NOISE_RESOLUTION = 64u;
static VkImage g_cloudShapeNoiseImage = VK_NULL_HANDLE;
static VkDeviceMemory g_cloudShapeNoiseMemory = VK_NULL_HANDLE;
static VkImageView g_cloudShapeNoiseView = VK_NULL_HANDLE;
static VkSampler g_cloudShapeNoiseSampler = VK_NULL_HANDLE;
static VkImage g_cloudCurlNoiseImage = VK_NULL_HANDLE;
static VkDeviceMemory g_cloudCurlNoiseMemory = VK_NULL_HANDLE;
static VkImageView g_cloudCurlNoiseView = VK_NULL_HANDLE;
static VkSampler g_cloudCurlNoiseSampler = VK_NULL_HANDLE;
// Primary-view clouds use a separate half-resolution target.  Keeping this
// out of the RT descriptor set prevents its HQ march from affecting reflected
// and GI sky rays.
static VkImage g_volumetricCloudImage = VK_NULL_HANDLE;
static VkDeviceMemory g_volumetricCloudMemory = VK_NULL_HANDLE;
static VkImageView g_volumetricCloudView = VK_NULL_HANDLE;
static VkImage g_volumetricCloudHistoryImage = VK_NULL_HANDLE;
static VkDeviceMemory g_volumetricCloudHistoryMemory = VK_NULL_HANDLE;
static VkImageView g_volumetricCloudHistoryView = VK_NULL_HANDLE;
static VkImage g_volumetricCloudResolvedImage = VK_NULL_HANDLE;
static VkDeviceMemory g_volumetricCloudResolvedMemory = VK_NULL_HANDLE;
static VkImageView g_volumetricCloudResolvedView = VK_NULL_HANDLE;
// The front-most participating cloud distance rejects unrelated history after
// reprojection.  R32 avoids storage-image support assumptions for R16.
static VkImage g_volumetricCloudDepthImage = VK_NULL_HANDLE;
static VkDeviceMemory g_volumetricCloudDepthMemory = VK_NULL_HANDLE;
static VkImageView g_volumetricCloudDepthView = VK_NULL_HANDLE;
static VkImage g_volumetricCloudHistoryDepthImage = VK_NULL_HANDLE;
static VkDeviceMemory g_volumetricCloudHistoryDepthMemory = VK_NULL_HANDLE;
static VkImageView g_volumetricCloudHistoryDepthView = VK_NULL_HANDLE;
static VkSampler g_volumetricCloudSampler = VK_NULL_HANDLE;
static VkImageLayout g_volumetricCloudLayout = VK_IMAGE_LAYOUT_UNDEFINED;
static VkImageLayout g_volumetricCloudHistoryLayout = VK_IMAGE_LAYOUT_UNDEFINED;
static VkImageLayout g_volumetricCloudResolvedLayout = VK_IMAGE_LAYOUT_UNDEFINED;
static VkImageLayout g_volumetricCloudDepthLayout = VK_IMAGE_LAYOUT_UNDEFINED;
static VkImageLayout g_volumetricCloudHistoryDepthLayout = VK_IMAGE_LAYOUT_UNDEFINED;
static std::atomic<bool> g_volumetricCloudHistoryNeedsClear{true};
static uint32_t g_volumetricCloudWidth = 0u;
static uint32_t g_volumetricCloudHeight = 0u;
static VkDescriptorSetLayout g_volumetricCloudRenderSetLayout = VK_NULL_HANDLE;
static VkDescriptorSetLayout g_volumetricCloudResolveSetLayout = VK_NULL_HANDLE;
static VkDescriptorSetLayout g_volumetricCloudCompositeSetLayout = VK_NULL_HANDLE;
static VkDescriptorPool g_volumetricCloudDescriptorPool = VK_NULL_HANDLE;
static VkDescriptorSet g_volumetricCloudRenderSet = VK_NULL_HANDLE;
static VkDescriptorSet g_volumetricCloudResolveSet = VK_NULL_HANDLE;
static VkDescriptorSet g_volumetricCloudCompositeSet = VK_NULL_HANDLE;
static VkPipelineLayout g_volumetricCloudRenderPipelineLayout = VK_NULL_HANDLE;
static VkPipelineLayout g_volumetricCloudResolvePipelineLayout = VK_NULL_HANDLE;
static VkPipelineLayout g_volumetricCloudCompositePipelineLayout = VK_NULL_HANDLE;
static VkPipeline g_volumetricCloudRenderPipeline = VK_NULL_HANDLE;
static VkPipeline g_volumetricCloudResolvePipeline = VK_NULL_HANDLE;
static VkPipeline g_volumetricCloudCompositePipeline = VK_NULL_HANDLE;
static VkImage g_waterDetailNormalImage = VK_NULL_HANDLE;
static VkDeviceMemory g_waterDetailNormalMemory = VK_NULL_HANDLE;
static VkImageView g_waterDetailNormalView = VK_NULL_HANDLE;
static VkSampler g_waterDetailNormalSampler = VK_NULL_HANDLE;
static VkImage g_waterCausticImage = VK_NULL_HANDLE;
static VkDeviceMemory g_waterCausticMemory = VK_NULL_HANDLE;
static VkImageView g_waterCausticView = VK_NULL_HANDLE;
static VkSampler g_waterCausticSampler = VK_NULL_HANDLE;
static VkDescriptorSetLayout g_waterCausticDescriptorSetLayout = VK_NULL_HANDLE;
static VkDescriptorPool g_waterCausticDescriptorPool = VK_NULL_HANDLE;
static VkDescriptorSet g_waterCausticDescriptorSet = VK_NULL_HANDLE;
static VkPipelineLayout g_waterCausticPipelineLayout = VK_NULL_HANDLE;
static VkPipeline g_waterCausticPipeline = VK_NULL_HANDLE;
static VkImageLayout g_waterCausticLayout = VK_IMAGE_LAYOUT_UNDEFINED;
static std::chrono::steady_clock::time_point g_waterCausticLastUpdate{};
static bool g_waterCausticLastUpdateValid = false;

struct WaterSimPushConstants
{
    uint32_t sourceLayer;
    uint32_t destinationLayer;
    uint32_t hasImpulse;
    uint32_t playerInWater;
    float deltaSeconds;
    float impulseX;
    float impulseZ;
    float impulseStrength;
    int32_t scrollX;
    int32_t scrollZ;
    uint32_t padding0;
    uint32_t padding1;
};

static void createWaterSimulationResources();
static void createWaterSimulationPipeline();
static void recordWaterSimulation(VkCommandBuffer cmd);
static void destroyWaterSimulationResources();
static void createWaterDetailNormalResources();
static void destroyWaterDetailNormalResources();
static void createCloudNoiseResources();
static void destroyCloudNoiseResources();
static void createVolumetricCloudPipeline(uint32_t width, uint32_t height);
static void recordVolumetricClouds(VkCommandBuffer cmd);
static void destroyVolumetricCloudResources();
static void createWaterCausticResources();
static void createWaterCausticPipeline();
static void recordWaterCaustics(VkCommandBuffer cmd);
static void destroyWaterCausticResources();
static float DLSSHaltonValue(
    uint32_t index,
    uint32_t base
);

static void GetDLSSFrameJitter(
    uint32_t frameIndex,
    float& outX,
    float& outY
);

static VkBuffer g_testVertexBuffer=VK_NULL_HANDLE;
static VkDeviceMemory g_testVertexMemory=VK_NULL_HANDLE;
static VkDeviceAddress g_testVertexDeviceAddress=0;
static VkBuffer g_blasBuffer=VK_NULL_HANDLE;
static VkDeviceMemory g_blasMemory=VK_NULL_HANDLE;
static VkAccelerationStructureKHR g_blas=VK_NULL_HANDLE;
static VkDeviceAddress g_blasDeviceAddress=0;
static VkBuffer g_blasScratchBuffer=VK_NULL_HANDLE;
// =====================================================
// P2-1 Section BLAS
//
// 每个 Minecraft Section 独立 BLAS
// TLAS 只保存 instance
// =====================================================

struct RTSectionAS
{
    uint64_t sectionKey = 0;

    VkBuffer vertexBuffer = VK_NULL_HANDLE;
    VkDeviceMemory vertexMemory = VK_NULL_HANDLE;
    VkDeviceAddress vertexAddress = 0;

    VkBuffer blasBuffer = VK_NULL_HANDLE;
    VkDeviceMemory blasMemory = VK_NULL_HANDLE;
    VkAccelerationStructureKHR blas = VK_NULL_HANDLE;

    VkDeviceAddress blasAddress = 0;

    uint32_t triangleCount = 0;
    VkDeviceSize vertexBytes = 0;
    VkDeviceSize buildScratchSize = 0;

    // OMM resources are retained for exactly the lifetime of this BLAS.
    VkBuffer ommDataBuffer = VK_NULL_HANDLE;
    VkDeviceMemory ommDataMemory = VK_NULL_HANDLE;
    VkDeviceAddress ommDataAddress = 0;
    VkBuffer ommTriangleBuffer = VK_NULL_HANDLE;
    VkDeviceMemory ommTriangleMemory = VK_NULL_HANDLE;
    VkDeviceAddress ommTriangleAddress = 0;
    VkBuffer ommStorageBuffer = VK_NULL_HANDLE;
    VkDeviceMemory ommStorageMemory = VK_NULL_HANDLE;
    VkBuffer ommScratchBuffer = VK_NULL_HANDLE;
    VkDeviceMemory ommScratchMemory = VK_NULL_HANDLE;
    VkDeviceAddress ommScratchAddress = 0;
    VkMicromapEXT opacityMicromap = VK_NULL_HANDLE;
    VkMicromapUsageEXT ommUsage{};
    bool hasOpacityMicromap = false;
};

struct OmmCutoutInput
{
    std::vector<uint8_t> data;
    uint32_t triangleCount = 0;
    uint32_t subdivisionLevel = 0;
    uint32_t knownMicroTriangleCount = 0;
};

static std::unordered_map<uint64_t, OmmCutoutInput> g_cutoutOmmInputs;
static bool g_ommNativeEnabled = false;
static uint32_t g_ommMax4StateSubdivisionLevel = 0;
static bool g_serExtAdvertised = false;
static bool g_serFeatureSupported = false;
static bool g_serHardwareReordering = false;
static std::atomic_bool g_serEnabled{true};


static std::unordered_map<uint64_t, RTSectionAS>
    g_sectionBLAS;


static VkBuffer g_tlasInstancesBuffer =
    VK_NULL_HANDLE;

static VkDeviceMemory g_tlasInstancesMemory =
    VK_NULL_HANDLE;

static VkDeviceAddress g_tlasInstancesAddress = 0;

struct GPUSectionVertexAddress
{
    uint32_t addressLo = 0;
    uint32_t addressHi = 0;
};

static_assert(
    sizeof(GPUSectionVertexAddress) == 8,
    "GPUSectionVertexAddress must remain exactly 8 bytes"
);

static VkBuffer g_sectionVertexAddressBuffer =
    VK_NULL_HANDLE;

static VkDeviceMemory g_sectionVertexAddressMemory =
    VK_NULL_HANDLE;

static size_t g_sectionVertexAddressCapacity = 0;
static VkDeviceMemory g_blasScratchMemory=VK_NULL_HANDLE;
static VkDeviceAddress g_blasScratchAddress=0;

static VkBuffer g_tlasBuffer=VK_NULL_HANDLE;
static VkDeviceMemory g_tlasMemory=VK_NULL_HANDLE;
static VkAccelerationStructureKHR g_tlas=VK_NULL_HANDLE;
static VkDeviceAddress g_tlasDeviceAddress=0;
static VkBuffer g_tlasInstanceBuffer=VK_NULL_HANDLE;
static VkDeviceMemory g_tlasInstanceMemory=VK_NULL_HANDLE;
static VkDeviceAddress g_tlasInstanceDeviceAddress=0;
static VkBuffer g_tlasScratchBuffer=VK_NULL_HANDLE;
static VkDeviceMemory g_tlasScratchMemory=VK_NULL_HANDLE;
static VkDeviceAddress g_tlasScratchAddress=0;
static VkDeviceSize g_tlasScratchCapacity=0;
static size_t g_tlasInstanceCount=0;

// A Section rebuild is submitted ahead of the frame's RT command buffer on
// the same graphics queue.  Its staging and BLAS scratch allocations must
// remain alive until that queue reaches the submission, but the CPU does not
// need to wait for it in the submitting frame.  They are released at the
// beginning of the next rebuild, after the retirement fence is signaled.
struct DeferredSectionBuildResources
{
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    VkBuffer blasScratchBuffer = VK_NULL_HANDLE;
    VkDeviceMemory blasScratchMemory = VK_NULL_HANDLE;
    VkBuffer vertexStagingBuffer = VK_NULL_HANDLE;
    VkDeviceMemory vertexStagingMemory = VK_NULL_HANDLE;
    struct OmmBuildInputs
    {
        VkBuffer dataBuffer = VK_NULL_HANDLE;
        VkDeviceMemory dataMemory = VK_NULL_HANDLE;
        VkBuffer triangleBuffer = VK_NULL_HANDLE;
        VkDeviceMemory triangleMemory = VK_NULL_HANDLE;
        VkBuffer scratchBuffer = VK_NULL_HANDLE;
        VkDeviceMemory scratchMemory = VK_NULL_HANDLE;
    };
    std::vector<OmmBuildInputs> ommBuildInputs;
};


static DeferredSectionBuildResources
    g_deferredSectionBuild;

// Empty submissions queued after presentation provide a completion point for
// all frame work that may still reference the current TLAS/BLAS set.
static std::mutex g_frameCompletionFenceMutex;
static std::vector<VkFence> g_frameCompletionFences;

static void destroyFrameCompletionFences()
{
    std::lock_guard<std::mutex> lock(g_frameCompletionFenceMutex);
    for(VkFence fence : g_frameCompletionFences)
    {
        if(fence != VK_NULL_HANDLE)
            vkDestroyFence(g_device, fence, nullptr);
    }
    g_frameCompletionFences.clear();
}

static bool latestFrameCompletionFenceSignaled()
{
    std::lock_guard<std::mutex> lock(g_frameCompletionFenceMutex);
    if(g_frameCompletionFences.empty())
        return false;

    const VkResult status =
        vkGetFenceStatus(g_device, g_frameCompletionFences.back());
    if(status == VK_NOT_READY)
        return false;
    if(status != VK_SUCCESS)
        return false;

    for(VkFence fence : g_frameCompletionFences)
        vkDestroyFence(g_device, fence, nullptr);
    g_frameCompletionFences.clear();
    return true;
}

static void releaseDeferredSectionBuildResources()
{
    if(
        g_deferredSectionBuild.commandBuffer != VK_NULL_HANDLE &&
        g_rtCommandPool != VK_NULL_HANDLE
    )
    {
        vkFreeCommandBuffers(
            g_device,
            g_rtCommandPool,
            1,
            &g_deferredSectionBuild.commandBuffer
        );
    }


    if(g_deferredSectionBuild.blasScratchBuffer != VK_NULL_HANDLE)
    {
        vkDestroyBuffer(
            g_device,
            g_deferredSectionBuild.blasScratchBuffer,
            nullptr
        );
    }


    if(g_deferredSectionBuild.blasScratchMemory != VK_NULL_HANDLE)
    {
        vkFreeMemory(
            g_device,
            g_deferredSectionBuild.blasScratchMemory,
            nullptr
        );
    }


    if(g_deferredSectionBuild.vertexStagingBuffer != VK_NULL_HANDLE)
    {
        vkDestroyBuffer(
            g_device,
            g_deferredSectionBuild.vertexStagingBuffer,
            nullptr
        );
    }


    if(g_deferredSectionBuild.vertexStagingMemory != VK_NULL_HANDLE)
    {
        vkFreeMemory(
            g_device,
            g_deferredSectionBuild.vertexStagingMemory,
            nullptr
        );
    }

    // After the queue-idle ownership barrier the micromap build inputs are
    // dead.  Keep only VkMicromapEXT and its storage backing with the BLAS.
    for(const auto& input : g_deferredSectionBuild.ommBuildInputs)
    {
        if(input.scratchBuffer != VK_NULL_HANDLE)
            vkDestroyBuffer(g_device, input.scratchBuffer, nullptr);
        if(input.scratchMemory != VK_NULL_HANDLE)
            vkFreeMemory(g_device, input.scratchMemory, nullptr);
        if(input.triangleBuffer != VK_NULL_HANDLE)
            vkDestroyBuffer(g_device, input.triangleBuffer, nullptr);
        if(input.triangleMemory != VK_NULL_HANDLE)
            vkFreeMemory(g_device, input.triangleMemory, nullptr);
        if(input.dataBuffer != VK_NULL_HANDLE)
            vkDestroyBuffer(g_device, input.dataBuffer, nullptr);
        if(input.dataMemory != VK_NULL_HANDLE)
            vkFreeMemory(g_device, input.dataMemory, nullptr);
    }


    g_deferredSectionBuild = {};
}

static VkDescriptorPool g_rayTracingDescriptorPool=VK_NULL_HANDLE;
static VkDescriptorSet g_rayTracingDescriptorSet=VK_NULL_HANDLE;
static VkDescriptorSetLayout g_rayTracingDescriptorSetLayout=VK_NULL_HANDLE;
static VkPipelineLayout g_rayTracingPipelineLayout=VK_NULL_HANDLE;
static VkPipeline g_rayTracingPipeline=VK_NULL_HANDLE;

// ReSTIR DI same-frame spatial reuse runs as a compute pass between the
// primary RT pass and a lightweight final-visibility RayGen pass.
static VkPipelineLayout g_restirDISpatialPipelineLayout = VK_NULL_HANDLE;
static VkPipeline g_restirDISpatialPipeline = VK_NULL_HANDLE;

// ReSTIR DI Block B2: current-frame post-visibility reconstruction.  The
// final visibility RayGen stores raw local-DI into the already-allocated
// Binding 41 reservoir lanes; this compute pass reuses those samples and the
// existing 26/27 temporal history before compositing into RT color.
static VkPipelineLayout g_restirDIReconstructPipelineLayout = VK_NULL_HANDLE;
static VkPipeline g_restirDIReconstructPipeline = VK_NULL_HANDLE;
// =====================================================
// RR-6.5 SDR display post pass
// RR output stays linear HDR until this compute pass.
// =====================================================

static VkDescriptorPool
    g_sdrPostDescriptorPool =
        VK_NULL_HANDLE;

static VkDescriptorSet
    g_sdrPostDescriptorSet =
        VK_NULL_HANDLE;

static VkDescriptorSetLayout
    g_sdrPostDescriptorSetLayout =
        VK_NULL_HANDLE;

static VkPipelineLayout
    g_sdrPostPipelineLayout =
        VK_NULL_HANDLE;

static VkPipeline
    g_sdrPostPipeline =
        VK_NULL_HANDLE;

static VkImage g_rtOutputImage=VK_NULL_HANDLE;
static VkImage g_dlssOutputImage = VK_NULL_HANDLE;
static VkImage g_dlssDepthImage;
static VkDeviceMemory g_dlssDepthMemory;
static VkImageView g_dlssDepthView;
static VkDeviceMemory g_dlssOutputMemory = VK_NULL_HANDLE;
static VkImageView g_dlssOutputView = VK_NULL_HANDLE;
static sl::ViewportHandle g_dlssViewport(0);

static VkImage g_motionImage = VK_NULL_HANDLE;
static VkDeviceMemory g_motionMemory = VK_NULL_HANDLE;
static VkImageView g_motionView = VK_NULL_HANDLE;

static uint32_t g_motionWidth = 0;
static uint32_t g_motionHeight = 0;


// =====================================================
// DLSS Ray Reconstruction GBuffer
//
// Binding 13 = Diffuse Albedo
// Binding 14 = Specular Albedo
// Binding 15 = Normal + Roughness
// Binding 16 = Specular Hit Distance
// =====================================================

static VkImage g_rrDiffuseAlbedoImage = VK_NULL_HANDLE;
static VkDeviceMemory g_rrDiffuseAlbedoMemory = VK_NULL_HANDLE;
static VkImageView g_rrDiffuseAlbedoView = VK_NULL_HANDLE;
static VkImageLayout g_rrDiffuseAlbedoLayout =
    VK_IMAGE_LAYOUT_UNDEFINED;


static VkImage g_rrSpecularAlbedoImage = VK_NULL_HANDLE;
static VkDeviceMemory g_rrSpecularAlbedoMemory = VK_NULL_HANDLE;
static VkImageView g_rrSpecularAlbedoView = VK_NULL_HANDLE;
static VkImageLayout g_rrSpecularAlbedoLayout =
    VK_IMAGE_LAYOUT_UNDEFINED;


static VkImage g_rrNormalRoughnessImage = VK_NULL_HANDLE;
static VkDeviceMemory g_rrNormalRoughnessMemory = VK_NULL_HANDLE;
static VkImageView g_rrNormalRoughnessView = VK_NULL_HANDLE;
static VkImageLayout g_rrNormalRoughnessLayout =
    VK_IMAGE_LAYOUT_UNDEFINED;


static VkImage g_rrSpecularHitDistanceImage = VK_NULL_HANDLE;
static VkDeviceMemory g_rrSpecularHitDistanceMemory = VK_NULL_HANDLE;
static VkImageView g_rrSpecularHitDistanceView = VK_NULL_HANDLE;
static VkImageLayout g_rrSpecularHitDistanceLayout =
    VK_IMAGE_LAYOUT_UNDEFINED;


static uint32_t g_rrGBufferWidth = 0;
static uint32_t g_rrGBufferHeight = 0;


static uint32_t g_dlssOutputWidth = 0;
static uint32_t g_dlssOutputHeight = 0;
static uint32_t g_dlssDepthWidth = 0;
static uint32_t g_dlssDepthHeight = 0;
static VkDeviceMemory g_rtOutputMemory=VK_NULL_HANDLE;
static VkImageView g_rtOutputView=VK_NULL_HANDLE;
// Perf-1.2: custom temporal history resources removed.
static uint32_t g_rtOutputWidth=0,g_rtOutputHeight=0;
static VkImageLayout g_rtOutputLayout=VK_IMAGE_LAYOUT_UNDEFINED;
static VkImageLayout g_dlssOutputLayout =
    VK_IMAGE_LAYOUT_UNDEFINED;

static VkImageLayout g_dlssDepthLayout =
    VK_IMAGE_LAYOUT_UNDEFINED;

static VkImageLayout g_motionLayout =
    VK_IMAGE_LAYOUT_UNDEFINED;


// =====================================================
// P1-1 - Minecraft Raster Far-Base snapshot
// Captured before RT/RR overwrites Minecraft mainTarget.
// No distance split / Cheap-PBR / compositing in P1-1.
// =====================================================
static VkImage g_farRasterSnapshotImage = VK_NULL_HANDLE;
static VkDeviceMemory g_farRasterSnapshotMemory = VK_NULL_HANDLE;
static VkImageView g_farRasterSnapshotView = VK_NULL_HANDLE;
static VkImageLayout g_farRasterSnapshotLayout =
    VK_IMAGE_LAYOUT_UNDEFINED;
static uint32_t g_farRasterSnapshotWidth = 0;
static uint32_t g_farRasterSnapshotHeight = 0;
static bool g_farRasterSnapshotCaptured = false;


static bool g_asBuilt=false;
static HMODULE g_slModule=NULL;
// =====================================================
// FG-4.1
// HUD-less / Final snapshots + binary UI Alpha
// =====================================================

static VkFormat
    g_fgBackbufferFormat =
        VK_FORMAT_UNDEFINED;

static VkFormat
    g_fgSnapshotFormat =
        VK_FORMAT_UNDEFINED;


static VkImage
    g_fgHudlessImage =
        VK_NULL_HANDLE;

static VkDeviceMemory
    g_fgHudlessMemory =
        VK_NULL_HANDLE;

static VkImageView
    g_fgHudlessView =
        VK_NULL_HANDLE;

static VkImageLayout
    g_fgHudlessLayout =
        VK_IMAGE_LAYOUT_UNDEFINED;


static VkImage
    g_fgFinalImage =
        VK_NULL_HANDLE;

static VkDeviceMemory
    g_fgFinalMemory =
        VK_NULL_HANDLE;

static VkImageView
    g_fgFinalView =
        VK_NULL_HANDLE;

static VkImageLayout
    g_fgFinalLayout =
        VK_IMAGE_LAYOUT_UNDEFINED;


static VkImage
    g_fgUIAlphaImage =
        VK_NULL_HANDLE;

static VkDeviceMemory
    g_fgUIAlphaMemory =
        VK_NULL_HANDLE;

static VkImageView
    g_fgUIAlphaView =
        VK_NULL_HANDLE;

static VkImageLayout
    g_fgUIAlphaLayout =
        VK_IMAGE_LAYOUT_UNDEFINED;


static uint32_t
    g_fgUIWidth =
        0;

static uint32_t
    g_fgUIHeight =
        0;


static uint32_t
    g_fgHudlessFrameIndex =
        UINT32_MAX;

static uint32_t
    g_fgUIAlphaFrameIndex =
        UINT32_MAX;


static VkSampler
    g_fgUISnapshotSampler =
        VK_NULL_HANDLE;


static VkDescriptorPool
    g_fgUIAlphaDescriptorPool =
        VK_NULL_HANDLE;

static VkDescriptorSet
    g_fgUIAlphaDescriptorSet =
        VK_NULL_HANDLE;

static VkDescriptorSetLayout
    g_fgUIAlphaDescriptorSetLayout =
        VK_NULL_HANDLE;

static VkPipelineLayout
    g_fgUIAlphaPipelineLayout =
        VK_NULL_HANDLE;

static VkPipeline
    g_fgUIAlphaPipeline =
        VK_NULL_HANDLE;
// =====================================================
// FG-3
// Vulkan Manual Hooking proxy state
// =====================================================

static bool
    g_slDeviceConfigured = false;


static PFN_vkGetInstanceProcAddr
    g_slVkGetInstanceProcAddrProxy = nullptr;

static PFN_vkGetDeviceProcAddr
    g_slVkGetDeviceProcAddrProxy = nullptr;


static PFN_vkCreateWin32SurfaceKHR
    g_slVkCreateWin32SurfaceKHRProxy = nullptr;

static PFN_vkDestroySurfaceKHR
    g_slVkDestroySurfaceKHRProxy = nullptr;


static PFN_vkCreateSwapchainKHR
    g_slVkCreateSwapchainKHRProxy = nullptr;

static PFN_vkDestroySwapchainKHR
    g_slVkDestroySwapchainKHRProxy = nullptr;

static PFN_vkGetSwapchainImagesKHR
    g_slVkGetSwapchainImagesKHRProxy = nullptr;

static PFN_vkAcquireNextImageKHR
    g_slVkAcquireNextImageKHRProxy = nullptr;

static PFN_vkQueuePresentKHR
    g_slVkQueuePresentKHRProxy = nullptr;

static PFN_vkDeviceWaitIdle
    g_slVkDeviceWaitIdleProxy = nullptr;


static void initStreamlineInternal();


static bool ResolveSLVulkanProxies()
{
    if(
        !g_slModule ||
        g_instance == VK_NULL_HANDLE ||
        g_device == VK_NULL_HANDLE
    )
    {
        return false;
    }


    if(!g_slVkGetInstanceProcAddrProxy)
    {
        g_slVkGetInstanceProcAddrProxy =
            reinterpret_cast<PFN_vkGetInstanceProcAddr>(
                GetProcAddress(
                    g_slModule,
                    "vkGetInstanceProcAddr"
                )
            );
    }


    if(!g_slVkGetDeviceProcAddrProxy)
    {
        g_slVkGetDeviceProcAddrProxy =
            reinterpret_cast<PFN_vkGetDeviceProcAddr>(
                GetProcAddress(
                    g_slModule,
                    "vkGetDeviceProcAddr"
                )
            );
    }


    if(
        !g_slVkGetInstanceProcAddrProxy ||
        !g_slVkGetDeviceProcAddrProxy
    )
    {
        return false;
    }


    if(!g_slVkCreateWin32SurfaceKHRProxy)
    {
        g_slVkCreateWin32SurfaceKHRProxy =
            reinterpret_cast<PFN_vkCreateWin32SurfaceKHR>(
                g_slVkGetInstanceProcAddrProxy(
                    g_instance,
                    "vkCreateWin32SurfaceKHR"
                )
            );
    }


    if(!g_slVkDestroySurfaceKHRProxy)
    {
        g_slVkDestroySurfaceKHRProxy =
            reinterpret_cast<PFN_vkDestroySurfaceKHR>(
                g_slVkGetInstanceProcAddrProxy(
                    g_instance,
                    "vkDestroySurfaceKHR"
                )
            );
    }


    if(!g_slVkCreateSwapchainKHRProxy)
    {
        g_slVkCreateSwapchainKHRProxy =
            reinterpret_cast<PFN_vkCreateSwapchainKHR>(
                g_slVkGetDeviceProcAddrProxy(
                    g_device,
                    "vkCreateSwapchainKHR"
                )
            );
    }


    if(!g_slVkDestroySwapchainKHRProxy)
    {
        g_slVkDestroySwapchainKHRProxy =
            reinterpret_cast<PFN_vkDestroySwapchainKHR>(
                g_slVkGetDeviceProcAddrProxy(
                    g_device,
                    "vkDestroySwapchainKHR"
                )
            );
    }


    if(!g_slVkGetSwapchainImagesKHRProxy)
    {
        g_slVkGetSwapchainImagesKHRProxy =
            reinterpret_cast<PFN_vkGetSwapchainImagesKHR>(
                g_slVkGetDeviceProcAddrProxy(
                    g_device,
                    "vkGetSwapchainImagesKHR"
                )
            );
    }


    if(!g_slVkAcquireNextImageKHRProxy)
    {
        g_slVkAcquireNextImageKHRProxy =
            reinterpret_cast<PFN_vkAcquireNextImageKHR>(
                g_slVkGetDeviceProcAddrProxy(
                    g_device,
                    "vkAcquireNextImageKHR"
                )
            );
    }


    if(!g_slVkQueuePresentKHRProxy)
    {
        g_slVkQueuePresentKHRProxy =
            reinterpret_cast<PFN_vkQueuePresentKHR>(
                g_slVkGetDeviceProcAddrProxy(
                    g_device,
                    "vkQueuePresentKHR"
                )
            );
    }


    if(!g_slVkDeviceWaitIdleProxy)
    {
        g_slVkDeviceWaitIdleProxy =
            reinterpret_cast<PFN_vkDeviceWaitIdle>(
                g_slVkGetDeviceProcAddrProxy(
                    g_device,
                    "vkDeviceWaitIdle"
                )
            );
    }


    const bool ready =
        g_slVkCreateWin32SurfaceKHRProxy &&
        g_slVkDestroySurfaceKHRProxy &&
        g_slVkCreateSwapchainKHRProxy &&
        g_slVkDestroySwapchainKHRProxy &&
        g_slVkGetSwapchainImagesKHRProxy &&
        g_slVkAcquireNextImageKHRProxy &&
        g_slVkQueuePresentKHRProxy &&
        g_slVkDeviceWaitIdleProxy;


    static bool printed =
        false;


    if(!printed)
    {
        fprintf(
            stderr,
            "[FG-3 PROXY]"
            " ready=%d"
            " surfaceCreate=%d"
            " surfaceDestroy=%d"
            " swapCreate=%d"
            " swapDestroy=%d"
            " getImages=%d"
            " acquire=%d"
            " present=%d"
            " deviceWaitIdle=%d\n",

            ready ? 1 : 0,

            g_slVkCreateWin32SurfaceKHRProxy ? 1 : 0,
            g_slVkDestroySurfaceKHRProxy ? 1 : 0,

            g_slVkCreateSwapchainKHRProxy ? 1 : 0,
            g_slVkDestroySwapchainKHRProxy ? 1 : 0,

            g_slVkGetSwapchainImagesKHRProxy ? 1 : 0,
            g_slVkAcquireNextImageKHRProxy ? 1 : 0,
            g_slVkQueuePresentKHRProxy ? 1 : 0,
            g_slVkDeviceWaitIdleProxy ? 1 : 0
        );

        fflush(stderr);

        printed = true;
    }


    return ready;
}


static VkResult FGDeviceWaitIdle(
    VkDevice device
)
{
    const bool useProxy =
        g_slDeviceConfigured &&
        ResolveSLVulkanProxies() &&
        g_slVkDeviceWaitIdleProxy;


    VkResult result;


    if(useProxy)
    {
        result =
            g_slVkDeviceWaitIdleProxy(
                device
            );
    }
    else
    {
        result =
            ::vkDeviceWaitIdle(
                device
            );
    }


    static bool printed =
        false;


    if(!printed)
    {
        fprintf(
            stderr,
            "[FG-3 DEVICE WAIT IDLE]"
            " proxy=%d"
            " result=%d\n",

            useProxy ? 1 : 0,
            static_cast<int>(result)
        );

        fflush(stderr);

        printed = true;
    }


    return result;
}

static bool g_dlssReady = false;
static bool g_dlssRRSupported = false;
static bool g_dlssRRReady = false;
static bool g_dlssGSupported = false;
static bool g_dlssGReady = false;
// Streamline feature loading and function lookup are device-lifetime work.
// Cache the result instead of performing the same dispatch lookup every frame.
static bool g_dlssGFeatureInitialized = false;
static sl::Result g_dlssGLoadResult = sl::Result::eErrorFeatureNotSupported;
static bool g_fgFrameSyncEnabled = true;
// =====================================================
// FG-2.4
// Vulkan requirements snapshot captured during
// Streamline early initialization.
//
// No Vulkan objects are modified here.
// =====================================================

static bool
    g_fgRequirementsReady = false;


static std::vector<std::string>
    g_fgInstanceExtensions;

static std::vector<std::string>
    g_fgDeviceExtensions;

static std::vector<std::string>
    g_fgFeatures12;

static std::vector<std::string>
    g_fgFeatures13;


static uint32_t
    g_fgGraphicsQueuesRequired = 0;

static uint32_t
    g_fgComputeQueuesRequired = 0;

static uint32_t
    g_fgOpticalFlowQueuesRequired = 0;
// =====================================================
// FG-2.11
// Streamline queue plan supplied by Java before
// initStreamlineInternal() calls slSetVulkanInfo().
// =====================================================

static bool
    g_slQueuePlanReady = false;


static uint32_t
    g_slGraphicsQueueFamily = UINT32_MAX;

static uint32_t
    g_slGraphicsQueueIndex = UINT32_MAX;


static uint32_t
    g_slComputeQueueFamily = UINT32_MAX;

static uint32_t
    g_slComputeQueueIndex = UINT32_MAX;


static uint32_t
    g_slOpticalFlowQueueFamily = UINT32_MAX;

static uint32_t
    g_slOpticalFlowQueueIndex = UINT32_MAX;


static bool
    g_slUseNativeOpticalFlow = false;


typedef sl::Result (*PFN_slDLSSGSetOptions)(
    const sl::ViewportHandle&,
    const sl::DLSSGOptions&
);

typedef sl::Result (*PFN_slDLSSGGetState)(
    const sl::ViewportHandle&,
    sl::DLSSGState&,
    const sl::DLSSGOptions*
);


static PFN_slDLSSGSetOptions
    g_slDLSSGSetOptions = nullptr;

static PFN_slDLSSGGetState
    g_slDLSSGGetState = nullptr;
static std::atomic<int> g_rrMode{1};
static std::atomic<int> g_upscalerMode{0};
static std::atomic<int> g_srMode{2};
static std::atomic<float>
    g_noDirectLightAmbient{0.10f};

// =====================================================
// POM parallax depth
// range: 0.00 - 0.20, default: 0.10
// =====================================================
static std::atomic<float>
    g_pomDepth{0.10f};
static std::atomic<bool> g_cloudEnabled{true};
static std::atomic<float> g_cloudBottom{1300.0f};
static std::atomic<float> g_cloudThickness{2100.0f};
static std::atomic<float> g_cloudCoverage{0.48f};
static std::atomic<float> g_cloudDensity{2.40f};
static std::atomic<float> g_cloudDetailStrength{0.14f};
static std::atomic<float> g_cloudWindSpeed{10.0f};
static std::atomic<float> g_cloudEvolution{0.80f};
static std::atomic<uint32_t> g_cloudQuality{1u};
static std::atomic<float> g_cloudSize{1.0f};
static std::atomic<float> g_cloudDistribution{0.50f};
static std::atomic<float> g_cloudBaseFeather{0.10f};
static std::atomic<float> g_cloudTopBrightness{1.15f};
static std::atomic<float> g_cloudBottomBrightness{0.75f};
static std::atomic<float> g_cloudSunlightStrength{1.0f};
static std::atomic<bool> g_highCloudEnabled{true};
static std::atomic<float> g_highCloudHeight{1600.0f};
static std::atomic<float> g_highCloudCoverage{0.55f};
static std::atomic<float> g_highCloudBrightness{1.0f};
static std::atomic<float> g_sunDiskLuminance{1.20f};
static std::atomic<float> g_sunHaloStrength{0.18f};
static std::atomic<float> g_sunAngularDiameterDegrees{0.53f};
// A stable anchor makes the curved cloud shell a world feature rather than a
// camera-relative bubble.  It is rebased only after a long travel or dimension change.
static float g_cloudWorldAnchorX = 0.0f;
static float g_cloudWorldAnchorZ = 0.0f;
static float g_cloudWorldAnchorDimension = 0.0f;
static bool g_cloudWorldAnchorValid = false;

static void populateCloudParams(float* params)
{
    params[0] = g_cloudBottom.load();
    params[1] = g_cloudThickness.load();
    params[2] = g_cloudCoverage.load();
    params[3] = g_cloudDensity.load();
    params[4] = g_cloudDetailStrength.load();
    params[5] = g_cloudWindSpeed.load();
    params[6] = g_cloudEvolution.load();
    params[7] = g_cloudEnabled.load() ? 1.0f : 0.0f;

    const uint32_t quality = g_cloudQuality.load();
    // Full-resolution cloud rendering uses the reference path's compact
    // 12-20 sample budget: 20 steps match the prior 80% x 32-step cost.
    params[8] = quality == 0u ? 16.0f : (quality == 2u ? 28.0f : 20.0f);
    params[9] = quality == 0u ? 3.0f : (quality == 2u ? 6.0f : 4.0f);
    params[10] = 0.008f;
    // Native-pixel cloud edges require a short history to avoid silhouette
    // smearing while the blue-noise march converges.
    params[11] = quality == 0u ? 0.38f : (quality == 2u ? 0.55f : 0.46f);
    params[12] = g_cloudWorldAnchorX;
    params[13] = g_cloudWorldAnchorZ;
    params[14] = g_cloudSize.load();
    params[15] = g_cloudDistribution.load();
    params[16] = g_cloudBaseFeather.load();
    params[20] = g_cloudTopBrightness.load();
    params[21] = g_cloudBottomBrightness.load();
    params[22] = g_cloudSunlightStrength.load();
    std::fill(params + 17, params + 20, 0.0f);
    params[23] = 0.0f;
    params[24] = g_highCloudEnabled.load() ? 1.0f : 0.0f;
    params[25] = g_highCloudHeight.load();
    params[26] = g_highCloudCoverage.load();
    params[27] = g_highCloudBrightness.load();
    std::fill(params + 28, params + 40, 0.0f);
}

static void populateSkyParams(float* params)
{
    params[0] = g_sunDiskLuminance.load();
    params[1] = g_sunHaloStrength.load();
    params[2] = g_sunAngularDiameterDegrees.load();
    params[3] = 0.0f;
}
static std::atomic<uint32_t> g_perfAoNearSamples{2u};
static std::atomic<uint32_t> g_perfAoMidSamples{1u};
static std::atomic<uint32_t> g_perfGiBounces{1u};
static std::atomic<uint32_t> g_perfSunShadowSamples{2u};
static std::atomic<uint32_t> g_perfLocalLightRisCandidates{2u};
static std::atomic<uint32_t> g_perfLocalLightAreaSamples{2u};
static std::atomic<uint32_t> g_perfWaterReflectionInterval{1u};
static std::atomic<uint32_t> g_perfGlossyFullDistance{64u};
static std::atomic<uint64_t> g_perfDynamicRtasInterval{2u};

static bool g_reflexSupported = false;
static bool g_pclSupported = false;
static bool g_reflexLowLatencyAvailable = false;
static int g_reflexMode = 1;

static std::mutex g_reflexMutex;


// =====================================================
// Unified Streamline frame state
//
// 每个应用帧只生成一个 FrameToken。
// Reflex / DLSS / PCL / Present 后续全部共用它。
// =====================================================

static sl::FrameToken*
    g_currentSLFrameToken = nullptr;

static uint32_t
    g_currentSLFrameIndex = 0;

static uint32_t
    g_nextSLFrameIndex = 0;

static bool
    g_currentSLFrameValid = false;
static uint32_t
    g_lastRenderSubmitStartFrame = UINT32_MAX;

static uint32_t
    g_lastRenderSubmitEndFrame = UINT32_MAX;
static uint32_t
    g_lastPresentStartFrame = UINT32_MAX;

static uint32_t
    g_lastPresentEndFrame = UINT32_MAX;


static bool g_slInitialized = false;

typedef sl::Result (*PFN_slInit)(const sl::Preferences&,uint64_t);
typedef sl::Result (*PFN_slGetFeatureFunction)(
    sl::Feature,
    const char*,
    void*&
);
typedef sl::Result (*PFN_slGetFeatureRequirements)(
    sl::Feature,
    sl::FeatureRequirements&
);


typedef sl::Result (*PFN_slShutdown)();
typedef sl::Result (*PFN_slSetVulkanInfo)(const sl::VulkanInfo&);
static PFN_slGetFeatureFunction g_slGetFeatureFunction=nullptr;
static PFN_slGetFeatureRequirements
    g_slGetFeatureRequirements = nullptr;
typedef sl::Result (*PFN_slIsFeatureSupported)(sl::Feature,const sl::AdapterInfo&);
typedef sl::Result (*PFN_slSetFeatureLoaded)(sl::Feature,bool);
typedef sl::Result (*PFN_slEvaluateFeature)(
    sl::Feature,
    const sl::FrameToken&,
    const sl::BaseStructure**,
    uint32_t,
    sl::CommandBuffer*
);
typedef sl::Result (*PFN_slGetNewFrameToken)(
    sl::FrameToken*& frameToken,
    const uint32_t* frameIndex
);
typedef sl::Result (*PFN_slSetTagForFrame)(
    const sl::FrameToken&,
    const sl::ViewportHandle&,
    const sl::ResourceTag*,
    uint32_t,
    sl::CommandBuffer*
);
typedef sl::Result (*PFN_slSetConstants)(
    const sl::Constants&,
    const sl::FrameToken&,
    const sl::ViewportHandle&
);
typedef sl::Result (*PFN_slReflexSetOptions)(
    const sl::ReflexOptions&
);

typedef sl::Result (*PFN_slReflexGetState)(
    sl::ReflexState&
);
typedef sl::Result (*PFN_slReflexSleep)(
    const sl::FrameToken&
);
typedef sl::Result (*PFN_slPCLSetMarker)(
    sl::PCLMarker,
    const sl::FrameToken&
);


static PFN_slInit g_slInit=nullptr;
static PFN_slShutdown g_slShutdown=nullptr;
static PFN_slSetVulkanInfo g_slSetVulkanInfo=nullptr;
static PFN_slIsFeatureSupported g_slIsFeatureSupported=nullptr;
static PFN_slSetFeatureLoaded g_slSetFeatureLoaded=nullptr;
static PFN_slReflexSetOptions
    g_slReflexSetOptions = nullptr;

static PFN_slReflexGetState
    g_slReflexGetState = nullptr;
static PFN_slReflexSleep
    g_slReflexSleep = nullptr;
static PFN_slPCLSetMarker
    g_slPCLSetMarker = nullptr;
typedef sl::Result (*PFN_slDLSSSetOptions)(
    const sl::ViewportHandle&,
    const sl::DLSSOptions&
);
static PFN_slDLSSSetOptions g_slDLSSSetOptions = nullptr;
typedef sl::Result (*PFN_slDLSSGetOptimalSettings)(
    const sl::DLSSOptions&,
    sl::DLSSOptimalSettings&
);

static PFN_slDLSSGetOptimalSettings
    g_slDLSSGetOptimalSettings = nullptr;


// =====================================================
// DLSS Ray Reconstruction / sl.dlss_d
// Streamline 2.12
// =====================================================

typedef sl::Result (*PFN_slDLSSDSetOptions)(
    const sl::ViewportHandle&,
    const sl::DLSSDOptions&
);

static PFN_slDLSSDSetOptions
    g_slDLSSDSetOptions = nullptr;


typedef sl::Result (*PFN_slDLSSDGetOptimalSettings)(
    const sl::DLSSDOptions&,
    sl::DLSSDOptimalSettings&
);

static PFN_slDLSSDGetOptimalSettings
    g_slDLSSDGetOptimalSettings = nullptr;


static PFN_slEvaluateFeature g_slEvaluateFeature=nullptr;
static sl::Constants g_slConstants{};
typedef sl::Result (*PFN_slSetConstants)(
    const sl::Constants&,
    const sl::FrameToken&,
    const sl::ViewportHandle&
);

static PFN_slSetConstants g_slSetConstants=nullptr;
static PFN_slGetNewFrameToken g_slGetNewFrameToken=nullptr;
static PFN_slSetTagForFrame
    g_slSetTagForFrame = nullptr;

VkImageView g_atlasImageView = VK_NULL_HANDLE;
VkSampler g_atlasSampler = VK_NULL_HANDLE;
bool g_atlasDirty = false;
// PBR upload starts before Minecraft publishes the replacement block-atlas
// view. Keep dynamic texture bindings blocked until that new view arrives.
static bool g_dynamicTextureBindingsNeedAtlasRefresh = false;
static bool g_dynamicTextureBindingsCanPublish = false;

static void StreamlineLogCallback(sl::LogType type,const char* msg){
    const char* s="UNKNOWN";
    if(type==sl::LogType::eInfo)s="INFO";else if(type==sl::LogType::eWarn)s="WARN";else if(type==sl::LogType::eError)s="ERROR";
    std::cerr
    << "[Streamline "
    << s
    << "] "
    << (msg ? msg : "")
    << std::endl;
}

extern "C" JNIEXPORT void JNICALL Java_com_example_vulkan_NativeVulkan_updateAtlas(
    JNIEnv* env, jclass clazz, jlong imageView, jlong sampler)
{
    const bool atlasChanged =
        g_atlasImageView != (VkImageView)imageView ||
        g_atlasSampler != (VkSampler)sampler;

    if (atlasChanged) {
        g_atlasImageView = (VkImageView)imageView;
        g_atlasSampler = (VkSampler)sampler;
        g_atlasDirty = true;

        std::stringstream ps;
        ps << "[ATLAS HIJACK] C++ Received Atlas: ImageView=0x" << std::hex << imageView
           << ", Sampler=0x" << sampler;
        RTLog(ps.str());
    }

    // A newly created Vulkan view can reuse the previous numeric handle. The
    // Java hook remains the authoritative atlas-generation boundary.
    if(g_dynamicTextureBindingsNeedAtlasRefresh)
    {
        g_atlasDirty = true;
        g_dynamicTextureBindingsCanPublish = true;
    }
}

static uint32_t findMemoryType(VkPhysicalDevice pd,uint32_t filter,VkMemoryPropertyFlags props){
    VkPhysicalDeviceMemoryProperties mp{};
    vkGetPhysicalDeviceMemoryProperties(pd,&mp);

    // std::cout<<"[MEMORY TYPES] count="<<mp.memoryTypeCount<<std::endl;

    for(uint32_t i=0;i<mp.memoryTypeCount;i++)
    {
        if((filter&(1<<i))&&(mp.memoryTypes[i].propertyFlags&props)==props)
        {
            // std::cout<<"[MEMORY SELECT] "<<i<<std::endl;
            return i;
        }
    }

    throw std::runtime_error("Failed to find suitable memory type");
}

static void check(VkResult r,const char* s){if(r!=VK_SUCCESS)throw std::runtime_error(std::string(s)+" VkResult="+std::to_string((int)r));}
static sl::float4x4 JOMLColumnMajorToSLRowMajor(
    const float m[16]
)
{
    sl::float4x4 out{};

    out[0] = sl::float4(
        m[0],
        m[1],
        m[2],
        m[3]
    );

    out[1] = sl::float4(
        m[4],
        m[5],
        m[6],
        m[7]
    );

    out[2] = sl::float4(
        m[8],
        m[9],
        m[10],
        m[11]
    );

    out[3] = sl::float4(
        m[12],
        m[13],
        m[14],
        m[15]
    );

    return out;
}

static void createRTCommandPool()
{
    VkCommandPoolCreateInfo ci{
        VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO
    };

    ci.queueFamilyIndex = g_queueFamily;

    ci.flags =
        VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;


    check(
        vkCreateCommandPool(
            g_device,
            &ci,
            nullptr,
            &g_rtCommandPool
        ),
        "Failed to create RT command pool"
    );


    // std::cout
    //     << "[RT] command pool created\n";
}


static void createBuffer(VkDevice d,VkPhysicalDevice pd,VkDeviceSize size,VkBufferUsageFlags usage,VkMemoryPropertyFlags props,VkBuffer& buffer,VkDeviceMemory& memory){
    VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};bi.size=size;bi.usage=usage;bi.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
    check(vkCreateBuffer(d,&bi,nullptr,&buffer),"Failed to create buffer");
    VkMemoryRequirements mr{};vkGetBufferMemoryRequirements(d,buffer,&mr);
    VkMemoryAllocateFlagsInfo fi{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO};
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};ai.allocationSize=mr.size;ai.memoryTypeIndex=findMemoryType(pd,mr.memoryTypeBits,props);
    if(usage&VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT){fi.flags=VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT;ai.pNext=&fi;}
    VkResult r=vkAllocateMemory(d,&ai,nullptr,&memory);
    if(r!=VK_SUCCESS){vkDestroyBuffer(d,buffer,nullptr);buffer=VK_NULL_HANDLE;check(r,"Failed to allocate buffer memory");}
    r=vkBindBufferMemory(d,buffer,memory,0);
    if(r!=VK_SUCCESS){vkFreeMemory(d,memory,nullptr);vkDestroyBuffer(d,buffer,nullptr);memory=VK_NULL_HANDLE;buffer=VK_NULL_HANDLE;check(r,"Failed to bind buffer memory");}
}
static uint64_t makeSectionKey(
    int x,
    int y,
    int z
)
{
    uint64_t ux =
        static_cast<uint64_t>(
            static_cast<uint32_t>(x)
        );

    uint64_t uy =
        static_cast<uint64_t>(
            static_cast<uint32_t>(y)
        );

    uint64_t uz =
        static_cast<uint64_t>(
            static_cast<uint32_t>(z)
        );


    return
        (ux << 42) |
        (uy << 21) |
        uz;
}

// === 新增：统一的物理显存泵入管线 ===
static void uploadTextureToVRAM(void* pixels, uint32_t width, uint32_t height, VkImage& image, VkDeviceMemory& memory, VkImageView& view) {
    VkDeviceSize imageSize = static_cast<VkDeviceSize>(width) * height * 4;

    // 1. 申请 CPU 可见的暂存缓冲区 (Staging Buffer)
    VkBuffer stagingBuffer;
    VkDeviceMemory stagingBufferMemory;
    createBuffer(
        g_device, g_physicalDevice, imageSize,
        VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
        stagingBuffer, stagingBufferMemory
    );

    // 2. 内存映射，将 JNI 传来的堆外像素泵入暂存缓冲区
    void* data = nullptr;
    vkMapMemory(g_device, stagingBufferMemory, 0, imageSize, 0, &data);
    std::memcpy(data, pixels, static_cast<size_t>(imageSize));
    vkUnmapMemory(g_device, stagingBufferMemory);

    // 3. 申请 GPU 专属显存的 VkImage (支持作为目标被拷贝，并支持着色器采样)
    VkImageCreateInfo imageInfo{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.extent.width = width;
    imageInfo.extent.height = height;
    imageInfo.extent.depth = 1;
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    imageInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    vkCreateImage(g_device, &imageInfo, nullptr, &image);

    // 绑定物理显存 (Device Local)
    VkMemoryRequirements memRequirements;
    vkGetImageMemoryRequirements(g_device, image, &memRequirements);
    VkMemoryAllocateInfo allocInfo{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    allocInfo.allocationSize = memRequirements.size;
    allocInfo.memoryTypeIndex = findMemoryType(g_physicalDevice, memRequirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    vkAllocateMemory(g_device, &allocInfo, nullptr, &memory);
    vkBindImageMemory(g_device, image, memory, 0);

    // 4. 一次性指令录制：执行内存拷贝与布局转换
    VkCommandBufferAllocateInfo allocInfoCmd{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    allocInfoCmd.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocInfoCmd.commandPool = g_rtCommandPool;
    allocInfoCmd.commandBufferCount = 1;
    VkCommandBuffer commandBuffer;
    vkAllocateCommandBuffers(g_device, &allocInfoCmd, &commandBuffer);

    VkCommandBufferBeginInfo beginInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(commandBuffer, &beginInfo);

    // 阶段 A: 将 Image 从 UNDEFINED 转换至 TRANSFER_DST
    VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = image;
    barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    barrier.subresourceRange.baseMipLevel = 0;
    barrier.subresourceRange.levelCount = 1;
    barrier.subresourceRange.baseArrayLayer = 0;
    barrier.subresourceRange.layerCount = 1;
    barrier.srcAccessMask = 0;
    barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &barrier);

    // 阶段 B: 执行物理拷贝
    VkBufferImageCopy region{};
    region.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    region.imageSubresource.mipLevel = 0;
    region.imageSubresource.baseArrayLayer = 0;
    region.imageSubresource.layerCount = 1;
    region.imageExtent = {width, height, 1};
    vkCmdCopyBufferToImage(commandBuffer, stagingBuffer, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);

    // 阶段 C: 将 Image 从 TRANSFER_DST 转换至 SHADER_READ_ONLY，供光追着色器采样
    barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    barrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR, 0, 0, nullptr, 0, nullptr, 1, &barrier);

    vkEndCommandBuffer(commandBuffer);

    // 提交队列并强行同步阻塞，直到 GPU 拷贝完毕
    VkSubmitInfo submitInfo{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &commandBuffer;
    vkQueueSubmit(g_queue, 1, &submitInfo, VK_NULL_HANDLE);
    vkQueueWaitIdle(g_queue);
    vkFreeCommandBuffers(g_device, g_rtCommandPool, 1, &commandBuffer);

    // 销毁暂存区内存，防止显存泄漏
    vkDestroyBuffer(g_device, stagingBuffer, nullptr);
    vkFreeMemory(g_device, stagingBufferMemory, nullptr);

    // 5. 绑定图像视图 (View)
    VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    viewInfo.image = image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.baseMipLevel = 0;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.baseArrayLayer = 0;
    viewInfo.subresourceRange.layerCount = 1;
    vkCreateImageView(g_device, &viewInfo, nullptr, &view);
}

static VkDeviceAddress getBufferDeviceAddress(VkDevice d,VkBuffer b){
    auto fn=reinterpret_cast<PFN_vkGetBufferDeviceAddress>(vkGetDeviceProcAddr(d,"vkGetBufferDeviceAddress"));
    if(!fn)throw std::runtime_error("vkGetBufferDeviceAddress unavailable");

    VkBufferDeviceAddressInfo i{VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO};
    i.buffer=b;

    VkDeviceAddress a=fn(d,&i);


    if(!a)throw std::runtime_error("Invalid buffer device address");

    return a;
}

static std::vector<uint8_t> loadSpv(const char* path)
{
    const std::string resolvedPath =
        ResolveRTXRuntimePath(
            path
        );


    std::ifstream f(
        resolvedPath,
        std::ios::binary |
        std::ios::ate
    );


    if(!f)
    {
        std::cerr
            << "[SPV] FAILED OPEN requested="
            << (path ? path : "<null>")
            << " resolved="
            << resolvedPath
            << "\n";


        throw std::runtime_error(
            std::string("Failed shader: ") +
            resolvedPath
        );
    }


    const std::streamsize size =
        f.tellg();


    if(size <= 0)
    {
        throw std::runtime_error(
            std::string("Invalid/empty shader: ") +
            resolvedPath
        );
    }


    std::cout
        << "[SPV] LOAD requested="
        << (path ? path : "<null>")
        << " resolved="
        << resolvedPath
        << " size="
        << size
        << "\n";


    f.seekg(
        0,
        std::ios::beg
    );


    std::vector<uint8_t> data(
        static_cast<size_t>(
            size
        )
    );


    if(
        !f.read(
            reinterpret_cast<char*>(
                data.data()
            ),
            size
        )
    )
    {
        throw std::runtime_error(
            std::string("Failed reading shader: ") +
            resolvedPath
        );
    }


    if(data.size() < sizeof(uint32_t))
    {
        throw std::runtime_error(
            std::string("Shader too small: ") +
            resolvedPath
        );
    }


    const uint32_t magic =
        *reinterpret_cast<const uint32_t*>(
            data.data()
        );


    if(magic != 0x07230203u)
    {
        throw std::runtime_error(
            std::string("Invalid SPIR-V magic: ") +
            resolvedPath
        );
    }


    return data;
}

static VkShaderModule createShaderModule(VkDevice d,const std::vector<uint8_t>& code){
    if(code.empty()||(code.size()%4)!=0)throw std::runtime_error("Invalid SPIR-V bytecode");
    VkShaderModuleCreateInfo ci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};ci.codeSize=code.size();ci.pCode=reinterpret_cast<const uint32_t*>(code.data());
    VkShaderModule m=VK_NULL_HANDLE;check(vkCreateShaderModule(d,&ci,nullptr,&m),"Failed to create shader module");return m;
}
static void createSDRPostPipeline()
{
    // =================================================
    // Descriptor:
    // binding 0 = RR output image, read/write in-place
    // =================================================

    VkDescriptorSetLayoutBinding binding{};

    binding.binding =
        0;

    binding.descriptorType =
        VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;

    binding.descriptorCount =
        1;

    binding.stageFlags =
        VK_SHADER_STAGE_COMPUTE_BIT;


    VkDescriptorSetLayoutCreateInfo layoutInfo{
        VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO
    };

    layoutInfo.bindingCount =
        1;

    layoutInfo.pBindings =
        &binding;


    check(
        vkCreateDescriptorSetLayout(
            g_device,
            &layoutInfo,
            nullptr,
            &g_sdrPostDescriptorSetLayout
        ),
        "create SDR post descriptor layout failed"
    );


    // =================================================
    // Descriptor pool + set
    // =================================================

    VkDescriptorPoolSize poolSize{};

    poolSize.type =
        VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;

    poolSize.descriptorCount =
        1;


    VkDescriptorPoolCreateInfo poolInfo{
        VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO
    };

    poolInfo.maxSets =
        1;

    poolInfo.poolSizeCount =
        1;

    poolInfo.pPoolSizes =
        &poolSize;


    check(
        vkCreateDescriptorPool(
            g_device,
            &poolInfo,
            nullptr,
            &g_sdrPostDescriptorPool
        ),
        "create SDR post descriptor pool failed"
    );


    VkDescriptorSetAllocateInfo setInfo{
        VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO
    };

    setInfo.descriptorPool =
        g_sdrPostDescriptorPool;

    setInfo.descriptorSetCount =
        1;

    setInfo.pSetLayouts =
        &g_sdrPostDescriptorSetLayout;


    check(
        vkAllocateDescriptorSets(
            g_device,
            &setInfo,
            &g_sdrPostDescriptorSet
        ),
        "allocate SDR post descriptor set failed"
    );


    // =================================================
    // Pipeline layout
    // =================================================

    VkPipelineLayoutCreateInfo pipelineLayoutInfo{
        VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO
    };

    pipelineLayoutInfo.setLayoutCount =
        1;

    pipelineLayoutInfo.pSetLayouts =
        &g_sdrPostDescriptorSetLayout;


    check(
        vkCreatePipelineLayout(
            g_device,
            &pipelineLayoutInfo,
            nullptr,
            &g_sdrPostPipelineLayout
        ),
        "create SDR post pipeline layout failed"
    );


    // =================================================
    // Compute shader
    // =================================================

    auto code =
        loadSpv(
            "shaders/rr_sdr_post.comp.spv"
        );


    VkShaderModule shaderModule =
        createShaderModule(
            g_device,
            code
        );


    VkPipelineShaderStageCreateInfo stage{
        VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO
    };

    stage.stage =
        VK_SHADER_STAGE_COMPUTE_BIT;

    stage.module =
        shaderModule;

    stage.pName =
        "main";


    VkComputePipelineCreateInfo pipelineInfo{
        VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO
    };

    pipelineInfo.stage =
        stage;

    pipelineInfo.layout =
        g_sdrPostPipelineLayout;


    VkResult result =
        vkCreateComputePipelines(
            g_device,
            VK_NULL_HANDLE,
            1,
            &pipelineInfo,
            nullptr,
            &g_sdrPostPipeline
        );


    vkDestroyShaderModule(
        g_device,
        shaderModule,
        nullptr
    );


    check(
        result,
        "create SDR post compute pipeline failed"
    );


    fprintf(
        stderr,
        "[RR-6.5.2] SDR post pipeline READY\n"
    );

    fflush(stderr);
}

static void updateSDRPostDescriptor()
{
    if(
        g_sdrPostDescriptorSet ==
            VK_NULL_HANDLE ||

        g_dlssOutputView ==
            VK_NULL_HANDLE
    )
    {
        return;
    }


    VkDescriptorImageInfo imageInfo{};

    imageInfo.imageView =
        g_dlssOutputView;

    imageInfo.imageLayout =
        VK_IMAGE_LAYOUT_GENERAL;


    VkWriteDescriptorSet write{
        VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET
    };

    write.dstSet =
        g_sdrPostDescriptorSet;

    write.dstBinding =
        0;

    write.descriptorCount =
        1;

    write.descriptorType =
        VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;

    write.pImageInfo =
        &imageInfo;


    vkUpdateDescriptorSets(
        g_device,
        1,
        &write,
        0,
        nullptr
    );
}
// =====================================================
// FG-4.1
// HUD-less / Final snapshot resource helpers
// =====================================================

static void destroyFGImage(
    VkImage& image,
    VkDeviceMemory& memory,
    VkImageView& view,
    VkImageLayout& layout
)
{
    if(view != VK_NULL_HANDLE)
    {
        vkDestroyImageView(
            g_device,
            view,
            nullptr
        );

        view =
            VK_NULL_HANDLE;
    }


    if(image != VK_NULL_HANDLE)
    {
        vkDestroyImage(
            g_device,
            image,
            nullptr
        );

        image =
            VK_NULL_HANDLE;
    }


    if(memory != VK_NULL_HANDLE)
    {
        vkFreeMemory(
            g_device,
            memory,
            nullptr
        );

        memory =
            VK_NULL_HANDLE;
    }


    layout =
        VK_IMAGE_LAYOUT_UNDEFINED;
}


static void destroyFGUIImages()
{
    destroyFGImage(
        g_fgHudlessImage,
        g_fgHudlessMemory,
        g_fgHudlessView,
        g_fgHudlessLayout
    );


    destroyFGImage(
        g_fgFinalImage,
        g_fgFinalMemory,
        g_fgFinalView,
        g_fgFinalLayout
    );


    destroyFGImage(
        g_fgUIAlphaImage,
        g_fgUIAlphaMemory,
        g_fgUIAlphaView,
        g_fgUIAlphaLayout
    );


    g_fgSnapshotFormat =
        VK_FORMAT_UNDEFINED;

    g_fgUIWidth =
        0;

    g_fgUIHeight =
        0;

    g_fgHudlessFrameIndex =
        UINT32_MAX;

    g_fgUIAlphaFrameIndex =
        UINT32_MAX;
}


static void createFGImage(
    uint32_t width,
    uint32_t height,
    VkFormat format,
    VkImageUsageFlags usage,
    VkImage& image,
    VkDeviceMemory& memory,
    VkImageView& view
)
{
    VkImageCreateInfo imageInfo{
        VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO
    };


    imageInfo.imageType =
        VK_IMAGE_TYPE_2D;

    imageInfo.format =
        format;

    imageInfo.extent =
    {
        width,
        height,
        1
    };

    imageInfo.mipLevels =
        1;

    imageInfo.arrayLayers =
        1;

    imageInfo.samples =
        VK_SAMPLE_COUNT_1_BIT;

    imageInfo.tiling =
        VK_IMAGE_TILING_OPTIMAL;

    imageInfo.usage =
        usage;

    imageInfo.sharingMode =
        VK_SHARING_MODE_EXCLUSIVE;

    imageInfo.initialLayout =
        VK_IMAGE_LAYOUT_UNDEFINED;


    check(
        vkCreateImage(
            g_device,
            &imageInfo,
            nullptr,
            &image
        ),
        "FG-4.1 create image failed"
    );


    VkMemoryRequirements memoryRequirements{};

    vkGetImageMemoryRequirements(
        g_device,
        image,
        &memoryRequirements
    );


    VkMemoryAllocateInfo allocationInfo{
        VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO
    };


    allocationInfo.allocationSize =
        memoryRequirements.size;

    allocationInfo.memoryTypeIndex =
        findMemoryType(
            g_physicalDevice,
            memoryRequirements.memoryTypeBits,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
        );


    check(
        vkAllocateMemory(
            g_device,
            &allocationInfo,
            nullptr,
            &memory
        ),
        "FG-4.1 allocate image memory failed"
    );


    check(
        vkBindImageMemory(
            g_device,
            image,
            memory,
            0
        ),
        "FG-4.1 bind image memory failed"
    );


    VkImageViewCreateInfo viewInfo{
        VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO
    };


    viewInfo.image =
        image;

    viewInfo.viewType =
        VK_IMAGE_VIEW_TYPE_2D;

    viewInfo.format =
        format;


    viewInfo.subresourceRange.aspectMask =
        VK_IMAGE_ASPECT_COLOR_BIT;

    viewInfo.subresourceRange.baseMipLevel =
        0;

    viewInfo.subresourceRange.levelCount =
        1;

    viewInfo.subresourceRange.baseArrayLayer =
        0;

    viewInfo.subresourceRange.layerCount =
        1;


    check(
        vkCreateImageView(
            g_device,
            &viewInfo,
            nullptr,
            &view
        ),
        "FG-4.1 create image view failed"
    );
}


static void updateFGUIAlphaDescriptor()
{
    if(
        g_fgUIAlphaDescriptorSet ==
            VK_NULL_HANDLE ||

        g_fgUISnapshotSampler ==
            VK_NULL_HANDLE ||

        g_fgHudlessView ==
            VK_NULL_HANDLE ||

        g_fgFinalView ==
            VK_NULL_HANDLE ||

        g_fgUIAlphaView ==
            VK_NULL_HANDLE
    )
    {
        return;
    }


    VkDescriptorImageInfo hudlessInfo{};

    hudlessInfo.sampler =
        g_fgUISnapshotSampler;

    hudlessInfo.imageView =
        g_fgHudlessView;

    hudlessInfo.imageLayout =
        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;


    VkDescriptorImageInfo finalInfo{};

    finalInfo.sampler =
        g_fgUISnapshotSampler;

    finalInfo.imageView =
        g_fgFinalView;

    finalInfo.imageLayout =
        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;


    VkDescriptorImageInfo alphaInfo{};

    alphaInfo.imageView =
        g_fgUIAlphaView;

    alphaInfo.imageLayout =
        VK_IMAGE_LAYOUT_GENERAL;


    VkWriteDescriptorSet writes[3]{};


    writes[0].sType =
        VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;

    writes[0].dstSet =
        g_fgUIAlphaDescriptorSet;

    writes[0].dstBinding =
        0;

    writes[0].descriptorCount =
        1;

    writes[0].descriptorType =
        VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;

    writes[0].pImageInfo =
        &hudlessInfo;


    writes[1].sType =
        VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;

    writes[1].dstSet =
        g_fgUIAlphaDescriptorSet;

    writes[1].dstBinding =
        1;

    writes[1].descriptorCount =
        1;

    writes[1].descriptorType =
        VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;

    writes[1].pImageInfo =
        &finalInfo;


    writes[2].sType =
        VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;

    writes[2].dstSet =
        g_fgUIAlphaDescriptorSet;

    writes[2].dstBinding =
        2;

    writes[2].descriptorCount =
        1;

    writes[2].descriptorType =
        VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;

    writes[2].pImageInfo =
        &alphaInfo;


    vkUpdateDescriptorSets(
        g_device,
        3,
        writes,
        0,
        nullptr
    );
}


static void createFGUIAlphaPipeline()
{
    if(
        g_fgUIAlphaPipeline !=
        VK_NULL_HANDLE
    )
    {
        return;
    }


    VkDescriptorSetLayoutBinding
        bindings[3]{};


    bindings[0].binding =
        0;

    bindings[0].descriptorType =
        VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;

    bindings[0].descriptorCount =
        1;

    bindings[0].stageFlags =
        VK_SHADER_STAGE_COMPUTE_BIT;


    bindings[1].binding =
        1;

    bindings[1].descriptorType =
        VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;

    bindings[1].descriptorCount =
        1;

    bindings[1].stageFlags =
        VK_SHADER_STAGE_COMPUTE_BIT;


    bindings[2].binding =
        2;

    bindings[2].descriptorType =
        VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;

    bindings[2].descriptorCount =
        1;

    bindings[2].stageFlags =
        VK_SHADER_STAGE_COMPUTE_BIT;


    VkDescriptorSetLayoutCreateInfo
        descriptorLayoutInfo{
            VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO
        };


    descriptorLayoutInfo.bindingCount =
        3;

    descriptorLayoutInfo.pBindings =
        bindings;


    check(
        vkCreateDescriptorSetLayout(
            g_device,
            &descriptorLayoutInfo,
            nullptr,
            &g_fgUIAlphaDescriptorSetLayout
        ),
        "FG-4.1 create descriptor layout failed"
    );


    VkDescriptorPoolSize
        poolSizes[2]{};


    poolSizes[0].type =
        VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;

    poolSizes[0].descriptorCount =
        2;


    poolSizes[1].type =
        VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;

    poolSizes[1].descriptorCount =
        1;


    VkDescriptorPoolCreateInfo
        poolInfo{
            VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO
        };


    poolInfo.maxSets =
        1;

    poolInfo.poolSizeCount =
        2;

    poolInfo.pPoolSizes =
        poolSizes;


    check(
        vkCreateDescriptorPool(
            g_device,
            &poolInfo,
            nullptr,
            &g_fgUIAlphaDescriptorPool
        ),
        "FG-4.1 create descriptor pool failed"
    );


    VkDescriptorSetAllocateInfo
        descriptorAllocateInfo{
            VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO
        };


    descriptorAllocateInfo.descriptorPool =
        g_fgUIAlphaDescriptorPool;

    descriptorAllocateInfo.descriptorSetCount =
        1;

    descriptorAllocateInfo.pSetLayouts =
        &g_fgUIAlphaDescriptorSetLayout;


    check(
        vkAllocateDescriptorSets(
            g_device,
            &descriptorAllocateInfo,
            &g_fgUIAlphaDescriptorSet
        ),
        "FG-4.1 allocate descriptor set failed"
    );


    VkSamplerCreateInfo samplerInfo{
        VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO
    };


    samplerInfo.magFilter =
        VK_FILTER_NEAREST;

    samplerInfo.minFilter =
        VK_FILTER_NEAREST;

    samplerInfo.mipmapMode =
        VK_SAMPLER_MIPMAP_MODE_NEAREST;

    samplerInfo.addressModeU =
        VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;

    samplerInfo.addressModeV =
        VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;

    samplerInfo.addressModeW =
        VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;

    samplerInfo.mipLodBias =
        0.0f;

    samplerInfo.anisotropyEnable =
        VK_FALSE;

    samplerInfo.maxAnisotropy =
        1.0f;

    samplerInfo.compareEnable =
        VK_FALSE;

    samplerInfo.compareOp =
        VK_COMPARE_OP_ALWAYS;

    samplerInfo.minLod =
        0.0f;

    samplerInfo.maxLod =
        0.0f;

    samplerInfo.borderColor =
        VK_BORDER_COLOR_INT_OPAQUE_BLACK;

    samplerInfo.unnormalizedCoordinates =
        VK_FALSE;


    check(
        vkCreateSampler(
            g_device,
            &samplerInfo,
            nullptr,
            &g_fgUISnapshotSampler
        ),
        "FG-4.1 create sampler failed"
    );


    VkPipelineLayoutCreateInfo
        pipelineLayoutInfo{
            VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO
        };


    pipelineLayoutInfo.setLayoutCount =
        1;

    pipelineLayoutInfo.pSetLayouts =
        &g_fgUIAlphaDescriptorSetLayout;


    check(
        vkCreatePipelineLayout(
            g_device,
            &pipelineLayoutInfo,
            nullptr,
            &g_fgUIAlphaPipelineLayout
        ),
        "FG-4.1 create pipeline layout failed"
    );


    const std::vector<uint8_t> shaderCode =
        loadSpv(
            "shaders/fg_ui_mask.comp.spv"
        );


    VkShaderModule shaderModule =
        createShaderModule(
            g_device,
            shaderCode
        );


    VkPipelineShaderStageCreateInfo
        stageInfo{
            VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO
        };


    stageInfo.stage =
        VK_SHADER_STAGE_COMPUTE_BIT;

    stageInfo.module =
        shaderModule;

    stageInfo.pName =
        "main";


    VkComputePipelineCreateInfo
        pipelineInfo{
            VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO
        };


    pipelineInfo.stage =
        stageInfo;

    pipelineInfo.layout =
        g_fgUIAlphaPipelineLayout;


    VkResult pipelineResult =
        vkCreateComputePipelines(
            g_device,
            VK_NULL_HANDLE,
            1,
            &pipelineInfo,
            nullptr,
            &g_fgUIAlphaPipeline
        );


    vkDestroyShaderModule(
        g_device,
        shaderModule,
        nullptr
    );


    check(
        pipelineResult,
        "FG-4.1 create compute pipeline failed"
    );


    fprintf(
        stderr,
        "[FG-4.1 PIPELINE] READY\n"
    );

    fflush(stderr);
}


static bool ensureFGUIResources(
    uint32_t width,
    uint32_t height
)
{
    if(
        g_device ==
            VK_NULL_HANDLE ||

        g_physicalDevice ==
            VK_NULL_HANDLE ||

        width == 0 ||

        height == 0 ||

        g_fgBackbufferFormat ==
            VK_FORMAT_UNDEFINED ||

        g_fgUIAlphaPipeline ==
            VK_NULL_HANDLE ||

        g_fgUIAlphaDescriptorSet ==
            VK_NULL_HANDLE ||

        g_fgUISnapshotSampler ==
            VK_NULL_HANDLE
    )
    {
        return false;
    }


    if(
        g_fgHudlessImage !=
            VK_NULL_HANDLE &&

        g_fgFinalImage !=
            VK_NULL_HANDLE &&

        g_fgUIAlphaImage !=
            VK_NULL_HANDLE &&

        g_fgSnapshotFormat ==
            g_fgBackbufferFormat &&

        g_fgUIWidth ==
            width &&

        g_fgUIHeight ==
            height
    )
    {
        return true;
    }


    VkFormatProperties snapshotProperties{};

    vkGetPhysicalDeviceFormatProperties(
        g_physicalDevice,
        g_fgBackbufferFormat,
        &snapshotProperties
    );


    const VkFormatFeatureFlags
        snapshotRequired =
            VK_FORMAT_FEATURE_BLIT_DST_BIT |
            VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;


    if(
        (
            snapshotProperties.optimalTilingFeatures &
            snapshotRequired
        ) !=
        snapshotRequired
    )
    {
        fprintf(
            stderr,
            "[FG-4.1 RESOURCES]"
            " unsupported snapshot format=%d"
            " features=0x%X\n",

            static_cast<int>(
                g_fgBackbufferFormat
            ),

            static_cast<unsigned int>(
                snapshotProperties.optimalTilingFeatures
            )
        );

        fflush(stderr);

        return false;
    }


    VkFormatProperties alphaProperties{};

    vkGetPhysicalDeviceFormatProperties(
        g_physicalDevice,
        VK_FORMAT_R32_SFLOAT,
        &alphaProperties
    );


    const VkFormatFeatureFlags
        alphaRequired =
            VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT |
            VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;


    if(
        (
            alphaProperties.optimalTilingFeatures &
            alphaRequired
        ) !=
        alphaRequired
    )
    {
        fprintf(
            stderr,
            "[FG-4.1 RESOURCES]"
            " R32_SFLOAT unsupported"
            " features=0x%X\n",

            static_cast<unsigned int>(
                alphaProperties.optimalTilingFeatures
            )
        );

        fflush(stderr);

        return false;
    }

    if(
        g_fgHudlessImage !=
            VK_NULL_HANDLE ||

        g_fgFinalImage !=
            VK_NULL_HANDLE ||

        g_fgUIAlphaImage !=
            VK_NULL_HANDLE
    )
    {
        const VkResult idleResult =
            FGDeviceWaitIdle(
                g_device
            );


        if(
            idleResult !=
            VK_SUCCESS
        )
        {
            fprintf(
                stderr,
                "[FG-4.1 RESOURCES]"
                " waitIdle failed result=%d\n",

                static_cast<int>(
                    idleResult
                )
            );

            fflush(stderr);

            return false;
        }
    }


    destroyFGUIImages();


    try
    {
        createFGImage(
            width,
            height,
            g_fgBackbufferFormat,

            VK_IMAGE_USAGE_TRANSFER_DST_BIT |
            VK_IMAGE_USAGE_SAMPLED_BIT,

            g_fgHudlessImage,
            g_fgHudlessMemory,
            g_fgHudlessView
        );


        createFGImage(
            width,
            height,
            g_fgBackbufferFormat,

            VK_IMAGE_USAGE_TRANSFER_DST_BIT |
            VK_IMAGE_USAGE_SAMPLED_BIT,

            g_fgFinalImage,
            g_fgFinalMemory,
            g_fgFinalView
        );


        createFGImage(
            width,
            height,
            VK_FORMAT_R32_SFLOAT,

            VK_IMAGE_USAGE_STORAGE_BIT |
            VK_IMAGE_USAGE_SAMPLED_BIT,

            g_fgUIAlphaImage,
            g_fgUIAlphaMemory,
            g_fgUIAlphaView
        );
    }
    catch(const std::exception& e)
    {
        fprintf(
            stderr,
            "[FG-4.1 RESOURCES]"
            " create failed: %s\n",
            e.what()
        );

        fflush(stderr);

        destroyFGUIImages();

        return false;
    }


    g_fgSnapshotFormat =
        g_fgBackbufferFormat;

    g_fgUIWidth =
        width;

    g_fgUIHeight =
        height;


    g_fgHudlessLayout =
        VK_IMAGE_LAYOUT_UNDEFINED;

    g_fgFinalLayout =
        VK_IMAGE_LAYOUT_UNDEFINED;

    g_fgUIAlphaLayout =
        VK_IMAGE_LAYOUT_UNDEFINED;


    updateFGUIAlphaDescriptor();


    fprintf(
        stderr,
        "[FG-4.1 RESOURCES]"
        " size=%ux%u"
        " snapshotFormat=%d"
        " uiAlphaFormat=R32_SFLOAT\n",

        width,
        height,

        static_cast<int>(
            g_fgSnapshotFormat
        )
    );

    fflush(stderr);


    return true;
}

static PFN_vkCreateAccelerationStructureKHR getCreateAccelerationStructure(){return g_device?reinterpret_cast<PFN_vkCreateAccelerationStructureKHR>(vkGetDeviceProcAddr(g_device,"vkCreateAccelerationStructureKHR")):nullptr;}
static PFN_vkDestroyAccelerationStructureKHR getDestroyAccelerationStructure(){return g_device?reinterpret_cast<PFN_vkDestroyAccelerationStructureKHR>(vkGetDeviceProcAddr(g_device,"vkDestroyAccelerationStructureKHR")):nullptr;}
static PFN_vkGetAccelerationStructureBuildSizesKHR getGetAccelerationStructureBuildSizes(){return g_device?reinterpret_cast<PFN_vkGetAccelerationStructureBuildSizesKHR>(vkGetDeviceProcAddr(g_device,"vkGetAccelerationStructureBuildSizesKHR")):nullptr;}
static PFN_vkGetAccelerationStructureDeviceAddressKHR getGetAccelerationStructureDeviceAddress(){return g_device?reinterpret_cast<PFN_vkGetAccelerationStructureDeviceAddressKHR>(vkGetDeviceProcAddr(g_device,"vkGetAccelerationStructureDeviceAddressKHR")):nullptr;}
static PFN_vkCmdBuildAccelerationStructuresKHR getCmdBuildAccelerationStructures(){return g_device?reinterpret_cast<PFN_vkCmdBuildAccelerationStructuresKHR>(vkGetDeviceProcAddr(g_device,"vkCmdBuildAccelerationStructuresKHR")):nullptr;}
static PFN_vkCmdTraceRaysKHR getCmdTraceRays()
{
    // vkGetDeviceProcAddr is stable for a live VkDevice. Ray dispatch occurs
    // every frame, so cache this lookup and invalidate it on device changes.
    static VkDevice cachedDevice = VK_NULL_HANDLE;
    static PFN_vkCmdTraceRaysKHR cachedFunction = nullptr;
    if(cachedDevice != g_device)
    {
        cachedDevice = g_device;
        cachedFunction = g_device
            ? reinterpret_cast<PFN_vkCmdTraceRaysKHR>(
                vkGetDeviceProcAddr(g_device, "vkCmdTraceRaysKHR")
            )
            : nullptr;
    }
    return cachedFunction;
}
static PFN_vkCreateMicromapEXT getCreateMicromap(){return g_device?reinterpret_cast<PFN_vkCreateMicromapEXT>(vkGetDeviceProcAddr(g_device,"vkCreateMicromapEXT")):nullptr;}
static PFN_vkDestroyMicromapEXT getDestroyMicromap(){return g_device?reinterpret_cast<PFN_vkDestroyMicromapEXT>(vkGetDeviceProcAddr(g_device,"vkDestroyMicromapEXT")):nullptr;}
static PFN_vkCmdBuildMicromapsEXT getCmdBuildMicromaps(){return g_device?reinterpret_cast<PFN_vkCmdBuildMicromapsEXT>(vkGetDeviceProcAddr(g_device,"vkCmdBuildMicromapsEXT")):nullptr;}
static PFN_vkGetMicromapBuildSizesEXT getMicromapBuildSizes(){return g_device?reinterpret_cast<PFN_vkGetMicromapBuildSizesEXT>(vkGetDeviceProcAddr(g_device,"vkGetMicromapBuildSizesEXT")):nullptr;}

// VK_EXT_opacity_micromap requires a Synchronization2 dependency between the
// micromap build write and the AS build read.  These private ABI mirrors keep
// the backend compatible with Vulkan headers which predate Synchronization2.
struct OmmMemoryBarrier2
{
    VkStructureType sType;
    const void* pNext;
    uint64_t srcStageMask;
    uint64_t srcAccessMask;
    uint64_t dstStageMask;
    uint64_t dstAccessMask;
};
struct OmmDependencyInfo
{
    VkStructureType sType;
    const void* pNext;
    VkDependencyFlags dependencyFlags;
    uint32_t memoryBarrierCount;
    const OmmMemoryBarrier2* pMemoryBarriers;
    uint32_t bufferMemoryBarrierCount;
    const void* pBufferMemoryBarriers;
    uint32_t imageMemoryBarrierCount;
    const void* pImageMemoryBarriers;
};
using PFN_vkCmdPipelineBarrier2Omm = void (VKAPI_PTR *)(
    VkCommandBuffer,
    const OmmDependencyInfo*
);
static PFN_vkCmdPipelineBarrier2Omm getCmdPipelineBarrier2Omm()
{
    if(!g_device)
        return nullptr;
    auto fn = vkGetDeviceProcAddr(g_device, "vkCmdPipelineBarrier2");
    if(!fn)
        fn = vkGetDeviceProcAddr(g_device, "vkCmdPipelineBarrier2KHR");
    return reinterpret_cast<PFN_vkCmdPipelineBarrier2Omm>(fn);
}

static constexpr VkStructureType OMM_STRUCTURE_TYPE_MEMORY_BARRIER_2 =
    static_cast<VkStructureType>(1000314000);
static constexpr VkStructureType OMM_STRUCTURE_TYPE_DEPENDENCY_INFO =
    static_cast<VkStructureType>(1000314001);
static constexpr uint64_t OMM_STAGE_MICROMAP_BUILD = 0x40000000ull;
static constexpr uint64_t OMM_ACCESS_MICROMAP_WRITE = 0x200000000000ull;
static constexpr uint64_t OMM_STAGE_AS_BUILD = 0x02000000ull;
static constexpr uint64_t OMM_ACCESS_AS_READ = 0x00200000ull;

static void probeOpacityMicromapSupport()
{
    g_ommNativeEnabled = false;
    g_ommMax4StateSubdivisionLevel = 0;
    if(!g_instance || !g_physicalDevice || !g_device)
    {
        return;
    }

    auto properties2 = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties2>(
        vkGetInstanceProcAddr(g_instance, "vkGetPhysicalDeviceProperties2")
    );
    if(!properties2 || !getCreateMicromap() || !getDestroyMicromap() ||
       !getCmdBuildMicromaps() || !getMicromapBuildSizes() ||
       !getCmdPipelineBarrier2Omm())
    {
        RTLog("[OMM NATIVE] enabled=0 reason=missingEntryPoint");
        return;
    }

    VkPhysicalDeviceOpacityMicromapPropertiesEXT ommProps{};
    ommProps.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPACITY_MICROMAP_PROPERTIES_EXT;
    VkPhysicalDeviceProperties2 properties{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
    properties.pNext = &ommProps;
    properties2(g_physicalDevice, &properties);

    g_ommMax4StateSubdivisionLevel = ommProps.maxOpacity4StateSubdivisionLevel;
    // The Java classifier uses level 4, matching the 16x16 default sprite
    // resolution. Never attach data that exceeds the driver's advertised cap.
    g_ommNativeEnabled = g_ommMax4StateSubdivisionLevel >= 4u;

    std::stringstream log;
    log << "[OMM NATIVE] enabled=" << (g_ommNativeEnabled ? 1 : 0)
        << " max4StateLevel=" << g_ommMax4StateSubdivisionLevel;
    RTLog(log.str());
}

// SER-1: Capability-only probe.  The extension must be enabled in the Java
// VkDeviceCreateInfo feature chain before its shaders can use Hit Objects;
// do not attempt to mutate an already-created logical device here.
static void probeSerSupport()
{
    g_serExtAdvertised = false;
    g_serFeatureSupported = false;
    g_serHardwareReordering = false;

    if(!g_instance || !g_physicalDevice)
    {
        RTLog("[SER-1 PROBE] ext=0 feature=0 reason=missingPhysicalDevice");
        return;
    }

    uint32_t extensionCount = 0;
    VkResult extensionResult = vkEnumerateDeviceExtensionProperties(
        g_physicalDevice, nullptr, &extensionCount, nullptr);
    if(extensionResult != VK_SUCCESS)
    {
        RTLog("[SER-1 PROBE] ext=0 feature=0 reason=enumerateFailed");
        return;
    }

    std::vector<VkExtensionProperties> extensions(extensionCount);
    extensionResult = vkEnumerateDeviceExtensionProperties(
        g_physicalDevice, nullptr, &extensionCount, extensions.data());
    if(extensionResult != VK_SUCCESS)
    {
        RTLog("[SER-1 PROBE] ext=0 feature=0 reason=enumerateListFailed");
        return;
    }

    for(const VkExtensionProperties& extension : extensions)
    {
        if(std::strcmp(
            extension.extensionName,
            VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME) == 0)
        {
            g_serExtAdvertised = true;
            break;
        }
    }

    if(g_serExtAdvertised)
    {
        auto getFeatures2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
            vkGetInstanceProcAddr(g_instance, "vkGetPhysicalDeviceFeatures2"));
        if(!getFeatures2)
        {
            getFeatures2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
                vkGetInstanceProcAddr(g_instance, "vkGetPhysicalDeviceFeatures2KHR"));
        }

        if(getFeatures2)
        {
            VkPhysicalDeviceRayTracingInvocationReorderFeaturesEXT serFeatures{};
            serFeatures.sType =
                VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_INVOCATION_REORDER_FEATURES_EXT;
            VkPhysicalDeviceFeatures2 features2{
                VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
            features2.pNext = &serFeatures;
            getFeatures2(g_physicalDevice, &features2);
            g_serFeatureSupported =
                serFeatures.rayTracingInvocationReorder == VK_TRUE;
        }

        auto getProperties2 = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties2>(
            vkGetInstanceProcAddr(g_instance, "vkGetPhysicalDeviceProperties2"));
        if(!getProperties2)
        {
            getProperties2 = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties2>(
                vkGetInstanceProcAddr(g_instance, "vkGetPhysicalDeviceProperties2KHR"));
        }
        if(getProperties2)
        {
            VkPhysicalDeviceRayTracingInvocationReorderPropertiesEXT serProperties{};
            serProperties.sType =
                VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_INVOCATION_REORDER_PROPERTIES_EXT;
            VkPhysicalDeviceProperties2 properties2{
                VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
            properties2.pNext = &serProperties;
            getProperties2(g_physicalDevice, &properties2);
            g_serHardwareReordering =
                serProperties.rayTracingInvocationReorderReorderingHint ==
                VK_RAY_TRACING_INVOCATION_REORDER_MODE_REORDER_EXT;
        }
    }

    std::stringstream log;
    log << "[SER-1 PROBE]"
        << " ext=" << (g_serExtAdvertised ? 1 : 0)
        << " feature=" << (g_serFeatureSupported ? 1 : 0)
        << " hardwareReorder=" << (g_serHardwareReordering ? 1 : 0)
        << " deviceEnable=javaManaged"
        << " enabled=" << (g_serEnabled.load() ? 1 : 0)
        << " action="
        << (!g_serEnabled.load()
            ? "disabledByConfig"
            : (g_serFeatureSupported
                ? (g_serHardwareReordering
                    ? "SER3Eligible"
                    : "SERFeatureEnabledNoReorderHint")
                : "fallbackTraceRay"));
    RTLog(log.str());
}


// Strictly matches the global GLSL scalar Vertex ABI: 12 floats / 48 bytes.
struct Vertex {
    float x, y, z;
    float u, v;
    float r, g, b, a;
    float materialId;
    float pad1;
    float pad2;
};

static_assert(sizeof(Vertex) == 48, "RT Vertex ABI must remain 12 floats");

// Java encodes DyeColor.getId() + 3 in byte 27 for exact stained-glass
// quads.  These are the vanilla DyeColor firework RGB values indexed by that
// stable 0..15 ID: white, orange, magenta, light_blue, yellow, lime, pink,
// gray, light_gray, cyan, purple, blue, brown, green, red, black.
static constexpr uint32_t kStainedGlassDyeRgbById[16] =
{
    15790320u, 15435844u, 12801229u,  6719955u,
    14602026u,  4312372u, 14188952u,  4408131u,
    11250603u,  2651799u,  8073150u,  2437522u,
     5320730u,  3887386u, 11743532u,  1973019u
};


static void applyStainedGlassDyeToRTVertex(
    const uint8_t materialMarker,
    Vertex& vertex
)
{
    if(materialMarker < 3u || materialMarker > 18u)
    {
        return;
    }

    const uint32_t rgb =
        kStainedGlassDyeRgbById[
            static_cast<uint32_t>(materialMarker - 3u)
        ];

    vertex.r =
        static_cast<float>((rgb >> 16u) & 0xffu) /
        255.0f;

    vertex.g =
        static_cast<float>((rgb >> 8u) & 0xffu) /
        255.0f;

    vertex.b =
        static_cast<float>(rgb & 0xffu) /
        255.0f;
}
// 全局互斥锁，防止 Java 多线程并发调用 uploadChunk 时把数据写串
static std::mutex g_chunkMutex;

// 我们的 C++ 端“世界顶点蓄水池”
static std::unordered_map<uint64_t, std::vector<Vertex>> g_chunkMeshes;

// This spatial key is outside Minecraft's legal world-height range and is
// reserved exclusively for the animated entity aggregate. It is kept in a
// dedicated layer so it can use the generic entity-aware alpha-test shaders
// without changing terrain's separate translucent routing.
static constexpr uint64_t ENTITY_RT_SPATIAL_KEY =
    (0x7fffull << 44ull) |
    (0x3fffffull << 22ull) |
    0x3fffffull;
static constexpr uint64_t ENTITY_RT_LAYER_KEY =
    (5ull << 60ull) | ENTITY_RT_SPATIAL_KEY;
static constexpr uint64_t LOCAL_PLAYER_RT_LAYER_KEY =
    (6ull << 60ull) | ENTITY_RT_SPATIAL_KEY;
static constexpr uint64_t PARTICLE_RT_LAYER_KEY =
    (7ull << 60ull) | ENTITY_RT_SPATIAL_KEY;
static std::vector<Vertex> g_entityFrameVertices;
static std::vector<Vertex> g_localPlayerFrameVertices;
static std::vector<Vertex> g_particleFrameVertices;
static float g_dynamicCaptureCameraX = 0.0f;
static float g_dynamicCaptureCameraY = 0.0f;
static float g_dynamicCaptureCameraZ = 0.0f;
static bool g_entityFrameOpen = false;
static bool g_particleFrameOpen = false;
static std::atomic<bool> g_entityRebuildRequested{false};

// Generic entities and particles share one dynamic Section. Coalesce their
// animation updates while retaining at most one rendered frame of stale RT
// geometry; TLAS updates reuse the stable instance topology.
static uint64_t g_lastDynamicRTASUpdateFrame = UINT64_MAX;
static bool g_dynamicRTASUpdatePending = false;

static bool requestDynamicRTASRebuild(bool changed)
{
    g_dynamicRTASUpdatePending |= changed;
    if(!g_dynamicRTASUpdatePending)
    {
        return false;
    }

    if(
        g_lastDynamicRTASUpdateFrame != UINT64_MAX &&
        g_perfL10FrameSerial <
            g_lastDynamicRTASUpdateFrame + g_perfDynamicRtasInterval.load()
    )
    {
        return false;
    }

    g_dynamicRTASUpdatePending = false;
    g_lastDynamicRTASUpdateFrame = g_perfL10FrameSerial;
    return true;
}

// The first-person local-player proxy is captured in camera-relative space.
// Small camera movement therefore changes its CPU vertex payload every frame,
// even though the world-space model has not materially changed. Rebuilding
// its BLAS/TLAS every frame forces a queue-idle stall, so publish the latest
// payload but refresh the AS at a bounded cadence.
// Fenced Section retirement keeps this bounded cadence from stalling the CPU.
static constexpr uint64_t LOCAL_PLAYER_AS_UPDATE_INTERVAL = 8u;
static uint64_t g_lastLocalPlayerASUpdateFrame = UINT64_MAX;
static bool g_localPlayerASUpdatePending = false;

static constexpr uint32_t ENTITY_RT_TEXTURE_SLOT_COUNT = 16u;
static VkImageView g_entityTextureViews[ENTITY_RT_TEXTURE_SLOT_COUNT]{};
static VkSampler g_entityTextureSamplers[ENTITY_RT_TEXTURE_SLOT_COUNT]{};
static bool g_entityTexturesDirty = false;
static constexpr uint32_t PARTICLE_RT_TEXTURE_SLOT_COUNT = 3u;
static VkImageView g_particleTextureViews[PARTICLE_RT_TEXTURE_SLOT_COUNT]{};
static VkSampler g_particleTextureSamplers[PARTICLE_RT_TEXTURE_SLOT_COUNT]{};
static bool g_particleTexturesDirty = false;
static VkImageView g_rainStreakTextureView = VK_NULL_HANDLE;
static VkSampler g_rainStreakTextureSampler = VK_NULL_HANDLE;
static bool g_rainStreakTextureDirty = false;

static void invalidateDynamicTextureBindingsForAtlasReload()
{
    for(uint32_t slot = 0u; slot < ENTITY_RT_TEXTURE_SLOT_COUNT; ++slot)
    {
        g_entityTextureViews[slot] = VK_NULL_HANDLE;
        g_entityTextureSamplers[slot] = VK_NULL_HANDLE;
    }

    for(uint32_t slot = 0u; slot < PARTICLE_RT_TEXTURE_SLOT_COUNT; ++slot)
    {
        g_particleTextureViews[slot] = VK_NULL_HANDLE;
        g_particleTextureSamplers[slot] = VK_NULL_HANDLE;
    }

    g_entityTexturesDirty = false;
    g_particleTexturesDirty = false;
    g_rainStreakTextureView = VK_NULL_HANDLE;
    g_rainStreakTextureSampler = VK_NULL_HANDLE;
    g_rainStreakTextureDirty = false;
    g_dynamicTextureBindingsNeedAtlasRefresh = true;
    g_dynamicTextureBindingsCanPublish = false;
}
// P1 diagnostic state.  It only records the boundaries of a non-empty
// particle episode and does not participate in capture, BLAS/TLAS, or
// descriptor updates.
static bool g_particleDebugEpisodeActive = false;
static uint64_t g_particleDebugEpisodeSerial = 0u;
// One diagnostic only: prove whether SectionCompiler's puddle marker reaches
// the exact terrain triangles supplied to ray tracing.  It deliberately does
// not affect geometry, shading, or per-frame memory.
static bool g_rainPuddleLoggedGeometry = false;

// Exact copies of the Java-owned 28-byte terrain payloads.  These exist only
// to reject an identical Section-layer upload before CPU quad expansion.  The
// copies include byte 27, so water/glass/stained-glass marker changes remain
// visible to the existing dirty/rebuild path.
static std::unordered_map<uint64_t, std::vector<uint8_t>>
    g_chunkRawPayloads;

// SectionCompiler can submit an already compiled layer again while the
// underlying Section has not changed.  Do not turn that duplicate upload into
// a BLAS/TLAS rebuild.  Every Vertex field is explicitly populated in
// uploadChunk(), so byte-wise comparison is both exact and stable here.
static bool sameVertexPayload(
    const std::vector<Vertex>& a,
    const std::vector<Vertex>& b
)
{
    return
        a.size() == b.size() &&
        (
            a.empty() ||
            std::memcmp(
                a.data(),
                b.data(),
                a.size() * sizeof(Vertex)
            ) == 0
        );
}

// packSectionKey stores layerId in bits 60..63. BLAS ownership is spatial:
// all render layers belonging to the same 16x16x16 Section share one BLAS.
static constexpr uint64_t SPATIAL_SECTION_KEY_MASK =
    (1ull << 60ull) - 1ull;

// The upper four bits are free after the spatial key was extracted.  Water
// owns one physical BLAS/TLAS partition; every non-water translucent material
// owns another.  Their hit-group routing is therefore independent.
static constexpr uint64_t SECTION_AS_CLASS_OPAQUE = 1ull << 60ull;
static constexpr uint64_t SECTION_AS_CLASS_OMM    = 2ull << 60ull;
static constexpr uint64_t SECTION_AS_CLASS_ALPHA  = 3ull << 60ull;
static constexpr uint64_t SECTION_AS_CLASS_WATER  = 4ull << 60ull;
// Dynamic entities require the generic hit shaders (Binding 36 entity
// textures) plus Any-Hit alpha cutout, rather than the terrain translucent
// shader modules used by SECTION_AS_CLASS_ALPHA.
static constexpr uint64_t SECTION_AS_CLASS_ENTITY = 5ull << 60ull;
static constexpr uint64_t SECTION_AS_CLASS_LOCAL_PLAYER = 6ull << 60ull;
static constexpr uint64_t SECTION_AS_CLASS_PARTICLE = 7ull << 60ull;

// R1 uses independent SBT records while deliberately retaining the current
// hit group in every record.  Partitioning is verified before any shader
// behavior changes in the following stage.
static constexpr uint32_t SBT_RECORD_OPAQUE = 0u;
static constexpr uint32_t SBT_RECORD_OMM    = 1u;
static constexpr uint32_t SBT_RECORD_ALPHA  = 2u;
static constexpr uint32_t SBT_RECORD_WATER  = 3u;
static constexpr uint32_t SBT_RECORD_ENTITY = 4u;
// Ray type 0 = normal renderer; ray type 1 = compact ReSTIR local-light
// visibility.  TLAS instance offsets stay 0..4; the final visibility ray adds
// exactly five records through traceRayEXT.sbtRecordOffset.
static constexpr uint32_t SBT_GEOMETRY_RECORD_COUNT = 5u;
static constexpr uint32_t SBT_LOCAL_VISIBILITY_RECORD_OFFSET =
    SBT_GEOMETRY_RECORD_COUNT;
static constexpr uint32_t SBT_HIT_RECORD_COUNT =
    SBT_GEOMETRY_RECORD_COUNT * 2u;
static constexpr uint32_t SBT_MISS_RECORD_COUNT = 2u;

struct SectionGeometrySplit
{
    std::vector<Vertex> opaque;
    std::vector<Vertex> ommCutout;
    std::vector<Vertex> alpha;
    std::vector<Vertex> water;
    std::vector<Vertex> entity;
    std::vector<Vertex> localPlayer;
    std::vector<Vertex> particle;
    OmmCutoutInput ommInput;
    bool hasOmmInput = false;
};

static std::unordered_set<uint64_t> g_dirtySectionKeys;
static bool g_forceSectionASReset = false;
static std::atomic<bool> g_worldGeometryDirty{false};
static std::atomic<uint64_t> g_worldGeometryRevision{0u};

// Every producer updates the revision after it has published a new Section
// payload under g_chunkMutex. The render thread uses this to coalesce the
// streaming burst without reading container state outside the mutex.
static void markWorldGeometryDirty()
{
    g_worldGeometryRevision.fetch_add(1u, std::memory_order_release);
    g_worldGeometryDirty.store(true, std::memory_order_release);
}


// =====================================================
// RT Local Lights - L2 native Section cache
//
// Must match the Java record layout exactly:
//
// int worldX          4 bytes
// int worldY          4 bytes
// int worldZ          4 bytes
// int emissionLevel   4 bytes
// int typeCode        4 bytes
//
// Total = 20 bytes.
// =====================================================

struct LocalLightRecord
{
    int32_t worldX;
    int32_t worldY;
    int32_t worldZ;
    int32_t emissionLevel;
    int32_t typeCode;
};


static_assert(
    sizeof(LocalLightRecord) == 20,
    "LocalLightRecord must remain exactly 20 bytes"
);


static bool sameLocalLightPayload(
    const std::vector<LocalLightRecord>& a,
    const std::vector<LocalLightRecord>& b
)
{
    return
        a.size() == b.size() &&
        (
            a.empty() ||
            std::memcmp(
                a.data(),
                b.data(),
                a.size() * sizeof(LocalLightRecord)
            ) == 0
        );
}


static std::mutex
    g_lightMutex;


static std::unordered_map<
    uint64_t,
    std::vector<LocalLightRecord>
> g_lightSections;


static size_t
    g_totalLocalLights = 0;


// =====================================================
// RT Local Lights - L3 GPU aggregate buffer
//
// GPU layout:
//   header (16 bytes)
//       uint lightCount
//       uint lightRevision      // invalidates ReSTIR history
//       uint pad1
//       uint pad2
//
//   each light (32 bytes / std430-friendly)
//       vec4 worldPosEmission   // xyz = world position, w = emission
//       uvec4 meta              // x = typeCode
// =====================================================

struct GPULocalLightHeader
{
    uint32_t lightCount;
    uint32_t pad0;
    uint32_t pad1;
    uint32_t pad2;
};


static_assert(
    sizeof(GPULocalLightHeader) == 16,
    "GPULocalLightHeader must remain exactly 16 bytes"
);


struct GPULocalLightRecord
{
    float worldX;
    float worldY;
    float worldZ;
    float emissionLevel;

    uint32_t typeCode;
    uint32_t pad0;
    uint32_t pad1;
    uint32_t pad2;
};


static_assert(
    sizeof(GPULocalLightRecord) == 32,
    "GPULocalLightRecord must remain exactly 32 bytes"
);


static VkBuffer
    g_localLightGpuBuffer =
        VK_NULL_HANDLE;


static VkDeviceMemory
    g_localLightGpuMemory =
        VK_NULL_HANDLE;


static size_t
    g_localLightGpuCount = 0;

static VkDeviceSize g_localLightGpuCapacity = 0;


static bool
    g_localLightsDirty = true;


// =====================================================
// RT Local Lights - P2B-1 spatial index (Binding 18)
//
// The existing Binding 17 aggregate-light layout remains unchanged.
// Binding 18 stores a 64x64x64-block hashed grid whose entries point
// back to Binding 17 light indices.
//
// GPU layout:
//   header (16 bytes)
//       uint occupiedCellCount
//       uint lightIndexCount
//       uint hashCapacity       // power of two, at least 1
//       uint slotStrideWords    // fixed at 8
//
//   hash slots (hashCapacity * 32 bytes)
//       int  cellX
//       int  cellY
//       int  cellZ
//       uint occupied
//       uint firstLightIndex
//       uint lightCount
//       uint pad0
//       uint pad1
//
//   packed uint lightIndices[lightIndexCount]
//
// RayGen consumes this grid for both the confirmed G2B path and
// ReSTIR DI candidate selection, avoiding scene-wide light scans.
// =====================================================

constexpr int32_t
    LOCAL_LIGHT_GRID_CELL_SIZE = 64;


constexpr uint32_t
    LOCAL_LIGHT_GRID_SLOT_WORDS = 8u;


struct LocalLightGridCellCoord
{
    int32_t x;
    int32_t y;
    int32_t z;


    bool operator==(
        const LocalLightGridCellCoord& other
    ) const noexcept
    {
        return
            x == other.x &&
            y == other.y &&
            z == other.z;
    }
};


static uint32_t localLightGridHash32(
    int32_t x,
    int32_t y,
    int32_t z
)
{
    uint32_t h =
        static_cast<uint32_t>(x) * 0x8da6b343u;

    h ^=
        static_cast<uint32_t>(y) * 0xd8163841u;

    h ^=
        static_cast<uint32_t>(z) * 0xcb1ab31fu;

    h ^= h >> 16u;
    h *= 0x7feb352du;
    h ^= h >> 15u;
    h *= 0x846ca68bu;
    h ^= h >> 16u;

    return h;
}


struct LocalLightGridCellCoordHash
{
    size_t operator()(
        const LocalLightGridCellCoord& coord
    ) const noexcept
    {
        return static_cast<size_t>(
            localLightGridHash32(
                coord.x,
                coord.y,
                coord.z
            )
        );
    }
};


struct GPULocalLightGridHeader
{
    uint32_t occupiedCellCount;
    uint32_t lightIndexCount;
    uint32_t hashCapacity;
    uint32_t slotStrideWords;
};


static_assert(
    sizeof(GPULocalLightGridHeader) == 16,
    "GPULocalLightGridHeader must remain exactly 16 bytes"
);


struct GPULocalLightGridSlot
{
    int32_t cellX;
    int32_t cellY;
    int32_t cellZ;
    uint32_t occupied;

    uint32_t firstLightIndex;
    uint32_t lightCount;
    uint32_t pad0;
    uint32_t pad1;
};


static_assert(
    sizeof(GPULocalLightGridSlot) == 32,
    "GPULocalLightGridSlot must remain exactly 32 bytes"
);


static VkBuffer
    g_localLightGridGpuBuffer =
        VK_NULL_HANDLE;


static VkDeviceMemory
    g_localLightGridGpuMemory =
        VK_NULL_HANDLE;

static VkDeviceSize g_localLightGridGpuCapacity = 0;


static size_t
    g_localLightGridCellCount = 0;


static size_t
    g_localLightGridIndexCount = 0;


static size_t
    g_localLightGridHashCapacity = 0;


// =====================================================
// ReSTIR DI - per-pixel double-buffered reservoirs
//
// Must match the std430 ReSTIRDIReservoir layout in rt_test.rgen.
// Five 16-byte lanes = 80 bytes per pixel.
// =====================================================

struct ReSTIRDIReservoirGPU
{
    float sampleX;
    float sampleY;
    float sampleZ;
    float sampleAreaPdf;

    float emitterNormalX;
    float emitterNormalY;
    float emitterNormalZ;
    float targetPdf;

    float receiverX;
    float receiverY;
    float receiverZ;
    float receiverViewDepth;

    float receiverNormalX;
    float receiverNormalY;
    float receiverNormalZ;
    float reservoirWeight;

    uint32_t lightIndex;
    uint32_t sampleCount;
    uint32_t lightRevision;
    uint32_t valid;
};


static_assert(
    sizeof(ReSTIRDIReservoirGPU) == 80,
    "ReSTIRDIReservoirGPU must remain exactly 80 bytes"
);

struct ReSTIRDISpatialPushConstants
{
    uint32_t width;
    uint32_t height;
};


static VkBuffer
    g_restirDIReservoirBuffers[2] =
    {
        VK_NULL_HANDLE,
        VK_NULL_HANDLE
    };


static VkDeviceMemory
    g_restirDIReservoirMemories[2] =
    {
        VK_NULL_HANDLE,
        VK_NULL_HANDLE
    };


static VkBuffer
    g_restirDISpatialBuffer =
        VK_NULL_HANDLE;


static VkDeviceMemory
    g_restirDISpatialMemory =
        VK_NULL_HANDLE;


static uint32_t
    g_restirDIWidth = 0;


static uint32_t
    g_restirDIHeight = 0;


static VkDeviceSize
    g_restirDIReservoirBytes = 0;


static bool
    g_restirDINeedsClear = false;


static bool
    g_restirCameraHistoryValid = false;


static int32_t
    g_restirLastCameraFrame = -1;


static uint32_t
    g_localLightRevision = 1u;


// =====================================================
// VPT-2.2 - screen-space temporal history for the primary
// voxel DDA candidate.  This intentionally does not reuse
// ReSTIR DI bindings 19/20 or their reservoir representation.
// Three std430 vec4 lanes = 48 bytes per render-resolution pixel.
// =====================================================
struct VPTDirectHistoryGPU
{
    float radianceX;
    float radianceY;
    float radianceZ;
    float sampleCount;

    float hitPositionX;
    float hitPositionY;
    float hitPositionZ;
    float valid;

    float normalX;
    float normalY;
    float normalZ;
    float padding;
};

static_assert(
    sizeof(VPTDirectHistoryGPU) == 48,
    "VPTDirectHistoryGPU must remain exactly three std430 vec4 lanes"
);

static VkBuffer g_vptDirectHistoryBuffers[2] =
{
    VK_NULL_HANDLE,
    VK_NULL_HANDLE
};

static VkDeviceMemory g_vptDirectHistoryMemories[2] =
{
    VK_NULL_HANDLE,
    VK_NULL_HANDLE
};

static uint32_t g_vptDirectHistoryWidth = 0u;
static uint32_t g_vptDirectHistoryHeight = 0u;
static VkDeviceSize g_vptDirectHistoryBytes = 0;
static bool g_vptDirectHistoryNeedsClear = false;


// =====================================================
// VPT-2 Section voxel-light pages
//
// Binding 22: sparse Section -> page table
// Binding 23: 16^3 source voxels + 9^3 irradiance probes per page
// Binding 24: previous-frame irradiance cache (ping-pong history)
// Binding 25: per-update work-list (table slot indices)
// =====================================================

constexpr uint32_t VLI_MAX_SECTION_PAGES = 4096u;
constexpr uint32_t VLI_PAGE_TABLE_CAPACITY = 8192u;
constexpr uint32_t VLI_SOURCE_WORDS = 16u * 16u * 16u;
constexpr uint32_t VLI_PROBE_EDGE = 9u;
constexpr uint32_t VLI_PROBE_WORDS = VLI_PROBE_EDGE * VLI_PROBE_EDGE * VLI_PROBE_EDGE;
// VPT-3.2B stores a separate irradiance response for the six axis directions
// at every probe.  A page is source voxels followed by +X/-X/+Y/-Y/+Z/-Z.
constexpr uint32_t VPT_DIRECTION_COUNT = 6u;
constexpr uint32_t VPT_DIRECTIONAL_CACHE_WORDS =
    VPT_DIRECTION_COUNT * VLI_PROBE_WORDS;
constexpr uint32_t VLI_PAGE_WORDS =
    VLI_SOURCE_WORDS + VPT_DIRECTIONAL_CACHE_WORDS;
// VPT-3.3A.1: one RGB radiance value for every exposed face of every
// Minecraft block in a Section.  This is intentionally not a 9^3 air field.
constexpr uint32_t VPT_SURFACE_FACES_PER_SECTION =
    VLI_SOURCE_WORDS * VPT_DIRECTION_COUNT;
// Directional pages are deterministic and event-driven: a dirty page is
// solved completely in one dispatch, never progressively refreshed per frame.
constexpr uint32_t VPT_PROBES_PER_PAGE_PER_FRAME = VLI_PROBE_WORDS;

// VPT-3.1 keeps an exact compact index of every emissive voxel.  Four header
// words keep each page std430-friendly; the remaining 4096 words are one
// packed record per possible block in the Section.  No emitter is clustered
// or discarded at this stage.
constexpr uint32_t VPT_EMITTER_HEADER_WORDS = 4u;
constexpr uint32_t VPT_MAX_EMITTERS_PER_SECTION = VLI_SOURCE_WORDS;
constexpr uint32_t VPT_EMITTER_PAGE_WORDS =
    VPT_EMITTER_HEADER_WORDS + VPT_MAX_EMITTERS_PER_SECTION;

// VPT-3.2: each receiver Section receives a fixed set of the eight most
// influential exact emitters in its 5x5x5-Section light neighborhood.  The
// full Binding 28 index remains authoritative; this compact page is only the
// bounded candidate set used by the later deterministic probe solver.
constexpr uint32_t VPT_RELATED_EMITTERS_PER_SECTION = 8u;

// VPT-3.3D.7: the primary RayGen path must not abruptly discard real lights
// merely because a receiver crosses a Section boundary.  Keep a compact,
// event-driven directory of every non-empty source Section in the same 5^3
// neighborhood used by the exact emitter index.  Each entry is two uints:
// pageIndex and packed (emitter count + signed Section offsets).
constexpr uint32_t VPT_SOURCE_DIRECTORY_RADIUS = 2u;
constexpr uint32_t VPT_SOURCE_DIRECTORY_MAX_SECTIONS =
    (VPT_SOURCE_DIRECTORY_RADIUS * 2u + 1u) *
    (VPT_SOURCE_DIRECTORY_RADIUS * 2u + 1u) *
    (VPT_SOURCE_DIRECTORY_RADIUS * 2u + 1u);
constexpr uint32_t VPT_SOURCE_DIRECTORY_ENTRIES_PER_PAGE =
    1u + VPT_SOURCE_DIRECTORY_MAX_SECTIONS; // entry 0 is the count header

static_assert(
    VLI_PAGE_WORDS >= VLI_SOURCE_WORDS + VPT_DIRECTIONAL_CACHE_WORDS,
    "VLI page must contain source voxels and directional irradiance probes"
);

static_assert(
    VPT_EMITTER_PAGE_WORDS * sizeof(uint32_t) <= 65536u,
    "VPT emitter page must fit one vkCmdUpdateBuffer operation"
);

struct VLITableSlot
{
    int32_t sectionX = 0;
    int32_t sectionY = 0;
    int32_t sectionZ = 0;
    uint32_t pagePlusOne = 0;
};

static_assert(sizeof(VLITableSlot) == 16, "VLI table slot must be 16 bytes");

struct VPTRelatedEmitterGPU
{
    int32_t worldX = 0;
    int32_t worldY = 0;
    int32_t worldZ = 0;
    // bits 0..3 emission level; bits 4..6 type code; zero means unused.
    uint32_t emissionAndType = 0;
};

static_assert(
    sizeof(VPTRelatedEmitterGPU) == 16,
    "VPTRelatedEmitterGPU must map to one std430 uvec4"
);

struct VPTSourceDirectoryEntryGPU
{
    uint32_t pageIndex = 0u;
    uint32_t emitterCountAndOffset = 0u;
    uint32_t aliasThresholdQ16 = 0u;
    uint32_t aliasIndex = 0u;
    float selectionProbability = 0.0f;
};

static_assert(
    sizeof(VPTSourceDirectoryEntryGPU) == 20,
    "VPTSourceDirectoryEntryGPU must map to the std430 Alias entry"
);

struct VLISectionCpuPage
{
    int32_t sectionX = 0;
    int32_t sectionY = 0;
    int32_t sectionZ = 0;
    uint32_t pageIndex = 0;
    uint32_t tableSlot = 0;
    std::vector<uint32_t> sourceWords;
    std::vector<uint32_t> emitterIndexWords;
    std::vector<uint32_t> emitterAliasWords;
    std::array<VPTRelatedEmitterGPU, VPT_RELATED_EMITTERS_PER_SECTION>
        relatedEmitters{};
    std::array<VPTSourceDirectoryEntryGPU,
        VPT_SOURCE_DIRECTORY_ENTRIES_PER_PAGE> sourceDirectory{};
};

static std::mutex g_vliMutex;
static std::unordered_map<uint64_t, VLISectionCpuPage> g_vliSections;
static std::vector<VLITableSlot> g_vliTableCpu(VLI_PAGE_TABLE_CAPACITY);
static std::vector<uint32_t> g_vliFreePages;
static uint32_t g_vliNextPage = 0u;
static std::unordered_set<uint32_t> g_vliDirtySourcePages;
static std::unordered_set<uint32_t> g_vliDirtyTableSlots;
static std::unordered_set<uint32_t> g_vliDirtySolveTableSlots;
static std::unordered_set<uint32_t> g_vptDirtyEmitterPages;
static std::unordered_set<uint32_t> g_vptDirtyEmitterAliasPages;
static std::unordered_set<uint32_t> g_vptDirtyCandidatePages;
static std::unordered_set<uint32_t> g_vptDirtySourceDirectoryPages;
static uint32_t g_vptEmitterRevision = 1u;
// ReSTIR DI stores the source-directory revision in a float lane. Keep the
// monotonically changing token inside the exact-integer range of float32 and
// reserve 0 as the invalid/uninitialized sentinel used by the shaders.
static uint32_t g_vptSourceDirectoryRevision = 1u;

static VkBuffer g_vliPageTableBuffer = VK_NULL_HANDLE;
static VkDeviceMemory g_vliPageTableMemory = VK_NULL_HANDLE;
static VkBuffer g_vliPageBuffer = VK_NULL_HANDLE;
static VkDeviceMemory g_vliPageMemory = VK_NULL_HANDLE;
static VkBuffer g_vliScratchBuffer = VK_NULL_HANDLE;
static VkDeviceMemory g_vliScratchMemory = VK_NULL_HANDLE;
static VkBuffer g_vptSurfaceCacheBuffer = VK_NULL_HANDLE;
static VkDeviceMemory g_vptSurfaceCacheMemory = VK_NULL_HANDLE;
static VkBuffer g_vliWorkListBuffer = VK_NULL_HANDLE;
static VkDeviceMemory g_vliWorkListMemory = VK_NULL_HANDLE;
static VkBuffer g_vptEmitterIndexBuffer = VK_NULL_HANDLE;
static VkDeviceMemory g_vptEmitterIndexMemory = VK_NULL_HANDLE;
static VkBuffer g_vptEmitterAliasBuffer = VK_NULL_HANDLE;
static VkDeviceMemory g_vptEmitterAliasMemory = VK_NULL_HANDLE;
static VkBuffer g_vptCandidateBuffer = VK_NULL_HANDLE;
static VkDeviceMemory g_vptCandidateMemory = VK_NULL_HANDLE;
static VkBuffer g_vptSourceDirectoryBuffer = VK_NULL_HANDLE;
static VkDeviceMemory g_vptSourceDirectoryMemory = VK_NULL_HANDLE;
static VkPipelineLayout g_vliComputePipelineLayout = VK_NULL_HANDLE;
static VkPipeline g_vliComputePipeline = VK_NULL_HANDLE;
static bool g_vliNeedsInitialClear = true;
static uint32_t g_vptTemporalFrame = 0u;

static void clearVLISections();
static void createVLILightResources();
static void createVLIComputePipeline();
// VPT-3.3: 定义两阶段 Compute 执行协议结构体 (8 字节)
struct VPTPushConstants
{
    uint32_t eventSerial;
    uint32_t passIndex; // 0 = Pass 1 (一次直接光), 1 = Pass 2 (二次漫反射)
};

static void recordVLIUpdates(VkCommandBuffer cmd);
static void destroyVLIResources();


static void createTestVertexBuffer(){
    std::cout
    << "[VERTEX SIZE] "
    << sizeof(Vertex)
    << std::endl;
    const Vertex v[] = {
        { -500.0f, -500.0f, -50.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f },
        {  500.0f, -500.0f, -50.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f },
        {    0.0f,  500.0f, -50.0f, 0.5f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f }
    };

    createBuffer(g_device,g_physicalDevice,sizeof(v),
    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|
    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR |
    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT |
    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
    g_testVertexBuffer,g_testVertexMemory);

    void* mapped=nullptr;
    check(vkMapMemory(g_device,g_testVertexMemory,0,sizeof(v),0,&mapped),"Failed map vertex");
    memcpy(mapped,v,sizeof(v));
    Vertex* checkVertex = static_cast<Vertex*>(mapped);

    std::cout
        << "[CPU BUFFER CHECK] "
        << checkVertex[0].x << " "
        << checkVertex[0].y << " "
        << checkVertex[0].z
        << std::endl;
    vkUnmapMemory(g_device,g_testVertexMemory);
    g_testVertexDeviceAddress=getBufferDeviceAddress(g_device,g_testVertexBuffer);
    VkBufferDeviceAddressInfo checkInfo{
        VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO
    };

    checkInfo.buffer = g_testVertexBuffer;

    auto addr2 = vkGetBufferDeviceAddress(
        g_device,
        &checkInfo
    );

    std::cerr
    <<"[BDA DOUBLE CHECK] "
    <<std::hex
    <<addr2
    <<std::endl;
    std::cerr
<< "[CREATE TEST BUFFER BDA] "
<< g_testVertexDeviceAddress
<< std::endl;
}


// Initialization-only fallback BLAS. It keeps the pre-world TLAS and the
// first descriptor write valid; once terrain Sections arrive, the dynamic
// multi-Section path replaces the TLAS before tracing the world.
static void createBLAS()
{
    auto getBuildSizes =
        getGetAccelerationStructureBuildSizes();

    auto createAS =
        getCreateAccelerationStructure();

    auto getASAddress =
        getGetAccelerationStructureDeviceAddress();

    auto cmdBuild =
        getCmdBuildAccelerationStructures();


    if(
        !getBuildSizes ||
        !createAS ||
        !getASAddress ||
        !cmdBuild
    )
    {
        throw std::runtime_error(
            "initial BLAS Vulkan functions unavailable"
        );
    }


    VkAccelerationStructureGeometryTrianglesDataKHR triangles{
        VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_TRIANGLES_DATA_KHR
    };

    triangles.vertexFormat =
        VK_FORMAT_R32G32B32_SFLOAT;

    triangles.vertexData.deviceAddress =
        g_testVertexDeviceAddress;

    triangles.vertexStride =
        sizeof(Vertex);

    triangles.maxVertex =
        2;

    triangles.indexType =
        VK_INDEX_TYPE_NONE_KHR;


    VkAccelerationStructureGeometryKHR geometry{
        VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR
    };

    geometry.geometryType =
        VK_GEOMETRY_TYPE_TRIANGLES_KHR;

    geometry.flags = 0;
    geometry.geometry.triangles =
        triangles;


    VkAccelerationStructureBuildGeometryInfoKHR buildInfo{
        VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR
    };

    buildInfo.type =
        VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR;

    buildInfo.flags =
        VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;

    buildInfo.mode =
        VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR;

    buildInfo.geometryCount = 1;
    buildInfo.pGeometries =
        &geometry;


    const uint32_t primitiveCount =
        1;


    VkAccelerationStructureBuildSizesInfoKHR sizesInfo{
        VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR
    };

    getBuildSizes(
        g_device,
        VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
        &buildInfo,
        &primitiveCount,
        &sizesInfo
    );


    createBuffer(
        g_device,
        g_physicalDevice,
        sizesInfo.accelerationStructureSize,
        VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR |
            VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
        g_blasBuffer,
        g_blasMemory
    );


    VkAccelerationStructureCreateInfoKHR createInfo{
        VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR
    };

    createInfo.buffer =
        g_blasBuffer;

    createInfo.size =
        sizesInfo.accelerationStructureSize;

    createInfo.type =
        VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR;

    check(
        createAS(
            g_device,
            &createInfo,
            nullptr,
            &g_blas
        ),
        "create initial BLAS failed"
    );


    VkDeviceAddress scratchAddress = 0;

    if(sizesInfo.buildScratchSize > 0)
    {
        createBuffer(
            g_device,
            g_physicalDevice,
            sizesInfo.buildScratchSize,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT |
                VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
            g_blasScratchBuffer,
            g_blasScratchMemory
        );

        scratchAddress =
            getBufferDeviceAddress(
                g_device,
                g_blasScratchBuffer
            );
    }


    buildInfo.dstAccelerationStructure =
        g_blas;

    buildInfo.scratchData.deviceAddress =
        scratchAddress;


    VkAccelerationStructureBuildRangeInfoKHR range{};
    range.primitiveCount =
        primitiveCount;

    const VkAccelerationStructureBuildRangeInfoKHR* ranges[] =
    {
        &range
    };


    VkCommandBufferAllocateInfo allocateInfo{
        VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO
    };

    allocateInfo.commandPool =
        g_rtCommandPool;

    allocateInfo.level =
        VK_COMMAND_BUFFER_LEVEL_PRIMARY;

    allocateInfo.commandBufferCount =
        1;


    VkCommandBuffer cmd =
        VK_NULL_HANDLE;

    check(
        vkAllocateCommandBuffers(
            g_device,
            &allocateInfo,
            &cmd
        ),
        "allocate initial BLAS command buffer failed"
    );


    VkCommandBufferBeginInfo beginInfo{
        VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO
    };

    beginInfo.flags =
        VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;

    check(
        vkBeginCommandBuffer(
            cmd,
            &beginInfo
        ),
        "begin initial BLAS command buffer failed"
    );

    cmdBuild(
        cmd,
        1,
        &buildInfo,
        ranges
    );

    check(
        vkEndCommandBuffer(cmd),
        "end initial BLAS command buffer failed"
    );


    VkSubmitInfo submitInfo{
        VK_STRUCTURE_TYPE_SUBMIT_INFO
    };

    submitInfo.commandBufferCount =
        1;

    submitInfo.pCommandBuffers =
        &cmd;

    check(
        vkQueueSubmit(
            g_queue,
            1,
            &submitInfo,
            VK_NULL_HANDLE
        ),
        "submit initial BLAS build failed"
    );

    check(
        vkQueueWaitIdle(g_queue),
        "wait initial BLAS build failed"
    );

    vkFreeCommandBuffers(
        g_device,
        g_rtCommandPool,
        1,
        &cmd
    );


    VkAccelerationStructureDeviceAddressInfoKHR addressInfo{
        VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_DEVICE_ADDRESS_INFO_KHR
    };

    addressInfo.accelerationStructure =
        g_blas;

    g_blasDeviceAddress =
        getASAddress(
            g_device,
            &addressInfo
        );
}

// === 新增：创建 Camera UBO ===
static void createCameraUBO() {
    createBuffer(
        g_device,
        g_physicalDevice,
        sizeof(CameraUBO),
        VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
        g_cameraUboBuffer,
        g_cameraUboMemory
    );
    // 持久化映射，方便每帧极速更新
    check(
        vkMapMemory(g_device, g_cameraUboMemory, 0, sizeof(CameraUBO), 0, &g_cameraUboMapped),
        "Failed to map Camera UBO memory"
    );
    // 初始化时注入顶点缓冲区物理地址
    CameraUBO* ubo = static_cast<CameraUBO*>(g_cameraUboMapped);
    ubo->vertexBufferAddrLo = static_cast<uint32_t>(g_testVertexDeviceAddress & 0xFFFFFFFF);
    ubo->vertexBufferAddrHi = static_cast<uint32_t>(g_testVertexDeviceAddress >> 32);
    ubo->padding = 0u;

    // historyParams.z in GLSL. historyParams.w starts at sunrise until the
    // first Java camera update provides the current Minecraft clock phase.
    ubo->historyParamsPadding0 =
        g_pomDepth.load();

    ubo->historyParamsPadding1 =
        0.0f;

    ubo->playerX = 0.0f;
    ubo->playerY = 0.0f;
    ubo->playerZ = 0.0f;
    ubo->playerInWater = 0.0f;
    ubo->playerVelocityX = 0.0f;
    ubo->playerVelocityY = 0.0f;
    ubo->playerVelocityZ = 0.0f;
    ubo->playerHorizontalSpeed = 0.0f;
    ubo->waterSurfaceY = 0.0f;
    ubo->waterSimOriginX = 0.0f;
    ubo->waterSimOriginZ = 0.0f;
    ubo->waterSimTexelWorldSize = WATER_SIM_TEXEL_WORLD_SIZE;
    ubo->waterSimReadLayer = 0u;
    ubo->waterSimEnabled = 0u;
    ubo->waterSimImpulsePending = 0u;
    ubo->cameraInWater = 0u;
    ubo->waterSimImpulseX = 0.0f;
    ubo->waterSimImpulseZ = 0.0f;
    ubo->waterSimImpulseStrength = 0.0f;
    ubo->waterSimImpulseSurfaceY = 0.0f;

    // Default to overworld with clear weather so volumetric clouds
    // are visible immediately before the first Java camera update.
    ubo->weatherParams[0] = 0.0f;  // rain
    ubo->weatherParams[1] = 0.0f;  // thunder
    ubo->weatherParams[2] = 1.0f;  // dimension code (overworld)
    ubo->weatherParams[3] = 0.0f;

    populateCloudParams(ubo->cloudParams);
    populateSkyParams(ubo->skyParams);
}

static void destroySectionOpacityMicromap(RTSectionAS& section)
{
    if(section.opacityMicromap != VK_NULL_HANDLE)
    {
        if(auto destroyMicromap = getDestroyMicromap())
            destroyMicromap(g_device, section.opacityMicromap, nullptr);
    }
    if(section.ommScratchBuffer != VK_NULL_HANDLE)
        vkDestroyBuffer(g_device, section.ommScratchBuffer, nullptr);
    if(section.ommScratchMemory != VK_NULL_HANDLE)
        vkFreeMemory(g_device, section.ommScratchMemory, nullptr);
    if(section.ommStorageBuffer != VK_NULL_HANDLE)
        vkDestroyBuffer(g_device, section.ommStorageBuffer, nullptr);
    if(section.ommStorageMemory != VK_NULL_HANDLE)
        vkFreeMemory(g_device, section.ommStorageMemory, nullptr);
    if(section.ommTriangleBuffer != VK_NULL_HANDLE)
        vkDestroyBuffer(g_device, section.ommTriangleBuffer, nullptr);
    if(section.ommTriangleMemory != VK_NULL_HANDLE)
        vkFreeMemory(g_device, section.ommTriangleMemory, nullptr);
    if(section.ommDataBuffer != VK_NULL_HANDLE)
        vkDestroyBuffer(g_device, section.ommDataBuffer, nullptr);
    if(section.ommDataMemory != VK_NULL_HANDLE)
        vkFreeMemory(g_device, section.ommDataMemory, nullptr);

    section.ommDataBuffer = VK_NULL_HANDLE;
    section.ommDataMemory = VK_NULL_HANDLE;
    section.ommDataAddress = 0;
    section.ommTriangleBuffer = VK_NULL_HANDLE;
    section.ommTriangleMemory = VK_NULL_HANDLE;
    section.ommTriangleAddress = 0;
    section.ommStorageBuffer = VK_NULL_HANDLE;
    section.ommStorageMemory = VK_NULL_HANDLE;
    section.ommScratchBuffer = VK_NULL_HANDLE;
    section.ommScratchMemory = VK_NULL_HANDLE;
    section.ommScratchAddress = 0;
    section.opacityMicromap = VK_NULL_HANDLE;
    section.ommUsage = {};
    section.hasOpacityMicromap = false;
}

static void deferSectionOpacityMicromapBuildInputs(RTSectionAS& section)
{
    if(!section.hasOpacityMicromap ||
       (section.ommDataBuffer == VK_NULL_HANDLE &&
        section.ommTriangleBuffer == VK_NULL_HANDLE &&
        section.ommScratchBuffer == VK_NULL_HANDLE))
    {
        return;
    }

    DeferredSectionBuildResources::OmmBuildInputs deferred{};
    deferred.dataBuffer = section.ommDataBuffer;
    deferred.dataMemory = section.ommDataMemory;
    deferred.triangleBuffer = section.ommTriangleBuffer;
    deferred.triangleMemory = section.ommTriangleMemory;
    deferred.scratchBuffer = section.ommScratchBuffer;
    deferred.scratchMemory = section.ommScratchMemory;
    g_deferredSectionBuild.ommBuildInputs.push_back(deferred);

    section.ommDataBuffer = VK_NULL_HANDLE;
    section.ommDataMemory = VK_NULL_HANDLE;
    section.ommDataAddress = 0;
    section.ommTriangleBuffer = VK_NULL_HANDLE;
    section.ommTriangleMemory = VK_NULL_HANDLE;
    section.ommTriangleAddress = 0;
    section.ommScratchBuffer = VK_NULL_HANDLE;
    section.ommScratchMemory = VK_NULL_HANDLE;
    section.ommScratchAddress = 0;
}

// 剔除了提交逻辑，仅完成内存分配与 AS 对象句柄创建
static void destroySectionAS(RTSectionAS& section)
{
    auto destroyAS =
        getDestroyAccelerationStructure();


    if(
        section.blas != VK_NULL_HANDLE &&
        destroyAS
    )
    {
        destroyAS(
            g_device,
            section.blas,
            nullptr
        );
    }


    if(section.blasBuffer != VK_NULL_HANDLE)
    {
        vkDestroyBuffer(
            g_device,
            section.blasBuffer,
            nullptr
        );
    }


    if(section.blasMemory != VK_NULL_HANDLE)
    {
        vkFreeMemory(
            g_device,
            section.blasMemory,
            nullptr
        );
    }


    if(section.vertexBuffer != VK_NULL_HANDLE)
    {
        vkDestroyBuffer(
            g_device,
            section.vertexBuffer,
            nullptr
        );
    }


    if(section.vertexMemory != VK_NULL_HANDLE)
    {
        vkFreeMemory(
            g_device,
            section.vertexMemory,
            nullptr
        );
    }


    destroySectionOpacityMicromap(section);

    section = {};
}


static bool createSectionOpacityMicromap(
    RTSectionAS& section,
    const OmmCutoutInput& input
)
{
    constexpr uint32_t kExpectedLevel = 4u;
    constexpr uint32_t kExpectedBytesPerTriangle = 64u;
    constexpr VkDeviceAddress kInputAddressAlignment = 256u;

    if(!g_ommNativeEnabled || input.subdivisionLevel != kExpectedLevel ||
       input.triangleCount != section.triangleCount ||
       input.knownMicroTriangleCount == 0u ||
       input.data.size() != static_cast<size_t>(input.triangleCount) *
                            kExpectedBytesPerTriangle)
    {
        return false;
    }

    const VkBufferUsageFlags inputUsage =
        VK_BUFFER_USAGE_MICROMAP_BUILD_INPUT_READ_ONLY_BIT_EXT |
        VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

    createBuffer(g_device, g_physicalDevice, input.data.size(), inputUsage,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
        section.ommDataBuffer, section.ommDataMemory);
    void* mapped = nullptr;
    check(vkMapMemory(g_device, section.ommDataMemory, 0, input.data.size(),
        0, &mapped), "map Section OMM data failed");
    std::memcpy(mapped, input.data.data(), input.data.size());
    vkUnmapMemory(g_device, section.ommDataMemory);
    section.ommDataAddress = getBufferDeviceAddress(g_device, section.ommDataBuffer);

    std::vector<VkMicromapTriangleEXT> triangles(input.triangleCount);
    for(uint32_t triangle = 0; triangle < input.triangleCount; ++triangle)
    {
        triangles[triangle].dataOffset = triangle * kExpectedBytesPerTriangle;
        triangles[triangle].subdivisionLevel = static_cast<uint16_t>(kExpectedLevel);
        triangles[triangle].format =
            static_cast<uint16_t>(VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT);
    }
    const VkDeviceSize triangleBytes =
        static_cast<VkDeviceSize>(triangles.size() * sizeof(VkMicromapTriangleEXT));
    createBuffer(g_device, g_physicalDevice, triangleBytes, inputUsage,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
        section.ommTriangleBuffer, section.ommTriangleMemory);
    mapped = nullptr;
    check(vkMapMemory(g_device, section.ommTriangleMemory, 0, triangleBytes,
        0, &mapped), "map Section OMM triangle records failed");
    std::memcpy(mapped, triangles.data(), static_cast<size_t>(triangleBytes));
    vkUnmapMemory(g_device, section.ommTriangleMemory);
    section.ommTriangleAddress =
        getBufferDeviceAddress(g_device, section.ommTriangleBuffer);

    if((section.ommDataAddress & (kInputAddressAlignment - 1u)) != 0u ||
       (section.ommTriangleAddress & (kInputAddressAlignment - 1u)) != 0u)
    {
        RTLog("[OMM NATIVE] sectionInputAlignment=0 fallback=anyHit");
        destroySectionOpacityMicromap(section);
        return false;
    }

    section.ommUsage.count = input.triangleCount;
    section.ommUsage.subdivisionLevel = kExpectedLevel;
    section.ommUsage.format = VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT;

    VkMicromapBuildInfoEXT buildInfo{};
    buildInfo.sType = VK_STRUCTURE_TYPE_MICROMAP_BUILD_INFO_EXT;
    buildInfo.type = VK_MICROMAP_TYPE_OPACITY_MICROMAP_EXT;
    buildInfo.flags = VK_BUILD_MICROMAP_PREFER_FAST_TRACE_BIT_EXT;
    buildInfo.mode = VK_BUILD_MICROMAP_MODE_BUILD_EXT;
    buildInfo.usageCountsCount = 1;
    buildInfo.pUsageCounts = &section.ommUsage;
    buildInfo.data.deviceAddress = section.ommDataAddress;
    buildInfo.triangleArray.deviceAddress = section.ommTriangleAddress;
    buildInfo.triangleArrayStride = sizeof(VkMicromapTriangleEXT);

    VkMicromapBuildSizesInfoEXT sizes{};
    sizes.sType = VK_STRUCTURE_TYPE_MICROMAP_BUILD_SIZES_INFO_EXT;
    getMicromapBuildSizes()(g_device,
        VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, &buildInfo, &sizes);
    if(sizes.micromapSize == 0u || sizes.buildScratchSize == 0u)
    {
        RTLog("[OMM NATIVE] buildSizes=0 fallback=anyHit");
        destroySectionOpacityMicromap(section);
        return false;
    }

    createBuffer(g_device, g_physicalDevice, sizes.micromapSize,
        VK_BUFFER_USAGE_MICROMAP_STORAGE_BIT_EXT,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
        section.ommStorageBuffer, section.ommStorageMemory);
    VkMicromapCreateInfoEXT createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_MICROMAP_CREATE_INFO_EXT;
    createInfo.buffer = section.ommStorageBuffer;
    createInfo.size = sizes.micromapSize;
    createInfo.type = VK_MICROMAP_TYPE_OPACITY_MICROMAP_EXT;
    check(getCreateMicromap()(g_device, &createInfo, nullptr,
        &section.opacityMicromap), "create Section opacity micromap failed");

    createBuffer(g_device, g_physicalDevice, sizes.buildScratchSize,
        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
        section.ommScratchBuffer, section.ommScratchMemory);
    section.ommScratchAddress =
        getBufferDeviceAddress(g_device, section.ommScratchBuffer);
    section.hasOpacityMicromap = true;
    return true;
}

static VkDeviceSize createSectionBLAS(
    RTSectionAS& section,
    uint64_t sectionKey,
    const std::vector<Vertex>& vertices,
    bool opaqueGeometry,
    const OmmCutoutInput* ommInput
)
{
    if(
        vertices.size() < 3 ||
        (vertices.size() % 3) != 0
    )
    {
        throw std::runtime_error(
            "Section BLAS vertex count must be a non-zero multiple of 3"
        );
    }


    if(
        vertices.size() >
        static_cast<size_t>(
            std::numeric_limits<uint32_t>::max()
        )
    )
    {
        throw std::runtime_error(
            "Section BLAS vertex count exceeds uint32_t"
        );
    }


    section = {};
    section.sectionKey = sectionKey;
    section.triangleCount =
        static_cast<uint32_t>(
            vertices.size() / 3
        );

    section.vertexBytes =
        static_cast<VkDeviceSize>(
            vertices.size()
        ) *
        sizeof(Vertex);

    if(!opaqueGeometry && ommInput)
    {
        createSectionOpacityMicromap(section, *ommInput);
    }


    VkBufferCreateInfo vertexBufferInfo{
        VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO
    };

    vertexBufferInfo.size =
        section.vertexBytes;

    vertexBufferInfo.usage =
        VK_BUFFER_USAGE_TRANSFER_DST_BIT |
        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT |
        VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR |
        VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

    vertexBufferInfo.sharingMode =
        VK_SHARING_MODE_EXCLUSIVE;


    check(
        vkCreateBuffer(
            g_device,
            &vertexBufferInfo,
            nullptr,
            &section.vertexBuffer
        ),
        "create Section vertex buffer failed"
    );


    VkMemoryRequirements vertexRequirements{};

    vkGetBufferMemoryRequirements(
        g_device,
        section.vertexBuffer,
        &vertexRequirements
    );


    VkAccelerationStructureGeometryTrianglesDataKHR triangles{
        VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_TRIANGLES_DATA_KHR
    };

    triangles.vertexFormat =
        VK_FORMAT_R32G32B32_SFLOAT;

    // Build-size queries do not consume the vertex data address.
    triangles.vertexData.deviceAddress = 0;

    triangles.vertexStride =
        sizeof(Vertex);

    triangles.maxVertex =
        section.triangleCount * 3u - 1u;

    triangles.indexType =
        VK_INDEX_TYPE_NONE_KHR;


    VkAccelerationStructureGeometryKHR geometry{
        VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR
    };

    geometry.geometryType =
        VK_GEOMETRY_TYPE_TRIANGLES_KHR;

    // SOLID geometry contains no alpha-tested pixels.  Marking it opaque lets
    // traversal skip .rahit entirely; cutout/water/glass stay unchanged.
    geometry.flags =
        opaqueGeometry
            ? VK_GEOMETRY_OPAQUE_BIT_KHR
            : 0;

    geometry.geometry.triangles =
        triangles;

    VkAccelerationStructureTrianglesOpacityMicromapEXT ommAttachment{};
    if(section.hasOpacityMicromap)
    {
        ommAttachment.sType =
            VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_TRIANGLES_OPACITY_MICROMAP_EXT;
        ommAttachment.indexType = VK_INDEX_TYPE_NONE_KHR;
        ommAttachment.baseTriangle = 0u;
        ommAttachment.usageCountsCount = 1u;
        ommAttachment.pUsageCounts = &section.ommUsage;
        ommAttachment.micromap = section.opacityMicromap;
        geometry.geometry.triangles.pNext = &ommAttachment;
    }


    VkAccelerationStructureBuildGeometryInfoKHR buildInfo{
        VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR
    };

    buildInfo.type =
        VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR;

    const uint64_t sectionClass =
        sectionKey & ~SPATIAL_SECTION_KEY_MASK;
    const bool dynamicSection =
        sectionClass == SECTION_AS_CLASS_ENTITY ||
        sectionClass == SECTION_AS_CLASS_LOCAL_PLAYER ||
        sectionClass == SECTION_AS_CLASS_PARTICLE;
    buildInfo.flags =
        dynamicSection
            ? VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR
            : VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;

    buildInfo.mode =
        VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR;

    buildInfo.geometryCount = 1;
    buildInfo.pGeometries = &geometry;


    VkAccelerationStructureBuildSizesInfoKHR sizesInfo{
        VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR
    };


    auto getBuildSizes =
        getGetAccelerationStructureBuildSizes();

    auto createAS =
        getCreateAccelerationStructure();

    auto getASAddress =
        getGetAccelerationStructureDeviceAddress();


    if(
        !getBuildSizes ||
        !createAS ||
        !getASAddress
    )
    {
        throw std::runtime_error(
            "Section BLAS Vulkan functions unavailable"
        );
    }


    const uint32_t primitiveCount =
        section.triangleCount;


    getBuildSizes(
        g_device,
        VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
        &buildInfo,
        &primitiveCount,
        &sizesInfo
    );


    VkBufferCreateInfo blasBufferInfo{
        VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO
    };

    blasBufferInfo.size =
        sizesInfo.accelerationStructureSize;

    blasBufferInfo.usage =
        VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR |
        VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

    blasBufferInfo.sharingMode =
        VK_SHARING_MODE_EXCLUSIVE;


    check(
        vkCreateBuffer(
            g_device,
            &blasBufferInfo,
            nullptr,
            &section.blasBuffer
        ),
        "create Section BLAS storage buffer failed"
    );


    VkMemoryRequirements blasRequirements{};

    vkGetBufferMemoryRequirements(
        g_device,
        section.blasBuffer,
        &blasRequirements
    );


    const uint32_t commonMemoryTypeBits =
        vertexRequirements.memoryTypeBits &
        blasRequirements.memoryTypeBits;


    if(commonMemoryTypeBits == 0)
    {
        throw std::runtime_error(
            "Section vertex and BLAS buffers have no common memory type"
        );
    }


    const VkDeviceSize blasAlignment =
        std::max<VkDeviceSize>(
            blasRequirements.alignment,
            1
        );


    if(
        vertexRequirements.size >
        std::numeric_limits<VkDeviceSize>::max() -
            (blasAlignment - 1)
    )
    {
        throw std::runtime_error(
            "Section shared allocation alignment overflow"
        );
    }


    const VkDeviceSize blasMemoryOffset =
        (
            (
                vertexRequirements.size +
                blasAlignment -
                1
            ) /
            blasAlignment
        ) *
        blasAlignment;


    if(
        blasMemoryOffset >
        std::numeric_limits<VkDeviceSize>::max() -
            blasRequirements.size
    )
    {
        throw std::runtime_error(
            "Section shared allocation size overflow"
        );
    }


    VkMemoryAllocateFlagsInfo allocateFlags{
        VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO
    };

    allocateFlags.flags =
        VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT;


    VkMemoryAllocateInfo allocateInfo{
        VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO
    };

    allocateInfo.pNext =
        &allocateFlags;

    allocateInfo.allocationSize =
        blasMemoryOffset +
        blasRequirements.size;

    allocateInfo.memoryTypeIndex =
        findMemoryType(
            g_physicalDevice,
            commonMemoryTypeBits,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
        );


    check(
        vkAllocateMemory(
            g_device,
            &allocateInfo,
            nullptr,
            &section.vertexMemory
        ),
        "allocate shared Section vertex/BLAS memory failed"
    );


    check(
        vkBindBufferMemory(
            g_device,
            section.vertexBuffer,
            section.vertexMemory,
            0
        ),
        "bind Section vertex buffer memory failed"
    );


    check(
        vkBindBufferMemory(
            g_device,
            section.blasBuffer,
            section.vertexMemory,
            blasMemoryOffset
        ),
        "bind Section BLAS buffer memory failed"
    );


    // Both buffers share section.vertexMemory. blasMemory intentionally
    // remains null so destruction frees the allocation exactly once.
    section.blasMemory =
        VK_NULL_HANDLE;


    section.vertexAddress =
        getBufferDeviceAddress(
            g_device,
            section.vertexBuffer
        );


    VkAccelerationStructureCreateInfoKHR createInfo{
        VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR
    };

    createInfo.buffer =
        section.blasBuffer;

    createInfo.offset = 0;

    createInfo.size =
        sizesInfo.accelerationStructureSize;

    createInfo.type =
        VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR;


    check(
        createAS(
            g_device,
            &createInfo,
            nullptr,
            &section.blas
        ),
        "create Section BLAS failed"
    );


    VkAccelerationStructureDeviceAddressInfoKHR addressInfo{
        VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_DEVICE_ADDRESS_INFO_KHR
    };

    addressInfo.accelerationStructure =
        section.blas;


    section.blasAddress =
        getASAddress(
            g_device,
            &addressInfo
        );

    section.buildScratchSize =
        sizesInfo.buildScratchSize;


    return section.buildScratchSize;
}
static void recordSectionBLASBuild(
    VkCommandBuffer cmd,
    const RTSectionAS& section,
    VkDeviceAddress scratchAddress
)
{
    VkAccelerationStructureGeometryTrianglesDataKHR triangles{
        VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_TRIANGLES_DATA_KHR
    };

    triangles.vertexFormat =
        VK_FORMAT_R32G32B32_SFLOAT;

    triangles.vertexData.deviceAddress =
        section.vertexAddress;

    triangles.vertexStride =
        sizeof(Vertex);

    triangles.maxVertex =
        section.triangleCount * 3u - 1u;

    triangles.indexType =
        VK_INDEX_TYPE_NONE_KHR;


    VkAccelerationStructureGeometryKHR geometry{
        VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR
    };

    geometry.geometryType =
        VK_GEOMETRY_TYPE_TRIANGLES_KHR;

    // Preserve the opaque classification during the actual build command.
    // Without this, solid terrain still enters any-hit processing even though
    // createSectionBLAS() correctly classified it as opaque.
    const uint64_t sectionClass =
        section.sectionKey & ~SPATIAL_SECTION_KEY_MASK;
    geometry.flags =
        sectionClass == SECTION_AS_CLASS_OPAQUE
            ? VK_GEOMETRY_OPAQUE_BIT_KHR
            : 0;

    geometry.geometry.triangles =
        triangles;

    VkAccelerationStructureTrianglesOpacityMicromapEXT ommAttachment{};
    if(section.hasOpacityMicromap)
    {
        ommAttachment.sType =
            VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_TRIANGLES_OPACITY_MICROMAP_EXT;
        ommAttachment.indexType = VK_INDEX_TYPE_NONE_KHR;
        ommAttachment.baseTriangle = 0u;
        ommAttachment.usageCountsCount = 1u;
        ommAttachment.pUsageCounts = &section.ommUsage;
        ommAttachment.micromap = section.opacityMicromap;
        geometry.geometry.triangles.pNext = &ommAttachment;
    }


    VkAccelerationStructureBuildGeometryInfoKHR buildInfo{
        VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR
    };

    buildInfo.type =
        VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR;

    const bool dynamicSection =
        (section.sectionKey & ~SPATIAL_SECTION_KEY_MASK) == SECTION_AS_CLASS_ENTITY ||
        (section.sectionKey & ~SPATIAL_SECTION_KEY_MASK) == SECTION_AS_CLASS_LOCAL_PLAYER ||
        (section.sectionKey & ~SPATIAL_SECTION_KEY_MASK) == SECTION_AS_CLASS_PARTICLE;
    buildInfo.flags =
        dynamicSection
            ? VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR
            : VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;

    buildInfo.mode =
        VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR;

    buildInfo.dstAccelerationStructure =
        section.blas;

    buildInfo.geometryCount = 1;
    buildInfo.pGeometries = &geometry;

    buildInfo.scratchData.deviceAddress =
        scratchAddress;


    VkAccelerationStructureBuildRangeInfoKHR range{};

    range.primitiveCount =
        section.triangleCount;


    const VkAccelerationStructureBuildRangeInfoKHR* ranges[] =
    {
        &range
    };


    auto cmdBuild =
        getCmdBuildAccelerationStructures();


    if(!cmdBuild)
    {
        throw std::runtime_error(
            "vkCmdBuildAccelerationStructuresKHR unavailable"
        );
    }

    if(section.hasOpacityMicromap)
    {
        auto cmdBuildMicromaps = getCmdBuildMicromaps();
        auto cmdBarrier2 = getCmdPipelineBarrier2Omm();
        if(!cmdBuildMicromaps || !cmdBarrier2)
            throw std::runtime_error("Opacity micromap build functions unavailable");

        VkMicromapBuildInfoEXT micromapBuild{};
        micromapBuild.sType = VK_STRUCTURE_TYPE_MICROMAP_BUILD_INFO_EXT;
        micromapBuild.type = VK_MICROMAP_TYPE_OPACITY_MICROMAP_EXT;
        micromapBuild.flags = VK_BUILD_MICROMAP_PREFER_FAST_TRACE_BIT_EXT;
        micromapBuild.mode = VK_BUILD_MICROMAP_MODE_BUILD_EXT;
        micromapBuild.dstMicromap = section.opacityMicromap;
        micromapBuild.usageCountsCount = 1u;
        micromapBuild.pUsageCounts = &section.ommUsage;
        micromapBuild.data.deviceAddress = section.ommDataAddress;
        micromapBuild.scratchData.deviceAddress = section.ommScratchAddress;
        micromapBuild.triangleArray.deviceAddress = section.ommTriangleAddress;
        micromapBuild.triangleArrayStride = sizeof(VkMicromapTriangleEXT);
        cmdBuildMicromaps(cmd, 1u, &micromapBuild);

        OmmMemoryBarrier2 micromapBarrier{};
        micromapBarrier.sType = OMM_STRUCTURE_TYPE_MEMORY_BARRIER_2;
        micromapBarrier.srcStageMask = OMM_STAGE_MICROMAP_BUILD;
        micromapBarrier.srcAccessMask = OMM_ACCESS_MICROMAP_WRITE;
        micromapBarrier.dstStageMask = OMM_STAGE_AS_BUILD;
        micromapBarrier.dstAccessMask = OMM_ACCESS_AS_READ;
        OmmDependencyInfo dependency{};
        dependency.sType = OMM_STRUCTURE_TYPE_DEPENDENCY_INFO;
        dependency.memoryBarrierCount = 1u;
        dependency.pMemoryBarriers = &micromapBarrier;
        cmdBarrier2(cmd, &dependency);
    }


    cmdBuild(
        cmd,
        1,
        &buildInfo,
        ranges
    );
}


static void destroySectionVertexAddressBuffer()
{
    if(g_sectionVertexAddressBuffer != VK_NULL_HANDLE)
    {
        vkDestroyBuffer(
            g_device,
            g_sectionVertexAddressBuffer,
            nullptr
        );

        g_sectionVertexAddressBuffer =
            VK_NULL_HANDLE;
    }


    if(g_sectionVertexAddressMemory != VK_NULL_HANDLE)
    {
        vkFreeMemory(
            g_device,
            g_sectionVertexAddressMemory,
            nullptr
        );

        g_sectionVertexAddressMemory =
            VK_NULL_HANDLE;
    }


    g_sectionVertexAddressCapacity = 0;
}


static void updateSectionVertexAddressDescriptor()
{
    if(
        g_rayTracingDescriptorSet == VK_NULL_HANDLE ||
        g_sectionVertexAddressBuffer == VK_NULL_HANDLE ||
        g_sectionVertexAddressCapacity == 0
    )
    {
        return;
    }


    VkDescriptorBufferInfo bufferInfo{};

    bufferInfo.buffer =
        g_sectionVertexAddressBuffer;

    bufferInfo.offset = 0;

    bufferInfo.range =
        static_cast<VkDeviceSize>(
            g_sectionVertexAddressCapacity
        ) *
        sizeof(GPUSectionVertexAddress);


    VkWriteDescriptorSet write{
        VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET
    };

    write.dstSet =
        g_rayTracingDescriptorSet;

    write.dstBinding = 21;
    write.descriptorCount = 1;

    write.descriptorType =
        VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;

    write.pBufferInfo =
        &bufferInfo;


    vkUpdateDescriptorSets(
        g_device,
        1,
        &write,
        0,
        nullptr
    );
}


static void rebuildSectionVertexAddressBuffer(
    const std::vector<RTSectionAS*>& orderedSections
)
{
    std::vector<GPUSectionVertexAddress> addresses;


    if(orderedSections.empty())
    {
        GPUSectionVertexAddress placeholder{};

        placeholder.addressLo =
            static_cast<uint32_t>(
                g_testVertexDeviceAddress &
                0xFFFFFFFFull
            );

        placeholder.addressHi =
            static_cast<uint32_t>(
                g_testVertexDeviceAddress >>
                32
            );

        addresses.push_back(
            placeholder
        );
    }
    else
    {
        addresses.reserve(
            orderedSections.size()
        );


        for(const RTSectionAS* section : orderedSections)
        {
            if(
                !section ||
                section->vertexAddress == 0
            )
            {
                throw std::runtime_error(
                    "Section vertex address table contains an invalid entry"
                );
            }


            GPUSectionVertexAddress entry{};

            entry.addressLo =
                static_cast<uint32_t>(
                    section->vertexAddress &
                    0xFFFFFFFFull
                );

            entry.addressHi =
                static_cast<uint32_t>(
                    section->vertexAddress >>
                    32
                );

            addresses.push_back(
                entry
            );
        }
    }


    const VkDeviceSize bufferBytes =
        static_cast<VkDeviceSize>(
            addresses.size()
        ) *
        sizeof(GPUSectionVertexAddress);

    // Section updates usually keep the same instance-table size. Reuse the
    // existing host-visible allocation and descriptor in that case; only
    // grow the buffer when the section count exceeds its capacity.
    if(
        g_sectionVertexAddressBuffer != VK_NULL_HANDLE &&
        g_sectionVertexAddressMemory != VK_NULL_HANDLE &&
        g_sectionVertexAddressCapacity >= addresses.size()
    )
    {
        void* mapped = nullptr;

        check(
            vkMapMemory(
                g_device,
                g_sectionVertexAddressMemory,
                0,
                bufferBytes,
                0,
                &mapped
            ),
            "map reusable Section vertex address buffer failed"
        );

        std::memcpy(
            mapped,
            addresses.data(),
            static_cast<size_t>(bufferBytes)
        );

        vkUnmapMemory(
            g_device,
            g_sectionVertexAddressMemory
        );

        return;
    }


    destroySectionVertexAddressBuffer();


    createBuffer(
        g_device,
        g_physicalDevice,
        bufferBytes,
        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
            VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
        g_sectionVertexAddressBuffer,
        g_sectionVertexAddressMemory
    );


    void* mapped = nullptr;

    check(
        vkMapMemory(
            g_device,
            g_sectionVertexAddressMemory,
            0,
            bufferBytes,
            0,
            &mapped
        ),
        "map Section vertex address buffer failed"
    );


    std::memcpy(
        mapped,
        addresses.data(),
        static_cast<size_t>(bufferBytes)
    );


    vkUnmapMemory(
        g_device,
        g_sectionVertexAddressMemory
    );


    g_sectionVertexAddressCapacity =
        addresses.size();


    updateSectionVertexAddressDescriptor();
}
static void createTLAS(){
    std::cout << "[RT] createTLAS ENTER\n";
    std::cout
<< "TLAS BUILT FLAG="
<< g_asBuilt
<< "\n";
//     std::cout<<"[RT INIT] createTLAS ENTER\n";
    auto sizes=getGetAccelerationStructureBuildSizes();auto createAS=getCreateAccelerationStructure();auto getAddr=getGetAccelerationStructureDeviceAddress();
    if(!sizes||!createAS||!getAddr)throw std::runtime_error("TLAS functions unavailable");

    VkAccelerationStructureInstanceKHR inst{};
    // 修正 3x4 仿射变换矩阵，设为单位矩阵，严格避免内存越界
    inst.transform.matrix[0][0]=1.0f;
    inst.transform.matrix[1][1]=1.0f;
    inst.transform.matrix[2][2]=1.0f;

    inst.mask=0xff;
    inst.flags = VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR | VK_GEOMETRY_INSTANCE_FORCE_NO_OPAQUE_BIT_KHR;
     std::cout
<< "BLAS ADDRESS="
<< g_blasDeviceAddress
<< "\n";
    inst.accelerationStructureReference=g_blasDeviceAddress;
    createBuffer(
    g_device,
    g_physicalDevice,
    sizeof(inst),
    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR |
    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
    VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
    g_tlasInstanceBuffer,
    g_tlasInstanceMemory
);
    void* mapped=nullptr;check(vkMapMemory(g_device,g_tlasInstanceMemory,0,sizeof(inst),0,&mapped),"Failed to map TLAS instance buffer");std::memcpy(mapped,&inst,sizeof(inst));vkUnmapMemory(g_device,g_tlasInstanceMemory);
    g_tlasInstanceDeviceAddress=getBufferDeviceAddress(g_device,g_tlasInstanceBuffer);
    VkAccelerationStructureGeometryInstancesDataKHR id{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_INSTANCES_DATA_KHR};id.data.deviceAddress=g_tlasInstanceDeviceAddress;
    VkAccelerationStructureGeometryKHR geo{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR};geo.geometryType=VK_GEOMETRY_TYPE_INSTANCES_KHR;geo.geometry.instances=id;
    VkAccelerationStructureBuildGeometryInfoKHR bi{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR};bi.type=VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR;bi.flags=VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;bi.geometryCount=1;bi.pGeometries=&geo;bi.mode=VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR;
    uint32_t pc=1;VkAccelerationStructureBuildSizesInfoKHR si{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR};sizes(g_device,VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,&bi,&pc,&si);
    createBuffer(g_device,g_physicalDevice,si.accelerationStructureSize,VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR|VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,g_tlasBuffer,g_tlasMemory);
    VkAccelerationStructureCreateInfoKHR aci{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR};aci.buffer=g_tlasBuffer;aci.size=si.accelerationStructureSize;aci.type=VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR;check(createAS(g_device,&aci,nullptr,&g_tlas),"Failed to create TLAS");
     std::cout
    << "[RT INIT] TLAS created = "
    << g_tlas
    << "\n";
    createBuffer(g_device,g_physicalDevice,si.buildScratchSize,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,g_tlasScratchBuffer,g_tlasScratchMemory);
    g_tlasScratchAddress=getBufferDeviceAddress(g_device,g_tlasScratchBuffer);
    VkAccelerationStructureDeviceAddressInfoKHR ai{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_DEVICE_ADDRESS_INFO_KHR};ai.accelerationStructure=g_tlas;g_tlasDeviceAddress=getAddr(g_device,&ai);VkAccelerationStructureBuildGeometryInfoKHR buildInfo{
    VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR
};

buildInfo.type =
    VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR;

buildInfo.flags =
    VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;

buildInfo.mode =
    VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR;

buildInfo.dstAccelerationStructure =
    g_tlas;

buildInfo.geometryCount = 1;

buildInfo.pGeometries =
    &geo;

buildInfo.scratchData.deviceAddress =
    g_tlasScratchAddress;


VkAccelerationStructureBuildRangeInfoKHR range{};

range.primitiveCount = 1;


VkCommandBufferAllocateInfo cai{
    VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO
};

cai.commandPool =
    g_rtCommandPool;

cai.level =
    VK_COMMAND_BUFFER_LEVEL_PRIMARY;

cai.commandBufferCount = 1;


VkCommandBuffer cmd;

check(
    vkAllocateCommandBuffers(
        g_device,
        &cai,
        &cmd
    ),
    "allocate TLAS command buffer failed"
);


VkCommandBufferBeginInfo bi2{
    VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO
};

bi2.flags =
    VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;


check(
    vkBeginCommandBuffer(
        cmd,
        &bi2
    ),
    "begin TLAS cmd failed"
);


auto build =
reinterpret_cast<PFN_vkCmdBuildAccelerationStructuresKHR>(
    vkGetDeviceProcAddr(
        g_device,
        "vkCmdBuildAccelerationStructuresKHR"
    )
);


if(!build)
    throw std::runtime_error(
        "vkCmdBuildAccelerationStructuresKHR missing"
    );


const VkAccelerationStructureBuildRangeInfoKHR* ranges[] =
{
    &range
};


build(
    cmd,
    1,
    &buildInfo,
    ranges
);


check(
    vkEndCommandBuffer(cmd),
    "end TLAS cmd failed"
);


VkSubmitInfo submit{
    VK_STRUCTURE_TYPE_SUBMIT_INFO
};

submit.commandBufferCount = 1;
submit.pCommandBuffers = &cmd;


check(
    vkQueueSubmit(
        g_queue,
        1,
        &submit,
        VK_NULL_HANDLE
    ),
    "submit TLAS build failed"
);
     std::cout
<< "[RT] TLAS SUBMITTED\n";
//     std::cout << "[RT] TLAS SUBMITTED\n";


check(
    vkQueueWaitIdle(
        g_queue
    ),
    "wait TLAS build failed"
);
    std::cout
<< "[RT] TLAS FINISHED\n";


    vkFreeCommandBuffers(
        g_device,
        g_rtCommandPool,
        1,
        &cmd
    );

    std::cout
<< "[RT INIT] TLAS BUILD COMPLETE\n";
}

static VkDescriptorSetLayout createRTDescriptorSetLayout(){
    VkDescriptorSetLayoutBinding b[42]{}; // binding 0..41

    // 0: TLAS, 1: RT Output Image, 2: Camera UBO, 3: Albedo, 4: Normal, 5: Specular
    // ... 保持原有 0-5 的配置绝对不变 ...
    b[0].binding=0; b[0].descriptorType=VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR; b[0].descriptorCount=1; b[0].stageFlags=VK_SHADER_STAGE_RAYGEN_BIT_KHR|VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR;
    b[1].binding=1; b[1].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_IMAGE; b[1].descriptorCount=1; b[1].stageFlags=VK_SHADER_STAGE_RAYGEN_BIT_KHR | VK_SHADER_STAGE_COMPUTE_BIT;
    b[2].binding=2; b[2].descriptorType=VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER; b[2].descriptorCount=1;
    b[2].stageFlags=VK_SHADER_STAGE_RAYGEN_BIT_KHR | VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR | VK_SHADER_STAGE_ANY_HIT_BIT_KHR | VK_SHADER_STAGE_MISS_BIT_KHR | VK_SHADER_STAGE_COMPUTE_BIT;
    b[3].binding=3; b[3].descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; b[3].descriptorCount=1; b[3].stageFlags=VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR | VK_SHADER_STAGE_ANY_HIT_BIT_KHR;
    b[4].binding=4; b[4].descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; b[4].descriptorCount=1; b[4].stageFlags=VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR | VK_SHADER_STAGE_ANY_HIT_BIT_KHR;
    b[5].binding=5; b[5].descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; b[5].descriptorCount=1; b[5].stageFlags=VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR | VK_SHADER_STAGE_ANY_HIT_BIT_KHR;

    // === 新增：Binding 6 (高精度历史累积图像) ===
    b[6].binding=6; b[6].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_IMAGE; b[6].descriptorCount=1; b[6].stageFlags=VK_SHADER_STAGE_RAYGEN_BIT_KHR;
    b[7].binding = 7;
    b[7].descriptorType =
        VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    b[7].descriptorCount = 1;
    b[7].stageFlags =
        VK_SHADER_STAGE_RAYGEN_BIT_KHR;
    b[8].binding = 8;
    b[8].descriptorType =
        VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    b[8].descriptorCount = 1;
    b[8].stageFlags =
        VK_SHADER_STAGE_RAYGEN_BIT_KHR;


    b[9].binding = 9;
    b[9].descriptorType =
        VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    b[9].descriptorCount = 1;
    b[9].stageFlags =
        VK_SHADER_STAGE_RAYGEN_BIT_KHR;


    b[10].binding = 10;
    b[10].descriptorType =
        VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    b[10].descriptorCount = 1;
    b[10].stageFlags =
        VK_SHADER_STAGE_RAYGEN_BIT_KHR;


    b[11].binding = 11;
    b[11].descriptorType =
        VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    b[11].descriptorCount = 1;
    b[11].stageFlags =
        VK_SHADER_STAGE_RAYGEN_BIT_KHR;


    b[12].binding = 12;
    b[12].descriptorType =
        VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    b[12].descriptorCount = 1;
    b[12].stageFlags =
        VK_SHADER_STAGE_RAYGEN_BIT_KHR;
    // =====================================================
    // DLSS Ray Reconstruction GBuffer
    // =====================================================

    // Binding 13: Diffuse Albedo
    b[13].binding = 13;
    b[13].descriptorType =
        VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    b[13].descriptorCount = 1;
    b[13].stageFlags =
        VK_SHADER_STAGE_RAYGEN_BIT_KHR;


    // Binding 14: Specular Albedo
    b[14].binding = 14;
    b[14].descriptorType =
        VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    b[14].descriptorCount = 1;
    b[14].stageFlags =
        VK_SHADER_STAGE_RAYGEN_BIT_KHR;


    // Binding 15: Normal + Roughness
    b[15].binding = 15;
    b[15].descriptorType =
        VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    b[15].descriptorCount = 1;
    b[15].stageFlags =
        VK_SHADER_STAGE_RAYGEN_BIT_KHR;


    // Binding 16: Specular Hit Distance
    b[16].binding = 16;
    b[16].descriptorType =
        VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    b[16].descriptorCount = 1;
    b[16].stageFlags =
        VK_SHADER_STAGE_RAYGEN_BIT_KHR;


    // Binding 17: RT Local Light Storage Buffer
    b[17].binding = 17;
    b[17].descriptorType =
        VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    b[17].descriptorCount = 1;
    b[17].stageFlags =
        VK_SHADER_STAGE_RAYGEN_BIT_KHR |
        VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR;


    // Binding 18: P2B Local-Light spatial index.
    // P2B-1 binds and validates this buffer; shader lookup starts in P2B-2.
    b[18].binding = 18;
    b[18].descriptorType =
        VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    b[18].descriptorCount = 1;
    b[18].stageFlags =
        VK_SHADER_STAGE_RAYGEN_BIT_KHR;


    // Bindings 19-20: ReSTIR DI reservoir ping-pong buffers.
    // Both remain bound; RayGen chooses read/write roles from frame parity.
    b[19].binding = 19;
    b[19].descriptorType =
        VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    b[19].descriptorCount = 1;
    b[19].stageFlags =
        VK_SHADER_STAGE_RAYGEN_BIT_KHR |
        VK_SHADER_STAGE_COMPUTE_BIT;


    b[20].binding = 20;
    b[20].descriptorType =
        VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    b[20].descriptorCount = 1;
    b[20].stageFlags =
        VK_SHADER_STAGE_RAYGEN_BIT_KHR |
        VK_SHADER_STAGE_COMPUTE_BIT;


    // Binding 21: one 64-bit vertex-buffer address per TLAS instance.
    // instanceCustomIndex selects the matching Section entry in hit shaders.
    b[21].binding = 21;
    b[21].descriptorType =
        VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    b[21].descriptorCount = 1;
    b[21].stageFlags =
        VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR |
        VK_SHADER_STAGE_ANY_HIT_BIT_KHR;


    // VPT-3.2B. Bindings 22-25 are compute-owned except that RayGen reads
    // the page table and final six-direction irradiance pages at 22/23.
    b[22].binding = 22;
    b[22].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    b[22].descriptorCount = 1;
    b[22].stageFlags = VK_SHADER_STAGE_RAYGEN_BIT_KHR | VK_SHADER_STAGE_COMPUTE_BIT;

    b[23].binding = 23;
    b[23].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    b[23].descriptorCount = 1;
    b[23].stageFlags = VK_SHADER_STAGE_RAYGEN_BIT_KHR | VK_SHADER_STAGE_COMPUTE_BIT;

    // VPT-3.3: Binding 24 升级为二次漫反射定向光照缓存，供 Compute 写入并在后续供 RayGen 读取
    // VPT-3.3: Binding 24 升级为二次漫反射定向光照缓存，供 Compute 写入并在后续供 RayGen 读取
    b[24].binding = 24;
    b[24].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    b[24].descriptorCount = 1;
    b[24].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT | VK_SHADER_STAGE_RAYGEN_BIT_KHR;

    b[25].binding = 25;
    b[25].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    b[25].descriptorCount = 1;
    b[25].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    // Bindings 26-27: VPT-2.2 primary-DDA temporal history ping-pong.
    // Kept entirely separate from the existing ReSTIR DI reservoirs.
    b[26].binding = 26;
    b[26].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    b[26].descriptorCount = 1;
    b[26].stageFlags = VK_SHADER_STAGE_RAYGEN_BIT_KHR |
                       VK_SHADER_STAGE_COMPUTE_BIT;

    b[27].binding = 27;
    b[27].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    b[27].descriptorCount = 1;
    b[27].stageFlags = VK_SHADER_STAGE_RAYGEN_BIT_KHR |
                       VK_SHADER_STAGE_COMPUTE_BIT;


    // Binding 28: VPT-3.1 exact per-Section emitter index.
    // The current VPT-2.2 shaders do not read it yet; VPT-3.2 will consume
    // the same persistent buffer without changing the Java upload contract.
    b[28].binding = 28;
    b[28].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    b[28].descriptorCount = 1;
    b[28].stageFlags =
        VK_SHADER_STAGE_RAYGEN_BIT_KHR |
        VK_SHADER_STAGE_COMPUTE_BIT;


    // Binding 29: VPT-3.2 bounded nearby-emitter candidates.  This remains
    // separate from Binding 28 so future shaders can use a fixed cost without
    // losing the exact raw emitter index used to build the candidates.
    b[29].binding = 29;
    b[29].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    b[29].descriptorCount = 1;
    // VPT-3.3C.1: RayGen reads the bounded list to select a fixed number of
    // exact voxel-DDA local-light visibility paths at the actual hit point.
    b[29].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT |
                       VK_SHADER_STAGE_RAYGEN_BIT_KHR;


    // Binding 30: VPT-3.3B.1 full-resolution per-block-face direct
    // irradiance cache.  Compute writes it; RayGen reads the exact face of
    // its primary hardware-RT hit.  It is deliberately not filtered through
    // the 9^3 air-probe field.
    b[30].binding = 30;
    b[30].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    b[30].descriptorCount = 1;
    b[30].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT |
                       VK_SHADER_STAGE_RAYGEN_BIT_KHR;

    // Binding 31: VPT-3.3D.7 complete nearby source-Section directory for
    // the M=2 RayGen RIS proposals.  Binding 29 remains intact for the
    // currently paused surface-cache/secondary-bounce path.
    b[31].binding = 31;
    b[31].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    b[31].descriptorCount = 1;
    b[31].stageFlags = VK_SHADER_STAGE_RAYGEN_BIT_KHR;

    // Binding 32: one compact Alias word for every exact emitter-index entry.
    // It is read only by the active single-frame RIS RayGen path.
    b[32].binding = 32;
    b[32].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    b[32].descriptorCount = 1;
    b[32].stageFlags = VK_SHADER_STAGE_RAYGEN_BIT_KHR;

    // Binding 33: sampled persistent player-water wave state.  Closest-hit
    // reads height gradients only; it never changes water geometry.
    b[33].binding = 33;
    b[33].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    b[33].descriptorCount = 1;
    b[33].stageFlags = VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR;

    // Binding 34: local wavefront-area caustic map.  RayGen samples it only
    // for refracted sunlight on actual underwater receivers.
    b[34].binding = 34;
    b[34].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    b[34].descriptorCount = 1;
    b[34].stageFlags = VK_SHADER_STAGE_RAYGEN_BIT_KHR;

    // Binding 35: static mipmapped micro-normal texture.  RayGen uses it only
    // for water reflection and the sun specular lobe.
    b[35].binding = 35;
    b[35].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    b[35].descriptorCount = 1;
    b[35].stageFlags = VK_SHADER_STAGE_RAYGEN_BIT_KHR;

    // Binding 36: one Sampler0 texture per visible entity-model batch.
    // Fixed descriptors plus static GLSL selection avoid descriptor-indexing
    // feature requirements on the existing Java-created Vulkan device.
    b[36].binding = 36;
    b[36].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    b[36].descriptorCount = ENTITY_RT_TEXTURE_SLOT_COUNT;
    b[36].stageFlags = VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR |
                       VK_SHADER_STAGE_ANY_HIT_BIT_KHR;

    b[37].binding = 37;
    b[37].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    b[37].descriptorCount = PARTICLE_RT_TEXTURE_SLOT_COUNT;
    b[37].stageFlags = VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR |
                       VK_SHADER_STAGE_ANY_HIT_BIT_KHR;

    // Immutable-at-runtime Tyndall cloud shape/detail and curl fields.
    // RayGen samples cloud shadows; Miss integrates the sky volume.
    b[38].binding = 38;
    b[38].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    b[38].descriptorCount = 1;
    b[38].stageFlags = VK_SHADER_STAGE_RAYGEN_BIT_KHR |
                       VK_SHADER_STAGE_MISS_BIT_KHR;

    b[39].binding = 39;
    b[39].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    b[39].descriptorCount = 1;
    b[39].stageFlags = VK_SHADER_STAGE_RAYGEN_BIT_KHR |
                       VK_SHADER_STAGE_MISS_BIT_KHR;

    // Dedicated authored weather streak atlas.  It is sampled only by the
    // existing rain Any-Hit/Closest-Hit route, never by secondary rays.
    b[40].binding = 40;
    b[40].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    b[40].descriptorCount = 1;
    b[40].stageFlags = VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR |
                       VK_SHADER_STAGE_ANY_HIT_BIT_KHR;

    // Binding 41: same-frame ReSTIR DI spatial reservoir output.
    // Binding 40 remains the authored rain streak atlas.
    b[41].binding = 41;
    b[41].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    b[41].descriptorCount = 1;
    b[41].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT |
                       VK_SHADER_STAGE_RAYGEN_BIT_KHR;

    VkDescriptorSetLayoutCreateInfo ci{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    ci.bindingCount = 42;
    ci.pBindings = b;

    VkDescriptorSetLayout l=VK_NULL_HANDLE;
    check(vkCreateDescriptorSetLayout(g_device,&ci,nullptr,&l),"Failed to create descriptor set layout");
    return l;
}

static void destroyHistoryImage(
    VkImage& image,
    VkDeviceMemory& memory,
    VkImageView& view,
    VkImageLayout& layout)
{
    if(view)
    {
        vkDestroyImageView(
            g_device,
            view,
            nullptr
        );

        view =
            VK_NULL_HANDLE;
    }


    if(image)
    {
        vkDestroyImage(
            g_device,
            image,
            nullptr
        );

        image =
            VK_NULL_HANDLE;
    }


    if(memory)
    {
        vkFreeMemory(
            g_device,
            memory,
            nullptr
        );

        memory =
            VK_NULL_HANDLE;
    }


    layout =
        VK_IMAGE_LAYOUT_UNDEFINED;
}



// =====================================================
// DLSS Ray Reconstruction GBuffer image creation
// =====================================================

static void createRRGuideImage(
    uint32_t width,
    uint32_t height,
    VkFormat format,
    VkImage& image,
    VkDeviceMemory& memory,
    VkImageView& view,
    VkImageLayout& layout)
{
    VkImageCreateInfo ci{
        VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO
    };


    ci.imageType =
        VK_IMAGE_TYPE_2D;

    ci.format =
        format;

    ci.extent =
    {
        width,
        height,
        1
    };

    ci.mipLevels = 1;
    ci.arrayLayers = 1;

    ci.samples =
        VK_SAMPLE_COUNT_1_BIT;

    ci.tiling =
        VK_IMAGE_TILING_OPTIMAL;


    ci.usage =
        VK_IMAGE_USAGE_STORAGE_BIT |
        VK_IMAGE_USAGE_SAMPLED_BIT |
        VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
        VK_IMAGE_USAGE_TRANSFER_DST_BIT;


    ci.initialLayout =
        VK_IMAGE_LAYOUT_UNDEFINED;


    check(
        vkCreateImage(
            g_device,
            &ci,
            nullptr,
            &image
        ),
        "create RR guide image failed"
    );


    VkMemoryRequirements mr{};

    vkGetImageMemoryRequirements(
        g_device,
        image,
        &mr
    );


    VkMemoryAllocateInfo ai{
        VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO
    };


    ai.allocationSize =
        mr.size;

    ai.memoryTypeIndex =
        findMemoryType(
            g_physicalDevice,
            mr.memoryTypeBits,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
        );


    check(
        vkAllocateMemory(
            g_device,
            &ai,
            nullptr,
            &memory
        ),
        "allocate RR guide image memory failed"
    );


    check(
        vkBindImageMemory(
            g_device,
            image,
            memory,
            0
        ),
        "bind RR guide image memory failed"
    );


    VkImageViewCreateInfo vi{
        VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO
    };


    vi.image =
        image;

    vi.viewType =
        VK_IMAGE_VIEW_TYPE_2D;

    vi.format =
        format;


    vi.subresourceRange.aspectMask =
        VK_IMAGE_ASPECT_COLOR_BIT;

    vi.subresourceRange.baseMipLevel = 0;
    vi.subresourceRange.levelCount = 1;

    vi.subresourceRange.baseArrayLayer = 0;
    vi.subresourceRange.layerCount = 1;


    check(
        vkCreateImageView(
            g_device,
            &vi,
            nullptr,
            &view
        ),
        "create RR guide image view failed"
    );


    layout =
        VK_IMAGE_LAYOUT_UNDEFINED;
}


// =====================================================
// RR GBuffer -> RayGen descriptors 13-16
// =====================================================

static void updateRRGBufferDescriptors()
{
    if(
        g_rayTracingDescriptorSet ==
            VK_NULL_HANDLE ||

        g_rrDiffuseAlbedoView ==
            VK_NULL_HANDLE ||

        g_rrSpecularAlbedoView ==
            VK_NULL_HANDLE ||

        g_rrNormalRoughnessView ==
            VK_NULL_HANDLE ||

        g_rrSpecularHitDistanceView ==
            VK_NULL_HANDLE
    )
    {
        return;
    }


    VkDescriptorImageInfo infos[4]{};


    infos[0].imageView =
        g_rrDiffuseAlbedoView;

    infos[1].imageView =
        g_rrSpecularAlbedoView;

    infos[2].imageView =
        g_rrNormalRoughnessView;

    infos[3].imageView =
        g_rrSpecularHitDistanceView;


    for(uint32_t i = 0; i < 4; ++i)
    {
        infos[i].imageLayout =
            VK_IMAGE_LAYOUT_GENERAL;
    }


    const uint32_t bindings[4] =
    {
        13,
        14,
        15,
        16
    };


    VkWriteDescriptorSet writes[4]{};


    for(uint32_t i = 0; i < 4; ++i)
    {
        writes[i].sType =
            VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;

        writes[i].dstSet =
            g_rayTracingDescriptorSet;

        writes[i].dstBinding =
            bindings[i];

        writes[i].dstArrayElement =
            0;

        writes[i].descriptorCount =
            1;

        writes[i].descriptorType =
            VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;

        writes[i].pImageInfo =
            &infos[i];
    }


    vkUpdateDescriptorSets(
        g_device,
        4,
        writes,
        0,
        nullptr
    );


    std::cerr
        << "[RR GBUFFER DESCRIPTOR]"
        << " bindings=13,14,15,16"
        << std::endl;
}


// =====================================================
// Create / recreate all RR input GBuffer images
// at render resolution.
// =====================================================

static void createRRGBufferImages(
    uint32_t width,
    uint32_t height)
{
    const bool alreadyValid =
        g_rrDiffuseAlbedoImage !=
            VK_NULL_HANDLE &&

        g_rrSpecularAlbedoImage !=
            VK_NULL_HANDLE &&

        g_rrNormalRoughnessImage !=
            VK_NULL_HANDLE &&

        g_rrSpecularHitDistanceImage !=
            VK_NULL_HANDLE &&

        g_rrGBufferWidth ==
            width &&

        g_rrGBufferHeight ==
            height;


    if(alreadyValid)
    {
        return;
    }


    const bool hasOldResources =
        g_rrDiffuseAlbedoImage !=
            VK_NULL_HANDLE ||

        g_rrSpecularAlbedoImage !=
            VK_NULL_HANDLE ||

        g_rrNormalRoughnessImage !=
            VK_NULL_HANDLE ||

        g_rrSpecularHitDistanceImage !=
            VK_NULL_HANDLE;


    if(hasOldResources)
    {
        check(
            FGDeviceWaitIdle(
                g_device
            ),
            "wait device before RR GBuffer recreate failed"
        );
    }


    destroyHistoryImage(
        g_rrDiffuseAlbedoImage,
        g_rrDiffuseAlbedoMemory,
        g_rrDiffuseAlbedoView,
        g_rrDiffuseAlbedoLayout
    );


    destroyHistoryImage(
        g_rrSpecularAlbedoImage,
        g_rrSpecularAlbedoMemory,
        g_rrSpecularAlbedoView,
        g_rrSpecularAlbedoLayout
    );


    destroyHistoryImage(
        g_rrNormalRoughnessImage,
        g_rrNormalRoughnessMemory,
        g_rrNormalRoughnessView,
        g_rrNormalRoughnessLayout
    );


    destroyHistoryImage(
        g_rrSpecularHitDistanceImage,
        g_rrSpecularHitDistanceMemory,
        g_rrSpecularHitDistanceView,
        g_rrSpecularHitDistanceLayout
    );


    // Diffuse Albedo
    createRRGuideImage(
        width,
        height,
        VK_FORMAT_R16G16B16A16_SFLOAT,
        g_rrDiffuseAlbedoImage,
        g_rrDiffuseAlbedoMemory,
        g_rrDiffuseAlbedoView,
        g_rrDiffuseAlbedoLayout
    );


    // Specular Albedo
    createRRGuideImage(
        width,
        height,
        VK_FORMAT_R16G16B16A16_SFLOAT,
        g_rrSpecularAlbedoImage,
        g_rrSpecularAlbedoMemory,
        g_rrSpecularAlbedoView,
        g_rrSpecularAlbedoLayout
    );


    // Packed Normal + Roughness
    createRRGuideImage(
        width,
        height,
        VK_FORMAT_R16G16B16A16_SFLOAT,
        g_rrNormalRoughnessImage,
        g_rrNormalRoughnessMemory,
        g_rrNormalRoughnessView,
        g_rrNormalRoughnessLayout
    );


    // Specular Hit Distance
    createRRGuideImage(
        width,
        height,
        VK_FORMAT_R32_SFLOAT,
        g_rrSpecularHitDistanceImage,
        g_rrSpecularHitDistanceMemory,
        g_rrSpecularHitDistanceView,
        g_rrSpecularHitDistanceLayout
    );


    g_rrGBufferWidth =
        width;

    g_rrGBufferHeight =
        height;


    updateRRGBufferDescriptors();


    std::cerr
        << "[RR GBUFFER] created "
        << width
        << "x"
        << height
        << std::endl;
}


// =====================================================
// P1-1 - Raster Far-Base snapshot
//
// Destination is RGBA16F so we do not hard-code Minecraft's
// mainTarget format. vkCmdBlitImage performs the conversion.
// =====================================================

static void destroyFarRasterSnapshot()
{
    destroyHistoryImage(
        g_farRasterSnapshotImage,
        g_farRasterSnapshotMemory,
        g_farRasterSnapshotView,
        g_farRasterSnapshotLayout
    );

    g_farRasterSnapshotWidth = 0;
    g_farRasterSnapshotHeight = 0;
    g_farRasterSnapshotCaptured = false;
}


static bool ensureFarRasterSnapshot(
    uint32_t width,
    uint32_t height
)
{
    if(
        g_farRasterSnapshotImage != VK_NULL_HANDLE &&
        g_farRasterSnapshotView != VK_NULL_HANDLE &&
        g_farRasterSnapshotWidth == width &&
        g_farRasterSnapshotHeight == height
    )
    {
        return true;
    }


    VkFormatProperties props{};

    vkGetPhysicalDeviceFormatProperties(
        g_physicalDevice,
        VK_FORMAT_R16G16B16A16_SFLOAT,
        &props
    );


    if(
        (
            props.optimalTilingFeatures &
            (
                VK_FORMAT_FEATURE_BLIT_DST_BIT |
                VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT
            )
        ) !=
        (
            VK_FORMAT_FEATURE_BLIT_DST_BIT |
            VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT
        )
    )
    {
        fprintf(
            stderr,
            "[P1-1 RASTER SNAPSHOT] RGBA16F blit/sample unsupported features=0x%X\n",
            static_cast<unsigned int>(
                props.optimalTilingFeatures
            )
        );

        fflush(stderr);
        return false;
    }


    if(g_farRasterSnapshotImage != VK_NULL_HANDLE)
    {
        if(FGDeviceWaitIdle(g_device) != VK_SUCCESS)
        {
            return false;
        }

        destroyFarRasterSnapshot();
    }


    try
    {
        createRRGuideImage(
            width,
            height,
            VK_FORMAT_R16G16B16A16_SFLOAT,
            g_farRasterSnapshotImage,
            g_farRasterSnapshotMemory,
            g_farRasterSnapshotView,
            g_farRasterSnapshotLayout
        );
    }
    catch(const std::exception& e)
    {
        fprintf(
            stderr,
            "[P1-1 RASTER SNAPSHOT] create failed: %s\n",
            e.what()
        );

        fflush(stderr);

        destroyFarRasterSnapshot();
        return false;
    }

    g_farRasterSnapshotWidth = width;
    g_farRasterSnapshotHeight = height;


    fprintf(
        stderr,
        "[P1-1 RASTER SNAPSHOT] READY size=%ux%u format=R16G16B16A16_SFLOAT\n",
        width,
        height
    );

    fflush(stderr);

    return true;
}


static bool recordFarRasterSnapshot(
    VkCommandBuffer cmd,
    VkImage minecraftMainTarget,
    uint32_t width,
    uint32_t height
)
{
    if(
        cmd == VK_NULL_HANDLE ||
        minecraftMainTarget == VK_NULL_HANDLE ||
        !ensureFarRasterSnapshot(
            width,
            height
        )
    )
    {
        return false;
    }


    VkImageSubresourceRange range{};

    range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    range.baseMipLevel = 0;
    range.levelCount = 1;
    range.baseArrayLayer = 0;
    range.layerCount = 1;


    // Minecraft mainTarget is already GENERAL in the current
    // integration. Make completed raster writes visible to
    // the snapshot transfer read.
    VkImageMemoryBarrier sourceBarrier{
        VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER
    };

    sourceBarrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    sourceBarrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    sourceBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    sourceBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    sourceBarrier.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT;
    sourceBarrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    sourceBarrier.image = minecraftMainTarget;
    sourceBarrier.subresourceRange = range;


    VkImageMemoryBarrier destinationBarrier{
        VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER
    };

    destinationBarrier.oldLayout =
        g_farRasterSnapshotLayout;

    destinationBarrier.newLayout =
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;

    destinationBarrier.srcQueueFamilyIndex =
        VK_QUEUE_FAMILY_IGNORED;

    destinationBarrier.dstQueueFamilyIndex =
        VK_QUEUE_FAMILY_IGNORED;

    destinationBarrier.srcAccessMask =
        g_farRasterSnapshotLayout == VK_IMAGE_LAYOUT_UNDEFINED
            ? 0
            : VK_ACCESS_SHADER_READ_BIT;

    destinationBarrier.dstAccessMask =
        VK_ACCESS_TRANSFER_WRITE_BIT;

    destinationBarrier.image =
        g_farRasterSnapshotImage;

    destinationBarrier.subresourceRange =
        range;


    VkImageMemoryBarrier preBlit[2] =
    {
        sourceBarrier,
        destinationBarrier
    };


    vkCmdPipelineBarrier(
        cmd,
        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        0,
        0,
        nullptr,
        0,
        nullptr,
        2,
        preBlit
    );


    VkImageBlit blit{};

    blit.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    blit.srcSubresource.layerCount = 1;
    blit.srcOffsets[1] =
    {
        static_cast<int32_t>(width),
        static_cast<int32_t>(height),
        1
    };

    blit.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    blit.dstSubresource.layerCount = 1;
    blit.dstOffsets[1] =
    {
        static_cast<int32_t>(width),
        static_cast<int32_t>(height),
        1
    };


    vkCmdBlitImage(
        cmd,
        minecraftMainTarget,
        VK_IMAGE_LAYOUT_GENERAL,
        g_farRasterSnapshotImage,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        1,
        &blit,
        VK_FILTER_NEAREST
    );


    VkImageMemoryBarrier snapshotReady{
        VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER
    };

    snapshotReady.oldLayout =
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;

    snapshotReady.newLayout =
        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

    snapshotReady.srcQueueFamilyIndex =
        VK_QUEUE_FAMILY_IGNORED;

    snapshotReady.dstQueueFamilyIndex =
        VK_QUEUE_FAMILY_IGNORED;

    snapshotReady.srcAccessMask =
        VK_ACCESS_TRANSFER_WRITE_BIT;

    snapshotReady.dstAccessMask =
        VK_ACCESS_SHADER_READ_BIT;

    snapshotReady.image =
        g_farRasterSnapshotImage;

    snapshotReady.subresourceRange =
        range;


    vkCmdPipelineBarrier(
        cmd,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR |
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0,
        0,
        nullptr,
        0,
        nullptr,
        1,
        &snapshotReady
    );


    g_farRasterSnapshotLayout =
        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;


    if(!g_farRasterSnapshotCaptured)
    {
        fprintf(
            stderr,
            "[P1-1 RASTER SNAPSHOT] CAPTURE RECORDED size=%ux%u\n",
            width,
            height
        );

        fflush(stderr);

        g_farRasterSnapshotCaptured = true;
    }


    return true;
}


static void createRTOutputImage(uint32_t width,uint32_t height){
    if(g_rtOutputImage &&
       (g_rtOutputWidth != width ||
        g_rtOutputHeight != height))
    {
        g_hasPrevCameraMatrices =
            false;

        g_restirCameraHistoryValid =
            false;

        g_restirLastCameraFrame =
            -1;

        g_restirDINeedsClear =
            true;
    }


    std::cerr
<< "[RT IMAGE CREATE ENTER] old="
<< g_rtOutputImage
<< " size="
<< g_rtOutputWidth
<< "x"
<< g_rtOutputHeight
<< " new="
<< width
<< "x"
<< height
<< std::endl;
    if(g_rtOutputImage &&
       g_rtOutputWidth == width &&
       g_rtOutputHeight == height)
    {
        return;
    }


    if(g_rtOutputImage != VK_NULL_HANDLE)
    {
        check(
            FGDeviceWaitIdle(g_device),
            "wait device before RT output recreate failed"
        );
    }


    if(g_rtOutputView)
    {
        vkDestroyImageView(
            g_device,
            g_rtOutputView,
            nullptr
        );

        g_rtOutputView =
            VK_NULL_HANDLE;
    }
    if(g_rtOutputImage){vkDestroyImage(g_device,g_rtOutputImage,nullptr);g_rtOutputImage=VK_NULL_HANDLE;}
    if(g_rtOutputMemory){vkFreeMemory(g_device,g_rtOutputMemory,nullptr);g_rtOutputMemory=VK_NULL_HANDLE;}
    VkImageCreateInfo ii{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};ii.imageType=VK_IMAGE_TYPE_2D;ii.format=VK_FORMAT_R16G16B16A16_SFLOAT;;ii.extent={width,height,1};ii.mipLevels=1;ii.arrayLayers=1;ii.samples=VK_SAMPLE_COUNT_1_BIT;ii.tiling=VK_IMAGE_TILING_OPTIMAL;ii.usage=
    VK_IMAGE_USAGE_STORAGE_BIT |
    VK_IMAGE_USAGE_SAMPLED_BIT |
    VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
    VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    ii.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED;
    check(vkCreateImage(g_device,&ii,nullptr,&g_rtOutputImage),"Failed to create RT output image");
    std::cout
<<"[RT OUTPUT] image created "
<<width<<"x"<<height
<<"\n";
    VkMemoryRequirements mr{};vkGetImageMemoryRequirements(g_device,g_rtOutputImage,&mr);
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};ai.allocationSize=mr.size;ai.memoryTypeIndex=findMemoryType(g_physicalDevice,mr.memoryTypeBits,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    VkResult allocResult =
vkAllocateMemory(
    g_device,
    &ai,
    nullptr,
    &g_rtOutputMemory
);

    if(allocResult!=VK_SUCCESS)
    {
         std::cout
        <<"[RT OUTPUT] allocate failed "
        <<allocResult
        <<" size="
        <<ai.allocationSize
        <<"\n";

        vkDestroyImage(
            g_device,
            g_rtOutputImage,
            nullptr
        );

        g_rtOutputImage=VK_NULL_HANDLE;

        return;
    }check(vkBindImageMemory(g_device,g_rtOutputImage,g_rtOutputMemory,0),"Failed to bind RT output image");
    VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vi.image=g_rtOutputImage;
    vi.viewType=VK_IMAGE_VIEW_TYPE_2D;
    vi.format=VK_FORMAT_R16G16B16A16_SFLOAT;vi.subresourceRange.aspectMask=VK_IMAGE_ASPECT_COLOR_BIT;vi.subresourceRange.levelCount=1;vi.subresourceRange.layerCount=1;
    check(vkCreateImageView(g_device,&vi,nullptr,&g_rtOutputView),"Failed to create RT output view");

    // Perf-1.2: no custom temporal history images are created.

    updateRTOutputDescriptor();

    std::cout
        << "[RT] updateRTOutputDescriptor called\n";

    g_rtOutputWidth = width;
    g_rtOutputHeight = height;


    // 新创建的两个 image 实际 initialLayout 都是 UNDEFINED。
    //这里只同步 CPU 侧状态，不伪装成已经完成 layout transition。
    g_rtOutputLayout =
        VK_IMAGE_LAYOUT_UNDEFINED;
}

static void createDLSSDepthImage(
    uint32_t width,
    uint32_t height)
{
    check(
        FGDeviceWaitIdle(g_device),
        "wait device before DLSS depth recreate failed"
    );


    if(g_dlssDepthView)
    {
        vkDestroyImageView(
            g_device,
            g_dlssDepthView,
            nullptr
        );

        g_dlssDepthView =
            VK_NULL_HANDLE;
    }


    if(g_dlssDepthImage)
    {
        vkDestroyImage(
            g_device,
            g_dlssDepthImage,
            nullptr
        );

        g_dlssDepthImage =
            VK_NULL_HANDLE;
    }


    if(g_dlssDepthMemory)
    {
        vkFreeMemory(
            g_device,
            g_dlssDepthMemory,
            nullptr
        );

        g_dlssDepthMemory =
            VK_NULL_HANDLE;
    }


    g_dlssDepthLayout =
        VK_IMAGE_LAYOUT_UNDEFINED;


    VkImageCreateInfo ci{
        VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO
    };

    ci.imageType = VK_IMAGE_TYPE_2D;
    ci.format =
        VK_FORMAT_R32_SFLOAT;
    ci.extent = {width,height,1};
    ci.mipLevels = 1;
    ci.arrayLayers = 1;
    ci.samples = VK_SAMPLE_COUNT_1_BIT;
    ci.tiling = VK_IMAGE_TILING_OPTIMAL;

    ci.usage =
        VK_IMAGE_USAGE_STORAGE_BIT |
        VK_IMAGE_USAGE_SAMPLED_BIT |
        VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
        VK_IMAGE_USAGE_TRANSFER_DST_BIT;


    check(
        vkCreateImage(
            g_device,
            &ci,
            nullptr,
            &g_dlssDepthImage
        ),
        "create depth image failed"
    );


    VkMemoryRequirements mr{};

    vkGetImageMemoryRequirements(
        g_device,
        g_dlssDepthImage,
        &mr
    );


    VkMemoryAllocateInfo ai{
        VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO
    };

    ai.allocationSize = mr.size;

    ai.memoryTypeIndex =
        findMemoryType(
            g_physicalDevice,
            mr.memoryTypeBits,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
        );


    check(
        vkAllocateMemory(
            g_device,
            &ai,
            nullptr,
            &g_dlssDepthMemory
        ),
        "allocate depth memory failed"
    );


    check(
        vkBindImageMemory(
            g_device,
            g_dlssDepthImage,
            g_dlssDepthMemory,
            0
        ),
        "bind depth memory failed"
    );


    VkImageViewCreateInfo vi{
        VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO
    };

    vi.image = g_dlssDepthImage;
    vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vi.format = VK_FORMAT_R32_SFLOAT;

    vi.subresourceRange.aspectMask =
        VK_IMAGE_ASPECT_COLOR_BIT;

    vi.subresourceRange.levelCount = 1;
    vi.subresourceRange.layerCount = 1;


    check(
    vkCreateImageView(
        g_device,
        &vi,
        nullptr,
        &g_dlssDepthView
    ),
    "create depth view failed"
);


    // =====================================================
    // DLSS Depth -> RT Descriptor Binding 7
    //
    // Descriptor 声明 GENERAL。
    // 真正的 image layout 转换将在 command buffer 中完成。
    // =====================================================

    VkDescriptorImageInfo depthDescriptorInfo{};

    depthDescriptorInfo.imageView =
        g_dlssDepthView;

    depthDescriptorInfo.imageLayout =
        VK_IMAGE_LAYOUT_GENERAL;


    VkWriteDescriptorSet depthWrite{
        VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET
    };

    depthWrite.dstSet =
        g_rayTracingDescriptorSet;

    depthWrite.dstBinding =
        7;

    depthWrite.dstArrayElement =
        0;

    depthWrite.descriptorCount =
        1;

    depthWrite.descriptorType =
        VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;

    depthWrite.pImageInfo =
        &depthDescriptorInfo;


    vkUpdateDescriptorSets(
        g_device,
        1,
        &depthWrite,
        0,
        nullptr
    );


    std::cerr
        << "[DLSS DEPTH DESCRIPTOR]"
        << " binding=7"
        << " view="
        << g_dlssDepthView
        << " size="
        << width
        << "x"
        << height
        << " layout=GENERAL"
        << std::endl;


    g_dlssDepthWidth = width;
    g_dlssDepthHeight = height;

    g_dlssDepthLayout =
        VK_IMAGE_LAYOUT_UNDEFINED;


    std::cout
        << "[DLSS] Depth image created "
        << width<<"x"<<height
        << std::endl;
}

static void createMotionImage(uint32_t width,uint32_t height)
{
    if(g_motionImage &&
       g_motionWidth==width &&
       g_motionHeight==height)
        return;


    if(g_motionView != VK_NULL_HANDLE ||
       g_motionImage != VK_NULL_HANDLE ||
       g_motionMemory != VK_NULL_HANDLE)
    {
        check(
            FGDeviceWaitIdle(g_device),
            "wait device before DLSS motion recreate failed"
        );

        g_hasPrevCameraMatrices =
            false;
    }


    if(g_motionView)
    {
        vkDestroyImageView(
            g_device,
            g_motionView,
            nullptr
        );

        g_motionView =
            VK_NULL_HANDLE;
    }


    if(g_motionImage)
    {
        vkDestroyImage(
            g_device,
            g_motionImage,
            nullptr
        );

        g_motionImage =
            VK_NULL_HANDLE;
    }


    if(g_motionMemory)
    {
        vkFreeMemory(
            g_device,
            g_motionMemory,
            nullptr
        );

        g_motionMemory =
            VK_NULL_HANDLE;
    }


    g_motionWidth = 0;
    g_motionHeight = 0;

    g_motionLayout =
        VK_IMAGE_LAYOUT_UNDEFINED;


    VkImageCreateInfo ci{
        VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO
    };

    ci.imageType = VK_IMAGE_TYPE_2D;

    // Motion Vector = RG 两个 float
    ci.format = VK_FORMAT_R16G16_SFLOAT;

    ci.extent =
    {
        width,
        height,
        1
    };

    ci.mipLevels = 1;
    ci.arrayLayers = 1;
    ci.samples = VK_SAMPLE_COUNT_1_BIT;
    ci.tiling = VK_IMAGE_TILING_OPTIMAL;

    ci.usage =
        VK_IMAGE_USAGE_STORAGE_BIT |
        VK_IMAGE_USAGE_SAMPLED_BIT |
        VK_IMAGE_USAGE_TRANSFER_DST_BIT;


    VkFormatProperties motionFormatProps{};

    vkGetPhysicalDeviceFormatProperties(
        g_physicalDevice,
        VK_FORMAT_R16G16_SFLOAT,
        &motionFormatProps
    );

    if((motionFormatProps.optimalTilingFeatures &
        VK_FORMAT_FEATURE_TRANSFER_DST_BIT) == 0)
    {
        throw std::runtime_error(
            "VK_FORMAT_R16G16_SFLOAT does not support TRANSFER_DST"
        );
    }


    check(
        vkCreateImage(
            g_device,
            &ci,
            nullptr,
            &g_motionImage
        ),
        "create motion image failed"
    );



    VkMemoryRequirements mr{};

    vkGetImageMemoryRequirements(
        g_device,
        g_motionImage,
        &mr
    );


    VkMemoryAllocateInfo ai{
        VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO
    };

    ai.allocationSize = mr.size;

    ai.memoryTypeIndex =
        findMemoryType(
            g_physicalDevice,
            mr.memoryTypeBits,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
        );


    check(
        vkAllocateMemory(
            g_device,
            &ai,
            nullptr,
            &g_motionMemory
        ),
        "motion memory failed"
    );


    check(
        vkBindImageMemory(
            g_device,
            g_motionImage,
            g_motionMemory,
            0
        ),
        "bind motion failed"
    );



    VkImageViewCreateInfo vi{
        VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO
    };

    vi.image=g_motionImage;
    vi.viewType=VK_IMAGE_VIEW_TYPE_2D;
    vi.format=VK_FORMAT_R16G16_SFLOAT;

    vi.subresourceRange.aspectMask =
        VK_IMAGE_ASPECT_COLOR_BIT;

    vi.subresourceRange.levelCount=1;
    vi.subresourceRange.layerCount=1;


    check(
        vkCreateImageView(
            g_device,
            &vi,
            nullptr,
            &g_motionView
        ),
        "motion view failed"
    );


    g_motionWidth=width;
    g_motionHeight=height;
    g_motionLayout =
    VK_IMAGE_LAYOUT_UNDEFINED;


    std::cerr
    << "[DLSS] Motion image created "
    << width<<"x"<<height
    << std::endl;
}

static void createDLSSOutputImage(uint32_t width,uint32_t height)
{
    check(
        FGDeviceWaitIdle(g_device),
        "wait device before DLSS output recreate failed"
    );


    g_hasPrevCameraMatrices =
        false;


    if(g_dlssOutputView)
    {
        vkDestroyImageView(
            g_device,
            g_dlssOutputView,
            nullptr
        );

        g_dlssOutputView = VK_NULL_HANDLE;
    }


    if(g_dlssOutputImage)
    {
        vkDestroyImage(
            g_device,
            g_dlssOutputImage,
            nullptr
        );

        g_dlssOutputImage = VK_NULL_HANDLE;
    }


    if(g_dlssOutputMemory)
    {
        vkFreeMemory(
            g_device,
            g_dlssOutputMemory,
            nullptr
        );

        g_dlssOutputMemory = VK_NULL_HANDLE;
    }


    VkImageCreateInfo ci{
        VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO
    };

    ci.imageType =
        VK_IMAGE_TYPE_2D;

    ci.format =
        VK_FORMAT_R16G16B16A16_SFLOAT;

    ci.extent =
    {
        width,
        height,
        1
    };

    ci.mipLevels = 1;
    ci.arrayLayers = 1;

    ci.samples =
        VK_SAMPLE_COUNT_1_BIT;

    ci.tiling =
        VK_IMAGE_TILING_OPTIMAL;


    ci.usage =
        VK_IMAGE_USAGE_STORAGE_BIT |
        VK_IMAGE_USAGE_SAMPLED_BIT |
        VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
        VK_IMAGE_USAGE_TRANSFER_DST_BIT;


    ci.initialLayout =
        VK_IMAGE_LAYOUT_UNDEFINED;


    check(
        vkCreateImage(
            g_device,
            &ci,
            nullptr,
            &g_dlssOutputImage
        ),
        "create DLSS output image failed"
    );

    VkMemoryRequirements mr{};

    vkGetImageMemoryRequirements(
        g_device,
        g_dlssOutputImage,
        &mr
    );


    VkMemoryAllocateInfo ai{
        VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO
    };

    ai.allocationSize =
        mr.size;

    ai.memoryTypeIndex =
        findMemoryType(
            g_physicalDevice,
            mr.memoryTypeBits,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
        );


    check(
        vkAllocateMemory(
            g_device,
            &ai,
            nullptr,
            &g_dlssOutputMemory
        ),
        "allocate DLSS output memory failed"
    );


    check(
        vkBindImageMemory(
            g_device,
            g_dlssOutputImage,
            g_dlssOutputMemory,
            0
        ),
        "bind DLSS output memory failed"
    );


    VkImageViewCreateInfo vi{
        VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO
    };

    vi.image =
        g_dlssOutputImage;

    vi.viewType =
        VK_IMAGE_VIEW_TYPE_2D;

    vi.format =
        VK_FORMAT_R16G16B16A16_SFLOAT;


    vi.subresourceRange.aspectMask =
        VK_IMAGE_ASPECT_COLOR_BIT;

    vi.subresourceRange.levelCount=1;
    vi.subresourceRange.layerCount=1;


    check(
        vkCreateImageView(
            g_device,
            &vi,
            nullptr,
            &g_dlssOutputView
        ),
        "create DLSS output view failed"
    );
    updateSDRPostDescriptor();


    g_dlssOutputWidth=width;
    g_dlssOutputHeight=height;
    g_dlssOutputLayout =
    VK_IMAGE_LAYOUT_UNDEFINED;


    std::cerr
    << "[DLSS] Output image created "
    << width
    << "x"
    << height
    << std::endl;
}

// =====================================================
// RT Local Lights - L3 GPU aggregate upload
// =====================================================

static void destroyLocalLightGpuBuffer()
{
    if(
        g_localLightGpuBuffer !=
        VK_NULL_HANDLE
    )
    {
        vkDestroyBuffer(
            g_device,
            g_localLightGpuBuffer,
            nullptr
        );

        g_localLightGpuBuffer =
            VK_NULL_HANDLE;
    }


    if(
        g_localLightGpuMemory !=
        VK_NULL_HANDLE
    )
    {
        vkFreeMemory(
            g_device,
            g_localLightGpuMemory,
            nullptr
        );

        g_localLightGpuMemory =
            VK_NULL_HANDLE;
    }


    g_localLightGpuCount =
        0;
    g_localLightGpuCapacity = 0;
}


static void destroyLocalLightGridGpuBuffer()
{
    if(
        g_localLightGridGpuBuffer !=
        VK_NULL_HANDLE
    )
    {
        vkDestroyBuffer(
            g_device,
            g_localLightGridGpuBuffer,
            nullptr
        );

        g_localLightGridGpuBuffer =
            VK_NULL_HANDLE;
    }


    if(
        g_localLightGridGpuMemory !=
        VK_NULL_HANDLE
    )
    {
        vkFreeMemory(
            g_device,
            g_localLightGridGpuMemory,
            nullptr
        );

        g_localLightGridGpuMemory =
            VK_NULL_HANDLE;
    }


    g_localLightGridCellCount =
        0;

    g_localLightGridIndexCount =
        0;

    g_localLightGridHashCapacity =
        0;
    g_localLightGridGpuCapacity = 0;
}


static int32_t localLightGridFloorDiv32(
    int32_t value
)
{
    const int64_t wideValue =
        static_cast<int64_t>(value);


    if(wideValue >= 0)
    {
        return static_cast<int32_t>(
            wideValue /
            LOCAL_LIGHT_GRID_CELL_SIZE
        );
    }


    return static_cast<int32_t>(
        -(
            (-wideValue +
                LOCAL_LIGHT_GRID_CELL_SIZE - 1) /
            LOCAL_LIGHT_GRID_CELL_SIZE
        )
    );
}


static void updateLocalLightDescriptor()
{
    if(
        g_rayTracingDescriptorSet ==
            VK_NULL_HANDLE ||

        g_localLightGpuBuffer ==
            VK_NULL_HANDLE
    )
    {
        return;
    }


    VkDescriptorBufferInfo
        localLightInfo{};

    localLightInfo.buffer =
        g_localLightGpuBuffer;

    localLightInfo.offset =
        0;

    localLightInfo.range =
        VK_WHOLE_SIZE;


    VkWriteDescriptorSet
        write{
            VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET
        };

    write.dstSet =
        g_rayTracingDescriptorSet;

    write.dstBinding =
        17;

    write.descriptorCount =
        1;

    write.descriptorType =
        VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;

    write.pBufferInfo =
        &localLightInfo;


    vkUpdateDescriptorSets(
        g_device,
        1,
        &write,
        0,
        nullptr
    );
}


static void updateLocalLightGridDescriptor()
{
    if(
        g_rayTracingDescriptorSet ==
            VK_NULL_HANDLE ||

        g_localLightGridGpuBuffer ==
            VK_NULL_HANDLE
    )
    {
        return;
    }


    VkDescriptorBufferInfo
        gridInfo{};

    gridInfo.buffer =
        g_localLightGridGpuBuffer;

    gridInfo.offset =
        0;

    gridInfo.range =
        VK_WHOLE_SIZE;


    VkWriteDescriptorSet
        write{
            VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET
        };

    write.dstSet =
        g_rayTracingDescriptorSet;

    write.dstBinding =
        18;

    write.descriptorCount =
        1;

    write.descriptorType =
        VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;

    write.pBufferInfo =
        &gridInfo;


    vkUpdateDescriptorSets(
        g_device,
        1,
        &write,
        0,
        nullptr
    );
}


static void destroyReSTIRDIReservoirBuffers()
{
    for(uint32_t i = 0; i < 2; ++i)
    {
        if(g_restirDIReservoirBuffers[i] != VK_NULL_HANDLE)
        {
            vkDestroyBuffer(g_device, g_restirDIReservoirBuffers[i], nullptr);
            g_restirDIReservoirBuffers[i] = VK_NULL_HANDLE;
        }
        if(g_restirDIReservoirMemories[i] != VK_NULL_HANDLE)
        {
            vkFreeMemory(g_device, g_restirDIReservoirMemories[i], nullptr);
            g_restirDIReservoirMemories[i] = VK_NULL_HANDLE;
        }
    }

    if(g_restirDISpatialBuffer != VK_NULL_HANDLE)
    {
        vkDestroyBuffer(g_device, g_restirDISpatialBuffer, nullptr);
        g_restirDISpatialBuffer = VK_NULL_HANDLE;
    }
    if(g_restirDISpatialMemory != VK_NULL_HANDLE)
    {
        vkFreeMemory(g_device, g_restirDISpatialMemory, nullptr);
        g_restirDISpatialMemory = VK_NULL_HANDLE;
    }

    g_restirDIWidth = 0;
    g_restirDIHeight = 0;
    g_restirDIReservoirBytes = 0;
    g_restirDINeedsClear = false;
    g_restirCameraHistoryValid = false;
    g_restirLastCameraFrame = -1;
}


static void updateReSTIRDIReservoirDescriptors()
{
    if(
        g_rayTracingDescriptorSet == VK_NULL_HANDLE ||
        g_restirDIReservoirBuffers[0] == VK_NULL_HANDLE ||
        g_restirDIReservoirBuffers[1] == VK_NULL_HANDLE ||
        g_restirDISpatialBuffer == VK_NULL_HANDLE
    )
    {
        return;
    }

    VkDescriptorBufferInfo infos[3]{};
    VkWriteDescriptorSet writes[3]{};
    const uint32_t bindings[3] = {19u, 20u, 41u};
    const VkBuffer buffers[3] = {
        g_restirDIReservoirBuffers[0],
        g_restirDIReservoirBuffers[1],
        g_restirDISpatialBuffer
    };

    for(uint32_t i = 0; i < 3; ++i)
    {
        infos[i].buffer = buffers[i];
        infos[i].offset = 0;
        infos[i].range = VK_WHOLE_SIZE;

        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = g_rayTracingDescriptorSet;
        writes[i].dstBinding = bindings[i];
        writes[i].descriptorCount = 1;
        writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[i].pBufferInfo = &infos[i];
    }

    vkUpdateDescriptorSets(g_device, 3, writes, 0, nullptr);
}


static void createReSTIRDIReservoirBuffers(
    uint32_t width,
    uint32_t height
)
{
    if(width == 0u || height == 0u)
    {
        return;
    }

    if(
        g_restirDIReservoirBuffers[0] != VK_NULL_HANDLE &&
        g_restirDIReservoirBuffers[1] != VK_NULL_HANDLE &&
        g_restirDISpatialBuffer != VK_NULL_HANDLE &&
        g_restirDIWidth == width &&
        g_restirDIHeight == height
    )
    {
        return;
    }

    if(
        g_restirDIReservoirBuffers[0] != VK_NULL_HANDLE ||
        g_restirDIReservoirBuffers[1] != VK_NULL_HANDLE ||
        g_restirDISpatialBuffer != VK_NULL_HANDLE
    )
    {
        check(
            FGDeviceWaitIdle(g_device),
            "wait device before ReSTIR DI reservoir recreate failed"
        );
    }

    destroyReSTIRDIReservoirBuffers();

    const uint64_t pixelCount =
        static_cast<uint64_t>(width) * static_cast<uint64_t>(height);
    if(
        pixelCount > static_cast<uint64_t>(
            std::numeric_limits<VkDeviceSize>::max() /
            sizeof(ReSTIRDIReservoirGPU))
    )
    {
        throw std::runtime_error("ReSTIR DI reservoir size overflow");
    }

    const VkDeviceSize reservoirBytes =
        static_cast<VkDeviceSize>(pixelCount) * sizeof(ReSTIRDIReservoirGPU);

    for(uint32_t i = 0; i < 2; ++i)
    {
        createBuffer(
            g_device,
            g_physicalDevice,
            reservoirBytes,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT |
                VK_BUFFER_USAGE_TRANSFER_DST_BIT |
                VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
            g_restirDIReservoirBuffers[i],
            g_restirDIReservoirMemories[i]
        );
    }

    createBuffer(
        g_device,
        g_physicalDevice,
        reservoirBytes,
        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT |
            VK_BUFFER_USAGE_TRANSFER_DST_BIT |
            VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
        g_restirDISpatialBuffer,
        g_restirDISpatialMemory
    );

    g_restirDIWidth = width;
    g_restirDIHeight = height;
    g_restirDIReservoirBytes = reservoirBytes;
    g_restirDINeedsClear = true;
    g_restirCameraHistoryValid = false;
    g_restirLastCameraFrame = -1;

    updateReSTIRDIReservoirDescriptors();

    std::stringstream ss;
    ss << "[ReSTIR DI] reservoirs created"
       << " size=" << width << "x" << height
       << " bytesPerBuffer="
       << static_cast<unsigned long long>(reservoirBytes)
       << " bindings=19,20 spatial=41";
    RTLog(ss.str());
}


static void prepareReSTIRDIReservoirBuffers(VkCommandBuffer cmd)
{
    if(
        cmd == VK_NULL_HANDLE ||
        g_restirDIReservoirBuffers[0] == VK_NULL_HANDLE ||
        g_restirDIReservoirBuffers[1] == VK_NULL_HANDLE ||
        g_restirDISpatialBuffer == VK_NULL_HANDLE ||
        g_restirDIReservoirBytes == 0
    )
    {
        return;
    }

    VkBuffer buffers[3] = {
        g_restirDIReservoirBuffers[0],
        g_restirDIReservoirBuffers[1],
        g_restirDISpatialBuffer
    };
    VkBufferMemoryBarrier barriers[3]{};

    if(g_restirDINeedsClear)
    {
        for(uint32_t i = 0; i < 3; ++i)
        {
            vkCmdFillBuffer(cmd, buffers[i], 0, g_restirDIReservoirBytes, 0u);
            barriers[i].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
            barriers[i].srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            barriers[i].dstAccessMask =
                VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            barriers[i].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barriers[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barriers[i].buffer = buffers[i];
            barriers[i].offset = 0;
            barriers[i].size = g_restirDIReservoirBytes;
        }

        vkCmdPipelineBarrier(
            cmd,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR |
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0,
            0, nullptr,
            3, barriers,
            0, nullptr
        );

        g_restirDINeedsClear = false;
        return;
    }

    for(uint32_t i = 0; i < 3; ++i)
    {
        barriers[i].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        barriers[i].srcAccessMask =
            VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
        barriers[i].dstAccessMask =
            VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        barriers[i].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barriers[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barriers[i].buffer = buffers[i];
        barriers[i].offset = 0;
        barriers[i].size = g_restirDIReservoirBytes;
    }

    vkCmdPipelineBarrier(
        cmd,
        VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR |
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT |
            VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR |
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0,
        0, nullptr,
        3, barriers,
        0, nullptr
    );
}


static void destroyVPTDirectHistoryBuffers()
{
    for(uint32_t i = 0u; i < 2u; ++i)
    {
        if(g_vptDirectHistoryBuffers[i] != VK_NULL_HANDLE)
        {
            vkDestroyBuffer(g_device, g_vptDirectHistoryBuffers[i], nullptr);
            g_vptDirectHistoryBuffers[i] = VK_NULL_HANDLE;
        }
        if(g_vptDirectHistoryMemories[i] != VK_NULL_HANDLE)
        {
            vkFreeMemory(g_device, g_vptDirectHistoryMemories[i], nullptr);
            g_vptDirectHistoryMemories[i] = VK_NULL_HANDLE;
        }
    }
    g_vptDirectHistoryWidth = 0u;
    g_vptDirectHistoryHeight = 0u;
    g_vptDirectHistoryBytes = 0;
    g_vptDirectHistoryNeedsClear = false;
}


static void updateVPTDirectHistoryDescriptors()
{
    if(
        g_rayTracingDescriptorSet == VK_NULL_HANDLE ||
        g_vptDirectHistoryBuffers[0] == VK_NULL_HANDLE ||
        g_vptDirectHistoryBuffers[1] == VK_NULL_HANDLE
    )
    {
        return;
    }

    VkDescriptorBufferInfo infos[2]{};
    VkWriteDescriptorSet writes[2]{};
    for(uint32_t i = 0u; i < 2u; ++i)
    {
        infos[i].buffer = g_vptDirectHistoryBuffers[i];
        infos[i].offset = 0;
        infos[i].range = VK_WHOLE_SIZE;

        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = g_rayTracingDescriptorSet;
        writes[i].dstBinding = 26u + i;
        writes[i].descriptorCount = 1;
        writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[i].pBufferInfo = &infos[i];
    }
    vkUpdateDescriptorSets(g_device, 2u, writes, 0u, nullptr);
}


static void createVPTDirectHistoryBuffers(uint32_t width, uint32_t height)
{
    if(width == 0u || height == 0u)
    {
        return;
    }
    if(
        g_vptDirectHistoryBuffers[0] != VK_NULL_HANDLE &&
        g_vptDirectHistoryBuffers[1] != VK_NULL_HANDLE &&
        g_vptDirectHistoryWidth == width &&
        g_vptDirectHistoryHeight == height
    )
    {
        return;
    }
    if(
        g_vptDirectHistoryBuffers[0] != VK_NULL_HANDLE ||
        g_vptDirectHistoryBuffers[1] != VK_NULL_HANDLE
    )
    {
        check(
            FGDeviceWaitIdle(g_device),
            "wait device before VPT direct-history recreate failed"
        );
    }

    destroyVPTDirectHistoryBuffers();

    const uint64_t pixelCount =
        static_cast<uint64_t>(width) * static_cast<uint64_t>(height);
    if(
        pixelCount > static_cast<uint64_t>(
            std::numeric_limits<VkDeviceSize>::max() / sizeof(VPTDirectHistoryGPU)
        )
    )
    {
        throw std::runtime_error("VPT direct-history size overflow");
    }

    const VkDeviceSize historyBytes =
        static_cast<VkDeviceSize>(pixelCount) * sizeof(VPTDirectHistoryGPU);
    for(uint32_t i = 0u; i < 2u; ++i)
    {
        createBuffer(
            g_device,
            g_physicalDevice,
            historyBytes,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
            g_vptDirectHistoryBuffers[i],
            g_vptDirectHistoryMemories[i]
        );
    }

    g_vptDirectHistoryWidth = width;
    g_vptDirectHistoryHeight = height;
    g_vptDirectHistoryBytes = historyBytes;
    g_vptDirectHistoryNeedsClear = true;
    updateVPTDirectHistoryDescriptors();

    std::stringstream ss;
    ss << "[VPT-2.2] primary DDA history created size="
       << width << "x" << height
       << " bytesPerBuffer="
       << static_cast<unsigned long long>(historyBytes)
       << " bindings=26,27";
    RTLog(ss.str());
}


static void prepareVPTDirectHistoryBuffers(VkCommandBuffer cmd)
{
    if(
        cmd == VK_NULL_HANDLE ||
        g_vptDirectHistoryBuffers[0] == VK_NULL_HANDLE ||
        g_vptDirectHistoryBuffers[1] == VK_NULL_HANDLE ||
        g_vptDirectHistoryBytes == 0
    )
    {
        return;
    }

    VkBufferMemoryBarrier barriers[2]{};
    for(uint32_t i = 0u; i < 2u; ++i)
    {
        if(g_vptDirectHistoryNeedsClear)
        {
            vkCmdFillBuffer(
                cmd,
                g_vptDirectHistoryBuffers[i],
                0,
                g_vptDirectHistoryBytes,
                0u
            );
        }

        barriers[i].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        barriers[i].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barriers[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barriers[i].buffer = g_vptDirectHistoryBuffers[i];
        barriers[i].offset = 0;
        barriers[i].size = g_vptDirectHistoryBytes;
        barriers[i].srcAccessMask = g_vptDirectHistoryNeedsClear
            ? VK_ACCESS_TRANSFER_WRITE_BIT
            : VK_ACCESS_SHADER_WRITE_BIT;
        barriers[i].dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    }

    vkCmdPipelineBarrier(
        cmd,
        g_vptDirectHistoryNeedsClear
            ? VK_PIPELINE_STAGE_TRANSFER_BIT
            : (VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR |
               VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT),
        VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR |
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0,
        0,
        nullptr,
        2,
        barriers,
        0,
        nullptr
    );
    g_vptDirectHistoryNeedsClear = false;
}


static void rebuildLocalLightGpuBuffer()
{
    if(
        g_device ==
            VK_NULL_HANDLE ||

        g_physicalDevice ==
            VK_NULL_HANDLE ||

        g_queue ==
            VK_NULL_HANDLE ||

        g_rayTracingDescriptorSet ==
            VK_NULL_HANDLE
    )
    {
        return;
    }


    const auto perfL10RebuildStart =
        std::chrono::steady_clock::now();

    double perfL10QueueIdleMilliseconds =
        0.0;


    std::vector<GPULocalLightRecord>
        gpuLights;


    std::unordered_map<
        LocalLightGridCellCoord,
        std::vector<uint32_t>,
        LocalLightGridCellCoordHash
    > gridCells;


    {
        std::lock_guard<std::mutex>
            lock(
                g_lightMutex
            );

        gpuLights.reserve(
            g_totalLocalLights
        );

        // unordered_map iteration order is intentionally unstable.  Light
        // indices are part of a ReSTIR reservoir, so aggregate Sections in a
        // stable order to avoid invalidating history for unchanged content.
        std::vector<uint64_t>
            orderedSectionKeys;

        orderedSectionKeys.reserve(
            g_lightSections.size()
        );

        for(const auto& sectionEntry : g_lightSections)
        {
            orderedSectionKeys.push_back(
                sectionEntry.first
            );
        }

        std::sort(
            orderedSectionKeys.begin(),
            orderedSectionKeys.end()
        );

        for(uint64_t sectionKey : orderedSectionKeys)
        {
            const auto sectionIt =
                g_lightSections.find(sectionKey);

            if(sectionIt == g_lightSections.end())
            {
                continue;
            }

            for(
                const LocalLightRecord& light :
                sectionIt->second
            )
            {
                GPULocalLightRecord
                    gpuLight{};

                gpuLight.worldX =
                    static_cast<float>(
                        light.worldX
                    );

                gpuLight.worldY =
                    static_cast<float>(
                        light.worldY
                    );

                gpuLight.worldZ =
                    static_cast<float>(
                        light.worldZ
                    );

                gpuLight.emissionLevel =
                    static_cast<float>(
                        light.emissionLevel
                    );

                gpuLight.typeCode =
                    static_cast<uint32_t>(
                        std::max(
                            light.typeCode,
                            0
                        )
                    );


                if(
                    gpuLights.size() >=
                    static_cast<size_t>(
                        std::numeric_limits<uint32_t>::max()
                    )
                )
                {
                    throw std::runtime_error(
                        "RT Local Lights exceed uint32 GPU index capacity"
                    );
                }


                const uint32_t lightIndex =
                    static_cast<uint32_t>(
                        gpuLights.size()
                    );


                const LocalLightGridCellCoord cellCoord{
                    localLightGridFloorDiv32(
                        light.worldX
                    ),
                    localLightGridFloorDiv32(
                        light.worldY
                    ),
                    localLightGridFloorDiv32(
                        light.worldZ
                    )
                };


                gridCells[cellCoord].push_back(
                    lightIndex
                );

                gpuLights.push_back(
                    gpuLight
                );
            }
        }
    }


    if(
        gridCells.size() >
        static_cast<size_t>(
            std::numeric_limits<uint32_t>::max()
        )
    )
    {
        throw std::runtime_error(
            "RT Local-Light grid exceeds uint32 cell capacity"
        );
    }


    const size_t requiredHashSlots =
        std::max<size_t>(
            1,
            gridCells.size() * 2
        );


    size_t hashCapacity =
        1;


    while(hashCapacity < requiredHashSlots)
    {
        if(
            hashCapacity >
            static_cast<size_t>(
                std::numeric_limits<uint32_t>::max()
            ) /
                2
        )
        {
            throw std::runtime_error(
                "RT Local-Light grid hash capacity overflow"
            );
        }


        hashCapacity *=
            2;
    }


    std::vector<GPULocalLightGridSlot>
        gridSlots(
            hashCapacity
        );


    std::vector<uint32_t>
        gridLightIndices;

    gridLightIndices.reserve(
        gpuLights.size()
    );


    const size_t hashMask =
        hashCapacity - 1;

    // Use a stable insertion order.  Open addressing collision placement is
    // otherwise affected by unordered_map iteration and can produce a
    // different Binding-18 payload for the exact same light set.
    std::vector<LocalLightGridCellCoord>
        orderedGridCells;

    orderedGridCells.reserve(
        gridCells.size()
    );

    for(const auto& cellEntry : gridCells)
    {
        orderedGridCells.push_back(
            cellEntry.first
        );
    }

    std::sort(
        orderedGridCells.begin(),
        orderedGridCells.end(),
        [](
            const LocalLightGridCellCoord& a,
            const LocalLightGridCellCoord& b
        )
        {
            if(a.x != b.x)
            {
                return a.x < b.x;
            }

            if(a.y != b.y)
            {
                return a.y < b.y;
            }

            return a.z < b.z;
        }
    );

    for(const LocalLightGridCellCoord& gridCoord : orderedGridCells)
    {
        const auto cellIt =
            gridCells.find(gridCoord);

        if(cellIt == gridCells.end())
        {
            continue;
        }

        const LocalLightGridCellCoord& coord =
            cellIt->first;

        const std::vector<uint32_t>& cellLightIndices =
            cellIt->second;


        const uint32_t firstLightIndex =
            static_cast<uint32_t>(
                gridLightIndices.size()
            );


        if(
            cellLightIndices.size() >
            static_cast<size_t>(
                std::numeric_limits<uint32_t>::max()
            )
        )
        {
            throw std::runtime_error(
                "RT Local-Light grid cell exceeds uint32 count capacity"
            );
        }


        gridLightIndices.insert(
            gridLightIndices.end(),
            cellLightIndices.begin(),
            cellLightIndices.end()
        );


        size_t slotIndex =
            static_cast<size_t>(
                localLightGridHash32(
                    coord.x,
                    coord.y,
                    coord.z
                )
            ) &
            hashMask;


        while(gridSlots[slotIndex].occupied != 0u)
        {
            slotIndex =
                (slotIndex + 1) &
                hashMask;
        }


        GPULocalLightGridSlot& slot =
            gridSlots[slotIndex];

        slot.cellX =
            coord.x;

        slot.cellY =
            coord.y;

        slot.cellZ =
            coord.z;

        slot.occupied =
            1u;

        slot.firstLightIndex =
            firstLightIndex;

        slot.lightCount =
            static_cast<uint32_t>(
                cellLightIndices.size()
            );
    }


    ++g_localLightRevision;

    if(g_localLightRevision == 0u)
    {
        g_localLightRevision = 1u;
    }


    const GPULocalLightHeader
        header{
            static_cast<uint32_t>(
                gpuLights.size()
            ),
            g_localLightRevision,
            0u,
            0u
        };


    const VkDeviceSize
        totalBytes =
            static_cast<VkDeviceSize>(
                sizeof(GPULocalLightHeader) +
                gpuLights.size() *
                sizeof(GPULocalLightRecord)
            );


    const GPULocalLightGridHeader
        gridHeader{
            static_cast<uint32_t>(
                gridCells.size()
            ),
            static_cast<uint32_t>(
                gridLightIndices.size()
            ),
            static_cast<uint32_t>(
                hashCapacity
            ),
            LOCAL_LIGHT_GRID_SLOT_WORDS
        };


    const VkDeviceSize
        gridTotalBytes =
            static_cast<VkDeviceSize>(
                sizeof(GPULocalLightGridHeader) +
                gridSlots.size() *
                sizeof(GPULocalLightGridSlot) +
                gridLightIndices.size() *
                sizeof(uint32_t)
            );


    const bool reuseGpuBuffers =
        g_localLightGpuBuffer != VK_NULL_HANDLE &&
        g_localLightGpuMemory != VK_NULL_HANDLE &&
        g_localLightGridGpuBuffer != VK_NULL_HANDLE &&
        g_localLightGridGpuMemory != VK_NULL_HANDLE &&
        totalBytes <= g_localLightGpuCapacity &&
        gridTotalBytes <= g_localLightGridGpuCapacity;

    if(g_localLightGpuBuffer != VK_NULL_HANDLE ||
       g_localLightGridGpuBuffer != VK_NULL_HANDLE)
    {
        // Bindings 17 and 18 are read only by the RT graphics queue.  A
        // device-wide wait would also serialize unrelated RR/FG work.
        const auto perfL10QueueIdleStart =
            std::chrono::steady_clock::now();

        const bool queueWaitRequired =
            !latestFrameCompletionFenceSignaled();
        if(queueWaitRequired)
        {
            check(
                vkQueueWaitIdle(g_queue),
                "wait RT queue before Local-Light buffer rebuild failed"
            );
            destroyFrameCompletionFences();
        }

        if(queueWaitRequired)
        {
            perfL10QueueIdleMilliseconds =
                std::chrono::duration<double, std::milli>(
                    std::chrono::steady_clock::now() -
                    perfL10QueueIdleStart
                ).count();
        }
    }


    if(!reuseGpuBuffers)
    {
        destroyLocalLightGpuBuffer();
        destroyLocalLightGridGpuBuffer();

        createBuffer(
            g_device,
            g_physicalDevice,
            totalBytes,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
            VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
            g_localLightGpuBuffer,
            g_localLightGpuMemory
        );
        g_localLightGpuCapacity = totalBytes;

        createBuffer(
            g_device,
            g_physicalDevice,
            gridTotalBytes,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
            VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
            g_localLightGridGpuBuffer,
            g_localLightGridGpuMemory
        );
        g_localLightGridGpuCapacity = gridTotalBytes;
    }


    void* mapped =
        nullptr;

    check(
        vkMapMemory(
            g_device,
            g_localLightGpuMemory,
            0,
            totalBytes,
            0,
            &mapped
        ),
        "RT Local Lights L3 map failed"
    );


    std::memcpy(
        mapped,
        &header,
        sizeof(GPULocalLightHeader)
    );


    if(
        !gpuLights.empty()
    )
    {
        std::memcpy(
            static_cast<uint8_t*>(
                mapped
            ) +
            sizeof(GPULocalLightHeader),
            gpuLights.data(),
            gpuLights.size() *
            sizeof(GPULocalLightRecord)
        );
    }


    vkUnmapMemory(
        g_device,
        g_localLightGpuMemory
    );


    void* gridMapped =
        nullptr;

    check(
        vkMapMemory(
            g_device,
            g_localLightGridGpuMemory,
            0,
            gridTotalBytes,
            0,
            &gridMapped
        ),
        "RT Local-Light Grid P2B-1 map failed"
    );


    std::memcpy(
        gridMapped,
        &gridHeader,
        sizeof(GPULocalLightGridHeader)
    );


    std::memcpy(
        static_cast<uint8_t*>(
            gridMapped
        ) +
            sizeof(GPULocalLightGridHeader),
        gridSlots.data(),
        gridSlots.size() *
        sizeof(GPULocalLightGridSlot)
    );


    if(!gridLightIndices.empty())
    {
        std::memcpy(
            static_cast<uint8_t*>(
                gridMapped
            ) +
                sizeof(GPULocalLightGridHeader) +
                gridSlots.size() *
                sizeof(GPULocalLightGridSlot),
            gridLightIndices.data(),
            gridLightIndices.size() *
            sizeof(uint32_t)
        );
    }


    vkUnmapMemory(
        g_device,
        g_localLightGridGpuMemory
    );


    updateLocalLightDescriptor();

    updateLocalLightGridDescriptor();


    g_localLightGpuCount =
        gpuLights.size();

    g_localLightGridCellCount =
        gridCells.size();

    g_localLightGridIndexCount =
        gridLightIndices.size();

    g_localLightGridHashCapacity =
        hashCapacity;

    g_localLightsDirty =
        false;


    std::stringstream ss;

    ss
        << "[RT LOCAL LIGHTS L3 GPU]"
        << " lights="
        << g_localLightGpuCount
        << " bytes="
        << static_cast<unsigned long long>(
            totalBytes
        );

    RTLog(
        ss.str()
    );


    std::stringstream gridLog;

    gridLog
        << "[RT LOCAL GRID P2B-1]"
        << " cells="
        << g_localLightGridCellCount
        << " indices="
        << g_localLightGridIndexCount
        << " hashCapacity="
        << g_localLightGridHashCapacity
        << " cellSize="
        << LOCAL_LIGHT_GRID_CELL_SIZE
        << " bytes="
        << static_cast<unsigned long long>(
            gridTotalBytes
        )
        << " binding=18";

    RTLog(
        gridLog.str()
    );


    const double perfL10TotalMilliseconds =
        std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() -
            perfL10RebuildStart
        ).count();

    std::stringstream perfL10Log;
    perfL10Log
        << "[PERF L10 LOCAL REBUILD]"
        << " frame=" << g_perfL10FrameSerial
        << " totalCpuMs=" << std::fixed << std::setprecision(3)
        << perfL10TotalMilliseconds
        << " queueIdleMs=" << perfL10QueueIdleMilliseconds
        << " lights=" << g_localLightGpuCount
        << " gridCells=" << g_localLightGridCellCount
        << " gridBytes="
        << static_cast<unsigned long long>(gridTotalBytes);

    RTLog(
        perfL10Log.str()
    );
}


static void createRayTracingDescriptorPoolAndSet()
{
    VkDescriptorPoolSize ps[5] = {
        { VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, 1 },
        { VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 12 },
        { VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1 },
        { VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 28 },
        { VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 17 }
    };
    VkDescriptorPoolCreateInfo pi{ VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO };
    pi.maxSets = 1;
    pi.poolSizeCount = 5;
    pi.pPoolSizes = ps;

    check(vkCreateDescriptorPool(g_device, &pi, nullptr, &g_rayTracingDescriptorPool), "Failed to create RT descriptor pool");

    VkDescriptorSetAllocateInfo ai{ VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO };
    ai.descriptorPool = g_rayTracingDescriptorPool;
    ai.descriptorSetCount = 1;
    ai.pSetLayouts = &g_rayTracingDescriptorSetLayout;

    check(vkAllocateDescriptorSets(g_device, &ai, &g_rayTracingDescriptorSet), "Failed to allocate RT descriptor set");

    // binding 0 : TLAS
    VkWriteDescriptorSetAccelerationStructureKHR asi{ VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR };
    asi.accelerationStructureCount = 1;
    asi.pAccelerationStructures = &g_tlas;

    VkWriteDescriptorSet tlasWrite{ VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET };
    tlasWrite.pNext = &asi;
    tlasWrite.dstSet = g_rayTracingDescriptorSet;
    tlasWrite.dstBinding = 0;
    tlasWrite.descriptorCount = 1;
    tlasWrite.descriptorType = VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;

    // binding 1 : RT Output Storage Image
    VkDescriptorImageInfo imageInfo{};
    imageInfo.imageView = g_rtOutputView;
    imageInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;

    VkWriteDescriptorSet imageWrite{ VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET };
    imageWrite.dstSet = g_rayTracingDescriptorSet;
    imageWrite.dstBinding = 1;
    imageWrite.descriptorCount = 1;
    imageWrite.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    imageWrite.pImageInfo = &imageInfo;

    // binding 2 : Camera UBO
    VkDescriptorBufferInfo uboInfo{};
    uboInfo.buffer = g_cameraUboBuffer;
    uboInfo.offset = 0;
    uboInfo.range = sizeof(CameraUBO);

    VkWriteDescriptorSet uboWrite{ VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET };
    uboWrite.dstSet = g_rayTracingDescriptorSet;
    uboWrite.dstBinding = 2;
    uboWrite.descriptorCount = 1;
    uboWrite.descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    uboWrite.pBufferInfo = &uboInfo;

    // 注意：Binding 3, 4, 5 (贴图数组) 在此处不写入，它们将作为空槽存在，
    // 后续在拦截到 Minecraft 纹理加载时，再通过 vkUpdateDescriptorSets 动态填入具体索引。

    VkWriteDescriptorSet writes[3] = { tlasWrite, imageWrite, uboWrite };
    vkUpdateDescriptorSets(g_device, 3, writes, 0, nullptr);

    // Bind a valid one-entry table for the initialization BLAS/TLAS.
    // The first Section rebuild replaces it with the complete table.
    rebuildSectionVertexAddressBuffer({});

    // =====================================================
    // RT Local Lights - L3 + P2B-1
    // Bind valid minimum buffers immediately so bindings 17 and 18
    // are never left dangling before the first section upload.
    // =====================================================
    rebuildLocalLightGpuBuffer();
    createVLILightResources();
}


static void updateVLIDescriptors()
{
    if(
        g_rayTracingDescriptorSet == VK_NULL_HANDLE ||
        g_vliPageTableBuffer == VK_NULL_HANDLE ||
        g_vliPageBuffer == VK_NULL_HANDLE ||
        g_vliScratchBuffer == VK_NULL_HANDLE ||
        g_vliWorkListBuffer == VK_NULL_HANDLE ||
        g_vptEmitterIndexBuffer == VK_NULL_HANDLE ||
        g_vptEmitterAliasBuffer == VK_NULL_HANDLE ||
        g_vptCandidateBuffer == VK_NULL_HANDLE ||
        g_vptSourceDirectoryBuffer == VK_NULL_HANDLE ||
        g_vptSurfaceCacheBuffer == VK_NULL_HANDLE
    )
    {
        return;
    }

    VkDescriptorBufferInfo infos[9]{};
    infos[0] = { g_vliPageTableBuffer, 0, VK_WHOLE_SIZE };
    infos[1] = { g_vliPageBuffer, 0, VK_WHOLE_SIZE };
    infos[2] = { g_vliScratchBuffer, 0, VK_WHOLE_SIZE };
    infos[3] = { g_vliWorkListBuffer, 0, VK_WHOLE_SIZE };
    infos[4] = { g_vptEmitterIndexBuffer, 0, VK_WHOLE_SIZE };
    infos[5] = { g_vptCandidateBuffer, 0, VK_WHOLE_SIZE };
    infos[6] = { g_vptSurfaceCacheBuffer, 0, VK_WHOLE_SIZE };
    infos[7] = { g_vptSourceDirectoryBuffer, 0, VK_WHOLE_SIZE };
    infos[8] = { g_vptEmitterAliasBuffer, 0, VK_WHOLE_SIZE };

    VkWriteDescriptorSet writes[9]{};
    for(uint32_t i = 0; i < 9; ++i)
    {
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = g_rayTracingDescriptorSet;
        writes[i].dstBinding =
            i < 4u ? 22u + i :
            (i == 4u ? 28u : (i == 5u ? 29u :
                (i == 6u ? 30u : (i == 7u ? 31u : 32u))));
        writes[i].descriptorCount = 1;
        writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[i].pBufferInfo = &infos[i];
    }

    vkUpdateDescriptorSets(g_device, 9, writes, 0, nullptr);
}


static void createVLILightResources()
{
    if(
        g_vliPageBuffer != VK_NULL_HANDLE ||
        g_device == VK_NULL_HANDLE ||
        g_physicalDevice == VK_NULL_HANDLE
    )
    {
        return;
    }

    const VkBufferUsageFlags storageUsage =
        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT |
        VK_BUFFER_USAGE_TRANSFER_DST_BIT |
        VK_BUFFER_USAGE_TRANSFER_SRC_BIT;

    createBuffer(
        g_device,
        g_physicalDevice,
        static_cast<VkDeviceSize>(VLI_PAGE_TABLE_CAPACITY) * sizeof(VLITableSlot),
        storageUsage,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
        g_vliPageTableBuffer,
        g_vliPageTableMemory
    );

    createBuffer(
        g_device,
        g_physicalDevice,
        static_cast<VkDeviceSize>(VLI_MAX_SECTION_PAGES) * VLI_PAGE_WORDS * sizeof(uint32_t),
        storageUsage,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
        g_vliPageBuffer,
        g_vliPageMemory
    );

    // VPT-3.3: Binding 24 扩容为完整的 6 方向二次漫反射缓存 (71,663,616 字节)
    createBuffer(
        g_device,
        g_physicalDevice,
        static_cast<VkDeviceSize>(VLI_MAX_SECTION_PAGES) * VPT_DIRECTIONAL_CACHE_WORDS * sizeof(uint32_t),
        storageUsage,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
        g_vliScratchBuffer,
        g_vliScratchMemory
    );

    createBuffer(
        g_device,
        g_physicalDevice,
        static_cast<VkDeviceSize>(VLI_MAX_SECTION_PAGES) *
            VPT_SURFACE_FACES_PER_SECTION * sizeof(uint32_t),
        storageUsage,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
        g_vptSurfaceCacheBuffer,
        g_vptSurfaceCacheMemory
    );

    createBuffer(
        g_device,
        g_physicalDevice,
        static_cast<VkDeviceSize>(1u + VLI_MAX_SECTION_PAGES) * sizeof(uint32_t),
        storageUsage,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
        g_vliWorkListBuffer,
        g_vliWorkListMemory
    );

    createBuffer(
        g_device,
        g_physicalDevice,
        static_cast<VkDeviceSize>(VLI_MAX_SECTION_PAGES) *
            VPT_EMITTER_PAGE_WORDS * sizeof(uint32_t),
        storageUsage,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
        g_vptEmitterIndexBuffer,
        g_vptEmitterIndexMemory
    );

    createBuffer(
        g_device,
        g_physicalDevice,
        static_cast<VkDeviceSize>(VLI_MAX_SECTION_PAGES) *
            VPT_EMITTER_PAGE_WORDS * sizeof(uint32_t),
        storageUsage,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
        g_vptEmitterAliasBuffer,
        g_vptEmitterAliasMemory
    );

    createBuffer(
        g_device,
        g_physicalDevice,
        static_cast<VkDeviceSize>(VLI_MAX_SECTION_PAGES) *
            VPT_RELATED_EMITTERS_PER_SECTION *
            sizeof(VPTRelatedEmitterGPU),
        storageUsage,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
        g_vptCandidateBuffer,
        g_vptCandidateMemory
    );

    createBuffer(
        g_device,
        g_physicalDevice,
        static_cast<VkDeviceSize>(VLI_MAX_SECTION_PAGES) *
            VPT_SOURCE_DIRECTORY_ENTRIES_PER_PAGE *
            sizeof(VPTSourceDirectoryEntryGPU),
        storageUsage,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
        g_vptSourceDirectoryBuffer,
        g_vptSourceDirectoryMemory
    );

    g_vliNeedsInitialClear = true;
    updateVLIDescriptors();

    std::stringstream ss;
    ss << "[VPT-3.1] exact Section emitter index allocated bytes="
       << static_cast<unsigned long long>(
            static_cast<VkDeviceSize>(VLI_MAX_SECTION_PAGES) *
            VPT_EMITTER_PAGE_WORDS * sizeof(uint32_t)
          )
       << " binding=28";
    RTLog(ss.str());
    RTLog("[VPT-3.2] bounded Section emitter candidates allocated bytes=524288 binding=29");
    std::stringstream sourceDirectoryLog;
    sourceDirectoryLog << "[VPT-3.3D.7] source-Section directory allocated bytes="
        << static_cast<unsigned long long>(
            static_cast<VkDeviceSize>(VLI_MAX_SECTION_PAGES) *
            VPT_SOURCE_DIRECTORY_ENTRIES_PER_PAGE *
            sizeof(VPTSourceDirectoryEntryGPU)
        )
        << " maxSections=" << VPT_SOURCE_DIRECTORY_MAX_SECTIONS
        << " binding=31";
    RTLog(sourceDirectoryLog.str());
    std::stringstream directionalCacheLog;
    directionalCacheLog << "[VPT-3.2B] directional cache allocated bytes="
        << static_cast<unsigned long long>(
            static_cast<VkDeviceSize>(VLI_MAX_SECTION_PAGES) *
            VPT_DIRECTIONAL_CACHE_WORDS * sizeof(uint32_t)
        )
        << " directions=" << VPT_DIRECTION_COUNT
        << " binding=23";
    RTLog(directionalCacheLog.str());
    std::stringstream secondaryCacheLog;
    secondaryCacheLog << "[VPT-3.3] secondary directional cache allocated bytes="
        << static_cast<unsigned long long>(
            static_cast<VkDeviceSize>(VLI_MAX_SECTION_PAGES) *
            VPT_DIRECTIONAL_CACHE_WORDS * sizeof(uint32_t)
        )
        << " directions=" << VPT_DIRECTION_COUNT
        << " binding=24";
    RTLog(secondaryCacheLog.str());
    std::stringstream surfaceCacheLog;
    surfaceCacheLog << "[VPT-3.3B.1] face cache allocated bytes="
        << static_cast<unsigned long long>(
            static_cast<VkDeviceSize>(VLI_MAX_SECTION_PAGES) *
            VPT_SURFACE_FACES_PER_SECTION * sizeof(uint32_t)
        )
        << " facesPerPage=" << VPT_SURFACE_FACES_PER_SECTION
        << " binding=30";
    RTLog(surfaceCacheLog.str());
    RTLog("[VLI-1.4] persistent Section voxel buffers allocated");}


static uint32_t vliHashSectionCoord(
    int32_t x,
    int32_t y,
    int32_t z
)
{
    return localLightGridHash32(x, y, z);
}


static uint32_t vliFindTableSlot(
    int32_t sectionX,
    int32_t sectionY,
    int32_t sectionZ,
    bool& found
)
{
    const uint32_t mask = VLI_PAGE_TABLE_CAPACITY - 1u;
    uint32_t slot = vliHashSectionCoord(sectionX, sectionY, sectionZ) & mask;

    for(uint32_t probe = 0; probe < VLI_PAGE_TABLE_CAPACITY; ++probe)
    {
        const VLITableSlot& entry = g_vliTableCpu[slot];
        if(entry.pagePlusOne == 0u)
        {
            found = false;
            return slot;
        }

        if(
            entry.sectionX == sectionX &&
            entry.sectionY == sectionY &&
            entry.sectionZ == sectionZ
        )
        {
            found = true;
            return slot;
        }

        slot = (slot + 1u) & mask;
    }

    throw std::runtime_error("VLI Section page table is full");
}


static void markVLINeighborhoodDirty(
    int32_t sectionX,
    int32_t sectionY,
    int32_t sectionZ
)
{
    for(const auto& entry : g_vliSections)
    {
        const VLISectionCpuPage& page = entry.second;
        if(
            std::abs(page.sectionX - sectionX) <= 2 &&
            std::abs(page.sectionY - sectionY) <= 2 &&
            std::abs(page.sectionZ - sectionZ) <= 2
        )
        {
            g_vliDirtySolveTableSlots.insert(page.tableSlot);
        }
    }
}


static std::vector<uint32_t> buildVPTEmitterIndexWords(
    const std::vector<uint32_t>& sourceWords,
    uint32_t revision
)
{
    std::vector<uint32_t> emitterWords(VPT_EMITTER_PAGE_WORDS, 0u);
    emitterWords[1] = revision;

    uint32_t emitterCount = 0u;
    const uint32_t sourceCount = static_cast<uint32_t>(
        std::min<size_t>(sourceWords.size(), VLI_SOURCE_WORDS)
    );

    for(uint32_t sourceIndex = 0u; sourceIndex < sourceCount; ++sourceIndex)
    {
        const uint32_t source = sourceWords[sourceIndex];
        const uint32_t emission = (source >> 2u) & 15u;
        if(emission == 0u)
        {
            continue;
        }

        // Source layout is x + 16 * (z + 16 * y).
        const uint32_t localX = sourceIndex & 15u;
        const uint32_t localZ = (sourceIndex >> 4u) & 15u;
        const uint32_t localY = (sourceIndex >> 8u) & 15u;
        const uint32_t typeCode = (source >> 6u) & 7u;

        // Packed emitter record:
        // bits  0..3  local X
        // bits  4..7  local Y
        // bits  8..11 local Z
        // bits 12..15 emission level
        // bits 16..18 type code
        const uint32_t packedEmitter =
            localX |
            (localY << 4u) |
            (localZ << 8u) |
            (emission << 12u) |
            (typeCode << 16u);

        emitterWords[VPT_EMITTER_HEADER_WORDS + emitterCount] = packedEmitter;
        ++emitterCount;
    }

    emitterWords[0] = emitterCount;
    uint32_t totalEmissionPower = 0u;
    for(uint32_t emitterIndex = 0u; emitterIndex < emitterCount; ++emitterIndex)
    {
        totalEmissionPower += (emitterWords[VPT_EMITTER_HEADER_WORDS + emitterIndex] >> 12u) & 15u;
    }
    // Header word 2 was reserved.  It is the exact sum of the proposal
    // powers, allowing RayGen to reconstruct an emitter proposal PDF without
    // an extra per-emitter probability buffer.
    emitterWords[2] = totalEmissionPower;
    return emitterWords;
}

// VPT-3.3D.8: Binding 17's Section light records are the authoritative
// source of local emitters.  VLI sourceWords encode voxel occupancy/albedo
// for DDA and face exposure, but their emission bits are not populated by the
// current Java VLI upload.  Build the VPT exact emitter page from the same
// accepted records that already light ordinary opaque geometry.
static std::vector<uint32_t> buildVPTEmitterIndexWordsFromLocalLights(
    int32_t sectionX,
    int32_t sectionY,
    int32_t sectionZ,
    const std::vector<LocalLightRecord>& lights,
    uint32_t revision
)
{
    std::vector<uint32_t> emitterWords(VPT_EMITTER_PAGE_WORDS, 0u);
    emitterWords[1] = revision;

    uint32_t emitterCount = 0u;
    uint32_t totalEmissionPower = 0u;
    const int32_t baseX = sectionX * 16;
    const int32_t baseY = sectionY * 16;
    const int32_t baseZ = sectionZ * 16;

    for(const LocalLightRecord& light : lights)
    {
        if(
            light.emissionLevel <= 0 ||
            light.emissionLevel > 15 ||
            emitterCount >= VPT_MAX_EMITTERS_PER_SECTION
        )
        {
            continue;
        }

        const int32_t localX = light.worldX - baseX;
        const int32_t localY = light.worldY - baseY;
        const int32_t localZ = light.worldZ - baseZ;
        if(
            localX < 0 || localX >= 16 ||
            localY < 0 || localY >= 16 ||
            localZ < 0 || localZ >= 16
        )
        {
            continue;
        }

        const uint32_t emission = static_cast<uint32_t>(light.emissionLevel);
        const uint32_t typeCode = static_cast<uint32_t>(
            std::clamp(light.typeCode, 0, 7)
        );
        emitterWords[VPT_EMITTER_HEADER_WORDS + emitterCount] =
            static_cast<uint32_t>(localX) |
            (static_cast<uint32_t>(localY) << 4u) |
            (static_cast<uint32_t>(localZ) << 8u) |
            (emission << 12u) |
            (typeCode << 16u);
        ++emitterCount;
        totalEmissionPower += emission;
    }

    emitterWords[0] = emitterCount;
    emitterWords[2] = totalEmissionPower;
    return emitterWords;
}

static std::vector<uint32_t> buildVPTEmitterIndexWordsForSection(
    int32_t sectionX,
    int32_t sectionY,
    int32_t sectionZ,
    const std::vector<uint32_t>& sourceWords,
    const std::vector<LocalLightRecord>& localLights,
    uint32_t revision
)
{
    return localLights.empty()
        ? buildVPTEmitterIndexWords(sourceWords, revision)
        : buildVPTEmitterIndexWordsFromLocalLights(
            sectionX,
            sectionY,
            sectionZ,
            localLights,
            revision
        );
}

// One uint per compact emitter: low 16 bits are the Alias threshold and high
// 16 bits are the fallback emitter index.  The proposal probability itself is
// exactly emission / emitterWords[2], so it is never quantized.
static std::vector<uint32_t> buildVPTEmitterAliasWords(
    const std::vector<uint32_t>& emitterWords
)
{
    std::vector<uint32_t> aliasWords(VPT_EMITTER_PAGE_WORDS, 0u);
    if(emitterWords.size() != VPT_EMITTER_PAGE_WORDS) return aliasWords;

    const uint32_t count = std::min(emitterWords[0], VPT_MAX_EMITTERS_PER_SECTION);
    const uint32_t totalPower = emitterWords[2];
    aliasWords[0] = count;
    aliasWords[1] = totalPower;
    if(count == 0u || totalPower == 0u) return aliasWords;

    std::vector<double> scaled(count, 0.0);
    std::deque<uint32_t> small;
    std::deque<uint32_t> large;
    for(uint32_t i = 0u; i < count; ++i)
    {
        const uint32_t power = (emitterWords[VPT_EMITTER_HEADER_WORDS + i] >> 12u) & 15u;
        scaled[i] = static_cast<double>(power) * static_cast<double>(count) /
                    static_cast<double>(totalPower);
        (scaled[i] < 1.0 ? small : large).push_back(i);
    }

    while(!small.empty() && !large.empty())
    {
        const uint32_t s = small.front(); small.pop_front();
        const uint32_t l = large.front(); large.pop_front();
        const uint32_t threshold = static_cast<uint32_t>(std::clamp(
            std::lround(scaled[s] * 65535.0), 0l, 65535l));
        aliasWords[VPT_EMITTER_HEADER_WORDS + s] = threshold | (l << 16u);
        scaled[l] = (scaled[l] + scaled[s]) - 1.0;
        (scaled[l] < 1.0 ? small : large).push_back(l);
    }
    while(!small.empty())
    {
        const uint32_t i = small.front(); small.pop_front();
        aliasWords[VPT_EMITTER_HEADER_WORDS + i] = 65535u | (i << 16u);
    }
    while(!large.empty())
    {
        const uint32_t i = large.front(); large.pop_front();
        aliasWords[VPT_EMITTER_HEADER_WORDS + i] = 65535u | (i << 16u);
    }
    return aliasWords;
}


static float vptDistanceSquaredToSectionBounds(
    const VPTRelatedEmitterGPU& emitter,
    const VLISectionCpuPage& receiver
)
{
    const float minX = static_cast<float>(receiver.sectionX * 16);
    const float minY = static_cast<float>(receiver.sectionY * 16);
    const float minZ = static_cast<float>(receiver.sectionZ * 16);
    const float maxX = minX + 16.0f;
    const float maxY = minY + 16.0f;
    const float maxZ = minZ + 16.0f;

    const float x = static_cast<float>(emitter.worldX) + 0.5f;
    const float y = static_cast<float>(emitter.worldY) + 0.5f;
    const float z = static_cast<float>(emitter.worldZ) + 0.5f;

    const float dx = x < minX ? minX - x : (x > maxX ? x - maxX : 0.0f);
    const float dy = y < minY ? minY - y : (y > maxY ? y - maxY : 0.0f);
    const float dz = z < minZ ? minZ - z : (z > maxZ ? z - maxZ : 0.0f);
    return dx * dx + dy * dy + dz * dz;
}


static uint32_t packVPTSourceDirectoryMetadata(
    uint32_t emitterCount,
    int32_t offsetX,
    int32_t offsetY,
    int32_t offsetZ
)
{
    // bits 0..12 emitter count (0..4096), bits 13..15/16..18/19..21 are
    // offsets encoded as [-2,2] -> [0,4].  The remaining bits stay zero.
    return (std::min(emitterCount, VPT_MAX_EMITTERS_PER_SECTION) & 0x1fffu) |
        ((static_cast<uint32_t>(offsetX + 2) & 7u) << 13u) |
        ((static_cast<uint32_t>(offsetY + 2) & 7u) << 16u) |
        ((static_cast<uint32_t>(offsetZ + 2) & 7u) << 19u);
}


static void rebuildVPTSourceDirectoryPageLocked(VLISectionCpuPage& receiver)
{
    std::array<VPTSourceDirectoryEntryGPU,
        VPT_SOURCE_DIRECTORY_ENTRIES_PER_PAGE> directory{};
    uint32_t directoryCount = 0u;
    std::array<double, VPT_SOURCE_DIRECTORY_MAX_SECTIONS> weights{};

    for(const auto& sourceEntry : g_vliSections)
    {
        const VLISectionCpuPage& sourcePage = sourceEntry.second;
        const int32_t offsetX = sourcePage.sectionX - receiver.sectionX;
        const int32_t offsetY = sourcePage.sectionY - receiver.sectionY;
        const int32_t offsetZ = sourcePage.sectionZ - receiver.sectionZ;
        if(
            std::abs(offsetX) > static_cast<int32_t>(VPT_SOURCE_DIRECTORY_RADIUS) ||
            std::abs(offsetY) > static_cast<int32_t>(VPT_SOURCE_DIRECTORY_RADIUS) ||
            std::abs(offsetZ) > static_cast<int32_t>(VPT_SOURCE_DIRECTORY_RADIUS) ||
            sourcePage.emitterIndexWords.size() < VPT_EMITTER_HEADER_WORDS
        )
        {
            continue;
        }

        const uint32_t emitterCount = std::min(
            sourcePage.emitterIndexWords[0],
            VPT_MAX_EMITTERS_PER_SECTION
        );
        if(emitterCount == 0u ||
           directoryCount >= VPT_SOURCE_DIRECTORY_MAX_SECTIONS)
        {
            continue;
        }

        VPTSourceDirectoryEntryGPU& destination =
            directory[1u + directoryCount++];
        destination.pageIndex = sourcePage.pageIndex;
        destination.emitterCountAndOffset = packVPTSourceDirectoryMetadata(
            emitterCount, offsetX, offsetY, offsetZ
        );
        // The source-level proposal is the exact sum of emitter powers times
        // the established Section-bound distance falloff.  The per-emitter
        // Alias table supplies the second, power-weighted level.
        const uint32_t sectionPower = sourcePage.emitterIndexWords.size() > 2u
            ? sourcePage.emitterIndexWords[2] : 0u;
        const float dx = std::max(0, std::abs(offsetX) - 1) * 16.0f;
        const float dy = std::max(0, std::abs(offsetY) - 1) * 16.0f;
        const float dz = std::max(0, std::abs(offsetZ) - 1) * 16.0f;
        weights[directoryCount - 1u] = static_cast<double>(sectionPower) /
            static_cast<double>(1.0f + dx * dx + dy * dy + dz * dz);
    }

    directory[0].pageIndex = directoryCount;

    // Header aliasThresholdQ16 is reserved as the ReSTIR DI directory revision.
    // rt_test.rgen rejects revision 0 and uses this token to invalidate temporal
    // reservoirs when the receiver's source-directory membership/weights change.
    // Every integer through 0x00ffffff is represented exactly by float32, which
    // matters because the reservoir carries this value in a float component.
    const uint32_t directoryRevision = g_vptSourceDirectoryRevision++;
    if(g_vptSourceDirectoryRevision == 0u ||
       g_vptSourceDirectoryRevision > 0x00ffffffu)
    {
        g_vptSourceDirectoryRevision = 1u;
    }
    directory[0].aliasThresholdQ16 = directoryRevision;

    if(directoryCount > 0u)
    {
        double totalWeight = 0.0;
        for(uint32_t i = 0u; i < directoryCount; ++i) totalWeight += weights[i];
        if(totalWeight > 0.0)
        {
            std::array<double, VPT_SOURCE_DIRECTORY_MAX_SECTIONS> scaled{};
            std::deque<uint32_t> small;
            std::deque<uint32_t> large;
            for(uint32_t i = 0u; i < directoryCount; ++i)
            {
                directory[1u + i].selectionProbability =
                    static_cast<float>(weights[i] / totalWeight);
                scaled[i] = weights[i] * static_cast<double>(directoryCount) / totalWeight;
                (scaled[i] < 1.0 ? small : large).push_back(i);
            }
            while(!small.empty() && !large.empty())
            {
                const uint32_t s = small.front(); small.pop_front();
                const uint32_t l = large.front(); large.pop_front();
                directory[1u + s].aliasThresholdQ16 = static_cast<uint32_t>(std::clamp(
                    std::lround(scaled[s] * 65535.0), 0l, 65535l));
                directory[1u + s].aliasIndex = l;
                scaled[l] = (scaled[l] + scaled[s]) - 1.0;
                (scaled[l] < 1.0 ? small : large).push_back(l);
            }
            while(!small.empty())
            {
                const uint32_t i = small.front(); small.pop_front();
                directory[1u + i].aliasThresholdQ16 = 65535u;
                directory[1u + i].aliasIndex = i;
            }
            while(!large.empty())
            {
                const uint32_t i = large.front(); large.pop_front();
                directory[1u + i].aliasThresholdQ16 = 65535u;
                directory[1u + i].aliasIndex = i;
            }
        }
    }
    receiver.sourceDirectory = directory;
    g_vptDirtySourceDirectoryPages.insert(receiver.pageIndex);
}


static void rebuildVPTCandidatePageLocked(VLISectionCpuPage& receiver)
{
    std::array<VPTRelatedEmitterGPU, VPT_RELATED_EMITTERS_PER_SECTION>
        candidates{};
    std::array<float, VPT_RELATED_EMITTERS_PER_SECTION> scores{};
    scores.fill(-1.0f);

    for(const auto& sourceEntry : g_vliSections)
    {
        const VLISectionCpuPage& sourcePage = sourceEntry.second;
        if(
            std::abs(sourcePage.sectionX - receiver.sectionX) > 2 ||
            std::abs(sourcePage.sectionY - receiver.sectionY) > 2 ||
            std::abs(sourcePage.sectionZ - receiver.sectionZ) > 2 ||
            sourcePage.emitterIndexWords.size() < VPT_EMITTER_HEADER_WORDS
        )
        {
            continue;
        }

        const uint32_t emitterCount = std::min(
            sourcePage.emitterIndexWords[0],
            VPT_MAX_EMITTERS_PER_SECTION
        );
        for(uint32_t emitterIndex = 0u; emitterIndex < emitterCount; ++emitterIndex)
        {
            const uint32_t packed = sourcePage.emitterIndexWords[
                VPT_EMITTER_HEADER_WORDS + emitterIndex
            ];
            const uint32_t emission = (packed >> 12u) & 15u;
            if(emission == 0u)
            {
                continue;
            }

            VPTRelatedEmitterGPU candidate{};
            candidate.worldX = sourcePage.sectionX * 16 +
                static_cast<int32_t>(packed & 15u);
            candidate.worldY = sourcePage.sectionY * 16 +
                static_cast<int32_t>((packed >> 4u) & 15u);
            candidate.worldZ = sourcePage.sectionZ * 16 +
                static_cast<int32_t>((packed >> 8u) & 15u);
            candidate.emissionAndType = emission |
                (((packed >> 16u) & 7u) << 4u);

            const float score = static_cast<float>(emission) /
                (1.0f + vptDistanceSquaredToSectionBounds(candidate, receiver));
            if(score <= scores[VPT_RELATED_EMITTERS_PER_SECTION - 1u])
            {
                continue;
            }

            uint32_t insertAt = VPT_RELATED_EMITTERS_PER_SECTION - 1u;
            while(insertAt > 0u && score > scores[insertAt - 1u])
            {
                scores[insertAt] = scores[insertAt - 1u];
                candidates[insertAt] = candidates[insertAt - 1u];
                --insertAt;
            }
            scores[insertAt] = score;
            candidates[insertAt] = candidate;
        }
    }

    receiver.relatedEmitters = candidates;
    g_vptDirtyCandidatePages.insert(receiver.pageIndex);
    // Candidate selection is part of the solved lighting state.  Recompute
    // this receiver page in the same event epoch even when its own source
    // voxels did not change.
    g_vliDirtySolveTableSlots.insert(receiver.tableSlot);
}


static void rebuildVPTCandidateNeighborhoodLocked(
    int32_t sectionX,
    int32_t sectionY,
    int32_t sectionZ
)
{
    for(auto& entry : g_vliSections)
    {
        VLISectionCpuPage& receiver = entry.second;
        if(
            std::abs(receiver.sectionX - sectionX) <= 2 &&
            std::abs(receiver.sectionY - sectionY) <= 2 &&
            std::abs(receiver.sectionZ - sectionZ) <= 2
        )
        {
            rebuildVPTCandidatePageLocked(receiver);
            rebuildVPTSourceDirectoryPageLocked(receiver);
        }
    }
}

// Defined with the native Section upload functions below.  This declaration is
// required here because the VPT bridge is intentionally kept beside the VPT
// page-management code.
static uint64_t packSectionKey(int x, int y, int z, int layerId);

// Local-light and voxel uploads are independent Java callbacks.  Refresh the
// VPT emitter representation when a light Section changes, but only after
// the light-cache mutex has been released; this keeps the global lock order
// one-way and lets either callback arrive first.
static void refreshVPTEmitterSectionFromLocalLights(
    int32_t sectionX,
    int32_t sectionY,
    int32_t sectionZ,
    const std::vector<LocalLightRecord>& localLights
)
{
    const uint64_t sectionKey = packSectionKey(sectionX, sectionY, sectionZ, 0);
    uint32_t pageIndex = 0u;
    uint32_t emitterCount = 0u;
    bool hasVoxelPage = false;

    {
        std::lock_guard<std::mutex> lock(g_vliMutex);
        auto pageIt = g_vliSections.find(sectionKey);
        if(pageIt == g_vliSections.end())
        {
            return;
        }

        uint32_t emitterRevision = g_vptEmitterRevision++;
        if(g_vptEmitterRevision == 0u)
        {
            g_vptEmitterRevision = 1u;
        }

        std::vector<uint32_t> emitterIndexWords =
            buildVPTEmitterIndexWordsForSection(
                sectionX,
                sectionY,
                sectionZ,
                pageIt->second.sourceWords,
                localLights,
                emitterRevision
            );

        emitterCount = emitterIndexWords[0];
        pageIt->second.emitterIndexWords = std::move(emitterIndexWords);
        pageIt->second.emitterAliasWords =
            buildVPTEmitterAliasWords(pageIt->second.emitterIndexWords);
        pageIndex = pageIt->second.pageIndex;
        hasVoxelPage = true;

        g_vptDirtyEmitterPages.insert(pageIndex);
        g_vptDirtyEmitterAliasPages.insert(pageIndex);
        markVLINeighborhoodDirty(sectionX, sectionY, sectionZ);
        rebuildVPTCandidateNeighborhoodLocked(sectionX, sectionY, sectionZ);
    }

    if(hasVoxelPage)
    {
        std::stringstream ss;
        ss << "[VPT-3.3D.8 EMITTER BRIDGE] section=("
           << sectionX << "," << sectionY << "," << sectionZ << ")"
           << " local=" << localLights.size()
           << " vpt=" << emitterCount
           << " page=" << pageIndex;
        RTLog(ss.str());
    }
}


static void recordVLIUpdates(VkCommandBuffer cmd)
{
    if(
        cmd == VK_NULL_HANDLE ||
        g_vliPageTableBuffer == VK_NULL_HANDLE ||
        g_vliPageBuffer == VK_NULL_HANDLE ||
        g_vliScratchBuffer == VK_NULL_HANDLE ||
        g_vptEmitterIndexBuffer == VK_NULL_HANDLE ||
        g_vptEmitterAliasBuffer == VK_NULL_HANDLE ||
        g_vptCandidateBuffer == VK_NULL_HANDLE ||
        g_vptSourceDirectoryBuffer == VK_NULL_HANDLE ||
        g_vptSurfaceCacheBuffer == VK_NULL_HANDLE ||
        g_vliComputePipeline == VK_NULL_HANDLE ||
        g_vliComputePipelineLayout == VK_NULL_HANDLE
    )
    {
        return;
    }

    std::vector<std::pair<uint32_t, std::vector<uint32_t>>> sourceUploads;
    std::vector<std::pair<uint32_t, std::vector<uint32_t>>> emitterUploads;
    std::vector<std::pair<uint32_t, std::vector<uint32_t>>> emitterAliasUploads;
    std::vector<std::pair<uint32_t,
        std::array<VPTRelatedEmitterGPU, VPT_RELATED_EMITTERS_PER_SECTION>>>
        candidateUploads;
    std::vector<std::pair<uint32_t, std::array<VPTSourceDirectoryEntryGPU,
        VPT_SOURCE_DIRECTORY_ENTRIES_PER_PAGE>>> sourceDirectoryUploads;
    std::vector<uint32_t> tableUpdates;
    std::vector<std::pair<uint32_t, VLITableSlot>> tablePayloads;
    std::vector<std::pair<uint32_t, uint32_t>> solvePages;

    {
        std::lock_guard<std::mutex> lock(g_vliMutex);

        sourceUploads.reserve(g_vliDirtySourcePages.size());
        for(uint32_t pageIndex : g_vliDirtySourcePages)
        {
            for(const auto& entry : g_vliSections)
            {
                const VLISectionCpuPage& page = entry.second;
                if(page.pageIndex == pageIndex)
                {
                    sourceUploads.emplace_back(pageIndex, page.sourceWords);
                    break;
                }
            }
        }

        emitterUploads.reserve(g_vptDirtyEmitterPages.size());
        for(uint32_t pageIndex : g_vptDirtyEmitterPages)
        {
            for(const auto& entry : g_vliSections)
            {
                const VLISectionCpuPage& page = entry.second;
                if(page.pageIndex == pageIndex)
                {
                    emitterUploads.emplace_back(
                        pageIndex,
                        page.emitterIndexWords
                    );
                    break;
                }
            }
        }

        emitterAliasUploads.reserve(g_vptDirtyEmitterAliasPages.size());
        for(uint32_t pageIndex : g_vptDirtyEmitterAliasPages)
        {
            for(const auto& entry : g_vliSections)
            {
                const VLISectionCpuPage& page = entry.second;
                if(page.pageIndex == pageIndex)
                {
                    emitterAliasUploads.emplace_back(pageIndex, page.emitterAliasWords);
                    break;
                }
            }
        }

        candidateUploads.reserve(g_vptDirtyCandidatePages.size());
        for(uint32_t pageIndex : g_vptDirtyCandidatePages)
        {
            for(const auto& entry : g_vliSections)
            {
                const VLISectionCpuPage& page = entry.second;
                if(page.pageIndex == pageIndex)
                {
                    candidateUploads.emplace_back(
                        pageIndex,
                        page.relatedEmitters
                    );
                    break;
                }
            }
        }

        sourceDirectoryUploads.reserve(g_vptDirtySourceDirectoryPages.size());
        for(uint32_t pageIndex : g_vptDirtySourceDirectoryPages)
        {
            for(const auto& entry : g_vliSections)
            {
                const VLISectionCpuPage& page = entry.second;
                if(page.pageIndex == pageIndex)
                {
                    sourceDirectoryUploads.emplace_back(
                        pageIndex,
                        page.sourceDirectory
                    );
                    break;
                }
            }
        }

        tableUpdates.assign(
            g_vliDirtyTableSlots.begin(),
            g_vliDirtyTableSlots.end()
        );

        tablePayloads.reserve(tableUpdates.size());
        for(uint32_t tableSlot : tableUpdates)
        {
            tablePayloads.emplace_back(tableSlot, g_vliTableCpu[tableSlot]);
        }

        // VPT-3.2B never continuously re-solves loaded pages.  A Section
        // content/candidate change explicitly marks the bounded affected
        // neighborhood; all of its 729 probes are then solved atomically.
        solvePages.reserve(g_vliDirtySolveTableSlots.size());
        for(uint32_t tableSlot : g_vliDirtySolveTableSlots)
        {
            const uint32_t pagePlusOne = g_vliTableCpu[tableSlot].pagePlusOne;
            if(pagePlusOne != 0u)
            {
                solvePages.emplace_back(tableSlot, pagePlusOne - 1u);
            }
        }

        g_vliDirtySourcePages.clear();
        g_vliDirtyTableSlots.clear();
        g_vliDirtySolveTableSlots.clear();
        g_vptDirtyEmitterPages.clear();
        g_vptDirtyEmitterAliasPages.clear();
        g_vptDirtyCandidatePages.clear();
        g_vptDirtySourceDirectoryPages.clear();
    }

    const bool didInitialClear = g_vliNeedsInitialClear;
    if(didInitialClear)
    {
        vkCmdFillBuffer(cmd, g_vliPageTableBuffer, 0, VK_WHOLE_SIZE, 0u);
        vkCmdFillBuffer(cmd, g_vliPageBuffer, 0, VK_WHOLE_SIZE, 0u);
        vkCmdFillBuffer(cmd, g_vliScratchBuffer, 0, VK_WHOLE_SIZE, 0u);
        vkCmdFillBuffer(cmd, g_vptSurfaceCacheBuffer, 0, VK_WHOLE_SIZE, 0u);
        vkCmdFillBuffer(cmd, g_vliWorkListBuffer, 0, VK_WHOLE_SIZE, 0u);
        vkCmdFillBuffer(cmd, g_vptEmitterIndexBuffer, 0, VK_WHOLE_SIZE, 0u);
        vkCmdFillBuffer(cmd, g_vptEmitterAliasBuffer, 0, VK_WHOLE_SIZE, 0u);
        vkCmdFillBuffer(cmd, g_vptCandidateBuffer, 0, VK_WHOLE_SIZE, 0u);
        vkCmdFillBuffer(cmd, g_vptSourceDirectoryBuffer, 0, VK_WHOLE_SIZE, 0u);
        g_vliNeedsInitialClear = false;
    }

    for(const auto& update : tablePayloads)
    {
        vkCmdUpdateBuffer(
            cmd,
            g_vliPageTableBuffer,
            static_cast<VkDeviceSize>(update.first) * sizeof(VLITableSlot),
            sizeof(VLITableSlot),
            &update.second
        );
    }

    for(const auto& upload : sourceUploads)
    {
        vkCmdUpdateBuffer(
            cmd,
            g_vliPageBuffer,
            static_cast<VkDeviceSize>(upload.first) * VLI_PAGE_WORDS * sizeof(uint32_t),
            VLI_SOURCE_WORDS * sizeof(uint32_t),
            upload.second.data()
        );
    }

    uint64_t uploadedEmitterCount = 0u;
    for(const auto& upload : emitterUploads)
    {
        if(upload.second.size() != VPT_EMITTER_PAGE_WORDS)
        {
            continue;
        }

        uploadedEmitterCount += upload.second[0];
        vkCmdUpdateBuffer(
            cmd,
            g_vptEmitterIndexBuffer,
            static_cast<VkDeviceSize>(upload.first) *
                VPT_EMITTER_PAGE_WORDS * sizeof(uint32_t),
            static_cast<VkDeviceSize>(VPT_EMITTER_PAGE_WORDS) * sizeof(uint32_t),
            upload.second.data()
        );
    }

    if(!emitterUploads.empty())
    {
        std::stringstream ss;
        ss << "[VPT-3.1 INDEX] pages=" << emitterUploads.size()
           << " emitters=" << uploadedEmitterCount
           << " bytes="
           << static_cast<unsigned long long>(
                emitterUploads.size() *
                static_cast<size_t>(VPT_EMITTER_PAGE_WORDS) *
                sizeof(uint32_t)
              )
           << " binding=28";
        RTLog(ss.str());
    }

    for(const auto& upload : emitterAliasUploads)
    {
        if(upload.second.size() != VPT_EMITTER_PAGE_WORDS) continue;
        vkCmdUpdateBuffer(
            cmd,
            g_vptEmitterAliasBuffer,
            static_cast<VkDeviceSize>(upload.first) *
                VPT_EMITTER_PAGE_WORDS * sizeof(uint32_t),
            static_cast<VkDeviceSize>(VPT_EMITTER_PAGE_WORDS) * sizeof(uint32_t),
            upload.second.data()
        );
    }

    uint64_t uploadedCandidateCount = 0u;
    for(const auto& upload : candidateUploads)
    {
        for(const VPTRelatedEmitterGPU& candidate : upload.second)
        {
            if((candidate.emissionAndType & 15u) != 0u)
            {
                ++uploadedCandidateCount;
            }
        }

        vkCmdUpdateBuffer(
            cmd,
            g_vptCandidateBuffer,
            static_cast<VkDeviceSize>(upload.first) *
                VPT_RELATED_EMITTERS_PER_SECTION *
                sizeof(VPTRelatedEmitterGPU),
            sizeof(upload.second),
            upload.second.data()
        );
    }

    if(!candidateUploads.empty())
    {
        std::stringstream ss;
        ss << "[VPT-3.2 CANDIDATES] pages=" << candidateUploads.size()
           << " entries=" << uploadedCandidateCount
           << " maxPerPage=" << VPT_RELATED_EMITTERS_PER_SECTION
           << " binding=29";
        RTLog(ss.str());
    }

    uint64_t uploadedDirectorySources = 0u;
    for(const auto& upload : sourceDirectoryUploads)
    {
        uploadedDirectorySources += upload.second[0].pageIndex;
        vkCmdUpdateBuffer(
            cmd,
            g_vptSourceDirectoryBuffer,
            static_cast<VkDeviceSize>(upload.first) *
                VPT_SOURCE_DIRECTORY_ENTRIES_PER_PAGE *
                sizeof(VPTSourceDirectoryEntryGPU),
            sizeof(upload.second),
            upload.second.data()
        );
    }

    if(!sourceDirectoryUploads.empty())
    {
        std::stringstream ss;
        ss << "[VPT-3.3D.7 DIRECTORY] pages="
           << sourceDirectoryUploads.size()
           << " sourceSections=" << uploadedDirectorySources
           << " maxPerPage=" << VPT_SOURCE_DIRECTORY_MAX_SECTIONS
           << " binding=31";
        RTLog(ss.str());
    }

    // ReSTIR DI Block B: do NOT clear the full-screen local-DI history when
    // unrelated streamed Sections update. rt_restir_final.rgen now validates
    // history per receiver from the local source-directory revision (plus a
    // soft selected-source revision tag), so only affected pixels reject old
    // lighting. Full clears remain reserved for buffer creation/world reset.

    std::sort(
        solvePages.begin(),
        solvePages.end(),
        [](const auto& a, const auto& b) { return a.first < b.first; }
    );
    solvePages.erase(
        std::unique(solvePages.begin(), solvePages.end()),
        solvePages.end()
    );

    if(solvePages.empty())
    {
        if(
            !tableUpdates.empty() ||
            !sourceUploads.empty() ||
            !emitterUploads.empty() ||
            !candidateUploads.empty() ||
            !sourceDirectoryUploads.empty() ||
            didInitialClear
        )
        {
            VkMemoryBarrier rayReadBarrier{ VK_STRUCTURE_TYPE_MEMORY_BARRIER };
            rayReadBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            rayReadBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            vkCmdPipelineBarrier(
                cmd,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                0,
                1,
                &rayReadBarrier,
                0,
                nullptr,
                0,
                nullptr
            );
        }
        return;
    }

    std::vector<uint32_t> workList(1u + solvePages.size());
    workList[0] = static_cast<uint32_t>(solvePages.size());
    for(size_t i = 0; i < solvePages.size(); ++i)
    {
        workList[1u + i] = solvePages[i].first;
    }

    vkCmdUpdateBuffer(
        cmd,
        g_vliWorkListBuffer,
        0,
        static_cast<VkDeviceSize>(workList.size()) * sizeof(uint32_t),
        workList.data()
    );

    VkMemoryBarrier workBarrier{ VK_STRUCTURE_TYPE_MEMORY_BARRIER };
    workBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    workBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    vkCmdPipelineBarrier(
        cmd,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0,
        1,
        &workBarrier,
        0,
        nullptr,
        0,
        nullptr
    );

    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, g_vliComputePipeline);
    vkCmdBindDescriptorSets(
        cmd,
        VK_PIPELINE_BIND_POINT_COMPUTE,
        g_vliComputePipelineLayout,
        0,
        1,
        &g_rayTracingDescriptorSet,
        0,
        nullptr
    );

    const uint64_t totalSurfaceFaces =
        static_cast<uint64_t>(solvePages.size()) * VPT_SURFACE_FACES_PER_SECTION;
    const uint32_t surfaceDispatchX =
        static_cast<uint32_t>((totalSurfaceFaces + 63u) / 64u);

    // VPT-3.3B.1 has exactly one event-driven solve pass: emitter -> exact
    // block face.  The former 9^3 direct/secondary probe passes are not
    // dispatched and therefore cannot contribute interpolated light to the
    // primary image.
    VPTPushConstants pushConstants{};
    pushConstants.eventSerial = g_vptTemporalFrame++;
    pushConstants.passIndex = 1u;
    vkCmdPushConstants(cmd, g_vliComputePipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
        0, sizeof(VPTPushConstants), &pushConstants);
    vkCmdDispatch(cmd, surfaceDispatchX, 1, 1);

    VkMemoryBarrier computeToRayBarrier{ VK_STRUCTURE_TYPE_MEMORY_BARRIER };
    // D.7's source directory is uploaded by transfer and read directly by
    // RayGen; it is not consumed by the intervening Compute solve.  Include
    // both producers so a frame with a surface-cache solve cannot race that
    // direct RayGen read.
    computeToRayBarrier.srcAccessMask =
        VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
    computeToRayBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(
        cmd,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
        0,
        1,
        &computeToRayBarrier,
        0,
        nullptr,
        0,
        nullptr
    );

    std::stringstream ss;
    ss << "[VPT-3.3B.1 FACE CACHE] pages=" << solvePages.size()
       << " surfaceFaces=" << totalSurfaceFaces
       << " passes=1 mode=event-driven";
    RTLog(ss.str());
}
static void createReSTIRDISpatialPipeline()
{
    if(g_restirDISpatialPipeline != VK_NULL_HANDLE)
    {
        return;
    }

    const std::vector<uint8_t> code =
        loadSpv("shaders/rt_restir_spatial.comp.spv");
    VkShaderModule shaderModule = createShaderModule(g_device, code);

    try
    {
        VkPushConstantRange pushRange{};
        pushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        pushRange.offset = 0;
        pushRange.size = sizeof(ReSTIRDISpatialPushConstants);

        VkPipelineLayoutCreateInfo layoutInfo{
            VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO
        };
        layoutInfo.setLayoutCount = 1;
        layoutInfo.pSetLayouts = &g_rayTracingDescriptorSetLayout;
        layoutInfo.pushConstantRangeCount = 1;
        layoutInfo.pPushConstantRanges = &pushRange;
        check(
            vkCreatePipelineLayout(
                g_device,
                &layoutInfo,
                nullptr,
                &g_restirDISpatialPipelineLayout
            ),
            "ReSTIR DI create spatial pipeline layout failed"
        );

        VkPipelineShaderStageCreateInfo stage{
            VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO
        };
        stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        stage.module = shaderModule;
        stage.pName = "main";

        VkComputePipelineCreateInfo pipelineInfo{
            VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO
        };
        pipelineInfo.stage = stage;
        pipelineInfo.layout = g_restirDISpatialPipelineLayout;
        check(
            vkCreateComputePipelines(
                g_device,
                VK_NULL_HANDLE,
                1,
                &pipelineInfo,
                nullptr,
                &g_restirDISpatialPipeline
            ),
            "ReSTIR DI create spatial compute pipeline failed"
        );
    }
    catch(...)
    {
        if(g_restirDISpatialPipelineLayout != VK_NULL_HANDLE)
        {
            vkDestroyPipelineLayout(
                g_device,
                g_restirDISpatialPipelineLayout,
                nullptr
            );
            g_restirDISpatialPipelineLayout = VK_NULL_HANDLE;
        }
        vkDestroyShaderModule(g_device, shaderModule, nullptr);
        throw;
    }

    vkDestroyShaderModule(g_device, shaderModule, nullptr);
    RTLog("[ReSTIR DI] same-frame spatial compute pipeline created");
}

static void destroyReSTIRDISpatialPipeline()
{
    if(g_restirDISpatialPipeline != VK_NULL_HANDLE)
    {
        vkDestroyPipeline(g_device, g_restirDISpatialPipeline, nullptr);
        g_restirDISpatialPipeline = VK_NULL_HANDLE;
    }
    if(g_restirDISpatialPipelineLayout != VK_NULL_HANDLE)
    {
        vkDestroyPipelineLayout(
            g_device,
            g_restirDISpatialPipelineLayout,
            nullptr
        );
        g_restirDISpatialPipelineLayout = VK_NULL_HANDLE;
    }
}

static void createReSTIRDIReconstructPipeline()
{
    if(g_restirDIReconstructPipeline != VK_NULL_HANDLE)
    {
        return;
    }

    const std::vector<uint8_t> code =
        loadSpv("shaders/rt_restir_reconstruct.comp.spv");
    VkShaderModule shaderModule = createShaderModule(g_device, code);

    try
    {
        VkPushConstantRange pushRange{};
        pushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        pushRange.offset = 0;
        pushRange.size = sizeof(ReSTIRDISpatialPushConstants);

        VkPipelineLayoutCreateInfo layoutInfo{
            VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO
        };
        layoutInfo.setLayoutCount = 1;
        layoutInfo.pSetLayouts = &g_rayTracingDescriptorSetLayout;
        layoutInfo.pushConstantRangeCount = 1;
        layoutInfo.pPushConstantRanges = &pushRange;
        check(
            vkCreatePipelineLayout(
                g_device,
                &layoutInfo,
                nullptr,
                &g_restirDIReconstructPipelineLayout
            ),
            "ReSTIR DI create reconstruction pipeline layout failed"
        );

        VkPipelineShaderStageCreateInfo stage{
            VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO
        };
        stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        stage.module = shaderModule;
        stage.pName = "main";

        VkComputePipelineCreateInfo pipelineInfo{
            VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO
        };
        pipelineInfo.stage = stage;
        pipelineInfo.layout = g_restirDIReconstructPipelineLayout;
        check(
            vkCreateComputePipelines(
                g_device,
                VK_NULL_HANDLE,
                1,
                &pipelineInfo,
                nullptr,
                &g_restirDIReconstructPipeline
            ),
            "ReSTIR DI create post-visibility reconstruction pipeline failed"
        );
    }
    catch(...)
    {
        if(g_restirDIReconstructPipelineLayout != VK_NULL_HANDLE)
        {
            vkDestroyPipelineLayout(
                g_device,
                g_restirDIReconstructPipelineLayout,
                nullptr
            );
            g_restirDIReconstructPipelineLayout = VK_NULL_HANDLE;
        }
        vkDestroyShaderModule(g_device, shaderModule, nullptr);
        throw;
    }

    vkDestroyShaderModule(g_device, shaderModule, nullptr);
    RTLog(
        "[ReSTIR DI] B2 same-frame post-visibility reconstruction pipeline created"
    );
}

static void destroyReSTIRDIReconstructPipeline()
{
    if(g_restirDIReconstructPipeline != VK_NULL_HANDLE)
    {
        vkDestroyPipeline(
            g_device,
            g_restirDIReconstructPipeline,
            nullptr
        );
        g_restirDIReconstructPipeline = VK_NULL_HANDLE;
    }
    if(g_restirDIReconstructPipelineLayout != VK_NULL_HANDLE)
    {
        vkDestroyPipelineLayout(
            g_device,
            g_restirDIReconstructPipelineLayout,
            nullptr
        );
        g_restirDIReconstructPipelineLayout = VK_NULL_HANDLE;
    }
}

static void createVLIComputePipeline()
{
    if(g_vliComputePipeline != VK_NULL_HANDLE)
    {
        return;
    }

    const std::vector<uint8_t> code = loadSpv("shaders/rt_vpt_irradiance.comp.spv");
    VkShaderModule shaderModule = createShaderModule(g_device, code);

    try
    {
        VkPushConstantRange pushRange{};
        pushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        pushRange.offset = 0;
        pushRange.size = sizeof(VPTPushConstants); // 扩充为 8 字节
        VkPipelineLayoutCreateInfo layoutInfo{ VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO };
        layoutInfo.setLayoutCount = 1;
        layoutInfo.pSetLayouts = &g_rayTracingDescriptorSetLayout;
        layoutInfo.pushConstantRangeCount = 1;
        layoutInfo.pPushConstantRanges = &pushRange;
        check(
            vkCreatePipelineLayout(g_device, &layoutInfo, nullptr, &g_vliComputePipelineLayout),
            "VLI-1.4 create compute pipeline layout failed"
        );

        VkPipelineShaderStageCreateInfo stage{ VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO };
        stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        stage.module = shaderModule;
        stage.pName = "main";

        VkComputePipelineCreateInfo pipelineInfo{ VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO };
        pipelineInfo.stage = stage;
        pipelineInfo.layout = g_vliComputePipelineLayout;
        check(
            vkCreateComputePipelines(g_device, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &g_vliComputePipeline),
            "VLI-1.4 create compute pipeline failed"
        );
    }
    catch(...)
    {
        if(g_vliComputePipelineLayout != VK_NULL_HANDLE)
        {
            vkDestroyPipelineLayout(g_device, g_vliComputePipelineLayout, nullptr);
            g_vliComputePipelineLayout = VK_NULL_HANDLE;
        }
        vkDestroyShaderModule(g_device, shaderModule, nullptr);
        throw;
    }

    vkDestroyShaderModule(g_device, shaderModule, nullptr);
    RTLog("[VLI-1.4] compute pipeline created");
}


static void destroyVLIResources()
{
    if(g_vliComputePipeline != VK_NULL_HANDLE)
    {
        vkDestroyPipeline(g_device, g_vliComputePipeline, nullptr);
        g_vliComputePipeline = VK_NULL_HANDLE;
    }
    if(g_vliComputePipelineLayout != VK_NULL_HANDLE)
    {
        vkDestroyPipelineLayout(g_device, g_vliComputePipelineLayout, nullptr);
        g_vliComputePipelineLayout = VK_NULL_HANDLE;
    }

    const auto destroyBuffer = [](VkBuffer& buffer, VkDeviceMemory& memory)
    {
        if(buffer != VK_NULL_HANDLE) vkDestroyBuffer(g_device, buffer, nullptr);
        if(memory != VK_NULL_HANDLE) vkFreeMemory(g_device, memory, nullptr);
        buffer = VK_NULL_HANDLE;
        memory = VK_NULL_HANDLE;
    };

    destroyBuffer(g_vliWorkListBuffer, g_vliWorkListMemory);
    destroyBuffer(g_vptSourceDirectoryBuffer, g_vptSourceDirectoryMemory);
    destroyBuffer(g_vptCandidateBuffer, g_vptCandidateMemory);
    destroyBuffer(g_vptEmitterAliasBuffer, g_vptEmitterAliasMemory);
    destroyBuffer(g_vptEmitterIndexBuffer, g_vptEmitterIndexMemory);
    destroyBuffer(g_vptSurfaceCacheBuffer, g_vptSurfaceCacheMemory);
    destroyBuffer(g_vliScratchBuffer, g_vliScratchMemory);
    destroyBuffer(g_vliPageBuffer, g_vliPageMemory);
    destroyBuffer(g_vliPageTableBuffer, g_vliPageTableMemory);
    g_vliNeedsInitialClear = true;
}

static void updateRTOutputDescriptor()
{
    if(!g_rayTracingDescriptorSet ||
       !g_rtOutputView)
    {
        return;
    }

    VkDescriptorImageInfo imageInfo{};
    imageInfo.imageView = g_rtOutputView;
    imageInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;

    VkWriteDescriptorSet write{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    write.dstSet = g_rayTracingDescriptorSet;
    write.dstBinding = 1;
    write.descriptorCount = 1;
    write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    write.pImageInfo = &imageInfo;

    vkUpdateDescriptorSets(
        g_device,
        1,
        &write,
        0,
        nullptr
    );
}

static void updateWaterSimulationRayDescriptor()
{
    if(g_rayTracingDescriptorSet == VK_NULL_HANDLE ||
       g_waterSimView == VK_NULL_HANDLE ||
       g_waterSimSampler == VK_NULL_HANDLE)
    {
        return;
    }

    VkDescriptorImageInfo info{};
    info.sampler = g_waterSimSampler;
    info.imageView = g_waterSimView;
    info.imageLayout = VK_IMAGE_LAYOUT_GENERAL;

    VkWriteDescriptorSet write{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    write.dstSet = g_rayTracingDescriptorSet;
    write.dstBinding = 33;
    write.descriptorCount = 1;
    write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    write.pImageInfo = &info;
    vkUpdateDescriptorSets(g_device, 1, &write, 0, nullptr);
}

static void updateWaterCausticRayDescriptor()
{
    if(g_rayTracingDescriptorSet == VK_NULL_HANDLE ||
       g_waterCausticView == VK_NULL_HANDLE ||
       g_waterCausticSampler == VK_NULL_HANDLE)
    {
        return;
    }

    VkDescriptorImageInfo info{};
    info.sampler = g_waterCausticSampler;
    info.imageView = g_waterCausticView;
    info.imageLayout = VK_IMAGE_LAYOUT_GENERAL;

    VkWriteDescriptorSet write{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    write.dstSet = g_rayTracingDescriptorSet;
    write.dstBinding = 34;
    write.descriptorCount = 1;
    write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    write.pImageInfo = &info;
    vkUpdateDescriptorSets(g_device, 1, &write, 0, nullptr);
}

static void updateWaterDetailNormalRayDescriptor()
{
    if(g_rayTracingDescriptorSet == VK_NULL_HANDLE ||
       g_waterDetailNormalView == VK_NULL_HANDLE ||
       g_waterDetailNormalSampler == VK_NULL_HANDLE)
    {
        return;
    }
    VkDescriptorImageInfo info{};
    info.sampler = g_waterDetailNormalSampler;
    info.imageView = g_waterDetailNormalView;
    info.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    VkWriteDescriptorSet write{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    write.dstSet = g_rayTracingDescriptorSet;
    write.dstBinding = 35;
    write.descriptorCount = 1;
    write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    write.pImageInfo = &info;
    vkUpdateDescriptorSets(g_device, 1, &write, 0, nullptr);
}

// A compact, tileable tangent-space normal map is generated once on the CPU.
// It replaces the unstable analytical shading slope, while the physical macro
// wave and the player simulation remain in their existing GPU paths.
static void createWaterDetailNormalResources()
{
    if(g_waterDetailNormalImage != VK_NULL_HANDLE)
    {
        return;
    }

    std::vector<std::vector<uint8_t>> mipPixels(WATER_DETAIL_NORMAL_MIP_LEVELS);
    uint32_t extent = WATER_DETAIL_NORMAL_RESOLUTION;
    mipPixels[0].resize(static_cast<size_t>(extent) * extent * 4u);
    for(uint32_t y = 0; y < extent; ++y)
    for(uint32_t x = 0; x < extent; ++x)
    {
        const float u = 6.28318530718f * (static_cast<float>(x) + 0.5f) / extent;
        const float v = 6.28318530718f * (static_cast<float>(y) + 0.5f) / extent;
        const float dx = 0.72f * std::cos(u * 5.0f + v * 2.0f) +
                         0.34f * std::sin(u * 9.0f - v * 7.0f) +
                         0.18f * std::cos(u * 15.0f + v * 11.0f);
        const float dy = 0.66f * std::sin(v * 6.0f - u * 3.0f) +
                         0.31f * std::cos(v * 10.0f + u * 8.0f) +
                         0.17f * std::sin(v * 17.0f - u * 13.0f);
        const float length = std::sqrt(dx * dx + dy * dy + 1.0f);
        const size_t index = (static_cast<size_t>(y) * extent + x) * 4u;
        mipPixels[0][index + 0] = static_cast<uint8_t>((dx / length * 0.5f + 0.5f) * 255.0f);
        mipPixels[0][index + 1] = static_cast<uint8_t>((dy / length * 0.5f + 0.5f) * 255.0f);
        mipPixels[0][index + 2] = static_cast<uint8_t>((1.0f / length * 0.5f + 0.5f) * 255.0f);
        mipPixels[0][index + 3] = 255u;
    }
    for(uint32_t level = 1u; level < WATER_DETAIL_NORMAL_MIP_LEVELS; ++level)
    {
        const uint32_t previousExtent = std::max(1u, WATER_DETAIL_NORMAL_RESOLUTION >> (level - 1u));
        const uint32_t currentExtent = std::max(1u, WATER_DETAIL_NORMAL_RESOLUTION >> level);
        mipPixels[level].resize(static_cast<size_t>(currentExtent) * currentExtent * 4u);
        for(uint32_t y = 0; y < currentExtent; ++y)
        for(uint32_t x = 0; x < currentExtent; ++x)
        {
            float nx = 0.0f, ny = 0.0f, nz = 0.0f;
            for(uint32_t oy = 0; oy < 2u; ++oy)
            for(uint32_t ox = 0; ox < 2u; ++ox)
            {
                const size_t src = (static_cast<size_t>((y * 2u + oy) % previousExtent) * previousExtent +
                                    ((x * 2u + ox) % previousExtent)) * 4u;
                nx += static_cast<float>(mipPixels[level - 1u][src + 0]) / 255.0f * 2.0f - 1.0f;
                ny += static_cast<float>(mipPixels[level - 1u][src + 1]) / 255.0f * 2.0f - 1.0f;
                nz += static_cast<float>(mipPixels[level - 1u][src + 2]) / 255.0f * 2.0f - 1.0f;
            }
            const float length = std::sqrt(nx * nx + ny * ny + nz * nz);
            const size_t dst = (static_cast<size_t>(y) * currentExtent + x) * 4u;
            mipPixels[level][dst + 0] = static_cast<uint8_t>((nx / length * 0.5f + 0.5f) * 255.0f);
            mipPixels[level][dst + 1] = static_cast<uint8_t>((ny / length * 0.5f + 0.5f) * 255.0f);
            mipPixels[level][dst + 2] = static_cast<uint8_t>((nz / length * 0.5f + 0.5f) * 255.0f);
            mipPixels[level][dst + 3] = 255u;
        }
    }
    VkDeviceSize totalBytes = 0u;
    std::vector<VkDeviceSize> offsets(WATER_DETAIL_NORMAL_MIP_LEVELS);
    for(uint32_t level = 0u; level < WATER_DETAIL_NORMAL_MIP_LEVELS; ++level)
    {
        offsets[level] = totalBytes;
        totalBytes += mipPixels[level].size();
    }
    VkBuffer stagingBuffer = VK_NULL_HANDLE;
    VkDeviceMemory stagingMemory = VK_NULL_HANDLE;
    createBuffer(g_device, g_physicalDevice, totalBytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
        stagingBuffer, stagingMemory);
    void* mapped = nullptr;
    check(vkMapMemory(g_device, stagingMemory, 0u, totalBytes, 0u, &mapped), "water normal map staging map failed");
    for(uint32_t level = 0u; level < WATER_DETAIL_NORMAL_MIP_LEVELS; ++level)
        std::memcpy(static_cast<uint8_t*>(mapped) + offsets[level], mipPixels[level].data(), mipPixels[level].size());
    vkUnmapMemory(g_device, stagingMemory);

    VkImageCreateInfo imageInfo{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imageInfo.extent = {WATER_DETAIL_NORMAL_RESOLUTION, WATER_DETAIL_NORMAL_RESOLUTION, 1u};
    imageInfo.mipLevels = WATER_DETAIL_NORMAL_MIP_LEVELS;
    imageInfo.arrayLayers = 1u;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    check(vkCreateImage(g_device, &imageInfo, nullptr, &g_waterDetailNormalImage), "water normal map create image failed");
    VkMemoryRequirements requirements{};
    vkGetImageMemoryRequirements(g_device, g_waterDetailNormalImage, &requirements);
    VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    allocation.allocationSize = requirements.size;
    allocation.memoryTypeIndex = findMemoryType(g_physicalDevice, requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    check(vkAllocateMemory(g_device, &allocation, nullptr, &g_waterDetailNormalMemory), "water normal map allocate failed");
    check(vkBindImageMemory(g_device, g_waterDetailNormalImage, g_waterDetailNormalMemory, 0u), "water normal map bind failed");

    VkCommandBufferAllocateInfo commandAllocation{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    commandAllocation.commandPool = g_rtCommandPool;
    commandAllocation.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    commandAllocation.commandBufferCount = 1u;
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    check(vkAllocateCommandBuffers(g_device, &commandAllocation, &commandBuffer), "water normal map command allocation failed");
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO}; begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    check(vkBeginCommandBuffer(commandBuffer, &begin), "water normal map command begin failed");
    VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED; barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED; barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = g_waterDetailNormalImage; barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    barrier.subresourceRange.levelCount = WATER_DETAIL_NORMAL_MIP_LEVELS; barrier.subresourceRange.layerCount = 1u;
    barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0u, 0u, nullptr, 0u, nullptr, 1u, &barrier);
    std::vector<VkBufferImageCopy> copies(WATER_DETAIL_NORMAL_MIP_LEVELS);
    for(uint32_t level = 0u; level < WATER_DETAIL_NORMAL_MIP_LEVELS; ++level) {
        copies[level].bufferOffset = offsets[level]; copies[level].imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        copies[level].imageSubresource.mipLevel = level; copies[level].imageSubresource.layerCount = 1u;
        const uint32_t levelExtent = std::max(1u, WATER_DETAIL_NORMAL_RESOLUTION >> level);
        copies[level].imageExtent = {levelExtent, levelExtent, 1u};
    }
    vkCmdCopyBufferToImage(commandBuffer, stagingBuffer, g_waterDetailNormalImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        WATER_DETAIL_NORMAL_MIP_LEVELS, copies.data());
    barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; barrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT; barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR, 0u, 0u, nullptr, 0u, nullptr, 1u, &barrier);
    check(vkEndCommandBuffer(commandBuffer), "water normal map command end failed");
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO}; submit.commandBufferCount = 1u; submit.pCommandBuffers = &commandBuffer;
    check(vkQueueSubmit(g_queue, 1u, &submit, VK_NULL_HANDLE), "water normal map submit failed");
    check(vkQueueWaitIdle(g_queue), "water normal map upload wait failed");
    vkFreeCommandBuffers(g_device, g_rtCommandPool, 1u, &commandBuffer);
    vkDestroyBuffer(g_device, stagingBuffer, nullptr); vkFreeMemory(g_device, stagingMemory, nullptr);

    VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    viewInfo.image = g_waterDetailNormalImage; viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D; viewInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT; viewInfo.subresourceRange.levelCount = WATER_DETAIL_NORMAL_MIP_LEVELS; viewInfo.subresourceRange.layerCount = 1u;
    check(vkCreateImageView(g_device, &viewInfo, nullptr, &g_waterDetailNormalView), "water normal map view failed");
    VkSamplerCreateInfo samplerInfo{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    samplerInfo.magFilter = VK_FILTER_LINEAR; samplerInfo.minFilter = VK_FILTER_LINEAR; samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_REPEAT; samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_REPEAT; samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_REPEAT;
    samplerInfo.minLod = 0.0f; samplerInfo.maxLod = static_cast<float>(WATER_DETAIL_NORMAL_MIP_LEVELS - 1u);
    check(vkCreateSampler(g_device, &samplerInfo, nullptr, &g_waterDetailNormalSampler), "water normal map sampler failed");
    updateWaterDetailNormalRayDescriptor();
}

static void destroyWaterDetailNormalResources()
{
    if(g_waterDetailNormalSampler) vkDestroySampler(g_device, g_waterDetailNormalSampler, nullptr);
    if(g_waterDetailNormalView) vkDestroyImageView(g_device, g_waterDetailNormalView, nullptr);
    if(g_waterDetailNormalImage) vkDestroyImage(g_device, g_waterDetailNormalImage, nullptr);
    if(g_waterDetailNormalMemory) vkFreeMemory(g_device, g_waterDetailNormalMemory, nullptr);
    g_waterDetailNormalSampler = VK_NULL_HANDLE; g_waterDetailNormalView = VK_NULL_HANDLE;
    g_waterDetailNormalImage = VK_NULL_HANDLE; g_waterDetailNormalMemory = VK_NULL_HANDLE;
}

// -------------------------------------------------------------------------
// Tyndall cloud-noise resources
// -------------------------------------------------------------------------
// The reference cloud implementations sample a persistent shape/detail field
// and a decorrelating curl field.  Generate both once, upload once, and never
// touch them again during normal rendering or resource-pack reloads.
static uint32_t cloudNoiseHash(uint32_t value)
{
    value ^= value >> 16u;
    value *= 0x7feb352du;
    value ^= value >> 15u;
    value *= 0x846ca68bu;
    value ^= value >> 16u;
    return value;
}

static float cloudNoiseUnit(uint32_t x, uint32_t y, uint32_t z, uint32_t seed)
{
    uint32_t value = x * 0x8da6b343u ^ y * 0xd8163841u ^
                     z * 0xcb1ab31fu ^ seed;
    return static_cast<float>(cloudNoiseHash(value) & 0x00ffffffu) /
        static_cast<float>(0x01000000u);
}

static float cloudNoiseFade(float value)
{
    return value * value * (3.0f - 2.0f * value);
}

static float cloudNoiseLerp(float a, float b, float t)
{
    return a + (b - a) * t;
}

static uint32_t cloudNoiseWrap(int value, int period)
{
    value %= period;
    return static_cast<uint32_t>(value < 0 ? value + period : value);
}

// A small periodic value-noise basis is enough here: the GPU's trilinear
// sampler handles interpolation between uploaded texels, while this produces
// coherent cloud masses instead of filtering independent white-noise voxels.
static float cloudValueNoise(float x, float y, float z, int period, uint32_t seed)
{
    const int x0 = static_cast<int>(std::floor(x));
    const int y0 = static_cast<int>(std::floor(y));
    const int z0 = static_cast<int>(std::floor(z));
    const float tx = cloudNoiseFade(x - static_cast<float>(x0));
    const float ty = cloudNoiseFade(y - static_cast<float>(y0));
    const float tz = cloudNoiseFade(z - static_cast<float>(z0));
    const auto sample = [period, seed](int sx, int sy, int sz)
    {
        return cloudNoiseUnit(cloudNoiseWrap(sx, period), cloudNoiseWrap(sy, period),
            cloudNoiseWrap(sz, period), seed);
    };
    const float x00 = cloudNoiseLerp(sample(x0, y0, z0), sample(x0 + 1, y0, z0), tx);
    const float x10 = cloudNoiseLerp(sample(x0, y0 + 1, z0), sample(x0 + 1, y0 + 1, z0), tx);
    const float x01 = cloudNoiseLerp(sample(x0, y0, z0 + 1), sample(x0 + 1, y0, z0 + 1), tx);
    const float x11 = cloudNoiseLerp(sample(x0, y0 + 1, z0 + 1), sample(x0 + 1, y0 + 1, z0 + 1), tx);
    return cloudNoiseLerp(cloudNoiseLerp(x00, x10, ty), cloudNoiseLerp(x01, x11, ty), tz);
}

static float cloudFbmNoise(float x, float y, float z, uint32_t seed)
{
    return 0.50f * cloudValueNoise(x * 4.0f, y * 4.0f, z * 4.0f, 4, seed) +
           0.30f * cloudValueNoise(x * 9.0f, y * 9.0f, z * 9.0f, 9, seed ^ 0x9e3779b9u) +
           0.20f * cloudValueNoise(x * 19.0f, y * 19.0f, z * 19.0f, 19, seed ^ 0x85ebca6bu);
}

static float cloudCellularNoise(float x, float y, float z, int period, uint32_t seed)
{
    const int ix = static_cast<int>(std::floor(x));
    const int iy = static_cast<int>(std::floor(y));
    const int iz = static_cast<int>(std::floor(z));
    const float fx = x - static_cast<float>(ix);
    const float fy = y - static_cast<float>(iy);
    const float fz = z - static_cast<float>(iz);
    float nearest = 3.0f;
    for(int dz = -1; dz <= 1; ++dz)
    for(int dy = -1; dy <= 1; ++dy)
    for(int dx = -1; dx <= 1; ++dx)
    {
        const int cx = ix + dx, cy = iy + dy, cz = iz + dz;
        const uint32_t wx = cloudNoiseWrap(cx, period);
        const uint32_t wy = cloudNoiseWrap(cy, period);
        const uint32_t wz = cloudNoiseWrap(cz, period);
        const float px = cloudNoiseUnit(wx, wy, wz, seed);
        const float py = cloudNoiseUnit(wx, wy, wz, seed ^ 0x68bc21ebu);
        const float pz = cloudNoiseUnit(wx, wy, wz, seed ^ 0x02e5be93u);
        const float ox = static_cast<float>(dx) + px - fx;
        const float oy = static_cast<float>(dy) + py - fy;
        const float oz = static_cast<float>(dz) + pz - fz;
        nearest = std::min(nearest, ox * ox + oy * oy + oz * oz);
    }
    return std::max(0.0f, 1.0f - std::sqrt(nearest) * 0.577350269f);
}

static uint8_t cloudNoiseByte(float value)
{
    const float clamped = std::max(0.0f, std::min(1.0f, value));
    return static_cast<uint8_t>(clamped * 255.0f + 0.5f);
}

static void updateCloudNoiseRayDescriptors()
{
    if(g_rayTracingDescriptorSet == VK_NULL_HANDLE ||
       g_cloudShapeNoiseView == VK_NULL_HANDLE ||
       g_cloudShapeNoiseSampler == VK_NULL_HANDLE ||
       g_cloudCurlNoiseView == VK_NULL_HANDLE ||
       g_cloudCurlNoiseSampler == VK_NULL_HANDLE)
    {
        return;
    }

    VkDescriptorImageInfo infos[2]{};
    infos[0].sampler = g_cloudShapeNoiseSampler;
    infos[0].imageView = g_cloudShapeNoiseView;
    infos[0].imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    infos[1].sampler = g_cloudCurlNoiseSampler;
    infos[1].imageView = g_cloudCurlNoiseView;
    infos[1].imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

    VkWriteDescriptorSet writes[2]{};
    for(uint32_t i = 0u; i < 2u; ++i)
    {
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = g_rayTracingDescriptorSet;
        writes[i].dstBinding = 38u + i;
        writes[i].descriptorCount = 1u;
        writes[i].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        writes[i].pImageInfo = &infos[i];
    }
    vkUpdateDescriptorSets(g_device, 2u, writes, 0u, nullptr);
}

static void createCloudNoiseResources()
{
    if(g_cloudShapeNoiseImage != VK_NULL_HANDLE)
    {
        return;
    }

    const size_t shapeTexelCount = static_cast<size_t>(CLOUD_SHAPE_NOISE_RESOLUTION) *
        CLOUD_SHAPE_NOISE_RESOLUTION * CLOUD_SHAPE_NOISE_RESOLUTION;
    const size_t curlTexelCount = static_cast<size_t>(CLOUD_CURL_NOISE_RESOLUTION) *
        CLOUD_CURL_NOISE_RESOLUTION * CLOUD_CURL_NOISE_RESOLUTION;
    std::vector<uint8_t> shapePixels(shapeTexelCount * 4u);
    std::vector<uint8_t> curlPixels(curlTexelCount * 4u);

    // RG contain broad coherent coverage fields. BA contain two cellular
    // erosion scales, allowing the shader to separate cloud mass from edge
    // detail without runtime procedural-noise cost.
    for(uint32_t z = 0u; z < CLOUD_SHAPE_NOISE_RESOLUTION; ++z)
    for(uint32_t y = 0u; y < CLOUD_SHAPE_NOISE_RESOLUTION; ++y)
    for(uint32_t x = 0u; x < CLOUD_SHAPE_NOISE_RESOLUTION; ++x)
    {
        const size_t index = (static_cast<size_t>(z) * CLOUD_SHAPE_NOISE_RESOLUTION *
            CLOUD_SHAPE_NOISE_RESOLUTION + static_cast<size_t>(y) *
            CLOUD_SHAPE_NOISE_RESOLUTION + x) * 4u;
        const float fx = static_cast<float>(x) / static_cast<float>(CLOUD_SHAPE_NOISE_RESOLUTION);
        const float fy = static_cast<float>(y) / static_cast<float>(CLOUD_SHAPE_NOISE_RESOLUTION);
        const float fz = static_cast<float>(z) / static_cast<float>(CLOUD_SHAPE_NOISE_RESOLUTION);
        const float macro = cloudFbmNoise(fx, fy, fz, 0x13579bdfu);
        const float billow = 1.0f - std::abs(2.0f * cloudFbmNoise(fx + 0.37f, fy + 0.11f, fz + 0.71f, 0x2468ace1u) - 1.0f);
        shapePixels[index + 0u] = cloudNoiseByte(macro);
        shapePixels[index + 1u] = cloudNoiseByte(0.55f * macro + 0.45f * billow);
        shapePixels[index + 2u] = cloudNoiseByte(cloudCellularNoise(fx * 7.0f, fy * 7.0f, fz * 7.0f, 7, 0x9e3779b9u));
        shapePixels[index + 3u] = cloudNoiseByte(cloudCellularNoise(fx * 15.0f, fy * 15.0f, fz * 15.0f, 15, 0xa341316cu));
    }

    // Derive a finite-difference curl from three deterministic scalar fields.
    // It avoids the directional stretching produced by three unrelated random
    // channels while remaining tileable and entirely static after startup.
    const uint32_t n = CLOUD_CURL_NOISE_RESOLUTION;
    for(uint32_t z = 0u; z < n; ++z)
    for(uint32_t y = 0u; y < n; ++y)
    for(uint32_t x = 0u; x < n; ++x)
    {
        const uint32_t xp = (x + 1u) % n, xm = (x + n - 1u) % n;
        const uint32_t yp = (y + 1u) % n, ym = (y + n - 1u) % n;
        const uint32_t zp = (z + 1u) % n, zm = (z + n - 1u) % n;
        const uint32_t sx = 0x7f4a7c15u, sy = 0x6a09e667u, sz = 0xbb67ae85u;
        const auto potential = [n](uint32_t px, uint32_t py, uint32_t pz, uint32_t seed)
        {
            return cloudValueNoise(static_cast<float>(px) * 5.0f / static_cast<float>(n),
                static_cast<float>(py) * 5.0f / static_cast<float>(n),
                static_cast<float>(pz) * 5.0f / static_cast<float>(n), 5, seed);
        };
        const float dFzDy = potential(x, yp, z, sz) - potential(x, ym, z, sz);
        const float dFyDz = potential(x, y, zp, sy) - potential(x, y, zm, sy);
        const float dFxDz = potential(x, y, zp, sx) - potential(x, y, zm, sx);
        const float dFzDx = potential(xp, y, z, sz) - potential(xm, y, z, sz);
        const float dFyDx = potential(xp, y, z, sy) - potential(xm, y, z, sy);
        const float dFxDy = potential(x, yp, z, sx) - potential(x, ym, z, sx);
        float curlX = dFzDy - dFyDz;
        float curlY = dFxDz - dFzDx;
        float curlZ = dFyDx - dFxDy;
        const float length = std::sqrt(curlX * curlX + curlY * curlY + curlZ * curlZ);
        if(length > 1.0e-6f)
        {
            curlX /= length;
            curlY /= length;
            curlZ /= length;
        }
        const size_t index = (static_cast<size_t>(z) * n * n +
            static_cast<size_t>(y) * n + x) * 4u;
        curlPixels[index + 0u] = cloudNoiseByte(curlX * 0.5f + 0.5f);
        curlPixels[index + 1u] = cloudNoiseByte(curlY * 0.5f + 0.5f);
        curlPixels[index + 2u] = cloudNoiseByte(curlZ * 0.5f + 0.5f);
        curlPixels[index + 3u] = 255u;
    }

    const VkDeviceSize shapeBytes = static_cast<VkDeviceSize>(shapePixels.size());
    const VkDeviceSize curlBytes = static_cast<VkDeviceSize>(curlPixels.size());
    VkBuffer stagingBuffer = VK_NULL_HANDLE;
    VkDeviceMemory stagingMemory = VK_NULL_HANDLE;
    createBuffer(g_device, g_physicalDevice, shapeBytes + curlBytes,
        VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
        stagingBuffer, stagingMemory);
    void* mapped = nullptr;
    check(vkMapMemory(g_device, stagingMemory, 0u, shapeBytes + curlBytes, 0u, &mapped),
        "cloud noise staging map failed");
    std::memcpy(mapped, shapePixels.data(), shapePixels.size());
    std::memcpy(static_cast<uint8_t*>(mapped) + shapeBytes, curlPixels.data(), curlPixels.size());
    vkUnmapMemory(g_device, stagingMemory);

    auto createVolume = [](uint32_t extent, VkImage& image, VkDeviceMemory& memory,
                           const char* createError, const char* allocateError, const char* bindError)
    {
        VkImageCreateInfo info{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
        info.imageType = VK_IMAGE_TYPE_3D;
        info.format = VK_FORMAT_R8G8B8A8_UNORM;
        info.extent = {extent, extent, extent};
        info.mipLevels = 1u;
        info.arrayLayers = 1u;
        info.samples = VK_SAMPLE_COUNT_1_BIT;
        info.tiling = VK_IMAGE_TILING_OPTIMAL;
        info.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        check(vkCreateImage(g_device, &info, nullptr, &image), createError);
        VkMemoryRequirements requirements{};
        vkGetImageMemoryRequirements(g_device, image, &requirements);
        VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        allocation.allocationSize = requirements.size;
        allocation.memoryTypeIndex = findMemoryType(g_physicalDevice,
            requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        check(vkAllocateMemory(g_device, &allocation, nullptr, &memory), allocateError);
        check(vkBindImageMemory(g_device, image, memory, 0u), bindError);
    };
    createVolume(CLOUD_SHAPE_NOISE_RESOLUTION, g_cloudShapeNoiseImage,
        g_cloudShapeNoiseMemory, "cloud shape image create failed",
        "cloud shape image allocate failed", "cloud shape image bind failed");
    createVolume(CLOUD_CURL_NOISE_RESOLUTION, g_cloudCurlNoiseImage,
        g_cloudCurlNoiseMemory, "cloud curl image create failed",
        "cloud curl image allocate failed", "cloud curl image bind failed");

    VkCommandBufferAllocateInfo allocation{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    allocation.commandPool = g_rtCommandPool;
    allocation.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocation.commandBufferCount = 1u;
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    check(vkAllocateCommandBuffers(g_device, &allocation, &commandBuffer),
        "cloud noise command allocation failed");
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    check(vkBeginCommandBuffer(commandBuffer, &begin), "cloud noise command begin failed");

    VkImageMemoryBarrier barriers[2]{};
    for(uint32_t i = 0u; i < 2u; ++i)
    {
        barriers[i].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barriers[i].oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        barriers[i].newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        barriers[i].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barriers[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barriers[i].image = i == 0u ? g_cloudShapeNoiseImage : g_cloudCurlNoiseImage;
        barriers[i].subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        barriers[i].subresourceRange.levelCount = 1u;
        barriers[i].subresourceRange.layerCount = 1u;
        barriers[i].dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    }
    vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT, 0u, 0u, nullptr, 0u, nullptr, 2u, barriers);

    VkBufferImageCopy copies[2]{};
    for(uint32_t i = 0u; i < 2u; ++i)
    {
        copies[i].bufferOffset = i == 0u ? 0u : shapeBytes;
        copies[i].imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        copies[i].imageSubresource.layerCount = 1u;
        const uint32_t extent = i == 0u ? CLOUD_SHAPE_NOISE_RESOLUTION :
            CLOUD_CURL_NOISE_RESOLUTION;
        copies[i].imageExtent = {extent, extent, extent};
    }
    vkCmdCopyBufferToImage(commandBuffer, stagingBuffer, g_cloudShapeNoiseImage,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1u, &copies[0]);
    vkCmdCopyBufferToImage(commandBuffer, stagingBuffer, g_cloudCurlNoiseImage,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1u, &copies[1]);
    for(uint32_t i = 0u; i < 2u; ++i)
    {
        barriers[i].oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        barriers[i].newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        barriers[i].srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        barriers[i].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    }
    vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0u, 0u, nullptr, 0u, nullptr, 2u, barriers);
    check(vkEndCommandBuffer(commandBuffer), "cloud noise command end failed");
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.commandBufferCount = 1u;
    submit.pCommandBuffers = &commandBuffer;
    check(vkQueueSubmit(g_queue, 1u, &submit, VK_NULL_HANDLE), "cloud noise submit failed");
    check(vkQueueWaitIdle(g_queue), "cloud noise upload wait failed");
    vkFreeCommandBuffers(g_device, g_rtCommandPool, 1u, &commandBuffer);
    vkDestroyBuffer(g_device, stagingBuffer, nullptr);
    vkFreeMemory(g_device, stagingMemory, nullptr);

    auto createViewAndSampler = [](VkImage image, VkImageView& view, VkSampler& sampler,
                                   const char* viewError, const char* samplerError)
    {
        VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        viewInfo.image = image;
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_3D;
        viewInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
        viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        viewInfo.subresourceRange.levelCount = 1u;
        viewInfo.subresourceRange.layerCount = 1u;
        check(vkCreateImageView(g_device, &viewInfo, nullptr, &view), viewError);
        VkSamplerCreateInfo samplerInfo{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
        samplerInfo.magFilter = VK_FILTER_LINEAR;
        samplerInfo.minFilter = VK_FILTER_LINEAR;
        samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
        samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_REPEAT;
        samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_REPEAT;
        samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_REPEAT;
        samplerInfo.minLod = 0.0f;
        samplerInfo.maxLod = 0.0f;
        check(vkCreateSampler(g_device, &samplerInfo, nullptr, &sampler), samplerError);
    };
    createViewAndSampler(g_cloudShapeNoiseImage, g_cloudShapeNoiseView,
        g_cloudShapeNoiseSampler, "cloud shape view create failed", "cloud shape sampler create failed");
    createViewAndSampler(g_cloudCurlNoiseImage, g_cloudCurlNoiseView,
        g_cloudCurlNoiseSampler, "cloud curl view create failed", "cloud curl sampler create failed");
    updateCloudNoiseRayDescriptors();
    RTLog("[CLOUD] Tyndall 128^3 shape/detail + 64^3 curl fields uploaded");
}

static void destroyCloudNoiseResources()
{
    if(g_cloudShapeNoiseSampler) vkDestroySampler(g_device, g_cloudShapeNoiseSampler, nullptr);
    if(g_cloudShapeNoiseView) vkDestroyImageView(g_device, g_cloudShapeNoiseView, nullptr);
    if(g_cloudShapeNoiseImage) vkDestroyImage(g_device, g_cloudShapeNoiseImage, nullptr);
    if(g_cloudShapeNoiseMemory) vkFreeMemory(g_device, g_cloudShapeNoiseMemory, nullptr);
    if(g_cloudCurlNoiseSampler) vkDestroySampler(g_device, g_cloudCurlNoiseSampler, nullptr);
    if(g_cloudCurlNoiseView) vkDestroyImageView(g_device, g_cloudCurlNoiseView, nullptr);
    if(g_cloudCurlNoiseImage) vkDestroyImage(g_device, g_cloudCurlNoiseImage, nullptr);
    if(g_cloudCurlNoiseMemory) vkFreeMemory(g_device, g_cloudCurlNoiseMemory, nullptr);
    g_cloudShapeNoiseSampler = VK_NULL_HANDLE;
    g_cloudShapeNoiseView = VK_NULL_HANDLE;
    g_cloudShapeNoiseImage = VK_NULL_HANDLE;
    g_cloudShapeNoiseMemory = VK_NULL_HANDLE;
    g_cloudCurlNoiseSampler = VK_NULL_HANDLE;
    g_cloudCurlNoiseView = VK_NULL_HANDLE;
    g_cloudCurlNoiseImage = VK_NULL_HANDLE;
    g_cloudCurlNoiseMemory = VK_NULL_HANDLE;
}

static void createVolumetricCloudResources(uint32_t width, uint32_t height)
{
    const uint32_t cloudWidth = width;
    const uint32_t cloudHeight = height;
    if(g_volumetricCloudImage != VK_NULL_HANDLE &&
       g_volumetricCloudWidth == cloudWidth &&
       g_volumetricCloudHeight == cloudHeight)
    {
        return;
    }
    if(g_volumetricCloudImage != VK_NULL_HANDLE)
    {
        destroyVolumetricCloudResources();
    }

    VkFormatProperties formatProperties{};
    vkGetPhysicalDeviceFormatProperties(
        g_physicalDevice, VK_FORMAT_R16G16B16A16_SFLOAT, &formatProperties
    );
    const VkFormatFeatureFlags requiredFeatures =
        VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
    if((formatProperties.optimalTilingFeatures & requiredFeatures) != requiredFeatures)
    {
        throw std::runtime_error(
            "VK_FORMAT_R16G16B16A16_SFLOAT cannot be sampled and stored for volumetric clouds"
        );
    }
    vkGetPhysicalDeviceFormatProperties(g_physicalDevice, VK_FORMAT_R32_SFLOAT, &formatProperties);
    if((formatProperties.optimalTilingFeatures & requiredFeatures) != requiredFeatures)
    {
        throw std::runtime_error(
            "VK_FORMAT_R32_SFLOAT cannot be sampled and stored for volumetric cloud depth"
        );
    }

    VkImageCreateInfo imageInfo{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = VK_FORMAT_R16G16B16A16_SFLOAT;
    imageInfo.extent = { cloudWidth, cloudHeight, 1u };
    imageInfo.mipLevels = 1u;
    imageInfo.arrayLayers = 1u;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
        VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    check(vkCreateImage(g_device, &imageInfo, nullptr, &g_volumetricCloudImage),
        "volumetric cloud image create failed");

    VkMemoryRequirements requirements{};
    vkGetImageMemoryRequirements(g_device, g_volumetricCloudImage, &requirements);
    VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    allocation.allocationSize = requirements.size;
    allocation.memoryTypeIndex = findMemoryType(g_physicalDevice,
        requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    check(vkAllocateMemory(g_device, &allocation, nullptr, &g_volumetricCloudMemory),
        "volumetric cloud image allocate failed");
    check(vkBindImageMemory(g_device, g_volumetricCloudImage,
        g_volumetricCloudMemory, 0u), "volumetric cloud image bind failed");

    VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    viewInfo.image = g_volumetricCloudImage;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_R16G16B16A16_SFLOAT;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.levelCount = 1u;
    viewInfo.subresourceRange.layerCount = 1u;
    check(vkCreateImageView(g_device, &viewInfo, nullptr, &g_volumetricCloudView),
        "volumetric cloud image view create failed");

    check(vkCreateImage(g_device, &imageInfo, nullptr, &g_volumetricCloudHistoryImage),
        "volumetric cloud history image create failed");
    vkGetImageMemoryRequirements(g_device, g_volumetricCloudHistoryImage, &requirements);
    allocation.allocationSize = requirements.size;
    allocation.memoryTypeIndex = findMemoryType(g_physicalDevice,
        requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    check(vkAllocateMemory(g_device, &allocation, nullptr, &g_volumetricCloudHistoryMemory),
        "volumetric cloud history memory allocate failed");
    check(vkBindImageMemory(g_device, g_volumetricCloudHistoryImage,
        g_volumetricCloudHistoryMemory, 0u), "volumetric cloud history image bind failed");
    viewInfo.image = g_volumetricCloudHistoryImage;
    check(vkCreateImageView(g_device, &viewInfo, nullptr, &g_volumetricCloudHistoryView),
        "volumetric cloud history image view create failed");

    check(vkCreateImage(g_device, &imageInfo, nullptr, &g_volumetricCloudResolvedImage),
        "volumetric cloud resolved image create failed");
    vkGetImageMemoryRequirements(g_device, g_volumetricCloudResolvedImage, &requirements);
    allocation.allocationSize = requirements.size;
    allocation.memoryTypeIndex = findMemoryType(g_physicalDevice,
        requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    check(vkAllocateMemory(g_device, &allocation, nullptr, &g_volumetricCloudResolvedMemory),
        "volumetric cloud resolved image allocate failed");
    check(vkBindImageMemory(g_device, g_volumetricCloudResolvedImage,
        g_volumetricCloudResolvedMemory, 0u), "volumetric cloud resolved image bind failed");
    viewInfo.image = g_volumetricCloudResolvedImage;
    check(vkCreateImageView(g_device, &viewInfo, nullptr, &g_volumetricCloudResolvedView),
        "volumetric cloud resolved image view create failed");

    imageInfo.format = VK_FORMAT_R32_SFLOAT;
    check(vkCreateImage(g_device, &imageInfo, nullptr, &g_volumetricCloudDepthImage),
        "volumetric cloud depth image create failed");
    vkGetImageMemoryRequirements(g_device, g_volumetricCloudDepthImage, &requirements);
    allocation.allocationSize = requirements.size;
    allocation.memoryTypeIndex = findMemoryType(g_physicalDevice,
        requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    check(vkAllocateMemory(g_device, &allocation, nullptr, &g_volumetricCloudDepthMemory),
        "volumetric cloud depth image allocate failed");
    check(vkBindImageMemory(g_device, g_volumetricCloudDepthImage,
        g_volumetricCloudDepthMemory, 0u), "volumetric cloud depth image bind failed");

    viewInfo.image = g_volumetricCloudDepthImage;
    viewInfo.format = VK_FORMAT_R32_SFLOAT;
    check(vkCreateImageView(g_device, &viewInfo, nullptr, &g_volumetricCloudDepthView),
        "volumetric cloud depth image view create failed");

    check(vkCreateImage(g_device, &imageInfo, nullptr, &g_volumetricCloudHistoryDepthImage),
        "volumetric cloud history depth image create failed");
    vkGetImageMemoryRequirements(g_device, g_volumetricCloudHistoryDepthImage, &requirements);
    allocation.allocationSize = requirements.size;
    allocation.memoryTypeIndex = findMemoryType(g_physicalDevice,
        requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    check(vkAllocateMemory(g_device, &allocation, nullptr, &g_volumetricCloudHistoryDepthMemory),
        "volumetric cloud history depth image allocate failed");
    check(vkBindImageMemory(g_device, g_volumetricCloudHistoryDepthImage,
        g_volumetricCloudHistoryDepthMemory, 0u), "volumetric cloud history depth image bind failed");

    viewInfo.image = g_volumetricCloudHistoryDepthImage;
    check(vkCreateImageView(g_device, &viewInfo, nullptr, &g_volumetricCloudHistoryDepthView),
        "volumetric cloud history depth image view create failed");

    VkSamplerCreateInfo samplerInfo{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    samplerInfo.magFilter = VK_FILTER_LINEAR;
    samplerInfo.minFilter = VK_FILTER_LINEAR;
    samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.maxLod = 0.0f;
    check(vkCreateSampler(g_device, &samplerInfo, nullptr, &g_volumetricCloudSampler),
        "volumetric cloud sampler create failed");

    VkDescriptorSetLayoutBinding renderBindings[5]{};
    renderBindings[0] = { 0u, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1u,
        VK_SHADER_STAGE_COMPUTE_BIT, nullptr };
    renderBindings[1] = { 1u, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1u,
        VK_SHADER_STAGE_COMPUTE_BIT, nullptr };
    renderBindings[2] = { 2u, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1u,
        VK_SHADER_STAGE_COMPUTE_BIT, nullptr };
    renderBindings[3] = { 3u, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1u,
        VK_SHADER_STAGE_COMPUTE_BIT, nullptr };
    renderBindings[4] = { 4u, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1u,
        VK_SHADER_STAGE_COMPUTE_BIT, nullptr };
    VkDescriptorSetLayoutCreateInfo renderLayoutInfo{
        VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO
    };
    renderLayoutInfo.bindingCount = 5u;
    renderLayoutInfo.pBindings = renderBindings;
    check(vkCreateDescriptorSetLayout(g_device, &renderLayoutInfo, nullptr,
        &g_volumetricCloudRenderSetLayout), "volumetric cloud render set layout failed");

    VkDescriptorSetLayoutBinding resolveBindings[6]{};
    resolveBindings[0] = { 0u, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1u,
        VK_SHADER_STAGE_COMPUTE_BIT, nullptr };
    for(uint32_t i = 1u; i < 5u; ++i)
    {
        resolveBindings[i] = { i, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1u,
            VK_SHADER_STAGE_COMPUTE_BIT, nullptr };
    }
    resolveBindings[5] = { 5u, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1u,
        VK_SHADER_STAGE_COMPUTE_BIT, nullptr };
    VkDescriptorSetLayoutCreateInfo resolveLayoutInfo{
        VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO
    };
    resolveLayoutInfo.bindingCount = 6u;
    resolveLayoutInfo.pBindings = resolveBindings;
    check(vkCreateDescriptorSetLayout(g_device, &resolveLayoutInfo, nullptr,
        &g_volumetricCloudResolveSetLayout), "volumetric cloud resolve set layout failed");

    VkDescriptorSetLayoutBinding compositeBindings[4]{};
    compositeBindings[0] = { 0u, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1u,
        VK_SHADER_STAGE_COMPUTE_BIT, nullptr };
    compositeBindings[1] = { 1u, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1u,
        VK_SHADER_STAGE_COMPUTE_BIT, nullptr };
    compositeBindings[2] = { 2u, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1u,
        VK_SHADER_STAGE_COMPUTE_BIT, nullptr };
    compositeBindings[3] = { 3u, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1u,
        VK_SHADER_STAGE_COMPUTE_BIT, nullptr };
    VkDescriptorSetLayoutCreateInfo compositeLayoutInfo{
        VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO
    };
    compositeLayoutInfo.bindingCount = 4u;
    compositeLayoutInfo.pBindings = compositeBindings;
    check(vkCreateDescriptorSetLayout(g_device, &compositeLayoutInfo, nullptr,
        &g_volumetricCloudCompositeSetLayout), "volumetric cloud composite set layout failed");

    VkDescriptorPoolSize poolSizes[3] = {
        { VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 3u },
        { VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 8u },
        { VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 4u }
    };
    VkDescriptorPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    poolInfo.maxSets = 3u;
    poolInfo.poolSizeCount = 3u;
    poolInfo.pPoolSizes = poolSizes;
    check(vkCreateDescriptorPool(g_device, &poolInfo, nullptr,
        &g_volumetricCloudDescriptorPool), "volumetric cloud descriptor pool failed");

    VkDescriptorSetLayout setLayouts[3] = {
        g_volumetricCloudRenderSetLayout, g_volumetricCloudResolveSetLayout,
        g_volumetricCloudCompositeSetLayout
    };
    VkDescriptorSet descriptorSets[3]{};
    VkDescriptorSetAllocateInfo setAllocation{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    setAllocation.descriptorPool = g_volumetricCloudDescriptorPool;
    setAllocation.descriptorSetCount = 3u;
    setAllocation.pSetLayouts = setLayouts;
    check(vkAllocateDescriptorSets(g_device, &setAllocation, descriptorSets),
        "volumetric cloud descriptor set allocation failed");
    g_volumetricCloudRenderSet = descriptorSets[0];
    g_volumetricCloudResolveSet = descriptorSets[1];
    g_volumetricCloudCompositeSet = descriptorSets[2];

    VkDescriptorBufferInfo cameraInfo{};
    cameraInfo.buffer = g_cameraUboBuffer;
    cameraInfo.range = sizeof(CameraUBO);
    VkDescriptorImageInfo shapeInfo{};
    shapeInfo.sampler = g_cloudShapeNoiseSampler;
    shapeInfo.imageView = g_cloudShapeNoiseView;
    shapeInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    VkDescriptorImageInfo curlInfo{};
    curlInfo.sampler = g_cloudCurlNoiseSampler;
    curlInfo.imageView = g_cloudCurlNoiseView;
    curlInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    VkDescriptorImageInfo cloudRawInfo{};
    cloudRawInfo.sampler = g_volumetricCloudSampler;
    cloudRawInfo.imageView = g_volumetricCloudView;
    cloudRawInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo cloudStorageInfo{};
    cloudStorageInfo.imageView = g_volumetricCloudView;
    cloudStorageInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo cloudResolvedInfo{};
    cloudResolvedInfo.sampler = g_volumetricCloudSampler;
    cloudResolvedInfo.imageView = g_volumetricCloudResolvedView;
    cloudResolvedInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo cloudResolvedStorageInfo{};
    cloudResolvedStorageInfo.imageView = g_volumetricCloudResolvedView;
    cloudResolvedStorageInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo cloudHistoryInfo{};
    cloudHistoryInfo.sampler = g_volumetricCloudSampler;
    cloudHistoryInfo.imageView = g_volumetricCloudHistoryView;
    cloudHistoryInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo cloudDepthStorageInfo{};
    cloudDepthStorageInfo.imageView = g_volumetricCloudDepthView;
    cloudDepthStorageInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo cloudDepthInfo{};
    cloudDepthInfo.sampler = g_volumetricCloudSampler;
    cloudDepthInfo.imageView = g_volumetricCloudDepthView;
    cloudDepthInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo cloudHistoryDepthInfo{};
    cloudHistoryDepthInfo.sampler = g_volumetricCloudSampler;
    cloudHistoryDepthInfo.imageView = g_volumetricCloudHistoryDepthView;
    cloudHistoryDepthInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo sceneStorageInfo{};
    sceneStorageInfo.imageView = g_rtOutputView;
    sceneStorageInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo depthInfo{};
    depthInfo.sampler = g_volumetricCloudSampler;
    depthInfo.imageView = g_dlssDepthView;
    depthInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet writes[15]{};
    writes[0] = { VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr,
        g_volumetricCloudRenderSet, 0u, 0u, 1u,
        VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, nullptr, &cameraInfo, nullptr };
    writes[1] = { VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr,
        g_volumetricCloudRenderSet, 1u, 0u, 1u,
        VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &shapeInfo, nullptr, nullptr };
    writes[2] = { VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr,
        g_volumetricCloudRenderSet, 2u, 0u, 1u,
        VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &curlInfo, nullptr, nullptr };
    writes[3] = { VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr,
        g_volumetricCloudRenderSet, 3u, 0u, 1u,
        VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, &cloudStorageInfo, nullptr, nullptr };
    writes[4] = { VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr,
        g_volumetricCloudRenderSet, 4u, 0u, 1u,
        VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, &cloudDepthStorageInfo, nullptr, nullptr };
    writes[5] = { VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr,
        g_volumetricCloudResolveSet, 0u, 0u, 1u,
        VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, nullptr, &cameraInfo, nullptr };
    writes[6] = { VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr,
        g_volumetricCloudResolveSet, 1u, 0u, 1u,
        VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &cloudRawInfo, nullptr, nullptr };
    writes[7] = { VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr,
        g_volumetricCloudResolveSet, 2u, 0u, 1u,
        VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &cloudHistoryInfo, nullptr, nullptr };
    writes[8] = { VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr,
        g_volumetricCloudResolveSet, 3u, 0u, 1u,
        VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &cloudDepthInfo, nullptr, nullptr };
    writes[9] = { VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr,
        g_volumetricCloudResolveSet, 4u, 0u, 1u,
        VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &cloudHistoryDepthInfo, nullptr, nullptr };
    writes[10] = { VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr,
        g_volumetricCloudResolveSet, 5u, 0u, 1u,
        VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, &cloudResolvedStorageInfo, nullptr, nullptr };
    writes[11] = { VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr,
        g_volumetricCloudCompositeSet, 0u, 0u, 1u,
        VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &cloudResolvedInfo, nullptr, nullptr };
    writes[12] = { VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr,
        g_volumetricCloudCompositeSet, 1u, 0u, 1u,
        VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, &sceneStorageInfo, nullptr, nullptr };
    writes[13] = { VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr,
        g_volumetricCloudCompositeSet, 2u, 0u, 1u,
        VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &depthInfo, nullptr, nullptr };
    writes[14] = { VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr,
        g_volumetricCloudCompositeSet, 3u, 0u, 1u,
        VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, nullptr, &cameraInfo, nullptr };
    vkUpdateDescriptorSets(g_device, 15u, writes, 0u, nullptr);
    g_volumetricCloudWidth = cloudWidth;
    g_volumetricCloudHeight = cloudHeight;
    g_volumetricCloudLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    g_volumetricCloudHistoryLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    g_volumetricCloudResolvedLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    g_volumetricCloudDepthLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    g_volumetricCloudHistoryDepthLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    g_volumetricCloudHistoryNeedsClear.store(true);
}

static void createVolumetricCloudPipeline(uint32_t width, uint32_t height)
{
    const uint32_t cloudWidth = width;
    const uint32_t cloudHeight = height;
    if(g_volumetricCloudRenderPipeline != VK_NULL_HANDLE &&
       g_volumetricCloudWidth == cloudWidth && g_volumetricCloudHeight == cloudHeight)
    {
        return;
    }
    createVolumetricCloudResources(width, height);
    const std::vector<uint8_t> renderCode = loadSpv("shaders/volumetric_clouds.comp.spv");
    const std::vector<uint8_t> resolveCode = loadSpv("shaders/volumetric_cloud_resolve.comp.spv");
    const std::vector<uint8_t> compositeCode = loadSpv("shaders/volumetric_cloud_composite.comp.spv");
    VkShaderModule renderShader = createShaderModule(g_device, renderCode);
    VkShaderModule resolveShader = createShaderModule(g_device, resolveCode);
    VkShaderModule compositeShader = createShaderModule(g_device, compositeCode);
    try
    {
        VkPipelineLayoutCreateInfo renderLayoutInfo{
            VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO
        };
        renderLayoutInfo.setLayoutCount = 1u;
        renderLayoutInfo.pSetLayouts = &g_volumetricCloudRenderSetLayout;
        check(vkCreatePipelineLayout(g_device, &renderLayoutInfo, nullptr,
            &g_volumetricCloudRenderPipelineLayout), "volumetric cloud render pipeline layout failed");
        VkPipelineLayoutCreateInfo resolveLayoutInfo{
            VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO
        };
        resolveLayoutInfo.setLayoutCount = 1u;
        resolveLayoutInfo.pSetLayouts = &g_volumetricCloudResolveSetLayout;
        check(vkCreatePipelineLayout(g_device, &resolveLayoutInfo, nullptr,
            &g_volumetricCloudResolvePipelineLayout), "volumetric cloud resolve pipeline layout failed");
        VkPipelineLayoutCreateInfo compositeLayoutInfo{
            VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO
        };
        compositeLayoutInfo.setLayoutCount = 1u;
        compositeLayoutInfo.pSetLayouts = &g_volumetricCloudCompositeSetLayout;
        check(vkCreatePipelineLayout(g_device, &compositeLayoutInfo, nullptr,
            &g_volumetricCloudCompositePipelineLayout), "volumetric cloud composite pipeline layout failed");
        VkPipelineShaderStageCreateInfo renderStage{
            VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO
        };
        renderStage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        renderStage.module = renderShader;
        renderStage.pName = "main";
        VkComputePipelineCreateInfo renderPipelineInfo{
            VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO
        };
        renderPipelineInfo.stage = renderStage;
        renderPipelineInfo.layout = g_volumetricCloudRenderPipelineLayout;
        check(vkCreateComputePipelines(g_device, VK_NULL_HANDLE, 1u, &renderPipelineInfo,
            nullptr, &g_volumetricCloudRenderPipeline), "volumetric cloud render pipeline failed");
        VkPipelineShaderStageCreateInfo resolveStage = renderStage;
        resolveStage.module = resolveShader;
        VkComputePipelineCreateInfo resolvePipelineInfo{
            VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO
        };
        resolvePipelineInfo.stage = resolveStage;
        resolvePipelineInfo.layout = g_volumetricCloudResolvePipelineLayout;
        check(vkCreateComputePipelines(g_device, VK_NULL_HANDLE, 1u, &resolvePipelineInfo,
            nullptr, &g_volumetricCloudResolvePipeline), "volumetric cloud resolve pipeline failed");
        VkPipelineShaderStageCreateInfo compositeStage = renderStage;
        compositeStage.module = compositeShader;
        VkComputePipelineCreateInfo compositePipelineInfo{
            VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO
        };
        compositePipelineInfo.stage = compositeStage;
        compositePipelineInfo.layout = g_volumetricCloudCompositePipelineLayout;
        check(vkCreateComputePipelines(g_device, VK_NULL_HANDLE, 1u, &compositePipelineInfo,
            nullptr, &g_volumetricCloudCompositePipeline), "volumetric cloud composite pipeline failed");
    }
    catch(...)
    {
        vkDestroyShaderModule(g_device, renderShader, nullptr);
        vkDestroyShaderModule(g_device, resolveShader, nullptr);
        vkDestroyShaderModule(g_device, compositeShader, nullptr);
        destroyVolumetricCloudResources();
        throw;
    }
    vkDestroyShaderModule(g_device, renderShader, nullptr);
    vkDestroyShaderModule(g_device, resolveShader, nullptr);
    vkDestroyShaderModule(g_device, compositeShader, nullptr);
    RTLog("[CLOUD] full-resolution world-anchored render, temporal resolve, and composite pipelines created");
}

static void recordVolumetricClouds(VkCommandBuffer cmd)
{
    if(cmd == VK_NULL_HANDLE || g_volumetricCloudRenderPipeline == VK_NULL_HANDLE ||
       g_volumetricCloudResolvePipeline == VK_NULL_HANDLE ||
       g_volumetricCloudCompositePipeline == VK_NULL_HANDLE)
    {
        return;
    }
    VkImageMemoryBarrier cloudBarriers[3]{};
    cloudBarriers[0].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    cloudBarriers[0].oldLayout = g_volumetricCloudLayout;
    cloudBarriers[0].newLayout = VK_IMAGE_LAYOUT_GENERAL;
    cloudBarriers[0].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    cloudBarriers[0].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    cloudBarriers[0].srcAccessMask = g_volumetricCloudLayout == VK_IMAGE_LAYOUT_UNDEFINED
        ? 0u : VK_ACCESS_SHADER_READ_BIT;
    cloudBarriers[0].dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    cloudBarriers[0].image = g_volumetricCloudImage;
    cloudBarriers[0].subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    cloudBarriers[0].subresourceRange.levelCount = 1u;
    cloudBarriers[0].subresourceRange.layerCount = 1u;
    cloudBarriers[1] = cloudBarriers[0];
    cloudBarriers[1].oldLayout = g_volumetricCloudDepthLayout;
    cloudBarriers[1].srcAccessMask = g_volumetricCloudDepthLayout == VK_IMAGE_LAYOUT_UNDEFINED
        ? 0u : VK_ACCESS_SHADER_READ_BIT;
    cloudBarriers[1].image = g_volumetricCloudDepthImage;
    cloudBarriers[2] = cloudBarriers[0];
    cloudBarriers[2].oldLayout = g_volumetricCloudResolvedLayout;
    cloudBarriers[2].srcAccessMask = g_volumetricCloudResolvedLayout == VK_IMAGE_LAYOUT_UNDEFINED
        ? 0u : VK_ACCESS_SHADER_READ_BIT;
    cloudBarriers[2].image = g_volumetricCloudResolvedImage;
    vkCmdPipelineBarrier(cmd,
        g_volumetricCloudLayout == VK_IMAGE_LAYOUT_UNDEFINED &&
            g_volumetricCloudDepthLayout == VK_IMAGE_LAYOUT_UNDEFINED &&
            g_volumetricCloudResolvedLayout == VK_IMAGE_LAYOUT_UNDEFINED
            ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT : VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr, 0u, nullptr, 3u, cloudBarriers);
    g_volumetricCloudLayout = VK_IMAGE_LAYOUT_GENERAL;
    g_volumetricCloudDepthLayout = VK_IMAGE_LAYOUT_GENERAL;
    g_volumetricCloudResolvedLayout = VK_IMAGE_LAYOUT_GENERAL;

    if(g_volumetricCloudHistoryNeedsClear.exchange(false))
    {
        VkImageMemoryBarrier historyBarriers[2]{};
        historyBarriers[0].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        historyBarriers[0].oldLayout = g_volumetricCloudHistoryLayout;
        historyBarriers[0].newLayout = VK_IMAGE_LAYOUT_GENERAL;
        historyBarriers[0].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        historyBarriers[0].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        historyBarriers[0].srcAccessMask = g_volumetricCloudHistoryLayout == VK_IMAGE_LAYOUT_UNDEFINED
            ? 0u : VK_ACCESS_SHADER_READ_BIT;
        historyBarriers[0].dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        historyBarriers[0].image = g_volumetricCloudHistoryImage;
        historyBarriers[0].subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        historyBarriers[0].subresourceRange.levelCount = 1u;
        historyBarriers[0].subresourceRange.layerCount = 1u;
        historyBarriers[1] = historyBarriers[0];
        historyBarriers[1].oldLayout = g_volumetricCloudHistoryDepthLayout;
        historyBarriers[1].srcAccessMask = g_volumetricCloudHistoryDepthLayout == VK_IMAGE_LAYOUT_UNDEFINED
            ? 0u : VK_ACCESS_SHADER_READ_BIT;
        historyBarriers[1].image = g_volumetricCloudHistoryDepthImage;
        vkCmdPipelineBarrier(cmd,
            g_volumetricCloudHistoryLayout == VK_IMAGE_LAYOUT_UNDEFINED &&
                g_volumetricCloudHistoryDepthLayout == VK_IMAGE_LAYOUT_UNDEFINED
                ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT : VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT, 0u, 0u, nullptr, 0u, nullptr, 2u, historyBarriers);
        const VkClearColorValue emptyCloud = {{0.0f, 0.0f, 0.0f, 1.0f}};
        vkCmdClearColorImage(cmd, g_volumetricCloudHistoryImage, VK_IMAGE_LAYOUT_GENERAL,
            &emptyCloud, 1u, &historyBarriers[0].subresourceRange);
        const VkClearColorValue emptyCloudDepth = {{1000000.0f, 0.0f, 0.0f, 0.0f}};
        vkCmdClearColorImage(cmd, g_volumetricCloudHistoryDepthImage, VK_IMAGE_LAYOUT_GENERAL,
            &emptyCloudDepth, 1u, &historyBarriers[1].subresourceRange);
        for(uint32_t i = 0u; i < 2u; ++i)
        {
            historyBarriers[i].srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            historyBarriers[i].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        }
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr, 0u, nullptr, 2u, historyBarriers);
        g_volumetricCloudHistoryLayout = VK_IMAGE_LAYOUT_GENERAL;
        g_volumetricCloudHistoryDepthLayout = VK_IMAGE_LAYOUT_GENERAL;
    }

    VkImageMemoryBarrier sceneBarriers[2]{};
    for(uint32_t i = 0u; i < 2u; ++i)
    {
        sceneBarriers[i].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        sceneBarriers[i].oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        sceneBarriers[i].newLayout = VK_IMAGE_LAYOUT_GENERAL;
        sceneBarriers[i].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        sceneBarriers[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        sceneBarriers[i].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        sceneBarriers[i].dstAccessMask = i == 0u
            ? VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT
            : VK_ACCESS_SHADER_READ_BIT;
        sceneBarriers[i].image = i == 0u ? g_rtOutputImage : g_dlssDepthImage;
        sceneBarriers[i].subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        sceneBarriers[i].subresourceRange.levelCount = 1u;
        sceneBarriers[i].subresourceRange.layerCount = 1u;
    }
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr, 0u, nullptr, 2u, sceneBarriers);

    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, g_volumetricCloudRenderPipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
        g_volumetricCloudRenderPipelineLayout, 0u, 1u, &g_volumetricCloudRenderSet,
        0u, nullptr);
    vkCmdDispatch(cmd, (g_volumetricCloudWidth + 7u) / 8u,
        (g_volumetricCloudHeight + 7u) / 8u, 1u);

    VkImageMemoryBarrier resolveBarriers[2]{};
    resolveBarriers[0].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    resolveBarriers[0].oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    resolveBarriers[0].newLayout = VK_IMAGE_LAYOUT_GENERAL;
    resolveBarriers[0].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    resolveBarriers[0].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    resolveBarriers[0].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    resolveBarriers[0].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    resolveBarriers[0].image = g_volumetricCloudImage;
    resolveBarriers[0].subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    resolveBarriers[0].subresourceRange.levelCount = 1u;
    resolveBarriers[0].subresourceRange.layerCount = 1u;
    resolveBarriers[1] = resolveBarriers[0];
    resolveBarriers[1].image = g_volumetricCloudDepthImage;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr, 0u, nullptr, 2u, resolveBarriers);

    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, g_volumetricCloudResolvePipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
        g_volumetricCloudResolvePipelineLayout, 0u, 1u, &g_volumetricCloudResolveSet,
        0u, nullptr);
    vkCmdDispatch(cmd, (g_volumetricCloudWidth + 7u) / 8u,
        (g_volumetricCloudHeight + 7u) / 8u, 1u);

    VkImageMemoryBarrier copyBarriers[4]{};
    copyBarriers[0].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    copyBarriers[0].oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    copyBarriers[0].newLayout = VK_IMAGE_LAYOUT_GENERAL;
    copyBarriers[0].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    copyBarriers[0].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    copyBarriers[0].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    copyBarriers[0].dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    copyBarriers[0].image = g_volumetricCloudResolvedImage;
    copyBarriers[0].subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    copyBarriers[0].subresourceRange.levelCount = 1u;
    copyBarriers[0].subresourceRange.layerCount = 1u;
    copyBarriers[1] = copyBarriers[0];
    copyBarriers[1].srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
    copyBarriers[1].image = g_volumetricCloudDepthImage;
    copyBarriers[2] = copyBarriers[0];
    copyBarriers[2].srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
    copyBarriers[2].dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    copyBarriers[2].image = g_volumetricCloudHistoryImage;
    copyBarriers[3] = copyBarriers[2];
    copyBarriers[3].image = g_volumetricCloudHistoryDepthImage;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT, 0u, 0u, nullptr, 0u, nullptr, 4u, copyBarriers);

    VkImageCopy historyCopy{};
    historyCopy.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    historyCopy.srcSubresource.layerCount = 1u;
    historyCopy.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    historyCopy.dstSubresource.layerCount = 1u;
    historyCopy.extent = {g_volumetricCloudWidth, g_volumetricCloudHeight, 1u};
    vkCmdCopyImage(cmd, g_volumetricCloudResolvedImage, VK_IMAGE_LAYOUT_GENERAL,
        g_volumetricCloudHistoryImage, VK_IMAGE_LAYOUT_GENERAL, 1u, &historyCopy);
    vkCmdCopyImage(cmd, g_volumetricCloudDepthImage, VK_IMAGE_LAYOUT_GENERAL,
        g_volumetricCloudHistoryDepthImage, VK_IMAGE_LAYOUT_GENERAL, 1u, &historyCopy);

    for(uint32_t i = 0u; i < 2u; ++i)
    {
        copyBarriers[i].srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        copyBarriers[i].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    }
    for(uint32_t i = 2u; i < 4u; ++i)
    {
        copyBarriers[i].srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        copyBarriers[i].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    }
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr, 0u, nullptr, 4u, copyBarriers);

    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, g_volumetricCloudCompositePipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
        g_volumetricCloudCompositePipelineLayout, 0u, 1u, &g_volumetricCloudCompositeSet,
        0u, nullptr);
    vkCmdDispatch(cmd, (g_rtOutputWidth + 7u) / 8u,
        (g_rtOutputHeight + 7u) / 8u, 1u);
}

static void destroyVolumetricCloudResources()
{
    if(g_volumetricCloudRenderPipeline) vkDestroyPipeline(g_device, g_volumetricCloudRenderPipeline, nullptr);
    if(g_volumetricCloudResolvePipeline) vkDestroyPipeline(g_device, g_volumetricCloudResolvePipeline, nullptr);
    if(g_volumetricCloudCompositePipeline) vkDestroyPipeline(g_device, g_volumetricCloudCompositePipeline, nullptr);
    if(g_volumetricCloudRenderPipelineLayout) vkDestroyPipelineLayout(g_device, g_volumetricCloudRenderPipelineLayout, nullptr);
    if(g_volumetricCloudResolvePipelineLayout) vkDestroyPipelineLayout(g_device, g_volumetricCloudResolvePipelineLayout, nullptr);
    if(g_volumetricCloudCompositePipelineLayout) vkDestroyPipelineLayout(g_device, g_volumetricCloudCompositePipelineLayout, nullptr);
    if(g_volumetricCloudDescriptorPool) vkDestroyDescriptorPool(g_device, g_volumetricCloudDescriptorPool, nullptr);
    if(g_volumetricCloudRenderSetLayout) vkDestroyDescriptorSetLayout(g_device, g_volumetricCloudRenderSetLayout, nullptr);
    if(g_volumetricCloudResolveSetLayout) vkDestroyDescriptorSetLayout(g_device, g_volumetricCloudResolveSetLayout, nullptr);
    if(g_volumetricCloudCompositeSetLayout) vkDestroyDescriptorSetLayout(g_device, g_volumetricCloudCompositeSetLayout, nullptr);
    if(g_volumetricCloudSampler) vkDestroySampler(g_device, g_volumetricCloudSampler, nullptr);
    if(g_volumetricCloudHistoryDepthView) vkDestroyImageView(g_device, g_volumetricCloudHistoryDepthView, nullptr);
    if(g_volumetricCloudHistoryDepthImage) vkDestroyImage(g_device, g_volumetricCloudHistoryDepthImage, nullptr);
    if(g_volumetricCloudHistoryDepthMemory) vkFreeMemory(g_device, g_volumetricCloudHistoryDepthMemory, nullptr);
    if(g_volumetricCloudDepthView) vkDestroyImageView(g_device, g_volumetricCloudDepthView, nullptr);
    if(g_volumetricCloudDepthImage) vkDestroyImage(g_device, g_volumetricCloudDepthImage, nullptr);
    if(g_volumetricCloudDepthMemory) vkFreeMemory(g_device, g_volumetricCloudDepthMemory, nullptr);
    if(g_volumetricCloudHistoryView) vkDestroyImageView(g_device, g_volumetricCloudHistoryView, nullptr);
    if(g_volumetricCloudHistoryImage) vkDestroyImage(g_device, g_volumetricCloudHistoryImage, nullptr);
    if(g_volumetricCloudHistoryMemory) vkFreeMemory(g_device, g_volumetricCloudHistoryMemory, nullptr);
    if(g_volumetricCloudResolvedView) vkDestroyImageView(g_device, g_volumetricCloudResolvedView, nullptr);
    if(g_volumetricCloudResolvedImage) vkDestroyImage(g_device, g_volumetricCloudResolvedImage, nullptr);
    if(g_volumetricCloudResolvedMemory) vkFreeMemory(g_device, g_volumetricCloudResolvedMemory, nullptr);
    if(g_volumetricCloudView) vkDestroyImageView(g_device, g_volumetricCloudView, nullptr);
    if(g_volumetricCloudImage) vkDestroyImage(g_device, g_volumetricCloudImage, nullptr);
    if(g_volumetricCloudMemory) vkFreeMemory(g_device, g_volumetricCloudMemory, nullptr);
    g_volumetricCloudImage = VK_NULL_HANDLE;
    g_volumetricCloudMemory = VK_NULL_HANDLE;
    g_volumetricCloudView = VK_NULL_HANDLE;
    g_volumetricCloudHistoryImage = VK_NULL_HANDLE;
    g_volumetricCloudHistoryMemory = VK_NULL_HANDLE;
    g_volumetricCloudHistoryView = VK_NULL_HANDLE;
    g_volumetricCloudResolvedImage = VK_NULL_HANDLE;
    g_volumetricCloudResolvedMemory = VK_NULL_HANDLE;
    g_volumetricCloudResolvedView = VK_NULL_HANDLE;
    g_volumetricCloudDepthImage = VK_NULL_HANDLE;
    g_volumetricCloudDepthMemory = VK_NULL_HANDLE;
    g_volumetricCloudDepthView = VK_NULL_HANDLE;
    g_volumetricCloudHistoryDepthImage = VK_NULL_HANDLE;
    g_volumetricCloudHistoryDepthMemory = VK_NULL_HANDLE;
    g_volumetricCloudHistoryDepthView = VK_NULL_HANDLE;
    g_volumetricCloudSampler = VK_NULL_HANDLE;
    g_volumetricCloudRenderSetLayout = VK_NULL_HANDLE;
    g_volumetricCloudResolveSetLayout = VK_NULL_HANDLE;
    g_volumetricCloudCompositeSetLayout = VK_NULL_HANDLE;
    g_volumetricCloudDescriptorPool = VK_NULL_HANDLE;
    g_volumetricCloudRenderSet = VK_NULL_HANDLE;
    g_volumetricCloudResolveSet = VK_NULL_HANDLE;
    g_volumetricCloudCompositeSet = VK_NULL_HANDLE;
    g_volumetricCloudRenderPipelineLayout = VK_NULL_HANDLE;
    g_volumetricCloudResolvePipelineLayout = VK_NULL_HANDLE;
    g_volumetricCloudCompositePipelineLayout = VK_NULL_HANDLE;
    g_volumetricCloudRenderPipeline = VK_NULL_HANDLE;
    g_volumetricCloudResolvePipeline = VK_NULL_HANDLE;
    g_volumetricCloudCompositePipeline = VK_NULL_HANDLE;
    g_volumetricCloudWidth = 0u;
    g_volumetricCloudHeight = 0u;
    g_volumetricCloudLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    g_volumetricCloudHistoryLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    g_volumetricCloudResolvedLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    g_volumetricCloudDepthLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    g_volumetricCloudHistoryDepthLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    g_volumetricCloudHistoryNeedsClear.store(true);
}

static void createWaterCausticResources()
{
    if(g_waterCausticImage != VK_NULL_HANDLE)
    {
        return;
    }

    createWaterSimulationResources();

    VkFormatProperties formatProperties{};
    vkGetPhysicalDeviceFormatProperties(
        g_physicalDevice,
        VK_FORMAT_R16G16_SFLOAT,
        &formatProperties
    );
    const VkFormatFeatureFlags requiredFeatures =
        VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT |
        VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
    if((formatProperties.optimalTilingFeatures & requiredFeatures) !=
       requiredFeatures)
    {
        throw std::runtime_error(
            "VK_FORMAT_R16G16_SFLOAT cannot be sampled and stored for water caustics"
        );
    }

    VkImageCreateInfo imageInfo{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = VK_FORMAT_R16G16_SFLOAT;
    imageInfo.extent = {
        WATER_CAUSTIC_RESOLUTION,
        WATER_CAUSTIC_RESOLUTION,
        1u
    };
    imageInfo.mipLevels = 1u;
    imageInfo.arrayLayers = 1u;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT |
                      VK_IMAGE_USAGE_STORAGE_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    check(
        vkCreateImage(g_device, &imageInfo, nullptr, &g_waterCausticImage),
        "water caustic create image failed"
    );

    VkMemoryRequirements requirements{};
    vkGetImageMemoryRequirements(g_device, g_waterCausticImage, &requirements);
    VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    allocation.allocationSize = requirements.size;
    allocation.memoryTypeIndex = findMemoryType(
        g_physicalDevice,
        requirements.memoryTypeBits,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
    );
    check(
        vkAllocateMemory(g_device, &allocation, nullptr, &g_waterCausticMemory),
        "water caustic allocate image memory failed"
    );
    check(
        vkBindImageMemory(g_device, g_waterCausticImage, g_waterCausticMemory, 0),
        "water caustic bind image memory failed"
    );

    VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    viewInfo.image = g_waterCausticImage;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_R16G16_SFLOAT;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.baseMipLevel = 0u;
    viewInfo.subresourceRange.levelCount = 1u;
    viewInfo.subresourceRange.baseArrayLayer = 0u;
    viewInfo.subresourceRange.layerCount = 1u;
    check(
        vkCreateImageView(g_device, &viewInfo, nullptr, &g_waterCausticView),
        "water caustic create image view failed"
    );

    VkSamplerCreateInfo samplerInfo{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    samplerInfo.magFilter = VK_FILTER_LINEAR;
    samplerInfo.minFilter = VK_FILTER_LINEAR;
    samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.minLod = 0.0f;
    samplerInfo.maxLod = 0.0f;
    check(
        vkCreateSampler(g_device, &samplerInfo, nullptr, &g_waterCausticSampler),
        "water caustic create sampler failed"
    );

    VkDescriptorSetLayoutBinding bindings[3]{};
    bindings[0].binding = 0u;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bindings[0].descriptorCount = 1u;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[1].binding = 1u;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[1].descriptorCount = 1u;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[2].binding = 2u;
    bindings[2].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    bindings[2].descriptorCount = 1u;
    bindings[2].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutCreateInfo layoutInfo{
        VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO
    };
    layoutInfo.bindingCount = 3u;
    layoutInfo.pBindings = bindings;
    check(
        vkCreateDescriptorSetLayout(
            g_device,
            &layoutInfo,
            nullptr,
            &g_waterCausticDescriptorSetLayout
        ),
        "water caustic create descriptor layout failed"
    );

    VkDescriptorPoolSize poolSizes[3] = {
        { VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1u },
        { VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1u },
        { VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1u }
    };
    VkDescriptorPoolCreateInfo poolInfo{
        VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO
    };
    poolInfo.maxSets = 1u;
    poolInfo.poolSizeCount = 3u;
    poolInfo.pPoolSizes = poolSizes;
    check(
        vkCreateDescriptorPool(
            g_device,
            &poolInfo,
            nullptr,
            &g_waterCausticDescriptorPool
        ),
        "water caustic create descriptor pool failed"
    );

    VkDescriptorSetAllocateInfo allocationInfo{
        VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO
    };
    allocationInfo.descriptorPool = g_waterCausticDescriptorPool;
    allocationInfo.descriptorSetCount = 1u;
    allocationInfo.pSetLayouts = &g_waterCausticDescriptorSetLayout;
    check(
        vkAllocateDescriptorSets(
            g_device,
            &allocationInfo,
            &g_waterCausticDescriptorSet
        ),
        "water caustic allocate descriptor set failed"
    );

    VkDescriptorImageInfo stateInfo{};
    stateInfo.sampler = g_waterSimSampler;
    stateInfo.imageView = g_waterSimView;
    stateInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo causticInfo{};
    causticInfo.imageView = g_waterCausticView;
    causticInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorBufferInfo cameraInfo{};
    cameraInfo.buffer = g_cameraUboBuffer;
    cameraInfo.range = sizeof(CameraUBO);

    VkWriteDescriptorSet writes[3]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = g_waterCausticDescriptorSet;
    writes[0].dstBinding = 0u;
    writes[0].descriptorCount = 1u;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[0].pImageInfo = &stateInfo;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = g_waterCausticDescriptorSet;
    writes[1].dstBinding = 1u;
    writes[1].descriptorCount = 1u;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[1].pImageInfo = &causticInfo;
    writes[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[2].dstSet = g_waterCausticDescriptorSet;
    writes[2].dstBinding = 2u;
    writes[2].descriptorCount = 1u;
    writes[2].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    writes[2].pBufferInfo = &cameraInfo;
    vkUpdateDescriptorSets(g_device, 3u, writes, 0u, nullptr);

    g_waterCausticLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    updateWaterCausticRayDescriptor();
}

static void createWaterCausticPipeline()
{
    if(g_waterCausticPipeline != VK_NULL_HANDLE)
    {
        return;
    }

    createWaterCausticResources();
    const std::vector<uint8_t> code =
        loadSpv("shaders/water_caustics.comp.spv");
    VkShaderModule shaderModule = createShaderModule(g_device, code);

    try
    {
        VkPipelineLayoutCreateInfo layoutInfo{
            VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO
        };
        layoutInfo.setLayoutCount = 1u;
        layoutInfo.pSetLayouts = &g_waterCausticDescriptorSetLayout;
        check(
            vkCreatePipelineLayout(
                g_device,
                &layoutInfo,
                nullptr,
                &g_waterCausticPipelineLayout
            ),
            "water caustic create pipeline layout failed"
        );

        VkPipelineShaderStageCreateInfo stage{
            VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO
        };
        stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        stage.module = shaderModule;
        stage.pName = "main";
        VkComputePipelineCreateInfo pipelineInfo{
            VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO
        };
        pipelineInfo.stage = stage;
        pipelineInfo.layout = g_waterCausticPipelineLayout;
        check(
            vkCreateComputePipelines(
                g_device,
                VK_NULL_HANDLE,
                1u,
                &pipelineInfo,
                nullptr,
                &g_waterCausticPipeline
            ),
            "water caustic create pipeline failed"
        );
    }
    catch(...)
    {
        if(g_waterCausticPipelineLayout != VK_NULL_HANDLE)
        {
            vkDestroyPipelineLayout(g_device, g_waterCausticPipelineLayout, nullptr);
            g_waterCausticPipelineLayout = VK_NULL_HANDLE;
        }
        vkDestroyShaderModule(g_device, shaderModule, nullptr);
        throw;
    }

    vkDestroyShaderModule(g_device, shaderModule, nullptr);
    RTLog("[WATER CAUSTIC] 256x256 wavefront-area compute pipeline created");
}

static void createWaterSimulationResources()
{
    if(g_waterSimImage != VK_NULL_HANDLE)
    {
        return;
    }

    VkFormatProperties formatProperties{};
    vkGetPhysicalDeviceFormatProperties(
        g_physicalDevice,
        VK_FORMAT_R16G16_SFLOAT,
        &formatProperties
    );
    const VkFormatFeatureFlags requiredFeatures =
        VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT |
        VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
    if((formatProperties.optimalTilingFeatures & requiredFeatures) !=
       requiredFeatures)
    {
        throw std::runtime_error(
            "VK_FORMAT_R16G16_SFLOAT cannot be sampled and stored for water simulation"
        );
    }

    VkImageCreateInfo imageInfo{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = VK_FORMAT_R16G16_SFLOAT;
    imageInfo.extent = { WATER_SIM_RESOLUTION, WATER_SIM_RESOLUTION, 1u };
    imageInfo.mipLevels = 1u;
    imageInfo.arrayLayers = 2u;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT |
                      VK_IMAGE_USAGE_STORAGE_BIT |
                      VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    check(
        vkCreateImage(g_device, &imageInfo, nullptr, &g_waterSimImage),
        "water simulation create image failed"
    );

    VkMemoryRequirements requirements{};
    vkGetImageMemoryRequirements(g_device, g_waterSimImage, &requirements);
    VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    allocation.allocationSize = requirements.size;
    allocation.memoryTypeIndex = findMemoryType(
        g_physicalDevice,
        requirements.memoryTypeBits,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
    );
    check(
        vkAllocateMemory(g_device, &allocation, nullptr, &g_waterSimMemory),
        "water simulation allocate image memory failed"
    );
    check(
        vkBindImageMemory(g_device, g_waterSimImage, g_waterSimMemory, 0),
        "water simulation bind image memory failed"
    );

    VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    viewInfo.image = g_waterSimImage;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D_ARRAY;
    viewInfo.format = VK_FORMAT_R16G16_SFLOAT;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.baseMipLevel = 0u;
    viewInfo.subresourceRange.levelCount = 1u;
    viewInfo.subresourceRange.baseArrayLayer = 0u;
    viewInfo.subresourceRange.layerCount = 2u;
    check(
        vkCreateImageView(g_device, &viewInfo, nullptr, &g_waterSimView),
        "water simulation create image view failed"
    );

    VkSamplerCreateInfo samplerInfo{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    samplerInfo.magFilter = VK_FILTER_LINEAR;
    samplerInfo.minFilter = VK_FILTER_LINEAR;
    samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.minLod = 0.0f;
    samplerInfo.maxLod = 0.0f;
    check(
        vkCreateSampler(g_device, &samplerInfo, nullptr, &g_waterSimSampler),
        "water simulation create sampler failed"
    );

    VkDescriptorSetLayoutBinding bindings[3]{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[2].binding = 2;
    bindings[2].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    bindings[2].descriptorCount = 1;
    bindings[2].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutCreateInfo layoutInfo{
        VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO
    };
    layoutInfo.bindingCount = 3u;
    layoutInfo.pBindings = bindings;
    check(
        vkCreateDescriptorSetLayout(
            g_device,
            &layoutInfo,
            nullptr,
            &g_waterSimDescriptorSetLayout
        ),
        "water simulation create descriptor layout failed"
    );

    VkDescriptorPoolSize poolSizes[3] = {
        { VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1u },
        { VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1u },
        { VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1u }
    };
    VkDescriptorPoolCreateInfo poolInfo{
        VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO
    };
    poolInfo.maxSets = 1u;
    poolInfo.poolSizeCount = 3u;
    poolInfo.pPoolSizes = poolSizes;
    check(
        vkCreateDescriptorPool(
            g_device,
            &poolInfo,
            nullptr,
            &g_waterSimDescriptorPool
        ),
        "water simulation create descriptor pool failed"
    );

    VkDescriptorSetAllocateInfo allocationInfo{
        VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO
    };
    allocationInfo.descriptorPool = g_waterSimDescriptorPool;
    allocationInfo.descriptorSetCount = 1u;
    allocationInfo.pSetLayouts = &g_waterSimDescriptorSetLayout;
    check(
        vkAllocateDescriptorSets(g_device, &allocationInfo, &g_waterSimDescriptorSet),
        "water simulation allocate descriptor set failed"
    );

    VkDescriptorImageInfo sampledInfo{};
    sampledInfo.sampler = g_waterSimSampler;
    sampledInfo.imageView = g_waterSimView;
    sampledInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo storageInfo{};
    storageInfo.imageView = g_waterSimView;
    storageInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorBufferInfo cameraInfo{};
    cameraInfo.buffer = g_cameraUboBuffer;
    cameraInfo.range = sizeof(CameraUBO);

    VkWriteDescriptorSet writes[3]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = g_waterSimDescriptorSet;
    writes[0].dstBinding = 0u;
    writes[0].descriptorCount = 1u;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[0].pImageInfo = &sampledInfo;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = g_waterSimDescriptorSet;
    writes[1].dstBinding = 1u;
    writes[1].descriptorCount = 1u;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[1].pImageInfo = &storageInfo;
    writes[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[2].dstSet = g_waterSimDescriptorSet;
    writes[2].dstBinding = 2u;
    writes[2].descriptorCount = 1u;
    writes[2].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    writes[2].pBufferInfo = &cameraInfo;
    vkUpdateDescriptorSets(g_device, 3u, writes, 0u, nullptr);

    g_waterSimLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    g_waterSimNeedsClear = true;
    updateWaterSimulationRayDescriptor();
}

static void createWaterSimulationPipeline()
{
    if(g_waterSimPipeline != VK_NULL_HANDLE)
    {
        return;
    }

    createWaterSimulationResources();
    const std::vector<uint8_t> code =
        loadSpv("shaders/water_sim.comp.spv");
    VkShaderModule shaderModule = createShaderModule(g_device, code);

    try
    {
        VkPushConstantRange pushRange{};
        pushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        pushRange.size = sizeof(WaterSimPushConstants);
        VkPipelineLayoutCreateInfo layoutInfo{
            VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO
        };
        layoutInfo.setLayoutCount = 1u;
        layoutInfo.pSetLayouts = &g_waterSimDescriptorSetLayout;
        layoutInfo.pushConstantRangeCount = 1u;
        layoutInfo.pPushConstantRanges = &pushRange;
        check(
            vkCreatePipelineLayout(
                g_device,
                &layoutInfo,
                nullptr,
                &g_waterSimPipelineLayout
            ),
            "water simulation create pipeline layout failed"
        );

        VkPipelineShaderStageCreateInfo stage{
            VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO
        };
        stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        stage.module = shaderModule;
        stage.pName = "main";
        VkComputePipelineCreateInfo pipelineInfo{
            VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO
        };
        pipelineInfo.stage = stage;
        pipelineInfo.layout = g_waterSimPipelineLayout;
        check(
            vkCreateComputePipelines(
                g_device,
                VK_NULL_HANDLE,
                1u,
                &pipelineInfo,
                nullptr,
                &g_waterSimPipeline
            ),
            "water simulation create pipeline failed"
        );
    }
    catch(...)
    {
        if(g_waterSimPipelineLayout != VK_NULL_HANDLE)
        {
            vkDestroyPipelineLayout(g_device, g_waterSimPipelineLayout, nullptr);
            g_waterSimPipelineLayout = VK_NULL_HANDLE;
        }
        vkDestroyShaderModule(g_device, shaderModule, nullptr);
        throw;
    }

    vkDestroyShaderModule(g_device, shaderModule, nullptr);
    RTLog("[WATER SIM] Eclipse-style 512x512 RG16F compute pipeline created");
}

static void recordWaterSimulation(VkCommandBuffer cmd)
{
    if(cmd == VK_NULL_HANDLE ||
       g_waterSimPipeline == VK_NULL_HANDLE ||
       g_waterSimDescriptorSet == VK_NULL_HANDLE ||
       g_cameraUboMapped == nullptr)
    {
        return;
    }

    CameraUBO* ubo = static_cast<CameraUBO*>(g_cameraUboMapped);
    const bool playerInWater = ubo->playerInWater > 0.5f;
    if(!playerInWater && !g_waterSimPendingImpulse &&
       g_waterSimKeepAliveSeconds <= 0.0f)
    {
        if(!g_waterSimLoggedAwaitingWater)
        {
            RTLog("[WATER SIM] idle: awaiting player water entry");
            g_waterSimLoggedAwaitingWater = true;
        }
        return;
    }

    const auto now = std::chrono::steady_clock::now();
    if(!g_waterSimLastUpdateValid)
    {
        g_waterSimLastUpdate = now;
        g_waterSimLastUpdateValid = true;
    }
    const float elapsed = std::chrono::duration<float>(
        now - g_waterSimLastUpdate
    ).count();
    if(elapsed < WATER_SIM_UPDATE_SECONDS)
    {
        return;
    }
    g_waterSimLastUpdate = now;
    const float deltaSeconds = std::min(elapsed, 0.1f);

    if(playerInWater || g_waterSimPendingImpulse)
    {
        g_waterSimKeepAliveSeconds = 8.0f;
    }
    else
    {
        g_waterSimKeepAliveSeconds = std::max(
            0.0f,
            g_waterSimKeepAliveSeconds - deltaSeconds
        );
    }

    const float nextOriginX = ubo->playerX - 0.5f * WATER_SIM_WORLD_SIZE;
    const float nextOriginZ = ubo->playerZ - 0.5f * WATER_SIM_WORLD_SIZE;
    int32_t scrollX = 0;
    int32_t scrollZ = 0;
    if(!g_waterSimOriginValid)
    {
        g_waterSimOriginX = nextOriginX;
        g_waterSimOriginZ = nextOriginZ;
        g_waterSimOriginValid = true;
        g_waterSimNeedsClear = true;
    }
    else
    {
        scrollX = static_cast<int32_t>(std::lround(
            (nextOriginX - g_waterSimOriginX) / WATER_SIM_TEXEL_WORLD_SIZE
        ));
        scrollZ = static_cast<int32_t>(std::lround(
            (nextOriginZ - g_waterSimOriginZ) / WATER_SIM_TEXEL_WORLD_SIZE
        ));
        if(std::abs(scrollX) >= static_cast<int32_t>(WATER_SIM_RESOLUTION / 2u) ||
           std::abs(scrollZ) >= static_cast<int32_t>(WATER_SIM_RESOLUTION / 2u))
        {
            scrollX = 0;
            scrollZ = 0;
            g_waterSimNeedsClear = true;
        }
        g_waterSimOriginX = nextOriginX;
        g_waterSimOriginZ = nextOriginZ;
    }

    ubo->waterSurfaceY = g_waterSimLastSurfaceY;
    ubo->waterSimOriginX = g_waterSimOriginX;
    ubo->waterSimOriginZ = g_waterSimOriginZ;
    ubo->waterSimTexelWorldSize = WATER_SIM_TEXEL_WORLD_SIZE;
    ubo->waterSimReadLayer = g_waterSimReadLayer;
    // Do not let closest-hit sample the image before its first recorded clear
    // and compute update have established the GENERAL layout and zero state.
    ubo->waterSimEnabled =
        (playerInWater || g_waterSimPendingImpulse ||
         g_waterSimKeepAliveSeconds > 0.0f) ? 1u : 0u;

    VkImageMemoryBarrier imageBarrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    imageBarrier.image = g_waterSimImage;
    imageBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    imageBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    imageBarrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    imageBarrier.subresourceRange.baseMipLevel = 0u;
    imageBarrier.subresourceRange.levelCount = 1u;
    imageBarrier.subresourceRange.baseArrayLayer = 0u;
    imageBarrier.subresourceRange.layerCount = 2u;

    if(g_waterSimLayout == VK_IMAGE_LAYOUT_UNDEFINED)
    {
        imageBarrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        imageBarrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        imageBarrier.srcAccessMask = 0u;
        imageBarrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT |
                                     VK_ACCESS_SHADER_READ_BIT |
                                     VK_ACCESS_SHADER_WRITE_BIT;
        vkCmdPipelineBarrier(
            cmd,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0u,
            0u, nullptr, 0u, nullptr, 1u, &imageBarrier
        );
        g_waterSimLayout = VK_IMAGE_LAYOUT_GENERAL;
    }

    if(g_waterSimNeedsClear)
    {
        VkClearColorValue clearValue{};
        VkImageSubresourceRange range{};
        range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        range.levelCount = 1u;
        range.layerCount = 2u;
        vkCmdClearColorImage(
            cmd,
            g_waterSimImage,
            VK_IMAGE_LAYOUT_GENERAL,
            &clearValue,
            1u,
            &range
        );
        imageBarrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        imageBarrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        imageBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        imageBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT |
                                     VK_ACCESS_SHADER_WRITE_BIT;
        vkCmdPipelineBarrier(
            cmd,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0u,
            0u, nullptr, 0u, nullptr, 1u, &imageBarrier
        );
        g_waterSimNeedsClear = false;
    }
    else
    {
        imageBarrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        imageBarrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        imageBarrier.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
        imageBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT |
                                     VK_ACCESS_SHADER_WRITE_BIT;
        vkCmdPipelineBarrier(
            cmd,
            VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0u,
            0u, nullptr, 0u, nullptr, 1u, &imageBarrier
        );
    }

    WaterSimPushConstants push{};
    push.sourceLayer = g_waterSimReadLayer;
    push.destinationLayer = g_waterSimReadLayer ^ 1u;
    push.hasImpulse = g_waterSimPendingImpulse ? 1u : 0u;
    push.playerInWater = playerInWater ? 1u : 0u;
    push.deltaSeconds = deltaSeconds;
    push.impulseX = g_waterSimPendingImpulseX;
    push.impulseZ = g_waterSimPendingImpulseZ;
    push.impulseStrength = g_waterSimPendingImpulseStrength;
    push.scrollX = scrollX;
    push.scrollZ = scrollZ;

    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, g_waterSimPipeline);
    vkCmdBindDescriptorSets(
        cmd,
        VK_PIPELINE_BIND_POINT_COMPUTE,
        g_waterSimPipelineLayout,
        0u,
        1u,
        &g_waterSimDescriptorSet,
        0u,
        nullptr
    );
    vkCmdPushConstants(
        cmd,
        g_waterSimPipelineLayout,
        VK_SHADER_STAGE_COMPUTE_BIT,
        0u,
        sizeof(WaterSimPushConstants),
        &push
    );
    vkCmdDispatch(
        cmd,
        (WATER_SIM_RESOLUTION + 15u) / 16u,
        (WATER_SIM_RESOLUTION + 15u) / 16u,
        1u
    );

    if(!g_waterSimLoggedFirstDispatch)
    {
        RTLog(
            "[WATER SIM] first dispatch"
            " playerInWater=" + std::to_string(playerInWater ? 1 : 0) +
            " impulse=" + std::to_string(g_waterSimPendingImpulse ? 1 : 0) +
            " surfaceY=" + std::to_string(g_waterSimLastSurfaceY)
        );
        g_waterSimLoggedFirstDispatch = true;
    }

    imageBarrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    imageBarrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    imageBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    imageBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(
        cmd,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
        0u,
        0u, nullptr, 0u, nullptr, 1u, &imageBarrier
    );

    g_waterSimReadLayer = push.destinationLayer;
    ubo->waterSimReadLayer = g_waterSimReadLayer;

    if(!g_waterSimLoggedSampleContract)
    {
        std::ostringstream log;
        log
            << "[WATER SIM] sample contract"
            << " enabled=" << ubo->waterSimEnabled
            << " readLayer=" << g_waterSimReadLayer
            << " origin=(" << ubo->waterSimOriginX
            << "," << ubo->waterSimOriginZ << ")"
            << " texel=" << ubo->waterSimTexelWorldSize
            << " surfaceY=" << ubo->waterSurfaceY;
        RTLog(log.str());
        g_waterSimLoggedSampleContract = true;
    }

    g_waterSimPendingImpulse = false;
}

static void recordWaterCaustics(VkCommandBuffer cmd)
{
    if(cmd == VK_NULL_HANDLE ||
       g_waterCausticPipeline == VK_NULL_HANDLE ||
       g_waterCausticDescriptorSet == VK_NULL_HANDLE ||
       g_cameraUboMapped == nullptr)
    {
        return;
    }

    CameraUBO* ubo = static_cast<CameraUBO*>(g_cameraUboMapped);
    const auto now = std::chrono::steady_clock::now();
    if(g_waterCausticLastUpdateValid &&
       std::chrono::duration<float>(now - g_waterCausticLastUpdate).count() <
           WATER_CAUSTIC_UPDATE_SECONDS)
    {
        return;
    }
    g_waterCausticLastUpdate = now;
    g_waterCausticLastUpdateValid = true;

    // When the player is not interacting with water, this local optical map
    // still follows the entity so natural-wave caustics remain available.  It
    // never changes a live simulation origin, avoiding any sliding of ripples
    // or wakes inside the persistent RG16F state.
    if(ubo->waterSimEnabled == 0u)
    {
        g_waterSimOriginX = ubo->playerX - 0.5f * WATER_SIM_WORLD_SIZE;
        g_waterSimOriginZ = ubo->playerZ - 0.5f * WATER_SIM_WORLD_SIZE;
        g_waterSimOriginValid = true;
        ubo->waterSimOriginX = g_waterSimOriginX;
        ubo->waterSimOriginZ = g_waterSimOriginZ;
        ubo->waterSimTexelWorldSize = WATER_SIM_TEXEL_WORLD_SIZE;
    }

    VkImageMemoryBarrier causticBarrier{
        VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER
    };
    causticBarrier.image = g_waterCausticImage;
    causticBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    causticBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    causticBarrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    causticBarrier.subresourceRange.baseMipLevel = 0u;
    causticBarrier.subresourceRange.levelCount = 1u;
    causticBarrier.subresourceRange.baseArrayLayer = 0u;
    causticBarrier.subresourceRange.layerCount = 1u;

    if(g_waterCausticLayout == VK_IMAGE_LAYOUT_UNDEFINED)
    {
        causticBarrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        causticBarrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        causticBarrier.srcAccessMask = 0u;
        causticBarrier.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        vkCmdPipelineBarrier(
            cmd,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0u,
            0u, nullptr, 0u, nullptr, 1u, &causticBarrier
        );
        g_waterCausticLayout = VK_IMAGE_LAYOUT_GENERAL;
    }
    else
    {
        causticBarrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        causticBarrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        causticBarrier.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
        causticBarrier.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        vkCmdPipelineBarrier(
            cmd,
            VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0u,
            0u, nullptr, 0u, nullptr, 1u, &causticBarrier
        );
    }

    // The input state may have been written by the 30 Hz ripple simulation
    // immediately above or read by the previous ray-tracing frame.
    VkImageMemoryBarrier stateBarrier{
        VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER
    };
    stateBarrier.image = g_waterSimImage;
    stateBarrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    stateBarrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    stateBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    stateBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    stateBarrier.srcAccessMask = VK_ACCESS_SHADER_READ_BIT |
                                 VK_ACCESS_SHADER_WRITE_BIT;
    stateBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    stateBarrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    stateBarrier.subresourceRange.baseMipLevel = 0u;
    stateBarrier.subresourceRange.levelCount = 1u;
    stateBarrier.subresourceRange.baseArrayLayer = 0u;
    stateBarrier.subresourceRange.layerCount = 2u;
    vkCmdPipelineBarrier(
        cmd,
        VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR |
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0u,
        0u, nullptr, 0u, nullptr, 1u, &stateBarrier
    );

    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, g_waterCausticPipeline);
    vkCmdBindDescriptorSets(
        cmd,
        VK_PIPELINE_BIND_POINT_COMPUTE,
        g_waterCausticPipelineLayout,
        0u,
        1u,
        &g_waterCausticDescriptorSet,
        0u,
        nullptr
    );
    vkCmdDispatch(
        cmd,
        (WATER_CAUSTIC_RESOLUTION + 15u) / 16u,
        (WATER_CAUSTIC_RESOLUTION + 15u) / 16u,
        1u
    );

    causticBarrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    causticBarrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    causticBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    causticBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(
        cmd,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
        0u,
        0u, nullptr, 0u, nullptr, 1u, &causticBarrier
    );
}

static void destroyWaterCausticResources()
{
    if(g_waterCausticPipeline != VK_NULL_HANDLE)
    {
        vkDestroyPipeline(g_device, g_waterCausticPipeline, nullptr);
        g_waterCausticPipeline = VK_NULL_HANDLE;
    }
    if(g_waterCausticPipelineLayout != VK_NULL_HANDLE)
    {
        vkDestroyPipelineLayout(g_device, g_waterCausticPipelineLayout, nullptr);
        g_waterCausticPipelineLayout = VK_NULL_HANDLE;
    }
    if(g_waterCausticDescriptorPool != VK_NULL_HANDLE)
    {
        vkDestroyDescriptorPool(g_device, g_waterCausticDescriptorPool, nullptr);
        g_waterCausticDescriptorPool = VK_NULL_HANDLE;
        g_waterCausticDescriptorSet = VK_NULL_HANDLE;
    }
    if(g_waterCausticDescriptorSetLayout != VK_NULL_HANDLE)
    {
        vkDestroyDescriptorSetLayout(
            g_device,
            g_waterCausticDescriptorSetLayout,
            nullptr
        );
        g_waterCausticDescriptorSetLayout = VK_NULL_HANDLE;
    }
    if(g_waterCausticSampler != VK_NULL_HANDLE)
    {
        vkDestroySampler(g_device, g_waterCausticSampler, nullptr);
        g_waterCausticSampler = VK_NULL_HANDLE;
    }
    if(g_waterCausticView != VK_NULL_HANDLE)
    {
        vkDestroyImageView(g_device, g_waterCausticView, nullptr);
        g_waterCausticView = VK_NULL_HANDLE;
    }
    if(g_waterCausticImage != VK_NULL_HANDLE)
    {
        vkDestroyImage(g_device, g_waterCausticImage, nullptr);
        g_waterCausticImage = VK_NULL_HANDLE;
    }
    if(g_waterCausticMemory != VK_NULL_HANDLE)
    {
        vkFreeMemory(g_device, g_waterCausticMemory, nullptr);
        g_waterCausticMemory = VK_NULL_HANDLE;
    }
    g_waterCausticLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    g_waterCausticLastUpdateValid = false;
}

static void destroyWaterSimulationResources()
{
    if(g_waterSimPipeline != VK_NULL_HANDLE)
    {
        vkDestroyPipeline(g_device, g_waterSimPipeline, nullptr);
        g_waterSimPipeline = VK_NULL_HANDLE;
    }
    if(g_waterSimPipelineLayout != VK_NULL_HANDLE)
    {
        vkDestroyPipelineLayout(g_device, g_waterSimPipelineLayout, nullptr);
        g_waterSimPipelineLayout = VK_NULL_HANDLE;
    }
    if(g_waterSimDescriptorPool != VK_NULL_HANDLE)
    {
        vkDestroyDescriptorPool(g_device, g_waterSimDescriptorPool, nullptr);
        g_waterSimDescriptorPool = VK_NULL_HANDLE;
        g_waterSimDescriptorSet = VK_NULL_HANDLE;
    }
    if(g_waterSimDescriptorSetLayout != VK_NULL_HANDLE)
    {
        vkDestroyDescriptorSetLayout(
            g_device,
            g_waterSimDescriptorSetLayout,
            nullptr
        );
        g_waterSimDescriptorSetLayout = VK_NULL_HANDLE;
    }
    if(g_waterSimSampler != VK_NULL_HANDLE)
    {
        vkDestroySampler(g_device, g_waterSimSampler, nullptr);
        g_waterSimSampler = VK_NULL_HANDLE;
    }
    if(g_waterSimView != VK_NULL_HANDLE)
    {
        vkDestroyImageView(g_device, g_waterSimView, nullptr);
        g_waterSimView = VK_NULL_HANDLE;
    }
    if(g_waterSimImage != VK_NULL_HANDLE)
    {
        vkDestroyImage(g_device, g_waterSimImage, nullptr);
        g_waterSimImage = VK_NULL_HANDLE;
    }
    if(g_waterSimMemory != VK_NULL_HANDLE)
    {
        vkFreeMemory(g_device, g_waterSimMemory, nullptr);
        g_waterSimMemory = VK_NULL_HANDLE;
    }

    g_waterSimLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    g_waterSimReadLayer = 0u;
    g_waterSimNeedsClear = true;
    g_waterSimPlayerStateValid = false;
    g_waterSimPlayerWasInWater = false;
    g_waterSimPendingImpulse = false;
    g_waterSimOriginValid = false;
    g_waterSimLastUpdateValid = false;
    g_waterSimKeepAliveSeconds = 0.0f;
    g_waterSimLoggedAwaitingWater = false;
    g_waterSimLoggedFirstDispatch = false;
    g_waterSimLoggedSampleContract = false;
}

static void createRayTracingSBT(){
    constexpr uint32_t SBT_GROUP_RAYGEN = 0u;
    constexpr uint32_t SBT_GROUP_OPAQUE = 1u;
    constexpr uint32_t SBT_GROUP_MISS = 2u;
    constexpr uint32_t SBT_GROUP_ALPHA = 3u;
    constexpr uint32_t SBT_GROUP_WATER = 4u;
    constexpr uint32_t SBT_GROUP_RESTIR_FINAL_RAYGEN = 5u;
    constexpr uint32_t SBT_GROUP_LOCAL_VISIBILITY_MISS = 6u;
    constexpr uint32_t SBT_GROUP_LOCAL_VISIBILITY_SHARED = 7u;
    constexpr uint32_t SBT_GROUP_LOCAL_VISIBILITY_ALPHA = 8u;
    constexpr uint32_t SBT_PIPELINE_GROUP_COUNT = 9u;
    VkPhysicalDeviceRayTracingPipelinePropertiesKHR rp{
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_PROPERTIES_KHR
    };

    VkPhysicalDeviceProperties2 props{
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2
    };
    props.pNext = &rp;

    auto fn =
        reinterpret_cast<PFN_vkGetPhysicalDeviceProperties2>(
            vkGetInstanceProcAddr(g_instance, "vkGetPhysicalDeviceProperties2")
        );

    if(!fn)
        throw std::runtime_error("vkGetPhysicalDeviceProperties2 unavailable");

    fn(g_physicalDevice, &props);

    uint32_t hs = rp.shaderGroupHandleSize;
    uint32_t ha = rp.shaderGroupHandleAlignment;
    uint32_t ba = rp.shaderGroupBaseAlignment;

    g_sbtBaseAlignment = ba;

    auto alignUp = [](uint32_t v, uint32_t a){
        return (v + a - 1) & ~(a - 1);
    };

    g_handleSizeAligned = alignUp(hs, ha);

    g_raygenOffset = 0;

    g_restirFinalRaygenOffset =
        alignUp(
            g_raygenOffset + g_handleSizeAligned,
            ba
        );

    g_missOffset =
        alignUp(
            g_restirFinalRaygenOffset + g_handleSizeAligned,
            ba
        );

    g_hitOffset =
        alignUp(
            g_missOffset +
                SBT_MISS_RECORD_COUNT * g_handleSizeAligned,
            ba
        );

    g_sbtSize =
        alignUp(
            g_hitOffset +
            SBT_HIT_RECORD_COUNT * g_handleSizeAligned,
            ba
        );

    uint32_t count = SBT_PIPELINE_GROUP_COUNT;
    std::vector<uint8_t> handles((size_t)count * hs);

    auto get =
        reinterpret_cast<PFN_vkGetRayTracingShaderGroupHandlesKHR>(
            vkGetDeviceProcAddr(g_device, "vkGetRayTracingShaderGroupHandlesKHR")
        );

    if(!get)
        throw std::runtime_error("vkGetRayTracingShaderGroupHandlesKHR unavailable");

    check(
        get(
            g_device,
            g_rayTracingPipeline,
            0,
            count,
            handles.size(),
            handles.data()
        ),
        "Failed to get RT shader handles"
    );

    std::vector<uint8_t> data((size_t)g_sbtSize, 0);

    // group[0] = primary raygen
    std::memcpy(
        data.data() + g_raygenOffset,
        handles.data() + SBT_GROUP_RAYGEN * hs,
        hs
    );

    // group[5] = lightweight ReSTIR DI final raygen
    std::memcpy(
        data.data() + g_restirFinalRaygenOffset,
        handles.data() + SBT_GROUP_RESTIR_FINAL_RAYGEN * hs,
        hs
    );

    // miss record 0 = normal renderer miss
    std::memcpy(
        data.data() + g_missOffset,
        handles.data() + SBT_GROUP_MISS * hs,
        hs
    );

    // miss record 1 = compact local-light visibility miss
    std::memcpy(
        data.data() + g_missOffset + g_handleSizeAligned,
        handles.data() + SBT_GROUP_LOCAL_VISIBILITY_MISS * hs,
        hs
    );

    const uint32_t recordGroups[SBT_HIT_RECORD_COUNT] = {
        // Ray type 0: existing renderer.
        SBT_GROUP_OPAQUE,
        SBT_GROUP_OPAQUE,
        SBT_GROUP_ALPHA,
        SBT_GROUP_WATER,
        // Entity geometry must use rt_test.rchit/rahit (group 1), which
        // understands Binding 36 and still performs alpha cutout in Any-Hit.
        SBT_GROUP_OPAQUE,

        // Ray type 1: compact ReSTIR local-light visibility.  Geometry class
        // ordering exactly mirrors records 0..4 so instance offsets remain
        // untouched and traceRayEXT only adds +5.
        SBT_GROUP_LOCAL_VISIBILITY_SHARED,
        SBT_GROUP_LOCAL_VISIBILITY_SHARED,
        SBT_GROUP_LOCAL_VISIBILITY_ALPHA,
        SBT_GROUP_LOCAL_VISIBILITY_SHARED,
        SBT_GROUP_LOCAL_VISIBILITY_SHARED
    };
    for(uint32_t record = 0u; record < SBT_HIT_RECORD_COUNT; ++record)
    {
        std::memcpy(
            data.data() + g_hitOffset + record * g_handleSizeAligned,
            handles.data() + recordGroups[record] * hs,
            hs
        );
    }

    createBuffer(
        g_device,
        g_physicalDevice,
        g_sbtSize,
        VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR |
        VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
        VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
        g_sbtBuffer,
        g_sbtMemory
    );

    void* mapped = nullptr;
    check(
        vkMapMemory(
            g_device,
            g_sbtMemory,
            0,
            g_sbtSize,
            0,
            &mapped
        ),
        "Failed to map SBT memory"
    );

    std::memcpy(mapped, data.data(), data.size());
    vkUnmapMemory(g_device, g_sbtMemory);

    g_sbtDeviceAddress =
        getBufferDeviceAddress(g_device, g_sbtBuffer);

    g_raygenRegion.deviceAddress =
        g_sbtDeviceAddress + g_raygenOffset;
    g_raygenRegion.stride =
        g_handleSizeAligned;
    g_raygenRegion.size =
        g_handleSizeAligned;

    g_restirFinalRaygenRegion.deviceAddress =
        g_sbtDeviceAddress + g_restirFinalRaygenOffset;
    g_restirFinalRaygenRegion.stride =
        g_handleSizeAligned;
    g_restirFinalRaygenRegion.size =
        g_handleSizeAligned;

    g_missRegion.deviceAddress =
        g_sbtDeviceAddress + g_missOffset;
    g_missRegion.stride =
        g_handleSizeAligned;
    g_missRegion.size =
        SBT_MISS_RECORD_COUNT * g_handleSizeAligned;

    g_hitRegion.deviceAddress =
        g_sbtDeviceAddress + g_hitOffset;
    g_hitRegion.stride =
        g_handleSizeAligned;
    g_hitRegion.size =
        SBT_HIT_RECORD_COUNT * g_handleSizeAligned;

    g_callableRegion.deviceAddress = 0;
    g_callableRegion.stride = 0;
    g_callableRegion.size = 0;

    RTLog(
        "[RT TRANSLUCENT SBT] groups=9"
        " normalRecords=0..4"
        " localVisibilityRecords=5..9"
        " missRecords=2"
        " localVisibilityOffset=" +
        std::to_string(SBT_LOCAL_VISIBILITY_RECORD_OFFSET)
    );
}


extern "C"
JNIEXPORT jboolean JNICALL Java_com_example_vulkan_NativeVulkan_probeHDRSurface(
    JNIEnv*,
    jclass,
    jlong surfaceHandle)
{
    if(
        g_physicalDevice == VK_NULL_HANDLE ||
        surfaceHandle == 0
    )
    {
        fprintf(
            stderr,
            "[HDR-2.2] INVALID HANDLE"
            " physicalDevice=%p"
            " surface=0x%llx\n",
            (void*)g_physicalDevice,
            static_cast<unsigned long long>(
                surfaceHandle
            )
        );

        fflush(stderr);

        return JNI_FALSE;
    }


    VkSurfaceKHR surface =
        reinterpret_cast<VkSurfaceKHR>(
            static_cast<uintptr_t>(
                surfaceHandle
            )
        );


    uint32_t formatCount =
        0;


    VkResult result =
        vkGetPhysicalDeviceSurfaceFormatsKHR(
            g_physicalDevice,
            surface,
            &formatCount,
            nullptr
        );


    fprintf(
        stderr,
        "[HDR-2.2]"
        " firstQuery=%d"
        " formatCount=%u\n",
        static_cast<int>(
            result
        ),
        formatCount
    );


    if(
        result != VK_SUCCESS ||
        formatCount == 0
    )
    {
        fflush(stderr);

        return JNI_FALSE;
    }


    std::vector<VkSurfaceFormatKHR>
        formats(
            formatCount
        );


    result =
        vkGetPhysicalDeviceSurfaceFormatsKHR(
            g_physicalDevice,
            surface,
            &formatCount,
            formats.data()
        );


    fprintf(
        stderr,
        "========== [HDR-2.2 SURFACE FORMATS BEGIN] ==========\n"
    );


    bool hdr10Found =
        false;


    for(
        uint32_t i = 0;
        i < formatCount;
        ++i
    )
    {
        const VkFormat format =
            formats[i].format;


        const VkColorSpaceKHR colorSpace =
            formats[i].colorSpace;


        const bool isHDR10 =
            colorSpace ==
            VK_COLOR_SPACE_HDR10_ST2084_EXT;


        if(isHDR10)
        {
            hdr10Found =
                true;
        }


        fprintf(
            stderr,
            "[HDR-2.2 FORMAT %u]"
            " VkFormat=%d"
            " ColorSpace=%d"
            " HDR10_ST2084=%d\n",
            i,
            static_cast<int>(
                format
            ),
            static_cast<int>(
                colorSpace
            ),
            isHDR10
                ? 1
                : 0
        );
    }


    fprintf(
        stderr,
        "[HDR-2.2 RESULT]"
        " HDR10_ST2084_SUPPORTED=%d\n",
        hdr10Found
            ? 1
            : 0
    );


    fprintf(
        stderr,
        "========== [HDR-2.2 SURFACE FORMATS END] ==========\n"
    );


    fflush(stderr);


    return result == VK_SUCCESS
        ? JNI_TRUE
        : JNI_FALSE;
}



extern "C"

JNIEXPORT void JNICALL Java_com_example_vulkan_NativeVulkan_uploadPBRAtlases(
    JNIEnv* env, jclass clazz, jlong normalPtr, jlong specularPtr, jint width, jint height)
{
    if (normalPtr == 0 || specularPtr == 0 || width <= 0 || height <= 0) return;

    // 物理防线 2：必须确保 Vulkan Device 处于可用状态
    if (g_device == VK_NULL_HANDLE) return;

    // 全局管线隔离，确保没有任何渲染命令正在空中飞行
    FGDeviceWaitIdle(g_device);

    // This call is issued from the block-atlas reload. Retire every
    // TextureAtlas-backed dynamic view while the device is known idle.
    invalidateDynamicTextureBindingsForAtlasReload();

    // 释放可能存在的旧版图集内存
    if (g_normalAtlasView)
    {
        vkDestroyImageView(
            g_device,
            g_normalAtlasView,
            nullptr
        );

        g_normalAtlasView =
            VK_NULL_HANDLE;
    }

    if (g_normalAtlasImage)
    {
        vkDestroyImage(
            g_device,
            g_normalAtlasImage,
            nullptr
        );

        g_normalAtlasImage =
            VK_NULL_HANDLE;
    }

    if (g_normalAtlasMemory)
    {
        vkFreeMemory(
            g_device,
            g_normalAtlasMemory,
            nullptr
        );

        g_normalAtlasMemory =
            VK_NULL_HANDLE;
    }


    if (g_specularAtlasView)
    {
        vkDestroyImageView(
            g_device,
            g_specularAtlasView,
            nullptr
        );

        g_specularAtlasView =
            VK_NULL_HANDLE;
    }

    if (g_specularAtlasImage)
    {
        vkDestroyImage(
            g_device,
            g_specularAtlasImage,
            nullptr
        );

        g_specularAtlasImage =
            VK_NULL_HANDLE;
    }

    if (g_specularAtlasMemory)
    {
        vkFreeMemory(
            g_device,
            g_specularAtlasMemory,
            nullptr
        );

        g_specularAtlasMemory =
            VK_NULL_HANDLE;
    }

    void* normalPixels = reinterpret_cast<void*>(normalPtr);
    void* specularPixels = reinterpret_cast<void*>(specularPtr);
    // 将内存地址强制泵入 VRAM
    uploadTextureToVRAM(normalPixels, static_cast<uint32_t>(width), static_cast<uint32_t>(height), g_normalAtlasImage, g_normalAtlasMemory, g_normalAtlasView);
    uploadTextureToVRAM(specularPixels, static_cast<uint32_t>(width), static_cast<uint32_t>(height), g_specularAtlasImage, g_specularAtlasMemory, g_specularAtlasView);

    // 设置全局脏标记（等待下一阶段在 renderPass 拦截中绑定至描述符 Binding 4 和 5）
    g_pbrAtlasesDirty = true;

    RTLog("[PBR VRAM] CPU -> GPU Native VRAM Copy Success. Pending Descriptor Update.");
}


extern "C"
JNIEXPORT void JNICALL Java_com_example_vulkan_NativeVulkan_setStreamlineQueuePlan(
    JNIEnv*,
    jclass,
    jint graphicsFamily,
    jint graphicsStartIndex,
    jint computeFamily,
    jint computeStartIndex,
    jint opticalFlowFamily,
    jint opticalFlowStartIndex,
    jboolean useNativeOpticalFlow
)
{
    g_slQueuePlanReady =
        false;


    if(
        graphicsFamily < 0 ||
        graphicsStartIndex < 0 ||
        computeFamily < 0 ||
        computeStartIndex < 0
    )
    {
        fprintf(
            stderr,
            "[FG-2.11 QUEUE BRIDGE]"
            " INVALID"
            " G=%d:%d"
            " C=%d:%d\n",
            graphicsFamily,
            graphicsStartIndex,
            computeFamily,
            computeStartIndex
        );

        fflush(stderr);
        return;
    }


    const bool nativeOF =
        useNativeOpticalFlow == JNI_TRUE;


    if(
        nativeOF &&
        (
            opticalFlowFamily < 0 ||
            opticalFlowStartIndex < 0
        )
    )
    {
        fprintf(
            stderr,
            "[FG-2.11 QUEUE BRIDGE]"
            " INVALID_NATIVE_OF"
            " OF=%d:%d\n",
            opticalFlowFamily,
            opticalFlowStartIndex
        );

        fflush(stderr);
        return;
    }


    g_slGraphicsQueueFamily =
        static_cast<uint32_t>(
            graphicsFamily
        );

    g_slGraphicsQueueIndex =
        static_cast<uint32_t>(
            graphicsStartIndex
        );


    g_slComputeQueueFamily =
        static_cast<uint32_t>(
            computeFamily
        );

    g_slComputeQueueIndex =
        static_cast<uint32_t>(
            computeStartIndex
        );


    g_slUseNativeOpticalFlow =
        nativeOF;


    if(nativeOF)
    {
        g_slOpticalFlowQueueFamily =
            static_cast<uint32_t>(
                opticalFlowFamily
            );

        g_slOpticalFlowQueueIndex =
            static_cast<uint32_t>(
                opticalFlowStartIndex
            );
    }
    else
    {
        g_slOpticalFlowQueueFamily =
            UINT32_MAX;

        g_slOpticalFlowQueueIndex =
            UINT32_MAX;
    }


    g_slQueuePlanReady =
        true;


    fprintf(
        stderr,
        "[FG-2.11 QUEUE BRIDGE]"
        " ready=1"
        " G=%u:%u"
        " C=%u:%u"
        " OF=%u:%u"
        " nativeOF=%d"
        " requiredG=%u"
        " requiredC=%u"
        " requiredOF=%u\n",

        g_slGraphicsQueueFamily,
        g_slGraphicsQueueIndex,

        g_slComputeQueueFamily,
        g_slComputeQueueIndex,

        g_slOpticalFlowQueueFamily,
        g_slOpticalFlowQueueIndex,

        g_slUseNativeOpticalFlow
            ? 1
            : 0,

        g_fgGraphicsQueuesRequired,
        g_fgComputeQueuesRequired,
        g_fgOpticalFlowQueuesRequired
    );

    fflush(stderr);
}
extern "C"
JNIEXPORT void JNICALL Java_com_example_vulkan_NativeVulkan_clearWorldGeometry(JNIEnv* env, jclass clazz)
{
    {
        std::lock_guard<std::mutex>
            lock(
                g_chunkMutex
            );

        g_chunkMeshes.clear();
        g_chunkRawPayloads.clear();
        g_cutoutOmmInputs.clear();

        g_dirtySectionKeys.clear();

        g_lastLocalPlayerASUpdateFrame = UINT64_MAX;
        g_localPlayerASUpdatePending = false;
        g_lastDynamicRTASUpdateFrame = UINT64_MAX;
        g_dynamicRTASUpdatePending = false;

        g_forceSectionASReset =
            true;

        markWorldGeometryDirty();
    }


    {
        std::lock_guard<std::mutex>
            lightLock(
                g_lightMutex
            );

        g_lightSections.clear();

        g_totalLocalLights =
            0;
    }


    g_localLightsDirty =
        true;


    clearVLISections();


    RTLog(
        "[C++] Global World Geometry + RT Local Lights + VLI Cleared."
    );
}

// === 新增：供 Java 层切换光追渲染状态的接口 ===
extern "C"
JNIEXPORT void JNICALL Java_com_example_vulkan_NativeVulkan_setInWorld(
    JNIEnv* env,
    jclass clazz,
    jboolean inWorld)
{
    g_isInWorld = inWorld;

    if(!inWorld)
    {
        g_hasPrevCameraMatrices =
            false;

        g_restirCameraHistoryValid =
            false;

        g_restirLastCameraFrame =
            -1;

        g_restirDINeedsClear =
            true;
    }
}
static bool ApplyReflexMode(
    int mode)
{
    std::lock_guard<std::mutex>
        lock(g_reflexMutex);


    if(!g_reflexSupported ||
       !g_slReflexSetOptions)
    {
        return false;
    }


    sl::ReflexMode reflexMode;


    switch(mode)
    {
        case 0:
        {
            reflexMode =
                sl::ReflexMode::eOff;

            break;
        }

        case 1:
        {
            reflexMode =
                sl::ReflexMode::eLowLatency;

            break;
        }

        case 2:
        {
            reflexMode =
                sl::ReflexMode::eLowLatencyWithBoost;

            break;
        }

        default:
        {
            return false;
        }
    }


    sl::ReflexOptions options{};

    options.mode =
        reflexMode;

    options.frameLimitUs =
        0;


    sl::Result result =
        g_slReflexSetOptions(
            options
        );


    if(result != sl::Result::eOk)
    {
        fprintf(
            stderr,
            "[REFLEX] SetOptions FAILED mode=%d result=%d\n",
            mode,
            static_cast<int>(result)
        );

        return false;
    }


    g_reflexMode =
        mode;


    fprintf(
        stderr,
        "[REFLEX] mode applied=%d\n",
        mode
    );


    return true;
}
static bool SetCurrentPCLMarker(
    sl::PCLMarker marker)
{
    if(!g_pclSupported ||
       !g_slPCLSetMarker ||
       !g_currentSLFrameValid ||
       g_currentSLFrameToken == nullptr)
    {
        return false;
    }


    sl::Result result =
        g_slPCLSetMarker(
            marker,
            *g_currentSLFrameToken
        );


    if(result != sl::Result::eOk)
    {
        fprintf(
            stderr,
            "[PCL MARKER] FAILED"
            " marker=%u"
            " frame=%u"
            " result=%d\n",
            static_cast<unsigned int>(
                marker
            ),
            g_currentSLFrameIndex,
            static_cast<int>(
                result
            )
        );


        return false;
    }


    return true;
}


static bool MarkRenderSubmitStart()
{
    if(!g_currentSLFrameValid ||
       g_currentSLFrameToken == nullptr)
    {
        return false;
    }


    if(g_lastRenderSubmitStartFrame ==
       g_currentSLFrameIndex)
    {
        return true;
    }


    if(!SetCurrentPCLMarker(
        sl::PCLMarker::eRenderSubmitStart
    ))
    {
        return false;
    }


    g_lastRenderSubmitStartFrame =
        g_currentSLFrameIndex;


    static bool printed =
        false;


    if(!printed)
    {
        fprintf(
            stderr,
            "[PCL] RenderSubmitStart ACTIVE frame=%u\n",
            g_currentSLFrameIndex
        );

        printed =
            true;
    }


    return true;
}


static bool MarkRenderSubmitEnd()
{
    if(!g_currentSLFrameValid ||
       g_currentSLFrameToken == nullptr)
    {
        return false;
    }


    // 没有同帧 Start，就不发送孤立的 End。
    if(g_lastRenderSubmitStartFrame !=
       g_currentSLFrameIndex)
    {
        return false;
    }


    if(g_lastRenderSubmitEndFrame ==
       g_currentSLFrameIndex)
    {
        return true;
    }


    if(!SetCurrentPCLMarker(
        sl::PCLMarker::eRenderSubmitEnd
    ))
    {
        return false;
    }


    g_lastRenderSubmitEndFrame =
        g_currentSLFrameIndex;


    static bool printed =
        false;


    if(!printed)
    {
        fprintf(
            stderr,
            "[PCL] RenderSubmitEnd ACTIVE frame=%u\n",
            g_currentSLFrameIndex
        );

        printed =
            true;
    }


    return true;
}
static bool MarkPresentStart()
{
    if(!g_currentSLFrameValid ||
       g_currentSLFrameToken == nullptr)
    {
        return false;
    }


    // 正常帧必须已经完成同一帧的 RenderSubmit。
    // 防止 renderFrame(false) 等额外渲染拿旧 token 发 Present。
    if(g_lastRenderSubmitEndFrame !=
       g_currentSLFrameIndex)
    {
        return false;
    }


    if(g_lastPresentStartFrame ==
       g_currentSLFrameIndex)
    {
        return true;
    }


    if(!SetCurrentPCLMarker(
        sl::PCLMarker::ePresentStart
    ))
    {
        return false;
    }


    g_lastPresentStartFrame =
        g_currentSLFrameIndex;


    static bool printed =
        false;


    if(!printed)
    {
        fprintf(
            stderr,
            "[PCL] PresentStart ACTIVE frame=%u\n",
            g_currentSLFrameIndex
        );

        printed =
            true;
    }


    return true;
}


static bool MarkPresentEnd()
{
    if(!g_currentSLFrameValid ||
       g_currentSLFrameToken == nullptr)
    {
        return false;
    }


    // 没有同帧 PresentStart，不发送孤立 End。
    if(g_lastPresentStartFrame !=
       g_currentSLFrameIndex)
    {
        return false;
    }


    if(g_lastPresentEndFrame ==
       g_currentSLFrameIndex)
    {
        return true;
    }


    if(!SetCurrentPCLMarker(
        sl::PCLMarker::ePresentEnd
    ))
    {
        return false;
    }


    g_lastPresentEndFrame =
        g_currentSLFrameIndex;


    static bool printed =
        false;


    if(!printed)
    {
        fprintf(
            stderr,
            "[PCL] PresentEnd ACTIVE frame=%u\n",
            g_currentSLFrameIndex
        );

        printed =
            true;
    }


    return true;
}


static bool BeginStreamlineFrame()
{
    if(!g_slInitialized ||
       !g_reflexSupported ||
       !g_slGetNewFrameToken ||
       !g_slReflexSleep)
    {
        g_currentSLFrameToken =
            nullptr;

        g_currentSLFrameValid =
            false;

        return false;
    }


    const uint32_t frameIndex =
        g_nextSLFrameIndex++;


    sl::FrameToken* frameToken =
        nullptr;


    sl::Result tokenResult =
        g_slGetNewFrameToken(
            frameToken,
            &frameIndex
        );


    if(tokenResult != sl::Result::eOk ||
       frameToken == nullptr)
    {
        g_currentSLFrameToken =
            nullptr;

        g_currentSLFrameValid =
            false;


        fprintf(
            stderr,
            "[REFLEX FRAME] GetFrameToken FAILED"
            " frame=%u result=%d\n",
            frameIndex,
            static_cast<int>(
                tokenResult
            )
        );


        return false;
    }


    g_currentSLFrameToken =
        frameToken;

    g_currentSLFrameIndex =
        frameIndex;

    g_currentSLFrameValid =
        true;


    sl::Result sleepResult =
        g_slReflexSleep(
            *g_currentSLFrameToken
        );


    if(sleepResult != sl::Result::eOk)
    {
        fprintf(
            stderr,
            "[REFLEX SLEEP] FAILED"
            " frame=%u result=%d\n",
            g_currentSLFrameIndex,
            static_cast<int>(
                sleepResult
            )
        );
    }
    else
    {
        static bool printedSleepSuccess =
            false;


        if(!printedSleepSuccess)
        {
            fprintf(
                stderr,
                "[REFLEX SLEEP] ACTIVE"
                " frame=%u result=0\n",
                g_currentSLFrameIndex
            );


            printedSleepSuccess =
                true;
        }
    }


    return true;
}
static void initStreamlineInternal()
{
    if(g_device == VK_NULL_HANDLE)
    {
        fprintf(
            stderr,
            "[SL] No Vulkan device yet, skip\n"
        );

        fflush(stderr);
        return;
    }


    if(g_slDeviceConfigured)
    {
        fprintf(
            stderr,
            "[FG-3 EARLY VK]"
            " alreadyConfigured=1\n"
        );

        fflush(stderr);
        return;
    }


    fprintf(
        stderr,
        "[CPP] initStreamlineInternal ENTER\n"
    );
    fprintf(stderr,"[POINT 0] g_device=%p\n",(void*)g_device);
    VkDevice savedDevice = g_device;
    VkInstance savedInstance = g_instance;
    VkPhysicalDevice savedPhysical = g_physicalDevice;
    VkQueue savedQueue = g_queue;
    uint32_t savedQueueFamily = g_queueFamily;

    fprintf(stderr,
    "[SAVE BEFORE DLL] addr=%p value=%p\n",
    (void*)&g_device,
    (void*)g_device);

    fflush(stderr);

    if(!g_slInitialized || !g_slModule)
    {
        fprintf(
            stderr,
            "[SL] ERROR: Streamline was not initialized early before VkDevice creation\n"
        );

        fflush(stderr);
        return;
    }


    fprintf(
        stderr,
        "[SL] Early initialization confirmed, now configuring Vulkan device\n"
    );

    fflush(stderr);


    g_slInit=reinterpret_cast<PFN_slInit>(
        GetProcAddress(g_slModule,"slInit")
    );

    g_slShutdown=reinterpret_cast<PFN_slShutdown>(
        GetProcAddress(g_slModule,"slShutdown")
    );

    g_slSetVulkanInfo=reinterpret_cast<PFN_slSetVulkanInfo>(
        GetProcAddress(g_slModule,"slSetVulkanInfo")
    );

    g_slIsFeatureSupported=reinterpret_cast<PFN_slIsFeatureSupported>(
        GetProcAddress(g_slModule,"slIsFeatureSupported")
    );

    g_slSetFeatureLoaded=reinterpret_cast<PFN_slSetFeatureLoaded>(
        GetProcAddress(g_slModule,"slSetFeatureLoaded")
    );
    g_slGetFeatureFunction =
    reinterpret_cast<PFN_slGetFeatureFunction>(
        GetProcAddress(
            g_slModule,
            "slGetFeatureFunction"
        )
    );


    fprintf(stderr,
    "[SL FUNC CHECK] slGetFeatureFunction=%p\n",
    (void*)g_slGetFeatureFunction);

    fflush(stderr);

    g_slSetConstants =
reinterpret_cast<PFN_slSetConstants>(
    GetProcAddress(
        g_slModule,
        "slSetConstants"
    )
);
    g_slEvaluateFeature =
reinterpret_cast<PFN_slEvaluateFeature>(
    GetProcAddress(
        g_slModule,
        "slEvaluateFeature")
        );
    g_slGetNewFrameToken =
    reinterpret_cast<PFN_slGetNewFrameToken>(
        GetProcAddress(
            g_slModule,
            "slGetNewFrameToken"
        )
    );
    g_slSetTagForFrame =
    reinterpret_cast<PFN_slSetTagForFrame>(
        GetProcAddress(
            g_slModule,
            "slSetTagForFrame"
        )
    );



#define CHECK_SL_FUNC(x)\
if(!(x))\
{\
    std::cerr<<"SL MISSING FUNCTION: "<<#x<<std::endl;\
}


    CHECK_SL_FUNC(g_slInit);
    CHECK_SL_FUNC(g_slShutdown);
    CHECK_SL_FUNC(g_slSetVulkanInfo);
    CHECK_SL_FUNC(g_slSetFeatureLoaded);
    CHECK_SL_FUNC(g_slSetConstants);
    CHECK_SL_FUNC(g_slEvaluateFeature);
    CHECK_SL_FUNC(g_slGetNewFrameToken);
    CHECK_SL_FUNC(g_slSetTagForFrame);
    fprintf(
    stderr,
    "[FG-4.2 TAG API]"
    " slSetTagForFrame=%p\n",
    reinterpret_cast<void*>(
        g_slSetTagForFrame
    )
);

    fflush(stderr);

    std::cerr
<< "[SL FUNC]"
<< "\n slInit="
<< g_slInit
<< "\n slShutdown="
<< g_slShutdown
<< "\n slSetVulkanInfo="
<< g_slSetVulkanInfo
<< "\n slIsFeatureSupported="
<< g_slIsFeatureSupported
    << "\n slSetFeatureLoaded="
    << g_slSetFeatureLoaded
    << "\n slSetConstants="
    << g_slSetConstants
    << "\n slDLSSSetOptions="
    << g_slDLSSSetOptions
    << "\n slEvaluateFeature="
<< g_slGetNewFrameToken
<< std::endl;

    fprintf(stderr,
"[AFTER GetProcAddress] g_device=%p\n",
(void*)g_device);

    fflush(stderr);


    if(!g_slInit)
    {


        if(!g_slSetConstants)
        {
            std::cerr
            << "[SL] slSetConstants missing"
            << std::endl;
        }
        std::cerr
        << "[Native C++] Failed to extract slInit."
        << std::endl;
        return;
    }
    if(!g_slEvaluateFeature)
    {
        fprintf(stderr,
        "[SL] slEvaluateFeature missing\n");
        fflush(stderr);
        return;
    }

    sl::Result r =
        sl::Result::eOk;


    std::cout
        << "[SL] slInit already completed during early initialization"
        << std::endl;

// =====================================================
// FG-2.12
// Provide the exact Streamline-reserved Vulkan queues.
//
// Queue plan was calculated before vkCreateDevice()
// and bridged to C++ by FG-2.11.
// =====================================================

if(!g_slQueuePlanReady)
{
    fprintf(
        stderr,
        "[FG-2.12 SL VKINFO]"
        " queuePlanReady=0"
        " ABORT\n"
    );

    fflush(stderr);
    return;
}


sl::VulkanInfo vkInfo{};

vkInfo.device =
    savedDevice;

vkInfo.instance =
    savedInstance;

vkInfo.physicalDevice =
    savedPhysical;


// -----------------------------------------------------
// Streamline Graphics queues
//
// family 0:
//   q0 = Minecraft
//   q1 = first SL queue
// -----------------------------------------------------

vkInfo.graphicsQueueFamily =
    g_slGraphicsQueueFamily;

vkInfo.graphicsQueueIndex =
    g_slGraphicsQueueIndex;


// -----------------------------------------------------
// Streamline Compute queues
//
// family 2:
//   q0    = Minecraft
//   q1-q2 = Streamline
//
// VulkanInfo receives the STARTING index only.
// -----------------------------------------------------

vkInfo.computeQueueFamily =
    g_slComputeQueueFamily;

vkInfo.computeQueueIndex =
    g_slComputeQueueIndex;


// -----------------------------------------------------
// Native NVIDIA Optical Flow
// -----------------------------------------------------

vkInfo.useNativeOpticalFlowMode =
    g_slUseNativeOpticalFlow;


if(g_slUseNativeOpticalFlow)
{
    vkInfo.opticalFlowQueueFamily =
        g_slOpticalFlowQueueFamily;

    vkInfo.opticalFlowQueueIndex =
        g_slOpticalFlowQueueIndex;
}


// All VkDeviceQueueCreateInfo::flags used by the
// current host queue creation path are zero.
vkInfo.graphicsQueueCreateFlags =
    0;

vkInfo.computeQueueCreateFlags =
    0;

vkInfo.opticalFlowQueueCreateFlags =
    0;


fprintf(
    stderr,
    "[FG-2.12 SL VKINFO]"
    " ready=1"
    " G=%u:%u"
    " C=%u:%u"
    " OF=%u:%u"
    " nativeOF=%d"
    " flags=0/0/0\n",

    vkInfo.graphicsQueueFamily,
    vkInfo.graphicsQueueIndex,

    vkInfo.computeQueueFamily,
    vkInfo.computeQueueIndex,

    vkInfo.opticalFlowQueueFamily,
    vkInfo.opticalFlowQueueIndex,

    vkInfo.useNativeOpticalFlowMode
        ? 1
        : 0
);

fflush(stderr);


sl::Result vkResult =
    g_slSetVulkanInfo(
        vkInfo
    );


fprintf(
    stderr,
    "[FG-2.12 SL SET VULKAN INFO]"
    " result=%d\n",
    static_cast<int>(
        vkResult
    )
);

fflush(stderr);



    std::cout << "[SL] slSetVulkanInfo result=" << (int)vkResult << std::endl;
    if (vkResult != sl::Result::eOk)
    {
        return;
    }
    sl::AdapterInfo ai{};

    ai.vkPhysicalDevice =
        g_physicalDevice;


    sl::Result dlssSupport =
        g_slIsFeatureSupported(
            sl::kFeatureDLSS,
            ai
        );


    sl::Result dlssRRSupport =
        g_slIsFeatureSupported(
            sl::kFeatureDLSS_RR,
            ai
        );


    sl::Result reflexSupport =
        g_slIsFeatureSupported(
            sl::kFeatureReflex,
            ai
        );
    sl::Result dlssGSupport =
    g_slIsFeatureSupported(
        sl::kFeatureDLSS_G,
        ai
    );


    sl::Result pclSupport =
        g_slIsFeatureSupported(
            sl::kFeaturePCL,
            ai
        );


    g_dlssRRSupported =
        dlssRRSupport ==
        sl::Result::eOk;
    g_dlssGSupported =
    dlssGSupport ==
    sl::Result::eOk;

    fprintf(
    stderr,
    "[FG-1 SUPPORT]"
    " result=%d"
    " supported=%d\n",

    static_cast<int>(
        dlssGSupport
    ),

    g_dlssGSupported
        ? 1
        : 0
);

    fflush(stderr);

    g_reflexSupported =
        reflexSupport == sl::Result::eOk;

    g_pclSupported =
        pclSupport == sl::Result::eOk;

    if(g_reflexSupported)
    {
        void* reflexSetOptionsFn =
            nullptr;

        sl::Result setOptionsFnResult =
            g_slGetFeatureFunction(
                sl::kFeatureReflex,
                "slReflexSetOptions",
                reflexSetOptionsFn
            );


        g_slReflexSetOptions =
            reinterpret_cast<PFN_slReflexSetOptions>(
                reflexSetOptionsFn
            );


        void* reflexGetStateFn =
            nullptr;

        sl::Result getStateFnResult =
            g_slGetFeatureFunction(
                sl::kFeatureReflex,
                "slReflexGetState",
                reflexGetStateFn
            );


        g_slReflexGetState =
            reinterpret_cast<PFN_slReflexGetState>(
                reflexGetStateFn
            );
        void* reflexSleepFn =
    nullptr;

        sl::Result sleepFnResult =
            g_slGetFeatureFunction(
                sl::kFeatureReflex,
                "slReflexSleep",
                reflexSleepFn
            );


        g_slReflexSleep =
            reinterpret_cast<PFN_slReflexSleep>(
                reflexSleepFn
            );


        fprintf(
            stderr,
            "[REFLEX FUNC]"
            " SetOptions result=%d ptr=%p"
            " GetState result=%d ptr=%p"
            " Sleep result=%d ptr=%p\n",

            static_cast<int>(
                setOptionsFnResult
            ),

            reinterpret_cast<void*>(
                g_slReflexSetOptions
            ),

            static_cast<int>(
                getStateFnResult
            ),

            reinterpret_cast<void*>(
                g_slReflexGetState
            ),

            static_cast<int>(
                sleepFnResult
            ),

            reinterpret_cast<void*>(
                g_slReflexSleep
            )
        );;
    }
    if(g_reflexSupported &&
   g_slReflexGetState)
    {
        sl::ReflexState state{};


        sl::Result stateResult =
            g_slReflexGetState(
                state
            );


        if(stateResult == sl::Result::eOk)
        {
            g_reflexLowLatencyAvailable =
                state.lowLatencyAvailable;


            fprintf(
                stderr,
                "[REFLEX STATE] result=%d lowLatencyAvailable=%d latencyReportAvailable=%d\n",
                static_cast<int>(stateResult),
                state.lowLatencyAvailable ? 1 : 0,
                state.latencyReportAvailable ? 1 : 0
            );
        }
        else
        {
            g_reflexLowLatencyAvailable =
                false;


            fprintf(
                stderr,
                "[REFLEX STATE] FAILED result=%d\n",
                static_cast<int>(stateResult)
            );
        }
    }


    if(g_reflexLowLatencyAvailable)
    {
        ApplyReflexMode(
            1
        );
    }
    else if(g_reflexSupported)
    {
        ApplyReflexMode(
            0
        );
    }


    if(g_pclSupported &&
       g_slGetFeatureFunction)
    {
        void* pclSetMarkerFn =
            nullptr;


        sl::Result pclSetMarkerFnResult =
            g_slGetFeatureFunction(
                sl::kFeaturePCL,
                "slPCLSetMarker",
                pclSetMarkerFn
            );


        g_slPCLSetMarker =
            reinterpret_cast<PFN_slPCLSetMarker>(
                pclSetMarkerFn
            );


        fprintf(
            stderr,
            "[PCL FUNC] SetMarker result=%d ptr=%p\n",
            static_cast<int>(
                pclSetMarkerFnResult
            ),
            reinterpret_cast<void*>(
                g_slPCLSetMarker
            )
        );
    }
    fprintf(
        stderr,
        "[SL SUPPORT]"
        " DLSS=%d"
        " DLSS_RR=%d"
        " Reflex=%d"
        " PCL=%d\n",

        static_cast<int>(
            dlssSupport
        ),

        static_cast<int>(
            dlssRRSupport
        ),

        static_cast<int>(
            reflexSupport
        ),

        static_cast<int>(
            pclSupport
        )
    );

    sl::Result dlssLoad =
        sl::Result::eErrorFeatureNotSupported;


    if(dlssSupport == sl::Result::eOk)
    {
        dlssLoad =
            g_slSetFeatureLoaded(
                sl::kFeatureDLSS,
                true
            );

        fprintf(
            stderr,
            "[SL] DLSS loaded result=%d\n",
            static_cast<int>(dlssLoad)
        );
    }
    if (dlssLoad == sl::Result::eOk)
    {
        void* dlssSetOptionsFn = nullptr;

        sl::Result featureFnResult =
            g_slGetFeatureFunction(
                sl::kFeatureDLSS,
                "slDLSSSetOptions",
                dlssSetOptionsFn
            );

        g_slDLSSSetOptions =
            reinterpret_cast<PFN_slDLSSSetOptions>(
                dlssSetOptionsFn
            );
        void* dlssOptimalFn = nullptr;

        sl::Result optimalFnResult =
            g_slGetFeatureFunction(
                sl::kFeatureDLSS,
                "slDLSSGetOptimalSettings",
                dlssOptimalFn
            );

        g_slDLSSGetOptimalSettings =
            reinterpret_cast<PFN_slDLSSGetOptimalSettings>(
                dlssOptimalFn
            );

        std::cerr
            << "[SL] GetFeatureFunction slDLSSGetOptimalSettings result="
            << std::dec
            << (int)optimalFnResult
            << " ptr="
            << g_slDLSSGetOptimalSettings
            << std::endl;

        std::cerr
            << "[SL] GetFeatureFunction slDLSSSetOptions result="
            << (int)featureFnResult
            << " ptr="
            << g_slDLSSSetOptions
            << std::endl;


        if(featureFnResult == sl::Result::eOk &&
           g_slDLSSSetOptions != nullptr)
        {
            g_dlssReady = true;

            std::cout
                << "[DLSS] READY = TRUE"
                << std::endl;
        }
        else
        {
            g_dlssReady = false;

            std::cerr
                << "[DLSS] READY = FALSE - slDLSSSetOptions unavailable"
                << std::endl;
        }
    }
    else
    {
        g_dlssReady = false;

        std::cout
            << "[DLSS] READY = FALSE"
            << std::endl;
    }
    // =====================================================
// DLSS Ray Reconstruction feature load
//
// RR-1:
// 只验证 plugin / support / exported feature functions。
// 此处不调用 SetOptions，不 Evaluate。
// =====================================================

sl::Result dlssRRLoad =
    sl::Result::eErrorFeatureNotSupported;


if(dlssRRSupport == sl::Result::eOk)
{
    dlssRRLoad =
        g_slSetFeatureLoaded(
            sl::kFeatureDLSS_RR,
            true
        );


    fprintf(
        stderr,
        "[SL RR] DLSS_RR loaded result=%d\n",
        static_cast<int>(
            dlssRRLoad
        )
    );
}


if(dlssRRLoad == sl::Result::eOk)
{
    void* dlssdSetOptionsFn =
        nullptr;


    sl::Result rrSetOptionsFnResult =
        g_slGetFeatureFunction(
            sl::kFeatureDLSS_RR,
            "slDLSSDSetOptions",
            dlssdSetOptionsFn
        );


    g_slDLSSDSetOptions =
        reinterpret_cast<
            PFN_slDLSSDSetOptions
        >(
            dlssdSetOptionsFn
        );


    void* dlssdOptimalFn =
        nullptr;


    sl::Result rrOptimalFnResult =
        g_slGetFeatureFunction(
            sl::kFeatureDLSS_RR,
            "slDLSSDGetOptimalSettings",
            dlssdOptimalFn
        );


    g_slDLSSDGetOptimalSettings =
        reinterpret_cast<
            PFN_slDLSSDGetOptimalSettings
        >(
            dlssdOptimalFn
        );


    fprintf(
        stderr,
        "[SL RR FUNC]"
        " slDLSSDSetOptions"
        " result=%d ptr=%p\n",

        static_cast<int>(
            rrSetOptionsFnResult
        ),

        reinterpret_cast<void*>(
            g_slDLSSDSetOptions
        )
    );


    fprintf(
        stderr,
        "[SL RR FUNC]"
        " slDLSSDGetOptimalSettings"
        " result=%d ptr=%p\n",

        static_cast<int>(
            rrOptimalFnResult
        ),

        reinterpret_cast<void*>(
            g_slDLSSDGetOptimalSettings
        )
    );


    g_dlssRRReady =
        rrSetOptionsFnResult ==
            sl::Result::eOk &&
        rrOptimalFnResult ==
            sl::Result::eOk &&
        g_slDLSSDSetOptions !=
            nullptr &&
        g_slDLSSDGetOptimalSettings !=
            nullptr;


    fprintf(
        stderr,
        "[SL RR] READY=%d\n",
        g_dlssRRReady
            ? 1
            : 0
    );
}
else
{
    g_dlssRRReady =
        false;


    fprintf(
        stderr,
        "[SL RR] READY=0"
        " feature load unavailable\n"
    );
}





    std::cout
    << "[Native C++] slSetVulkanInfo result: "
    << (int)vkResult
    << std::endl;

    // if(g_slReflexSetOptions)
    // {
    //     sl::ReflexOptions options{};
    //
    //     options.mode = sl::eLowLatency;
    //
    //     sl::Result reflexResult =
    //         g_slReflexSetOptions(options);
    //
    //     std::cout
    //     << "[SL] ReflexSetOptions result="
    //     << (int)reflexResult
    //     << std::endl;
    // }


    printf(
    "[SL RESULT] init=%d vulkan=%d\n",
    (int)r,
    (int)vkResult
    );
    g_slDeviceConfigured =
    vkResult ==
    sl::Result::eOk;


    const bool proxyReady =
        g_slDeviceConfigured &&
        ResolveSLVulkanProxies();


    fprintf(
        stderr,
        "[FG-3 EARLY VK]"
        " configured=%d"
        " proxies=%d\n",

        g_slDeviceConfigured
            ? 1
            : 0,

        proxyReady
            ? 1
            : 0
    );

    fflush(stderr);

    }
// =====================================================
// FG-3
// Vulkan Manual Hooking JNI bridge
// =====================================================

extern "C"
JNIEXPORT jboolean JNICALL Java_com_example_vulkan_NativeVulkan_prepareStreamlineVulkan(
    JNIEnv*,
    jclass,
    jlong instanceHandle,
    jlong physicalDeviceHandle,
    jlong deviceHandle
)
{
    if(
        instanceHandle == 0 ||
        physicalDeviceHandle == 0 ||
        deviceHandle == 0
    )
    {
        return JNI_FALSE;
    }


    g_instance =
        reinterpret_cast<VkInstance>(
            instanceHandle
        );

    g_physicalDevice =
        reinterpret_cast<VkPhysicalDevice>(
            physicalDeviceHandle
        );

    g_device =
        reinterpret_cast<VkDevice>(
            deviceHandle
        );


    initStreamlineInternal();


    const bool ready =
        g_slDeviceConfigured &&
        ResolveSLVulkanProxies();


    fprintf(
        stderr,
        "[FG-3 PREPARE]"
        " configured=%d"
        " proxyReady=%d\n",

        g_slDeviceConfigured ? 1 : 0,
        ready ? 1 : 0
    );

    fflush(stderr);


    return ready
        ? JNI_TRUE
        : JNI_FALSE;
}


extern "C"
JNIEXPORT jint JNICALL Java_com_example_vulkan_NativeVulkan_slCreateWin32Surface(
    JNIEnv*,
    jclass,
    jlong instanceHandle,
    jlong hwndHandle,
    jlong surfaceOutAddress
)
{
    VkInstance instance =
        reinterpret_cast<VkInstance>(
            instanceHandle
        );


    VkSurfaceKHR* surfaceOut =
        reinterpret_cast<VkSurfaceKHR*>(
            static_cast<uintptr_t>(
                surfaceOutAddress
            )
        );


    VkWin32SurfaceCreateInfoKHR ci{
        VK_STRUCTURE_TYPE_WIN32_SURFACE_CREATE_INFO_KHR
    };


    ci.hinstance =
        GetModuleHandleW(
            nullptr
        );

    ci.hwnd =
        reinterpret_cast<HWND>(
            static_cast<uintptr_t>(
                hwndHandle
            )
        );


    const bool useProxy =
        g_slDeviceConfigured &&
        ResolveSLVulkanProxies() &&
        g_slVkCreateWin32SurfaceKHRProxy;


    VkResult result =
        useProxy
            ? g_slVkCreateWin32SurfaceKHRProxy(
                instance,
                &ci,
                nullptr,
                surfaceOut
            )
            : ::vkCreateWin32SurfaceKHR(
                instance,
                &ci,
                nullptr,
                surfaceOut
            );


    fprintf(
        stderr,
        "[FG-3 SURFACE CREATE]"
        " proxy=%d"
        " result=%d\n",

        useProxy ? 1 : 0,
        static_cast<int>(result)
    );

    fflush(stderr);


    return static_cast<jint>(
        result
    );
}


extern "C"
JNIEXPORT void JNICALL Java_com_example_vulkan_NativeVulkan_slDestroySurface(
    JNIEnv*,
    jclass,
    jlong instanceHandle,
    jlong surfaceHandle
)
{
    VkInstance instance =
        reinterpret_cast<VkInstance>(
            instanceHandle
        );

    VkSurfaceKHR surface =
        reinterpret_cast<VkSurfaceKHR>(
            static_cast<uintptr_t>(
                surfaceHandle
            )
        );


    const bool useProxy =
        g_slDeviceConfigured &&
        ResolveSLVulkanProxies() &&
        g_slVkDestroySurfaceKHRProxy;


    if(useProxy)
    {
        g_slVkDestroySurfaceKHRProxy(
            instance,
            surface,
            nullptr
        );
    }
    else
    {
        ::vkDestroySurfaceKHR(
            instance,
            surface,
            nullptr
        );
    }


    fprintf(
        stderr,
        "[FG-3 SURFACE DESTROY]"
        " proxy=%d\n",

        useProxy ? 1 : 0
    );

    fflush(stderr);
}


extern "C"
JNIEXPORT jint JNICALL Java_com_example_vulkan_NativeVulkan_slCreateSwapchain(
    JNIEnv*,
    jclass,
    jlong deviceHandle,
    jlong createInfoAddress,
    jlong swapchainOutAddress
)
{
    VkDevice device =
        reinterpret_cast<VkDevice>(
            deviceHandle
        );


    const auto* createInfo =
        reinterpret_cast<
            const VkSwapchainCreateInfoKHR*
        >(
            static_cast<uintptr_t>(
                createInfoAddress
            )
        );


    VkSwapchainKHR* swapchainOut =
        reinterpret_cast<VkSwapchainKHR*>(
            static_cast<uintptr_t>(
                swapchainOutAddress
            )
        );


    const bool useProxy =
        g_slDeviceConfigured &&
        ResolveSLVulkanProxies() &&
        g_slVkCreateSwapchainKHRProxy;


    VkResult result =
        useProxy
            ? g_slVkCreateSwapchainKHRProxy(
                device,
                createInfo,
                nullptr,
                swapchainOut
            )
            : ::vkCreateSwapchainKHR(
                device,
                createInfo,
                nullptr,
                swapchainOut
            );


    fprintf(
        stderr,
        "[FG-3 SWAPCHAIN CREATE]"
        " proxy=%d"
        " result=%d\n",

        useProxy ? 1 : 0,
        static_cast<int>(result)
    );

    fflush(stderr);


    return static_cast<jint>(
        result
    );
}


extern "C"
JNIEXPORT void JNICALL Java_com_example_vulkan_NativeVulkan_slDestroySwapchain(
    JNIEnv*,
    jclass,
    jlong deviceHandle,
    jlong swapchainHandle
)
{
    VkDevice device =
        reinterpret_cast<VkDevice>(
            deviceHandle
        );


    VkSwapchainKHR swapchain =
        reinterpret_cast<VkSwapchainKHR>(
            static_cast<uintptr_t>(
                swapchainHandle
            )
        );


    // FG may have asynchronous presentation work.
    // Use the mandatory SL DeviceWaitIdle hook before teardown.
    FGDeviceWaitIdle(
        device
    );


    const bool useProxy =
        g_slDeviceConfigured &&
        ResolveSLVulkanProxies() &&
        g_slVkDestroySwapchainKHRProxy;


    if(useProxy)
    {
        g_slVkDestroySwapchainKHRProxy(
            device,
            swapchain,
            nullptr
        );
    }
    else
    {
        ::vkDestroySwapchainKHR(
            device,
            swapchain,
            nullptr
        );
    }


    fprintf(
        stderr,
        "[FG-3 SWAPCHAIN DESTROY]"
        " proxy=%d\n",

        useProxy ? 1 : 0
    );

    fflush(stderr);
}


extern "C"
JNIEXPORT jint JNICALL Java_com_example_vulkan_NativeVulkan_slGetSwapchainImages(
    JNIEnv*,
    jclass,
    jlong deviceHandle,
    jlong swapchainHandle,
    jlong countAddress,
    jlong imagesAddress
)
{
    VkDevice device =
        reinterpret_cast<VkDevice>(
            deviceHandle
        );


    VkSwapchainKHR swapchain =
        reinterpret_cast<VkSwapchainKHR>(
            static_cast<uintptr_t>(
                swapchainHandle
            )
        );


    uint32_t* count =
        reinterpret_cast<uint32_t*>(
            static_cast<uintptr_t>(
                countAddress
            )
        );


    VkImage* images =
        imagesAddress != 0
            ? reinterpret_cast<VkImage*>(
                static_cast<uintptr_t>(
                    imagesAddress
                )
            )
            : nullptr;


    const bool useProxy =
        g_slDeviceConfigured &&
        ResolveSLVulkanProxies() &&
        g_slVkGetSwapchainImagesKHRProxy;


    VkResult result =
        useProxy
            ? g_slVkGetSwapchainImagesKHRProxy(
                device,
                swapchain,
                count,
                images
            )
            : ::vkGetSwapchainImagesKHR(
                device,
                swapchain,
                count,
                images
            );


    if(images != nullptr)
    {
        fprintf(
            stderr,
            "[FG-3 GET IMAGES]"
            " proxy=%d"
            " result=%d"
            " count=%u\n",

            useProxy ? 1 : 0,
            static_cast<int>(result),
            count ? *count : 0
        );

        fflush(stderr);
    }


    return static_cast<jint>(
        result
    );
}


extern "C"
JNIEXPORT jint JNICALL Java_com_example_vulkan_NativeVulkan_slAcquireNextImage(
    JNIEnv*,
    jclass,
    jlong deviceHandle,
    jlong swapchainHandle,
    jlong timeout,
    jlong semaphoreHandle,
    jlong fenceHandle,
    jlong imageIndexAddress
)
{
    VkDevice device =
        reinterpret_cast<VkDevice>(
            deviceHandle
        );


    VkSwapchainKHR swapchain =
        reinterpret_cast<VkSwapchainKHR>(
            static_cast<uintptr_t>(
                swapchainHandle
            )
        );


    VkSemaphore semaphore =
        reinterpret_cast<VkSemaphore>(
            static_cast<uintptr_t>(
                semaphoreHandle
            )
        );


    VkFence fence =
        reinterpret_cast<VkFence>(
            static_cast<uintptr_t>(
                fenceHandle
            )
        );


    uint32_t* imageIndex =
        reinterpret_cast<uint32_t*>(
            static_cast<uintptr_t>(
                imageIndexAddress
            )
        );


    const bool useProxy =
        g_slDeviceConfigured &&
        ResolveSLVulkanProxies() &&
        g_slVkAcquireNextImageKHRProxy;


    VkResult result =
        useProxy
            ? g_slVkAcquireNextImageKHRProxy(
                device,
                swapchain,
                static_cast<uint64_t>(timeout),
                semaphore,
                fence,
                imageIndex
            )
            : ::vkAcquireNextImageKHR(
                device,
                swapchain,
                static_cast<uint64_t>(timeout),
                semaphore,
                fence,
                imageIndex
            );


    static bool printed =
        false;


    if(!printed)
    {
        fprintf(
            stderr,
            "[FG-3 ACQUIRE]"
            " proxy=%d"
            " result=%d"
            " index=%u\n",

            useProxy ? 1 : 0,
            static_cast<int>(result),
            imageIndex ? *imageIndex : UINT32_MAX
        );

        fflush(stderr);

        printed = true;
    }


    return static_cast<jint>(
        result
    );
}


extern "C"
JNIEXPORT jint JNICALL Java_com_example_vulkan_NativeVulkan_slQueuePresent(
    JNIEnv*,
    jclass,
    jlong queueHandle,
    jlong presentInfoAddress
)
{
    VkQueue queue =
        reinterpret_cast<VkQueue>(
            queueHandle
        );


    const VkPresentInfoKHR* presentInfo =
        reinterpret_cast<
            const VkPresentInfoKHR*
        >(
            static_cast<uintptr_t>(
                presentInfoAddress
            )
        );


    const bool useProxy =
        g_slDeviceConfigured &&
        ResolveSLVulkanProxies() &&
        g_slVkQueuePresentKHRProxy;
    // =====================================================
    // FG-4.3A
    // Enable fixed 2x DLSS Frame Generation.
    // Run on the presenting thread.
    // =====================================================

    static bool fg43Enabled =
        false;
    static bool fg43AppliedFrameSync =
        true;

    if(
        (!fg43Enabled ||
         fg43AppliedFrameSync != g_fgFrameSyncEnabled) &&
        g_isInWorld &&
        g_dlssGReady &&
        g_slDLSSGSetOptions &&
        g_slDLSSGGetState &&
        g_currentSLFrameValid &&
        g_currentSLFrameToken != nullptr &&
        g_fgHudlessFrameIndex == g_currentSLFrameIndex &&
        g_fgUIAlphaFrameIndex == g_currentSLFrameIndex
    )
    {
        sl::DLSSGState preState{};

        sl::Result preStateResult =
            g_slDLSSGGetState(
                g_dlssViewport,
                preState,
                nullptr
            );


        // 当前 RTX 4070 固定 2x：
        // 1 generated frame + 1 rendered frame.
        if(
            preStateResult == sl::Result::eOk &&
            preState.numFramesToGenerateMax >= 1
        )
        {
            sl::DLSSGOptions options{};

            options.mode =
                sl::DLSSGMode::eOn;

            options.numFramesToGenerate =
                1;

            if(g_fgFrameSyncEnabled)
            {
                // Keep the presenting client queue ordered with DLSS-G. The
                // asynchronous mode requires completion-fence ownership that
                // this backend does not currently expose.
                options.queueParallelismMode =
                    sl::DLSSGQueueParallelismMode::eBlockPresentingClientQueue;
            }


            sl::Result setResult =
                g_slDLSSGSetOptions(
                    g_dlssViewport,
                    options
                );


            fprintf(
                stderr,
                "[FG-4.3 ENABLE]"
                " frame=%u"
                " setResult=%d"
                " preStateResult=%d"
                " status=%u"
                " maxGenerated=%u"
                " requestedGenerated=1"
                " frameSync=%d\n",

                g_currentSLFrameIndex,

                static_cast<int>(
                    setResult
                ),

                static_cast<int>(
                    preStateResult
                ),

                static_cast<unsigned int>(
                    preState.status
                ),

                preState.numFramesToGenerateMax,
                g_fgFrameSyncEnabled ? 1 : 0
            );

            fflush(stderr);


            if(setResult == sl::Result::eOk)
            {
                fg43Enabled =
                    true;
                fg43AppliedFrameSync =
                    g_fgFrameSyncEnabled;
            }
        }
    }


    VkResult result =
        useProxy
            ? g_slVkQueuePresentKHRProxy(
                queue,
                presentInfo
            )
            : ::vkQueuePresentKHR(
                queue,
                presentInfo
            );

    // A Section rebuild may replace AS resources on a later frame. Queue an
    // empty fenced submission after present so that replacement can prove all
    // earlier frame work using the old AS has completed without idling the
    // entire graphics queue.
    if(g_worldGeometryDirty.load(std::memory_order_acquire) &&
       g_device != VK_NULL_HANDLE &&
       queue != VK_NULL_HANDLE)
    {
        // Bound the fence list during normal play. If the newest fence has
        // completed, queue ordering guarantees every older fence has too.
        (void)latestFrameCompletionFenceSignaled();

        VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        VkFence frameFence = VK_NULL_HANDLE;
        if(vkCreateFence(g_device, &fenceInfo, nullptr, &frameFence) == VK_SUCCESS)
        {
            VkSubmitInfo completionSubmit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
            const VkResult completionResult =
                vkQueueSubmit(queue, 1u, &completionSubmit, frameFence);
            if(completionResult == VK_SUCCESS)
            {
                std::lock_guard<std::mutex> lock(g_frameCompletionFenceMutex);
                g_frameCompletionFences.push_back(frameFence);
            }
            else
            {
                vkDestroyFence(g_device, frameFence, nullptr);
            }
        }
    }

    static VkResult lastPresentResult = VK_SUCCESS;
    if(result != VK_SUCCESS && result != lastPresentResult)
    {
        fprintf(
            stderr,
            "[FG-4.3 PRESENT]"
            " frame=%u"
            " proxy=%d"
            " result=%d\n",
            g_currentSLFrameIndex,
            useProxy ? 1 : 0,
            static_cast<int>(result)
        );

        fflush(stderr);
    }

    lastPresentResult = result;


    static bool printed =
        false;


    if(!printed)
    {
        fprintf(
            stderr,
            "[FG-3 PRESENT]"
            " proxy=%d"
            " result=%d\n",

            useProxy ? 1 : 0,
            static_cast<int>(result)
        );

        fflush(stderr);

        printed = true;
    }


    return static_cast<jint>(
        result
    );
}

extern "C"
JNIEXPORT jboolean JNICALL Java_com_example_vulkan_NativeVulkan_setReflexMode(
    JNIEnv*,
    jclass,
    jint mode)
{
    return ApplyReflexMode(
        static_cast<int>(mode)
    )
        ? JNI_TRUE
        : JNI_FALSE;
}


extern "C"
JNIEXPORT jboolean JNICALL Java_com_example_vulkan_NativeVulkan_isReflexAvailable(
    JNIEnv*,
    jclass)
{
    return g_reflexLowLatencyAvailable
        ? JNI_TRUE
        : JNI_FALSE;
}


extern "C"
JNIEXPORT jint JNICALL Java_com_example_vulkan_NativeVulkan_getReflexMode(
    JNIEnv*,
    jclass)
{
    return static_cast<jint>(
        g_reflexMode
    );
}

extern "C"
JNIEXPORT jboolean JNICALL Java_com_example_vulkan_NativeVulkan_setFGFrameSync(
    JNIEnv*,
    jclass,
    jboolean enabled)
{
    g_fgFrameSyncEnabled = enabled == JNI_TRUE;

    fprintf(
        stderr,
        "[FG FRAME SYNC] enabled=%d\n",
        g_fgFrameSyncEnabled ? 1 : 0
    );
    fflush(stderr);

    return JNI_TRUE;
}
extern "C"
JNIEXPORT jboolean JNICALL Java_com_example_vulkan_NativeVulkan_setNoDirectLightAmbient(
    JNIEnv*,
    jclass,
    jfloat value)
{
    const float requested =
        static_cast<float>(
            value
        );


    if(
        !std::isfinite(requested) ||
        requested < 0.0f ||
        requested > 1.0f
    )
    {
        fprintf(
            stderr,
            "[RTX AMBIENT] Invalid value=%.4f\n",
            static_cast<double>(
                requested
            )
        );

        return JNI_FALSE;
    }


    g_noDirectLightAmbient.store(
        requested
    );


    fprintf(
        stderr,
        "[RTX AMBIENT] value=%.2f\n",
        static_cast<double>(
            requested
        )
    );

    fflush(stderr);


    return JNI_TRUE;
}


extern "C"
JNIEXPORT jfloat JNICALL Java_com_example_vulkan_NativeVulkan_getNoDirectLightAmbient(
    JNIEnv*,
    jclass)
{
    return static_cast<jfloat>(
        g_noDirectLightAmbient.load()
    );
}

extern "C"
JNIEXPORT jboolean JNICALL Java_com_example_vulkan_NativeVulkan_setPOMDepth(
    JNIEnv*,
    jclass,
    jfloat value)
{
    const float requested =
        static_cast<float>(
            value
        );


    if(
        !std::isfinite(requested) ||
        requested < 0.0f ||
        requested > 0.20f
    )
    {
        fprintf(
            stderr,
            "[RTX POM] Invalid depth=%.4f\n",
            static_cast<double>(
                requested
            )
        );

        return JNI_FALSE;
    }


    const float snapped =
        std::round(
            requested * 100.0f
        ) / 100.0f;


    g_pomDepth.store(
        snapped
    );


    fprintf(
        stderr,
        "[RTX POM] depth=%.2f\n",
        static_cast<double>(
            snapped
        )
    );

    fflush(stderr);


    return JNI_TRUE;
}


extern "C"
JNIEXPORT jfloat JNICALL Java_com_example_vulkan_NativeVulkan_getPOMDepth(
    JNIEnv*,
    jclass)
{
    return static_cast<jfloat>(
        g_pomDepth.load()
    );
}

extern "C"
JNIEXPORT jboolean JNICALL Java_com_example_vulkan_NativeVulkan_setSunVisualSettings(
    JNIEnv*,
    jclass,
    jfloat diskLuminance,
    jfloat haloStrength,
    jfloat angularDiameterDegrees)
{
    const float requestedDiskLuminance = static_cast<float>(diskLuminance);
    const float requestedHaloStrength = static_cast<float>(haloStrength);
    const float requestedAngularDiameterDegrees = static_cast<float>(angularDiameterDegrees);
    if(!std::isfinite(requestedDiskLuminance) || !std::isfinite(requestedHaloStrength) ||
       !std::isfinite(requestedAngularDiameterDegrees) ||
       requestedDiskLuminance < 0.0f || requestedDiskLuminance > 8.0f ||
       requestedHaloStrength < 0.0f || requestedHaloStrength > 1.0f ||
       requestedAngularDiameterDegrees < 0.10f || requestedAngularDiameterDegrees > 2.00f)
    {
        return JNI_FALSE;
    }
    g_sunDiskLuminance.store(requestedDiskLuminance);
    g_sunHaloStrength.store(requestedHaloStrength);
    g_sunAngularDiameterDegrees.store(requestedAngularDiameterDegrees);
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL Java_com_example_vulkan_NativeVulkan_setHighCloudSettings(
    JNIEnv*, jclass, jboolean enabled, jfloat height, jfloat coverage, jfloat brightness)
{
    if(!std::isfinite(height) || height < 256 || height > 8000 ||
       !std::isfinite(coverage) || coverage < 0 || coverage > 1 ||
       !std::isfinite(brightness) || brightness < 0 || brightness > 3)
        return JNI_FALSE;
    g_highCloudEnabled.store(enabled == JNI_TRUE);
    g_highCloudHeight.store(height);
    g_highCloudCoverage.store(coverage);
    g_highCloudBrightness.store(brightness);
    g_volumetricCloudHistoryNeedsClear.store(true);
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL Java_com_example_vulkan_NativeVulkan_setCloudSettings(
    JNIEnv*,
    jclass,
    jboolean enabled,
    jfloat bottom,
    jfloat thickness,
    jfloat coverage,
    jfloat density,
    jfloat detailStrength,
    jfloat windSpeed,
    jfloat evolution,
    jint quality,
    jfloat size,
    jfloat distribution,
    jfloat baseFeather,
    jfloat topBrightness,
    jfloat bottomBrightness,
    jfloat sunlightStrength)
{
    const float requestedBottom = static_cast<float>(bottom);
    const float requestedThickness = static_cast<float>(thickness);
    const float requestedCoverage = static_cast<float>(coverage);
    const float requestedDensity = static_cast<float>(density);
    const float requestedDetailStrength = static_cast<float>(detailStrength);
    const float requestedWindSpeed = static_cast<float>(windSpeed);
    const float requestedEvolution = static_cast<float>(evolution);
    const int requestedQuality = static_cast<int>(quality);
    const float requestedSize = static_cast<float>(size);
    const float requestedDistribution = static_cast<float>(distribution);
    const float requestedBaseFeather = static_cast<float>(baseFeather);
    const float requestedTopBrightness = static_cast<float>(topBrightness);
    const float requestedBottomBrightness = static_cast<float>(bottomBrightness);
    const float requestedSunlightStrength = static_cast<float>(sunlightStrength);
    if(!std::isfinite(requestedBottom) || !std::isfinite(requestedThickness) ||
       !std::isfinite(requestedCoverage) || !std::isfinite(requestedDensity) ||
       !std::isfinite(requestedDetailStrength) || !std::isfinite(requestedWindSpeed) ||
       !std::isfinite(requestedEvolution) || !std::isfinite(requestedSize) ||
       !std::isfinite(requestedDistribution) || !std::isfinite(requestedBaseFeather) ||
       !std::isfinite(requestedTopBrightness) || !std::isfinite(requestedBottomBrightness) ||
       !std::isfinite(requestedSunlightStrength) ||
       requestedBottom < 96.0f || requestedBottom > 8000.0f ||
       requestedThickness < 256.0f || requestedThickness > 6000.0f ||
       requestedCoverage < 0.10f || requestedCoverage > 0.90f ||
       requestedDensity < 0.25f || requestedDensity > 5.0f ||
       requestedDetailStrength < 0.0f || requestedDetailStrength > 0.50f ||
       requestedWindSpeed < 0.0f || requestedWindSpeed > 32.0f ||
       requestedEvolution < 0.0f || requestedEvolution > 2.0f ||
       requestedQuality < 0 || requestedQuality > 2 ||
       requestedSize < 0.50f || requestedSize > 3.0f ||
       requestedDistribution < 0.0f || requestedDistribution > 1.0f ||
       requestedBaseFeather < 0.03f || requestedBaseFeather > 0.30f ||
       requestedTopBrightness < 0.0f || requestedTopBrightness > 3.0f ||
       requestedBottomBrightness < 0.0f || requestedBottomBrightness > 3.0f ||
       requestedSunlightStrength < 0.0f || requestedSunlightStrength > 3.0f)
    {
        return JNI_FALSE;
    }
    g_cloudEnabled.store(enabled == JNI_TRUE);
    g_cloudBottom.store(requestedBottom);
    g_cloudThickness.store(requestedThickness);
    g_cloudCoverage.store(requestedCoverage);
    g_cloudDensity.store(requestedDensity);
    g_cloudDetailStrength.store(requestedDetailStrength);
    g_cloudWindSpeed.store(requestedWindSpeed);
    g_cloudEvolution.store(requestedEvolution);
    g_cloudQuality.store(static_cast<uint32_t>(requestedQuality));
    g_cloudSize.store(requestedSize);
    g_cloudDistribution.store(requestedDistribution);
    g_cloudBaseFeather.store(requestedBaseFeather);
    g_cloudTopBrightness.store(requestedTopBrightness);
    g_cloudBottomBrightness.store(requestedBottomBrightness);
    g_cloudSunlightStrength.store(requestedSunlightStrength);
    g_volumetricCloudHistoryNeedsClear.store(true);
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL Java_com_example_vulkan_NativeVulkan_setRRMode(
    JNIEnv*,
    jclass,
    jint mode)
{
    const int requestedMode =
        static_cast<int>(mode);


    if(requestedMode < 0 ||
       requestedMode > 3)
    {
        fprintf(
            stderr,
            "[RR SETTINGS] Invalid mode=%d\n",
            requestedMode
        );

        return JNI_FALSE;
    }


    const int oldMode =
        g_rrMode.exchange(
            requestedMode
        );


    if(oldMode != requestedMode)
    {
        fprintf(
            stderr,
            "[RR SETTINGS] mode %d -> %d\n",
            oldMode,
            requestedMode
        );

        fflush(stderr);
    }


    return JNI_TRUE;
}


extern "C"
JNIEXPORT jint JNICALL Java_com_example_vulkan_NativeVulkan_getRRMode(
    JNIEnv*,
    jclass)
{
    return static_cast<jint>(
        g_rrMode.load()
    );
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_example_vulkan_NativeVulkan_setUpscalerMode(
    JNIEnv*, jclass, jint mode)
{
    if(mode < 0 || mode > 1) return JNI_FALSE;
    g_upscalerMode.store(static_cast<int>(mode));
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_example_vulkan_NativeVulkan_setSRMode(
    JNIEnv*, jclass, jint mode)
{
    if(mode < 0 || mode > 3) return JNI_FALSE;
    g_srMode.store(static_cast<int>(mode));
    return JNI_TRUE;
}


extern "C"
JNIEXPORT jboolean JNICALL Java_com_example_vulkan_NativeVulkan_setPerformanceSettingsNative(
    JNIEnv*, jclass,
    jint aoNearSamples, jint aoMidSamples, jint giBounces, jint sunShadowSamples,
    jint localLightRisCandidates, jint localLightAreaSamples,
    jint waterReflectionInterval, jint glossyFullDistance, jint dynamicRtasInterval)
{
    if(aoNearSamples < 0 || aoNearSamples > 4 ||
       aoMidSamples < 0 || aoMidSamples > 2 ||
       giBounces < 0 || giBounces > 2 ||
       sunShadowSamples < 1 || sunShadowSamples > 2 ||
       localLightRisCandidates < 1 || localLightRisCandidates > 2 ||
       localLightAreaSamples < 1 || localLightAreaSamples > 2 ||
       waterReflectionInterval < 0 || waterReflectionInterval > 4 ||
       glossyFullDistance < 16 || glossyFullDistance > 96 ||
       dynamicRtasInterval < 1 || dynamicRtasInterval > 8)
    {
        return JNI_FALSE;
    }
    g_perfAoNearSamples.store(static_cast<uint32_t>(aoNearSamples));
    g_perfAoMidSamples.store(static_cast<uint32_t>(aoMidSamples));
    g_perfGiBounces.store(static_cast<uint32_t>(giBounces));
    g_perfSunShadowSamples.store(static_cast<uint32_t>(sunShadowSamples));
    g_perfLocalLightRisCandidates.store(static_cast<uint32_t>(localLightRisCandidates));
    g_perfLocalLightAreaSamples.store(static_cast<uint32_t>(localLightAreaSamples));
    g_perfWaterReflectionInterval.store(static_cast<uint32_t>(waterReflectionInterval));
    g_perfGlossyFullDistance.store(static_cast<uint32_t>(glossyFullDistance));
    g_perfDynamicRtasInterval.store(static_cast<uint64_t>(dynamicRtasInterval));
    return JNI_TRUE;
}
extern "C"
JNIEXPORT jboolean JNICALL Java_com_example_vulkan_NativeVulkan_beginStreamlineFrame(
    JNIEnv*,
    jclass)
{
    return BeginStreamlineFrame()
        ? JNI_TRUE
        : JNI_FALSE;
}
extern "C"
JNIEXPORT jboolean JNICALL Java_com_example_vulkan_NativeVulkan_markSimulationStart(
    JNIEnv*,
    jclass)
{
    const bool result =
        SetCurrentPCLMarker(
            sl::PCLMarker::eSimulationStart
        );


    if(result)
    {
        static bool printed =
            false;


        if(!printed)
        {
            fprintf(
                stderr,
                "[PCL] SimulationStart ACTIVE frame=%u\n",
                g_currentSLFrameIndex
            );

            printed =
                true;
        }
    }


    return result
        ? JNI_TRUE
        : JNI_FALSE;
}


extern "C"
JNIEXPORT jboolean JNICALL Java_com_example_vulkan_NativeVulkan_markSimulationEnd(
    JNIEnv*,
    jclass)
{
    const bool result =
        SetCurrentPCLMarker(
            sl::PCLMarker::eSimulationEnd
        );


    if(result)
    {
        static bool printed =
            false;


        if(!printed)
        {
            fprintf(
                stderr,
                "[PCL] SimulationEnd ACTIVE frame=%u\n",
                g_currentSLFrameIndex
            );

            printed =
                true;
        }
    }


    return result
        ? JNI_TRUE
        : JNI_FALSE;
}
extern "C"
JNIEXPORT jboolean JNICALL Java_com_example_vulkan_NativeVulkan_markRenderSubmitStart(
    JNIEnv*,
    jclass)
{
    return MarkRenderSubmitStart()
        ? JNI_TRUE
        : JNI_FALSE;
}


extern "C"
JNIEXPORT jboolean JNICALL Java_com_example_vulkan_NativeVulkan_markRenderSubmitEnd(
    JNIEnv*,
    jclass)
{
    return MarkRenderSubmitEnd()
        ? JNI_TRUE
        : JNI_FALSE;
}
extern "C"
JNIEXPORT jboolean JNICALL Java_com_example_vulkan_NativeVulkan_markPresentStart(
    JNIEnv*,
    jclass)
{
    return MarkPresentStart()
        ? JNI_TRUE
        : JNI_FALSE;
}


extern "C"
JNIEXPORT jboolean JNICALL Java_com_example_vulkan_NativeVulkan_markPresentEnd(
    JNIEnv*,
    jclass)
{
    return MarkPresentEnd()
        ? JNI_TRUE
        : JNI_FALSE;
}
static jobjectArray
FGMakeJavaStringArray(
    JNIEnv* env,
    const std::vector<std::string>& values
)
{
    jclass stringClass =
        env->FindClass(
            "java/lang/String"
        );


    if(!stringClass)
    {
        return nullptr;
    }


    jobjectArray result =
        env->NewObjectArray(
            static_cast<jsize>(
                values.size()
            ),
            stringClass,
            nullptr
        );


    if(!result)
    {
        env->DeleteLocalRef(
            stringClass
        );

        return nullptr;
    }


    for(
        size_t i = 0;
        i < values.size();
        ++i
    )
    {
        jstring value =
            env->NewStringUTF(
                values[i].c_str()
            );


        if(value)
        {
            env->SetObjectArrayElement(
                result,
                static_cast<jsize>(i),
                value
            );


            env->DeleteLocalRef(
                value
            );
        }
    }


    env->DeleteLocalRef(
        stringClass
    );


    return result;
}
extern "C"
JNIEXPORT jboolean JNICALL Java_com_example_vulkan_NativeVulkan_isFGRequirementsReady(
    JNIEnv*,
    jclass
)
{
    return g_fgRequirementsReady
        ? JNI_TRUE
        : JNI_FALSE;
}


extern "C"
JNIEXPORT jobjectArray JNICALL Java_com_example_vulkan_NativeVulkan_getFGInstanceExtensions(
    JNIEnv* env,
    jclass
)
{
    return FGMakeJavaStringArray(
        env,
        g_fgInstanceExtensions
    );
}


extern "C"
JNIEXPORT jobjectArray JNICALL Java_com_example_vulkan_NativeVulkan_getFGDeviceExtensions(
    JNIEnv* env,
    jclass
)
{
    return FGMakeJavaStringArray(
        env,
        g_fgDeviceExtensions
    );
}


extern "C"
JNIEXPORT jobjectArray JNICALL Java_com_example_vulkan_NativeVulkan_getFGVulkan12Features(
    JNIEnv* env,
    jclass
)
{
    return FGMakeJavaStringArray(
        env,
        g_fgFeatures12
    );
}


extern "C"
JNIEXPORT jobjectArray JNICALL Java_com_example_vulkan_NativeVulkan_getFGVulkan13Features(
    JNIEnv* env,
    jclass
)
{
    return FGMakeJavaStringArray(
        env,
        g_fgFeatures13
    );
}


extern "C"
JNIEXPORT jint JNICALL Java_com_example_vulkan_NativeVulkan_getFGGraphicsQueuesRequired(
    JNIEnv*,
    jclass
)
{
    return static_cast<jint>(
        g_fgGraphicsQueuesRequired
    );
}


extern "C"
JNIEXPORT jint JNICALL Java_com_example_vulkan_NativeVulkan_getFGComputeQueuesRequired(
    JNIEnv*,
    jclass
)
{
    return static_cast<jint>(
        g_fgComputeQueuesRequired
    );
}


extern "C"
JNIEXPORT jint JNICALL Java_com_example_vulkan_NativeVulkan_getFGOpticalFlowQueuesRequired(
    JNIEnv*,
    jclass
)
{
    return static_cast<jint>(
        g_fgOpticalFlowQueuesRequired
    );
}
extern "C"
JNIEXPORT void JNICALL Java_com_example_vulkan_NativeVulkan_initStreamlineEarly(
    JNIEnv*,
    jclass)
{
    fprintf(
        stderr,
        "[SL EARLY] initStreamlineEarly ENTER device=%p\n",
        (void*)g_device
    );

    fflush(stderr);


    if(g_slInitialized)
    {
        fprintf(
            stderr,
            "[SL EARLY] already initialized\n"
        );

        fflush(stderr);
        return;
    }


    if(!g_slModule)
    {
        const std::string runtimeDirectory =
            GetRTXBackendDirectory();


        const std::string interposerPath =
            ResolveRTXRuntimePath(
                "sl.interposer.dll"
            );


        fprintf(
            stderr,
            "[SL EARLY PATH]"
            " backendDir=%s"
            " interposer=%s\n",
            runtimeDirectory.c_str(),
            interposerPath.c_str()
        );

        fflush(stderr);


        if(!runtimeDirectory.empty())
        {
            // Streamline subsequently loads sl.common.dll,
            // feature plugins and NGX DLLs by filename.
            // Keep the rtx_backend.dll directory in the Win32
            // DLL search path for those nested loads as well.
            if(
                !SetDllDirectoryA(
                    runtimeDirectory.c_str()
                )
            )
            {
                fprintf(
                    stderr,
                    "[SL EARLY PATH]"
                    " SetDllDirectoryA failed error=%lu\n",
                    GetLastError()
                );

                fflush(stderr);
            }
        }


        g_slModule =
            LoadLibraryExA(
                interposerPath.c_str(),
                nullptr,
                LOAD_WITH_ALTERED_SEARCH_PATH
            );
    }


    if(!g_slModule)
    {
        fprintf(
            stderr,
            "[SL EARLY]"
            " LoadLibraryEx sl.interposer.dll failed"
            " error=%lu"
            " resolved=%s\n",
            GetLastError(),
            ResolveRTXRuntimePath(
                "sl.interposer.dll"
            ).c_str()
        );

        fflush(stderr);
        return;
    }


    g_slInit =
        reinterpret_cast<PFN_slInit>(
            GetProcAddress(
                g_slModule,
                "slInit"
            )
        );


    g_slShutdown =
        reinterpret_cast<PFN_slShutdown>(
            GetProcAddress(
                g_slModule,
                "slShutdown"
            )
        );
    g_slGetFeatureRequirements =
    reinterpret_cast<PFN_slGetFeatureRequirements>(
        GetProcAddress(
            g_slModule,
            "slGetFeatureRequirements"
        )
    );


    if(!g_slInit)
    {
        fprintf(
            stderr,
            "[SL EARLY] slInit missing\n"
        );

        fflush(stderr);
        return;
    }
    if(!g_slGetFeatureRequirements)
    {
        fprintf(
            stderr,
            "[SL EARLY] slGetFeatureRequirements missing\n"
        );

        fflush(stderr);
        return;
    }


    const sl::Feature features[] =
    {
        sl::kFeatureDLSS,
        sl::kFeatureDLSS_RR,
        sl::kFeatureDLSS_G,
        sl::kFeatureReflex,
        sl::kFeaturePCL
    };
    sl::Preferences pref{};


    pref.logMessageCallback =
        StreamlineLogCallback;


    pref.showConsole =
        false;


    pref.logLevel =
        sl::LogLevel::eOff;


    pref.renderAPI =
        sl::RenderAPI::eVulkan;


    pref.applicationId =
        231313132;


    pref.featuresToLoad =
        features;


    pref.numFeaturesToLoad =
        5;


    // Streamline 2.12 Manual Hooking
    pref.flags |=
        sl::PreferenceFlags::eUseManualHooking;
    pref.flags |=
    sl::PreferenceFlags::eUseFrameBasedResourceTagging;


    fprintf(
        stderr,
        "[SL EARLY] BEFORE slInit device=%p manualHooking=1\n",
        (void*)g_device
    );

    fflush(stderr);


    sl::Result result =
        g_slInit(
            pref,
            sl::kSDKVersion
        );


    fprintf(
        stderr,
        "[SL EARLY] slInit result=%d device=%p\n",
        (int)result,
        (void*)g_device
    );

    fflush(stderr);


    if(result != sl::Result::eOk)
    {
        return;
    }


    g_slInitialized = true;


    fprintf(
        stderr,
        "[SL EARLY] INITIALIZED SUCCESSFULLY\n"
    );

    fflush(stderr);
    // =====================================================
// Streamline DLSS Vulkan requirements diagnostic
// 这里只读取和打印，不修改 Vulkan 配置
// =====================================================

sl::FeatureRequirements requirements{};


sl::Result requirementsResult =
    g_slGetFeatureRequirements(
        sl::kFeatureDLSS,
        requirements
    );


fprintf(
    stderr,
    "[SL REQ] slGetFeatureRequirements(DLSS) result=%d\n",
    (int)requirementsResult
);

fflush(stderr);


if(requirementsResult == sl::Result::eOk)
{
    fprintf(
        stderr,
        "[SL REQ QUEUES] graphics=%u compute=%u opticalFlow=%u\n",
        requirements.vkNumGraphicsQueuesRequired,
        requirements.vkNumComputeQueuesRequired,
        requirements.vkNumOpticalFlowQueuesRequired
    );


    fprintf(
        stderr,
        "[SL REQ INSTANCE EXT] count=%u\n",
        requirements.vkNumInstanceExtensions
    );


    for(uint32_t i = 0;
        i < requirements.vkNumInstanceExtensions;
        ++i)
    {
        const char* extensionName =
            requirements.vkInstanceExtensions
            ? requirements.vkInstanceExtensions[i]
            : nullptr;

        fprintf(
            stderr,
            "[SL REQ INSTANCE EXT %u] %s\n",
            i,
            extensionName
                ? extensionName
                : "<null>"
        );
    }


    fprintf(
        stderr,
        "[SL REQ DEVICE EXT] count=%u\n",
        requirements.vkNumDeviceExtensions
    );


    for(uint32_t i = 0;
        i < requirements.vkNumDeviceExtensions;
        ++i)
    {
        const char* extensionName =
            requirements.vkDeviceExtensions
            ? requirements.vkDeviceExtensions[i]
            : nullptr;

        fprintf(
            stderr,
            "[SL REQ DEVICE EXT %u] %s\n",
            i,
            extensionName
                ? extensionName
                : "<null>"
        );
    }


    fprintf(
        stderr,
        "[SL REQ VK12] count=%u\n",
        requirements.vkNumFeatures12
    );


    for(uint32_t i = 0;
        i < requirements.vkNumFeatures12;
        ++i)
    {
        const char* featureName =
            requirements.vkFeatures12
            ? requirements.vkFeatures12[i]
            : nullptr;

        fprintf(
            stderr,
            "[SL REQ VK12 %u] %s\n",
            i,
            featureName
                ? featureName
                : "<null>"
        );
    }


    fprintf(
        stderr,
        "[SL REQ VK13] count=%u\n",
        requirements.vkNumFeatures13
    );


    for(uint32_t i = 0;
        i < requirements.vkNumFeatures13;
        ++i)
    {
        const char* featureName =
            requirements.vkFeatures13
            ? requirements.vkFeatures13[i]
            : nullptr;

        fprintf(
            stderr,
            "[SL REQ VK13 %u] %s\n",
            i,
            featureName
                ? featureName
                : "<null>"
        );
    }


    fflush(stderr);
}
else
{
    fprintf(
        stderr,
        "[SL REQ] FAILED - do not modify Vulkan requirements yet\n"
    );

    fflush(stderr);
}
sl::FeatureRequirements fgRequirements{};


sl::Result fgRequirementsResult =
    g_slGetFeatureRequirements(
        sl::kFeatureDLSS_G,
        fgRequirements
    );


fprintf(
    stderr,
    "[FG-2.1 REQ]"
    " result=%d"
    " flags=0x%llX"
    " maxViewports=%u\n",

    static_cast<int>(
        fgRequirementsResult
    ),

    static_cast<unsigned long long>(
        fgRequirements.flags
    ),

    fgRequirements.maxNumViewports
);

fflush(stderr);


if(fgRequirementsResult == sl::Result::eOk)
{
   // =====================================================
// FG-2.4
// Copy Streamline-owned requirement data into our own
// persistent storage before Vulkan Instance/Device
// creation.
//
// Do NOT retain raw pointers from FeatureRequirements.
// =====================================================

g_fgRequirementsReady =
    false;


g_fgInstanceExtensions.clear();
g_fgDeviceExtensions.clear();
g_fgFeatures12.clear();
g_fgFeatures13.clear();


g_fgGraphicsQueuesRequired =
    fgRequirements.vkNumGraphicsQueuesRequired;

g_fgComputeQueuesRequired =
    fgRequirements.vkNumComputeQueuesRequired;

g_fgOpticalFlowQueuesRequired =
    fgRequirements.vkNumOpticalFlowQueuesRequired;


// -----------------------------------------------------
// Instance extensions
// -----------------------------------------------------

if(fgRequirements.vkInstanceExtensions)
{
    for(
        uint32_t i = 0;
        i < fgRequirements.vkNumInstanceExtensions;
        ++i
    )
    {
        const char* name =
            fgRequirements.vkInstanceExtensions[i];


        if(name && name[0] != '\0')
        {
            g_fgInstanceExtensions.emplace_back(
                name
            );
        }
    }
}


// -----------------------------------------------------
// Device extensions
// -----------------------------------------------------

if(fgRequirements.vkDeviceExtensions)
{
    for(
        uint32_t i = 0;
        i < fgRequirements.vkNumDeviceExtensions;
        ++i
    )
    {
        const char* name =
            fgRequirements.vkDeviceExtensions[i];


        if(name && name[0] != '\0')
        {
            g_fgDeviceExtensions.emplace_back(
                name
            );
        }
    }
}


// -----------------------------------------------------
// Vulkan 1.2 features
// -----------------------------------------------------

if(fgRequirements.vkFeatures12)
{
    for(
        uint32_t i = 0;
        i < fgRequirements.vkNumFeatures12;
        ++i
    )
    {
        const char* name =
            fgRequirements.vkFeatures12[i];


        if(name && name[0] != '\0')
        {
            g_fgFeatures12.emplace_back(
                name
            );
        }
    }
}


// -----------------------------------------------------
// Vulkan 1.3 features
// -----------------------------------------------------

if(fgRequirements.vkFeatures13)
{
    for(
        uint32_t i = 0;
        i < fgRequirements.vkNumFeatures13;
        ++i
    )
    {
        const char* name =
            fgRequirements.vkFeatures13[i];


        if(name && name[0] != '\0')
        {
            g_fgFeatures13.emplace_back(
                name
            );
        }
    }
}


g_fgRequirementsReady =
    true;


fprintf(
    stderr,
    "[FG-2.4 SNAPSHOT]"
    " ready=1"
    " instanceExt=%zu"
    " deviceExt=%zu"
    " vk12=%zu"
    " vk13=%zu"
    " graphics=%u"
    " compute=%u"
    " opticalFlow=%u\n",

    g_fgInstanceExtensions.size(),
    g_fgDeviceExtensions.size(),
    g_fgFeatures12.size(),
    g_fgFeatures13.size(),

    g_fgGraphicsQueuesRequired,
    g_fgComputeQueuesRequired,
    g_fgOpticalFlowQueuesRequired
);

fflush(stderr);
    fprintf(
        stderr,
        "[FG-2.1 QUEUES]"
        " graphics=%u"
        " compute=%u"
        " opticalFlow=%u\n",

        fgRequirements.vkNumGraphicsQueuesRequired,
        fgRequirements.vkNumComputeQueuesRequired,
        fgRequirements.vkNumOpticalFlowQueuesRequired
    );


    // -----------------------------------------------------
    // Instance extensions
    // -----------------------------------------------------

    fprintf(
        stderr,
        "[FG-2.1 INSTANCE EXT]"
        " count=%u\n",

        fgRequirements.vkNumInstanceExtensions
    );


    for(
        uint32_t i = 0;
        i < fgRequirements.vkNumInstanceExtensions;
        ++i
    )
    {
        const char* extensionName =
            fgRequirements.vkInstanceExtensions
                ? fgRequirements.vkInstanceExtensions[i]
                : nullptr;


        fprintf(
            stderr,
            "[FG-2.1 INSTANCE EXT %u] %s\n",

            i,

            extensionName
                ? extensionName
                : "<null>"
        );
    }


    // -----------------------------------------------------
    // Device extensions
    // -----------------------------------------------------

    fprintf(
        stderr,
        "[FG-2.1 DEVICE EXT]"
        " count=%u\n",

        fgRequirements.vkNumDeviceExtensions
    );


    for(
        uint32_t i = 0;
        i < fgRequirements.vkNumDeviceExtensions;
        ++i
    )
    {
        const char* extensionName =
            fgRequirements.vkDeviceExtensions
                ? fgRequirements.vkDeviceExtensions[i]
                : nullptr;


        fprintf(
            stderr,
            "[FG-2.1 DEVICE EXT %u] %s\n",

            i,

            extensionName
                ? extensionName
                : "<null>"
        );
    }


    // -----------------------------------------------------
    // Vulkan 1.2 features
    // -----------------------------------------------------

    fprintf(
        stderr,
        "[FG-2.1 VK12]"
        " count=%u\n",

        fgRequirements.vkNumFeatures12
    );


    for(
        uint32_t i = 0;
        i < fgRequirements.vkNumFeatures12;
        ++i
    )
    {
        const char* featureName =
            fgRequirements.vkFeatures12
                ? fgRequirements.vkFeatures12[i]
                : nullptr;


        fprintf(
            stderr,
            "[FG-2.1 VK12 %u] %s\n",

            i,

            featureName
                ? featureName
                : "<null>"
        );
    }


    // -----------------------------------------------------
    // Vulkan 1.3 features
    // -----------------------------------------------------

    fprintf(
        stderr,
        "[FG-2.1 VK13]"
        " count=%u\n",

        fgRequirements.vkNumFeatures13
    );


    for(
        uint32_t i = 0;
        i < fgRequirements.vkNumFeatures13;
        ++i
    )
    {
        const char* featureName =
            fgRequirements.vkFeatures13
                ? fgRequirements.vkFeatures13[i]
                : nullptr;


        fprintf(
            stderr,
            "[FG-2.1 VK13 %u] %s\n",

            i,

            featureName
                ? featureName
                : "<null>"
        );
    }


    fflush(stderr);
}
else
{g_fgRequirementsReady =
    false;

    g_fgInstanceExtensions.clear();
    g_fgDeviceExtensions.clear();
    g_fgFeatures12.clear();
    g_fgFeatures13.clear();

    g_fgGraphicsQueuesRequired = 0;
    g_fgComputeQueuesRequired = 0;
    g_fgOpticalFlowQueuesRequired = 0;
    fprintf(
        stderr,
        "[FG-2.1 REQ FAILED]"
        " result=%d\n",

        static_cast<int>(
            fgRequirementsResult
        )
    );

    fflush(stderr);
}
    // =====================================================
// Streamline DLSS Ray Reconstruction Vulkan requirements
//
// RR-1:
// 这里只查询并打印。
// 不在这里擅自修改 Vulkan Instance / Device 配置。
// =====================================================

sl::FeatureRequirements rrRequirements{};


sl::Result rrRequirementsResult =
    g_slGetFeatureRequirements(
        sl::kFeatureDLSS_RR,
        rrRequirements
    );


fprintf(
    stderr,
    "[SL RR REQ] slGetFeatureRequirements(DLSS_RR) result=%d\n",
    static_cast<int>(
        rrRequirementsResult
    )
);

fflush(stderr);


if(rrRequirementsResult == sl::Result::eOk)
{
    fprintf(
        stderr,
        "[SL RR REQ QUEUES]"
        " graphics=%u"
        " compute=%u"
        " opticalFlow=%u\n",
        rrRequirements.vkNumGraphicsQueuesRequired,
        rrRequirements.vkNumComputeQueuesRequired,
        rrRequirements.vkNumOpticalFlowQueuesRequired
    );


    fprintf(
        stderr,
        "[SL RR REQ INSTANCE EXT] count=%u\n",
        rrRequirements.vkNumInstanceExtensions
    );


    for(
        uint32_t i = 0;
        i < rrRequirements.vkNumInstanceExtensions;
        ++i
    )
    {
        const char* extensionName =
            rrRequirements.vkInstanceExtensions
                ? rrRequirements.vkInstanceExtensions[i]
                : nullptr;


        fprintf(
            stderr,
            "[SL RR REQ INSTANCE EXT %u] %s\n",
            i,
            extensionName
                ? extensionName
                : "<null>"
        );
    }


    fprintf(
        stderr,
        "[SL RR REQ DEVICE EXT] count=%u\n",
        rrRequirements.vkNumDeviceExtensions
    );


    for(
        uint32_t i = 0;
        i < rrRequirements.vkNumDeviceExtensions;
        ++i
    )
    {
        const char* extensionName =
            rrRequirements.vkDeviceExtensions
                ? rrRequirements.vkDeviceExtensions[i]
                : nullptr;


        fprintf(
            stderr,
            "[SL RR REQ DEVICE EXT %u] %s\n",
            i,
            extensionName
                ? extensionName
                : "<null>"
        );
    }


    fprintf(
        stderr,
        "[SL RR REQ VK12] count=%u\n",
        rrRequirements.vkNumFeatures12
    );


    for(
        uint32_t i = 0;
        i < rrRequirements.vkNumFeatures12;
        ++i
    )
    {
        const char* featureName =
            rrRequirements.vkFeatures12
                ? rrRequirements.vkFeatures12[i]
                : nullptr;


        fprintf(
            stderr,
            "[SL RR REQ VK12 %u] %s\n",
            i,
            featureName
                ? featureName
                : "<null>"
        );
    }


    fprintf(
        stderr,
        "[SL RR REQ VK13] count=%u\n",
        rrRequirements.vkNumFeatures13
    );


    for(
        uint32_t i = 0;
        i < rrRequirements.vkNumFeatures13;
        ++i
    )
    {
        const char* featureName =
            rrRequirements.vkFeatures13
                ? rrRequirements.vkFeatures13[i]
                : nullptr;


        fprintf(
            stderr,
            "[SL RR REQ VK13 %u] %s\n",
            i,
            featureName
                ? featureName
                : "<null>"
        );
    }


    fflush(stderr);
}
else
{
    fprintf(
        stderr,
        "[SL RR REQ] FAILED"
        " - do not modify Vulkan requirements yet\n"
    );

    fflush(stderr);
// =====================================================
// FG-2.1
// DLSS Frame Generation Vulkan requirements diagnostic.
//
// Probe only.
// DO NOT modify Vulkan Instance / Device yet.
// =====================================================

sl::FeatureRequirements fgRequirements{};


sl::Result fgRequirementsResult =
    g_slGetFeatureRequirements(
        sl::kFeatureDLSS_G,
        fgRequirements
    );


fprintf(
    stderr,
    "[FG-2.1 REQ]"
    " result=%d"
    " flags=0x%llX"
    " maxViewports=%u\n",

    static_cast<int>(
        fgRequirementsResult
    ),

    static_cast<unsigned long long>(
        fgRequirements.flags
    ),

    fgRequirements.maxNumViewports
);

fflush(stderr);


if(fgRequirementsResult == sl::Result::eOk)
{
    // -----------------------------------------------------
    // Queues
    // -----------------------------------------------------

    fprintf(
        stderr,
        "[FG-2.1 QUEUES]"
        " graphics=%u"
        " compute=%u"
        " opticalFlow=%u\n",

        fgRequirements.vkNumGraphicsQueuesRequired,
        fgRequirements.vkNumComputeQueuesRequired,
        fgRequirements.vkNumOpticalFlowQueuesRequired
    );


    // -----------------------------------------------------
    // Instance extensions
    // -----------------------------------------------------

    fprintf(
        stderr,
        "[FG-2.1 INSTANCE EXT]"
        " count=%u\n",

        fgRequirements.vkNumInstanceExtensions
    );


    for(
        uint32_t i = 0;
        i < fgRequirements.vkNumInstanceExtensions;
        ++i
    )
    {
        const char* extensionName =
            fgRequirements.vkInstanceExtensions
                ? fgRequirements.vkInstanceExtensions[i]
                : nullptr;


        fprintf(
            stderr,
            "[FG-2.1 INSTANCE EXT %u] %s\n",

            i,

            extensionName
                ? extensionName
                : "<null>"
        );
    }


    // -----------------------------------------------------
    // Device extensions
    // -----------------------------------------------------

    fprintf(
        stderr,
        "[FG-2.1 DEVICE EXT]"
        " count=%u\n",

        fgRequirements.vkNumDeviceExtensions
    );


    for(
        uint32_t i = 0;
        i < fgRequirements.vkNumDeviceExtensions;
        ++i
    )
    {
        const char* extensionName =
            fgRequirements.vkDeviceExtensions
                ? fgRequirements.vkDeviceExtensions[i]
                : nullptr;


        fprintf(
            stderr,
            "[FG-2.1 DEVICE EXT %u] %s\n",

            i,

            extensionName
                ? extensionName
                : "<null>"
        );
    }


    // -----------------------------------------------------
    // Vulkan 1.2 features
    // -----------------------------------------------------

    fprintf(
        stderr,
        "[FG-2.1 VK12]"
        " count=%u\n",

        fgRequirements.vkNumFeatures12
    );


    for(
        uint32_t i = 0;
        i < fgRequirements.vkNumFeatures12;
        ++i
    )
    {
        const char* featureName =
            fgRequirements.vkFeatures12
                ? fgRequirements.vkFeatures12[i]
                : nullptr;


        fprintf(
            stderr,
            "[FG-2.1 VK12 %u] %s\n",

            i,

            featureName
                ? featureName
                : "<null>"
        );
    }


    // -----------------------------------------------------
    // Vulkan 1.3 features
    // -----------------------------------------------------

    fprintf(
        stderr,
        "[FG-2.1 VK13]"
        " count=%u\n",

        fgRequirements.vkNumFeatures13
    );


    for(
        uint32_t i = 0;
        i < fgRequirements.vkNumFeatures13;
        ++i
    )
    {
        const char* featureName =
            fgRequirements.vkFeatures13
                ? fgRequirements.vkFeatures13[i]
                : nullptr;


        fprintf(
            stderr,
            "[FG-2.1 VK13 %u] %s\n",

            i,

            featureName
                ? featureName
                : "<null>"
        );
    }


    fflush(stderr);
}
else
{
    fprintf(
        stderr,
        "[FG-2.1 REQ FAILED]"
        " result=%d\n",

        static_cast<int>(
            fgRequirementsResult
        )
    );

    fflush(stderr);
}}

}

extern "C"// === 新增：供 Java 层每帧调用的摄像机矩阵更新接口 ===
JNIEXPORT void JNICALL Java_com_example_vulkan_NativeVulkan_updateCamera(
    JNIEnv* env,
    jclass clazz,
    jfloatArray viewInvArray,
    jfloatArray projInvArray,
    jint frameCount,
    jfloat cameraNear,
    jfloat cameraFar,
    jfloat cameraFov,
    jfloat overworldTimePhase,
    jfloat rainLevel,
    jfloat thunderLevel,
    jfloat dimensionCode,
    jfloat playerX,
    jfloat playerY,
    jfloat playerZ,
    jfloat playerVelocityX,
    jfloat playerVelocityY,
    jfloat playerVelocityZ,
    jfloat playerInWater,
    jfloat waterSurfaceY,
    jfloat cameraInWater)
{
    if (!g_cameraUboMapped) return;

    CameraUBO* ubo = static_cast<CameraUBO*>(g_cameraUboMapped);

    float nextViewInverse[16]{};
    float nextProjInverse[16]{};


    env->GetFloatArrayRegion(
        viewInvArray,
        0,
        16,
        nextViewInverse
    );

    env->GetFloatArrayRegion(
        projInvArray,
        0,
        16,
        nextProjInverse
    );


    const int32_t nextFrame =
        static_cast<int32_t>(frameCount);


    const bool restirHistoryValid =
        g_restirCameraHistoryValid &&
        g_restirLastCameraFrame >= 0 &&
        nextFrame == g_restirLastCameraFrame + 1;


    if(restirHistoryValid)
    {
        std::memcpy(
            ubo->prevViewInverse,
            ubo->viewInverse,
            sizeof(ubo->prevViewInverse)
        );

        std::memcpy(
            ubo->prevProjInverse,
            ubo->projInverse,
            sizeof(ubo->prevProjInverse)
        );


        GetDLSSFrameJitter(
            static_cast<uint32_t>(
                g_restirLastCameraFrame
            ),
            ubo->historyPrevJitterX,
            ubo->historyPrevJitterY
        );
    }
    else
    {
        std::memcpy(
            ubo->prevViewInverse,
            nextViewInverse,
            sizeof(ubo->prevViewInverse)
        );

        std::memcpy(
            ubo->prevProjInverse,
            nextProjInverse,
            sizeof(ubo->prevProjInverse)
        );


        GetDLSSFrameJitter(
            static_cast<uint32_t>(
                std::max(nextFrame, 0)
            ),
            ubo->historyPrevJitterX,
            ubo->historyPrevJitterY
        );
    }


    std::memcpy(
        ubo->viewInverse,
        nextViewInverse,
        sizeof(ubo->viewInverse)
    );

    std::memcpy(
        ubo->projInverse,
        nextProjInverse,
        sizeof(ubo->projInverse)
    );

    ubo->vertexBufferAddrLo = static_cast<uint32_t>(g_testVertexDeviceAddress & 0xFFFFFFFF);
    ubo->vertexBufferAddrHi = static_cast<uint32_t>(g_testVertexDeviceAddress >> 32);

    // 注入动态帧计数器
    ubo->frameCount =
        static_cast<uint32_t>(frameCount);

    const float waterWaveTimeSeconds =
        std::chrono::duration<float>(
            std::chrono::steady_clock::now() - g_waterWaveEpoch
        ).count();
    std::memcpy(
        &ubo->padding,
        &waterWaveTimeSeconds,
        sizeof(waterWaveTimeSeconds)
    );


    ubo->historyReadIndex =
        (static_cast<uint32_t>(frameCount) + 1u) & 1u;

    ubo->historyValid =
        restirHistoryValid
            ? 1u
            : 0u;

    ubo->historyPadding0 = 0u;
    ubo->historyPadding1 = 0u;
    ubo->perfAoNearSamples = g_perfAoNearSamples.load();
    ubo->perfAoMidSamples = g_perfAoMidSamples.load();
    ubo->perfGiBounces = g_perfGiBounces.load();
    ubo->perfSunShadowSamples = g_perfSunShadowSamples.load();
    ubo->perfLocalLightRisCandidates = g_perfLocalLightRisCandidates.load();
    ubo->perfLocalLightAreaSamples = g_perfLocalLightAreaSamples.load();
    ubo->perfWaterReflectionInterval = g_perfWaterReflectionInterval.load();
    ubo->perfGlossyFullDistance = g_perfGlossyFullDistance.load();

    ubo->cameraNear =
        static_cast<float>(cameraNear);

    ubo->cameraFar =
        static_cast<float>(cameraFar);

    ubo->cameraFov =
        static_cast<float>(cameraFov);

    ubo->cameraParamsPadding =
        g_noDirectLightAmbient.load();

    // GLSL CameraProperties.historyParams.z
    // Dynamic POM depth from RTX settings.
    ubo->historyParamsPadding0 =
        g_pomDepth.load();

    // GLSL CameraProperties.historyParams.w. This lane is otherwise unused
    // by the active pipeline; retain the UBO size and descriptor contract.
    ubo->historyParamsPadding1 =
        std::clamp(
            static_cast<float>(overworldTimePhase),
            0.0f,
            0.99999994f
        );

    ubo->playerX = static_cast<float>(playerX);
    ubo->playerY = static_cast<float>(playerY);
    ubo->playerZ = static_cast<float>(playerZ);
    ubo->playerInWater = playerInWater > 0.5f ? 1.0f : 0.0f;
    ubo->playerVelocityX = static_cast<float>(playerVelocityX);
    ubo->playerVelocityY = static_cast<float>(playerVelocityY);
    ubo->playerVelocityZ = static_cast<float>(playerVelocityZ);
    ubo->playerHorizontalSpeed = std::sqrt(
        ubo->playerVelocityX * ubo->playerVelocityX +
        ubo->playerVelocityZ * ubo->playerVelocityZ
    );

    // Capture real-entity water transitions here, where the JVM provides the
    // authoritative player state.  The compute pass consumes the event at
    // 30 Hz and then evolves it as a persistent wave field.
    const bool nowInWater = ubo->playerInWater > 0.5f;
    if(nowInWater || !g_waterSimPlayerStateValid)
    {
        g_waterSimLastSurfaceY = static_cast<float>(waterSurfaceY);
    }

    if(g_waterSimPlayerStateValid &&
       nowInWater != g_waterSimPlayerWasInWater)
    {
        const float eventSpeed = std::sqrt(
            ubo->playerVelocityX * ubo->playerVelocityX +
            ubo->playerVelocityY * ubo->playerVelocityY +
            ubo->playerVelocityZ * ubo->playerVelocityZ
        );
        g_waterSimPendingImpulse = true;
        g_waterSimPendingImpulseX = ubo->playerX;
        g_waterSimPendingImpulseZ = ubo->playerZ;
        g_waterSimPendingImpulseStrength =
            (nowInWater ? 1.0f : -1.0f) *
            (0.42f + 0.05f * std::min(eventSpeed, 8.0f));
        g_waterSimKeepAliveSeconds = 8.0f;
    }

    g_waterSimPlayerStateValid = true;
    g_waterSimPlayerWasInWater = nowInWater;

    ubo->waterSurfaceY = g_waterSimLastSurfaceY;
    ubo->waterSimOriginX = g_waterSimOriginX;
    ubo->waterSimOriginZ = g_waterSimOriginZ;
    ubo->waterSimTexelWorldSize = WATER_SIM_TEXEL_WORLD_SIZE;
    ubo->waterSimReadLayer = g_waterSimReadLayer;
    ubo->waterSimEnabled =
        (g_waterSimOriginValid &&
         (nowInWater || g_waterSimPendingImpulse ||
          g_waterSimKeepAliveSeconds > 0.0f)) ? 1u : 0u;
    ubo->waterSimImpulsePending =
        g_waterSimPendingImpulse ? 1u : 0u;
    ubo->cameraInWater =
        cameraInWater > 0.5f ? 1u : 0u;
    ubo->waterSimImpulseX = g_waterSimPendingImpulseX;
    ubo->waterSimImpulseZ = g_waterSimPendingImpulseZ;
    ubo->waterSimImpulseStrength = g_waterSimPendingImpulseStrength;
    ubo->waterSimImpulseSurfaceY = g_waterSimLastSurfaceY;

    // weatherParams feeds C2/C3 volumetric cloud density functions.
    //   x = rain strength, y = thunder strength, z = dimension code, w = unused
    // Java passes live values via the updateCamera JNI bridge.
    ubo->weatherParams[0] = std::clamp(static_cast<float>(rainLevel), 0.0f, 1.0f);
    ubo->weatherParams[1] = std::clamp(static_cast<float>(thunderLevel), 0.0f, 1.0f);
    ubo->weatherParams[2] = static_cast<float>(dimensionCode);
    ubo->weatherParams[3] = 0.0f;

    const float requestedDimension = static_cast<float>(dimensionCode);
    constexpr float anchorDistance = 8192.0f;
    const float anchorDeltaX = static_cast<float>(playerX) - g_cloudWorldAnchorX;
    const float anchorDeltaZ = static_cast<float>(playerZ) - g_cloudWorldAnchorZ;
    if(!g_cloudWorldAnchorValid || requestedDimension != g_cloudWorldAnchorDimension ||
       anchorDeltaX * anchorDeltaX + anchorDeltaZ * anchorDeltaZ > anchorDistance * anchorDistance)
    {
        // Quantization keeps the anchor stable across adjacent camera updates.
        g_cloudWorldAnchorX = std::floor(static_cast<float>(playerX) / 2048.0f) * 2048.0f;
        g_cloudWorldAnchorZ = std::floor(static_cast<float>(playerZ) / 2048.0f) * 2048.0f;
        g_cloudWorldAnchorDimension = requestedDimension;
        g_cloudWorldAnchorValid = true;
        g_volumetricCloudHistoryNeedsClear.store(true);
    }
    populateCloudParams(ubo->cloudParams);
    populateSkyParams(ubo->skyParams);


    g_restirCameraHistoryValid = true;
    g_restirLastCameraFrame = nextFrame;
}
extern "C"
JNIEXPORT void JNICALL Java_com_example_vulkan_NativeVulkan_setSerEnabledNative(
    JNIEnv*,
    jclass,
    jboolean enabled
)
{
    g_serEnabled.store(enabled == JNI_TRUE);
}

extern "C"
JNIEXPORT void JNICALL Java_com_example_vulkan_NativeVulkan_initDeviceHandlesNative(JNIEnv*,jclass,jlong instanceHandle,jlong physicalDeviceHandle,jlong deviceHandle,jlong queueHandle,jint queueFamily,jlong,jlong){
    fprintf(stderr,
"[CPP TEST] initDeviceHandlesNative ENTER\n");
    fflush(stderr);
    g_instance=reinterpret_cast<VkInstance>(instanceHandle);
    g_physicalDevice=reinterpret_cast<VkPhysicalDevice>(physicalDeviceHandle);
    g_device=reinterpret_cast<VkDevice>(deviceHandle);
    g_queue=reinterpret_cast<VkQueue>(queueHandle);
    g_queueFamily=static_cast<uint32_t>(queueFamily);
    fprintf(stderr,
"[BEFORE SL INIT]\n");
    fflush(stderr);
    initStreamlineInternal();
    fprintf(stderr,
    "[AFTER SL INIT]\n");
    fflush(stderr);
    fprintf(stderr,
"[CHECK AFTER SAVE] g_device=%p\n",
(void*)g_device);

    fflush(stderr);

    fprintf(stderr,
    "[CHECK INSIDE SL INIT] addr=%p value=%p\n",
    (void*)&g_device,
    (void*)g_device);

    fflush(stderr);
    fprintf(stderr,
        "[CPP] Vulkan handles received:\n"
        " instance=%p\n"
        " physicalDevice=%p\n"
        " device=%p\n"
        " queue=%p\n"
        " queueFamily=%u\n",
        (void*)g_instance,
        (void*)g_physicalDevice,
        (void*)g_device,
        (void*)g_queue,
        g_queueFamily);

    fflush(stderr);


    fprintf(stderr,
"[RT CMD POOL ENTER]\n");
    fflush(stderr);

    createRTCommandPool();
    fprintf(stderr,
    "[RT CMD POOL DONE]\n");
    fflush(stderr);

    if(!g_instance||!g_physicalDevice||!g_device||!g_queue){std::cerr<<"[Native C++] Invalid Vulkan handles passed from Java."<<std::endl;return;}
    try{
        probeOpacityMicromapSupport();
        probeSerSupport();

        // SER-3: select the optional raygen once the device feature is enabled.
        // The reorder-hint property is telemetry only; Orion's EXT path
        // uses the feature gate and remains valid when the hint is NONE.
        // A missing/invalid SER SPIR-V asset still falls back to traceRayEXT.
        std::vector<uint8_t> rgen;
        bool serRaygenLoaded = false;
        const bool serShaderRequested =
            g_serEnabled.load() && g_serFeatureSupported;
        if(serShaderRequested)
        {
            try
            {
                rgen = loadSpv("shaders/rt_test_ser.rgen.spv");
                serRaygenLoaded = true;
            }
            catch(const std::exception& error)
            {
                RTLog(std::string("[SER-3 PIPELINE] requested=1 loaded=0 fallback=traceRay reason=") + error.what());
            }
        }
        if(!serRaygenLoaded)
        {
            rgen = loadSpv("shaders/rt_test.rgen.spv");
        }
        {
            std::stringstream log;
            log << "[SER-3 PIPELINE] requested="
                << (serShaderRequested ? 1 : 0)
                << " loaded=" << (serRaygenLoaded ? 1 : 0)
                << " mode=" << (serRaygenLoaded ? "secondaryGI_SER" : "traceRayFallback");
            RTLog(log.str());
        }

        // Load the shared/water modules plus the dedicated non-water
        // translucent closest-hit and any-hit modules.
        auto chit=loadSpv("shaders/rt_test.rchit.spv");
        auto miss=loadSpv("shaders/rt_test.rmiss.spv");
        auto ahit=loadSpv("shaders/rt_test.rahit.spv");
        auto translucentChit=loadSpv("shaders/rt_test_translucent.rchit.spv");
        auto translucentAhit=loadSpv("shaders/rt_test_translucent.rahit.spv");
        auto restirFinalRgen=loadSpv("shaders/rt_restir_final.rgen.spv");
        // Dedicated compact RGB visibility ray type for the one final ReSTIR
        // local-light connection.  It never carries the full camera HitPayload.
        auto localVisibilityMiss=loadSpv("shaders/rt_local_visibility.rmiss.spv");
        auto localVisibilityAhit=loadSpv("shaders/rt_local_visibility.rahit.spv");
        auto localVisibilityTranslucentAhit=
            loadSpv("shaders/rt_local_visibility_translucent.rahit.spv");
        auto comp=loadSpv("shaders/rt_vpt_irradiance.comp.spv");


        g_rayTracingDescriptorSetLayout=createRTDescriptorSetLayout();
        VkPipelineLayoutCreateInfo pli{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        pli.setLayoutCount=1;
        pli.pSetLayouts=&g_rayTracingDescriptorSetLayout;
        check(vkCreatePipelineLayout(g_device,&pli,nullptr,&g_rayTracingPipelineLayout),"Failed to create RT pipeline layout");

        VkShaderModule mods[10]={
            VK_NULL_HANDLE,
            VK_NULL_HANDLE,
            VK_NULL_HANDLE,
            VK_NULL_HANDLE,
            VK_NULL_HANDLE,
            VK_NULL_HANDLE,
            VK_NULL_HANDLE,
            VK_NULL_HANDLE,
            VK_NULL_HANDLE,
            VK_NULL_HANDLE
        };
        try{
            mods[0]=createShaderModule(g_device,rgen);
            mods[1]=createShaderModule(g_device,chit);
            mods[2]=createShaderModule(g_device,miss);
            mods[3]=createShaderModule(g_device,ahit);
            mods[4]=createShaderModule(g_device,translucentChit);
            mods[5]=createShaderModule(g_device,translucentAhit);
            mods[6]=createShaderModule(g_device,restirFinalRgen);
            mods[7]=createShaderModule(g_device,localVisibilityMiss);
            mods[8]=createShaderModule(g_device,localVisibilityAhit);
            mods[9]=createShaderModule(g_device,localVisibilityTranslucentAhit);

            VkPipelineShaderStageCreateInfo
                stages[10]{};


            stages[0]={
                VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                nullptr,
                0,
                VK_SHADER_STAGE_RAYGEN_BIT_KHR,
                mods[0],
                "main",
                nullptr
            };


            stages[1]={
                VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                nullptr,
                0,
                VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR,
                mods[1],
                "main",
                nullptr
            };


            stages[2]={
                VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                nullptr,
                0,
                VK_SHADER_STAGE_MISS_BIT_KHR,
                mods[2],
                "main",
                nullptr
            };


            stages[3]={
                VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                nullptr,
                0,
                VK_SHADER_STAGE_ANY_HIT_BIT_KHR,
                mods[3],
                "main",
                nullptr
            };

            stages[4]={
                VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                nullptr,
                0,
                VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR,
                mods[4],
                "main",
                nullptr
            };

            stages[5]={
                VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                nullptr,
                0,
                VK_SHADER_STAGE_ANY_HIT_BIT_KHR,
                mods[5],
                "main",
                nullptr
            };

            // Lightweight ReSTIR DI final visibility pass.  Its trace uses the
            // dedicated payload location 1 and the second SBT ray-type block.
            stages[6]={
                VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                nullptr,
                0,
                VK_SHADER_STAGE_RAYGEN_BIT_KHR,
                mods[6],
                "main",
                nullptr
            };

            stages[7]={
                VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                nullptr,
                0,
                VK_SHADER_STAGE_MISS_BIT_KHR,
                mods[7],
                "main",
                nullptr
            };

            stages[8]={
                VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                nullptr,
                0,
                VK_SHADER_STAGE_ANY_HIT_BIT_KHR,
                mods[8],
                "main",
                nullptr
            };

            stages[9]={
                VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                nullptr,
                0,
                VK_SHADER_STAGE_ANY_HIT_BIT_KHR,
                mods[9],
                "main",
                nullptr
            };


            // 4. 重组 Shader Group (极度关键：Hit Group 绑定双着色器)
            VkRayTracingShaderGroupCreateInfoKHR
     groups[9]{};

            // Group 0: Raygen
            groups[0]={VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR,nullptr,VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR,0,VK_SHADER_UNUSED_KHR,VK_SHADER_UNUSED_KHR,VK_SHADER_UNUSED_KHR};

            // Group 1: opaque/cutout hit group (Closest Hit = 1, Any Hit = 3)
            groups[1]={VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR,nullptr,VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR,VK_SHADER_UNUSED_KHR,1,3,VK_SHADER_UNUSED_KHR};

            // Group 2: Miss
            groups[2]={VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR,nullptr,VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR,2,VK_SHADER_UNUSED_KHR,VK_SHADER_UNUSED_KHR,VK_SHADER_UNUSED_KHR};

            // Group 3: dedicated non-water translucent SPVs.
            groups[3]={VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR,nullptr,VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR,VK_SHADER_UNUSED_KHR,4,5,VK_SHADER_UNUSED_KHR};

            // Group 4: water remains on the existing water-capable shaders.
            groups[4]={VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR,nullptr,VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR,VK_SHADER_UNUSED_KHR,1,3,VK_SHADER_UNUSED_KHR};

            // Group 5: ReSTIR DI final visibility RayGen.
            groups[5]={VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR,nullptr,VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR,6,VK_SHADER_UNUSED_KHR,VK_SHADER_UNUSED_KHR,VK_SHADER_UNUSED_KHR};

            // Group 6: compact local-light visibility miss (payload location 1).
            groups[6]={VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR,nullptr,VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR,7,VK_SHADER_UNUSED_KHR,VK_SHADER_UNUSED_KHR,VK_SHADER_UNUSED_KHR};

            // Group 7: compact opaque/OMM/water/entity visibility Any-Hit.
            // Fully opaque geometry still skips Any-Hit in hardware.
            groups[7]={VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR,nullptr,VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR,VK_SHADER_UNUSED_KHR,VK_SHADER_UNUSED_KHR,8,VK_SHADER_UNUSED_KHR};

            // Group 8: compact non-water translucent visibility Any-Hit.
            groups[8]={VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR,nullptr,VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR,VK_SHADER_UNUSED_KHR,VK_SHADER_UNUSED_KHR,9,VK_SHADER_UNUSED_KHR};

            VkRayTracingPipelineCreateInfoKHR pci{VK_STRUCTURE_TYPE_RAY_TRACING_PIPELINE_CREATE_INFO_KHR};
            pci.stageCount=10;
            pci.pStages=stages;
            pci.groupCount=9;
            pci.pGroups=groups;
            pci.maxPipelineRayRecursionDepth=1;
            pci.layout=g_rayTracingPipelineLayout;
            if(g_ommNativeEnabled)
            {
                pci.flags |=
                    VK_PIPELINE_CREATE_RAY_TRACING_OPACITY_MICROMAP_BIT_EXT;
            }

            auto createPipe=reinterpret_cast<PFN_vkCreateRayTracingPipelinesKHR>(vkGetDeviceProcAddr(g_device,"vkCreateRayTracingPipelinesKHR"));
            if(!createPipe)throw std::runtime_error("vkCreateRayTracingPipelinesKHR unavailable");

            check(createPipe(g_device,VK_NULL_HANDLE,VK_NULL_HANDLE,1,&pci,nullptr,&g_rayTracingPipeline),"Failed to create RT pipeline");
            RTLog(
                "[RT TRANSLUCENT SPV]"
                " group3=rt_test_translucent.rchit+rahit"
                " group4=rt_test.rchit+rahit"
                " stages=10 restirFinalRaygen=group5"
                " localVisibilityMiss=group6"
                " localVisibilityHit=group7/8"
            );
        }catch(...){
            for(auto m:mods)if(m)vkDestroyShaderModule(g_device,m,nullptr);
            throw;
        }
        for(auto m:mods)if(m)vkDestroyShaderModule(g_device,m,nullptr);

        std::cout<<"[RT] createTestVertexBuffer"<<std::endl;
        createTestVertexBuffer();

        std::cout<<"[RT] createBLAS"<<std::endl;
        createBLAS();

        std::cout<<"[RT] createTLAS"<<std::endl;
        createTLAS();

        std::cout<<"[RT] createRayTracingSBT"<<std::endl;
        createRayTracingSBT();

        std::cout<<"[ReSTIR DI] create spatial pipeline"<<std::endl;
        createReSTIRDISpatialPipeline();

        std::cout<<"[ReSTIR DI] create reconstruction pipeline"<<std::endl;
        createReSTIRDIReconstructPipeline();

        std::cout<<"[RT] createCameraUBO"<<std::endl;
        createCameraUBO();

        std::cout<<"[RT] createDescriptor"<<std::endl;
        createRayTracingDescriptorPoolAndSet();

        std::cout << "[TYNDALL CLOUD] create static 3D noise resources" << std::endl;
        createCloudNoiseResources();

        std::cout << "[WATER NORMAL] create mipmapped detail normal" << std::endl;
        createWaterDetailNormalResources();


        std::cout
            << "[WATER SIM] create Eclipse-style player water pipeline"
            << std::endl;
        createWaterSimulationPipeline();

        std::cout
            << "[WATER CAUSTIC] create wavefront-area caustic pipeline"
            << std::endl;
        createWaterCausticPipeline();


        std::cout<<"[VLI-1.4] create voxel compute pipeline"<<std::endl;
        createVLIComputePipeline();


        std::cout
            << "[RR-6.5.2] create SDR post pipeline"
            << std::endl;

        createSDRPostPipeline();


        std::cout
            << "[FG-4.1] create UI Alpha pipeline"
            << std::endl;

        try
        {
            createFGUIAlphaPipeline();
        }
        catch(const std::exception& e)
        {
            fprintf(
                stderr,
                "[FG-4.1 PIPELINE] FAILED: %s\n",
                e.what()
            );

            fflush(stderr);
        }


        std::cout
            << "[Native C++] RT initialization completed."
            << std::endl;
    }
    catch(const std::exception& e)
    {
        std::cerr<<"[Native C++] RT initialization failed: "<<e.what()<<std::endl;
    }

}

static void rebuildWorldGeometry(
    size_t maxSectionUpdates = std::numeric_limits<size_t>::max()
);

static uint64_t packSectionKey(int x, int y, int z, int layerId) {
    return (static_cast<uint64_t>(layerId & 0xF) << 60) |
           (static_cast<uint64_t>(y & 0xFFFF) << 44) |
           (static_cast<uint64_t>(z & 0x3FFFFF) << 22) |
           (static_cast<uint64_t>(x & 0x3FFFFF));
}


static void clearVLISections()
{
    std::lock_guard<std::mutex> lock(g_vliMutex);
    g_vliSections.clear();
    std::fill(g_vliTableCpu.begin(), g_vliTableCpu.end(), VLITableSlot{});
    g_vliFreePages.clear();
    g_vliNextPage = 0u;
    g_vliDirtySourcePages.clear();
    g_vliDirtyTableSlots.clear();
    g_vliDirtySolveTableSlots.clear();
    g_vptDirtyEmitterPages.clear();
    g_vptDirtyEmitterAliasPages.clear();
    g_vptDirtyCandidatePages.clear();
    g_vptDirtySourceDirectoryPages.clear();
    g_vptEmitterRevision = 1u;
    g_vptSourceDirectoryRevision = 1u;
    g_vptTemporalFrame = 0u;
    g_vliNeedsInitialClear = true;
    // A real world/VLI reset is still a legitimate full history invalidation.
    g_vptDirectHistoryNeedsClear = true;
}


static void eraseVLITableSlot(uint32_t eraseSlot)
{
    const uint32_t mask = VLI_PAGE_TABLE_CAPACITY - 1u;
    g_vliTableCpu[eraseSlot] = {};
    g_vliDirtyTableSlots.insert(eraseSlot);

    uint32_t cursor = (eraseSlot + 1u) & mask;
    while(g_vliTableCpu[cursor].pagePlusOne != 0u)
    {
        const VLITableSlot displaced = g_vliTableCpu[cursor];
        g_vliTableCpu[cursor] = {};
        g_vliDirtyTableSlots.insert(cursor);

        bool found = false;
        const uint32_t newSlot = vliFindTableSlot(
            displaced.sectionX,
            displaced.sectionY,
            displaced.sectionZ,
            found
        );
        if(found)
        {
            throw std::runtime_error("VLI table reinsertion unexpectedly found an entry");
        }

        g_vliTableCpu[newSlot] = displaced;
        g_vliDirtyTableSlots.insert(newSlot);

        const uint64_t sectionKey = packSectionKey(
            displaced.sectionX,
            displaced.sectionY,
            displaced.sectionZ,
            0
        );
        const auto pageIt = g_vliSections.find(sectionKey);
        if(pageIt != g_vliSections.end())
        {
            pageIt->second.tableSlot = newSlot;
            g_vliDirtySolveTableSlots.insert(newSlot);
        }

        cursor = (cursor + 1u) & mask;
    }
}


extern "C"
JNIEXPORT void JNICALL Java_com_example_vulkan_NativeVulkan_uploadVoxelLightSection(
    JNIEnv*,
    jclass,
    jint sectionX,
    jint sectionY,
    jint sectionZ,
    jlong voxelPointer,
    jint voxelBytes
)
{
    constexpr size_t expectedBytes =
        static_cast<size_t>(VLI_SOURCE_WORDS) * sizeof(uint32_t);

    if(
        voxelPointer == 0 ||
        voxelBytes != static_cast<jint>(expectedBytes)
    )
    {
        std::stringstream ss;
        ss << "[VLI-1.4 SOURCE] INVALID section=("
           << sectionX << "," << sectionY << "," << sectionZ << ") bytes="
           << voxelBytes;
        RTLog(ss.str());
        return;
    }

    const uint32_t* const incomingWords =
        reinterpret_cast<const uint32_t*>(
            static_cast<uintptr_t>(voxelPointer)
        );

    // Do not scan all 4096 source words only to emit a diagnostic. This JNI
    // callback is invoked for every rebuilt Section; the persistent-page
    // memcmp below remains the authoritative change detector.

    const uint64_t sectionKey = packSectionKey(sectionX, sectionY, sectionZ, 0);
    uint32_t pageIndex = 0u;

    // The light callback can arrive before or after this voxel callback.
    // Snapshot its accepted records before taking g_vliMutex; do not derive
    // VPT emitters from sourceWords when the authoritative light Section is
    // already available.
    std::vector<LocalLightRecord> localLightsForVPT;
    {
        std::lock_guard<std::mutex> lightLock(g_lightMutex);
        const auto lightIt = g_lightSections.find(sectionKey);
        if(lightIt != g_lightSections.end())
        {
            localLightsForVPT = lightIt->second;
        }
    }

    {
        std::lock_guard<std::mutex> lock(g_vliMutex);
        auto pageIt = g_vliSections.find(sectionKey);

        if(
            pageIt != g_vliSections.end() &&
            pageIt->second.sourceWords.size() == VLI_SOURCE_WORDS &&
            std::memcmp(
                pageIt->second.sourceWords.data(),
                incomingWords,
                expectedBytes
            ) == 0
        )
        {
            return;
        }

        // SectionCompiler owns voxelPointer for this JNI call.  Do not create
        // or copy a 4096-word vector until the exact persistent page comparison
        // proves that VLI source data actually changed.
        std::vector<uint32_t> incoming(VLI_SOURCE_WORDS);
        std::memcpy(
            incoming.data(),
            incomingWords,
            expectedBytes
        );

        const uint32_t emitterRevision = g_vptEmitterRevision++;
        if(g_vptEmitterRevision == 0u)
        {
            g_vptEmitterRevision = 1u;
        }
        std::vector<uint32_t> emitterIndexWords =
            buildVPTEmitterIndexWordsForSection(
                sectionX,
                sectionY,
                sectionZ,
                incoming,
                localLightsForVPT,
                emitterRevision
            );
        std::vector<uint32_t> emitterAliasWords =
            buildVPTEmitterAliasWords(emitterIndexWords);

        if(pageIt != g_vliSections.end())
        {
            pageIt->second.sourceWords = std::move(incoming);
            pageIt->second.emitterIndexWords = std::move(emitterIndexWords);
            pageIt->second.emitterAliasWords = std::move(emitterAliasWords);
            pageIndex = pageIt->second.pageIndex;
        }
        else
        {
            if(!g_vliFreePages.empty())
            {
                pageIndex = g_vliFreePages.back();
                g_vliFreePages.pop_back();
            }
            else
            {
                if(g_vliNextPage >= VLI_MAX_SECTION_PAGES)
                {
                    RTLog("[VLI-1.4 SOURCE] page capacity exhausted; Section ignored");
                    return;
                }
                pageIndex = g_vliNextPage++;
            }

            bool found = false;
            const uint32_t tableSlot = vliFindTableSlot(sectionX, sectionY, sectionZ, found);
            if(found)
            {
                // JNI entry points must never allow a native exception to
                // cross into Java. The table/map mismatch is recoverable on
                // the next full VLI reset; keep the render thread alive and
                // report the invariant violation instead.
                RTLog("[VLI-1.4 SOURCE] table/map mismatch; Section ignored");
                return;
            }

            VLITableSlot& slot = g_vliTableCpu[tableSlot];
            slot.sectionX = sectionX;
            slot.sectionY = sectionY;
            slot.sectionZ = sectionZ;
            slot.pagePlusOne = pageIndex + 1u;
            g_vliDirtyTableSlots.insert(tableSlot);

            VLISectionCpuPage page{};
            page.sectionX = sectionX;
            page.sectionY = sectionY;
            page.sectionZ = sectionZ;
            page.pageIndex = pageIndex;
            page.tableSlot = tableSlot;
            page.sourceWords = std::move(incoming);
            page.emitterIndexWords = std::move(emitterIndexWords);
            page.emitterAliasWords = std::move(emitterAliasWords);
            g_vliSections.emplace(sectionKey, std::move(page));
        }

        g_vliDirtySourcePages.insert(pageIndex);
        g_vptDirtyEmitterPages.insert(pageIndex);
        g_vptDirtyEmitterAliasPages.insert(pageIndex);
        markVLINeighborhoodDirty(sectionX, sectionY, sectionZ);
        rebuildVPTCandidateNeighborhoodLocked(sectionX, sectionY, sectionZ);
    }
}


// =====================================================
// RT Local Lights - L2 native receiver
//
// Java calls this once whenever a render Section is rebuilt.
//
// lightCount == 0:
//     replace this Section with an empty light list.
//
// No Vulkan buffer / descriptor / shader work is done here yet.
// This stage only proves the Java -> JNI -> native Section cache.
// =====================================================

extern "C"
JNIEXPORT void JNICALL Java_com_example_vulkan_NativeVulkan_uploadLightSection(
    JNIEnv*,
    jclass,
    jint sectionX,
    jint sectionY,
    jint sectionZ,
    jlong lightPointer,
    jint lightCount,
    jint lightStride
)
{
    constexpr int
        EXPECTED_LIGHT_STRIDE = 20;

    constexpr int
        MAX_LIGHTS_PER_SECTION = 16 * 16 * 16;


    const int
        count =
            static_cast<int>(
                lightCount
            );

    const int
        stride =
            static_cast<int>(
                lightStride
            );


    // -------------------------------------------------
    // Validate the Java/native contract before touching
    // the raw pointer.
    // -------------------------------------------------

    if(
        count < 0 ||
        count > MAX_LIGHTS_PER_SECTION ||
        stride != EXPECTED_LIGHT_STRIDE ||
        (
            count > 0 &&
            lightPointer == 0
        )
    )
    {
        std::stringstream ss;

        ss
            << "[RT LOCAL LIGHTS L2] INVALID"
            << " section=("
            << sectionX
            << ","
            << sectionY
            << ","
            << sectionZ
            << ")"
            << " ptr=0x"
            << std::hex
            << static_cast<unsigned long long>(
                lightPointer
            )
            << std::dec
            << " count="
            << count
            << " stride="
            << stride;

        RTLog(
            ss.str()
        );

        return;
    }


    const uint8_t* rawBase = nullptr;

    if(count > 0)
    {
        rawBase =
            reinterpret_cast<const uint8_t*>(
                static_cast<uintptr_t>(
                    lightPointer
                )
            );

        // The Java producer writes five packed int32 values per record and
        // LocalLightRecord is statically fixed at the same 20-byte layout.
        // Preserve native filtering exactly: a raw early-return is valid only
        // when every incoming emission is already accepted by the old path.
        std::lock_guard<std::mutex> lock(g_lightMutex);

        const uint64_t sectionKey =
            packSectionKey(
                static_cast<int>(sectionX),
                static_cast<int>(sectionY),
                static_cast<int>(sectionZ),
                0
            );

        const auto oldIt = g_lightSections.find(sectionKey);

        if(
            oldIt != g_lightSections.end() &&
            oldIt->second.size() == static_cast<size_t>(count)
        )
        {
            bool allRecordsAreAccepted = true;

            for(int i = 0; i < count; ++i)
            {
                int32_t emissionLevel = 0;

                std::memcpy(
                    &emissionLevel,
                    rawBase +
                        static_cast<size_t>(i) *
                            static_cast<size_t>(stride) +
                        12,
                    sizeof(emissionLevel)
                );

                if(emissionLevel <= 0 || emissionLevel > 15)
                {
                    allRecordsAreAccepted = false;
                    break;
                }
            }

            if(
                allRecordsAreAccepted &&
                std::memcmp(
                    oldIt->second.data(),
                    rawBase,
                    static_cast<size_t>(count) *
                        sizeof(LocalLightRecord)
                ) == 0
            )
            {
                return;
            }
        }
    }


    std::vector<LocalLightRecord>
        incomingLights;


    incomingLights.reserve(
        static_cast<size_t>(
            count
        )
    );


    if(count > 0)
    {
        for(
            int i = 0;
            i < count;
            ++i
        )
        {
            const uint8_t* const
                recordBase =
                    rawBase +
                    static_cast<size_t>(
                        i
                    ) *
                    static_cast<size_t>(
                        stride
                    );


            LocalLightRecord
                light{};


            // Use memcpy rather than dereferencing int32_t*
            // so this remains safe even if the incoming address
            // is not naturally aligned.
            std::memcpy(
                &light.worldX,
                recordBase + 0,
                sizeof(int32_t)
            );

            std::memcpy(
                &light.worldY,
                recordBase + 4,
                sizeof(int32_t)
            );

            std::memcpy(
                &light.worldZ,
                recordBase + 8,
                sizeof(int32_t)
            );

            std::memcpy(
                &light.emissionLevel,
                recordBase + 12,
                sizeof(int32_t)
            );

            std::memcpy(
                &light.typeCode,
                recordBase + 16,
                sizeof(int32_t)
            );


            // Minecraft block-light emission is expected to be
            // in the 1..15 range. Invalid records are discarded
            // instead of entering the native cache.
            if(
                light.emissionLevel <= 0 ||
                light.emissionLevel > 15
            )
            {
                continue;
            }


            incomingLights.push_back(
                light
            );
        }
    }


    // The light cache is separate from the geometry cache, but
    // it intentionally reuses the exact same coordinate packing.
    // Layer 0 is safe here because this is a separate unordered_map.
    const uint64_t
        sectionKey =
            packSectionKey(
                static_cast<int>(
                    sectionX
                ),
                static_cast<int>(
                    sectionY
                ),
                static_cast<int>(
                    sectionZ
                ),
                0
            );


    size_t
        sectionLightCount = 0;

    size_t
        totalLightCount = 0;

    bool
        sectionChanged = false;


    {
        std::lock_guard<std::mutex>
            lock(
                g_lightMutex
            );


        auto oldIt =
            g_lightSections.find(
                sectionKey
            );

        if(
            oldIt !=
                g_lightSections.end() &&
            sameLocalLightPayload(
                oldIt->second,
                incomingLights
            )
        )
        {
            sectionLightCount =
                oldIt->second.size();

            totalLightCount =
                g_totalLocalLights;
        }
        else if(
            oldIt ==
                g_lightSections.end() &&
            incomingLights.empty()
        )
        {
            sectionLightCount =
                0;

            totalLightCount =
                g_totalLocalLights;
        }
        else
        {
            if(
                oldIt !=
                    g_lightSections.end()
            )
            {
                const size_t
                    oldCount =
                        oldIt->second.size();


                if(
                    g_totalLocalLights >=
                    oldCount
                )
                {
                    g_totalLocalLights -=
                        oldCount;
                }
                else
                {
                    // Defensive recovery. This should never be
                    // needed unless the accounting is corrupted.
                    g_totalLocalLights =
                        0;
                }
            }

            if(
                incomingLights.empty()
            )
            {
                g_lightSections.erase(
                    sectionKey
                );

                sectionLightCount =
                    0;
            }
            else
            {
                sectionLightCount =
                    incomingLights.size();

                g_lightSections[
                    sectionKey
                ] =
                    std::move(
                        incomingLights
                    );


                g_totalLocalLights +=
                    sectionLightCount;
            }

            totalLightCount =
                g_totalLocalLights;

            sectionChanged =
                true;
        }
    }


    if(!sectionChanged)
    {
        return;
    }

    // Mirror the accepted Binding 17 payload into VPT after releasing
    // g_lightMutex.  The VPT page may not exist yet; uploadVoxelLightSection
    // handles that inverse arrival order with its own light-cache snapshot.
    std::vector<LocalLightRecord> localLightsForVPT;
    {
        std::lock_guard<std::mutex> lightLock(g_lightMutex);
        const auto lightIt = g_lightSections.find(sectionKey);
        if(lightIt != g_lightSections.end())
        {
            localLightsForVPT = lightIt->second;
        }
    }
    refreshVPTEmitterSectionFromLocalLights(
        static_cast<int32_t>(sectionX),
        static_cast<int32_t>(sectionY),
        static_cast<int32_t>(sectionZ),
        localLightsForVPT
    );

    g_localLightsDirty =
        true;


    std::stringstream ss;

    ss
        << "[RT LOCAL LIGHTS L2]"
        << " section=("
        << sectionX
        << ","
        << sectionY
        << ","
        << sectionZ
        << ")"
        << " lights="
        << sectionLightCount
        << " total="
        << totalLightCount;


    RTLog(
        ss.str()
    );
}


// =====================================================
// RT Local Lights - L6
//
// ClientChunkCache.drop(ChunkPos)
//     -> remove every light Section belonging to chunk X/Z
//
// Section key layout currently used by packSectionKey():
//
// bits  0..21 = sectionX
// bits 22..43 = sectionZ
// bits 44..59 = sectionY
// bits 60..63 = layer
//
// Local-light cache always uses layer 0.
// Therefore matching the low 44 bits removes every Y Section
// belonging to the unloaded chunk.
// =====================================================

extern "C"
JNIEXPORT void JNICALL Java_com_example_vulkan_NativeVulkan_removeLightChunk(
    JNIEnv*,
    jclass,
    jint chunkX,
    jint chunkZ
)
{
    constexpr uint64_t
        SECTION_XZ_MASK =
            (1ull << 44) - 1ull;


    const uint64_t
        targetXZ =
            packSectionKey(
                static_cast<int>(
                    chunkX
                ),
                0,
                static_cast<int>(
                    chunkZ
                ),
                0
            ) &
            SECTION_XZ_MASK;


    size_t removedSections =
        0;

    size_t removedLights =
        0;

    size_t remainingLights =
        0;


    {
        std::lock_guard<std::mutex>
            lock(
                g_lightMutex
            );


        for(
            auto it =
                g_lightSections.begin();

            it !=
                g_lightSections.end();
        )
        {
            const bool sameChunk =
                (
                    it->first &
                    SECTION_XZ_MASK
                ) ==
                targetXZ;


            if(!sameChunk)
            {
                ++it;

                continue;
            }


            const size_t
                sectionLightCount =
                    it->second.size();


            removedLights +=
                sectionLightCount;


            if(
                g_totalLocalLights >=
                sectionLightCount
            )
            {
                g_totalLocalLights -=
                    sectionLightCount;
            }
            else
            {
                // Defensive recovery only.
                g_totalLocalLights =
                    0;
            }


            it =
                g_lightSections.erase(
                    it
                );


            ++removedSections;
        }


        remainingLights =
            g_totalLocalLights;
    }


    if(removedSections > 0)
    {
        g_localLightsDirty =
            true;
    }


    size_t vliRemovedSections = 0;
    {
        std::lock_guard<std::mutex> lock(g_vliMutex);
        std::vector<std::array<int32_t, 3>> removedCoordinates;

        for(auto it = g_vliSections.begin(); it != g_vliSections.end();)
        {
            const VLISectionCpuPage& page = it->second;
            if(page.sectionX != chunkX || page.sectionZ != chunkZ)
            {
                ++it;
                continue;
            }

            removedCoordinates.push_back({
                page.sectionX,
                page.sectionY,
                page.sectionZ
            });
            g_vliFreePages.push_back(page.pageIndex);
            eraseVLITableSlot(page.tableSlot);
            it = g_vliSections.erase(it);
            ++vliRemovedSections;
        }

        for(const auto& coordinate : removedCoordinates)
        {
            markVLINeighborhoodDirty(
                coordinate[0],
                coordinate[1],
                coordinate[2]
            );
            rebuildVPTCandidateNeighborhoodLocked(
                coordinate[0],
                coordinate[1],
                coordinate[2]
            );
        }
    }


    std::stringstream ss;

    ss
        << "[RT LOCAL LIGHTS L6]"
        << " chunk=("
        << chunkX
        << ","
        << chunkZ
        << ")"
        << " removedSections="
        << removedSections
        << " removedLights="
        << removedLights
        << " total="
        << remainingLights
        << " vliRemovedSections="
        << vliRemovedSections;


    RTLog(
        ss.str()
    );
}


extern "C"
JNIEXPORT void JNICALL Java_com_example_vulkan_NativeVulkan_uploadCutoutOmmSection(
    JNIEnv*,
    jclass,
    jint sectionX,
    jint sectionY,
    jint sectionZ,
    jlong dataPointer,
    jint dataBytes,
    jint triangleCount,
    jint subdivisionLevel,
    jint knownMicroTriangleCount
)
{
    constexpr uint32_t kOmmLevel = 4u;
    constexpr uint32_t kOmmBytesPerTriangle = 64u;
    const uint64_t key = packSectionKey(sectionX, sectionY, sectionZ, 1);
    const uint64_t spatialKey = key & SPATIAL_SECTION_KEY_MASK;

    const bool valid =
        dataPointer != 0 &&
        triangleCount > 0 &&
        subdivisionLevel == static_cast<jint>(kOmmLevel) &&
        knownMicroTriangleCount > 0 &&
        static_cast<uint64_t>(triangleCount) <=
            std::numeric_limits<uint32_t>::max() / kOmmBytesPerTriangle &&
        static_cast<uint64_t>(knownMicroTriangleCount) <=
            static_cast<uint64_t>(triangleCount) * 256u &&
        dataBytes == static_cast<jint>(
            static_cast<uint64_t>(triangleCount) * kOmmBytesPerTriangle
        );

    std::lock_guard<std::mutex> lock(g_chunkMutex);
    if(!valid)
    {
        if(g_cutoutOmmInputs.erase(key) != 0u)
        {
            g_dirtySectionKeys.insert(spatialKey);
            markWorldGeometryDirty();
        }
        return;
    }

    OmmCutoutInput input{};
    input.triangleCount = static_cast<uint32_t>(triangleCount);
    input.subdivisionLevel = kOmmLevel;
    input.knownMicroTriangleCount =
        static_cast<uint32_t>(knownMicroTriangleCount);
    const uint8_t* source = reinterpret_cast<const uint8_t*>(dataPointer);
    input.data.assign(source, source + static_cast<size_t>(dataBytes));

    auto existing = g_cutoutOmmInputs.find(key);
    if(existing != g_cutoutOmmInputs.end() &&
       existing->second.triangleCount == input.triangleCount &&
       existing->second.knownMicroTriangleCount == input.knownMicroTriangleCount &&
       existing->second.data == input.data)
    {
        return;
    }

    g_cutoutOmmInputs[key] = std::move(input);
    g_dirtySectionKeys.insert(spatialKey);
    markWorldGeometryDirty();
}

extern "C"
JNIEXPORT void JNICALL Java_com_example_vulkan_NativeVulkan_uploadChunk(
        JNIEnv* env,
        jclass clazz,
        jint sectionX,
        jint sectionY,
        jint sectionZ,
        jint layerId,
        jlong vertexPointer,
        jint vertexCount,
        jint vertexStride)
{
    uint64_t chunkKey =
    packSectionKey(
        sectionX,
        sectionY,
        sectionZ,
        layerId
    );

    const uint64_t spatialSectionKey =
        chunkKey &
        SPATIAL_SECTION_KEY_MASK;

    // 【核心修复 1】恢复并严格锁定 28 字节步长校验，拦截非法数据
    if (vertexCount == 0 || vertexPointer == 0 || vertexStride != 28) {
        std::lock_guard<std::mutex> lock(g_chunkMutex);
        g_chunkMeshes.erase(chunkKey);

        if(layerId == 1)
        {
            g_cutoutOmmInputs.erase(chunkKey);
        }

        g_chunkRawPayloads.erase(chunkKey);

        // A zero-vertex upload is authoritative SectionCompiler removal
        // data.  The CPU layer cache may already have been erased while the
        // active Section BLAS still contains the previous geometry; therefore
        // removal must invalidate the spatial Section unconditionally.
        // Otherwise destroyed blocks can persist until an unrelated edit
        // happens to rebuild the same Section.
        g_dirtySectionKeys.insert(spatialSectionKey);
        markWorldGeometryDirty();
        return;
    }

    if (vertexCount % 4 != 0) return;

    const uint8_t* const rawMemory =
        reinterpret_cast<const uint8_t*>(vertexPointer);

    const size_t rawPayloadBytes =
        static_cast<size_t>(vertexCount) *
        static_cast<size_t>(vertexStride);

    {
        std::lock_guard<std::mutex> lock(g_chunkMutex);

        const auto rawIt = g_chunkRawPayloads.find(chunkKey);
        const auto meshIt = g_chunkMeshes.find(chunkKey);

        if(
            rawIt != g_chunkRawPayloads.end() &&
            meshIt != g_chunkMeshes.end() &&
            rawIt->second.size() == rawPayloadBytes &&
            std::memcmp(
                rawIt->second.data(),
                rawMemory,
                rawPayloadBytes
            ) == 0
        )
        {
            return;
        }
    }

    int quadCount = vertexCount / 4;
    int triVertexCount = quadCount * 6;

    std::vector<Vertex> repackedVertices(triVertexCount);
    uint32_t rainPuddleCandidateQuads = 0u;
    uint32_t rainPuddleMalformedQuads = 0u;

    for (int q = 0; q < quadCount; ++q) {
        Vertex quadVerts[4];
        uint32_t rainPuddleMarkedVertices = 0u;

        for (int v = 0; v < 4; ++v) {
            const uint8_t* currentRawVertex =
                rawMemory + ((q * 4 + v) * vertexStride);

            // 1. 提取坐标 (Offset 0)
            float lx = *reinterpret_cast<const float*>(currentRawVertex + 0);
            float ly = *reinterpret_cast<const float*>(currentRawVertex + 4);
            float lz = *reinterpret_cast<const float*>(currentRawVertex + 8);
            quadVerts[v].x = lx + (sectionX * 16.0f);
            quadVerts[v].y = ly + (sectionY * 16.0f);
            quadVerts[v].z = lz + (sectionZ * 16.0f);

            // 2. 提取颜色 (Offset 12)
            const uint8_t* colorPtr = currentRawVertex + 12;
            quadVerts[v].r = colorPtr[0] / 255.0f;
            quadVerts[v].g = colorPtr[1] / 255.0f;
            quadVerts[v].b = colorPtr[2] / 255.0f;
            quadVerts[v].a = colorPtr[3] / 255.0f;

            // 3. 提取 UV (Offset 16)
            quadVerts[v].u = *reinterpret_cast<const float*>(currentRawVertex + 16);
            quadVerts[v].v = *reinterpret_cast<const float*>(currentRawVertex + 20);

            // 4. Read the RT-only byte-27 material marker.  Water keeps 1;
            // clear glass / pane is 2; stained glass / pane is DyeColor ID +
            // 3 (3..18); 23 marks a solid upward terrain face eligible for
            // the rain-puddle overlay.
            const uint8_t materialMarker =
                *(currentRawVertex + 27);

            const bool rainPuddleCandidate = materialMarker == 23u;
            rainPuddleMarkedVertices += rainPuddleCandidate ? 1u : 0u;


            // =====================================================
            // T1-1 Generic translucent classification
            //
            // 0 = opaque
            // 1 = water
            // 2 = clear glass / clear pane
            // 3..18 = stained glass / stained pane
            // 19 = tinted glass; 20 = ice; 21 = slime; 22 = honey
            //
            // Water marker always has priority because water itself
            // also belongs to a translucent render layer.
            // =====================================================

            if(materialMarker == 1u)
            {
                quadVerts[v].materialId =
                    1.0f;
            }
            else if(
                materialMarker >= 2u &&
                materialMarker <= 22u
            )
            {
                if(materialMarker <= 18u)
                {
                    quadVerts[v].materialId =
                        2.0f;

                    // This changes only the native 48-byte RT copy.  The
                    // source 28-byte Minecraft mesh stays intact for vanilla
                    // raster.
                    applyStainedGlassDyeToRTVertex(
                        materialMarker,
                        quadVerts[v]
                    );
                }
                else
                {
                    // Keep the profile in a fractional RT-only material ID;
                    // shader payload IDs preserve it without an ABI change.
                    quadVerts[v].materialId =
                        2.0f +
                        0.1f *
                        static_cast<float>(materialMarker - 18u);
                }
            }
            else
            {
                quadVerts[v].materialId =
                    0.0f;
            }

            // 5. 【核心修复 3】物理抹除扩容结构的脏数据，严格保障 48 字节对齐安全
            // pad1 is private to the expanded RT vertex.  Reserve 0.25 as
            // the puddle-candidate tag; it cannot collide with entity state
            // (which is >= 1) or the normal terrain value (0).
            quadVerts[v].pad1 = rainPuddleCandidate ? 0.25f : 0.0f;
            quadVerts[v].pad2 = 0.0f;
        }

        if(rainPuddleMarkedVertices == 4u)
        {
            ++rainPuddleCandidateQuads;
        }
        else if(rainPuddleMarkedVertices != 0u)
        {
            ++rainPuddleMalformedQuads;
        }

        // 拓扑重组：Quad 转 Triangle (0-1-2, 0-2-3)
        repackedVertices[q * 6 + 0] = quadVerts[0];
        repackedVertices[q * 6 + 1] = quadVerts[1];
        repackedVertices[q * 6 + 2] = quadVerts[2];
        repackedVertices[q * 6 + 3] = quadVerts[0];
        repackedVertices[q * 6 + 4] = quadVerts[2];
        repackedVertices[q * 6 + 5] = quadVerts[3];
    }

    if(!g_rainPuddleLoggedGeometry && rainPuddleCandidateQuads != 0u)
    {
        std::ostringstream log;
        log
            << "[RAIN PUDDLE] geometry"
            << " section=(" << sectionX << "," << sectionY << "," << sectionZ
            << ") layer=" << layerId
            << " candidateQuads=" << rainPuddleCandidateQuads
            << " rayHitTriangles=" << (rainPuddleCandidateQuads * 2u)
            << " malformedQuads=" << rainPuddleMalformedQuads;
        RTLog(log.str());
        g_rainPuddleLoggedGeometry = true;
    }

    {
        std::lock_guard<std::mutex> lock(g_chunkMutex);

        const auto existingIt =
            g_chunkMeshes.find(chunkKey);

        const auto rawIt =
            g_chunkRawPayloads.find(chunkKey);

        if(
            rawIt != g_chunkRawPayloads.end() &&
            rawIt->second.size() == rawPayloadBytes &&
            std::memcmp(
                rawIt->second.data(),
                rawMemory,
                rawPayloadBytes
            ) == 0
        )
        {
            return;
        }

        if(
            existingIt != g_chunkMeshes.end() &&
            sameVertexPayload(
                existingIt->second,
                repackedVertices
            )
        )
        {
            g_chunkRawPayloads[chunkKey] =
                std::vector<uint8_t>(
                    rawMemory,
                    rawMemory + rawPayloadBytes
                );

            return;
        }

        g_chunkMeshes[chunkKey] = std::move(repackedVertices);
        g_chunkRawPayloads[chunkKey] =
            std::vector<uint8_t>(
                rawMemory,
                rawMemory + rawPayloadBytes
            );
        g_dirtySectionKeys.insert(spatialSectionKey);
        markWorldGeometryDirty();
    }
}

// =====================================================
// Dynamic entity-model RT input
//
// Java captures ModelFeatureRenderer batches after vanilla has applied the
// current animation pose.  Positions are camera-relative at that point, so
// convert them to the RT world's absolute coordinates here using the exact
// same camera inverse matrix consumed by RayGen.
// =====================================================
extern "C"
JNIEXPORT void JNICALL Java_com_example_vulkan_NativeVulkan_beginEntityFrame(
    JNIEnv*,
    jclass
)
{
    std::lock_guard<std::mutex> lock(g_chunkMutex);
    if(g_cameraUboMapped)
    {
        const CameraUBO* camera =
            static_cast<const CameraUBO*>(g_cameraUboMapped);
        g_dynamicCaptureCameraX = camera->viewInverse[12];
        g_dynamicCaptureCameraY = camera->viewInverse[13];
        g_dynamicCaptureCameraZ = camera->viewInverse[14];
    }
    else
    {
        g_dynamicCaptureCameraX = 0.0f;
        g_dynamicCaptureCameraY = 0.0f;
        g_dynamicCaptureCameraZ = 0.0f;
    }
    // The previous frame payload is swapped into g_chunkMeshes. Reuse its
    // exact size as the next frame's initial capacity so the first batch does
    // not trigger geometric vector growth and copies.
    if(const auto it = g_chunkMeshes.find(ENTITY_RT_LAYER_KEY);
       it != g_chunkMeshes.end())
    {
        g_entityFrameVertices.reserve(it->second.size());
    }
    if(const auto it = g_chunkMeshes.find(LOCAL_PLAYER_RT_LAYER_KEY);
       it != g_chunkMeshes.end())
    {
        g_localPlayerFrameVertices.reserve(it->second.size());
    }
    g_entityFrameVertices.clear();
    g_localPlayerFrameVertices.clear();
    g_entityFrameOpen = true;
}

extern "C"
JNIEXPORT void JNICALL Java_com_example_vulkan_NativeVulkan_uploadEntityBatch(
    JNIEnv*,
    jclass,
    jint textureSlot,
    jlong vertexPointer,
    jint vertexCount,
    jint vertexStride
)
{
    if(
        textureSlot < 1 ||
        textureSlot > static_cast<jint>(ENTITY_RT_TEXTURE_SLOT_COUNT) ||
        vertexPointer == 0 ||
        vertexCount <= 0 ||
        vertexStride != static_cast<jint>(sizeof(Vertex)) ||
        (vertexCount % 3) != 0
    )
    {
        return;
    }

    std::lock_guard<std::mutex> lock(g_chunkMutex);
    if(!g_entityFrameOpen)
    {
        return;
    }

    const Vertex* source =
        reinterpret_cast<const Vertex*>(vertexPointer);

    // EntityRtCapture marks every model batch belonging to the local
    // AvatarRenderState in pad1.  Keep that geometry in a separate layer so
    // the primary camera mask can exclude it while reflections/shadows/GI
    // still see it.
    const bool localPlayerProxy = source[0].pad1 > 0.5f;
    std::vector<Vertex>& target = localPlayerProxy
        ? g_localPlayerFrameVertices
        : g_entityFrameVertices;
    const size_t oldSize = target.size();
    target.resize(
        oldSize + static_cast<size_t>(vertexCount)
    );

    for(int index = 0; index < vertexCount; ++index)
    {
        Vertex vertex = source[index];
        vertex.x += g_dynamicCaptureCameraX;
        vertex.y += g_dynamicCaptureCameraY;
        vertex.z += g_dynamicCaptureCameraZ;
        vertex.materialId = 0.0f;
        // pad2 is unused by the original terrain/translucent ABI.  Reuse it
        // for entity texture selection so every existing shader still reads
        // the same 48-byte vertex stride.
        vertex.pad2 = static_cast<float>(textureSlot);
        target[oldSize + static_cast<size_t>(index)] = vertex;
    }
}

extern "C"
JNIEXPORT void JNICALL Java_com_example_vulkan_NativeVulkan_endEntityFrame(
    JNIEnv*,
    jclass
)
{
    std::lock_guard<std::mutex> lock(g_chunkMutex);
    if(!g_entityFrameOpen)
    {
        return;
    }

    g_entityFrameOpen = false;

    const auto currentEntity = g_chunkMeshes.find(ENTITY_RT_LAYER_KEY);
    const auto currentLocalPlayer = g_chunkMeshes.find(LOCAL_PLAYER_RT_LAYER_KEY);
    const bool entityChanged = currentEntity == g_chunkMeshes.end()
        ? !g_entityFrameVertices.empty()
        : !sameVertexPayload(currentEntity->second, g_entityFrameVertices);
    const bool localPlayerChanged = currentLocalPlayer == g_chunkMeshes.end()
        ? !g_localPlayerFrameVertices.empty()
        : !sameVertexPayload(currentLocalPlayer->second, g_localPlayerFrameVertices);
    const bool changed = entityChanged || localPlayerChanged;

    if(
        !changed &&
        !g_localPlayerASUpdatePending &&
        !g_dynamicRTASUpdatePending
    )
    {
        return;
    }

    if(entityChanged)
    {
        if(g_entityFrameVertices.empty())
        {
            g_chunkMeshes.erase(ENTITY_RT_LAYER_KEY);
        }
        else
        {
            std::vector<Vertex>& cached =
                g_chunkMeshes[ENTITY_RT_LAYER_KEY];
            cached.swap(g_entityFrameVertices);
            g_entityFrameVertices.clear();
        }
    }

    if(localPlayerChanged)
    {
        if(g_localPlayerFrameVertices.empty())
        {
            g_chunkMeshes.erase(LOCAL_PLAYER_RT_LAYER_KEY);
        }
        else
        {
            std::vector<Vertex>& cached =
                g_chunkMeshes[LOCAL_PLAYER_RT_LAYER_KEY];
            cached.swap(g_localPlayerFrameVertices);
            g_localPlayerFrameVertices.clear();
        }
    }

    // Cache all animated entities immediately, but rebuild their shared
    // Section only at its short cadence.
    bool requestRebuild = requestDynamicRTASRebuild(entityChanged);
    if(localPlayerChanged)
    {
        g_localPlayerASUpdatePending = true;
    }

    if(g_localPlayerASUpdatePending)
    {
        const bool firstUpdate =
            g_lastLocalPlayerASUpdateFrame == UINT64_MAX;
        const bool updateDue =
            firstUpdate ||
            g_perfL10FrameSerial >=
                g_lastLocalPlayerASUpdateFrame +
                LOCAL_PLAYER_AS_UPDATE_INTERVAL;
        if(updateDue)
        {
            requestRebuild = true;
            g_localPlayerASUpdatePending = false;
            g_lastLocalPlayerASUpdateFrame = g_perfL10FrameSerial;
        }
    }

    if(requestRebuild &&
       (localPlayerChanged || g_localPlayerASUpdatePending))
    {
        // The combined entity rebuild also includes the newest local-player
        // payload, so no second cadence-triggered rebuild is needed.
        g_localPlayerASUpdatePending = false;
        g_lastLocalPlayerASUpdateFrame = g_perfL10FrameSerial;
    }

    if(requestRebuild)
    {
        g_dirtySectionKeys.insert(ENTITY_RT_SPATIAL_KEY);
        markWorldGeometryDirty();
        g_entityRebuildRequested.store(true, std::memory_order_release);
    }
}

extern "C"
JNIEXPORT void JNICALL Java_com_example_vulkan_NativeVulkan_updateEntityTexture(
    JNIEnv*,
    jclass,
    jint textureSlot,
    jlong imageView,
    jlong sampler
)
{
    if(
        textureSlot < 1 ||
        textureSlot > static_cast<jint>(ENTITY_RT_TEXTURE_SLOT_COUNT) ||
        imageView == 0 ||
        sampler == 0
    )
    {
        return;
    }

    const uint32_t slot = static_cast<uint32_t>(textureSlot - 1);
    const VkImageView newView = reinterpret_cast<VkImageView>(imageView);
    const VkSampler newSampler = reinterpret_cast<VkSampler>(sampler);

    if(
        g_entityTextureViews[slot] != newView ||
        g_entityTextureSamplers[slot] != newSampler
    )
    {
        g_entityTextureViews[slot] = newView;
        g_entityTextureSamplers[slot] = newSampler;
        g_entityTexturesDirty = true;
    }
}

extern "C"
JNIEXPORT void JNICALL Java_com_example_vulkan_NativeVulkan_beginParticleFrame(
    JNIEnv*,
    jclass
)
{
    std::lock_guard<std::mutex> lock(g_chunkMutex);
    if(g_cameraUboMapped)
    {
        const CameraUBO* camera =
            static_cast<const CameraUBO*>(g_cameraUboMapped);
        g_dynamicCaptureCameraX = camera->viewInverse[12];
        g_dynamicCaptureCameraY = camera->viewInverse[13];
        g_dynamicCaptureCameraZ = camera->viewInverse[14];
    }
    else
    {
        g_dynamicCaptureCameraX = 0.0f;
        g_dynamicCaptureCameraY = 0.0f;
        g_dynamicCaptureCameraZ = 0.0f;
    }
    if(const auto it = g_chunkMeshes.find(PARTICLE_RT_LAYER_KEY);
       it != g_chunkMeshes.end())
    {
        g_particleFrameVertices.reserve(it->second.size());
    }
    g_particleFrameVertices.clear();
    g_particleFrameOpen = true;
}

extern "C"
JNIEXPORT void JNICALL Java_com_example_vulkan_NativeVulkan_uploadParticleBatch(
    JNIEnv*,
    jclass,
    jlong vertexPointer,
    jint vertexCount,
    jint vertexStride
)
{
    if(vertexPointer == 0 || vertexCount <= 0 ||
       vertexStride != static_cast<jint>(sizeof(Vertex)) ||
       (vertexCount % 3) != 0)
    {
        return;
    }

    std::lock_guard<std::mutex> lock(g_chunkMutex);
    if(!g_particleFrameOpen)
    {
        return;
    }

    const Vertex* source = reinterpret_cast<const Vertex*>(vertexPointer);
    const size_t oldSize = g_particleFrameVertices.size();
    g_particleFrameVertices.resize(oldSize + static_cast<size_t>(vertexCount));
    for(int index = 0; index < vertexCount; ++index)
    {
        Vertex vertex = source[index];
        vertex.x += g_dynamicCaptureCameraX;
        vertex.y += g_dynamicCaptureCameraY;
        vertex.z += g_dynamicCaptureCameraZ;
        vertex.materialId = 0.0f;
        g_particleFrameVertices[oldSize + static_cast<size_t>(index)] = vertex;
    }
}

extern "C"
JNIEXPORT void JNICALL Java_com_example_vulkan_NativeVulkan_endParticleFrame(
    JNIEnv*,
    jclass
)
{
    std::lock_guard<std::mutex> lock(g_chunkMutex);
    if(!g_particleFrameOpen)
    {
        return;
    }
    g_particleFrameOpen = false;

    const auto current = g_chunkMeshes.find(PARTICLE_RT_LAYER_KEY);
    const bool changed = current == g_chunkMeshes.end()
        ? !g_particleFrameVertices.empty()
        : !sameVertexPayload(current->second, g_particleFrameVertices);

    const bool particleAtlasesReady =
        g_particleTextureViews[0] != VK_NULL_HANDLE &&
        g_particleTextureViews[1] != VK_NULL_HANDLE &&
        g_particleTextureViews[2] != VK_NULL_HANDLE &&
        g_particleTextureSamplers[0] != VK_NULL_HANDLE &&
        g_particleTextureSamplers[1] != VK_NULL_HANDLE &&
        g_particleTextureSamplers[2] != VK_NULL_HANDLE;

    if(!g_particleFrameVertices.empty() && !g_particleDebugEpisodeActive)
    {
        ++g_particleDebugEpisodeSerial;
        std::stringstream log;
        log
            << "[RT PARTICLES P1]"
            << " episode=" << g_particleDebugEpisodeSerial
            << " phase=start"
            << " vertices=" << g_particleFrameVertices.size()
            << " quads=" << (g_particleFrameVertices.size() / 6u)
            << " triangles=" << (g_particleFrameVertices.size() / 3u)
            << " binding37Ready=" << (particleAtlasesReady ? 1 : 0)
            << " queuedTLAS=" << (changed ? 1 : 0);
        RTLog(log.str());
        g_particleDebugEpisodeActive = true;
    }
    else if(g_particleFrameVertices.empty() && g_particleDebugEpisodeActive)
    {
        std::stringstream log;
        log
            << "[RT PARTICLES P1]"
            << " episode=" << g_particleDebugEpisodeSerial
            << " phase=end"
            << " binding37Ready=" << (particleAtlasesReady ? 1 : 0)
            << " queuedTLAS=" << (changed ? 1 : 0);
        RTLog(log.str());
        g_particleDebugEpisodeActive = false;
    }

    if(changed)
    {
        if(g_particleFrameVertices.empty())
        {
            g_chunkMeshes.erase(PARTICLE_RT_LAYER_KEY);
        }
        else
        {
            std::vector<Vertex>& cached =
                g_chunkMeshes[PARTICLE_RT_LAYER_KEY];
            cached.swap(g_particleFrameVertices);
            g_particleFrameVertices.clear();
        }
    }

    if(requestDynamicRTASRebuild(changed))
    {
        g_dirtySectionKeys.insert(ENTITY_RT_SPATIAL_KEY);
        markWorldGeometryDirty();
        g_entityRebuildRequested.store(true, std::memory_order_release);
    }
}

extern "C"
JNIEXPORT void JNICALL Java_com_example_vulkan_NativeVulkan_updateParticleTexture(
    JNIEnv*,
    jclass,
    jint textureSlot,
    jlong imageView,
    jlong sampler
)
{
    if(textureSlot < 1 || textureSlot > static_cast<jint>(PARTICLE_RT_TEXTURE_SLOT_COUNT) ||
       imageView == 0 || sampler == 0)
    {
        return;
    }
    const uint32_t slot = static_cast<uint32_t>(textureSlot - 1);
    const VkImageView newView = reinterpret_cast<VkImageView>(imageView);
    const VkSampler newSampler = reinterpret_cast<VkSampler>(sampler);
    if(g_particleTextureViews[slot] != newView || g_particleTextureSamplers[slot] != newSampler)
    {
        g_particleTextureViews[slot] = newView;
        g_particleTextureSamplers[slot] = newSampler;
        g_particleTexturesDirty = true;
    }
}

extern "C"
JNIEXPORT void JNICALL Java_com_example_vulkan_NativeVulkan_updateRainStreakTexture(
    JNIEnv*,
    jclass,
    jlong imageView,
    jlong sampler
)
{
    if(imageView == 0 || sampler == 0)
    {
        return;
    }
    const VkImageView newView = reinterpret_cast<VkImageView>(imageView);
    const VkSampler newSampler = reinterpret_cast<VkSampler>(sampler);
    if(g_rainStreakTextureView != newView || g_rainStreakTextureSampler != newSampler)
    {
        g_rainStreakTextureView = newView;
        g_rainStreakTextureSampler = newSampler;
        g_rainStreakTextureDirty = true;
    }
}
// =====================================================
// FG-4.1
// Java -> Native backbuffer format
// =====================================================

extern "C"
JNIEXPORT void JNICALL Java_com_example_vulkan_NativeVulkan_setFGBackbufferFormat(
    JNIEnv*,
    jclass,
    jint vkFormat
)
{
    const VkFormat newFormat =
        static_cast<VkFormat>(
            vkFormat
        );


    if(
        newFormat ==
        VK_FORMAT_UNDEFINED
    )
    {
        fprintf(
            stderr,
            "[FG-4.1 FORMAT] invalid=0\n"
        );

        fflush(stderr);

        return;
    }


    if(
        g_fgBackbufferFormat !=
        newFormat
    )
    {
        g_fgBackbufferFormat =
            newFormat;


        fprintf(
            stderr,
            "[FG-4.1 FORMAT]"
            " backbufferFormat=%d\n",

            static_cast<int>(
                g_fgBackbufferFormat
            )
        );

        fflush(stderr);
    }
}


// =====================================================
// FG-4.1
// Capture Final + build binary UI Alpha
//
// Final and HUD-less are both converted from the same
// Minecraft main target into the same backbuffer format.
// =====================================================

extern "C"
JNIEXPORT void JNICALL Java_com_example_vulkan_NativeVulkan_captureFGFinalAndBuildUIAlpha(
    JNIEnv*,
    jclass,
    jlong commandBufferHandle,
    jlong finalColorImageHandle,
    jint width,
    jint height
)
{
    if(
        commandBufferHandle == 0 ||

        finalColorImageHandle == 0 ||

        width <= 0 ||

        height <= 0
    )
    {
        return;
    }


    if(
        !g_currentSLFrameValid ||

        g_fgHudlessFrameIndex !=
            g_currentSLFrameIndex
    )
    {
        return;
    }


    const uint32_t finalWidth =
        static_cast<uint32_t>(
            width
        );

    const uint32_t finalHeight =
        static_cast<uint32_t>(
            height
        );


    if(
        finalWidth !=
            g_fgUIWidth ||

        finalHeight !=
            g_fgUIHeight ||

        g_fgHudlessImage ==
            VK_NULL_HANDLE ||

        g_fgFinalImage ==
            VK_NULL_HANDLE ||

        g_fgUIAlphaImage ==
            VK_NULL_HANDLE ||

        g_fgUIAlphaPipeline ==
            VK_NULL_HANDLE ||

        g_fgUIAlphaPipelineLayout ==
            VK_NULL_HANDLE ||

        g_fgUIAlphaDescriptorSet ==
            VK_NULL_HANDLE
    )
    {
        return;
    }


    VkCommandBuffer cmd =
        reinterpret_cast<VkCommandBuffer>(
            static_cast<uintptr_t>(
                commandBufferHandle
            )
        );


    VkImage finalColorImage =
        reinterpret_cast<VkImage>(
            static_cast<uintptr_t>(
                finalColorImageHandle
            )
        );


    // -------------------------------------------------
    // Final snapshot:
    // old sampled/undefined -> transfer destination
    // -------------------------------------------------

    VkImageMemoryBarrier
        finalToTransfer{};

    finalToTransfer.sType =
        VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;

    finalToTransfer.oldLayout =
        g_fgFinalLayout;

    finalToTransfer.newLayout =
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;

    finalToTransfer.srcQueueFamilyIndex =
        VK_QUEUE_FAMILY_IGNORED;

    finalToTransfer.dstQueueFamilyIndex =
        VK_QUEUE_FAMILY_IGNORED;

    finalToTransfer.srcAccessMask =
        g_fgFinalLayout ==
            VK_IMAGE_LAYOUT_UNDEFINED
                ? 0
                : VK_ACCESS_SHADER_READ_BIT;

    finalToTransfer.dstAccessMask =
        VK_ACCESS_TRANSFER_WRITE_BIT;

    finalToTransfer.image =
        g_fgFinalImage;

    finalToTransfer.subresourceRange.aspectMask =
        VK_IMAGE_ASPECT_COLOR_BIT;

    finalToTransfer.subresourceRange.baseMipLevel =
        0;

    finalToTransfer.subresourceRange.levelCount =
        1;

    finalToTransfer.subresourceRange.baseArrayLayer =
        0;

    finalToTransfer.subresourceRange.layerCount =
        1;


    vkCmdPipelineBarrier(
        cmd,

        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT,

        0,

        0,
        nullptr,

        0,
        nullptr,

        1,
        &finalToTransfer
    );


    g_fgFinalLayout =
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;


    // -------------------------------------------------
    // Main target -> Final snapshot
    //
    // Match Minecraft's final Y flip.
    // -------------------------------------------------

    VkImageBlit finalBlit{};


    finalBlit.srcSubresource.aspectMask =
        VK_IMAGE_ASPECT_COLOR_BIT;

    finalBlit.srcSubresource.mipLevel =
        0;

    finalBlit.srcSubresource.baseArrayLayer =
        0;

    finalBlit.srcSubresource.layerCount =
        1;


    finalBlit.srcOffsets[0] =
    {
        0,
        0,
        0
    };

    finalBlit.srcOffsets[1] =
    {
        static_cast<int32_t>(
            finalWidth
        ),

        static_cast<int32_t>(
            finalHeight
        ),

        1
    };


    finalBlit.dstSubresource.aspectMask =
        VK_IMAGE_ASPECT_COLOR_BIT;

    finalBlit.dstSubresource.mipLevel =
        0;

    finalBlit.dstSubresource.baseArrayLayer =
        0;

    finalBlit.dstSubresource.layerCount =
        1;


    finalBlit.dstOffsets[0] =
    {
        0,

        static_cast<int32_t>(
            finalHeight
        ),

        0
    };

    finalBlit.dstOffsets[1] =
    {
        static_cast<int32_t>(
            finalWidth
        ),

        0,

        1
    };


    vkCmdBlitImage(
        cmd,

        finalColorImage,
        VK_IMAGE_LAYOUT_GENERAL,

        g_fgFinalImage,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,

        1,
        &finalBlit,

        VK_FILTER_NEAREST
    );


    // -------------------------------------------------
    // Prepare:
    //
    // Hudless -> compute read
    // Final   -> compute read
    // UIAlpha -> compute write
    // -------------------------------------------------

    VkImageMemoryBarrier
        computeBarriers[3]{};


    computeBarriers[0].sType =
        VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;

    computeBarriers[0].oldLayout =
        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

    computeBarriers[0].newLayout =
        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

    computeBarriers[0].srcQueueFamilyIndex =
        VK_QUEUE_FAMILY_IGNORED;

    computeBarriers[0].dstQueueFamilyIndex =
        VK_QUEUE_FAMILY_IGNORED;

    computeBarriers[0].srcAccessMask =
        VK_ACCESS_TRANSFER_WRITE_BIT;

    computeBarriers[0].dstAccessMask =
        VK_ACCESS_SHADER_READ_BIT;

    computeBarriers[0].image =
        g_fgHudlessImage;

    computeBarriers[0].subresourceRange.aspectMask =
        VK_IMAGE_ASPECT_COLOR_BIT;

    computeBarriers[0].subresourceRange.baseMipLevel =
        0;

    computeBarriers[0].subresourceRange.levelCount =
        1;

    computeBarriers[0].subresourceRange.baseArrayLayer =
        0;

    computeBarriers[0].subresourceRange.layerCount =
        1;


    computeBarriers[1].sType =
        VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;

    computeBarriers[1].oldLayout =
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;

    computeBarriers[1].newLayout =
        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

    computeBarriers[1].srcQueueFamilyIndex =
        VK_QUEUE_FAMILY_IGNORED;

    computeBarriers[1].dstQueueFamilyIndex =
        VK_QUEUE_FAMILY_IGNORED;

    computeBarriers[1].srcAccessMask =
        VK_ACCESS_TRANSFER_WRITE_BIT;

    computeBarriers[1].dstAccessMask =
        VK_ACCESS_SHADER_READ_BIT;

    computeBarriers[1].image =
        g_fgFinalImage;

    computeBarriers[1].subresourceRange.aspectMask =
        VK_IMAGE_ASPECT_COLOR_BIT;

    computeBarriers[1].subresourceRange.baseMipLevel =
        0;

    computeBarriers[1].subresourceRange.levelCount =
        1;

    computeBarriers[1].subresourceRange.baseArrayLayer =
        0;

    computeBarriers[1].subresourceRange.layerCount =
        1;


    computeBarriers[2].sType =
        VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;

    computeBarriers[2].oldLayout =
        g_fgUIAlphaLayout;

    computeBarriers[2].newLayout =
        VK_IMAGE_LAYOUT_GENERAL;

    computeBarriers[2].srcQueueFamilyIndex =
        VK_QUEUE_FAMILY_IGNORED;

    computeBarriers[2].dstQueueFamilyIndex =
        VK_QUEUE_FAMILY_IGNORED;

    computeBarriers[2].srcAccessMask =
        g_fgUIAlphaLayout ==
            VK_IMAGE_LAYOUT_UNDEFINED
                ? 0
                : VK_ACCESS_SHADER_WRITE_BIT;

    computeBarriers[2].dstAccessMask =
        VK_ACCESS_SHADER_WRITE_BIT;

    computeBarriers[2].image =
        g_fgUIAlphaImage;

    computeBarriers[2].subresourceRange.aspectMask =
        VK_IMAGE_ASPECT_COLOR_BIT;

    computeBarriers[2].subresourceRange.baseMipLevel =
        0;

    computeBarriers[2].subresourceRange.levelCount =
        1;

    computeBarriers[2].subresourceRange.baseArrayLayer =
        0;

    computeBarriers[2].subresourceRange.layerCount =
        1;


    vkCmdPipelineBarrier(
        cmd,

        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,

        0,

        0,
        nullptr,

        0,
        nullptr,

        3,
        computeBarriers
    );


    g_fgFinalLayout =
        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

    g_fgUIAlphaLayout =
        VK_IMAGE_LAYOUT_GENERAL;


    // -------------------------------------------------
    // Binary RGB comparison
    // -------------------------------------------------

    vkCmdBindPipeline(
        cmd,

        VK_PIPELINE_BIND_POINT_COMPUTE,

        g_fgUIAlphaPipeline
    );


    vkCmdBindDescriptorSets(
        cmd,

        VK_PIPELINE_BIND_POINT_COMPUTE,

        g_fgUIAlphaPipelineLayout,

        0,
        1,
        &g_fgUIAlphaDescriptorSet,

        0,
        nullptr
    );


    vkCmdDispatch(
        cmd,

        (
            finalWidth +
            7
        ) / 8,

        (
            finalHeight +
            7
        ) / 8,

        1
    );


    // -------------------------------------------------
    // Make UI Alpha writes visible to future SL reads.
    // No Streamline tag is added in FG-4.1.
    // -------------------------------------------------

    VkImageMemoryBarrier
        alphaReady{};

    alphaReady.sType =
        VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;

    alphaReady.oldLayout =
        VK_IMAGE_LAYOUT_GENERAL;

    alphaReady.newLayout =
        VK_IMAGE_LAYOUT_GENERAL;

    alphaReady.srcQueueFamilyIndex =
        VK_QUEUE_FAMILY_IGNORED;

    alphaReady.dstQueueFamilyIndex =
        VK_QUEUE_FAMILY_IGNORED;

    alphaReady.srcAccessMask =
        VK_ACCESS_SHADER_WRITE_BIT;

    alphaReady.dstAccessMask =
        VK_ACCESS_MEMORY_READ_BIT;

    alphaReady.image =
        g_fgUIAlphaImage;

    alphaReady.subresourceRange.aspectMask =
        VK_IMAGE_ASPECT_COLOR_BIT;

    alphaReady.subresourceRange.baseMipLevel =
        0;

    alphaReady.subresourceRange.levelCount =
        1;

    alphaReady.subresourceRange.baseArrayLayer =
        0;

    alphaReady.subresourceRange.layerCount =
        1;


    vkCmdPipelineBarrier(
        cmd,

        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,

        0,

        0,
        nullptr,

        0,
        nullptr,

        1,
        &alphaReady
    );


    g_fgUIAlphaFrameIndex =
        g_currentSLFrameIndex;
    // =====================================================
// FG-4.2B
// Frame Generation required resource tags
// =====================================================

if(
    g_slSetTagForFrame &&
    g_currentSLFrameToken != nullptr &&
    g_fgHudlessFrameIndex == g_currentSLFrameIndex &&
    g_fgUIAlphaFrameIndex == g_currentSLFrameIndex
)
{
    // -------------------------------------------------
    // Depth
    // -------------------------------------------------

    sl::Resource fgDepth(
        sl::ResourceType::eTex2d,
        (void*)g_dlssDepthImage,
        (void*)g_dlssDepthMemory,
        (void*)g_dlssDepthView,
        VK_IMAGE_LAYOUT_GENERAL
    );

    fgDepth.width =
        g_dlssDepthWidth;

    fgDepth.height =
        g_dlssDepthHeight;

    fgDepth.nativeFormat =
        static_cast<uint32_t>(
            VK_FORMAT_R32_SFLOAT
        );

    fgDepth.mipLevels = 1;
    fgDepth.arrayLayers = 1;
    fgDepth.flags = 0;

    fgDepth.usage =
        VK_IMAGE_USAGE_STORAGE_BIT |
        VK_IMAGE_USAGE_SAMPLED_BIT |
        VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
        VK_IMAGE_USAGE_TRANSFER_DST_BIT;


    // -------------------------------------------------
    // Motion
    // -------------------------------------------------

    sl::Resource fgMotion(
        sl::ResourceType::eTex2d,
        (void*)g_motionImage,
        (void*)g_motionMemory,
        (void*)g_motionView,
        VK_IMAGE_LAYOUT_GENERAL
    );

    fgMotion.width =
        g_motionWidth;

    fgMotion.height =
        g_motionHeight;

    fgMotion.nativeFormat =
        static_cast<uint32_t>(
            VK_FORMAT_R16G16_SFLOAT
        );

    fgMotion.mipLevels = 1;
    fgMotion.arrayLayers = 1;
    fgMotion.flags = 0;

    fgMotion.usage =
        VK_IMAGE_USAGE_STORAGE_BIT |
        VK_IMAGE_USAGE_SAMPLED_BIT |
        VK_IMAGE_USAGE_TRANSFER_DST_BIT;


    // -------------------------------------------------
    // HUD-less
    // -------------------------------------------------

    sl::Resource fgHudless(
        sl::ResourceType::eTex2d,
        (void*)g_fgHudlessImage,
        (void*)g_fgHudlessMemory,
        (void*)g_fgHudlessView,
        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
    );

    fgHudless.width =
        g_fgUIWidth;

    fgHudless.height =
        g_fgUIHeight;

    fgHudless.nativeFormat =
        static_cast<uint32_t>(
            g_fgSnapshotFormat
        );

    fgHudless.mipLevels = 1;
    fgHudless.arrayLayers = 1;
    fgHudless.flags = 0;

    fgHudless.usage =
        VK_IMAGE_USAGE_TRANSFER_DST_BIT |
        VK_IMAGE_USAGE_SAMPLED_BIT;


    // -------------------------------------------------
    // UI Alpha
    // -------------------------------------------------

    sl::Resource fgUIAlpha(
        sl::ResourceType::eTex2d,
        (void*)g_fgUIAlphaImage,
        (void*)g_fgUIAlphaMemory,
        (void*)g_fgUIAlphaView,
        VK_IMAGE_LAYOUT_GENERAL
    );

    fgUIAlpha.width =
        g_fgUIWidth;

    fgUIAlpha.height =
        g_fgUIHeight;

    fgUIAlpha.nativeFormat =
        static_cast<uint32_t>(
            VK_FORMAT_R32_SFLOAT
        );

    fgUIAlpha.mipLevels = 1;
    fgUIAlpha.arrayLayers = 1;
    fgUIAlpha.flags = 0;

    fgUIAlpha.usage =
        VK_IMAGE_USAGE_STORAGE_BIT |
        VK_IMAGE_USAGE_SAMPLED_BIT;


    // -------------------------------------------------
    // Extents
    // -------------------------------------------------

    sl::Extent renderExtent{};

    renderExtent.left = 0;
    renderExtent.top = 0;
    renderExtent.width =
        g_dlssDepthWidth;
    renderExtent.height =
        g_dlssDepthHeight;


    sl::Extent finalExtent{};

    finalExtent.left = 0;
    finalExtent.top = 0;
    finalExtent.width =
        g_fgUIWidth;
    finalExtent.height =
        g_fgUIHeight;


    // -------------------------------------------------
    // Tags
    // -------------------------------------------------

    sl::ResourceTag depthTag(
        &fgDepth,
        sl::kBufferTypeDepth,
        sl::ResourceLifecycle::eValidUntilPresent,
        &renderExtent
    );

    sl::ResourceTag motionTag(
        &fgMotion,
        sl::kBufferTypeMotionVectors,
        sl::ResourceLifecycle::eValidUntilPresent,
        &renderExtent
    );

    sl::ResourceTag hudlessTag(
        &fgHudless,
        sl::kBufferTypeHUDLessColor,
        sl::ResourceLifecycle::eValidUntilPresent,
        &finalExtent
    );

    sl::ResourceTag uiAlphaTag(
        &fgUIAlpha,
        sl::kBufferTypeUIAlpha,
        sl::ResourceLifecycle::eValidUntilPresent,
        &finalExtent
    );


    sl::ResourceTag fgTags[] =
    {
        depthTag,
        motionTag,
        hudlessTag,
        uiAlphaTag
    };


    sl::Result tagResult =
        g_slSetTagForFrame(
            *g_currentSLFrameToken,
            g_dlssViewport,
            fgTags,
            4,
            nullptr
        );


    static bool printed =
        false;

    if(!printed ||
       tagResult != sl::Result::eOk)
    {
        fprintf(
            stderr,
            "[FG-4.2 TAG]"
            " frame=%u"
            " result=%d"
            " depth=%ux%u"
            " motion=%ux%u"
            " hudless=%ux%u"
            " uiAlpha=%ux%u\n",

            g_currentSLFrameIndex,
            static_cast<int>(tagResult),

            g_dlssDepthWidth,
            g_dlssDepthHeight,

            g_motionWidth,
            g_motionHeight,

            g_fgUIWidth,
            g_fgUIHeight,

            g_fgUIWidth,
            g_fgUIHeight
        );

        fflush(stderr);

        printed = true;
    }
}


    static uint32_t
        lastPrintedWidth =
            0;

    static uint32_t
        lastPrintedHeight =
            0;


    if(
        lastPrintedWidth !=
            finalWidth ||

        lastPrintedHeight !=
            finalHeight
    )
    {
        fprintf(
            stderr,
            "[FG-4.1 UI ALPHA]"
            " frame=%u"
            " size=%ux%u"
            " binaryRGBCompare=1\n",

            g_currentSLFrameIndex,

            finalWidth,
            finalHeight
        );

        fflush(stderr);


        lastPrintedWidth =
            finalWidth;

        lastPrintedHeight =
            finalHeight;
    }

}
extern "C"
JNIEXPORT void JNICALL Java_com_example_vulkan_NativeVulkan_recordFrameCommand(
    JNIEnv* env,
    jclass clazz,
    jlong commandBufferHandle,
    jlong targetImageHandle,
    jint width,
    jint height,
    jboolean targetIsMainRenderTarget
)
{
    // Publish all pending atlas descriptors after a single lifecycle-safe
    // device-idle barrier. PBR and albedo updates often arrive together during
    // resource reload; the previous split paths drained the entire device twice.
    const bool publishPbrAtlases =
        g_pbrAtlasesDirty &&
        g_normalAtlasView != VK_NULL_HANDLE &&
        g_specularAtlasView != VK_NULL_HANDLE &&
        g_atlasSampler != VK_NULL_HANDLE;
    const bool publishAlbedoAtlas =
        g_atlasDirty &&
        g_atlasImageView != VK_NULL_HANDLE &&
        g_atlasSampler != VK_NULL_HANDLE;

    const bool dynamicTextureBindingsBlocked =
        g_dynamicTextureBindingsNeedAtlasRefresh &&
        !g_dynamicTextureBindingsCanPublish;
    const bool publishEntityTextures =
        (g_entityTexturesDirty ||
         (g_dynamicTextureBindingsNeedAtlasRefresh &&
          g_dynamicTextureBindingsCanPublish)) &&
        !dynamicTextureBindingsBlocked &&
        g_atlasImageView != VK_NULL_HANDLE &&
        g_atlasSampler != VK_NULL_HANDLE;
    const bool publishParticleTextures =
        (g_particleTexturesDirty ||
         (g_dynamicTextureBindingsNeedAtlasRefresh &&
          g_dynamicTextureBindingsCanPublish)) &&
        !dynamicTextureBindingsBlocked &&
        g_atlasImageView != VK_NULL_HANDLE &&
        g_atlasSampler != VK_NULL_HANDLE;
    const bool publishRainStreakTexture =
        (g_rainStreakTextureDirty ||
         (g_dynamicTextureBindingsNeedAtlasRefresh &&
          g_dynamicTextureBindingsCanPublish)) &&
        !dynamicTextureBindingsBlocked &&
        g_atlasImageView != VK_NULL_HANDLE &&
        g_atlasSampler != VK_NULL_HANDLE;

    if(publishPbrAtlases || publishAlbedoAtlas || publishEntityTextures ||
       publishParticleTextures || publishRainStreakTexture)
    {
        // The active descriptor set may still be referenced by submitted work.
        // Keep the existing safety contract while coalescing all dirty bindings.
        FGDeviceWaitIdle(g_device);

        VkDescriptorImageInfo normalInfo{};
        VkDescriptorImageInfo specularInfo{};
        VkDescriptorImageInfo atlasInfo{};
        VkDescriptorImageInfo entityInfos[ENTITY_RT_TEXTURE_SLOT_COUNT]{};
        VkDescriptorImageInfo particleInfos[PARTICLE_RT_TEXTURE_SLOT_COUNT]{};
        VkDescriptorImageInfo rainStreakInfo{};
        VkWriteDescriptorSet writes[6]{};
        uint32_t writeCount = 0u;

        if(publishAlbedoAtlas)
        {
            atlasInfo.imageView = g_atlasImageView;
            atlasInfo.sampler = g_atlasSampler;
            atlasInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            VkWriteDescriptorSet& write = writes[writeCount++];
            write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            write.dstSet = g_rayTracingDescriptorSet;
            write.dstBinding = 3;
            write.descriptorCount = 1;
            write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            write.pImageInfo = &atlasInfo;
        }

        if(publishPbrAtlases)
        {
            normalInfo.imageView = g_normalAtlasView;
            normalInfo.sampler = g_atlasSampler;
            normalInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            VkWriteDescriptorSet& normalWrite = writes[writeCount++];
            normalWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            normalWrite.dstSet = g_rayTracingDescriptorSet;
            normalWrite.dstBinding = 4;
            normalWrite.descriptorCount = 1;
            normalWrite.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            normalWrite.pImageInfo = &normalInfo;

            specularInfo.imageView = g_specularAtlasView;
            specularInfo.sampler = g_atlasSampler;
            specularInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            VkWriteDescriptorSet& specularWrite = writes[writeCount++];
            specularWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            specularWrite.dstSet = g_rayTracingDescriptorSet;
            specularWrite.dstBinding = 5;
            specularWrite.descriptorCount = 1;
            specularWrite.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            specularWrite.pImageInfo = &specularInfo;
        }

        if(publishEntityTextures)
        {
            for(uint32_t slot = 0u;
                slot < ENTITY_RT_TEXTURE_SLOT_COUNT;
                ++slot)
            {
                entityInfos[slot].imageView =
                    g_entityTextureViews[slot] != VK_NULL_HANDLE
                        ? g_entityTextureViews[slot]
                        : g_atlasImageView;
                entityInfos[slot].sampler =
                    g_entityTextureSamplers[slot] != VK_NULL_HANDLE
                        ? g_entityTextureSamplers[slot]
                        : g_atlasSampler;
                entityInfos[slot].imageLayout =
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            }

            VkWriteDescriptorSet& entityWrite = writes[writeCount++];
            entityWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            entityWrite.dstSet = g_rayTracingDescriptorSet;
            entityWrite.dstBinding = 36;
            entityWrite.descriptorCount = ENTITY_RT_TEXTURE_SLOT_COUNT;
            entityWrite.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            entityWrite.pImageInfo = entityInfos;
        }

        if(publishParticleTextures)
        {
            for(uint32_t slot = 0u; slot < PARTICLE_RT_TEXTURE_SLOT_COUNT; ++slot)
            {
                particleInfos[slot].imageView = g_particleTextureViews[slot] != VK_NULL_HANDLE
                    ? g_particleTextureViews[slot] : g_atlasImageView;
                particleInfos[slot].sampler = g_particleTextureSamplers[slot] != VK_NULL_HANDLE
                    ? g_particleTextureSamplers[slot] : g_atlasSampler;
                particleInfos[slot].imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            }
            VkWriteDescriptorSet& particleWrite = writes[writeCount++];
            particleWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            particleWrite.dstSet = g_rayTracingDescriptorSet;
            particleWrite.dstBinding = 37;
            particleWrite.descriptorCount = PARTICLE_RT_TEXTURE_SLOT_COUNT;
            particleWrite.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            particleWrite.pImageInfo = particleInfos;
        }

        if(publishRainStreakTexture)
        {
            rainStreakInfo.imageView =
                g_rainStreakTextureView != VK_NULL_HANDLE
                    ? g_rainStreakTextureView
                    : g_atlasImageView;
            rainStreakInfo.sampler =
                g_rainStreakTextureSampler != VK_NULL_HANDLE
                    ? g_rainStreakTextureSampler
                    : g_atlasSampler;
            rainStreakInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            VkWriteDescriptorSet& rainStreakWrite = writes[writeCount++];
            rainStreakWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            rainStreakWrite.dstSet = g_rayTracingDescriptorSet;
            rainStreakWrite.dstBinding = 40;
            rainStreakWrite.descriptorCount = 1;
            rainStreakWrite.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            rainStreakWrite.pImageInfo = &rainStreakInfo;
        }

        vkUpdateDescriptorSets(g_device, writeCount, writes, 0, nullptr);
        if(publishAlbedoAtlas)
        {
            g_atlasDirty = false;
        }
        if(publishPbrAtlases)
        {
            g_pbrAtlasesDirty = false;
        }
        if(publishEntityTextures)
        {
            g_entityTexturesDirty = false;
        }
        if(publishParticleTextures)
        {
            g_particleTexturesDirty = false;
        }
        if(publishRainStreakTexture)
        {
            g_rainStreakTextureDirty = false;
        }

        if(publishEntityTextures && publishParticleTextures && publishRainStreakTexture)
        {
            g_dynamicTextureBindingsNeedAtlasRefresh = false;
            g_dynamicTextureBindingsCanPublish = false;
        }

        RTLog("[RT DESCRIPTOR] Published pending atlas descriptor bindings.");
    }
    if (!g_isInWorld)
    {
        return;
    }


    ++g_perfL10FrameSerial;

    // VLI owns local-light updates. Bindings 17/18 remain permanent empty
    // compatibility sentinels, so a Section change must never rebuild them
    // or call vkQueueWaitIdle().
    g_localLightsDirty = false;

    if(g_worldGeometryDirty.load(std::memory_order_acquire))
    {
        bool dynamicRebuildPerformed = false;
        // Dynamic entity/particle payloads are updated frequently. Keep their
        // latest CPU payload immediately, but rate-limit the expensive AS
        // rebuild to the same bounded interval used for pending sections.
        if(g_entityRebuildRequested.load(std::memory_order_acquire))
        {
            if(g_nextSectionRebuildFrame == 0u)
            {
                g_nextSectionRebuildFrame =
                    g_perfL10FrameSerial + SECTION_REBUILD_MIN_FRAME_INTERVAL;
            }

            if(g_perfL10FrameSerial >= g_nextSectionRebuildFrame)
            {
                g_entityRebuildRequested.store(false, std::memory_order_release);
                rebuildWorldGeometry(SECTION_REBUILD_MAX_SECTIONS_PER_BATCH);
                dynamicRebuildPerformed = true;
                if(g_worldGeometryDirty.load(std::memory_order_acquire))
                {
                    g_nextSectionRebuildFrame =
                        g_perfL10FrameSerial + SECTION_REBUILD_MIN_FRAME_INTERVAL;
                }
                else
                {
                    g_nextSectionRebuildFrame = 0u;
                }
            }
        }

        if(!g_entityRebuildRequested.load(std::memory_order_acquire) &&
           !dynamicRebuildPerformed)
        {
        // Chunk streaming produces a long chain of small JNI uploads. Rather
        // than repeatedly draining the RT queue, wait for a short quiet window
        // and rebuild a bounded Section batch. A hard deadline guarantees that
        // continuous streaming still makes visible forward progress.
        const uint64_t revision =
            g_worldGeometryRevision.load(std::memory_order_acquire);
        if(revision != g_lastObservedSectionRevision)
        {
            g_lastObservedSectionRevision = revision;
            if(g_firstPendingSectionFrame == 0u)
            {
                g_firstPendingSectionFrame = g_perfL10FrameSerial;
            }

            const uint64_t quietDeadline =
                g_perfL10FrameSerial + SECTION_REBUILD_QUIET_FRAMES;
            const uint64_t hardDeadline =
                g_firstPendingSectionFrame +
                SECTION_REBUILD_MAX_DEFERRAL_FRAMES;
            g_nextSectionRebuildFrame = std::min(
                quietDeadline,
                hardDeadline
            );
        }

        const bool firstWorldBatch = !g_asBuilt;
        const bool quietWindowElapsed =
            g_perfL10FrameSerial >= g_nextSectionRebuildFrame;
        const bool hardDeadlineElapsed =
            g_firstPendingSectionFrame != 0u &&
            g_perfL10FrameSerial >=
                g_firstPendingSectionFrame +
                SECTION_REBUILD_MAX_DEFERRAL_FRAMES;
        if(firstWorldBatch || quietWindowElapsed || hardDeadlineElapsed)
        {
            rebuildWorldGeometry(SECTION_REBUILD_MAX_SECTIONS_PER_BATCH);
            if(g_worldGeometryDirty.load(std::memory_order_acquire))
            {
                g_nextSectionRebuildFrame =
                    g_perfL10FrameSerial +
                    SECTION_REBUILD_MIN_FRAME_INTERVAL;
            }
            else
            {
                g_firstPendingSectionFrame = 0u;
                g_nextSectionRebuildFrame = 0u;
            }
        }
        }
    }
    else
    {
        g_firstPendingSectionFrame = 0u;
        g_nextSectionRebuildFrame = 0u;
    }

    VkCommandBuffer cmd =
        reinterpret_cast<VkCommandBuffer>(
            commandBufferHandle
        );


    VkImage target =
        reinterpret_cast<VkImage>(
            targetImageHandle
        );


    const bool targetIsMain =
        targetIsMainRenderTarget == JNI_TRUE;

    // === 核心修改：只拦截 cmd，允许 swap 为 NULL ===
    if (cmd == VK_NULL_HANDLE ||
        target == VK_NULL_HANDLE)
    {
        return;
    }


    // Record the event-driven voxel upload/solve before any RayGen read.
    recordVLIUpdates(cmd);

// =====================================================
// DLSS dynamic resolution
//
// width / height = 当前真实 swapchain 最终输出尺寸
// renderWidth / renderHeight = DLSS 建议的内部渲染尺寸
// =====================================================

if(width <= 0 || height <= 0)
{
    std::cerr
        << "[DLSS SIZE] invalid swapchain size "
        << width
        << "x"
        << height
        << std::endl;

    return;
}


const uint32_t outputWidth =
    static_cast<uint32_t>(width);

    const uint32_t outputHeight =
        static_cast<uint32_t>(height);


    // =====================================================
    // P1-1 - preserve Minecraft raster BEFORE native RT/RR
    // overwrites mainTarget.
    // =====================================================
    if(targetIsMain)
    {
        recordFarRasterSnapshot(
            cmd,
            target,
            outputWidth,
            outputHeight
        );
    }


    // =====================================================
    // RR-3.1 BUILD PROBE
    // =====================================================

    static bool rr31BuildProbePrinted =
        false;

    if(!rr31BuildProbePrinted)
    {
        fprintf(
            stderr,
            "[RR-3.1 BUILD PROBE] NEW DLL ACTIVE output=%ux%u\n",
            outputWidth,
            outputHeight
        );

        fflush(stderr);

        RTLog(
            "[RR-3.1 BUILD PROBE] NEW DLL ACTIVE"
        );

        rr31BuildProbePrinted =
            true;
    }


    const bool requestedSR = g_upscalerMode.load() == 1;
    const bool useSR = requestedSR && g_dlssReady &&
        g_slDLSSGetOptimalSettings && g_slDLSSSetOptions;
    if((useSR && (!g_dlssReady || !g_slDLSSGetOptimalSettings || !g_slDLSSSetOptions)) ||
       (!useSR && (!g_dlssRRReady || !g_slDLSSDGetOptimalSettings || !g_slDLSSDSetOptions)))
    {
        std::cerr
            << "[RR-5.2] RR functions unavailable"
            << std::endl;

        return;
    }

    // ReSTIR DI Block B2 -> DLSS RR handoff. historyPadding0 remains the
    // existing ABI-compatible mode marker (1 = RR, 0 = SR). The final ReSTIR
    // RayGen now writes only raw post-visibility local-DI into Binding 41. A
    // same-frame 5/9/13-tap edge-aware compute reconstruction then composites
    // the stable local-DI result into g_rtOutputImage before RR/SR consumes it.
    // No candidate, reservoir tap, visibility ray, or GI ray is added.
    if(g_cameraUboMapped != nullptr)
    {
        CameraUBO* cameraUbo =
            static_cast<CameraUBO*>(g_cameraUboMapped);
        cameraUbo->historyPadding0 = useSR ? 0u : 1u;
    }

    // Both RR and SR now consume the same local-DI history. Switching upscaler
    // mode therefore does not invalidate it; per-receiver revision checks own
    // scene-change rejection.
    static int lastLocalDIDenoiserMode = -1;
    const int localDIDenoiserMode = useSR ? 0 : 1;
    if(lastLocalDIDenoiserMode != localDIDenoiserMode)
    {
        RTLog(
            useSR
                ? "[ReSTIR DI] B2 post-visibility reconstruction=adaptive 5/9/13tap current-frame + temporal -> DLSS_SR"
                : "[ReSTIR DI] B2 post-visibility reconstruction=adaptive 5/9/13tap current-frame + temporal -> DLSS_RR"
        );
        lastLocalDIDenoiserMode = localDIDenoiserMode;
    }


sl::DLSSDOptions rrOptions{};
sl::DLSSOptions srOptions{};


// RR uses the same Performance / Quality mode
// as the existing DLSS-SR path.
    const int rrMode = g_rrMode.load();
    switch(rrMode)
    {
        case 0:
        {
            rrOptions.mode =
                sl::DLSSMode::eMaxPerformance;

            break;
        }

        case 1:
        {
            rrOptions.mode =
                sl::DLSSMode::eBalanced;

            break;
        }

        case 2:
        {
            rrOptions.mode =
                sl::DLSSMode::eMaxQuality;

            break;
        }

        case 3:
        {
            rrOptions.mode =
                sl::DLSSMode::eDLAA;

            break;
        }

        default:
        {
            rrOptions.mode =
                sl::DLSSMode::eMaxQuality;

            break;
        }
    }


rrOptions.outputWidth =
    outputWidth;

rrOptions.outputHeight =
    outputHeight;


// DLSS Ray Reconstruction requires HDR input.
rrOptions.colorBuffersHDR =
    sl::Boolean::eTrue;


// RR-2 uses Normal RGB + Roughness A.
rrOptions.normalRoughnessMode =
    sl::DLSSDNormalRoughnessMode::ePacked;


// Current final alpha channel is not part of
// our RR reconstruction target.
rrOptions.alphaUpscalingEnabled =
    sl::Boolean::eFalse;


// Keep exposure neutral for the current
// linear RT radiance pipeline.
rrOptions.preExposure =
    1.0f;

rrOptions.exposureScale =
    1.0f;


// RR ignores sharpening, but initialize it explicitly.
rrOptions.sharpness =
    0.0f;

const int srMode = g_srMode.load();
srOptions.mode = srMode == 0 ? sl::DLSSMode::eMaxPerformance :
                 srMode == 1 ? sl::DLSSMode::eBalanced :
                 srMode == 3 ? sl::DLSSMode::eDLAA : sl::DLSSMode::eMaxQuality;
srOptions.outputWidth = outputWidth;
srOptions.outputHeight = outputHeight;
srOptions.colorBuffersHDR = sl::Boolean::eTrue;
srOptions.alphaUpscalingEnabled = sl::Boolean::eFalse;
srOptions.preExposure = 1.0f;
srOptions.exposureScale = 1.0f;
srOptions.sharpness = 0.0f;


// Do NOT fill:
//
// rrOptions.worldToCameraView
// rrOptions.cameraViewToWorld
//
// in RR-3.1.
//
// Those matrices are required by our Specular Hit
// Distance route and will be wired from the actual
// camera matrices in RR-3.2 before SetOptions.


// Streamline's optimal-size query only depends on the output extent and
// selected quality mode in this integration. Cache it across frames; include
// the device and function pointer in the key so a Streamline/Vulkan restart
// cannot reuse settings from a previous runtime instance.
static bool hasCachedRROptimalSettings = false;
static uint32_t cachedRROutputWidth = 0u;
static uint32_t cachedRROutputHeight = 0u;
static int cachedRRMode = -1;
static VkDevice cachedRRDevice = VK_NULL_HANDLE;
static PFN_slDLSSDGetOptimalSettings cachedRROptimalFunction = nullptr;
static sl::DLSSDOptimalSettings cachedRROptimal{};
static sl::Result cachedRROptimalResult =
    sl::Result::eErrorFeatureNotSupported;

const bool canQueryRROptimal =
    !useSR && g_dlssRRReady && g_slDLSSDGetOptimalSettings != nullptr;
const bool rrOptimalCacheMiss =
    !hasCachedRROptimalSettings ||
    cachedRROutputWidth != outputWidth ||
    cachedRROutputHeight != outputHeight ||
    cachedRRMode != rrMode ||
    cachedRRDevice != g_device ||
    cachedRROptimalFunction != g_slDLSSDGetOptimalSettings;

if(canQueryRROptimal && rrOptimalCacheMiss)
{
    cachedRROptimal = {};
    cachedRROptimalResult =
        g_slDLSSDGetOptimalSettings(rrOptions, cachedRROptimal);
    cachedRROutputWidth = outputWidth;
    cachedRROutputHeight = outputHeight;
    cachedRRMode = rrMode;
    cachedRRDevice = g_device;
    cachedRROptimalFunction = g_slDLSSDGetOptimalSettings;
    hasCachedRROptimalSettings = true;
}
else if(!canQueryRROptimal)
{
    hasCachedRROptimalSettings = false;
    cachedRROptimal = {};
    cachedRROptimalResult = sl::Result::eErrorFeatureNotSupported;
}

static bool hasCachedSROptimalSettings = false;
static uint32_t cachedSROutputWidth = 0u;
static uint32_t cachedSROutputHeight = 0u;
static int cachedSRMode = -1;
static VkDevice cachedSRDevice = VK_NULL_HANDLE;
static PFN_slDLSSGetOptimalSettings cachedSROptimalFunction = nullptr;
static sl::DLSSOptimalSettings cachedSROptimal{};
static sl::Result cachedSROptimalResult = sl::Result::eErrorFeatureNotSupported;
const bool canQuerySROptimal = useSR && g_dlssReady && g_slDLSSGetOptimalSettings;
const bool srOptimalCacheMiss = !hasCachedSROptimalSettings || cachedSROutputWidth != outputWidth ||
    cachedSROutputHeight != outputHeight || cachedSRMode != srMode || cachedSRDevice != g_device ||
    cachedSROptimalFunction != g_slDLSSGetOptimalSettings;
if(canQuerySROptimal && srOptimalCacheMiss)
{
    cachedSROptimal = {};
    cachedSROptimalResult = g_slDLSSGetOptimalSettings(srOptions, cachedSROptimal);
    cachedSROutputWidth = outputWidth;
    cachedSROutputHeight = outputHeight;
    cachedSRMode = srMode;
    cachedSRDevice = g_device;
    cachedSROptimalFunction = g_slDLSSGetOptimalSettings;
    hasCachedSROptimalSettings = true;
}
else if(!canQuerySROptimal)
{
    hasCachedSROptimalSettings = false;
    cachedSROptimal = {};
    cachedSROptimalResult = sl::Result::eErrorFeatureNotSupported;
}

const sl::DLSSDOptimalSettings& rrOptimal = cachedRROptimal;
const sl::Result rrOptimalResult = cachedRROptimalResult;


// Only print when output resolution changes,
// avoiding one log line every frame.
static uint32_t lastRRSizeLogWidth =
    0;

static uint32_t lastRRSizeLogHeight =
    0;


if(lastRRSizeLogWidth != outputWidth ||
   lastRRSizeLogHeight != outputHeight)
{
    fprintf(
        stderr,
        "[RR SIZE]"
        " result=%d"
        " output=%ux%u"
        " rrOptimal=%ux%u"
        " rrMin=%ux%u"
        " rrMax=%ux%u"
        " HDR=1"
        " packedNR=1\n",

        static_cast<int>(
            rrOptimalResult
        ),

        outputWidth,
        outputHeight,

        rrOptimal.optimalRenderWidth,
        rrOptimal.optimalRenderHeight,

        rrOptimal.renderWidthMin,
        rrOptimal.renderHeightMin,

        rrOptimal.renderWidthMax,
        rrOptimal.renderHeightMax
    );


    fflush(
        stderr
    );


    lastRRSizeLogWidth =
        outputWidth;

    lastRRSizeLogHeight =
        outputHeight;
}
    // =====================================================
// FG-1
// DLSS Frame Generation feature load + API probe.
// DO NOT enable frame generation yet.
// =====================================================

// Feature loading and function resolution are invariant for the lifetime of
// the active Streamline/Vulkan device. Doing both every frame adds dispatch
// overhead and generated several synchronous diagnostic writes per frame.
if(!g_dlssGFeatureInitialized)
{
    g_dlssGFeatureInitialized = true;
    g_dlssGLoadResult =
        g_dlssGSupported && g_slSetFeatureLoaded
            ? g_slSetFeatureLoaded(sl::kFeatureDLSS_G, true)
            : sl::Result::eErrorFeatureNotSupported;

    if(g_dlssGLoadResult == sl::Result::eOk && g_slGetFeatureFunction)
    {
        void* setOptionsFn = nullptr;
        void* getStateFn = nullptr;
        const sl::Result setOptionsResult =
            g_slGetFeatureFunction(
                sl::kFeatureDLSS_G,
                "slDLSSGSetOptions",
                setOptionsFn
            );
        const sl::Result getStateResult =
            g_slGetFeatureFunction(
                sl::kFeatureDLSS_G,
                "slDLSSGGetState",
                getStateFn
            );

        g_slDLSSGSetOptions =
            reinterpret_cast<PFN_slDLSSGSetOptions>(setOptionsFn);
        g_slDLSSGGetState =
            reinterpret_cast<PFN_slDLSSGGetState>(getStateFn);
        g_dlssGReady =
            setOptionsResult == sl::Result::eOk &&
            getStateResult == sl::Result::eOk &&
            g_slDLSSGSetOptions != nullptr &&
            g_slDLSSGGetState != nullptr;

        fprintf(
            stderr,
            "[FG-1 INIT] load=%d setOptions=%d getState=%d ready=%d\n",
            static_cast<int>(g_dlssGLoadResult),
            static_cast<int>(setOptionsResult),
            static_cast<int>(getStateResult),
            g_dlssGReady ? 1 : 0
        );
    }
    else
    {
        g_dlssGReady = false;
        fprintf(
            stderr,
            "[FG-1 INIT] load=%d ready=0\n",
            static_cast<int>(g_dlssGLoadResult)
        );
    }
}
    // State is diagnostic only. Sample it once every two seconds instead of
    // querying and synchronously printing it for every rendered frame.
    if(
        g_dlssGReady &&
        g_slDLSSGGetState &&
        (g_perfL10FrameSerial % 120u) == 0u
    )
    {
        sl::DLSSGState state{};


        sl::Result stateResult =
            g_slDLSSGGetState(
                g_dlssViewport,
                state,
                nullptr
            );


        fprintf(
            stderr,
            "[FG-1 STATE]"
            " result=%d"
            " status=%u"
            " minWidthOrHeight=%u"
            " numFramesToGenerateMax=%u"
            " presented=%u\n",

            static_cast<int>(
                stateResult
            ),

            static_cast<unsigned int>(
                state.status
            ),

            state.minWidthOrHeight,

            state.numFramesToGenerateMax,

            state.numFramesActuallyPresented
        );

        fflush(stderr);
    }


    // =====================================================
    // RR-5.1
    // ACTIVE render sizing switches to DLSS Ray Reconstruction.
    // =====================================================

    if((!useSR && (rrOptimalResult != sl::Result::eOk || rrOptimal.optimalRenderWidth == 0 || rrOptimal.optimalRenderHeight == 0)) ||
       (useSR && (cachedSROptimalResult != sl::Result::eOk || cachedSROptimal.optimalRenderWidth == 0 || cachedSROptimal.optimalRenderHeight == 0)))
    {
        fprintf(
            stderr,
            "[RR-5.1] invalid RR optimal sizing"
            " result=%d"
            " render=%ux%u\n",

            static_cast<int>(useSR ? cachedSROptimalResult : rrOptimalResult),

            useSR ? cachedSROptimal.optimalRenderWidth : rrOptimal.optimalRenderWidth,
            useSR ? cachedSROptimal.optimalRenderHeight : rrOptimal.optimalRenderHeight
        );

        fflush(stderr);

        return;
    }


    const uint32_t renderWidth = useSR
        ? cachedSROptimal.optimalRenderWidth
        : rrOptimal.optimalRenderWidth;

    const uint32_t renderHeight = useSR
        ? cachedSROptimal.optimalRenderHeight
        : rrOptimal.optimalRenderHeight;
    static bool rr53Printed =
    false;

    if(!rr53Printed)
    {
        fprintf(
            stderr,
            "[DLSS-5.3]"
            " runtimeSizing=selected"
            " SRQuery=enabled\n"
        );

        fflush(stderr);

        rr53Printed =
            true;
    }


    static uint32_t rr51LastWidth = 0;
    static uint32_t rr51LastHeight = 0;


    if(
        rr51LastWidth != renderWidth ||
        rr51LastHeight != renderHeight
    )
    {
        fprintf(
            stderr,
            "[DLSS-5.1]"
            " activeSizing=%s"
            " render=%ux%u"
            " output=%ux%u\n",

            useSR ? "SR" : "RR",
            renderWidth,
            renderHeight,

            outputWidth,
            outputHeight
        );

        fflush(stderr);

        rr51LastWidth =
            renderWidth;

        rr51LastHeight =
            renderHeight;
    }


// -------------------------------------
// RT / DLSS input = render resolution
// -------------------------------------

    if(g_rtOutputImage == VK_NULL_HANDLE ||
       g_rtOutputWidth != renderWidth ||
       g_rtOutputHeight != renderHeight)
    {
        createRTOutputImage(
            renderWidth,
            renderHeight
        );
    }


    // =====================================================
    // DLSS Ray Reconstruction GBuffer
    //
    // Always follows RT render resolution.
    // RR Evaluate is NOT enabled yet.
    // =====================================================

    if(
        g_rrDiffuseAlbedoImage ==
            VK_NULL_HANDLE ||

        g_rrSpecularAlbedoImage ==
            VK_NULL_HANDLE ||

        g_rrNormalRoughnessImage ==
            VK_NULL_HANDLE ||

        g_rrSpecularHitDistanceImage ==
            VK_NULL_HANDLE ||

        g_rrGBufferWidth !=
            renderWidth ||

        g_rrGBufferHeight !=
            renderHeight
        )
    {
        createRRGBufferImages(
            renderWidth,
            renderHeight
        );
    }


    createReSTIRDIReservoirBuffers(
        renderWidth,
        renderHeight
    );

    createVPTDirectHistoryBuffers(
        renderWidth,
        renderHeight
    );


    // -------------------------------------
    // DLSS output = swapchain resolution
    // -------------------------------------

if(g_dlssOutputImage == VK_NULL_HANDLE ||
   g_dlssOutputWidth != outputWidth ||
   g_dlssOutputHeight != outputHeight)
{
    createDLSSOutputImage(
        outputWidth,
        outputHeight
    );
}

    if(g_motionImage == VK_NULL_HANDLE ||
       g_motionWidth != renderWidth ||
       g_motionHeight != renderHeight)
    {
        createMotionImage(
            renderWidth,
            renderHeight
        );
    }


    // Depth 必须补这里
    if(g_dlssDepthImage == VK_NULL_HANDLE ||
       g_dlssDepthWidth != renderWidth ||
       g_dlssDepthHeight != renderHeight)
    {
        createDLSSDepthImage(
            renderWidth,
            renderHeight
        );
    }

    // =====================================================
    // DLSS Depth layout preparation
    //
    // binding 7 是 RayGen Storage Image。
    // shader 执行前，Depth 必须已经处于 GENERAL。
    // =====================================================

    if(g_dlssDepthLayout != VK_IMAGE_LAYOUT_GENERAL)
    {
        VkImageMemoryBarrier depthToGeneral{};

        depthToGeneral.sType =
            VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;

        depthToGeneral.oldLayout =
            g_dlssDepthLayout;

        depthToGeneral.newLayout =
            VK_IMAGE_LAYOUT_GENERAL;

        depthToGeneral.srcQueueFamilyIndex =
            VK_QUEUE_FAMILY_IGNORED;

        depthToGeneral.dstQueueFamilyIndex =
            VK_QUEUE_FAMILY_IGNORED;

        depthToGeneral.srcAccessMask =
            0;

        depthToGeneral.dstAccessMask =
            VK_ACCESS_SHADER_WRITE_BIT;

        depthToGeneral.image =
            g_dlssDepthImage;

        depthToGeneral.subresourceRange.aspectMask =
            VK_IMAGE_ASPECT_COLOR_BIT;

        depthToGeneral.subresourceRange.baseMipLevel = 0;
        depthToGeneral.subresourceRange.levelCount = 1;

        depthToGeneral.subresourceRange.baseArrayLayer = 0;
        depthToGeneral.subresourceRange.layerCount = 1;


        vkCmdPipelineBarrier(
            cmd,

            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,

            VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,

            0,

            0,
            nullptr,

            0,
            nullptr,

            1,
            &depthToGeneral
        );


        g_dlssDepthLayout =
            VK_IMAGE_LAYOUT_GENERAL;


        std::cerr
            << "[DLSS DEPTH LAYOUT]"
            << " GENERAL before trace"
            << std::endl;
    }


    // =====================================================
// DLSS Ray Reconstruction GBuffer
// Prepare bindings 13-16 for RayGen writes.
// =====================================================

VkImageMemoryBarrier rrGuideBarriers[4]{};

uint32_t rrGuideBarrierCount =
    0;


auto prepareRRGuideForRayGen =
[&](
    VkImage image,
    VkImageLayout& layout)
{
    if(layout ==
       VK_IMAGE_LAYOUT_GENERAL)
    {
        return;
    }


    VkImageMemoryBarrier& b =
        rrGuideBarriers[
            rrGuideBarrierCount++
        ];


    b.sType =
        VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;

    b.oldLayout =
        layout;

    b.newLayout =
        VK_IMAGE_LAYOUT_GENERAL;

    b.srcQueueFamilyIndex =
        VK_QUEUE_FAMILY_IGNORED;

    b.dstQueueFamilyIndex =
        VK_QUEUE_FAMILY_IGNORED;

    b.srcAccessMask =
        0;

    b.dstAccessMask =
        VK_ACCESS_SHADER_WRITE_BIT;

    b.image =
        image;

    b.subresourceRange.aspectMask =
        VK_IMAGE_ASPECT_COLOR_BIT;

    b.subresourceRange.baseMipLevel = 0;
    b.subresourceRange.levelCount = 1;

    b.subresourceRange.baseArrayLayer = 0;
    b.subresourceRange.layerCount = 1;
};


if(!useSR)
{
prepareRRGuideForRayGen(g_rrDiffuseAlbedoImage, g_rrDiffuseAlbedoLayout);

prepareRRGuideForRayGen(
    g_rrSpecularAlbedoImage,
    g_rrSpecularAlbedoLayout
);

prepareRRGuideForRayGen(
    g_rrNormalRoughnessImage,
    g_rrNormalRoughnessLayout
);

prepareRRGuideForRayGen(g_rrSpecularHitDistanceImage, g_rrSpecularHitDistanceLayout);
}


if(rrGuideBarrierCount > 0)
{
    vkCmdPipelineBarrier(
        cmd,

        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,

        VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,

        0,

        0,
        nullptr,

        0,
        nullptr,

        rrGuideBarrierCount,
        rrGuideBarriers
    );


    g_rrDiffuseAlbedoLayout =
        VK_IMAGE_LAYOUT_GENERAL;

    g_rrSpecularAlbedoLayout =
        VK_IMAGE_LAYOUT_GENERAL;

    g_rrNormalRoughnessLayout =
        VK_IMAGE_LAYOUT_GENERAL;

    g_rrSpecularHitDistanceLayout =
        VK_IMAGE_LAYOUT_GENERAL;


    std::cerr
        << "[RR GBUFFER LAYOUT]"
        << " GENERAL before trace"
        << std::endl;
}


    // A-3 RT OUTPUT IMAGE BARRIER
    // ===============================

    VkImageMemoryBarrier barrier{};

    barrier.sType =
        VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;


    barrier.oldLayout =
    g_rtOutputLayout;

    barrier.newLayout =
        VK_IMAGE_LAYOUT_GENERAL;


    barrier.srcAccessMask = 0;


    barrier.dstAccessMask =
        VK_ACCESS_SHADER_WRITE_BIT;


    barrier.image =
        g_rtOutputImage;


    barrier.subresourceRange.aspectMask =
        VK_IMAGE_ASPECT_COLOR_BIT;


    barrier.subresourceRange.baseMipLevel = 0;
    barrier.subresourceRange.levelCount = 1;


    barrier.subresourceRange.baseArrayLayer = 0;
    barrier.subresourceRange.layerCount = 1;



    if(g_rtOutputLayout != VK_IMAGE_LAYOUT_GENERAL)
    {

        VkImageMemoryBarrier barrier{};

        barrier.sType =
            VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;


        barrier.oldLayout =
            g_rtOutputLayout;


        barrier.newLayout =
            VK_IMAGE_LAYOUT_GENERAL;


        barrier.srcAccessMask = 0;


        barrier.dstAccessMask =
            VK_ACCESS_SHADER_WRITE_BIT;


        barrier.image =
            g_rtOutputImage;


        barrier.subresourceRange.aspectMask =
            VK_IMAGE_ASPECT_COLOR_BIT;


        barrier.subresourceRange.baseMipLevel = 0;
        barrier.subresourceRange.levelCount = 1;

        barrier.subresourceRange.baseArrayLayer = 0;
        barrier.subresourceRange.layerCount = 1;


        vkCmdPipelineBarrier(

            cmd,

            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,

            VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,

            0,

            0,
            nullptr,

            0,
            nullptr,

            1,
            &barrier
        );

        std::cerr
    << "[RT OUTPUT LAYOUT] "
    << (int)g_rtOutputLayout
    << " -> GENERAL before trace"
    << std::endl;


        g_rtOutputLayout =
            VK_IMAGE_LAYOUT_GENERAL;

    }


    static bool printedHandle = false;

    if(!printedHandle)
    {
        // std::cout
        //     << "RT OUTPUT HANDLE="
        //     << g_rtOutputImage
        //     << "\n";

        printedHandle = true;
    }

    // ReSTIR DI reservoir history is independent from the removed
    // custom color-history images.  Prepare its ping-pong SSBOs before
    // RayGen reads the previous frame and writes the current frame.
    prepareReSTIRDIReservoirBuffers(cmd);
    prepareVPTDirectHistoryBuffers(cmd);
    recordWaterSimulation(cmd);
    recordWaterCaustics(cmd);
    createVolumetricCloudPipeline(renderWidth, renderHeight);


    // Perf-1.2: custom temporal history barriers removed.
    vkCmdBindPipeline(
        cmd,
        VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
        g_rayTracingPipeline
    );
    // Atlas descriptor publishing happens only at the start of this frame,
    // after its lifecycle-safe idle barrier. If an uploader marks an atlas
    // dirty while this frame is recording, defer publication to the next frame
    // instead of overwriting a descriptor set that may already be in flight.
    vkCmdBindDescriptorSets(

        cmd,
        VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
        g_rayTracingPipelineLayout,
        0,
        1,
        &g_rayTracingDescriptorSet,
        0,
        nullptr
    );

    auto trace =
       getCmdTraceRays();

    if(!trace)
    {
        return;
    }


    const int32_t perfL10RtTimestampSlot =
        beginPerfL10RtTimestampScope(
            cmd,
            renderWidth,
            renderHeight
        );


    trace(
        cmd,
        &g_raygenRegion,
        &g_missRegion,
        &g_hitRegion,
        &g_callableRegion,
        renderWidth,
        renderHeight,
        1
    );

    // =====================================================
    // ReSTIR DI same-frame spatial reuse
    //
    // Pass 1 above only builds Initial+Temporal reservoirs.  This compute
    // stage combines four compatible same-frame neighbours without tracing
    // visibility. Spatial reuse is terminal for this frame; temporal history
    // remains the pre-spatial reservoir. The final RayGen below then evaluates
    // exactly one selected shadow ray per valid opaque receiver.
    // =====================================================
    if(
        g_restirDISpatialPipeline != VK_NULL_HANDLE &&
        g_restirDISpatialBuffer != VK_NULL_HANDLE &&
        g_restirDIReservoirBytes > 0
    )
    {
        VkBufferMemoryBarrier initialToSpatial[2]{};
        for(uint32_t i = 0u; i < 2u; ++i)
        {
            initialToSpatial[i].sType =
                VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
            initialToSpatial[i].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            initialToSpatial[i].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            initialToSpatial[i].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            initialToSpatial[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            initialToSpatial[i].buffer = g_restirDIReservoirBuffers[i];
            initialToSpatial[i].offset = 0;
            initialToSpatial[i].size = g_restirDIReservoirBytes;
        }

        vkCmdPipelineBarrier(
            cmd,
            VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0,
            0, nullptr,
            2, initialToSpatial,
            0, nullptr
        );

        vkCmdBindPipeline(
            cmd,
            VK_PIPELINE_BIND_POINT_COMPUTE,
            g_restirDISpatialPipeline
        );
        vkCmdBindDescriptorSets(
            cmd,
            VK_PIPELINE_BIND_POINT_COMPUTE,
            g_restirDISpatialPipelineLayout,
            0,
            1,
            &g_rayTracingDescriptorSet,
            0,
            nullptr
        );

        ReSTIRDISpatialPushConstants spatialPush{};
        spatialPush.width = renderWidth;
        spatialPush.height = renderHeight;
        vkCmdPushConstants(
            cmd,
            g_restirDISpatialPipelineLayout,
            VK_SHADER_STAGE_COMPUTE_BIT,
            0,
            sizeof(spatialPush),
            &spatialPush
        );
        vkCmdDispatch(
            cmd,
            (renderWidth + 7u) / 8u,
            (renderHeight + 7u) / 8u,
            1u
        );

        VkBufferMemoryBarrier spatialReady{};
        spatialReady.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        spatialReady.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        spatialReady.dstAccessMask =
            VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        spatialReady.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        spatialReady.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        spatialReady.buffer = g_restirDISpatialBuffer;
        spatialReady.offset = 0;
        spatialReady.size = g_restirDIReservoirBytes;

        VkImageMemoryBarrier outputReady{};
        outputReady.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        outputReady.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        outputReady.dstAccessMask =
            VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        outputReady.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        outputReady.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        outputReady.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        outputReady.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        outputReady.image = g_rtOutputImage;
        outputReady.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        outputReady.subresourceRange.baseMipLevel = 0;
        outputReady.subresourceRange.levelCount = 1;
        outputReady.subresourceRange.baseArrayLayer = 0;
        outputReady.subresourceRange.layerCount = 1;

        vkCmdPipelineBarrier(
            cmd,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT |
                VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
            VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
            0,
            0, nullptr,
            1, &spatialReady,
            1, &outputReady
        );

        // Orion-style terminal spatial reuse: do not feed the spatially
        // merged reservoir back into temporal history. The primary RayGen has
        // already written the fresh + same-receiver temporal reservoir into the
        // current ping-pong buffer. Keeping that version avoids recursive
        // neighbour re-import and also removes one full reservoir-buffer copy.

        // Rebind RT after compute and add only the spatially-selected local DI.
        vkCmdBindPipeline(
            cmd,
            VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
            g_rayTracingPipeline
        );
        vkCmdBindDescriptorSets(
            cmd,
            VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
            g_rayTracingPipelineLayout,
            0,
            1,
            &g_rayTracingDescriptorSet,
            0,
            nullptr
        );
        trace(
            cmd,
            &g_restirFinalRaygenRegion,
            &g_missRegion,
            &g_hitRegion,
            &g_callableRegion,
            renderWidth,
            renderHeight,
            1
        );

        // =====================================================
        // ReSTIR DI B2: same-frame post-visibility reconstruction
        //
        // Final RayGen has consumed the spatial reservoir and overwritten only
        // Binding 41 sampleAndAreaPdf with the raw one-ray local-DI estimate.
        // The reservoir's receiver position/normal/albedo metadata remains
        // intact, so the compute pass can reuse neighbouring current-frame
        // samples without another RT visibility query or another large buffer.
        // =====================================================
        if(
            g_restirDIReconstructPipeline != VK_NULL_HANDLE &&
            g_vptDirectHistoryBuffers[0] != VK_NULL_HANDLE &&
            g_vptDirectHistoryBuffers[1] != VK_NULL_HANDLE
        )
        {
            VkBufferMemoryBarrier rawLocalDIReady{};
            rawLocalDIReady.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
            rawLocalDIReady.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            rawLocalDIReady.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            rawLocalDIReady.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            rawLocalDIReady.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            rawLocalDIReady.buffer = g_restirDISpatialBuffer;
            rawLocalDIReady.offset = 0;
            rawLocalDIReady.size = g_restirDIReservoirBytes;

            VkImageMemoryBarrier baseColorReady{};
            baseColorReady.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            baseColorReady.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            baseColorReady.dstAccessMask =
                VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            baseColorReady.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
            baseColorReady.newLayout = VK_IMAGE_LAYOUT_GENERAL;
            baseColorReady.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            baseColorReady.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            baseColorReady.image = g_rtOutputImage;
            baseColorReady.subresourceRange.aspectMask =
                VK_IMAGE_ASPECT_COLOR_BIT;
            baseColorReady.subresourceRange.baseMipLevel = 0;
            baseColorReady.subresourceRange.levelCount = 1;
            baseColorReady.subresourceRange.baseArrayLayer = 0;
            baseColorReady.subresourceRange.layerCount = 1;

            vkCmdPipelineBarrier(
                cmd,
                VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0,
                0, nullptr,
                1, &rawLocalDIReady,
                1, &baseColorReady
            );

            vkCmdBindPipeline(
                cmd,
                VK_PIPELINE_BIND_POINT_COMPUTE,
                g_restirDIReconstructPipeline
            );
            vkCmdBindDescriptorSets(
                cmd,
                VK_PIPELINE_BIND_POINT_COMPUTE,
                g_restirDIReconstructPipelineLayout,
                0,
                1,
                &g_rayTracingDescriptorSet,
                0,
                nullptr
            );

            ReSTIRDISpatialPushConstants reconstructPush{};
            reconstructPush.width = renderWidth;
            reconstructPush.height = renderHeight;
            vkCmdPushConstants(
                cmd,
                g_restirDIReconstructPipelineLayout,
                VK_SHADER_STAGE_COMPUTE_BIT,
                0,
                sizeof(reconstructPush),
                &reconstructPush
            );
            vkCmdDispatch(
                cmd,
                (renderWidth + 7u) / 8u,
                (renderHeight + 7u) / 8u,
                1u
            );

            VkImageMemoryBarrier reconstructedOutputReady{};
            reconstructedOutputReady.sType =
                VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            reconstructedOutputReady.srcAccessMask =
                VK_ACCESS_SHADER_WRITE_BIT;
            reconstructedOutputReady.dstAccessMask =
                VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            reconstructedOutputReady.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
            reconstructedOutputReady.newLayout = VK_IMAGE_LAYOUT_GENERAL;
            reconstructedOutputReady.srcQueueFamilyIndex =
                VK_QUEUE_FAMILY_IGNORED;
            reconstructedOutputReady.dstQueueFamilyIndex =
                VK_QUEUE_FAMILY_IGNORED;
            reconstructedOutputReady.image = g_rtOutputImage;
            reconstructedOutputReady.subresourceRange.aspectMask =
                VK_IMAGE_ASPECT_COLOR_BIT;
            reconstructedOutputReady.subresourceRange.baseMipLevel = 0;
            reconstructedOutputReady.subresourceRange.levelCount = 1;
            reconstructedOutputReady.subresourceRange.baseArrayLayer = 0;
            reconstructedOutputReady.subresourceRange.layerCount = 1;

            vkCmdPipelineBarrier(
                cmd,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT |
                    VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                0,
                0, nullptr,
                0, nullptr,
                1, &reconstructedOutputReady
            );
        }
    }

    endPerfL10RtTimestampScope(
        cmd,
        perfL10RtTimestampSlot
    );

    recordVolumetricClouds(cmd);


    // Perf-1.2: custom temporal CPU bookkeeping removed.
    // =====================================================
// DLSS Vulkan synchronization / layout preparation
// =====================================================

// RT output 已经是 GENERAL，
// 这里只建立 RayTracing shader write -> DLSS read 的内存依赖。
    // =====================================================
    // Streamline input synchronization
    //
    // RayGen writes:
    //   RT color
    //   Depth
    //   RR Diffuse Albedo
    //   RR Specular Albedo
    //   RR Normal + Roughness
    //   RR Specular Hit Distance
    //
    // All remain GENERAL.
    // This only establishes shader-write -> SL-read dependency.
    // =====================================================

    VkImageMemoryBarrier slInputBarriers[6]{};


    auto setupSLReadBarrier =
    [](
        VkImageMemoryBarrier& b,
        VkImage image)
    {
        b.sType =
            VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;

        b.oldLayout =
            VK_IMAGE_LAYOUT_GENERAL;

        b.newLayout =
            VK_IMAGE_LAYOUT_GENERAL;

        b.srcQueueFamilyIndex =
            VK_QUEUE_FAMILY_IGNORED;

        b.dstQueueFamilyIndex =
            VK_QUEUE_FAMILY_IGNORED;

        b.srcAccessMask =
            VK_ACCESS_SHADER_WRITE_BIT;

        b.dstAccessMask =
            VK_ACCESS_MEMORY_READ_BIT;

        b.image =
            image;

        b.subresourceRange.aspectMask =
            VK_IMAGE_ASPECT_COLOR_BIT;

        b.subresourceRange.baseMipLevel = 0;
        b.subresourceRange.levelCount = 1;

        b.subresourceRange.baseArrayLayer = 0;
        b.subresourceRange.layerCount = 1;
    };


    setupSLReadBarrier(
        slInputBarriers[0],
        g_rtOutputImage
    );

    setupSLReadBarrier(
        slInputBarriers[1],
        g_dlssDepthImage
    );

    setupSLReadBarrier(
        slInputBarriers[2],
        g_rrDiffuseAlbedoImage
    );

    setupSLReadBarrier(
        slInputBarriers[3],
        g_rrSpecularAlbedoImage
    );

    setupSLReadBarrier(
        slInputBarriers[4],
        g_rrNormalRoughnessImage
    );

    setupSLReadBarrier(
        slInputBarriers[5],
        g_rrSpecularHitDistanceImage
    );


    vkCmdPipelineBarrier(
        cmd,

        VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR |
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,

        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,

        0,

        0,
        nullptr,

        0,
        nullptr,

        6,
        slInputBarriers
    );


// -----------------------------------------------------
// DLSS output / depth / motion:
// 把真实 layout 从 UNDEFINED 转到 GENERAL
// -----------------------------------------------------

VkImageMemoryBarrier dlssLayoutBarriers[3]{};
uint32_t dlssLayoutBarrierCount = 0;
    // =====================================================
    // DLSS Output real layout:
    //
    // newly created:
    //     UNDEFINED
    //
    // before slEvaluateFeature:
    //     GENERAL
    //
    // Streamline resource state below is also declared GENERAL.
    // =====================================================

    if(g_dlssOutputLayout != VK_IMAGE_LAYOUT_GENERAL)
    {
        VkImageMemoryBarrier& b =
            dlssLayoutBarriers[dlssLayoutBarrierCount++];


        b.sType =
            VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;


        b.oldLayout =
            g_dlssOutputLayout;


        b.newLayout =
            VK_IMAGE_LAYOUT_GENERAL;


        b.srcQueueFamilyIndex =
            VK_QUEUE_FAMILY_IGNORED;

        b.dstQueueFamilyIndex =
            VK_QUEUE_FAMILY_IGNORED;


        b.srcAccessMask =
            0;


        b.dstAccessMask =
            VK_ACCESS_MEMORY_READ_BIT |
            VK_ACCESS_MEMORY_WRITE_BIT;


        b.image =
            g_dlssOutputImage;


        b.subresourceRange.aspectMask =
            VK_IMAGE_ASPECT_COLOR_BIT;

        b.subresourceRange.baseMipLevel =
            0;

        b.subresourceRange.levelCount =
            1;

        b.subresourceRange.baseArrayLayer =
            0;

        b.subresourceRange.layerCount =
            1;


        std::cerr
            << "[DLSS OUTPUT LAYOUT]"
            << " "
            << (int)g_dlssOutputLayout
            << " -> GENERAL before Evaluate"
            << std::endl;
    }
if(g_dlssDepthLayout != VK_IMAGE_LAYOUT_GENERAL)
{
    VkImageMemoryBarrier& b =
        dlssLayoutBarriers[dlssLayoutBarrierCount++];

    b.sType =
        VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;

    b.oldLayout =
        g_dlssDepthLayout;

    b.newLayout =
        VK_IMAGE_LAYOUT_GENERAL;

    b.srcQueueFamilyIndex =
        VK_QUEUE_FAMILY_IGNORED;

    b.dstQueueFamilyIndex =
        VK_QUEUE_FAMILY_IGNORED;

    b.srcAccessMask = 0;

    b.dstAccessMask =
        VK_ACCESS_MEMORY_READ_BIT |
        VK_ACCESS_MEMORY_WRITE_BIT;

    b.image =
        g_dlssDepthImage;

    b.subresourceRange.aspectMask =
        VK_IMAGE_ASPECT_COLOR_BIT;

    b.subresourceRange.baseMipLevel = 0;
    b.subresourceRange.levelCount = 1;
    b.subresourceRange.baseArrayLayer = 0;
    b.subresourceRange.layerCount = 1;
}


if(g_motionLayout != VK_IMAGE_LAYOUT_GENERAL)
{
    VkImageMemoryBarrier& b =
        dlssLayoutBarriers[dlssLayoutBarrierCount++];

    b.sType =
        VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;

    b.oldLayout =
        g_motionLayout;

    b.newLayout =
        VK_IMAGE_LAYOUT_GENERAL;

    b.srcQueueFamilyIndex =
        VK_QUEUE_FAMILY_IGNORED;

    b.dstQueueFamilyIndex =
        VK_QUEUE_FAMILY_IGNORED;

    b.srcAccessMask = 0;

    b.dstAccessMask =
        VK_ACCESS_MEMORY_READ_BIT |
        VK_ACCESS_MEMORY_WRITE_BIT;

    b.image =
        g_motionImage;

    b.subresourceRange.aspectMask =
        VK_IMAGE_ASPECT_COLOR_BIT;

    b.subresourceRange.baseMipLevel = 0;
    b.subresourceRange.levelCount = 1;
    b.subresourceRange.baseArrayLayer = 0;
    b.subresourceRange.layerCount = 1;
}


if(dlssLayoutBarrierCount > 0)
{
    vkCmdPipelineBarrier(
        cmd,

        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,

        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,

        0,

        0,
        nullptr,

        0,
        nullptr,

        dlssLayoutBarrierCount,
        dlssLayoutBarriers
    );


    g_dlssOutputLayout =
        VK_IMAGE_LAYOUT_GENERAL;

    g_dlssDepthLayout =
        VK_IMAGE_LAYOUT_GENERAL;

    g_motionLayout =
        VK_IMAGE_LAYOUT_GENERAL;
}
    // =====================================================
    // DLSS Motion Vector
    //
    // Route A:
    // static world geometry has no object motion.
    // Clear motion buffer to an explicit (0, 0) every frame.
    // Camera motion will be supplied by Streamline later
    // through depth + clipToPrevClip.
    // =====================================================

    VkClearColorValue motionZero{};
    motionZero.float32[0] = 0.0f;
    motionZero.float32[1] = 0.0f;
    motionZero.float32[2] = 0.0f;
    motionZero.float32[3] = 0.0f;


    VkImageSubresourceRange motionRange{};

    motionRange.aspectMask =
        VK_IMAGE_ASPECT_COLOR_BIT;

    motionRange.baseMipLevel = 0;
    motionRange.levelCount = 1;

    motionRange.baseArrayLayer = 0;
    motionRange.layerCount = 1;


    vkCmdClearColorImage(
        cmd,
        g_motionImage,
        VK_IMAGE_LAYOUT_GENERAL,
        &motionZero,
        1,
        &motionRange
    );
    VkImageMemoryBarrier motionClearToDLSS{};

    motionClearToDLSS.sType =
        VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;

    motionClearToDLSS.oldLayout =
        VK_IMAGE_LAYOUT_GENERAL;

    motionClearToDLSS.newLayout =
        VK_IMAGE_LAYOUT_GENERAL;

    motionClearToDLSS.srcQueueFamilyIndex =
        VK_QUEUE_FAMILY_IGNORED;

    motionClearToDLSS.dstQueueFamilyIndex =
        VK_QUEUE_FAMILY_IGNORED;

    motionClearToDLSS.srcAccessMask =
        VK_ACCESS_TRANSFER_WRITE_BIT;

    motionClearToDLSS.dstAccessMask =
        VK_ACCESS_MEMORY_READ_BIT;

    motionClearToDLSS.image =
        g_motionImage;

    motionClearToDLSS.subresourceRange =
        motionRange;


    vkCmdPipelineBarrier(
        cmd,

        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,

        0,

        0,
        nullptr,

        0,
        nullptr,

        1,
        &motionClearToDLSS
    );
    if(g_dlssRRReady || g_dlssReady)
    {
        if(!g_currentSLFrameValid ||
           g_currentSLFrameToken == nullptr)
        {
            return;
        }


        sl::FrameToken* frameToken =
            g_currentSLFrameToken;


        // recordFrameCommand 在同一个应用帧中可能不止进入一次。
        // DLSS Evaluate 只能针对当前统一 FrameToken 执行一次。
        static uint32_t lastDLSSFrameIndex =
            UINT32_MAX;


        if(lastDLSSFrameIndex ==
           g_currentSLFrameIndex)
        {
            return;
        }


        lastDLSSFrameIndex =
            g_currentSLFrameIndex;


        uint32_t dlssJitterFrameIndex =
            0u;


        sl::float4x4 currentCameraViewToWorld =
        {
            sl::float4(1, 0, 0, 0),
            sl::float4(0, 1, 0, 0),
            sl::float4(0, 0, 1, 0),
            sl::float4(0, 0, 0, 1)
        };

        sl::float4x4 currentClipToCameraView =
            currentCameraViewToWorld;

        sl::float4x4 currentCameraViewToClip =
            currentCameraViewToWorld;


        if(g_cameraUboMapped)
        {
            const CameraUBO* cameraUbo =
                static_cast<const CameraUBO*>(
                    g_cameraUboMapped
                );


            currentCameraViewToWorld =
                JOMLColumnMajorToSLRowMajor(
                    cameraUbo->viewInverse
                );


            currentClipToCameraView =
                JOMLColumnMajorToSLRowMajor(
                    cameraUbo->projInverse
                );


            sl::matrixFullInvert(
                currentCameraViewToClip,
                currentClipToCameraView
            );


            dlssJitterFrameIndex =
                cameraUbo->frameCount;
        }

                // =====================================================
        // RR-3.2
        // DLSS Ray Reconstruction camera matrices
        //
        // Specular Hit Distance requires:
        //
        //   worldToCameraView
        //   cameraViewToWorld
        //
        // Both matrices are Streamline row-major.
        //
        // currentCameraViewToWorld already comes from the same
        // camera matrix used by the ray tracer / SL constants.
        // =====================================================

        sl::float4x4 currentWorldToCameraView{};


        sl::matrixFullInvert(
            currentWorldToCameraView,
            currentCameraViewToWorld
        );


        rrOptions.cameraViewToWorld =
            currentCameraViewToWorld;


        rrOptions.worldToCameraView =
            currentWorldToCameraView;


        // =====================================================
        // Validate:
        //
        // worldToCameraView * cameraViewToWorld
        // must be Identity.
        // =====================================================

        sl::float4x4 rrMatrixIdentityCheck{};


        sl::matrixMul(
            rrMatrixIdentityCheck,
            rrOptions.worldToCameraView,
            rrOptions.cameraViewToWorld
        );


        const float* rrIdentityValues =
            &rrMatrixIdentityCheck[0].x;


        float rrMatrixMaxError =
            0.0f;


        for(uint32_t i = 0; i < 16; ++i)
        {
            const uint32_t row =
                i / 4;

            const uint32_t column =
                i % 4;


            const float expected =
                row == column
                    ? 1.0f
                    : 0.0f;


            rrMatrixMaxError =
                std::max(
                    rrMatrixMaxError,
                    std::abs(
                        rrIdentityValues[i] -
                        expected
                    )
                );
        }


        // =====================================================
        // RR-3.2 SetOptions
        //
        // Still NO DLSS_RR Evaluate here.
        //
        // Matrices change with the camera, therefore RR options
        // carrying these matrices are updated every rendered frame.
        // =====================================================

        sl::Result rrSetOptionsResult =
            sl::Result::eErrorFeatureNotSupported;


        if(useSR && g_dlssReady && g_slDLSSSetOptions)
        {
            rrSetOptionsResult = g_slDLSSSetOptions(g_dlssViewport, srOptions);
        }
        else if(!useSR && g_dlssRRReady && g_slDLSSDSetOptions)
        {
            rrSetOptionsResult =
                g_slDLSSDSetOptions(
                    g_dlssViewport,
                    rrOptions
                );
        }


        static bool rr32SuccessPrinted =
            false;

        static bool rr32RealCameraPrinted =
            false;

        if(
            rrSetOptionsResult ==
                sl::Result::eOk
        )
        {static bool rr52Printed =
    false;

            if(!rr52Printed)
            {
                fprintf(
                    stderr,
                    "[RR-5.2]"
                    " activeSetOptions=RR"
                    " SRSetOptions=disabled\n"
                );

                fflush(stderr);

                rr52Printed =
                    true;
            }
            const float rrCameraMagnitude =
    std::abs(currentCameraViewToWorld[3].x) +
    std::abs(currentCameraViewToWorld[3].y) +
    std::abs(currentCameraViewToWorld[3].z);


            if(
                !rr32RealCameraPrinted &&
                rrCameraMagnitude > 0.001f
            )
            {
                fprintf(
                    stderr,
                    "[RR-3.2 REAL CAMERA]"
                    " inverseError=%.9g"
                    " cameraPos=(%.6f,%.6f,%.6f)\n",

                    static_cast<double>(
                        rrMatrixMaxError
                    ),

                    static_cast<double>(
                        currentCameraViewToWorld[3].x
                    ),

                    static_cast<double>(
                        currentCameraViewToWorld[3].y
                    ),

                    static_cast<double>(
                        currentCameraViewToWorld[3].z
                    )
                );

                fflush(stderr);

                rr32RealCameraPrinted =
                    true;
            }
            if(!rr32SuccessPrinted)
            {
                fprintf(
                    stderr,
                    "[RR-3.2]"
                    " SetOptions=0"
                    " inverseError=%.9g"
                    " cameraPos=(%.6f,%.6f,%.6f)"
                    "\n",

                    static_cast<double>(
                        rrMatrixMaxError
                    ),

                    static_cast<double>(
                        currentCameraViewToWorld[3].x
                    ),

                    static_cast<double>(
                        currentCameraViewToWorld[3].y
                    ),

                    static_cast<double>(
                        currentCameraViewToWorld[3].z
                    )
                );


                fflush(
                    stderr
                );


                rr32SuccessPrinted =
                    true;
            }
        }
        else
        {
            fprintf(
                stderr,
                "[RR-3.2]"
                " SetOptions FAILED"
                " result=%d"
                " inverseError=%.9g"
                "\n",

                static_cast<int>(
                    rrSetOptionsResult
                ),

                static_cast<double>(
                    rrMatrixMaxError
                )
            );


            fflush(
                stderr
            );
        }


        if(rrMatrixMaxError > 1.0e-3f)
        {
            fprintf(
                stderr,
                "[RR-3.2]"
                " MATRIX VALIDATION FAILED"
                " inverseError=%.9g"
                "\n",

                static_cast<double>(
                    rrMatrixMaxError
                )
            );


            fflush(
                stderr
            );
        }


        float dlssJitterX =
            0.0f;

        float dlssJitterY =
            0.0f;


        GetDLSSFrameJitter(
            dlssJitterFrameIndex,
            dlssJitterX,
            dlssJitterY
        );
        sl::Constants constants{};


        // 临时测试矩阵：单位矩阵
        constants.cameraViewToClip =
    currentCameraViewToClip;

        constants.clipToCameraView =
            currentClipToCameraView;


        if(g_hasPrevCameraMatrices)
        {
            sl::float4x4 cameraViewToPrevCameraView{};

            sl::calcCameraToPrevCamera(
                cameraViewToPrevCameraView,
                currentCameraViewToWorld,
                g_prevCameraViewToWorld
            );


            sl::float4x4 clipToPrevCameraView{};

            sl::matrixMul(
                clipToPrevCameraView,
                currentClipToCameraView,
                cameraViewToPrevCameraView
            );


            sl::matrixMul(
                constants.clipToPrevClip,
                clipToPrevCameraView,
                g_prevCameraViewToClip
            );


            sl::matrixFullInvert(
                constants.prevClipToClip,
                constants.clipToPrevClip
            );
        }
        else
        {
            for(int i = 0; i < 4; i++)
            {
                constants.clipToPrevClip[i] =
                    sl::float4(
                        i == 0 ? 1.0f : 0.0f,
                        i == 1 ? 1.0f : 0.0f,
                        i == 2 ? 1.0f : 0.0f,
                        i == 3 ? 1.0f : 0.0f
                    );

                constants.prevClipToClip[i] =
                    constants.clipToPrevClip[i];
            }
        }


        constants.jitterOffset =
            sl::float2(
                -dlssJitterX,
                -dlssJitterY
            );
        constants.cameraPinholeOffset =
    sl::float2(
        0.0f,
        0.0f
    );


        constants.cameraPos =
            sl::float3(
                currentCameraViewToWorld[3].x,
                currentCameraViewToWorld[3].y,
                currentCameraViewToWorld[3].z
            );


        constants.cameraRight =
            sl::float3(
                currentCameraViewToWorld[0].x,
                currentCameraViewToWorld[0].y,
                currentCameraViewToWorld[0].z
            );


        constants.cameraUp =
            sl::float3(
                currentCameraViewToWorld[1].x,
                currentCameraViewToWorld[1].y,
                currentCameraViewToWorld[1].z
            );


        constants.cameraFwd =
            sl::float3(
                -currentCameraViewToWorld[2].x,
                -currentCameraViewToWorld[2].y,
                -currentCameraViewToWorld[2].z
            );

        if(g_cameraUboMapped)
        {
            const CameraUBO* cameraUbo =
                static_cast<const CameraUBO*>(
                    g_cameraUboMapped
                );

            constants.cameraNear =
                cameraUbo->cameraNear;

            constants.cameraFar =
                cameraUbo->cameraFar;

            constants.cameraFOV =
                cameraUbo->cameraFov;
        }


        constants.cameraAspectRatio =
            static_cast<float>(outputWidth) /
            static_cast<float>(outputHeight);


        constants.mvecScale =
            sl::float2(
                1.0f,
                1.0f
            );


        constants.depthInverted =
            sl::Boolean::eTrue;

        constants.cameraMotionIncluded =
            sl::Boolean::eFalse;

        constants.motionVectors3D =
            sl::Boolean::eFalse;

        constants.motionVectorsJittered =
            sl::Boolean::eFalse;

        constants.motionVectorsDilated =
            sl::Boolean::eFalse;

        constants.reset =
            g_hasPrevCameraMatrices
                ? sl::Boolean::eFalse
                : sl::Boolean::eTrue;


        sl::Result constantsResult =
            g_slSetConstants(
                constants,
                *frameToken,
                g_dlssViewport
            );


// =====================================================
// DLSS INPUT RESOURCE
// =====================================================
        // =====================================================
        // RR-3.4
        // Common Constants final validation
        // =====================================================

        if(constantsResult != sl::Result::eOk)
        {
            fprintf(
                stderr,
                "[RR-3.4] SetConstants FAILED result=%d\n",
                static_cast<int>(
                    constantsResult
                )
            );

            fflush(stderr);

            return;
        }


        const bool rr34SizeMatch =
            g_dlssDepthWidth ==
                g_rtOutputWidth &&
            g_dlssDepthHeight ==
                g_rtOutputHeight &&
            g_motionWidth ==
                g_rtOutputWidth &&
            g_motionHeight ==
                g_rtOutputHeight;


        static bool rr34Printed =
            false;


        if(!rr34Printed)
        {
            fprintf(
                stderr,
                "[RR-3.4]"
                " Constants=0"
                " depth=%ux%u"
                " motion=%ux%u"
                " input=%ux%u"
                " sizeMatch=%d"
                " reversedZ=1"
                " cameraMotionIncluded=0"
                " mvec3D=0"
                " jitteredMV=0"
                " dilatedMV=0"
                " jitter=(%.6f,%.6f)"
                " near=%.6f"
                " far=%.6f"
                " fov=%.6f\n",

                g_dlssDepthWidth,
                g_dlssDepthHeight,

                g_motionWidth,
                g_motionHeight,

                g_rtOutputWidth,
                g_rtOutputHeight,

                rr34SizeMatch
                    ? 1
                    : 0,

                static_cast<double>(
                    constants.jitterOffset.x
                ),

                static_cast<double>(
                    constants.jitterOffset.y
                ),

                static_cast<double>(
                    constants.cameraNear
                ),

                static_cast<double>(
                    constants.cameraFar
                ),

                static_cast<double>(
                    constants.cameraFOV
                )
            );

            fflush(stderr);

            rr34Printed =
                true;
        }
sl::Resource dlssInput(
    sl::ResourceType::eTex2d,
    (void*)g_rtOutputImage,
    (void*)g_rtOutputMemory,
    (void*)g_rtOutputView,
    VK_IMAGE_LAYOUT_GENERAL
);

dlssInput.width =
    g_rtOutputWidth;

dlssInput.height =
    g_rtOutputHeight;

dlssInput.nativeFormat =
    static_cast<uint32_t>(
        VK_FORMAT_R16G16B16A16_SFLOAT
    );

dlssInput.mipLevels = 1;
dlssInput.arrayLayers = 1;
dlssInput.flags = 0;

dlssInput.usage =
    VK_IMAGE_USAGE_STORAGE_BIT |
    VK_IMAGE_USAGE_SAMPLED_BIT |
    VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
    VK_IMAGE_USAGE_TRANSFER_DST_BIT;


// =====================================================
// DLSS OUTPUT RESOURCE
// =====================================================

sl::Resource dlssOutput(
    sl::ResourceType::eTex2d,
    (void*)g_dlssOutputImage,
    (void*)g_dlssOutputMemory,
    (void*)g_dlssOutputView,
    VK_IMAGE_LAYOUT_GENERAL
);

dlssOutput.width =
    g_dlssOutputWidth;

dlssOutput.height =
    g_dlssOutputHeight;

dlssOutput.nativeFormat =
    static_cast<uint32_t>(
        VK_FORMAT_R16G16B16A16_SFLOAT
    );

dlssOutput.mipLevels = 1;
dlssOutput.arrayLayers = 1;
dlssOutput.flags = 0;

dlssOutput.usage =
    VK_IMAGE_USAGE_STORAGE_BIT |
    VK_IMAGE_USAGE_SAMPLED_BIT |
    VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
    VK_IMAGE_USAGE_TRANSFER_DST_BIT;


// =====================================================
// DLSS DEPTH RESOURCE
// =====================================================

sl::Resource dlssDepth(
    sl::ResourceType::eTex2d,
    (void*)g_dlssDepthImage,
    (void*)g_dlssDepthMemory,
    (void*)g_dlssDepthView,
    VK_IMAGE_LAYOUT_GENERAL
);

dlssDepth.width =
    g_dlssDepthWidth;

dlssDepth.height =
    g_dlssDepthHeight;

dlssDepth.nativeFormat =
    static_cast<uint32_t>(
        VK_FORMAT_R32_SFLOAT
    );

dlssDepth.mipLevels = 1;
dlssDepth.arrayLayers = 1;
dlssDepth.flags = 0;

dlssDepth.usage =
    VK_IMAGE_USAGE_STORAGE_BIT |
    VK_IMAGE_USAGE_SAMPLED_BIT |
    VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
    VK_IMAGE_USAGE_TRANSFER_DST_BIT;


// =====================================================
// DLSS MOTION RESOURCE
// =====================================================

sl::Resource dlssMotion(
    sl::ResourceType::eTex2d,
    (void*)g_motionImage,
    (void*)g_motionMemory,
    (void*)g_motionView,
    VK_IMAGE_LAYOUT_GENERAL
);

dlssMotion.width =
    g_motionWidth;

dlssMotion.height =
    g_motionHeight;

dlssMotion.nativeFormat =
    static_cast<uint32_t>(
        VK_FORMAT_R16G16_SFLOAT
    );

dlssMotion.mipLevels = 1;
dlssMotion.arrayLayers = 1;
dlssMotion.flags = 0;

dlssMotion.usage =
    VK_IMAGE_USAGE_STORAGE_BIT |
    VK_IMAGE_USAGE_SAMPLED_BIT |
    VK_IMAGE_USAGE_TRANSFER_DST_BIT;


// =====================================================
// RR-3.3
// DLSS Ray Reconstruction Guide Resources
// =====================================================

sl::Resource rrDiffuseAlbedo(
    sl::ResourceType::eTex2d,
    (void*)g_rrDiffuseAlbedoImage,
    (void*)g_rrDiffuseAlbedoMemory,
    (void*)g_rrDiffuseAlbedoView,
    VK_IMAGE_LAYOUT_GENERAL
);

rrDiffuseAlbedo.width =
    g_rrGBufferWidth;

rrDiffuseAlbedo.height =
    g_rrGBufferHeight;

rrDiffuseAlbedo.nativeFormat =
    static_cast<uint32_t>(
        VK_FORMAT_R16G16B16A16_SFLOAT
    );

rrDiffuseAlbedo.mipLevels = 1;
rrDiffuseAlbedo.arrayLayers = 1;
rrDiffuseAlbedo.flags = 0;

rrDiffuseAlbedo.usage =
    VK_IMAGE_USAGE_STORAGE_BIT |
    VK_IMAGE_USAGE_SAMPLED_BIT |
    VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
    VK_IMAGE_USAGE_TRANSFER_DST_BIT;


// -----------------------------------------------------

sl::Resource rrSpecularAlbedo(
    sl::ResourceType::eTex2d,
    (void*)g_rrSpecularAlbedoImage,
    (void*)g_rrSpecularAlbedoMemory,
    (void*)g_rrSpecularAlbedoView,
    VK_IMAGE_LAYOUT_GENERAL
);

rrSpecularAlbedo.width =
    g_rrGBufferWidth;

rrSpecularAlbedo.height =
    g_rrGBufferHeight;

rrSpecularAlbedo.nativeFormat =
    static_cast<uint32_t>(
        VK_FORMAT_R16G16B16A16_SFLOAT
    );

rrSpecularAlbedo.mipLevels = 1;
rrSpecularAlbedo.arrayLayers = 1;
rrSpecularAlbedo.flags = 0;

rrSpecularAlbedo.usage =
    VK_IMAGE_USAGE_STORAGE_BIT |
    VK_IMAGE_USAGE_SAMPLED_BIT |
    VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
    VK_IMAGE_USAGE_TRANSFER_DST_BIT;


// -----------------------------------------------------

sl::Resource rrNormalRoughness(
    sl::ResourceType::eTex2d,
    (void*)g_rrNormalRoughnessImage,
    (void*)g_rrNormalRoughnessMemory,
    (void*)g_rrNormalRoughnessView,
    VK_IMAGE_LAYOUT_GENERAL
);

rrNormalRoughness.width =
    g_rrGBufferWidth;

rrNormalRoughness.height =
    g_rrGBufferHeight;

rrNormalRoughness.nativeFormat =
    static_cast<uint32_t>(
        VK_FORMAT_R16G16B16A16_SFLOAT
    );

rrNormalRoughness.mipLevels = 1;
rrNormalRoughness.arrayLayers = 1;
rrNormalRoughness.flags = 0;

rrNormalRoughness.usage =
    VK_IMAGE_USAGE_STORAGE_BIT |
    VK_IMAGE_USAGE_SAMPLED_BIT |
    VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
    VK_IMAGE_USAGE_TRANSFER_DST_BIT;


// -----------------------------------------------------

sl::Resource rrSpecularHitDistance(
    sl::ResourceType::eTex2d,
    (void*)g_rrSpecularHitDistanceImage,
    (void*)g_rrSpecularHitDistanceMemory,
    (void*)g_rrSpecularHitDistanceView,
    VK_IMAGE_LAYOUT_GENERAL
);

rrSpecularHitDistance.width =
    g_rrGBufferWidth;

rrSpecularHitDistance.height =
    g_rrGBufferHeight;

rrSpecularHitDistance.nativeFormat =
    static_cast<uint32_t>(
        VK_FORMAT_R32_SFLOAT
    );

rrSpecularHitDistance.mipLevels = 1;
rrSpecularHitDistance.arrayLayers = 1;
rrSpecularHitDistance.flags = 0;

rrSpecularHitDistance.usage =
    VK_IMAGE_USAGE_STORAGE_BIT |
    VK_IMAGE_USAGE_SAMPLED_BIT |
    VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
    VK_IMAGE_USAGE_TRANSFER_DST_BIT;


// =====================================================
// Existing DLSS SR tags
// =====================================================

        // =====================================================
        // RR-3.4
        // Input-resolution extent
        //
        // Dynamic-resolution inputs must all describe
        // the actual render-resolution subrect.
        // =====================================================

        sl::Extent inputExtent{};

        inputExtent.left = 0;
        inputExtent.top = 0;

        inputExtent.width =
            g_rtOutputWidth;

        inputExtent.height =
            g_rtOutputHeight;


        // Motion = render resolution
        sl::ResourceTag motionTag(
            &dlssMotion,
            sl::kBufferTypeMotionVectors,
            sl::eValidUntilEvaluate,
            &inputExtent
        );


        // Depth = render resolution
        sl::ResourceTag depthTag(
            &dlssDepth,
            sl::kBufferTypeDepth,
            sl::eValidUntilEvaluate,
            &inputExtent
        );


// =====================================================
// RR-3.3 Guide Tags
// =====================================================

sl::ResourceTag rrDiffuseAlbedoTag(
    &rrDiffuseAlbedo,
    sl::kBufferTypeAlbedo,
    sl::eValidUntilEvaluate,
    &inputExtent
);

sl::ResourceTag rrSpecularAlbedoTag(
    &rrSpecularAlbedo,
    sl::kBufferTypeSpecularAlbedo,
    sl::eValidUntilEvaluate,
    &inputExtent
);

sl::ResourceTag rrNormalRoughnessTag(
    &rrNormalRoughness,
    sl::kBufferTypeNormalRoughness,
    sl::eValidUntilEvaluate,
    &inputExtent
);

sl::ResourceTag rrSpecularHitDistanceTag(
    &rrSpecularHitDistance,
    sl::kBufferTypeSpecularHitDistance,
    sl::eValidUntilEvaluate,
    &inputExtent
);


// =====================================================
// RR-3.3 validation
// =====================================================

static bool rr33Printed =
    false;

if(!rr33Printed)
{
    const bool rrGuideSizesValid =
        g_rrGBufferWidth ==
            g_rtOutputWidth &&
        g_rrGBufferHeight ==
            g_rtOutputHeight;


    fprintf(
        stderr,
        "[RR-3.3]"
        " guides=%ux%u"
        " input=%ux%u"
        " sizeMatch=%d"
        " tags=Albedo,SpecAlbedo,NormalRoughness,SpecHitDistance\n",

        g_rrGBufferWidth,
        g_rrGBufferHeight,

        g_rtOutputWidth,
        g_rtOutputHeight,

        rrGuideSizesValid
            ? 1
            : 0
    );

    fflush(stderr);

    rr33Printed =
        true;
}


// =====================================================
// Output-resolution extent
// =====================================================

sl::Extent outputExtent{};

outputExtent.left = 0;
outputExtent.top = 0;

outputExtent.width =
    g_dlssOutputWidth;

outputExtent.height =
    g_dlssOutputHeight;


// =====================================================
// Existing DLSS SR color tags
// =====================================================

sl::ResourceTag inputTag(
    &dlssInput,
    sl::kBufferTypeScalingInputColor,
    sl::eValidUntilEvaluate,
    &inputExtent
);

sl::ResourceTag outputTag(
    &dlssOutput,
    sl::kBufferTypeScalingOutputColor,
    sl::eValidUntilEvaluate,
    &outputExtent
);


// =====================================================
// Existing DLSS SR inputs
//
// IMPORTANT:
// RR guides are intentionally NOT inserted here yet.
// =====================================================

        // =====================================================
        // RR-4.1
        // First real DLSS Ray Reconstruction Evaluate
        // =====================================================

        const sl::BaseStructure* inputs[] =
        {
            &inputTag, &outputTag, &depthTag, &motionTag,
            &rrDiffuseAlbedoTag, &rrSpecularAlbedoTag,
            &rrNormalRoughnessTag, &rrSpecularHitDistanceTag,
            &g_dlssViewport
        };
        const uint32_t inputCount = useSR ? 5u : 9u;


        sl::CommandBuffer* slCmd =
            reinterpret_cast<sl::CommandBuffer*>(cmd);


        if(
            ((!useSR && !g_dlssRRReady) || (useSR && !g_dlssReady)) ||
            !g_slEvaluateFeature ||
            rrSetOptionsResult != sl::Result::eOk
        )
        {
            static bool hasLoggedRRSkip = false;
            static int lastRRSkipReady = -1;
            static int lastRRSkipEvaluate = -1;
            static int lastRRSkipSetOptions = std::numeric_limits<int>::min();
            const int ready = g_dlssRRReady ? 1 : 0;
            const int evaluate = g_slEvaluateFeature ? 1 : 0;
            const int setOptions = static_cast<int>(rrSetOptionsResult);
            if(!hasLoggedRRSkip ||
               ready != lastRRSkipReady ||
               evaluate != lastRRSkipEvaluate ||
               setOptions != lastRRSkipSetOptions)
            {
                fprintf(
                    stderr,
                    "[DLSS-4.1] SKIP ready=%d evaluate=%d setOptions=%d\n",
                    ready,
                    evaluate,
                    setOptions
                );
                hasLoggedRRSkip = true;
                lastRRSkipReady = ready;
                lastRRSkipEvaluate = evaluate;
                lastRRSkipSetOptions = setOptions;
            }

            return;
        }


        sl::Result dlssResult =
            g_slEvaluateFeature(
                useSR ? sl::kFeatureDLSS : sl::kFeatureDLSS_RR,
                *frameToken,
                inputs,
                inputCount,
                slCmd
            );


        // Keep evaluation telemetry useful without turning the render thread
        // into a synchronous stderr writer. Log the first result, state
        // changes, and a low-frequency heartbeat only.
        static bool hasLoggedRREvaluateResult = false;
        static sl::Result lastRREvaluateResult =
            sl::Result::eErrorFeatureNotSupported;
        if(!hasLoggedRREvaluateResult ||
           dlssResult != lastRREvaluateResult ||
           (g_perfL10FrameSerial % 120u) == 0u)
        {
            fprintf(
                stderr,
                "[DLSS-4.1] Evaluate result=%d render=%ux%u output=%ux%u\n",
                static_cast<int>(dlssResult),
                g_rtOutputWidth,
                g_rtOutputHeight,
                g_dlssOutputWidth,
                g_dlssOutputHeight
            );
            hasLoggedRREvaluateResult = true;
            lastRREvaluateResult = dlssResult;
        }


        // =====================================================
        // RR-4.2
        // Restore Vulkan command-buffer state after
        // Streamline Evaluate.
        //
        // Manual Hooking requires the host to restore
        // pipeline + descriptor-set state modified by SL.
        // Immediately before Evaluate, our active state was
        // the RT pipeline and its descriptor set.
        // =====================================================

        vkCmdBindPipeline(
            cmd,
            VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
            g_rayTracingPipeline
        );


        vkCmdBindDescriptorSets(
            cmd,
            VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
            g_rayTracingPipelineLayout,
            0,
            1,
            &g_rayTracingDescriptorSet,
            0,
            nullptr
        );


        static bool rr42RestorePrinted =
            false;


        if(!rr42RestorePrinted)
        {
            fprintf(
                stderr,
                "[RR-4.2]"
                " Vulkan state restored"
                " pipeline=RT"
                " descriptors=1\n"
            );

            fflush(stderr);

            rr42RestorePrinted =
                true;
        }


        if(dlssResult != sl::Result::eOk)
        {
            fprintf(
                stderr,
                "[DLSS] Evaluate FAILED result=%d (0x%X)\n",
                static_cast<int>(dlssResult),
                static_cast<unsigned int>(dlssResult)
            );

            return;
        }


        g_prevCameraViewToWorld =
            currentCameraViewToWorld;

        g_prevCameraViewToClip =
            currentCameraViewToClip;

        g_hasPrevCameraMatrices =
            true;


// 我们自己的状态追踪必须仍然认为 DLSS output 是 GENERAL。
// 新创建时在 Evaluate 前已经转成 GENERAL，
// 每帧 blit 完以后也会恢复 GENERAL。
if(g_dlssOutputLayout != VK_IMAGE_LAYOUT_GENERAL)
{
    return;
}
        // =====================================================
// RR-6.5.2
// RR linear HDR output -> old SDR display transform
//
// In-place:
// g_dlssOutputImage GENERAL
//     -> compute pow(1/2.2)
//     -> still GENERAL
// =====================================================

if(
    g_sdrPostPipeline ==
        VK_NULL_HANDLE ||

    g_sdrPostPipelineLayout ==
        VK_NULL_HANDLE ||

    g_sdrPostDescriptorSet ==
        VK_NULL_HANDLE
)
{
    fprintf(
        stderr,
        "[RR-6.5.2] SDR post unavailable\n"
    );

    return;
}


// Streamline write -> compute read/write
VkImageMemoryBarrier rrToSDRPost{};

rrToSDRPost.sType =
    VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;

rrToSDRPost.oldLayout =
    VK_IMAGE_LAYOUT_GENERAL;

rrToSDRPost.newLayout =
    VK_IMAGE_LAYOUT_GENERAL;

rrToSDRPost.srcQueueFamilyIndex =
    VK_QUEUE_FAMILY_IGNORED;

rrToSDRPost.dstQueueFamilyIndex =
    VK_QUEUE_FAMILY_IGNORED;

rrToSDRPost.srcAccessMask =
    VK_ACCESS_MEMORY_WRITE_BIT;

rrToSDRPost.dstAccessMask =
    VK_ACCESS_SHADER_READ_BIT |
    VK_ACCESS_SHADER_WRITE_BIT;

rrToSDRPost.image =
    g_dlssOutputImage;

rrToSDRPost.subresourceRange.aspectMask =
    VK_IMAGE_ASPECT_COLOR_BIT;

rrToSDRPost.subresourceRange.baseMipLevel = 0;
rrToSDRPost.subresourceRange.levelCount = 1;

rrToSDRPost.subresourceRange.baseArrayLayer = 0;
rrToSDRPost.subresourceRange.layerCount = 1;


vkCmdPipelineBarrier(
    cmd,

    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,

    0,

    0,
    nullptr,

    0,
    nullptr,

    1,
    &rrToSDRPost
);


vkCmdBindPipeline(
    cmd,
    VK_PIPELINE_BIND_POINT_COMPUTE,
    g_sdrPostPipeline
);


vkCmdBindDescriptorSets(
    cmd,

    VK_PIPELINE_BIND_POINT_COMPUTE,

    g_sdrPostPipelineLayout,

    0,
    1,
    &g_sdrPostDescriptorSet,

    0,
    nullptr
);


vkCmdDispatch(
    cmd,

    (
        g_dlssOutputWidth +
        7
    ) / 8,

    (
        g_dlssOutputHeight +
        7
    ) / 8,

    1
);


static bool rr65PostPrinted =
    false;


if(!rr65PostPrinted)
{
    fprintf(
        stderr,
        "[RR-6.5.2]"
        " postSDR=pow(1/2.2)"
        " size=%ux%u\n",

        g_dlssOutputWidth,
        g_dlssOutputHeight
    );

    fflush(stderr);

    rr65PostPrinted =
        true;
}


// -----------------------------------------------------
// 1. DLSS output:
//
// GENERAL
// -> TRANSFER_SRC_OPTIMAL
//
// 同时给 Minecraft 前一个 swapchain blit 建立
// TRANSFER_WRITE -> TRANSFER_WRITE 依赖。
// swap 的 layout 不变，仍然是 TRANSFER_DST_OPTIMAL。
// -----------------------------------------------------

VkImageMemoryBarrier dlssToTransfer{};

dlssToTransfer.sType =
    VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;

dlssToTransfer.oldLayout =
    VK_IMAGE_LAYOUT_GENERAL;

dlssToTransfer.newLayout =
    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;

dlssToTransfer.srcQueueFamilyIndex =
    VK_QUEUE_FAMILY_IGNORED;

dlssToTransfer.dstQueueFamilyIndex =
    VK_QUEUE_FAMILY_IGNORED;

dlssToTransfer.srcAccessMask =
    VK_ACCESS_MEMORY_WRITE_BIT;

dlssToTransfer.dstAccessMask =
    VK_ACCESS_TRANSFER_READ_BIT;

dlssToTransfer.image =
    g_dlssOutputImage;

dlssToTransfer.subresourceRange.aspectMask =
    VK_IMAGE_ASPECT_COLOR_BIT;

dlssToTransfer.subresourceRange.baseMipLevel = 0;
dlssToTransfer.subresourceRange.levelCount = 1;

dlssToTransfer.subresourceRange.baseArrayLayer = 0;
dlssToTransfer.subresourceRange.layerCount = 1;



        VkImageMemoryBarrier targetOverwriteBarrier{};

        targetOverwriteBarrier.sType =
            VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;


        // Minecraft main RenderTarget 全程使用 GENERAL。
        // 原 swapchain 路径仍然保持 TRANSFER_DST_OPTIMAL。
        const VkImageLayout targetLayout =
            targetIsMain
                ? VK_IMAGE_LAYOUT_GENERAL
                : VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;


        targetOverwriteBarrier.oldLayout =
            targetLayout;

        targetOverwriteBarrier.newLayout =
            targetLayout;


        targetOverwriteBarrier.srcQueueFamilyIndex =
            VK_QUEUE_FAMILY_IGNORED;

        targetOverwriteBarrier.dstQueueFamilyIndex =
            VK_QUEUE_FAMILY_IGNORED;


        targetOverwriteBarrier.srcAccessMask =
            targetIsMain
                ? (
                    VK_ACCESS_MEMORY_WRITE_BIT |
                    VK_ACCESS_TRANSFER_READ_BIT
                  )
                : VK_ACCESS_TRANSFER_WRITE_BIT;


        targetOverwriteBarrier.dstAccessMask =
            VK_ACCESS_TRANSFER_WRITE_BIT;


        targetOverwriteBarrier.image =
            target;


        targetOverwriteBarrier.subresourceRange.aspectMask =
            VK_IMAGE_ASPECT_COLOR_BIT;

        targetOverwriteBarrier.subresourceRange.baseMipLevel =
            0;

        targetOverwriteBarrier.subresourceRange.levelCount =
            1;

        targetOverwriteBarrier.subresourceRange.baseArrayLayer =
            0;

        targetOverwriteBarrier.subresourceRange.layerCount =
            1;


        VkImageMemoryBarrier dlssPresentBarriers[2] =
        {
            dlssToTransfer,
            targetOverwriteBarrier
        };


vkCmdPipelineBarrier(
    cmd,

    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,

    VK_PIPELINE_STAGE_TRANSFER_BIT,

    0,

    0,
    nullptr,

    0,
    nullptr,

    2,
    dlssPresentBarriers
);


g_dlssOutputLayout =
    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;

// -----------------------------------------------------
// 2. DLSS final-resolution image -> swapchain
// -----------------------------------------------------

VkImageBlit blit{};


blit.srcSubresource.aspectMask =
    VK_IMAGE_ASPECT_COLOR_BIT;

blit.srcSubresource.mipLevel = 0;
blit.srcSubresource.baseArrayLayer = 0;
blit.srcSubresource.layerCount = 1;

blit.srcOffsets[0] =
{
    0,
    0,
    0
};

blit.srcOffsets[1] =
{
    static_cast<int32_t>(g_dlssOutputWidth),
    static_cast<int32_t>(g_dlssOutputHeight),
    1
};


blit.dstSubresource.aspectMask =
    VK_IMAGE_ASPECT_COLOR_BIT;

blit.dstSubresource.mipLevel = 0;
blit.dstSubresource.baseArrayLayer = 0;
blit.dstSubresource.layerCount = 1;

        if(targetIsMain)
        {
            // Minecraft 最终 mainTarget -> swapchain
            // 本身还会再做一次 Y flip。
            // 所以写入 main target 时先反一次，
            // 最终屏幕方向保持和当前 RT 完全一致。

            blit.dstOffsets[0] =
            {
                0,
                static_cast<int32_t>(outputHeight),
                0
            };

            blit.dstOffsets[1] =
            {
                static_cast<int32_t>(outputWidth),
                0,
                1
            };
        }
        else
        {
            // 保留原来的直接 swapchain 路径。

            blit.dstOffsets[0] =
            {
                0,
                0,
                0
            };

            blit.dstOffsets[1] =
            {
                static_cast<int32_t>(outputWidth),
                static_cast<int32_t>(outputHeight),
                1
            };
        }
        vkCmdBlitImage(
            cmd,

            g_dlssOutputImage,
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,

            target,
            targetLayout,

            1,
            &blit,

            VK_FILTER_NEAREST
        );

        // =====================================================
// FG-4.1
// Capture HUD-less color.
//
// At targetIsMain:
// RR + SDR result has just been written to Minecraft's
// main render target.
//
// GUI rendering has NOT started yet.
// =====================================================

if(
    targetIsMain &&

    g_currentSLFrameValid &&

    ensureFGUIResources(
        outputWidth,
        outputHeight
    )
)
{
    VkImageMemoryBarrier
        hudlessBarriers[2]{};


    // main target:
    // transfer write -> transfer read
    //
    // Layout remains GENERAL.
    hudlessBarriers[0].sType =
        VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;

    hudlessBarriers[0].oldLayout =
        VK_IMAGE_LAYOUT_GENERAL;

    hudlessBarriers[0].newLayout =
        VK_IMAGE_LAYOUT_GENERAL;

    hudlessBarriers[0].srcQueueFamilyIndex =
        VK_QUEUE_FAMILY_IGNORED;

    hudlessBarriers[0].dstQueueFamilyIndex =
        VK_QUEUE_FAMILY_IGNORED;

    hudlessBarriers[0].srcAccessMask =
        VK_ACCESS_TRANSFER_WRITE_BIT;

    hudlessBarriers[0].dstAccessMask =
        VK_ACCESS_TRANSFER_READ_BIT;

    hudlessBarriers[0].image =
        target;

    hudlessBarriers[0].subresourceRange.aspectMask =
        VK_IMAGE_ASPECT_COLOR_BIT;

    hudlessBarriers[0].subresourceRange.baseMipLevel =
        0;

    hudlessBarriers[0].subresourceRange.levelCount =
        1;

    hudlessBarriers[0].subresourceRange.baseArrayLayer =
        0;

    hudlessBarriers[0].subresourceRange.layerCount =
        1;


    // HUD-less snapshot:
    // undefined / previous sampled
    // -> transfer destination
    hudlessBarriers[1].sType =
        VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;

    hudlessBarriers[1].oldLayout =
        g_fgHudlessLayout;

    hudlessBarriers[1].newLayout =
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;

    hudlessBarriers[1].srcQueueFamilyIndex =
        VK_QUEUE_FAMILY_IGNORED;

    hudlessBarriers[1].dstQueueFamilyIndex =
        VK_QUEUE_FAMILY_IGNORED;

    hudlessBarriers[1].srcAccessMask =
        g_fgHudlessLayout ==
            VK_IMAGE_LAYOUT_UNDEFINED
                ? 0
                : VK_ACCESS_SHADER_READ_BIT;

    hudlessBarriers[1].dstAccessMask =
        VK_ACCESS_TRANSFER_WRITE_BIT;

    hudlessBarriers[1].image =
        g_fgHudlessImage;

    hudlessBarriers[1].subresourceRange.aspectMask =
        VK_IMAGE_ASPECT_COLOR_BIT;

    hudlessBarriers[1].subresourceRange.baseMipLevel =
        0;

    hudlessBarriers[1].subresourceRange.levelCount =
        1;

    hudlessBarriers[1].subresourceRange.baseArrayLayer =
        0;

    hudlessBarriers[1].subresourceRange.layerCount =
        1;


    vkCmdPipelineBarrier(
        cmd,

        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT,

        0,

        0,
        nullptr,

        0,
        nullptr,

        2,
        hudlessBarriers
    );


    g_fgHudlessLayout =
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;


    VkImageBlit
        hudlessBlit{};


    hudlessBlit.srcSubresource.aspectMask =
        VK_IMAGE_ASPECT_COLOR_BIT;

    hudlessBlit.srcSubresource.mipLevel =
        0;

    hudlessBlit.srcSubresource.baseArrayLayer =
        0;

    hudlessBlit.srcSubresource.layerCount =
        1;


    hudlessBlit.srcOffsets[0] =
    {
        0,
        0,
        0
    };

    hudlessBlit.srcOffsets[1] =
    {
        static_cast<int32_t>(
            outputWidth
        ),

        static_cast<int32_t>(
            outputHeight
        ),

        1
    };


    hudlessBlit.dstSubresource.aspectMask =
        VK_IMAGE_ASPECT_COLOR_BIT;

    hudlessBlit.dstSubresource.mipLevel =
        0;

    hudlessBlit.dstSubresource.baseArrayLayer =
        0;

    hudlessBlit.dstSubresource.layerCount =
        1;


    // Same Y flip as the final Minecraft
    // mainTarget -> swapchain blit.
    hudlessBlit.dstOffsets[0] =
    {
        0,

        static_cast<int32_t>(
            outputHeight
        ),

        0
    };

    hudlessBlit.dstOffsets[1] =
    {
        static_cast<int32_t>(
            outputWidth
        ),

        0,

        1
    };


    vkCmdBlitImage(
        cmd,

        target,
        VK_IMAGE_LAYOUT_GENERAL,

        g_fgHudlessImage,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,

        1,
        &hudlessBlit,

        VK_FILTER_NEAREST
    );


    VkImageMemoryBarrier
        hudlessToSample{};

    hudlessToSample.sType =
        VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;

    hudlessToSample.oldLayout =
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;

    hudlessToSample.newLayout =
        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

    hudlessToSample.srcQueueFamilyIndex =
        VK_QUEUE_FAMILY_IGNORED;

    hudlessToSample.dstQueueFamilyIndex =
        VK_QUEUE_FAMILY_IGNORED;

    hudlessToSample.srcAccessMask =
        VK_ACCESS_TRANSFER_WRITE_BIT;

    hudlessToSample.dstAccessMask =
        VK_ACCESS_SHADER_READ_BIT;

    hudlessToSample.image =
        g_fgHudlessImage;

    hudlessToSample.subresourceRange.aspectMask =
        VK_IMAGE_ASPECT_COLOR_BIT;

    hudlessToSample.subresourceRange.baseMipLevel =
        0;

    hudlessToSample.subresourceRange.levelCount =
        1;

    hudlessToSample.subresourceRange.baseArrayLayer =
        0;

    hudlessToSample.subresourceRange.layerCount =
        1;


    vkCmdPipelineBarrier(
        cmd,

        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,

        0,

        0,
        nullptr,

        0,
        nullptr,

        1,
        &hudlessToSample
    );


    g_fgHudlessLayout =
        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

    g_fgHudlessFrameIndex =
        g_currentSLFrameIndex;


    static uint32_t
        lastHudlessWidth =
            0;

    static uint32_t
        lastHudlessHeight =
            0;


    if(
        lastHudlessWidth !=
            outputWidth ||

        lastHudlessHeight !=
            outputHeight
    )
    {
        fprintf(
            stderr,
            "[FG-4.1 HUDLESS]"
            " frame=%u"
            " size=%ux%u\n",

            g_currentSLFrameIndex,

            outputWidth,
            outputHeight
        );

        fflush(stderr);


        lastHudlessWidth =
            outputWidth;

        lastHudlessHeight =
            outputHeight;
    }
}

// -----------------------------------------------------
// 3. DLSS output 恢复为 GENERAL
//
// 下一帧 Streamline Resource 仍然声明 GENERAL。
// -----------------------------------------------------

VkImageMemoryBarrier dlssBack{};

dlssBack.sType =
    VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;

dlssBack.oldLayout =
    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;

dlssBack.newLayout =
    VK_IMAGE_LAYOUT_GENERAL;

dlssBack.srcQueueFamilyIndex =
    VK_QUEUE_FAMILY_IGNORED;

dlssBack.dstQueueFamilyIndex =
    VK_QUEUE_FAMILY_IGNORED;

dlssBack.srcAccessMask =
    VK_ACCESS_TRANSFER_READ_BIT;

dlssBack.dstAccessMask =
    VK_ACCESS_MEMORY_READ_BIT |
    VK_ACCESS_MEMORY_WRITE_BIT;

dlssBack.image =
    g_dlssOutputImage;

dlssBack.subresourceRange.aspectMask =
    VK_IMAGE_ASPECT_COLOR_BIT;

dlssBack.subresourceRange.baseMipLevel = 0;
dlssBack.subresourceRange.levelCount = 1;

dlssBack.subresourceRange.baseArrayLayer = 0;
dlssBack.subresourceRange.layerCount = 1;


vkCmdPipelineBarrier(
    cmd,

    VK_PIPELINE_STAGE_TRANSFER_BIT,

    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,

    0,

    0,
    nullptr,

    0,
    nullptr,

    1,
    &dlssBack
);
        // =====================================================
        // Main RenderTarget:
        // DLSS transfer write -> upcoming Minecraft GUI draw
        //
        // Layout remains GENERAL.
        // =====================================================

        if(targetIsMain)
        {
            VkImageMemoryBarrier targetToGui{};

            targetToGui.sType =
                VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;

            targetToGui.oldLayout =
                VK_IMAGE_LAYOUT_GENERAL;

            targetToGui.newLayout =
                VK_IMAGE_LAYOUT_GENERAL;

            targetToGui.srcQueueFamilyIndex =
                VK_QUEUE_FAMILY_IGNORED;

            targetToGui.dstQueueFamilyIndex =
                VK_QUEUE_FAMILY_IGNORED;

            targetToGui.srcAccessMask =
                VK_ACCESS_TRANSFER_READ_BIT |
                VK_ACCESS_TRANSFER_WRITE_BIT;

            targetToGui.dstAccessMask =
                VK_ACCESS_COLOR_ATTACHMENT_READ_BIT |
                VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

            targetToGui.image =
                target;

            targetToGui.subresourceRange.aspectMask =
                VK_IMAGE_ASPECT_COLOR_BIT;

            targetToGui.subresourceRange.baseMipLevel =
                0;

            targetToGui.subresourceRange.levelCount =
                1;

            targetToGui.subresourceRange.baseArrayLayer =
                0;

            targetToGui.subresourceRange.layerCount =
                1;


            vkCmdPipelineBarrier(
                cmd,

                VK_PIPELINE_STAGE_TRANSFER_BIT,

                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,

                0,

                0,
                nullptr,

                0,
                nullptr,

                1,
                &targetToGui
            );
        }


g_dlssOutputLayout =
    VK_IMAGE_LAYOUT_GENERAL;

    return;
}
}


extern "C"
JNIEXPORT void JNICALL Java_com_example_vulkan_NativeVulkan_testFrame(JNIEnv*,jclass){
    // std::cout<<"[Native C++] FRAME CALLBACK WORKING"<<std::endl;
}
extern "C"
JNIEXPORT void JNICALL Java_com_example_vulkan_NativeVulkan_cleanup(JNIEnv*,jclass){
    if(g_device){
        FGDeviceWaitIdle(g_device);

        destroyFrameCompletionFences();
        destroyPerfL10RtTimestampPool();

        releaseDeferredSectionBuildResources();

        // P1-1
        destroyFarRasterSnapshot();

        if(g_rtCommandPool)
        {
            vkDestroyCommandPool(
                g_device,
                g_rtCommandPool,
                nullptr
            );
            g_rtCommandPool=VK_NULL_HANDLE;
        }
        if(g_rtOutputView){vkDestroyImageView(g_device,g_rtOutputView,nullptr);g_rtOutputView=VK_NULL_HANDLE;}
        if(g_rtOutputImage){vkDestroyImage(g_device,g_rtOutputImage,nullptr);g_rtOutputImage=VK_NULL_HANDLE;}
        if(g_rtOutputMemory){vkFreeMemory(g_device,g_rtOutputMemory,nullptr);g_rtOutputMemory=VK_NULL_HANDLE;}


        // Perf-1.2: custom temporal history cleanup removed.

        // =====================================================
        // DLSS Ray Reconstruction GBuffer cleanup
        // =====================================================

        destroyHistoryImage(
            g_rrDiffuseAlbedoImage,
            g_rrDiffuseAlbedoMemory,
            g_rrDiffuseAlbedoView,
            g_rrDiffuseAlbedoLayout
        );


        destroyHistoryImage(
            g_rrSpecularAlbedoImage,
            g_rrSpecularAlbedoMemory,
            g_rrSpecularAlbedoView,
            g_rrSpecularAlbedoLayout
        );


        destroyHistoryImage(
            g_rrNormalRoughnessImage,
            g_rrNormalRoughnessMemory,
            g_rrNormalRoughnessView,
            g_rrNormalRoughnessLayout
        );


        destroyHistoryImage(
            g_rrSpecularHitDistanceImage,
            g_rrSpecularHitDistanceMemory,
            g_rrSpecularHitDistanceView,
            g_rrSpecularHitDistanceLayout
        );


        g_rrGBufferWidth =
            0;

        g_rrGBufferHeight =
            0;


        if(g_motionView)
        {
            vkDestroyImageView(
                g_device,
                g_motionView,
                nullptr
            );

            g_motionView =
                VK_NULL_HANDLE;
        }


        if(g_motionImage)
        {
            vkDestroyImage(
                g_device,
                g_motionImage,
                nullptr
            );

            g_motionImage =
                VK_NULL_HANDLE;
        }


        if(g_motionMemory)
        {
            vkFreeMemory(
                g_device,
                g_motionMemory,
                nullptr
            );

            g_motionMemory =
                VK_NULL_HANDLE;
        }


        g_motionWidth = 0;
        g_motionHeight = 0;

        g_motionLayout =
            VK_IMAGE_LAYOUT_UNDEFINED;


        if(g_dlssDepthView)
        {
            vkDestroyImageView(
                g_device,
                g_dlssDepthView,
                nullptr
            );

            g_dlssDepthView =
                VK_NULL_HANDLE;
        }


        if(g_dlssDepthImage)
        {
            vkDestroyImage(
                g_device,
                g_dlssDepthImage,
                nullptr
            );

            g_dlssDepthImage =
                VK_NULL_HANDLE;
        }


        if(g_dlssDepthMemory)
        {
            vkFreeMemory(
                g_device,
                g_dlssDepthMemory,
                nullptr
            );

            g_dlssDepthMemory =
                VK_NULL_HANDLE;
        }


        g_dlssDepthWidth = 0;
        g_dlssDepthHeight = 0;

        g_dlssDepthLayout =
            VK_IMAGE_LAYOUT_UNDEFINED;
        if(g_dlssOutputView)
        {
            vkDestroyImageView(
                g_device,
                g_dlssOutputView,
                nullptr
            );

            g_dlssOutputView =
                VK_NULL_HANDLE;
        }


        if(g_dlssOutputImage)
        {
            vkDestroyImage(
                g_device,
                g_dlssOutputImage,
                nullptr
            );

            g_dlssOutputImage =
                VK_NULL_HANDLE;
        }


        if(g_dlssOutputMemory)
        {
            vkFreeMemory(
                g_device,
                g_dlssOutputMemory,
                nullptr
            );

            g_dlssOutputMemory =
                VK_NULL_HANDLE;
        }


        g_dlssOutputWidth = 0;
        g_dlssOutputHeight = 0;

        g_dlssOutputLayout =
            VK_IMAGE_LAYOUT_UNDEFINED;


        // RT Local Lights: release both the aggregate-light buffer
        // and the P2B-1 spatial-index buffer before destroying the
        // descriptor pool/layout that reference them.
        destroyVPTDirectHistoryBuffers();
        destroyReSTIRDIReservoirBuffers();
        destroyVLIResources();
        destroyLocalLightGridGpuBuffer();
        destroyLocalLightGpuBuffer();
        destroySectionVertexAddressBuffer();


        destroyVolumetricCloudResources();
        destroyCloudNoiseResources();
        destroyWaterDetailNormalResources();
        destroyWaterCausticResources();
        destroyWaterSimulationResources();

        if(g_rayTracingDescriptorPool){vkDestroyDescriptorPool(g_device,g_rayTracingDescriptorPool,nullptr);}

        // === 注入点：在此处销毁 UBO 并释放内存 ===
        if(g_cameraUboBuffer) {
            vkDestroyBuffer(g_device, g_cameraUboBuffer, nullptr);
            g_cameraUboBuffer = VK_NULL_HANDLE;
        }
        if(g_cameraUboMemory) {
            vkUnmapMemory(g_device, g_cameraUboMemory);
            vkFreeMemory(g_device, g_cameraUboMemory, nullptr);
            g_cameraUboMemory = VK_NULL_HANDLE;
            g_cameraUboMapped = nullptr;
        }

        auto fn = getDestroyAccelerationStructure();
        if(g_tlas&&fn) fn(g_device,g_tlas,nullptr);
        g_tlas = VK_NULL_HANDLE;

        for(auto& entry : g_sectionBLAS)
        {
            destroySectionAS(
                entry.second
            );
        }

        g_sectionBLAS.clear();

        if(g_blas&&fn) fn(g_device,g_blas,nullptr);
        g_blas = VK_NULL_HANDLE;
        if(g_tlasScratchBuffer)vkDestroyBuffer(g_device,g_tlasScratchBuffer,nullptr);
        if(g_tlasScratchMemory)vkFreeMemory(g_device,g_tlasScratchMemory,nullptr);
        if(g_tlasInstanceBuffer)vkDestroyBuffer(g_device,g_tlasInstanceBuffer,nullptr);
        if(g_tlasInstanceMemory)vkFreeMemory(g_device,g_tlasInstanceMemory,nullptr);
        if(g_tlasInstancesBuffer)vkDestroyBuffer(g_device,g_tlasInstancesBuffer,nullptr);
        if(g_tlasInstancesMemory)vkFreeMemory(g_device,g_tlasInstancesMemory,nullptr);
        if(g_tlasBuffer)vkDestroyBuffer(g_device,g_tlasBuffer,nullptr);
        if(g_tlasMemory)vkFreeMemory(g_device,g_tlasMemory,nullptr);
        if(g_blasScratchBuffer)vkDestroyBuffer(g_device,g_blasScratchBuffer,nullptr);
        if(g_blasScratchMemory)vkFreeMemory(g_device,g_blasScratchMemory,nullptr);
        if(g_blasBuffer)vkDestroyBuffer(g_device,g_blasBuffer,nullptr);
        if(g_blasMemory)vkFreeMemory(g_device,g_blasMemory,nullptr);
        if(g_testVertexBuffer)vkDestroyBuffer(g_device,g_testVertexBuffer,nullptr);
        if(g_testVertexMemory)vkFreeMemory(g_device,g_testVertexMemory,nullptr);
        if(g_sbtBuffer)vkDestroyBuffer(g_device,g_sbtBuffer,nullptr);
        if(g_sbtMemory)vkFreeMemory(g_device,g_sbtMemory,nullptr);
        destroyReSTIRDIReconstructPipeline();
        destroyReSTIRDISpatialPipeline();
        if(g_rayTracingPipeline)vkDestroyPipeline(g_device,g_rayTracingPipeline,nullptr);
        if(g_rayTracingPipelineLayout)vkDestroyPipelineLayout(g_device,g_rayTracingPipelineLayout,nullptr);
        if(g_rayTracingDescriptorSetLayout)vkDestroyDescriptorSetLayout(g_device,g_rayTracingDescriptorSetLayout,nullptr);
        if(g_sdrPostPipeline)
        {
            vkDestroyPipeline(
                g_device,
                g_sdrPostPipeline,
                nullptr
            );

            g_sdrPostPipeline =
                VK_NULL_HANDLE;

        }


        if(g_sdrPostPipelineLayout)
        {
            vkDestroyPipelineLayout(
                g_device,
                g_sdrPostPipelineLayout,
                nullptr
            );

            g_sdrPostPipelineLayout =
                VK_NULL_HANDLE;
        }


        if(g_sdrPostDescriptorPool)
        {
            vkDestroyDescriptorPool(
                g_device,
                g_sdrPostDescriptorPool,
                nullptr
            );

            g_sdrPostDescriptorPool =
                VK_NULL_HANDLE;

            g_sdrPostDescriptorSet =
                VK_NULL_HANDLE;
        }


        if(g_sdrPostDescriptorSetLayout)
        {
            vkDestroyDescriptorSetLayout(
                g_device,
                g_sdrPostDescriptorSetLayout,
                nullptr
            );

            g_sdrPostDescriptorSetLayout =
                VK_NULL_HANDLE;
        }
        // =====================================================
        // FG-4.1 cleanup
        // =====================================================

        if(g_fgUIAlphaPipeline != VK_NULL_HANDLE)
        {
            vkDestroyPipeline(
                g_device,
                g_fgUIAlphaPipeline,
                nullptr
            );

            g_fgUIAlphaPipeline =
                VK_NULL_HANDLE;
        }


        if(g_fgUIAlphaPipelineLayout != VK_NULL_HANDLE)
        {
            vkDestroyPipelineLayout(
                g_device,
                g_fgUIAlphaPipelineLayout,
                nullptr
            );

            g_fgUIAlphaPipelineLayout =
                VK_NULL_HANDLE;
        }


        if(g_fgUIAlphaDescriptorPool != VK_NULL_HANDLE)
        {
            vkDestroyDescriptorPool(
                g_device,
                g_fgUIAlphaDescriptorPool,
                nullptr
            );

            g_fgUIAlphaDescriptorPool =
                VK_NULL_HANDLE;

            g_fgUIAlphaDescriptorSet =
                VK_NULL_HANDLE;
        }


        if(g_fgUIAlphaDescriptorSetLayout != VK_NULL_HANDLE)
        {
            vkDestroyDescriptorSetLayout(
                g_device,
                g_fgUIAlphaDescriptorSetLayout,
                nullptr
            );

            g_fgUIAlphaDescriptorSetLayout =
                VK_NULL_HANDLE;
        }


        if(g_fgUISnapshotSampler != VK_NULL_HANDLE)
        {
            vkDestroySampler(
                g_device,
                g_fgUISnapshotSampler,
                nullptr
            );

            g_fgUISnapshotSampler =
                VK_NULL_HANDLE;
        }


        destroyFGUIImages();


        g_fgBackbufferFormat =
            VK_FORMAT_UNDEFINED;

        g_fgSnapshotFormat =
            VK_FORMAT_UNDEFINED;

        g_fgUIWidth =
            0;

        g_fgUIHeight =
            0;

        g_fgHudlessFrameIndex =
            UINT32_MAX;

        g_fgUIAlphaFrameIndex =
            UINT32_MAX;
    }
    g_rtOutputWidth=g_rtOutputHeight=0;g_rtOutputLayout=VK_IMAGE_LAYOUT_UNDEFINED;
    g_rayTracingDescriptorPool=VK_NULL_HANDLE;g_rayTracingDescriptorSet=VK_NULL_HANDLE;
    g_tlasScratchBuffer=VK_NULL_HANDLE;g_tlasScratchMemory=VK_NULL_HANDLE;g_tlasScratchCapacity=0;g_tlasInstanceCount=0;g_tlasInstanceBuffer=VK_NULL_HANDLE;g_tlasInstanceMemory=VK_NULL_HANDLE;g_tlasBuffer=VK_NULL_HANDLE;g_tlasMemory=VK_NULL_HANDLE;
    g_tlasInstancesBuffer=VK_NULL_HANDLE;g_tlasInstancesMemory=VK_NULL_HANDLE;g_tlasInstancesAddress=0;
    g_blasScratchBuffer=VK_NULL_HANDLE;g_blasScratchMemory=VK_NULL_HANDLE;g_blasBuffer=VK_NULL_HANDLE;g_blasMemory=VK_NULL_HANDLE;
    g_blasDeviceAddress=g_blasScratchAddress=g_tlasDeviceAddress=g_tlasScratchAddress=g_tlasInstanceDeviceAddress=0;
    g_testVertexBuffer=VK_NULL_HANDLE;g_testVertexMemory=VK_NULL_HANDLE;g_sbtBuffer=VK_NULL_HANDLE;g_sbtMemory=VK_NULL_HANDLE;g_rayTracingPipeline=VK_NULL_HANDLE;g_rayTracingPipelineLayout=VK_NULL_HANDLE;g_rayTracingDescriptorSetLayout=VK_NULL_HANDLE;g_queue=VK_NULL_HANDLE;g_queueFamily=UINT32_MAX;
    g_testVertexDeviceAddress=0;g_sbtDeviceAddress=0;g_sbtSize=0;g_raygenOffset=0;g_hitOffset=0;g_missOffset=0;g_handleSizeAligned=0;
    g_sectionVertexAddressBuffer=VK_NULL_HANDLE;g_sectionVertexAddressMemory=VK_NULL_HANDLE;g_sectionVertexAddressCapacity=0;

    clearVLISections();

    {
        std::lock_guard<std::mutex> lock(g_chunkMutex);
        g_chunkMeshes.clear();
        g_chunkRawPayloads.clear();
        g_cutoutOmmInputs.clear();
        g_dirtySectionKeys.clear();
        g_forceSectionASReset = false;
        g_worldGeometryDirty.store(false, std::memory_order_release);
    }
    g_asBuilt =
        false;
    g_nextSectionRebuildFrame = 0u;
    g_firstPendingSectionFrame = 0u;
    g_lastObservedSectionRevision =
        g_worldGeometryRevision.load(std::memory_order_acquire);

    g_hasPrevCameraMatrices =
        false;

    g_restirCameraHistoryValid =
        false;

    g_restirLastCameraFrame =
        -1;

    g_dlssReady =
        false;

    g_dlssRRSupported =
    false;

    g_dlssRRReady =
        false;

    g_dlssGSupported = false;
    g_dlssGReady = false;
    g_dlssGFeatureInitialized = false;
    g_dlssGLoadResult = sl::Result::eErrorFeatureNotSupported;
    g_slDLSSGSetOptions = nullptr;
    g_slDLSSGGetState = nullptr;


    if(g_slShutdown)
    {
        g_slShutdown();

        g_slShutdown =
            nullptr;
    }


    g_slInit =
        nullptr;

    g_slSetVulkanInfo =
        nullptr;

    g_slIsFeatureSupported =
        nullptr;

    g_slSetFeatureLoaded =
        nullptr;

    g_slGetFeatureFunction =
        nullptr;

    g_slGetFeatureRequirements =
        nullptr;

    g_slSetConstants =
        nullptr;

    g_slEvaluateFeature =
        nullptr;

    g_slGetNewFrameToken =
        nullptr;
    g_slSetTagForFrame =
    nullptr;

    g_slDLSSSetOptions =
        nullptr;

    g_slDLSSGetOptimalSettings =
        nullptr;


    g_slDLSSDSetOptions =
        nullptr;

    g_slDLSSDGetOptimalSettings =
        nullptr;


    g_slReflexSetOptions =
        nullptr;
    g_slReflexGetState =
    nullptr;
    g_slReflexSleep =
    nullptr;
    g_slPCLSetMarker =
    nullptr;


    g_currentSLFrameToken =
        nullptr;

    g_currentSLFrameIndex =
        0;

    g_nextSLFrameIndex =
        0;

    g_currentSLFrameValid =
        false;
    g_lastRenderSubmitStartFrame =
    UINT32_MAX;

    g_lastRenderSubmitEndFrame =
        UINT32_MAX;
    g_lastPresentStartFrame =
    UINT32_MAX;

    g_lastPresentEndFrame =
        UINT32_MAX;

    g_reflexSupported =
        false;

    g_pclSupported =
        false;

    g_reflexLowLatencyAvailable =
        false;

    // 首次默认 On。
    // 下一次 Streamline 初始化重新应用。
    g_reflexMode =
        1;


    g_slInitialized =
        false;


    if(g_slModule)
    {
        FreeLibrary(g_slModule);

        g_slModule =
            NULL;
    }
    g_slDeviceConfigured =
    false;


    g_slVkGetInstanceProcAddrProxy =
        nullptr;

    g_slVkGetDeviceProcAddrProxy =
        nullptr;


    g_slVkCreateWin32SurfaceKHRProxy =
        nullptr;

    g_slVkDestroySurfaceKHRProxy =
        nullptr;


    g_slVkCreateSwapchainKHRProxy =
        nullptr;

    g_slVkDestroySwapchainKHRProxy =
        nullptr;

    g_slVkGetSwapchainImagesKHRProxy =
        nullptr;

    g_slVkAcquireNextImageKHRProxy =
        nullptr;

    g_slVkQueuePresentKHRProxy =
        nullptr;

    g_slVkDeviceWaitIdleProxy =
        nullptr;


    g_instance =
        VK_NULL_HANDLE;

    g_physicalDevice =
        VK_NULL_HANDLE;

    g_device =
        VK_NULL_HANDLE;
}

// === 核心逻辑：批量更新 Bindless 纹理描述符集 ===
// === 新增：全局几何体动态重建核心 ===
static void rebuildWorldGeometry(
    size_t maxSectionUpdates
)
{
    if(
        g_device == VK_NULL_HANDLE ||
        g_rtCommandPool == VK_NULL_HANDLE ||
        g_queue == VK_NULL_HANDLE
    )
    {
        return;
    }

    const auto perfL10RebuildStart =
        std::chrono::steady_clock::now();

    double perfL10QueueIdleMilliseconds =
        0.0;

    bool forceReset = false;

    std::vector<
        std::pair<
            uint64_t,
            SectionGeometrySplit
        >
    > sectionUpdates;

    const auto appendSectionVertices =
        [](std::vector<Vertex>& destination,
           const std::vector<Vertex>& source)
        {
            if(source.empty())
            {
                return;
            }

            const size_t requiredSize =
                destination.size() + source.size();
            if(destination.capacity() < requiredSize)
            {
                const size_t geometricCapacity =
                    destination.capacity() >
                        std::numeric_limits<size_t>::max() / 2u
                        ? requiredSize
                        : destination.capacity() * 2u;
                destination.reserve(
                    std::max(requiredSize, geometricCapacity)
                );
            }
            destination.insert(
                destination.end(),
                source.begin(),
                source.end()
            );
        };


    {
        std::lock_guard<std::mutex>
            lock(
                g_chunkMutex
            );


        forceReset =
            g_forceSectionASReset;


        if(forceReset)
        {
            std::unordered_map<
                uint64_t,
                SectionGeometrySplit
            > mergedSections;


            for(const auto& layerEntry : g_chunkMeshes)
                {
                    const uint64_t spatialSectionKey =
                        layerEntry.first &
                        SPATIAL_SECTION_KEY_MASK;


                const uint32_t layerId =
                    static_cast<uint32_t>(
                        layerEntry.first >> 60ull
                    );

                SectionGeometrySplit& split =
                    mergedSections[spatialSectionKey];

                // Entity batches have their own shader route: generic
                // entity-aware closest/any-hit shaders, not the terrain
                // translucent modules.
                std::vector<Vertex>* mergedVertices = nullptr;
                if(layerEntry.first == LOCAL_PLAYER_RT_LAYER_KEY)
                {
                    mergedVertices = &split.localPlayer;
                }
                else if(layerEntry.first == ENTITY_RT_LAYER_KEY)
                {
                    mergedVertices = &split.entity;
                }
                else if(layerEntry.first == PARTICLE_RT_LAYER_KEY)
                {
                    mergedVertices = &split.particle;
                }
                // Only CUTOUT can carry a micromap.  If the Java mesh and
                // its exact triangle-order metadata disagree, preserving the
                // prior Any-Hit route is the safe fallback.
                else if(layerId == 0u)
                {
                    mergedVertices = &split.opaque;
                }
                else if(layerId == 1u)
                {
                    auto ommIt = g_cutoutOmmInputs.find(layerEntry.first);
                    const bool validOmm =
                        g_ommNativeEnabled &&
                        ommIt != g_cutoutOmmInputs.end() &&
                        ommIt->second.subdivisionLevel == 4u &&
                        ommIt->second.knownMicroTriangleCount > 0u &&
                        ommIt->second.triangleCount ==
                            layerEntry.second.size() / 3u;
                    if(validOmm)
                    {
                        mergedVertices = &split.ommCutout;
                        split.ommInput = ommIt->second;
                        split.hasOmmInput = true;
                    }
                    else
                    {
                        mergedVertices = &split.alpha;
                    }
                }
                else
                {
                    mergedVertices = &split.alpha;
                }

                appendSectionVertices(
                    *mergedVertices,
                    layerEntry.second
                );
            }


            sectionUpdates.reserve(
                mergedSections.size()
            );


            for(auto& sectionEntry : mergedSections)
            {
                sectionUpdates.emplace_back(
                    sectionEntry.first,
                    std::move(
                        sectionEntry.second
                    )
                );
            }
        }
        else
        {
            // Snapshot only a bounded prefix of the dirty set. Remaining
            // Section keys stay pending for later batches, turning a large
            // streaming burst into several bounded BLAS/TLAS rebuilds.
            const bool entityDirty =
                g_dirtySectionKeys.find(ENTITY_RT_SPATIAL_KEY) !=
                g_dirtySectionKeys.end();

            const size_t selectedCount = std::min(
                g_dirtySectionKeys.size(),
                maxSectionUpdates
            );
            sectionUpdates.reserve(selectedCount);

            std::unordered_map<uint64_t, size_t> updateIndices;
            updateIndices.reserve(selectedCount);

            // Entity animation is the only non-streaming producer.  Keep its
            // reserved Section first so bounded world updates cannot defer it
            // behind a chunk upload burst.
            if(entityDirty && selectedCount > 0u)
            {
                sectionUpdates.emplace_back(
                    ENTITY_RT_SPATIAL_KEY,
                    SectionGeometrySplit{}
                );
                updateIndices.emplace(
                    ENTITY_RT_SPATIAL_KEY,
                    0u
                );
            }

            for(auto it = g_dirtySectionKeys.begin();
                it != g_dirtySectionKeys.end() &&
                sectionUpdates.size() < selectedCount;
                ++it)
            {
                const uint64_t sectionKey = *it;
                if(sectionKey == ENTITY_RT_SPATIAL_KEY)
                {
                    continue;
                }
                const size_t updateIndex = sectionUpdates.size();
                sectionUpdates.emplace_back(sectionKey, SectionGeometrySplit{});
                updateIndices.emplace(sectionKey, updateIndex);
            }


            const bool onlyDynamicSection =
                sectionUpdates.size() == 1u &&
                sectionUpdates.front().first == ENTITY_RT_SPATIAL_KEY;
            if(onlyDynamicSection)
            {
                SectionGeometrySplit& split = sectionUpdates.front().second;
                auto appendDynamicLayer =
                [&](uint64_t layerKey, std::vector<Vertex>& destination)
                {
                    const auto layer = g_chunkMeshes.find(layerKey);
                    if(layer != g_chunkMeshes.end())
                    {
                        appendSectionVertices(
                            destination,
                            layer->second
                        );
                    }
                };

                appendDynamicLayer(LOCAL_PLAYER_RT_LAYER_KEY, split.localPlayer);
                appendDynamicLayer(ENTITY_RT_LAYER_KEY, split.entity);
                appendDynamicLayer(PARTICLE_RT_LAYER_KEY, split.particle);
            }
            else
            {
                for(const auto& layerEntry : g_chunkMeshes)
                {
                    const uint64_t spatialSectionKey =
                        layerEntry.first &
                        SPATIAL_SECTION_KEY_MASK;


                    auto updateIt =
                        updateIndices.find(
                            spatialSectionKey
                        );


                    if(updateIt == updateIndices.end())
                    {
                        continue;
                    }


                    const uint32_t layerId =
                        static_cast<uint32_t>(
                            layerEntry.first >> 60ull
                        );

                    SectionGeometrySplit& split =
                        sectionUpdates[updateIt->second].second;
                    if(layerEntry.first == LOCAL_PLAYER_RT_LAYER_KEY)
                    {
                        appendSectionVertices(
                            split.localPlayer,
                            layerEntry.second
                        );
                    }
                    else if(layerEntry.first == ENTITY_RT_LAYER_KEY)
                    {
                        appendSectionVertices(
                            split.entity,
                            layerEntry.second
                        );
                    }
                    else if(layerEntry.first == PARTICLE_RT_LAYER_KEY)
                    {
                        appendSectionVertices(
                            split.particle,
                            layerEntry.second
                        );
                    }
                    else if(layerId == 0u)
                    {
                        appendSectionVertices(
                            split.opaque,
                            layerEntry.second
                        );
                    }
                    else if(layerId == 1u)
                    {
                        auto ommIt = g_cutoutOmmInputs.find(layerEntry.first);
                        const bool validOmm =
                            g_ommNativeEnabled &&
                            ommIt != g_cutoutOmmInputs.end() &&
                            ommIt->second.subdivisionLevel == 4u &&
                            ommIt->second.knownMicroTriangleCount > 0u &&
                            ommIt->second.triangleCount ==
                                layerEntry.second.size() / 3u;
                        if(validOmm)
                        {
                            appendSectionVertices(
                                split.ommCutout,
                                layerEntry.second
                            );
                            split.ommInput = ommIt->second;
                            split.hasOmmInput = true;
                        }
                        else
                        {
                            appendSectionVertices(
                                split.alpha,
                                layerEntry.second
                            );
                        }
                    }
                    else
                    {
                        // The translucent render layer contains both water and
                        // non-water translucent blocks. Never classify from
                        // alpha or texture colour: marker 1 is the sole water
                        // contract, and every other triangle stays together.
                        const std::vector<Vertex>& layer = layerEntry.second;
                        size_t waterVertexCount = 0u;
                        size_t alphaVertexCount = 0u;
                        for(size_t vertex = 0u;
                            vertex + 2u < layer.size();
                            vertex += 3u)
                        {
                            const bool waterTriangle =
                                layer[vertex + 0u].materialId == 1.0f &&
                                layer[vertex + 1u].materialId == 1.0f &&
                                layer[vertex + 2u].materialId == 1.0f;
                            (waterTriangle ? waterVertexCount : alphaVertexCount) += 3u;
                        }
                        split.water.reserve(
                            split.water.size() + waterVertexCount
                        );
                        split.alpha.reserve(
                            split.alpha.size() + alphaVertexCount
                        );
                        for(size_t vertex = 0u; vertex + 2u < layer.size(); vertex += 3u)
                        {
                            const bool waterTriangle =
                                layer[vertex + 0u].materialId == 1.0f &&
                                layer[vertex + 1u].materialId == 1.0f &&
                                layer[vertex + 2u].materialId == 1.0f;
                            std::vector<Vertex>& destination =
                                waterTriangle ? split.water : split.alpha;
                            destination.insert(
                                destination.end(),
                                layer.begin() + vertex,
                                layer.begin() + vertex + 3u
                            );
                        }
                    }
                }
            }
            }


        if(forceReset)
        {
            g_dirtySectionKeys.clear();
        }
        else
        {
            // Remove only the bounded batch captured above. Uploads that
            // arrived after this snapshot remain dirty and will be processed
            // by the following rebuild rather than being silently discarded.
            for(const auto& update : sectionUpdates)
            {
                g_dirtySectionKeys.erase(update.first);
            }
        }

        g_forceSectionASReset = false;
        g_worldGeometryDirty.store(
            !g_dirtySectionKeys.empty(),
            std::memory_order_release
        );
    }


    if(
        !forceReset &&
        sectionUpdates.empty()
    )
    {
        return;
    }

    // The initialization TLAS is built synchronously by createTLAS() before
    // the first world batch. Runtime replacements normally retire through the
    // previous presented-frame completion fence; fall back to queue idle only
    // while that frame is still in flight.
    if(g_asBuilt)
    {
        const auto perfL10QueueIdleStart =
            std::chrono::steady_clock::now();

        const bool queueWaitRequired =
            !latestFrameCompletionFenceSignaled();

        if(queueWaitRequired)
        {
            check(
                vkQueueWaitIdle(g_queue),
                "wait RT queue before Section AS rebuild failed"
            );
            destroyFrameCompletionFences();
        }

        if(queueWaitRequired)
        {
            perfL10QueueIdleMilliseconds =
                std::chrono::duration<double, std::milli>(
                    std::chrono::steady_clock::now() -
                    perfL10QueueIdleStart
                ).count();
        }
    }

    releaseDeferredSectionBuildResources();

    // The existing TLAS references the current Section BLAS objects.
    // Retire it before replacing or destroying any referenced BLAS.
    auto destroyPreviousAS =
        getDestroyAccelerationStructure();


    if(!destroyPreviousAS)
    {
        throw std::runtime_error(
            "vkDestroyAccelerationStructureKHR unavailable"
        );
    }


    if(g_tlas != VK_NULL_HANDLE)
    {
        destroyPreviousAS(
            g_device,
            g_tlas,
            nullptr
        );

        g_tlas =
            VK_NULL_HANDLE;
    }


    if(forceReset)
    {
        for(auto& entry : g_sectionBLAS)
        {
            destroySectionAS(
                entry.second
            );
        }


        g_sectionBLAS.clear();
    }


    uint32_t removedSectionCount = 0;
    uint32_t rebuiltSectionCount = 0;
    uint64_t rebuiltOpaqueTriangleCount = 0;
    uint64_t rebuiltOmmTriangleCount = 0;
    uint64_t rebuiltAlphaTriangleCount = 0;
    uint64_t rebuiltWaterTriangleCount = 0;
    uint64_t rebuiltParticleTriangleCount = 0;

    std::vector<RTSectionAS*>
        sectionsNeedingBuild;

    sectionsNeedingBuild.reserve(
        sectionUpdates.size() * 7u
    );

    // Each Section can contribute seven independent geometry classes. Keep
    // the hash table and upload list sized for the batch to avoid rehashing
    // and vector growth while the rebuild is assembling resources.
    g_sectionBLAS.reserve(
        g_sectionBLAS.size() + sectionUpdates.size() * 7u
    );


    struct PendingSectionUpload
    {
        RTSectionAS* section = nullptr;
        const std::vector<Vertex>* vertices = nullptr;
        VkDeviceSize stagingOffset = 0;
    };


    std::vector<PendingSectionUpload>
        pendingUploads;

    pendingUploads.reserve(
        sectionUpdates.size() * 7u
    );


    VkDeviceSize totalStagingBytes = 0;


    VkDeviceSize maxBLASScratchSize = 0;


    for(auto& update : sectionUpdates)
    {
        const uint64_t spatialSectionKey =
            update.first;

        auto rebuildGeometryClass =
            [&]
            (
                uint64_t classKey,
                const std::vector<Vertex>& vertices,
                bool opaqueGeometry,
                const OmmCutoutInput* ommInput
            )
            {
                if(auto old = g_sectionBLAS.find(classKey);
                   old != g_sectionBLAS.end())
                {
                    destroySectionAS(old->second);
                    g_sectionBLAS.erase(old);
                }

                if(vertices.empty())
                {
                    return;
                }

                RTSectionAS newSection{};
                try
                {
                    const VkDeviceSize scratchSize =
                        createSectionBLAS(
                            newSection,
                            classKey,
                            vertices,
                            opaqueGeometry,
                            ommInput
                        );

                    auto insertResult =
                        g_sectionBLAS.emplace(
                            classKey,
                            std::move(newSection)
                        );
                    if(!insertResult.second)
                    {
                        throw std::runtime_error("duplicate Section BLAS key");
                    }

                    RTSectionAS& insertedSection =
                        insertResult.first->second;
                    newSection = {};
                    sectionsNeedingBuild.push_back(&insertedSection);

                    constexpr VkDeviceSize STAGING_ALIGNMENT = 16;
                    if(totalStagingBytes >
                       std::numeric_limits<VkDeviceSize>::max() -
                           (STAGING_ALIGNMENT - 1))
                    {
                        throw std::runtime_error("Section staging offset overflow");
                    }

                    const VkDeviceSize stagingOffset =
                        (totalStagingBytes + STAGING_ALIGNMENT - 1) &
                        ~(STAGING_ALIGNMENT - 1);
                    if(stagingOffset >
                       std::numeric_limits<VkDeviceSize>::max() -
                           insertedSection.vertexBytes)
                    {
                        throw std::runtime_error("Section staging size overflow");
                    }

                    pendingUploads.push_back(
                        { &insertedSection, &vertices, stagingOffset }
                    );
                    totalStagingBytes =
                        stagingOffset + insertedSection.vertexBytes;
                    maxBLASScratchSize = std::max(
                        maxBLASScratchSize,
                        scratchSize
                    );
                    if(opaqueGeometry)
                    {
                        rebuiltOpaqueTriangleCount +=
                            insertedSection.triangleCount;
                    }
                    else if(insertedSection.hasOpacityMicromap)
                    {
                        rebuiltOmmTriangleCount +=
                            insertedSection.triangleCount;
                    }
                    else if(
                        (classKey & ~SPATIAL_SECTION_KEY_MASK) ==
                        SECTION_AS_CLASS_WATER
                    )
                    {
                        rebuiltWaterTriangleCount +=
                            insertedSection.triangleCount;
                    }
                    else if(
                        (classKey & ~SPATIAL_SECTION_KEY_MASK) ==
                        SECTION_AS_CLASS_PARTICLE
                    )
                    {
                        rebuiltParticleTriangleCount +=
                            insertedSection.triangleCount;
                    }
                    else
                    {
                        rebuiltAlphaTriangleCount +=
                            insertedSection.triangleCount;
                    }
                    ++rebuiltSectionCount;
                }
                catch(...)
                {
                    destroySectionAS(newSection);
                    throw;
                }
            };

        if(update.second.opaque.empty() &&
           update.second.ommCutout.empty() &&
           update.second.alpha.empty() &&
           update.second.water.empty() &&
           update.second.entity.empty() &&
           update.second.localPlayer.empty() &&
           update.second.particle.empty())
        {
            ++removedSectionCount;
        }

        rebuildGeometryClass(
            spatialSectionKey | SECTION_AS_CLASS_OPAQUE,
            update.second.opaque,
            true,
            nullptr
        );
        rebuildGeometryClass(
            spatialSectionKey | SECTION_AS_CLASS_OMM,
            update.second.ommCutout,
            false,
            update.second.hasOmmInput ? &update.second.ommInput : nullptr
        );
        rebuildGeometryClass(
            spatialSectionKey | SECTION_AS_CLASS_ALPHA,
            update.second.alpha,
            false,
            nullptr
        );
        rebuildGeometryClass(
            spatialSectionKey | SECTION_AS_CLASS_WATER,
            update.second.water,
            false,
            nullptr
        );
        rebuildGeometryClass(
            spatialSectionKey | SECTION_AS_CLASS_ENTITY,
            update.second.entity,
            false,
            nullptr
        );
        rebuildGeometryClass(
            spatialSectionKey | SECTION_AS_CLASS_LOCAL_PLAYER,
            update.second.localPlayer,
            false,
            nullptr
        );
        rebuildGeometryClass(
            spatialSectionKey | SECTION_AS_CLASS_PARTICLE,
            update.second.particle,
            false,
            nullptr
        );
    }


    VkBuffer vertexStagingBuffer =
        VK_NULL_HANDLE;

    VkDeviceMemory vertexStagingMemory =
        VK_NULL_HANDLE;


    if(!pendingUploads.empty())
    {
        createBuffer(
            g_device,
            g_physicalDevice,
            totalStagingBytes,
            VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
            vertexStagingBuffer,
            vertexStagingMemory
        );


        void* mappedStaging = nullptr;

        check(
            vkMapMemory(
                g_device,
                vertexStagingMemory,
                0,
                totalStagingBytes,
                0,
                &mappedStaging
            ),
            "map batched Section staging buffer failed"
        );


        uint8_t* stagingBytes =
            static_cast<uint8_t*>(
                mappedStaging
            );


        for(const PendingSectionUpload& upload : pendingUploads)
        {
            std::memcpy(
                stagingBytes +
                    static_cast<size_t>(
                        upload.stagingOffset
                    ),
                upload.vertices->data(),
                static_cast<size_t>(
                    upload.section->vertexBytes
                )
            );
        }


        vkUnmapMemory(
            g_device,
            vertexStagingMemory
        );
    }


    std::vector<RTSectionAS*>
        orderedSections;

    orderedSections.reserve(
        g_sectionBLAS.size()
    );


    for(auto& entry : g_sectionBLAS)
    {
        if(
            entry.second.blas == VK_NULL_HANDLE ||
            entry.second.blasAddress == 0 ||
            entry.second.vertexAddress == 0
        )
        {
            throw std::runtime_error(
                "Section AS map contains an incomplete entry"
            );
        }


        orderedSections.push_back(
            &entry.second
        );
    }


    std::sort(
        orderedSections.begin(),
        orderedSections.end(),
        [](
            const RTSectionAS* a,
            const RTSectionAS* b
        )
        {
            return
                a->sectionKey <
                b->sectionKey;
        }
    );


    constexpr size_t MAX_TLAS_CUSTOM_INDEX_COUNT =
        static_cast<size_t>(1u << 24u);


    if(
        orderedSections.size() >
        MAX_TLAS_CUSTOM_INDEX_COUNT
    )
    {
        throw std::runtime_error(
            "Section count exceeds instanceCustomIndex 24-bit capacity"
        );
    }


    std::vector<VkAccelerationStructureInstanceKHR>
        instances;


    if(orderedSections.empty())
    {
        // Keep Binding 0 valid while no world Section is loaded.
        // A zero BLAS reference makes this instance inactive.
        VkAccelerationStructureInstanceKHR inactive{};

        inactive.transform.matrix[0][0] = 1.0f;
        inactive.transform.matrix[1][1] = 1.0f;
        inactive.transform.matrix[2][2] = 1.0f;

        inactive.instanceCustomIndex = 0u;
        inactive.mask = 0u;

        inactive.instanceShaderBindingTableRecordOffset =
            0u;

        inactive.flags =
            VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR |
            VK_GEOMETRY_INSTANCE_FORCE_NO_OPAQUE_BIT_KHR;

        inactive.accelerationStructureReference =
            0;


        instances.push_back(
            inactive
        );
    }
    else
    {
        instances.reserve(
            orderedSections.size()
        );


        for(
            uint32_t instanceIndex = 0;
            instanceIndex <
                static_cast<uint32_t>(
                    orderedSections.size()
                );
            ++instanceIndex
        )
        {
            const RTSectionAS& section =
                *orderedSections[instanceIndex];


            VkAccelerationStructureInstanceKHR instance{};

            instance.transform.matrix[0][0] = 1.0f;
            instance.transform.matrix[1][1] = 1.0f;
            instance.transform.matrix[2][2] = 1.0f;

            instance.instanceCustomIndex =
                instanceIndex;

            instance.mask =
                0xff;

            const uint64_t geometryClass =
                section.sectionKey & ~SPATIAL_SECTION_KEY_MASK;

            // Main camera rays use mask 0x01.  The local player's RT proxy
            // uses 0x02, so reflections/shadows/GI (0xff) can still hit it
            // without making first-person camera rays hit the player's own
            // body.
            if(geometryClass == SECTION_AS_CLASS_LOCAL_PLAYER)
            {
                instance.mask = 0x02;
            }

            if(geometryClass == SECTION_AS_CLASS_OPAQUE)
            {
                instance.instanceShaderBindingTableRecordOffset =
                    SBT_RECORD_OPAQUE;
            }
            else if(geometryClass == SECTION_AS_CLASS_OMM)
            {
                instance.instanceShaderBindingTableRecordOffset =
                    SBT_RECORD_OMM;
            }
            else if(geometryClass == SECTION_AS_CLASS_WATER)
            {
                instance.instanceShaderBindingTableRecordOffset =
                    SBT_RECORD_WATER;
            }
            else if(geometryClass == SECTION_AS_CLASS_ENTITY)
            {
                instance.instanceShaderBindingTableRecordOffset =
                    SBT_RECORD_ENTITY;
            }
            else if(geometryClass == SECTION_AS_CLASS_LOCAL_PLAYER)
            {
                instance.instanceShaderBindingTableRecordOffset =
                    SBT_RECORD_ENTITY;
            }
            else if(geometryClass == SECTION_AS_CLASS_PARTICLE)
            {
                instance.instanceShaderBindingTableRecordOffset =
                    SBT_RECORD_ENTITY;
            }
            else
            {
                instance.instanceShaderBindingTableRecordOffset =
                    SBT_RECORD_ALPHA;
            }

            const bool opaqueGeometry =
                geometryClass ==
                    SECTION_AS_CLASS_OPAQUE;

            const bool ommGeometry = section.hasOpacityMicromap;

            instance.flags =
                VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR |
                (
                    // OMM needs its opaque/transparent/unknown states to be
                    // visible to traversal. FORCE_NO_OPAQUE would make every
                    // micro-triangle invoke .rahit and remove the benefit.
                    (opaqueGeometry || ommGeometry)
                        ? 0u
                        : VK_GEOMETRY_INSTANCE_FORCE_NO_OPAQUE_BIT_KHR
                );

            instance.accelerationStructureReference =
                section.blasAddress;


            instances.push_back(
                instance
            );
        }
    }


    auto destroyAS =
        getDestroyAccelerationStructure();

    auto createAS =
        getCreateAccelerationStructure();

    auto getBuildSizes =
        getGetAccelerationStructureBuildSizes();

    auto getASAddress =
        getGetAccelerationStructureDeviceAddress();

    auto cmdBuild =
        getCmdBuildAccelerationStructures();


    if(
        !destroyAS ||
        !createAS ||
        !getBuildSizes ||
        !getASAddress ||
        !cmdBuild
    )
    {
        throw std::runtime_error(
            "multi-Section TLAS Vulkan functions unavailable"
        );
    }

    // The driver can hang if a TLAS UPDATE reads a source TLAS whose dynamic
    // BLAS references were just replaced. Keep the proven full-build path.
    constexpr bool updateTlas = false;

    if(!updateTlas)
    {
    if(g_tlas != VK_NULL_HANDLE)
    {
        destroyAS(
            g_device,
            g_tlas,
            nullptr
        );

        g_tlas =
            VK_NULL_HANDLE;
    }


    if(g_tlasScratchBuffer != VK_NULL_HANDLE)
    {
        vkDestroyBuffer(
            g_device,
            g_tlasScratchBuffer,
            nullptr
        );

        g_tlasScratchBuffer =
            VK_NULL_HANDLE;
    }


    if(g_tlasScratchMemory != VK_NULL_HANDLE)
    {
        vkFreeMemory(
            g_device,
            g_tlasScratchMemory,
            nullptr
        );

        g_tlasScratchMemory =
            VK_NULL_HANDLE;
    }


    if(g_tlasBuffer != VK_NULL_HANDLE)
    {
        vkDestroyBuffer(
            g_device,
            g_tlasBuffer,
            nullptr
        );

        g_tlasBuffer =
            VK_NULL_HANDLE;
    }


    if(g_tlasMemory != VK_NULL_HANDLE)
    {
        vkFreeMemory(
            g_device,
            g_tlasMemory,
            nullptr
        );

        g_tlasMemory =
            VK_NULL_HANDLE;
    }


    // Release the one-instance initialization allocation on the first
    // dynamic rebuild, then use the plural Section instance buffer.
    if(g_tlasInstanceBuffer != VK_NULL_HANDLE)
    {
        vkDestroyBuffer(
            g_device,
            g_tlasInstanceBuffer,
            nullptr
        );

        g_tlasInstanceBuffer =
            VK_NULL_HANDLE;
    }


    if(g_tlasInstanceMemory != VK_NULL_HANDLE)
    {
        vkFreeMemory(
            g_device,
            g_tlasInstanceMemory,
            nullptr
        );

        g_tlasInstanceMemory =
            VK_NULL_HANDLE;
    }


    if(g_tlasInstancesBuffer != VK_NULL_HANDLE)
    {
        vkDestroyBuffer(
            g_device,
            g_tlasInstancesBuffer,
            nullptr
        );

        g_tlasInstancesBuffer =
            VK_NULL_HANDLE;
    }


    if(g_tlasInstancesMemory != VK_NULL_HANDLE)
    {
        vkFreeMemory(
            g_device,
            g_tlasInstancesMemory,
            nullptr
        );

        g_tlasInstancesMemory =
            VK_NULL_HANDLE;
    }
    }


    const VkDeviceSize instanceBytes =
        static_cast<VkDeviceSize>(
            instances.size()
        ) *
        sizeof(VkAccelerationStructureInstanceKHR);


    if(!updateTlas)
    {
        createBuffer(
            g_device,
            g_physicalDevice,
            instanceBytes,
            VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR |
                VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
            g_tlasInstancesBuffer,
            g_tlasInstancesMemory
        );
    }


    void* mappedInstances = nullptr;

    check(
        vkMapMemory(
            g_device,
            g_tlasInstancesMemory,
            0,
            instanceBytes,
            0,
            &mappedInstances
        ),
        "map multi-Section TLAS instance buffer failed"
    );


    std::memcpy(
        mappedInstances,
        instances.data(),
        static_cast<size_t>(instanceBytes)
    );


    vkUnmapMemory(
        g_device,
        g_tlasInstancesMemory
    );


    g_tlasInstancesAddress =
        getBufferDeviceAddress(
            g_device,
            g_tlasInstancesBuffer
        );


    VkAccelerationStructureGeometryInstancesDataKHR
        instanceData{
            VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_INSTANCES_DATA_KHR
        };

    instanceData.arrayOfPointers =
        VK_FALSE;

    instanceData.data.deviceAddress =
        g_tlasInstancesAddress;


    VkAccelerationStructureGeometryKHR
        tlasGeometry{
            VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR
        };

    tlasGeometry.geometryType =
        VK_GEOMETRY_TYPE_INSTANCES_KHR;

    tlasGeometry.geometry.instances =
        instanceData;


    VkAccelerationStructureBuildGeometryInfoKHR
        tlasBuildInfo{
            VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR
        };

    tlasBuildInfo.type =
        VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR;

    tlasBuildInfo.flags =
        VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR |
        VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR;

    tlasBuildInfo.mode =
        VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR;

    tlasBuildInfo.geometryCount = 1;
    tlasBuildInfo.pGeometries =
        &tlasGeometry;


    const uint32_t tlasPrimitiveCount =
        static_cast<uint32_t>(
            instances.size()
        );


    VkAccelerationStructureBuildSizesInfoKHR
        tlasSizes{
            VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR
        };


    getBuildSizes(
        g_device,
        VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
        &tlasBuildInfo,
        &tlasPrimitiveCount,
        &tlasSizes
    );


    if(!updateTlas)
    {
        createBuffer(
            g_device,
            g_physicalDevice,
            tlasSizes.accelerationStructureSize,
            VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR |
                VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
            g_tlasBuffer,
            g_tlasMemory
        );


    VkAccelerationStructureCreateInfoKHR
        tlasCreateInfo{
            VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR
        };

    tlasCreateInfo.buffer =
        g_tlasBuffer;

    tlasCreateInfo.size =
        tlasSizes.accelerationStructureSize;

    tlasCreateInfo.type =
        VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR;


        check(
            createAS(
                g_device,
                &tlasCreateInfo,
                nullptr,
                &g_tlas
            ),
            "create multi-Section TLAS failed"
        );


        g_tlasScratchAddress = 0;
        g_tlasScratchCapacity = std::max(
            tlasSizes.buildScratchSize,
            tlasSizes.updateScratchSize
        );


        if(g_tlasScratchCapacity > 0)
        {
            createBuffer(
                g_device,
                g_physicalDevice,
                g_tlasScratchCapacity,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT |
                    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                g_tlasScratchBuffer,
                g_tlasScratchMemory
            );


            g_tlasScratchAddress =
                getBufferDeviceAddress(
                    g_device,
                    g_tlasScratchBuffer
                );
        }
    }
    else if(g_tlasScratchCapacity < tlasSizes.updateScratchSize)
    {
        throw std::runtime_error("cached TLAS scratch buffer is too small");
    }


    VkBuffer blasScratchBuffer =
        VK_NULL_HANDLE;

    VkDeviceMemory blasScratchMemory =
        VK_NULL_HANDLE;

    VkDeviceAddress blasScratchAddress =
        0;


    if(
        !sectionsNeedingBuild.empty() &&
        maxBLASScratchSize > 0
    )
    {
        createBuffer(
            g_device,
            g_physicalDevice,
            maxBLASScratchSize,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT |
                VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
            blasScratchBuffer,
            blasScratchMemory
        );


        blasScratchAddress =
            getBufferDeviceAddress(
                g_device,
                blasScratchBuffer
            );
    }


    VkCommandBufferAllocateInfo allocateInfo{
        VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO
    };

    allocateInfo.commandPool =
        g_rtCommandPool;

    allocateInfo.level =
        VK_COMMAND_BUFFER_LEVEL_PRIMARY;

    allocateInfo.commandBufferCount =
        1;


    VkCommandBuffer cmd =
        VK_NULL_HANDLE;


    check(
        vkAllocateCommandBuffers(
            g_device,
            &allocateInfo,
            &cmd
        ),
        "allocate Section AS command buffer failed"
    );


    VkCommandBufferBeginInfo beginInfo{
        VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO
    };

    beginInfo.flags =
        VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;


    check(
        vkBeginCommandBuffer(
            cmd,
            &beginInfo
        ),
        "begin Section AS command buffer failed"
    );


    if(!pendingUploads.empty())
    {
        for(const PendingSectionUpload& upload : pendingUploads)
        {
            VkBufferCopy copy{};

            copy.srcOffset =
                upload.stagingOffset;

            copy.dstOffset = 0;

            copy.size =
                upload.section->vertexBytes;


            vkCmdCopyBuffer(
                cmd,
                vertexStagingBuffer,
                upload.section->vertexBuffer,
                1,
                &copy
            );
        }


        VkMemoryBarrier uploadBarrier{
            VK_STRUCTURE_TYPE_MEMORY_BARRIER
        };

        uploadBarrier.srcAccessMask =
            VK_ACCESS_TRANSFER_WRITE_BIT;

        uploadBarrier.dstAccessMask =
            VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR |
            VK_ACCESS_SHADER_READ_BIT;


        vkCmdPipelineBarrier(
            cmd,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR |
                VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
            0,
            1,
            &uploadBarrier,
            0,
            nullptr,
            0,
            nullptr
        );
    }


    VkMemoryBarrier asBuildBarrier{
        VK_STRUCTURE_TYPE_MEMORY_BARRIER
    };

    asBuildBarrier.srcAccessMask =
        VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR;

    asBuildBarrier.dstAccessMask =
        VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR |
        VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR;


    for(const RTSectionAS* section : sectionsNeedingBuild)
    {
        recordSectionBLASBuild(
            cmd,
            *section,
            blasScratchAddress
        );


        // The next BLAS reuses the same scratch allocation. This also
        // makes every newly built BLAS visible to the following TLAS build.
        vkCmdPipelineBarrier(
            cmd,
            VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
            VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
            0,
            1,
            &asBuildBarrier,
            0,
            nullptr,
            0,
            nullptr
        );
    }


    tlasBuildInfo.dstAccelerationStructure =
        g_tlas;

    tlasBuildInfo.mode =
        updateTlas
            ? VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR
            : VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR;

    tlasBuildInfo.srcAccelerationStructure =
        updateTlas
            ? g_tlas
            : VK_NULL_HANDLE;

    tlasBuildInfo.scratchData.deviceAddress =
        g_tlasScratchAddress;


    VkAccelerationStructureBuildRangeInfoKHR
        tlasRange{};

    tlasRange.primitiveCount =
        tlasPrimitiveCount;


    const VkAccelerationStructureBuildRangeInfoKHR*
        tlasRanges[] =
        {
            &tlasRange
        };


    cmdBuild(
        cmd,
        1,
        &tlasBuildInfo,
        tlasRanges
    );


    check(
        vkEndCommandBuffer(cmd),
        "end Section AS command buffer failed"
    );


    VkSubmitInfo submitInfo{
        VK_STRUCTURE_TYPE_SUBMIT_INFO
    };

    submitInfo.commandBufferCount =
        1;

    submitInfo.pCommandBuffers =
        &cmd;


    check(
        vkQueueSubmit(
            g_queue,
            1,
            &submitInfo,
            VK_NULL_HANDLE
        ),
        "submit Section BLAS/TLAS build failed"
    );

    g_tlasInstanceCount =
        instances.size();

    for(RTSectionAS* section : sectionsNeedingBuild)
    {
        deferSectionOpacityMicromapBuildInputs(*section);
    }


    // Queue ordering guarantees that this build completes before the frame's
    // later RT submission.  Keep only the temporary inputs alive; do not
    // stall this CPU frame waiting for the build command to finish.
    g_deferredSectionBuild.commandBuffer =
        cmd;

    g_deferredSectionBuild.blasScratchBuffer =
        blasScratchBuffer;

    g_deferredSectionBuild.blasScratchMemory =
        blasScratchMemory;

    g_deferredSectionBuild.vertexStagingBuffer =
        vertexStagingBuffer;

    g_deferredSectionBuild.vertexStagingMemory =
        vertexStagingMemory;

    cmd = VK_NULL_HANDLE;
    blasScratchBuffer = VK_NULL_HANDLE;
    blasScratchMemory = VK_NULL_HANDLE;
    vertexStagingBuffer = VK_NULL_HANDLE;
    vertexStagingMemory = VK_NULL_HANDLE;


    VkAccelerationStructureDeviceAddressInfoKHR
        tlasAddressInfo{
            VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_DEVICE_ADDRESS_INFO_KHR
        };

    tlasAddressInfo.accelerationStructure =
        g_tlas;


    g_tlasDeviceAddress =
        getASAddress(
            g_device,
            &tlasAddressInfo
        );


    rebuildSectionVertexAddressBuffer(
        orderedSections
    );


    VkWriteDescriptorSetAccelerationStructureKHR
        tlasDescriptorInfo{
            VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR
        };

    tlasDescriptorInfo.accelerationStructureCount =
        1;

    tlasDescriptorInfo.pAccelerationStructures =
        &g_tlas;


    VkWriteDescriptorSet tlasWrite{
        VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET
    };

    tlasWrite.pNext =
        &tlasDescriptorInfo;

    tlasWrite.dstSet =
        g_rayTracingDescriptorSet;

    tlasWrite.dstBinding = 0;
    tlasWrite.descriptorCount = 1;

    tlasWrite.descriptorType =
        VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;


    vkUpdateDescriptorSets(
        g_device,
        1,
        &tlasWrite,
        0,
        nullptr
    );


    g_asBuilt =
        true;


    uint64_t totalTriangleCount = 0;


    for(const RTSectionAS* section : orderedSections)
    {
        totalTriangleCount +=
            section->triangleCount;
    }


    if constexpr(PERF_L10_ENABLE_SECTION_REBUILD_LOGS)
    {
        std::stringstream log;

        log
            << "[RT SECTION AS]"
            << " rebuilt=" << rebuiltSectionCount
            << " removed=" << removedSectionCount
            << " instances=" << orderedSections.size()
            << " triangles=" << totalTriangleCount
            << " rebuiltOpaqueTriangles=" << rebuiltOpaqueTriangleCount
            << " rebuiltOmmTriangles=" << rebuiltOmmTriangleCount
            << " rebuiltAlphaTriangles=" << rebuiltAlphaTriangleCount
            << " rebuiltWaterTriangles=" << rebuiltWaterTriangleCount
            << " rebuiltParticleTriangles=" << rebuiltParticleTriangleCount
            << " queued=1"
            << " tlasAddr=0x"
            << std::hex
            << static_cast<unsigned long long>(
                g_tlasDeviceAddress
            );

        RTLog(
            log.str()
        );

        const double perfL10TotalMilliseconds =
            std::chrono::duration<double, std::milli>(
                std::chrono::steady_clock::now() -
                perfL10RebuildStart
            ).count();

        std::stringstream perfL10Log;
        perfL10Log
            << "[PERF L10 SECTION REBUILD]"
            << " frame=" << g_perfL10FrameSerial
            << " totalCpuMs=" << std::fixed << std::setprecision(3)
            << perfL10TotalMilliseconds
            << " queueIdleMs=" << perfL10QueueIdleMilliseconds
            << " rebuilt=" << rebuiltSectionCount
            << " removed=" << removedSectionCount
            << " instances=" << orderedSections.size()
            << " triangles=" << totalTriangleCount
            << " rebuiltOpaqueTriangles=" << rebuiltOpaqueTriangleCount
            << " rebuiltOmmTriangles=" << rebuiltOmmTriangleCount
            << " rebuiltAlphaTriangles=" << rebuiltAlphaTriangleCount
            << " rebuiltWaterTriangles=" << rebuiltWaterTriangleCount
            << " rebuiltParticleTriangles=" << rebuiltParticleTriangleCount;

        RTLog(
            perfL10Log.str()
        );
    }

}
// DLSS frame-level Halton(2,3)
// 必须与 rt_test.rgen 完全一致
// =====================================================

static float DLSSHaltonValue(
    uint32_t index,
    uint32_t base
)
{
    float f = 1.0f;
    float result = 0.0f;

    uint32_t i = index;

    while(i > 0u)
    {
        f /= static_cast<float>(base);

        result +=
            f *
            static_cast<float>(
                i % base
            );

        i /= base;
    }

    return result;
}


static void GetDLSSFrameJitter(
    uint32_t frameIndex,
    float& outX,
    float& outY
)
{
    constexpr uint32_t PHASE_COUNT =
        32u;

    const uint32_t sequenceIndex =
        (frameIndex % PHASE_COUNT) +
        1u;


    outX =
        DLSSHaltonValue(
            sequenceIndex,
            2u
        ) -
        0.5f;


    outY =
        DLSSHaltonValue(
            sequenceIndex,
            3u
        ) -
        0.5f;
}
