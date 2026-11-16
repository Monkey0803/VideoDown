package com.videodown.douyin.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageMetadataTest {
    @Test fun `metadata removes blank values and formats precise gps`() {
        val fields = imageMetadataFields(
            attributes = listOf("Make" to " Sony ", "Model" to "", "ISO" to null),
            latitude = 31.230416,
            longitude = 121.473701,
            altitudeMeters = 12.345,
        )

        assertEquals(
            listOf(
                ImageMetadataField("GPS", "31.230416, 121.473701"),
                ImageMetadataField("GPS Altitude", "12.35 m"),
                ImageMetadataField("Make", "Sony"),
            ),
            fields,
        )
    }
}
