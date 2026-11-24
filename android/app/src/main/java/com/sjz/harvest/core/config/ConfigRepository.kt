package com.sjz.harvest.core.config

interface ConfigRepository {
    suspend fun loadAppConfig(): AppConfig

    suspend fun loadProfile(): UiProfile

    suspend fun loadCatalog(): Catalog

    suspend fun loadPlan(): Plan

    suspend fun savePlan(plan: Plan)

    suspend fun saveProfile(profile: UiProfile)

    suspend fun saveAppConfig(config: AppConfig)

    /** 把 assets 中的默认配置重新覆盖到内部存储（用于恢复出厂）。 */
    suspend fun resetToDefaults()
}
