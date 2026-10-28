"""Isolated PowerVR 16KB experiment. Never edits the pinned llama.cpp source.

Run vulkan-build-bench.ps1 first. This script changes only the generated Ninja
edge for ggml-vulkan.cpp, reusing already compiled shaders and other objects.
A subsequent CMake configure resets that edge; rerun this script afterwards.
"""
from pathlib import Path
import shutil
import subprocess

root = Path(__file__).resolve().parents[1]
original = root / '.tools/llama.cpp/ggml/src/ggml-vulkan'
isolated = root / '.tools/vulkan-source/ggml-vulkan'
shutil.copytree(original, isolated, dirs_exist_ok=True)
source = isolated / 'ggml-vulkan.cpp'
text = source.read_text(encoding='utf-8')
old = '''                std::cerr << "ggml_vulkan: Error: Shared memory size too small for matrix multiplication." << std::endl;
                throw std::runtime_error("Shared memory size too small for matrix multiplication.");'''
new = '''                GGML_LOG_WARN("ggml_vulkan: disabling unsupported matmul type %s (fp16=%d, shared=%u)\\n",
                              ggml_type_name(t), int(device->fp16), device->properties.limits.maxComputeSharedMemorySize);
                device->mul_mat_s[i] = device->mul_mat_m[i] = device->mul_mat_l[i] = false;
                device->mul_mat_id_s[i] = device->mul_mat_id_m[i] = device->mul_mat_id_l[i] = false;
                device->mul_mat_s_int[i] = device->mul_mat_m_int[i] = device->mul_mat_l_int[i] = false;
                device->mul_mat_id_s_int[i] = device->mul_mat_id_m_int[i] = device->mul_mat_id_l_int[i] = false;
                continue;'''
assert text.count(old) == 1
text = text.replace(old, new)
old = '''    const bool uses_bda = (op->op == GGML_OP_IM2COL || op->op == GGML_OP_IM2COL_3D) &&'''
new = '''    // Reject unsupported quantized tensors, including vector paths, rather
    // than allowing a missing pipeline to be selected later.
    if (device->driver_id == vk::DriverId::eImaginationProprietary) {
        for (int i = 0; i < GGML_MAX_SRC; ++i) {
            if (op->src[i] && ggml_is_quantized(op->src[i]->type)) {
                const auto t = op->src[i]->type;
                if (!device->mul_mat_s[t] && !device->mul_mat_m[t] && !device->mul_mat_l[t]) return false;
            }
        }
    }
    const bool uses_bda = (op->op == GGML_OP_IM2COL || op->op == GGML_OP_IM2COL_3D) &&'''
assert text.count(old) == 1
text = text.replace(old, new)
helper = '''
// Vulkan 1.1 drivers may ignore aggregate Vulkan12 feature structures. Keep
// capability results initialized and query the promoted extension structures.
struct webtoon_legacy_vk_features {
    bool enabled;
    bool f16_supported;
    VkPhysicalDevice16BitStorageFeatures storage{};
    VkPhysicalDeviceShaderFloat16Int8Features f16{};
    webtoon_legacy_vk_features(uint32_t api, bool f16_extension)
        : enabled(api < VK_API_VERSION_1_2), f16_supported(f16_extension) {
        storage.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_16BIT_STORAGE_FEATURES;
        f16.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES;
        storage.pNext = f16_supported ? &f16 : nullptr;
    }
    void chain(VkPhysicalDeviceFeatures2 &query, VkBaseOutStructure *&last) {
        query.pNext = &storage;
        last = reinterpret_cast<VkBaseOutStructure *>(f16_supported ? static_cast<void *>(&f16) : static_cast<void *>(&storage));
    }
    void copy(VkPhysicalDeviceVulkan11Features &eleven, VkPhysicalDeviceVulkan12Features &twelve) {
        if (!enabled) return;
        eleven.storageBuffer16BitAccess = storage.storageBuffer16BitAccess;
        twelve.shaderFloat16 = f16.shaderFloat16;
    }
};

'''
marker = 'static bool ggml_vk_external_memory_host_usable(vk::DriverId driver_id) {'
assert text.count(marker) == 1
text = text.replace(marker, helper + marker)
for declaration in ('VkPhysicalDeviceFeatures2 device_features2;',
                    'VkPhysicalDeviceVulkan11Features vk11_features;',
                    'VkPhysicalDeviceVulkan12Features vk12_features;'):
    text = text.replace(declaration, declaration[:-1] + '{};')
