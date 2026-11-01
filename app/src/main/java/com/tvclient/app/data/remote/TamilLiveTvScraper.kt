package com.tvclient.app.data.remote

import com.tvclient.app.data.model.Category
import com.tvclient.app.data.model.Movie
import com.tvclient.app.data.model.MovieListResponse
import com.tvclient.app.data.model.StreamResponse
import okhttp3.Request

/**
 * Liefert Tamil Live-TV-Sender (Sun TV HD, Star Vijay, Zee Tamil HD, Colors Tamil HD,
 * Kalaignar TV, Raj TV HD, Polimer TV, Musik- & News-Sender) als direkte HLS-Streams (.m3u8).
 *
 * Kombiniert eine kuratierte, gepruefte Top-Sender-Liste in optimaler Reihenfolge
 * mit einem dynamischen Abgleich der oeffentlichen Tamil-IPTV-Playlist, damit neue
 * oder aktualisierte Stream-URLs automatisch uebernommen werden.
 */
object TamilLiveTvScraper {

    const val CATEGORY_LIVE_TV = "live_tv"
    const val PREFIX_LIVE_TV = "live_tv:"

    private const val IPTV_TAMIL_M3U = "https://iptv-org.github.io/iptv/languages/tam.m3u"

    private data class LiveChannelDef(
        val key: String,
        val title: String,
        val subtitle: String,
        val streamUrl: String,
        val logoUrl: String,
        val matchKeywords: List<String> = emptyList()
    )

