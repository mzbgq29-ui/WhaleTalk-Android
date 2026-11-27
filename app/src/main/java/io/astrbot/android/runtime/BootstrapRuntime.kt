package io.astrbot.android.runtime

import android.content.Context
import android.os.Build
import android.system.Os
import io.astrbot.android.AppText
import io.astrbot.android.LocalizedStateException
import io.astrbot.android.R
import io.astrbot.android.checkLocalized
import io.astrbot.android.core.ArchivePaths
import io.astrbot.android.core.SecretRedactor
import io.astrbot.android.requireLocalized
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/** The launch executables are installed from APK jniLibs, never chmod'd from writable assets. */
class BootstrapRuntime(private val context: Context) {
    val rootfs: File = File(context.filesDir, "runtime-data/ubuntu")
    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    private val lock get() = installationLock
    val abi: String get() = if (nativeDir.path.contains("x86_64")) "x86_64" else Build.SUPPORTED_ABIS.first {
        it == "arm64-v8a" || it == "x86_64"
    }

    suspend fun install(progress: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        lock.withLock {
            val manifest = bundledManifest()
            val marker = File(rootfs, ".astrbot-bootstrap.json")
            if (DurableFiles.exists(marker)) {
                val installed = requireNotNull(DurableFiles.read(marker)) { "运行记录不完整，原数据已保留" }
                // An APK replacement does not authorize replacing a user's working guest.
                requireLocalized(installed.getString("abi") == abi) { AppText.get(R.string.ui_the_installed_runtime_architecture_differs_from_this_apk_files) }
                val prefs = context.getSharedPreferences("manager", Context.MODE_PRIVATE)
                if (prefs.getString(PREF_PROOT_CHILD_VERIFIED_KERNEL, null) != currentKernelVersion()) {
                    verifyChildProcessAt(rootfs, complete = installed.optString("kind") == "complete")
                }
                progress(AppText.get(R.string.ui_ubuntu_runtime_is_ready))
                return@withLock
            }
            val staging = File(rootfs.parentFile, "ubuntu.installing")
            prepareAt(staging, manifest, progress)
            // Only an interrupted, uncommitted bootstrap can exist here.
            checkLocalized(!rootfs.exists()) { AppText.get(R.string.ui_unrecognized_existing_runtime_initialization_stopped_data_is_preserved) }
            DurableFiles.move(staging, rootfs)
            progress(AppText.get(R.string.ui_ubuntu_runtime_is_ready))
        }
    }

    internal fun bundledManifest(): JSONObject = context.assets.open("bootstrap/$abi/manifest.json").bufferedReader().use {
        JSONObject(it.readText())
    }

    internal suspend fun prepareBundled(staging: File, progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        lock.withLock { prepareAt(staging, bundledManifest(), progress) }
    }

