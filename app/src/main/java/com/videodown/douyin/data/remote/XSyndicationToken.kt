package com.videodown.douyin.data.remote

/** JS-compatible positive Double base-36 conversion used by X's public embed endpoint.
 * Algorithm reference: yt-dlp/jsinterp.py js_number_to_string (fractional rounding with ULP bounds).
 */
internal object XSyndicationToken {
    fun generate(id: String): String {
        require(id.matches(Regex("[0-9]{5,22}")))
        val value = (id.toDouble() / 1e15) * Math.PI
        var integer = value.toLong()
        var fraction = value - integer
        var delta = maxOf(Double.MIN_VALUE, Math.ulp(value) / 2)
        val digits = mutableListOf<Int>()
        while (fraction >= delta) {
            delta *= 36
            fraction *= 36
            val digit = fraction.toInt()
            digits.add(digit)
            fraction -= digit
            if ((fraction > .5 || (fraction == .5 && digit % 2 == 1)) && fraction + delta > 1) {
                while (digits.isNotEmpty() && digits.last() == 35) digits.removeAt(digits.lastIndex)
                if (digits.isEmpty()) integer++ else digits[digits.lastIndex]++
                break
            }
        }
        return (integer.toString(36) + digits.joinToString("") { it.toString(36) }).replace("0", "")
    }
}