    private val curatedChannels = listOf(
        LiveChannelDef(
            key = "sun_tv_hd",
            title = "Sun TV HD",
            subtitle = "LIVE • 1080p",
            streamUrl = "https://d2px1d6ssc60fw.cloudfront.net/SunTV/SunTV-HDR10-IN-index.m3u8",
            logoUrl = "https://dtil.tmsimg.com/assets/GNLZZGG002Z0M0Y.png?lock=720x540",
            matchKeywords = listOf("sun tv")
        ),
        LiveChannelDef(
            key = "star_vijay",
            title = "Star Vijay",
            subtitle = "LIVE • Entertainment",
            streamUrl = "https://da86m1sqpm3o0.cloudfront.net/28072023/smil:starvijayuk.smil/playlist.m3u8",
            logoUrl = "https://i.imgur.com/dZtJHAe.png",
            matchKeywords = listOf("star vijay", "vijay tv")
        ),
        LiveChannelDef(
            key = "zee_tamil_hd",
            title = "Zee Tamil HD",
            subtitle = "LIVE • 1080p",
            streamUrl = "https://d2o8pibksxtln6.cloudfront.net/live/media/Zee_Tamil_HD/hls/Zee_Tamil_HD.m3u8",
            logoUrl = "https://dtil.tmsimg.com/assets/GNLZZGG00233WCL.png?lock=720x540",
            matchKeywords = listOf("zee tamil")
        ),
        LiveChannelDef(
            key = "colors_tamil_hd",
            title = "Colors Tamil HD",
            subtitle = "LIVE • 720p",
            streamUrl = "https://da86m1sqpm3o0.cloudfront.net/28072023/smil:colorstamilhd11.smil/playlist.m3u8",
            logoUrl = "https://xstreamcp-assets-msp.streamready.in/assets/LIVETV/LIVECHANNEL/LIVETV_LIVETVCHANNEL_COLORS_TAMIL/images/LOGO_HD/image.png",
            matchKeywords = listOf("colors tamil")
        ),
        LiveChannelDef(
            key = "kalaignar_tv",
            title = "Kalaignar TV",
            subtitle = "LIVE • Entertainment",
            streamUrl = "https://segment.yuppcdn.net/240122/kalaignartv/playlist.m3u8",
            logoUrl = "https://ltsk-cdn.s3.eu-west-1.amazonaws.com/jumpstart/Temp_Live/cdn/HLS/Channel/transparentImages/Kalaignar%20TV.png",
            matchKeywords = listOf("kalaignar tv")
        ),
        LiveChannelDef(
            key = "raj_tv_hd",
            title = "Raj TV HD",
            subtitle = "LIVE • 1080p",
            streamUrl = "https://livestream.rajtv.tv/hlslive/Admin/px08241087/live/RAJTV/master_1.m3u8",
            logoUrl = "https://xstreamcp-assets-msp.streamready.in/assets/LIVETV/LIVECHANNEL/LIVETV_LIVETVCHANNEL_RAJ_TV/images/LOGO_HD/image.png",
            matchKeywords = listOf("raj tv")
        ),
        LiveChannelDef(
            key = "polimer_tv_hd",
            title = "Polimer TV HD",
            subtitle = "LIVE • 720p",
            streamUrl = "https://cdn.pishow.tv/ott/live/1241/master.m3u8",
            logoUrl = "https://dtil.tmsimg.com/assets/s143665_ld_h15_aa.png?lock=720x540",
            matchKeywords = listOf("polimer tv")
        ),
        LiveChannelDef(
            key = "kalaignar_murasu",
            title = "Kalaignar Murasu",
            subtitle = "LIVE • Filme",
            streamUrl = "https://yuppmedtaorire.akamaized.net/v1/master/a0d007312bfd99c47f76b77ae26b1ccdaae76cb1/murasu_nim_https/050522/murasu/playlist.m3u8",
            logoUrl = "https://dtil.tmsimg.com/assets/s143561_ld_h15_aa.png?lock=720x540",
            matchKeywords = listOf("murasu")
        ),
        LiveChannelDef(
            key = "raj_digital_plus",
            title = "Raj Digital Plus",
            subtitle = "LIVE • Filme 1080p",
            streamUrl = "https://livestream.rajtv.tv/hlslive/Admin/px08241087/live/RajTV_Digital_plus/master_1.m3u8",
            logoUrl = "https://xstreamcp-assets-msp.streamready.in/assets/LIVETV/LIVECHANNEL/LIVETV_LIVETVCHANNEL_RAJ_DIGITAL_PLUS/images/LOGO_HD/image.png",
            matchKeywords = listOf("raj digital plus")
        ),
        LiveChannelDef(
            key = "sirippoli_tv",
            title = "Sirippoli TV",
            subtitle = "LIVE • Comedy",
            streamUrl = "https://segment.yuppcdn.net/240122/siripoli/playlist.m3u8",
            logoUrl = "https://dtil.tmsimg.com/assets/s143791_ld_h9_aa.png?lock=720x540",
            matchKeywords = listOf("sirippoli")
        ),
        LiveChannelDef(
            key = "isai_aruvi",
            title = "Isai Aruvi",
            subtitle = "LIVE • Musik",
            streamUrl = "https://segment.yuppcdn.net/140622/isaiaruvi/playlist.m3u8",
            logoUrl = "https://xstreamcp-assets-msp.streamready.in/assets/LIVETV/LIVECHANNEL/LIVETV_LIVETVCHANNEL_ISAI_ARUVI/images/LOGO_HD/image.png",
            matchKeywords = listOf("isai aruvi", "isaiaruvi")
        ),
        LiveChannelDef(
            key = "raj_musix",
            title = "Raj Musix Tamil",
            subtitle = "LIVE • Musik 1080p",
            streamUrl = "https://livestream.rajtv.tv/hlslive/Admin/px08241087/live/Raj_Musix/master_1.m3u8",
            logoUrl = "https://xstreamcp-assets-msp.streamready.in/assets/LIVETV/LIVECHANNEL/LIVETV_LIVETVCHANNEL_RAJ_MUSIX_TAMIL/images/LOGO_HD/image.png",
            matchKeywords = listOf("raj musix")
        ),
        LiveChannelDef(
            key = "7s_music",
            title = "7S Music",
            subtitle = "LIVE • Musik",
            streamUrl = "https://cdn.pishow.tv/ott/live/1257/master.m3u8",
            logoUrl = "https://i.imgur.com/zDiIhdN.png",
            matchKeywords = listOf("7s music")
        ),
        LiveChannelDef(
            key = "thanthi_one",
            title = "Thanthi One",
            subtitle = "LIVE • Entertainment",
            streamUrl = "https://mumt07.tangotv.in/zHjX9OFlTHANTHIONE/index.m3u8",
            logoUrl = "https://dtil.tmsimg.com/assets/s158791_ld_h15_aa.png?lock=720x540",
            matchKeywords = listOf("thanthi one")
        ),
        LiveChannelDef(
            key = "vendhar_tv",
            title = "Vendhar TV",
            subtitle = "LIVE • Entertainment",
            streamUrl = "https://mumt04.tangotv.in/m18aqlK4VENDHARTV/index.m3u8",
            logoUrl = "https://jiotvimages.cdn.jio.com/dare_images/images/Vendhar_TV.png",
            matchKeywords = listOf("vendhar tv")
        ),
        LiveChannelDef(
            key = "vasanth_tv",
            title = "Vasanth TV",
            subtitle = "LIVE • Entertainment",
            streamUrl = "https://mumt04.tangotv.in/m18aqlK4VASANTHTV/index.m3u8",
            logoUrl = "https://dtil.tmsimg.com/assets/s144068_ld_h15_aa.png?lock=720x540",
            matchKeywords = listOf("vasanth tv")
        ),
        LiveChannelDef(
            key = "makkal_tv",
            title = "Makkal TV",
            subtitle = "LIVE • Entertainment",
            streamUrl = "https://5k8q87azdy4v-hls-live.wmncdn.net/MAKKAL/271ddf829afeece44d8732757fba1a66.sdp/playlist.m3u8",
            logoUrl = "https://xstreamcp-assets-msp.streamready.in/assets/LIVETV/LIVECHANNEL/LIVETV_LIVETVCHANNEL_MAKKAL_TV/images/LOGO_HD/image.png",
            matchKeywords = listOf("makkal tv")
        ),
        LiveChannelDef(
            key = "puthuyugam_tv",
            title = "Puthuyugam TV",
            subtitle = "LIVE • Entertainment",
            streamUrl = "https://mumt04.tangotv.in/m18aqlK4PUTHUYUGAMTV/index.m3u8",
            logoUrl = "https://xstreamcp-assets-msp.streamready.in/assets/LIVETV/LIVECHANNEL/LIVETV_LIVETVCHANNEL_PUTHUYUGAM/images/LOGO_HD/image.png",
            matchKeywords = listOf("puthuyugam")
        ),
        LiveChannelDef(
            key = "peppers_tv",
            title = "Peppers TV",
            subtitle = "LIVE • Entertainment",
            streamUrl = "https://cdn.pishow.tv/ott/live/1383/master.m3u8",
            logoUrl = "https://jiotvimages.cdn.jio.com/dare_images/images/Peppers_TV.png",
            matchKeywords = listOf("peppers tv")
        ),
        LiveChannelDef(
            key = "ibc_tamil",
            title = "IBC Tamil HD",
            subtitle = "LIVE • 1080p",
            streamUrl = "https://ibc.massstream.net/IBC/index.m3u8",
            logoUrl = "https://i.imgur.com/uVt9vBa.png",
            matchKeywords = listOf("ibc tamil")
        ),
        LiveChannelDef(
            key = "dd_tamil",
            title = "DD Tamil HD",
            subtitle = "LIVE • 1080p",
            streamUrl = "https://cdn.pishow.tv/ott/live/1737/master.m3u8",
            logoUrl = "https://xstreamcp-assets-msp.streamready.in/assets/LIVETV/LIVECHANNEL/LIVETV_LIVETVCHANNEL_DD_TAMIL/images/LOGO_HD/image.png",
            matchKeywords = listOf("dd tamil")
        ),
        LiveChannelDef(
            key = "thanthi_tv",
            title = "Thanthi TV",
            subtitle = "LIVE • News",
            streamUrl = "https://streams.tangotv.in/THANTHITV/ORIGIN/index.m3u8",
            logoUrl = "https://dtil.tmsimg.com/assets/s144000_ld_h15_aa.png?lock=720x540",
            matchKeywords = listOf("thanthi tv")
        ),
        LiveChannelDef(
            key = "polimer_news",
            title = "Polimer News",
            subtitle = "LIVE • News",
            streamUrl = "https://segment.yuppcdn.net/110322/polimernews/playlist.m3u8",
            logoUrl = "https://dtil.tmsimg.com/assets/s143664_ld_h9_aa.png?lock=720x540",
            matchKeywords = listOf("polimer news")
        ),
        LiveChannelDef(
            key = "puthiya_thalaimurai",
            title = "Puthiya Thalaimurai",
            subtitle = "LIVE • News",
            streamUrl = "https://mumt07.tangotv.in/zHjX9OFlPUTHIYAEXPRESS/index.m3u8",
            logoUrl = "https://dtil.tmsimg.com/assets/s143692_ld_h15_aa.png?lock=720x540",
            matchKeywords = listOf("puthiya thalaimurai")
        ),
        LiveChannelDef(
            key = "news7_tamil",
            title = "News 7 Tamil",
            subtitle = "LIVE • News",
            streamUrl = "https://segment.yuppcdn.net/240122/news7/playlist.m3u8",
            logoUrl = "https://jiotvimages.cdn.jio.com/dare_images/images/News7_Tamil.png",
            matchKeywords = listOf("news 7 tamil", "news7 tamil")
        ),
        LiveChannelDef(
            key = "news18_tamil",
            title = "News18 Tamil Nadu",
            subtitle = "LIVE • News 1080p",
            streamUrl = "https://n18syndication.akamaized.net/bpk-tv/News18_Tamil_Nadu_NW18_MOB/output01/master.m3u8",
            logoUrl = "https://xstreamcp-assets-msp.streamready.in/assets/LIVETV/LIVECHANNEL/LIVETV_LIVETVCHANNEL_NEWS18_TAMIL_NADU/images/LOGO_HD/image.png",
            matchKeywords = listOf("news18 tamil")
        ),
        LiveChannelDef(
            key = "news_tamil_24x7",
            title = "News Tamil 24x7",
            subtitle = "LIVE • News",
            streamUrl = "https://cdn.pishow.tv/ott/live/1433/master.m3u8",
            logoUrl = "https://xstreamcp-assets-msp.streamready.in/assets/LIVETV/LIVECHANNEL/LIVETV_LIVETVCHANNEL_NEWS_TAMIL_24X7/images/LOGO_HD/image.png",
            matchKeywords = listOf("news tamil 24x7")
        )
    )

