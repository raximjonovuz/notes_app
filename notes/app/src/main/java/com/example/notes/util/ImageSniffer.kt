package com.example.notes.util

/**
 * Tiny image-format sniffer. Inspects the first few bytes of a buffer and
 * returns the MIME type (e.g. `image/png`) and extension (`png`) for the
 * formats the app actually supports, or `null` when the bytes don't match
 * any known image signature.
 *
 * Why this exists
 * ---------------
 * `ContentResolver.getType(Uri)` is unreliable for the `file://` URIs the
 * editor hands around after copying a picker's bytes into the app sandbox.
 * On every Android version we tested it returns `null` for a freshly written
 * file, so the upload pipeline used to fall back to `image/jpeg` for *every*
 * image. PNG bytes shipped to R2 with `content_type=image/jpeg` and a URL
 * ending in `.jpg`, which broke downstream tools that sniff MIME and made
 * the public URL confusing.
 *
 * Sniffing bytes is the only reliable cross-version source of truth. The
 * signatures below are the first bytes from each container's spec:
 *
 *   PNG   : 89 50 4E 47 0D 0A 1A 0A (8 bytes)
 *   JPEG  : FF D8 FF
 *   GIF   : "GIF87a" / "GIF89a"
 *   WebP  : "RIFF" + size + "WEBP"
 *   BMP   : "BM"
 *   HEIC  : bytes [4..7] = "ftyp", brand "heic" / "heix" / "heim" / "heis"
 *   HEIF  : bytes [4..7] = "ftyp", brand "heif" / "mif1" / "msf1"
 */
object ImageSniffer {

    data class ImageFormat(
        val mime: String,
        val ext: String
    )

    private val PNG = ImageFormat("image/png", "png")
    private val JPEG = ImageFormat("image/jpeg", "jpg")
    private val GIF = ImageFormat("image/gif", "gif")
    private val WEBP = ImageFormat("image/webp", "webp")
    private val BMP = ImageFormat("image/bmp", "bmp")
    private val HEIC = ImageFormat("image/heic", "heic")
    private val HEIF = ImageFormat("image/heif", "heif")

    /**
     * Sniff the format out of [bytes] (which may be a prefix, never more
     * than the first 16 bytes are inspected). Returns `null` when the
     * buffer is too small or doesn't match any known image signature.
     */
    fun detect(bytes: ByteArray): ImageFormat? {
        val n = bytes.size
        if (n >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() &&
            bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() &&
            bytes[7] == 0x0A.toByte()
        ) return PNG
        if (n >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) return JPEG
        if (n >= 6 &&
            bytes[0] == 'G'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == '8'.code.toByte() &&
            (bytes[4] == '7'.code.toByte() || bytes[4] == '9'.code.toByte()) &&
            bytes[5] == 'a'.code.toByte()
        ) return GIF
        if (n >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte()
        ) return WEBP
        if (n >= 2 &&
            bytes[0] == 'B'.code.toByte() &&
            bytes[1] == 'M'.code.toByte()
        ) return BMP
        if (n >= 12 &&
            bytes[4] == 'f'.code.toByte() &&
            bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() &&
            bytes[7] == 'p'.code.toByte()
        ) {
            val brand = String(
                chars = charArrayOf(
                    bytes[8].toInt().toChar(),
                    bytes[9].toInt().toChar(),
                    bytes[10].toInt().toChar(),
                    bytes[11].toInt().toChar()
                )
            ).lowercase()
            return when (brand) {
                "heic", "heix", "heim", "heis", "hevc" -> HEIC
                "heif", "mif1", "msf1" -> HEIF
                else -> null
            }
        }
        return null
    }

    /**
     * Convenience for the (MIME, extension) pair, falling back to JPEG
     * when nothing matched. Use this in upload pipelines that must always
     * pick something (the backend rejects non-image content types).
     */
    fun detectOrJpeg(bytes: ByteArray): ImageFormat =
        detect(bytes) ?: JPEG
}