    private suspend fun prepareAt(staging: File, manifest: JSONObject, progress: (String) -> Unit) {
            val assetBase = "bootstrap/$abi"
            requireLocalized(manifest.getString("abi") == abi) { AppText.get(R.string.ui_bundled_runtime_architecture_mismatch) }
            progress(AppText.get(R.string.ui_verifying_bundled_ubuntu_resources))
            val digest = MessageDigest.getInstance("SHA-256")
            var bytesRead = 0L
            context.assets.open("$assetBase/ubuntu.tar.bin").use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    bytesRead += count
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            val expected = manifest.getString("sha256")
            requireLocalized(actual == expected) {
                // Keep checksum details in exported runtime logs, with a short recovery hint in the UI.
                runCatching {
                    RuntimeStore.log(context, LogCategory.RUNTIME).append(
                        "${Instant.now()} Ubuntu asset integrity mismatch: " +
                            "asset=$assetBase/ubuntu.tar.bin abi=$abi bytesRead=$bytesRead " +
                            "expectedSha256=$expected actualSha256=$actual"
                    )
                }
                AppText.get(R.string.ui_ubuntu_resource_integrity_check_failed)
            }
            requireLocalized(manifest.getLong("unpackedBytes") in 1..(12L * 1024 * 1024 * 1024)) { AppText.get(R.string.ui_resource_size_exceeds_the_supported_limit) }
            RuntimeStorageAdvice.fromManifest(manifest, context.filesDir.usableSpace)?.takeIf { it.shouldWarn }?.let {
                progress(it.message)
            }
            DurableFiles.delete(staging, writableGuestDirectories = true)
            checkLocalized(staging.mkdirs()) { AppText.get(R.string.ui_cannot_create_runtime_staging_directory) }
            try {
                progress(AppText.get(R.string.ui_extracting_ubuntu_runtime))
                extract(assetBase, staging, manifest.getLong("unpackedBytes"), progress)
                prepareDesktopSnapshot(staging, manifest, progress)
                listOf("root", "tmp", "proc", "sys", "dev", "run", "var/tmp", "var/log", "var/cache", "persistent").forEach { File(staging, it).mkdirs() }
                Os.chmod(File(staging, "tmp").path, 1023)
                Os.chmod(File(staging, "var/tmp").path, 1023)
                File(staging, "etc/resolv.conf").writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
                progress(AppText.get(R.string.ui_verifying_extracted_runtime))
                verifyAt(staging, complete = manifest.optString("kind") == "complete")
                DurableFiles.write(File(staging, ".astrbot-bootstrap.json"), manifest)
            } catch (failure: Throwable) {
                DurableFiles.delete(staging, writableGuestDirectories = true)
                throw failure
            }
    }

    private suspend fun verifyAt(staging: File, complete: Boolean) {
        // Run the probes directly so shell startup and its child processes cannot block verification.
        if (!complete) {
            verifyCommand(staging, "PRoot startup and /etc/os-release", 20, "/usr/bin/test", "-f", "/etc/os-release")
            verifyChildProcessAt(staging, complete = false)
            return
        }
        verifyCommand(staging, "PRoot startup", 20, "/bin/true")
        verifyCommand(staging, "Python dependencies", 180,
            "/usr/bin/env", "OPENBLAS_NUM_THREADS=1", "OMP_NUM_THREADS=1",
            "/opt/astrbot-venv/bin/python", "-I", "-B", "-u", "-c", """
                from pathlib import Path

                for path in ("/var/lib/bigfish/runtime-components.json", "/opt/astrbot/main.py"):
                    print(f"Checking {path}")
                    if not Path(path).is_file():
                        raise SystemExit(f"Missing runtime file: {path}")

                print("Loading Quart")
                import quart
                print("Loading FastAPI")
                import fastapi
                print("Loading FAISS")
                import faiss
                print("runtime-ok")
            """.trimIndent())
        verifyChildProcessAt(staging, complete = true)
    }

