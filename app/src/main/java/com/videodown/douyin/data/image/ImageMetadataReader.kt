package com.videodown.douyin.data.image

import androidx.exifinterface.media.ExifInterface
import com.videodown.douyin.core.ImageMetadata
import com.videodown.douyin.core.imageMetadataFields
import java.io.File

/** 只读本地文件；不持久化、不联网，也不记录可能包含位置隐私的字段。 */
object ImageMetadataReader {
    fun read(file: File): ImageMetadata {
        if (!file.isFile) return ImageMetadata(emptyList())
        val exif = runCatching { ExifInterface(file) }.getOrNull() ?: return ImageMetadata(emptyList())
        val latLong = exif.latLong
        val altitude = exif.getAltitude(Double.NaN).takeUnless(Double::isNaN)
        return ImageMetadata(
            imageMetadataFields(
                attributes = TAGS.map { tag -> tag.label to exif.getAttribute(tag.name) },
                latitude = latLong?.getOrNull(0),
                longitude = latLong?.getOrNull(1),
                altitudeMeters = altitude,
            ).distinctBy { it.label to it.value },
        )
    }

    private data class Tag(val label: String, val name: String)

    /** ExifInterface 没有公开“枚举当前文件全部标签”的 API，因此集中列出其支持的常用 EXIF/XMP 标签。 */
    private val TAGS = listOf(
        Tag("Image Description", "ImageDescription"), Tag("Make", "Make"), Tag("Model", "Model"),
        Tag("Software", "Software"), Tag("Artist", "Artist"), Tag("Copyright", "Copyright"),
        Tag("Date/Time", "DateTime"), Tag("Original Date/Time", "DateTimeOriginal"),
        Tag("Digitized Date/Time", "DateTimeDigitized"), Tag("Offset Time", "OffsetTime"),
        Tag("Original Offset Time", "OffsetTimeOriginal"), Tag("Digitized Offset Time", "OffsetTimeDigitized"),
        Tag("Sub-second Time", "SubSecTime"), Tag("Original Sub-second Time", "SubSecTimeOriginal"),
        Tag("Digitized Sub-second Time", "SubSecTimeDigitized"), Tag("Orientation", "Orientation"),
        Tag("Width", "ImageWidth"), Tag("Height", "ImageLength"), Tag("Pixel X Dimension", "PixelXDimension"),
        Tag("Pixel Y Dimension", "PixelYDimension"), Tag("X Resolution", "XResolution"),
        Tag("Y Resolution", "YResolution"), Tag("Resolution Unit", "ResolutionUnit"),
        Tag("Exposure Time", "ExposureTime"), Tag("F Number", "FNumber"), Tag("Exposure Program", "ExposureProgram"),
        Tag("ISO", "PhotographicSensitivity"), Tag("Sensitivity Type", "SensitivityType"),
        Tag("Standard Output Sensitivity", "StandardOutputSensitivity"),
        Tag("Recommended Exposure Index", "RecommendedExposureIndex"), Tag("Exposure Index", "ExposureIndex"),
        Tag("Shutter Speed", "ShutterSpeedValue"), Tag("Aperture", "ApertureValue"),
        Tag("Brightness", "BrightnessValue"), Tag("Exposure Bias", "ExposureBiasValue"),
        Tag("Max Aperture", "MaxApertureValue"), Tag("Subject Distance", "SubjectDistance"),
        Tag("Metering Mode", "MeteringMode"), Tag("Light Source", "LightSource"), Tag("Flash", "Flash"),
        Tag("Focal Length", "FocalLength"), Tag("Focal Length 35mm", "FocalLengthIn35mmFilm"),
        Tag("Lens Make", "LensMake"), Tag("Lens Model", "LensModel"), Tag("Lens Specification", "LensSpecification"),
        Tag("White Balance", "WhiteBalance"), Tag("Digital Zoom Ratio", "DigitalZoomRatio"),
        Tag("Scene Capture Type", "SceneCaptureType"), Tag("Scene Type", "SceneType"),
        Tag("Custom Rendered", "CustomRendered"), Tag("Exposure Mode", "ExposureMode"),
        Tag("Gain Control", "GainControl"), Tag("Contrast", "Contrast"), Tag("Saturation", "Saturation"),
        Tag("Sharpness", "Sharpness"), Tag("Subject Distance Range", "SubjectDistanceRange"),
        Tag("Color Space", "ColorSpace"), Tag("Components Configuration", "ComponentsConfiguration"),
        Tag("Compressed Bits/Pixel", "CompressedBitsPerPixel"), Tag("Flash Energy", "FlashEnergy"),
        Tag("Focal Plane X Resolution", "FocalPlaneXResolution"), Tag("Focal Plane Y Resolution", "FocalPlaneYResolution"),
        Tag("Focal Plane Resolution Unit", "FocalPlaneResolutionUnit"), Tag("Subject Area", "SubjectArea"),
        Tag("Subject Location", "SubjectLocation"), Tag("Sensing Method", "SensingMethod"),
        Tag("File Source", "FileSource"), Tag("Spectral Sensitivity", "SpectralSensitivity"),
        Tag("OECF", "OECF"), Tag("CFAPattern", "CFAPattern"), Tag("Device Setting Description", "DeviceSettingDescription"),
        Tag("Body Serial Number", "BodySerialNumber"), Tag("Camera Owner Name", "CameraOwnerName"),
        Tag("Image Unique ID", "ImageUniqueID"), Tag("User Comment", "UserComment"),
        Tag("GPS Time Stamp", "GPSTimeStamp"), Tag("GPS Date Stamp", "GPSDateStamp"),
        Tag("GPS Speed", "GPSSpeed"), Tag("GPS Speed Ref", "GPSSpeedRef"),
        Tag("GPS Track", "GPSTrack"), Tag("GPS Track Ref", "GPSTrackRef"),
        Tag("GPS Image Direction", "GPSImgDirection"), Tag("GPS Image Direction Ref", "GPSImgDirectionRef"),
        Tag("GPS Destination Bearing", "GPSDestBearing"), Tag("GPS Destination Bearing Ref", "GPSDestBearingRef"),
        Tag("GPS Processing Method", "GPSProcessingMethod"), Tag("GPS Area Information", "GPSAreaInformation"),
        Tag("GPS Horizontal Positioning Error", "GPSHPositioningError"), Tag("XMP", "Xmp"),
    )
}
