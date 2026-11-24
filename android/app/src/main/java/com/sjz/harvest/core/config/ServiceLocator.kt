package com.sjz.harvest.core.config

import android.content.Context
import com.sjz.harvest.core.logging.LogBus
import kotlinx.serialization.json.Json

/**
 * 极简服务定位器。App 内只有一个进程、一套配置，因此不做复杂的 DI 容器。
 *
 * 使用约定：
 * - [init] 必须在 Application.onCreate / 任何业务调用之前执行（SjzApplication 已处理）；
 * - 其余方法均为线程安全，配置从磁盘读取，保证设置页保存后能立即生效。
 */
object ServiceLocator {

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var repository: ConfigRepository? = null

    private val jsonInstance: Json = AssetConfigRepository.DefaultJson

    fun init(context: Context) {
        if (appContext != null) return
        synchronized(this) {
            if (appContext != null) return
            val ctx = context.applicationContext
            appContext = ctx
            repository = AssetConfigRepository(ctx, LogBus, jsonInstance)
            // 先给日志一个合理的默认上限；加载到 appConfig 后 Controller 会按配置重设。
            LogBus.install(ctx, DEFAULT_LOG_LINES)
        }
    }

    fun configRepository(): ConfigRepository =
        repository ?: throw IllegalStateException("ServiceLocator 尚未初始化，请先调用 init(context)")

    fun json(): Json = jsonInstance

    suspend fun appConfig(): AppConfig = configRepository().loadAppConfig()

    suspend fun profile(): UiProfile = configRepository().loadProfile()

    suspend fun catalogIndex(): CatalogIndex = CatalogIndex(configRepository().loadCatalog())

    suspend fun loadPlan(): Plan = configRepository().loadPlan()

    private const val DEFAULT_LOG_LINES = 300
}
