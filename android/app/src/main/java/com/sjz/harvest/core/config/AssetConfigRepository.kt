package com.sjz.harvest.core.config

import android.content.Context
import com.sjz.harvest.core.logging.AppLogger
import com.sjz.harvest.core.logging.LogBus
import com.sjz.harvest.core.model.SjzException
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * 把 assets/config/ 下的 *.json 复制到 filesDir/config 的配置仓库。
 *
 * 设计要点：
 * - 首次启动时按需复制；已存在的用户配置不会被覆盖；
 * - 所有读写串行化（Mutex），避免 UI 保存与自动化循环同时读写；
 * - 写文件使用「临时文件 + rename」原子替换，避免断电/崩溃留下半个 JSON；
 * - 任何解析/IO 失败都包装为 [SjzException.ConfigError]，由编排层统一提示。
 */
class AssetConfigRepository(
    context: Context,
    private val logger: AppLogger = LogBus,
    private val json: Json = DefaultJson,
) : ConfigRepository {

    private val appContext: Context = context.applicationContext

    private val configDir: File
        get() = File(appContext.filesDir, CONFIG_DIR_NAME)

    private val ioMutex = Mutex()

    @Volatile
    private var seeded: Boolean = false

    override suspend fun loadAppConfig(): AppConfig = withIo {
        val app = decodeAppConfig(readTextLocked(APP_CONFIG_FILE))
        logger.d(TAG, "app_config 已加载: ${app.gamePackage}")
        app
    }

    override suspend fun loadProfile(): UiProfile = withIo {
        val app = decodeAppConfig(readTextLocked(APP_CONFIG_FILE))
        decodeProfile(readTextLocked(app.profileFile))
    }

    override suspend fun loadCatalog(): Catalog = withIo {
        val app = decodeAppConfig(readTextLocked(APP_CONFIG_FILE))
        decodeCatalog(readTextLocked(app.catalogFile))
    }

    override suspend fun loadPlan(): Plan = withIo {
        val app = decodeAppConfig(readTextLocked(APP_CONFIG_FILE))
        decodePlan(readTextLocked(app.planFile))
    }

    override suspend fun savePlan(plan: Plan) = withIo {
        val app = decodeAppConfig(readTextLocked(APP_CONFIG_FILE))
        writeAtomic(File(configDir, app.planFile), json.encodeToString(Plan.serializer(), plan))
        logger.i(TAG, "plan 已保存: ${app.planFile}")
    }

    override suspend fun saveProfile(profile: UiProfile) = withIo {
        val app = decodeAppConfig(readTextLocked(APP_CONFIG_FILE))
        writeAtomic(
            File(configDir, app.profileFile),
            json.encodeToString(UiProfile.serializer(), profile),
        )
        logger.i(TAG, "profile 已保存: ${app.profileFile}")
    }

    override suspend fun saveAppConfig(config: AppConfig) = withIo {
        // 先算出当前使用的文件名，避免写到一半时配置改名造成不一致。
        writeAtomic(
            File(configDir, APP_CONFIG_FILE),
            json.encodeToString(AppConfig.serializer(), config),
        )
        logger.i(TAG, "app_config 已保存")
    }

    override suspend fun resetToDefaults() = withIo {
        ensureDirLocked()
        val assets = listAssetConfigsLocked()
        if (assets.isEmpty()) {
            throw SjzException.ConfigError("assets/$CONFIG_DIR_NAME 中没有可恢复的配置文件")
        }
        for (name in assets) {
            copyAssetLocked(name, File(configDir, name), overwrite = true)
        }
        seeded = true
        logger.i(TAG, "已恢复默认配置（${assets.size} 个文件）")
    }

    // ---------------------------------------------------------------------
    // 内部实现
    // ---------------------------------------------------------------------

    private suspend fun <T> withIo(block: () -> T): T = try {
        withContext(Dispatchers.IO) {
            ioMutex.withLock {
                ensureSeededLocked()
                block()
            }
        }
    } catch (e: SjzException.ConfigError) {
        throw e
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        throw SjzException.ConfigError("配置读写失败: ${e.message}", e)
    }

    /** 只在持锁时调用。 */
    private fun ensureSeededLocked() {
        if (seeded) return
        ensureDirLocked()
        val assets = listAssetConfigsLocked()
        if (assets.isEmpty()) {
            // assets 为空一般意味着打包配置缺失；但如果内部已有文件，仍允许继续。
            if (File(configDir, APP_CONFIG_FILE).exists()) {
                seeded = true
                return
            }
            throw SjzException.ConfigError("assets/$CONFIG_DIR_NAME 中没有配置文件")
        }
        var copied = 0
        for (name in assets) {
            val target = File(configDir, name)
            if (!target.exists()) {
                copyAssetLocked(name, target, overwrite = false)
                copied++
            }
        }
        seeded = true
        if (copied > 0) {
            logger.i(TAG, "首次初始化配置：已从 assets 复制 $copied 个文件")
        }
    }

    private fun ensureDirLocked() {
        val dir = configDir
        if (!dir.exists() && !dir.mkdirs()) {
            throw SjzException.ConfigError("无法创建配置目录: ${dir.absolutePath}")
        }
    }

    private fun listAssetConfigsLocked(): List<String> {
        val names = try {
            appContext.assets.list(CONFIG_DIR_NAME)?.toList().orEmpty()
        } catch (e: Exception) {
            throw SjzException.ConfigError("读取 assets 配置目录失败", e)
        }
        return names.filter { it.endsWith(".json", ignoreCase = true) }.sorted()
    }

    private fun copyAssetLocked(assetName: String, target: File, overwrite: Boolean) {
        if (!overwrite && target.exists()) return
        val temp = File(target.parentFile, target.name + TEMP_SUFFIX)
        try {
            appContext.assets.open("$CONFIG_DIR_NAME/$assetName").use { input ->
                FileOutputStream(temp).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            replaceFile(temp, target)
        } catch (e: SjzException.ConfigError) {
            temp.delete()
            throw e
        } catch (e: Exception) {
            temp.delete()
            throw SjzException.ConfigError("复制默认配置失败: $assetName", e)
        }
    }

    private fun readTextLocked(fileName: String): String {
        val file = File(configDir, fileName)
        if (!file.exists()) {
            throw SjzException.ConfigError("配置文件不存在: $fileName")
        }
        return try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            throw SjzException.ConfigError("读取配置文件失败: $fileName", e)
        }
    }

    private fun decodeAppConfig(text: String): AppConfig = decode(APP_CONFIG_FILE) {
        json.decodeFromString(AppConfig.serializer(), text)
    }

    private fun decodeProfile(text: String): UiProfile = decode("profile") {
        json.decodeFromString(UiProfile.serializer(), text)
    }

    private fun decodeCatalog(text: String): Catalog = decode("catalog") {
        json.decodeFromString(Catalog.serializer(), text)
    }

    private fun decodePlan(text: String): Plan = decode("plan") {
        json.decodeFromString(Plan.serializer(), text)
    }

    private inline fun <T> decode(name: String, block: () -> T): T = try {
        block()
    } catch (e: SjzException.ConfigError) {
        throw e
    } catch (e: Exception) {
        throw SjzException.ConfigError("解析配置失败: $name", e)
    }

    /** 临时文件 + 原子 rename。 */
    private fun writeAtomic(target: File, text: String) {
        ensureDirLocked()
        val temp = File(target.parentFile, target.name + TEMP_SUFFIX)
        try {
            FileOutputStream(temp).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            replaceFile(temp, target)
        } catch (e: SjzException.ConfigError) {
            temp.delete()
            throw e
        } catch (e: Exception) {
            temp.delete()
            throw SjzException.ConfigError("写入配置文件失败: ${target.name}", e)
        }
    }

    private fun replaceFile(temp: File, target: File) {
        // API 26+ 支持 java.nio；优先 ATOMIC_MOVE，失败时退化为 REPLACE_EXISTING。
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            return
        } catch (_: Exception) {
            // 部分文件系统不支持 ATOMIC_MOVE，继续尝试普通覆盖。
        }
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            return
        } catch (e: Exception) {
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                throw SjzException.ConfigError("替换配置文件失败: ${target.name}", e)
            }
        }
    }

    companion object {
        private const val TAG = "ConfigRepo"
        private const val CONFIG_DIR_NAME = "config"
        private const val APP_CONFIG_FILE = "app_config.json"
        private const val TEMP_SUFFIX = ".tmp"

        /**
         * 全局共享的 Json 实例。注意：prettyPrint 便于用户直接查看/导出，
         * ignoreUnknownKeys 保证旧版本客户端能读取新增字段。
         */
        val DefaultJson: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }
    }
}