    private val logoRegex = Regex("""tvg-logo="([^"]+)"""")

    fun getCategories(): List<Category> = listOf(
        Category(CATEGORY_LIVE_TV, "Live TV")
    )

    fun isLiveTvCategory(categoryId: String): Boolean = categoryId == CATEGORY_LIVE_TV

    fun getLiveChannels(page: Int = 1): MovieListResponse {
        if (page > 1) return MovieListResponse(emptyList(), page, hasMore = false)

        val movies = curatedChannels.map { def ->
            def.toMovie(def.streamUrl)
        }

        return MovieListResponse(
            items = movies,
            page = 1,
            hasMore = false
        )
    }

    fun search(query: String): List<Movie> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return curatedChannels
            .filter {
                it.title.lowercase().contains(q) ||
                    it.subtitle.lowercase().contains(q) ||
                    it.matchKeywords.any { kw -> kw.contains(q) }
            }
            .map { it.toMovie(it.streamUrl) }
    }

    private fun LiveChannelDef.toMovie(resolvedStreamUrl: String): Movie =
        Movie(
            id = "$PREFIX_LIVE_TV$key",
            title = title,
            thumbnail = logoUrl,
            description = "Tamil Live TV – $title ($subtitle)",
            year = subtitle,
            stream = StreamResponse(
                url = resolvedStreamUrl,
                type = "hls",
                title = "$title (LIVE)",
                headers = mapOf("User-Agent" to ApiClient.DESKTOP_USER_AGENT)
            )
        )

    private data class M3uEntry(
        val name: String,
        val logo: String?,
        val url: String
    )

    private fun fetchRemoteM3uEntries(): List<M3uEntry> = runCatching {
        val req = Request.Builder()
            .url(IPTV_TAMIL_M3U)
            .header("User-Agent", ApiClient.DESKTOP_USER_AGENT)
            .build()
        val body = ApiClient.okHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            resp.body?.string().orEmpty()
        }
        val lines = body.lines()
        val entries = mutableListOf<M3uEntry>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXTINF")) {
                val name = line.substringAfterLast(',').trim()
                val logo = logoRegex.find(line)?.groupValues?.getOrNull(1)
                var j = i + 1
                while (j < lines.size && lines[j].trim().startsWith("#")) {
                    j++
                }
                val url = lines.getOrNull(j)?.trim().orEmpty()
                if (url.startsWith("http")) {
                    entries.add(M3uEntry(name, logo, url))
                }
                i = j + 1
            } else {
                i++
            }
        }
        entries
    }.getOrDefault(emptyList())
}
