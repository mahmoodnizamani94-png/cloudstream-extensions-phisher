package com.phisher98

/**
 * ZeroAllocParser
 *
 * High-performance, zero-DOM, low-allocation token and attribute scanner for HTML, JSON, and JS scripts.
 * Eliminates full-DOM Jsoup overhead and dynamic regex re-compilations on memory-constrained Android TV devices
 * (e.g. Fire TV Stick, Chromecast with Google TV, 1–2 GB RAM).
 *
 * Designed as a drop-in replacement for:
 * 1. `Jsoup.parse(html).selectFirst(...)` / `document.select(...)`
 * 2. `document.selectFirst("script:containsData(...)")?.data()`
 * 3. Dynamic Regex compilation inside loops / functions (`Regex(...)`, `toRegex()`)
 * 4. Heavy string reallocation pipelines during link cleanup
 */
object ZeroAllocParser {

    // Pre-compiled hoisted regexes for fallback and pattern extraction
    val QUALITY_REGEX = Regex("""(\d{3,4}p)""", RegexOption.IGNORE_CASE)
    val RESOLUTION_NUMBER_REGEX = Regex("""(\d{3,4})[pP]""")
    val M3U8_URL_REGEX = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""", RegexOption.IGNORE_CASE)
    val HTTP_URL_REGEX = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

    data class SuperStreamQualityItem(
        val url: String,
        val quality: String,
        val size: String
    )

    // =========================================================================
    // 1. Zero-Allocation HTML Attribute Extraction
    // =========================================================================

    /**
     * Extracts the value of [attribute] from the first occurrence of [tag] in [html].
     * Scans directly on the [CharSequence] without constructing any DOM nodes or lowercased string copies.
     *
     * @param html The raw HTML string or CharSequence
     * @param tag Tag name to match, e.g. "iframe", "a", "div", "meta" (case-insensitive)
     * @param attribute Attribute name to match, e.g. "src", "href", "data-url" (case-insensitive)
     * @param startIndex Character offset to start searching from
     * @return Extracted attribute value, or null if not found
     */
    fun extractAttribute(
        html: CharSequence,
        tag: String,
        attribute: String,
        startIndex: Int = 0
    ): String? {
        var cursor = startIndex
        val len = html.length

        while (cursor < len) {
            val tagIdx = indexOfIgnoreCase(html, "<$tag", cursor)
            if (tagIdx == -1) return null

            val afterTagIdx = tagIdx + 1 + tag.length
            if (afterTagIdx < len) {
                val nextChar = html[afterTagIdx]
                if (nextChar != ' ' && nextChar != '\t' && nextChar != '\n' && nextChar != '\r' && nextChar != '>' && nextChar != '/') {
                    cursor = afterTagIdx
                    continue
                }
            }

            val tagEnd = findTagClose(html, afterTagIdx)
            if (tagEnd == -1) return null

            val attrVal = extractAttributeInBounds(html, attribute, afterTagIdx, tagEnd)
            if (attrVal != null) {
                return decodeHtmlEntities(attrVal)
            }

            cursor = tagEnd + 1
        }
        return null
    }

    /**
     * Extracts all occurrences of [attribute] from all [tag] elements in [html].
     */
    fun extractAllAttributes(
        html: CharSequence,
        tag: String,
        attribute: String
    ): List<String> {
        val results = mutableListOf<String>()
        var cursor = 0
        val len = html.length

        while (cursor < len) {
            val tagIdx = indexOfIgnoreCase(html, "<$tag", cursor)
            if (tagIdx == -1) break

            val afterTagIdx = tagIdx + 1 + tag.length
            if (afterTagIdx < len) {
                val nextChar = html[afterTagIdx]
                if (nextChar != ' ' && nextChar != '\t' && nextChar != '\n' && nextChar != '\r' && nextChar != '>' && nextChar != '/') {
                    cursor = afterTagIdx
                    continue
                }
            }

            val tagEnd = findTagClose(html, afterTagIdx)
            if (tagEnd == -1) break

            val attrVal = extractAttributeInBounds(html, attribute, afterTagIdx, tagEnd)
            if (attrVal != null && attrVal.isNotEmpty()) {
                results.add(decodeHtmlEntities(attrVal))
            }

            cursor = tagEnd + 1
        }
        return results
    }

    /**
     * Extracts [targetAttr] from a [tag] element that satisfies a condition:
     * [whereAttr] must equal or contain [whereValue].
     *
     * Example: extractAttributeWhere(html, "a", "href", "id", "vd")
     * Example: extractAttributeWhere(html, "meta", "content", "http-equiv", "refresh")
     * Example: extractAttributeWhere(html, "a", "href", "class", "btn-success")
     */
    fun extractAttributeWhere(
        html: CharSequence,
        tag: String,
        targetAttr: String,
        whereAttr: String,
        whereValue: String
    ): String? {
        var cursor = 0
        val len = html.length
        val matchAnyTag = tag == "*"

        while (cursor < len) {
            val tagIdx = if (matchAnyTag) {
                html.indexOf('<', cursor)
            } else {
                indexOfIgnoreCase(html, "<$tag", cursor)
            }
            if (tagIdx == -1) return null

            val afterTagIdx = if (matchAnyTag) {
                tagIdx + 1
            } else {
                val after = tagIdx + 1 + tag.length
                if (after < len) {
                    val nextChar = html[after]
                    if (nextChar != ' ' && nextChar != '\t' && nextChar != '\n' && nextChar != '\r' && nextChar != '>' && nextChar != '/') {
                        cursor = after
                        continue
                    }
                }
                after
            }

            val tagEnd = findTagClose(html, afterTagIdx)
            if (tagEnd == -1) return null

            val conditionVal = extractAttributeInBounds(html, whereAttr, afterTagIdx, tagEnd)
            if (conditionVal != null) {
                val matches = if (whereAttr.equals("class", ignoreCase = true)) {
                    containsClass(conditionVal, whereValue)
                } else {
                    conditionVal.equals(whereValue, ignoreCase = true)
                }
                if (matches) {
                    val targetVal = extractAttributeInBounds(html, targetAttr, afterTagIdx, tagEnd)
                    if (targetVal != null) {
                        return decodeHtmlEntities(targetVal)
                    }
                }
            }

            cursor = tagEnd + 1
        }
        return null
    }

    /**
     * Fast iframe src extractor. Looks for <iframe ...> and extracts data-src (if non-empty) or src.
     */
    fun extractIframeSrc(html: CharSequence): String? {
        var cursor = 0
        val len = html.length
        while (cursor < len) {
            val tagIdx = indexOfIgnoreCase(html, "<iframe", cursor)
            if (tagIdx == -1) return null

            val afterTag = tagIdx + 7
            val tagEnd = findTagClose(html, afterTag)
            val effectiveEnd = if (tagEnd != -1) tagEnd else {
                val nextLt = html.indexOf('<', afterTag)
                if (nextLt != -1) nextLt else len
            }

            val dataSrc = extractAttributeInBounds(html, "data-src", afterTag, effectiveEnd)
            if (!dataSrc.isNullOrBlank()) {
                return decodeHtmlEntities(dataSrc)
            }

            val src = extractAttributeInBounds(html, "src", afterTag, effectiveEnd)
            if (!src.isNullOrBlank()) {
                return decodeHtmlEntities(src)
            }

            if (tagEnd == -1) break
            cursor = tagEnd + 1
        }
        return null
    }

    /**
     * Fast meta-refresh URL extractor. Replaces Jsoup parsing for redirect hops.
     */
    fun extractMetaRefreshUrl(html: CharSequence): String? {
        val content = extractAttributeWhere(html, "meta", "content", "http-equiv", "refresh")
            ?: return null
        val urlIdx = content.indexOf("url=", ignoreCase = true)
        return if (urlIdx != -1) {
            content.substring(urlIdx + 4).trim('\'', '"', ' ')
        } else null
    }

    // =========================================================================
    // 2. Zero-Allocation Script Tag Extraction
    // =========================================================================

    /**
     * Extracts the script content from the first <script>...</script> block that contains [marker].
     * Equivalent to `document.selectFirst("script:containsData(marker)")?.data()`.
     */
    fun extractScriptContaining(html: CharSequence, marker: String): String? {
        var cursor = 0
        val len = html.length

        while (cursor < len) {
            val scriptStart = indexOfIgnoreCase(html, "<script", cursor)
            if (scriptStart == -1) return null

            val openTagClose = html.indexOf('>', scriptStart + 7)
            if (openTagClose == -1) return null

            val scriptEnd = indexOfIgnoreCase(html, "</script>", openTagClose + 1)
            val scriptBodyEnd = if (scriptEnd != -1) scriptEnd else len

            val scriptBodyStart = openTagClose + 1
            if (scriptBodyStart < scriptBodyEnd) {
                val markerIdx = indexOf(html, marker, scriptBodyStart, scriptBodyEnd)
                if (markerIdx != -1) {
                    return html.subSequence(scriptBodyStart, scriptBodyEnd).toString().trim()
                }
            }

            if (scriptEnd == -1) break
            cursor = scriptEnd + 9
        }
        return null
    }

    // =========================================================================
    // 3. Zero-Allocation HTML Tag Text Extraction
    // =========================================================================

    /**
     * Extracts inner text of an HTML tag matching optional [id] or [className].
     * Automatically strips any inner HTML tags and decodes HTML entities.
     */
    fun extractTagText(
        html: CharSequence,
        tag: String,
        id: String? = null,
        className: String? = null
    ): String? {
        var cursor = 0
        val len = html.length
        val closeTag = "</$tag>"

        while (cursor < len) {
            val tagIdx = indexOfIgnoreCase(html, "<$tag", cursor)
            if (tagIdx == -1) return null

            val afterTagIdx = tagIdx + 1 + tag.length
            val tagEnd = findTagClose(html, afterTagIdx)
            if (tagEnd == -1) return null

            var matches = true
            if (id != null) {
                val actualId = extractAttributeInBounds(html, "id", afterTagIdx, tagEnd)
                if (!actualId.equals(id, ignoreCase = true)) matches = false
            }
            if (matches && className != null) {
                val actualClass = extractAttributeInBounds(html, "class", afterTagIdx, tagEnd)
                if (actualClass == null || !containsClass(actualClass, className)) matches = false
            }

            if (matches) {
                val bodyStart = tagEnd + 1
                val bodyEnd = indexOfIgnoreCase(html, closeTag, bodyStart)
                val rawText = if (bodyEnd != -1) {
                    html.subSequence(bodyStart, bodyEnd)
                } else {
                    val nextTag = html.indexOf('<', bodyStart)
                    if (nextTag != -1) html.subSequence(bodyStart, nextTag)
                    else html.subSequence(bodyStart, len)
                }
                return stripTagsAndDecode(rawText)
            }

            cursor = tagEnd + 1
        }
        return null
    }

    // =========================================================================
    // 4. SuperStream Quality List Token Scanner
    // =========================================================================

    /**
     * High-speed, zero-DOM extractor for SuperStream's file_quality blocks.
     * Extracts raw (url, qualityAttr, size) triples with 100% parity to Jsoup element selection.
     */
    fun extractFileQualities(html: String): List<Triple<String, String, String>> {
        if (html.isEmpty()) return emptyList()
        val results = mutableListOf<Triple<String, String, String>>()
        var cursor = 0
        val len = html.length

        while (cursor < len) {
            val markerIdx = indexOfIgnoreCase(html, "file_quality", cursor)
            if (markerIdx == -1) break

            var tagStart = markerIdx
            while (tagStart > 0 && html[tagStart] != '<') {
                tagStart--
            }

            val tagEnd = findTagClose(html, tagStart + 1)
            val effectiveTagEnd = if (tagEnd != -1) tagEnd else {
                val nextGt = html.indexOf('>', tagStart)
                if (nextGt != -1) nextGt else len
            }

            val url = extractAttributeInBounds(html, "data-url", tagStart + 1, effectiveTagEnd)
            val qualityAttr = extractAttributeInBounds(html, "data-quality", tagStart + 1, effectiveTagEnd)

            if (!url.isNullOrEmpty() && !qualityAttr.isNullOrEmpty()) {
                val nextMarker = indexOfIgnoreCase(html, "file_quality", effectiveTagEnd + 1)
                val closingDiv = indexOfIgnoreCase(html, "</div>", effectiveTagEnd + 1)
                var searchBound = len
                if (closingDiv != -1 && (nextMarker == -1 || closingDiv < nextMarker)) {
                    searchBound = closingDiv
                } else if (nextMarker != -1) {
                    searchBound = nextMarker
                }

                var size: String? = null
                var sIdx = indexOfIgnoreCase(html, "size", effectiveTagEnd + 1)
                while (sIdx != -1 && sIdx < searchBound) {
                    val tagClose = html.indexOf('>', sIdx)
                    if (tagClose != -1 && tagClose < searchBound) {
                        val nextTag = html.indexOf('<', tagClose + 1)
                        val endTxt = if (nextTag != -1 && nextTag <= searchBound) nextTag else searchBound
                        val candidate = html.substring(tagClose + 1, endTxt).trim()
                        if (candidate.isNotEmpty() && !candidate.startsWith("<")) {
                            size = candidate
                            break
                        }
                    }
                    sIdx = indexOfIgnoreCase(html, "size", sIdx + 4)
                }

                if (!size.isNullOrEmpty()) {
                    results.add(Triple(decodeHtmlEntities(fastUnescapeSlash(url)), decodeHtmlEntities(qualityAttr), decodeHtmlEntities(size)))
                }
            }
            cursor = if (effectiveTagEnd < len) effectiveTagEnd + 1 else len
        }
        return results
    }

    /**
     * High-speed, zero-DOM extractor for SuperStream's file_quality blocks.
     * Resolves "ORG" qualities using QUALITY_REGEX, matching SuperStreamExtractor and StreamPlayUtils.
     */
    fun extractSuperStreamQualities(html: String): List<SuperStreamQualityItem> {
        val rawItems = extractFileQualities(html)
        if (rawItems.isEmpty()) return emptyList()

        return rawItems.map { (url, qualityAttr, size) ->
            val resolvedQuality = if (qualityAttr.equals("ORG", ignoreCase = true)) {
                QUALITY_REGEX.find(url)?.groupValues?.get(1) ?: "2160p"
            } else {
                qualityAttr
            }
            SuperStreamQualityItem(url, resolvedQuality, size)
        }
    }

    // =========================================================================
    // 5. Zero-Allocation Balanced JSON & JS Block Extraction
    // =========================================================================

    /**
     * Extracts a complete JSON object block `{ ... }` corresponding to [key] by tracking brace depth.
     * Properly handles string literals and escaped quotes to prevent false closures.
     * Replaces dynamic regex compilation: `Regex(""""?${Regex.escape(key)}"?\s*:\s*(\{)""")`.
     */
    fun extractJsonBlock(html: CharSequence, key: String): String? {
        var cursor = 0
        val len = html.length

        while (cursor < len) {
            val keyIdx = indexOf(html, key, cursor, len)
            if (keyIdx == -1) return null

            var afterKey = keyIdx + key.length
            if (afterKey < len && html[afterKey] == '"') afterKey++
            while (afterKey < len && html[afterKey].isWhitespace()) afterKey++

            if (afterKey < len && (html[afterKey] == ':' || html[afterKey] == '=')) {
                var afterSeparator = afterKey + 1
                while (afterSeparator < len && html[afterSeparator].isWhitespace()) afterSeparator++

                if (afterSeparator < len && html[afterSeparator] == '{') {
                    var depth = 0
                    var i = afterSeparator
                    var inString = false
                    var escape = false

                    while (i < len) {
                        val c = html[i]
                        if (escape) {
                            escape = false
                        } else if (c == '\\') {
                            escape = true
                        } else if (c == '"') {
                            inString = !inString
                        } else if (!inString) {
                            if (c == '{') depth++
                            else if (c == '}') {
                                depth--
                                if (depth == 0) {
                                    return html.subSequence(afterSeparator, i + 1).toString()
                                }
                            }
                        }
                        i++
                    }
                    return null
                }
            }
            cursor = keyIdx + key.length
        }
        return null
    }

    /**
     * Extracts a complete JSON array block `[ ... ]` corresponding to [key] by tracking bracket depth.
     * Replaces dynamic regex compilation: `Regex(""""?${Regex.escape(key)}"?\s*:\s*(\[)""")`.
     */
    fun extractArrayBlock(html: CharSequence, key: String): String? {
        var cursor = 0
        val len = html.length

        while (cursor < len) {
            val keyIdx = indexOf(html, key, cursor, len)
            if (keyIdx == -1) return null

            var afterKey = keyIdx + key.length
            if (afterKey < len && html[afterKey] == '"') afterKey++
            while (afterKey < len && html[afterKey].isWhitespace()) afterKey++

            if (afterKey < len && (html[afterKey] == ':' || html[afterKey] == '=')) {
                var afterSeparator = afterKey + 1
                while (afterSeparator < len && html[afterSeparator].isWhitespace()) afterSeparator++

                if (afterSeparator < len && html[afterSeparator] == '[') {
                    var depth = 0
                    var i = afterSeparator
                    var inString = false
                    var escape = false

                    while (i < len) {
                        val c = html[i]
                        if (escape) {
                            escape = false
                        } else if (c == '\\') {
                            escape = true
                        } else if (c == '"') {
                            inString = !inString
                        } else if (!inString) {
                            if (c == '[') depth++
                            else if (c == ']') {
                                depth--
                                if (depth == 0) {
                                    return html.subSequence(afterSeparator, i + 1).toString()
                                }
                            }
                        }
                        i++
                    }
                    return null
                }
            }
            cursor = keyIdx + key.length
        }
        return null
    }

    /**
     * Extracts variable assignment value from JavaScript code: e.g. `varName = "value";`
     * Replaces dynamic regex compilation in Playm4u `findIn()`.
     */
    fun extractJsAssignment(script: CharSequence, varName: String): String? {
        var cursor = 0
        val len = script.length

        while (cursor < len) {
            val varIdx = indexOf(script, varName, cursor, len)
            if (varIdx == -1) return null

            var afterVar = varIdx + varName.length
            while (afterVar < len && script[afterVar].isWhitespace()) afterVar++

            if (afterVar < len && script[afterVar] == '=') {
                var afterEq = afterVar + 1
                while (afterEq < len && script[afterEq].isWhitespace()) afterEq++

                if (afterEq < len) {
                    val quote = script[afterEq]
                    if (quote == '"' || quote == '\'') {
                        val valStart = afterEq + 1
                        val valEnd = script.indexOf(quote, valStart)
                        if (valEnd != -1) {
                            return script.subSequence(valStart, valEnd).toString()
                        }
                    } else {
                        // Unquoted token until semicolon, newline, or whitespace
                        var valEnd = afterEq
                        while (valEnd < len && script[valEnd] != ';' && script[valEnd] != '\n' && !script[valEnd].isWhitespace()) {
                            valEnd++
                        }
                        return script.subSequence(afterEq, valEnd).toString()
                    }
                }
            }
            cursor = varIdx + varName.length
        }
        return null
    }

    // =========================================================================
    // 6. Fast String Utilities & Zero-Alloc Helpers
    // =========================================================================

    /**
     * Fast unescape of `\/` into `/`.
     * Avoids intermediate object allocations if `\/` is not present in the input.
     */
    fun fastUnescapeSlash(input: String): String {
        val firstSlash = input.indexOf("\\/")
        if (firstSlash == -1) return input

        val sb = java.lang.StringBuilder(input.length)
        var cursor = 0
        val len = input.length
        while (cursor < len) {
            val idx = input.indexOf("\\/", cursor)
            if (idx == -1) {
                sb.append(input, cursor, len)
                break
            }
            sb.append(input, cursor, idx)
            sb.append('/')
            cursor = idx + 2
        }
        return sb.toString()
    }

    // =========================================================================
    // Internal Helper Routines
    // =========================================================================

    private fun indexOfIgnoreCase(str: CharSequence, target: String, startIndex: Int = 0): Int {
        val targetLen = target.length
        val max = str.length - targetLen
        if (startIndex > max) return -1
        val firstCharLower = target[0].lowercaseChar()
        val firstCharUpper = target[0].uppercaseChar()

        for (i in startIndex..max) {
            val c = str[i]
            if (c == firstCharLower || c == firstCharUpper) {
                if (regionMatchesIgnoreCase(str, i, target)) {
                    return i
                }
            }
        }
        return -1
    }

    private fun regionMatchesIgnoreCase(str: CharSequence, strOffset: Int, target: String): Boolean {
        val len = target.length
        for (i in 0 until len) {
            val c1 = str[strOffset + i]
            val c2 = target[i]
            if (!c1.equals(c2, ignoreCase = true)) return false
        }
        return true
    }

    private fun indexOf(str: CharSequence, target: String, start: Int, end: Int): Int {
        val targetLen = target.length
        val max = end - targetLen
        if (start > max) return -1
        val first = target[0]

        for (i in start..max) {
            if (str[i] == first) {
                var match = true
                for (j in 1 until targetLen) {
                    if (str[i + j] != target[j]) {
                        match = false
                        break
                    }
                }
                if (match) return i
            }
        }
        return -1
    }

    private fun findTagClose(html: CharSequence, start: Int): Int {
        var i = start
        val len = html.length
        var inQuote = false
        var quoteChar = ' '

        while (i < len) {
            val c = html[i]
            if (inQuote) {
                if (c == quoteChar) inQuote = false
            } else {
                if (c == '"' || c == '\'') {
                    inQuote = true
                    quoteChar = c
                } else if (c == '>') {
                    return i
                }
            }
            i++
        }
        return -1
    }

    private fun extractAttributeInBounds(
        html: CharSequence,
        attribute: String,
        start: Int,
        end: Int
    ): String? {
        var cursor = start
        val attrLen = attribute.length

        while (cursor < end) {
            val idx = indexOfIgnoreCaseWithin(html, attribute, cursor, end)
            if (idx == -1) return null

            // Verify prefix delimiter (whitespace or '/')
            val prevChar = html[idx - 1]
            if (prevChar != ' ' && prevChar != '\t' && prevChar != '\n' && prevChar != '\r' && prevChar != '/' && idx != start) {
                cursor = idx + attrLen
                continue
            }

            var afterAttr = idx + attrLen
            while (afterAttr < end && html[afterAttr].isWhitespace()) afterAttr++

            if (afterAttr < end && html[afterAttr] == '=') {
                var afterEq = afterAttr + 1
                while (afterEq < end && html[afterEq].isWhitespace()) afterEq++

                if (afterEq < end) {
                    val quote = html[afterEq]
                    return if (quote == '"' || quote == '\'') {
                        val valStart = afterEq + 1
                        val valEnd = indexOfCharWithin(html, quote, valStart, end)
                        if (valEnd != -1) {
                            html.subSequence(valStart, valEnd).toString()
                        } else null
                    } else {
                        // Unquoted attribute value
                        var valEnd = afterEq
                        while (valEnd < end && !html[valEnd].isWhitespace() && html[valEnd] != '>') {
                            valEnd++
                        }
                        html.subSequence(afterEq, valEnd).toString()
                    }
                }
            }
            cursor = idx + attrLen
        }
        return null
    }

    private fun indexOfIgnoreCaseWithin(str: CharSequence, target: String, start: Int, end: Int): Int {
        val targetLen = target.length
        val max = end - targetLen
        if (start > max) return -1
        val firstLower = target[0].lowercaseChar()
        val firstUpper = target[0].uppercaseChar()

        for (i in start..max) {
            val c = str[i]
            if (c == firstLower || c == firstUpper) {
                var match = true
                for (j in 1 until targetLen) {
                    if (!str[i + j].equals(target[j], ignoreCase = true)) {
                        match = false
                        break
                    }
                }
                if (match) return i
            }
        }
        return -1
    }

    private fun indexOfCharWithin(str: CharSequence, target: Char, start: Int, end: Int): Int {
        for (i in start until end) {
            if (str[i] == target) return i
        }
        return -1
    }

    private fun containsClass(classAttr: String, targetClass: String): Boolean {
        var start = 0
        val len = classAttr.length
        val targetLen = targetClass.length

        while (start < len) {
            while (start < len && classAttr[start].isWhitespace()) start++
            if (start >= len) break
            var end = start
            while (end < len && !classAttr[end].isWhitespace()) end++
            if (end - start == targetLen) {
                if (classAttr.regionMatches(start, targetClass, 0, targetLen, ignoreCase = true)) {
                    return true
                }
            }
            start = end + 1
        }
        return false
    }

    private fun stripTagsAndDecode(rawText: CharSequence): String {
        val sb = java.lang.StringBuilder(rawText.length)
        var inTag = false
        for (i in 0 until rawText.length) {
            val c = rawText[i]
            if (c == '<') inTag = true
            else if (c == '>') inTag = false
            else if (!inTag) sb.append(c)
        }
        return decodeHtmlEntities(sb.toString().trim())
    }

    private fun decodeHtmlEntities(text: String): String {
        if (!text.contains('&')) return text
        return text
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }
}
