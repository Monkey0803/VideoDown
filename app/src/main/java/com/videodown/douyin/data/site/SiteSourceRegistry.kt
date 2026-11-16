package com.videodown.douyin.data.site

import com.videodown.douyin.domain.model.SiteChannel
import com.videodown.douyin.domain.model.SiteCapabilities
import com.videodown.douyin.domain.model.SiteSource

class SiteSourceRegistry(
    adapters: List<SiteSourceAdapter> = listOf(KUBB_ADAPTER),
) {
    private val sources = adapters.map(SiteSourceAdapter::source)
    init {
        require(sources.map(SiteSource::id).distinct().size == sources.size) { "Duplicate site source id" }
        sources.forEach { source ->
            require(source.channels.map(SiteChannel::id).distinct().size == source.channels.size) {
                "Duplicate channel id for ${source.id}"
            }
        }
    }
    private val byId = sources.associateBy(SiteSource::id)
    private val adaptersById = adapters.associateBy { it.source.id }
    val all: List<SiteSource> = sources.toList()

    fun get(id: String): SiteSource? = byId[id]
    fun adapter(id: String): SiteSourceAdapter? = adaptersById[id]

    companion object {
        val KUBB = SiteSource(
            id = "kubb",
            homeUrl = "https://m.kubb.tv",
            channels = listOf(
                SiteChannel("movie", "/movie"),
                SiteChannel("drama", "/drama"),
                SiteChannel("variety", "/variety"),
                SiteChannel("anime", "/anime"),
                SiteChannel("short", "/short"),
                SiteChannel("sport", "/list?cid=0%2C1%2C95"),
                SiteChannel("documentary", "/documentary"),
                SiteChannel("chinese", "/list/chinese"),
                SiteChannel("game", "/list/game"),
                SiteChannel("news", "/list/news"),
                SiteChannel("yule", "/list/yule"),
                SiteChannel("life", "/list/life"),
                SiteChannel("music", "/list/music"),
                SiteChannel("fashion", "/list/fashion"),
                SiteChannel("tech", "/list/tech"),
                SiteChannel("svideo", "/short-videos"),
            ),
            navigationHosts = setOf("kubb.tv"),
            capabilities = SiteCapabilities(
                onlinePlayback = true,
                download = true,
                anonymousOnly = true,
                live = false,
            ),
            blockedPathPrefixes = setOf(
                "/login", "/user", "/account", "/member", "/vip", "/vip-center",
                "/pay", "/payment", "/checkout", "/order", "/shop",
            ),
            mediaHostSuffixes = setOf("pipecdn.vip", "kubb.tv"),
        )
        val KUBB_ADAPTER = SiteSourceAdapter(KUBB, KUBB_PAGE_METADATA_JAVASCRIPT, KUBB_INTERACTION_JAVASCRIPT)
    }
}
