package com.lizongying.mytv0.data

/**
 * my-tv-server GET /api/v1/config 聚合配置。
 * 字段名与服务端 JSON 保持一致（snake_case），由 gson 直接映射。
 */
data class RemoteConfig(
    val config_version: Int?,
    val updated_at: String?,
    val live_sources: List<RemoteLiveSource>?,
    val epg_url: String?,
    val logo_base_url: String?,
    val update: RemoteUpdate?,
)

data class RemoteLiveSource(
    val id: String?,
    val name: String?,
    val url: String,
    val format: String?,
)

data class RemoteUpdate(
    val version_code: Int?,
    val version_name: String?,
    val apk_name: String?,
    val apk_url: String?,
    val changelog: String?,
)
