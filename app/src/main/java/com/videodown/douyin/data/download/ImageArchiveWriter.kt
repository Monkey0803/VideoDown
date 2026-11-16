package com.videodown.douyin.data.download

import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 把已下载的图片写成一个 ZIP。
 *
 * 纯 Kotlin（只依赖 `java.util.zip`），所以 JVM 用例可以直接覆盖：条目顺序、重名、缺文件、
 * 原子发布（先写 `.tmp` 再改名，进程被杀不会留下一个"看起来完成"的半个包）。
 * 图片本身已经压缩过，这里仍用默认的 DEFLATED——CPU 代价有限，代码不必为 STORED 手工算 CRC。
 */
internal object ImageArchiveWriter {

    const val ENTRY_DIRECTORY = "images"

    /** 归档里第 [index] 张图的条目名；用补零数字保序，也保证不会重名。 */
    fun entryName(index: Int, extension: String): String =
        "$ENTRY_DIRECTORY/%03d.%s".format(index + 1, extension.trimStart('.').lowercase().ifBlank { "jpg" })

    /**
     * 写入并发布 ZIP，返回最终文件大小。
     *
     * @throws IllegalArgumentException when an entry file is missing.
     */
    fun write(entries: List<Pair<String, File>>, output: File): Long {
        require(entries.isNotEmpty()) { "archive needs at least one entry" }
        val temp = File(output.parentFile, output.name + ".tmp")
        try {
            ZipOutputStream(BufferedOutputStream(temp.outputStream())).use { zip ->
                val buffer = ByteArray(64 * 1024)
                entries.forEach { (name, file) ->
                    require(file.isFile) { "archive entry is missing: $name" }
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            zip.write(buffer, 0, read)
                        }
                    }
                    zip.closeEntry()
                }
            }
            if (!temp.renameTo(output)) {
                temp.copyTo(output, overwrite = true)
                temp.delete()
            }
            return output.length()
        } finally {
            temp.delete()
        }
    }
}