    private suspend fun verifyChildProcessAt(guestRoot: File, complete: Boolean) {
        val kernel = currentKernelVersion()
        val prefs = context.getSharedPreferences("manager", Context.MODE_PRIVATE)
        val initialDisableSeccomp = isSeccompDisabledForCurrentKernel(context)
        val python = if (complete) "/opt/astrbot-venv/bin/python" else "/usr/bin/python3"
        val script = """
            import subprocess
            out = subprocess.check_output(['/bin/echo', 'child-ok'])
            assert out.strip() == b'child-ok', f'unexpected output: {out!r}'
            print('child-ok')
        """.trimIndent()
        val result = probeProotChildProcess(initialDisableSeccomp) { disableSeccomp ->
            var timedOutOutput: String? = null
            try {
                runCommand(
                    processBuilderAt(guestRoot, disableSeccomp, "/usr/bin/env", python, "-I", "-B", "-u", "-c", script),
                    15,
                ) { output ->
                    timedOutOutput = output
                    runCatching {
                        val detail = SecretRedactor.redact(output).trim().ifEmpty { "No output from child-process probe" }
                        RuntimeStore.log(context, LogCategory.RUNTIME).append(
                            "${Instant.now()} PRoot child-process probe (kernel $kernel, prootNoSeccomp=$disableSeccomp) timed out after 15s: $detail"
                        )
                    }
                }
            } catch (failure: LocalizedStateException) {
                val detail = SecretRedactor.redact(timedOutOutput ?: failure.message.orEmpty()).trim().ifEmpty {
                    "Timed out after 15s"
                }
                CommandResult(-1, detail)
            }
        }
        when (result) {
            is ProotChildProbeResult.Passed -> {
                val editor = prefs.edit().putString(PREF_PROOT_CHILD_VERIFIED_KERNEL, kernel)
                if (result.disableSeccomp) editor.putString(PREF_PROOT_NO_SECCOMP_KERNEL, kernel)
                check(editor.commit()) { "无法保存运行环境探测结果，请检查存储空间" }
                runCatching {
                    RuntimeStore.log(context, LogCategory.RUNTIME).append(
                        "${Instant.now()} PRoot child-process probe passed (kernel $kernel, prootNoSeccomp=${result.disableSeccomp})"
                    )
                }
            }
            is ProotChildProbeResult.Failed -> {
                runCatching {
                    RuntimeStore.log(context, LogCategory.RUNTIME).append(
                        "${Instant.now()} PRoot child-process probe failed (device=${Build.MANUFACTURER} ${Build.MODEL}, " +
                            "android=${Build.VERSION.RELEASE}/API${Build.VERSION.SDK_INT}, kernel=$kernel, " +
                            "retriedWithoutSeccomp=${result.retriedWithoutSeccomp}): ${SecretRedactor.redact(result.detail)}"
                    )
                }
                throw LocalizedStateException(AppText.get(R.string.ui_runtime_proot_child_process_failed, kernel))
            }
        }
    }

    private suspend fun verifyCommand(staging: File, phase: String, timeoutSeconds: Long, vararg command: String) {
        val check = runCommand(processBuilderAt(staging, *command), timeoutSeconds) { output ->
            runCatching {
                val detail = SecretRedactor.redact(output).trim().ifEmpty { "No output from verification command" }
                val kernel = currentKernelVersion()
                RuntimeStore.log(context, LogCategory.RUNTIME).append(
                    "${Instant.now()} Ubuntu verification ($phase, kernel $kernel) timed out after ${timeoutSeconds}s: $detail"
                )
            }
        }
        requireLocalized(check.exitCode == 0) {
            val detail = check.output.takeLast(3000).trim().ifEmpty { "$phase exited with code ${check.exitCode}" }
            AppText.get(R.string.ui_runtime_verification_failed, detail)
        }
    }