marker = '        last_struct = (VkBaseOutStructure *)&vk12_features;'
replacement = marker + '''
        webtoon_legacy_vk_features legacy_features(device->properties.apiVersion, fp16_compute);
        if (legacy_features.enabled) {
            legacy_features.chain(device_features2, last_struct);
            if (fp16_compute) device_extensions.push_back("VK_KHR_shader_float16_int8");
        }
'''
assert text.count(marker) == 1
text = text.replace(marker, replacement)
marker = '        vkGetPhysicalDeviceFeatures2(device->physical_device, &device_features2);'
assert text.count(marker) == 1
text = text.replace(marker, marker + '\n        legacy_features.copy(vk11_features, vk12_features);')
marker = '    last_struct = (VkBaseOutStructure *)&vk12_features;'
replacement = marker + '''
    webtoon_legacy_vk_features legacy_features(props2.properties.apiVersion, fp16_compute);
    if (legacy_features.enabled) legacy_features.chain(device_features2, last_struct);
'''
assert text.count(marker) == 2  # One is inside the indented device initialization.
# Replace the unindented line only, leaving device initialization unchanged.
text = text.replace('\n' + marker + '\n', '\n' + replacement + '\n')
marker = '    vkGetPhysicalDeviceFeatures2(physical_device, &device_features2);'
assert text.count(marker) == 1
text = text.replace(marker, marker + '\n    legacy_features.copy(vk11_features, vk12_features);')
marker = '    vkGetPhysicalDeviceFeatures2(vkdev, &device_features2);'
replacement = '''    webtoon_legacy_vk_features legacy_features(vkdev.getProperties().apiVersion, false);
    VkBaseOutStructure *legacy_last = nullptr;
    if (legacy_features.enabled) legacy_features.chain(device_features2, legacy_last);
''' + marker + '''
    if (legacy_features.enabled) return legacy_features.storage.storageBuffer16BitAccess;
'''
assert text.count(marker) == 1
text = text.replace(marker, replacement)
text = text.replace('device->buffer_device_address = vk12_features.bufferDeviceAddress;',
    'device->buffer_device_address = !legacy_features.enabled && vk12_features.bufferDeviceAddress && ggml_vk_default_dispatcher().vkGetBufferDeviceAddress != nullptr;')
text = text.replace('device->vulkan_memory_model = vk12_features.vulkanMemoryModel;',
    'device->vulkan_memory_model = !legacy_features.enabled && vk12_features.vulkanMemoryModel;')
source.write_text(text, encoding='utf-8')
ninja = root / '.tools/vulkan-bench/build.ninja'
text = ninja.read_text(encoding='utf-8')
original_path = str(original / 'ggml-vulkan.cpp').replace('\\', '/').replace(':', '$:')
isolated_path = str(source).replace('\\', '/').replace(':', '$:')
assert text.count(original_path) == 1 or text.count(isolated_path) == 1
text = text.replace(original_path, isolated_path)
ninja.write_text(text, encoding='utf-8')
cmake = Path('C:/Users/techb/AppData/Local/Android/Sdk/cmake/3.22.1/bin/cmake.exe')
subprocess.run([str(cmake), '--build', str(root / '.tools/vulkan-bench'), '--target', 'llama-bench', '-j', '2'], check=True)
