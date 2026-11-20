package com.videodown.douyin.core

/** Android EXIF 读取器与 Compose 信息面板之间的纯 Kotlin 数据。 */
data class ImageMetadata(
    val fields: List<ImageMetadataField>,
    /** 原始坐标：面板据此提供"在地图中打开"，展示文本仍由 fields 里的 GPS 行负责。 */
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    val isEmpty: Boolean get() = fields.isEmpty()
}

data class ImageMetadataField(
    val label: String,
    val value: String,
)

internal fun imageMetadataFields(
    attributes: List<Pair<String, String?>>,
    latitude: Double? = null,
    longitude: Double? = null,
    altitudeMeters: Double? = null,
): List<ImageMetadataField> = buildList {
    if (latitude != null && longitude != null) {
        add(ImageMetadataField("GPS", "%.6f, %.6f".format(java.util.Locale.US, latitude, longitude)))
    }
    altitudeMeters?.let {
        add(ImageMetadataField("GPS Altitude", "%.2f m".format(java.util.Locale.US, it)))
    }
    attributes.forEach { (label, raw) ->
        raw?.trim()?.takeIf(String::isNotEmpty)?.let { add(ImageMetadataField(label, it)) }
    }
}