    private suspend fun prepareDesktopSnapshot(staging: File, base: JSONObject, progress: (String) -> Unit) {
        if (base.optString("kind") != "complete" || abi != "arm64-v8a") return
        val asset = "desktop-snapshot/$abi"
        val snapshot = try {
            context.assets.open("$asset/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        } catch (_: java.io.FileNotFoundException) { return } // Older/custom complete images retain offline preparation.
        checkLocalized(snapshot.getInt("schemaVersion") == 1 && snapshot.getString("baseRuntimeSHA256") == base.getString("sha256")) {
            AppText.get(R.string.qq_bundle_missing)
        }
        val bundle = context.assets.open("desktop-packages/$abi/manifest.json").use { it.readBytes() }
        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        checkLocalized(snapshot.getString("bundleSHA256") == sha(bundle)) { AppText.get(R.string.qq_bundle_missing) }
        progress(AppText.get(R.string.qq_snapshot_verify))
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        context.assets.open("$asset/desktop.tar.bin").use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count); size += count
            }
        }
        checkLocalized(size == snapshot.getLong("bytes") &&
            digest.digest().joinToString("") { "%02x".format(it) } == snapshot.getString("sha256")) { AppText.get(R.string.qq_bundle_missing) }
        // This overlay contains dpkg state. Apply it only before committing a new
        // bootstrap, never to an existing guest with user-installed packages.
        check(!File(staging, ".astrbot-bootstrap.json").exists())
        val unpacked = snapshot.getLong("unpackedBytes")
        checkLocalized(unpacked in 1..(2L * 1024 * 1024 * 1024)) { AppText.get(R.string.qq_bundle_missing) }
        checkLocalized(staging.usableSpace >= unpacked + 128L * 1024 * 1024) {
            AppText.get(R.string.desktop_space)
        }
        val root = staging.canonicalFile.toPath()
        val removed = snapshot.getJSONArray("removed")
        for (index in 0 until removed.length()) {
            val path = ArchivePaths.entry(root, removed.getString(index), allowUnixEscapes = true)
            check(path.parent.toFile().canonicalFile.toPath().startsWith(root))
            java.nio.file.Files.deleteIfExists(path)
        }
        extract(asset, staging, unpacked, progress, image = "desktop.tar.bin", replace = true,
            entriesText = R.string.qq_snapshot_extract)
        val marker = JSONObject(File(staging, "var/lib/astrbot-manager/desktop-packages.json").readText())
        checkLocalized(marker.getString("bundleSHA256") == snapshot.getString("bundleSHA256") &&
            marker.getString("baseRuntimeSHA256") == base.getString("sha256")) { AppText.get(R.string.qq_bundle_missing) }
    }

    private suspend fun extract(assetBase: String, staging: File, expectedBytes: Long, progress: (String) -> Unit,
                                image: String = "ubuntu.tar.bin", replace: Boolean = false,
                                entriesText: Int = R.string.ui_extracting_ubuntu_entries) {
        context.assets.open("$assetBase/$image").use { input ->
            RuntimeArchiveExtractor.extract(input,staging,expectedBytes,progress,replace,entriesText)
        }
    }

    fun processBuilder(vararg command: String): ProcessBuilder = processBuilderAt(rootfs, *command)

    fun terminalProcessBuilder(columns: Int, rows: Int, vararg command: String): ProcessBuilder = processBuilder(*command).apply {
        require(columns in 1..1000 && rows in 1..1000)
        val existing = command()
        command(listOf(existing.first(), File(nativeDir, "libterminal-runner.so").path, columns.toString(), rows.toString()) + existing.drop(1))
    }

    private fun processBuilderAt(guestRoot: File, vararg command: String): ProcessBuilder =
        processBuilderAt(guestRoot, isSeccompDisabledForCurrentKernel(context), *command)

    private fun processBuilderAt(guestRoot: File, disableSeccomp: Boolean, vararg command: String): ProcessBuilder {
        val tmp = File(context.cacheDir, "proot-tmp").apply { mkdirs() }
        // Android has no /dev/shm; Electron needs a private writable shared-memory directory.
        val sharedMemory = File(context.cacheDir, "guest-shm").apply { mkdirs() }
        val persistent = File(context.filesDir, "persistent").apply { mkdirs() }
        val manager = File(context.filesDir, "manager").apply { mkdirs() }
        io.astrbot.android.device.DeviceBridge.prepareGuest(context)
        val sharedFiles = File(context.getExternalFilesDir(null) ?: context.filesDir, "shared").apply { mkdirs() }
        // procfs reports Android host paths even inside PRoot. Keep our own
        // mapped files readable by those paths as well as their guest paths.
        // Include both spellings: /data/user/0 can resolve to /data/data.
        val hostPathBindings = listOf(guestRoot, persistent, manager)
            .flatMap { listOf(it.absolutePath, it.canonicalPath) }
            .distinct()
            .flatMap { listOf("-b", "$it:$it") }
        return ProcessBuilder(listOf(
            File(nativeDir, "libprocess-runner.so").path,
            File(nativeDir, "libproot.so").path,
            "--kill-on-exit", "--link2symlink", "-0", "-r", guestRoot.path,
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "${sharedMemory.path}:/dev/shm",
            "-b", "${persistent.path}:/persistent", "-b", "${manager.path}:/manager", "-b", "${sharedFiles.path}:/android-share",
        ) + hostPathBindings + listOf(
            "-w", "/root",
            "/usr/bin/env", "-i", "HOME=/root", "USER=root", "TMPDIR=/tmp",
            "PATH=/manager/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", "ANDROID_SHARED_DIR=/android-share",
            "LANG=C.UTF-8", "TERM=xterm-256color", "SHELL=/bin/bash",
        ) + command).apply {
            directory(context.filesDir)
            redirectErrorStream(true)
            environment().apply {
                remove("LD_PRELOAD")
                put("LD_LIBRARY_PATH", nativeDir.path)
                put("PROOT_LOADER", File(nativeDir, "libproot-loader.so").path)
                put("PROOT_LOADER_32", File(nativeDir, "libproot-loader32.so").path)
                put("PROOT_TMP_DIR", tmp.path)
                put("TMPDIR", "/tmp")
                put("HOME", "/root")
                put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                put("LANG", "C.UTF-8")
                put("TERM", "xterm-256color")
                if (disableSeccomp) put("PROOT_NO_SECCOMP", "1")
            }
        }
    }

    suspend fun execute(vararg command: String, timeoutSeconds: Long = 30): CommandResult =
        runCommand(processBuilder(*command), timeoutSeconds)

    private suspend fun runCommand(builder: ProcessBuilder, timeoutSeconds: Long, onTimeout: (String) -> Unit = {}): CommandResult =
        runRuntimeCommand(timeoutSeconds, builder::start) { output ->
            onTimeout(output)
            LocalizedStateException(AppText.get(R.string.ui_command_timed_out))
        }

    companion object {
        private val installationLock = Mutex()
        private const val PREF_PROOT_NO_SECCOMP_KERNEL = "proot_no_seccomp_kernel"
        private const val PREF_PROOT_CHILD_VERIFIED_KERNEL = "proot_child_verified_kernel"

        internal fun currentKernelVersion(): String =
            System.getProperty("os.version")?.trim().orEmpty().ifEmpty { "unknown" }

        internal fun isSeccompDisabledForCurrentKernel(context: Context): Boolean =
            context.getSharedPreferences("manager", Context.MODE_PRIVATE)
                .getString(PREF_PROOT_NO_SECCOMP_KERNEL, null) == currentKernelVersion()
    }
}

internal sealed class ProotChildProbeResult {
    data class Passed(val disableSeccomp: Boolean) : ProotChildProbeResult()
    data class Failed(val detail: String, val retriedWithoutSeccomp: Boolean) : ProotChildProbeResult()
}

internal suspend fun probeProotChildProcess(
    initialDisableSeccomp: Boolean,
    runProbe: suspend (disableSeccomp: Boolean) -> CommandResult,
): ProotChildProbeResult {
    val first = runProbe(initialDisableSeccomp)
    if (first.exitCode == 0) return ProotChildProbeResult.Passed(initialDisableSeccomp)
    if (!initialDisableSeccomp) {
        val retry = runProbe(true)
        if (retry.exitCode == 0) return ProotChildProbeResult.Passed(disableSeccomp = true)
        val detail = retry.output.ifBlank { first.output }.trim().ifEmpty {
            "PRoot child process exited with code ${retry.exitCode} (initial ${first.exitCode})"
        }
        return ProotChildProbeResult.Failed(detail, retriedWithoutSeccomp = true)
    }
    val detail = first.output.trim().ifEmpty { "PRoot child process exited with code ${first.exitCode}" }
    return ProotChildProbeResult.Failed(detail, retriedWithoutSeccomp = false)
}
