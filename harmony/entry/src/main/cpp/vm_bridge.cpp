// SPDX-License-Identifier: MIT
// Copyright (c) 2026 mzbgq
// Uses the qemu_system_entry embedding ABI demonstrated by HiSH (MIT, hackeris).
#include <napi/native_api.h>
#include <rawfile/raw_file_manager.h>
#include <rawfile/raw_file.h>
#include <hilog/log.h>
#include <atomic>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <unistd.h>
#include <vector>

namespace {
std::atomic<int> vmStatus{0}; // 0 unused, 1 running, 2 stopped, 3 failed
std::mutex errorMutex;
std::string vmError;
using QemuEntry = int (*)(int, const char **);

std::string String(napi_env env, napi_value value) {
    size_t size = 0;
    if (napi_get_value_string_utf8(env, value, nullptr, 0, &size) != napi_ok) return {};
    std::vector<char> data(size + 1);
    napi_get_value_string_utf8(env, value, data.data(), data.size(), &size);
    return std::string(data.data(), size);
}
void SetError(const std::string &message) {
    std::lock_guard<std::mutex> lock(errorMutex);
    vmError = message;
}
napi_value Bool(napi_env env, bool value) {
    napi_value result;
    napi_get_boolean(env, value, &result);
    return result;
}

struct CopyWork {
    napi_async_work work{};
    napi_deferred promise{};
    napi_ref reference{};
    NativeResourceManager *resources{};
    std::string name, destination, error;
    int64_t bytes = 0;
};
void CopyExecute(napi_env, void *data) {
    auto &job = *static_cast<CopyWork *>(data);
    RawFile64 *raw = OH_ResourceManager_OpenRawFile64(job.resources, job.name.c_str());
    if (!raw) { job.error = "内置运行资源无法打开：" + job.name; return; }
    const int64_t expected = OH_ResourceManager_GetRawFileSize64(raw);
    const std::string temporary = job.destination + ".installing";
    int output = open(temporary.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (output < 0) {
        job.error = "无法创建运行资源：" + std::string(strerror(errno));
    } else {
        std::vector<char> buffer(1024 * 1024);
        while (job.bytes < expected) {
            const int64_t count = OH_ResourceManager_ReadRawFile64(raw, buffer.data(), buffer.size());
            if (count <= 0) { job.error = "运行资源读取不完整"; break; }
            int64_t written = 0;
            while (written < count) {
                ssize_t amount = write(output, buffer.data() + written, count - written);
                if (amount < 0 && errno == EINTR) continue;
                if (amount <= 0) { job.error = "运行资源写入失败：" + std::string(strerror(errno)); break; }
                written += amount;
            }
            if (!job.error.empty()) break;
            job.bytes += count;
        }
        if (expected <= 0 || job.bytes != expected) job.error = "运行资源大小不匹配";
        if (job.error.empty() && fsync(output) != 0) job.error = "运行资源同步失败";
        close(output);
        if (job.error.empty() && rename(temporary.c_str(), job.destination.c_str()) != 0)
            job.error = "运行资源提交失败：" + std::string(strerror(errno));
        if (!job.error.empty()) unlink(temporary.c_str());
    }
    OH_ResourceManager_CloseRawFile64(raw);
}
void CopyComplete(napi_env env, napi_status status, void *data) {
    auto *job = static_cast<CopyWork *>(data);
    napi_value value;
    if (status == napi_ok && job->error.empty()) {
        napi_create_int64(env, job->bytes, &value);
        napi_resolve_deferred(env, job->promise, value);
    } else {
        napi_value message;
        napi_create_string_utf8(env, job->error.empty() ? "运行资源准备中断" : job->error.c_str(), NAPI_AUTO_LENGTH, &message);
        napi_create_error(env, nullptr, message, &value);
        napi_reject_deferred(env, job->promise, value);
    }
    OH_ResourceManager_ReleaseNativeResourceManager(job->resources);
    napi_delete_reference(env, job->reference);
    napi_delete_async_work(env, job->work);
    delete job;
}
napi_value CopyRawFile(napi_env env, napi_callback_info info) {
    size_t count = 3;
    napi_value args[3];
    napi_get_cb_info(env, info, &count, args, nullptr, nullptr);
    if (count != 3) { napi_throw_type_error(env, nullptr, "copyRawFile requires resource manager, resource name, destination"); return nullptr; }
    auto *job = new CopyWork;
    job->name = String(env, args[1]);
    job->destination = String(env, args[2]);
    job->resources = OH_ResourceManager_InitNativeResourceManager(env, args[0]);
    if (!job->resources || job->name.empty() || job->destination.empty()) {
        if (job->resources) OH_ResourceManager_ReleaseNativeResourceManager(job->resources);
        delete job;
        napi_throw_error(env, nullptr, "运行资源参数无效"); return nullptr;
    }
    napi_create_reference(env, args[0], 1, &job->reference);
    napi_value promise, name;
    napi_create_promise(env, &job->promise, &promise);
    napi_create_string_utf8(env, "WhaleTalkCopyResource", NAPI_AUTO_LENGTH, &name);
    napi_create_async_work(env, nullptr, name, CopyExecute, CopyComplete, job, &job->work);
    napi_queue_async_work(env, job->work);
    return promise;
}
napi_value StartVM(napi_env env, napi_callback_info info) {
    size_t count = 1;
    napi_value value;
    napi_get_cb_info(env, info, &count, &value, nullptr, nullptr);
    if (count != 1) return Bool(env, false);
    int expected = 0;
    if (!vmStatus.compare_exchange_strong(expected, 1)) {
        SetError(expected == 1 ? "运行环境已经启动" : "请重新打开鲸语后启动运行环境");
        return Bool(env, false);
    }
    // A phone build must never silently fall back to a JIT-only core.
    void *handle = dlopen("libqemu-system-aarch64-tci.so", RTLD_NOW | RTLD_LOCAL);
    auto entry = handle ? reinterpret_cast<QemuEntry>(dlsym(handle, "qemu_system_entry")) : nullptr;
    if (!entry) {
        const char *reason = dlerror();
        SetError(std::string("无法加载手机运行内核：") + (reason ? reason : "入口缺失"));
        vmStatus.store(3);
        return Bool(env, false);
    }
    std::vector<std::string> arguments;
    std::istringstream input(String(env, value));
    std::string line;
    while (std::getline(input, line)) arguments.push_back(line);
    if (arguments.size() < 2) { SetError("运行参数不完整"); vmStatus.store(3); return Bool(env, false); }
    std::thread([arguments, entry]() {
        std::vector<const char *> argv;
        for (const auto &argument : arguments) argv.push_back(argument.c_str());
        argv.push_back(nullptr);
        const int result = entry(static_cast<int>(arguments.size()), argv.data());
        if (result != 0) SetError("运行环境退出，代码 " + std::to_string(result));
        vmStatus.store(result == 0 ? 2 : 3);
        OH_LOG_Print(LOG_APP, LOG_INFO, 0x3300, "WhaleTalk", "VM exited: %{public}d", result);
    }).detach();
    return Bool(env, true);
}
napi_value GetVMStatus(napi_env env, napi_callback_info) {
    napi_value value;
    napi_create_int32(env, vmStatus.load(), &value);
    return value;
}
napi_value GetVMError(napi_env env, napi_callback_info) {
    std::lock_guard<std::mutex> lock(errorMutex);
    napi_value value;
    napi_create_string_utf8(env, vmError.c_str(), vmError.size(), &value);
    return value;
}
napi_value Init(napi_env env, napi_value exports) {
    napi_property_descriptor descriptors[] = {
        {"copyRawFile", nullptr, CopyRawFile, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"startVM", nullptr, StartVM, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"getVMStatus", nullptr, GetVMStatus, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"getVMError", nullptr, GetVMError, nullptr, nullptr, nullptr, napi_default, nullptr}
    };
    napi_define_properties(env, exports, sizeof(descriptors) / sizeof(descriptors[0]), descriptors);
    return exports;
}
napi_module module = {1, 0, nullptr, Init, "whaletalk_vm", nullptr, {0}};
}
extern "C" __attribute__((constructor)) void RegisterWhaleTalkModule() { napi_module_register(&module); }
