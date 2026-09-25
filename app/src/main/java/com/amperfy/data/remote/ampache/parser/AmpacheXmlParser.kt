/*
 * Amperfy4Android - an unofficial Android port of Amperfy
 * Copyright (c) 2026 angelo
 * Based on Amperfy for iOS, Copyright (c) 2019-2025 Maximilian Bauer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.amperfy.data.remote.ampache.parser

import com.amperfy.data.remote.ampache.AmpacheResponseError
import com.amperfy.data.remote.ampache.AmpacheXmlParseException
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.parsers.SAXParserFactory

/**
 * Ampache XML 解析器基类（对应 iOS `GenericXmlParser` + `AmpacheXmlParser` 两层的合并）。
 *
 * - `GenericXmlParser`（AmperfyKit/Api/GenericXmlParser.swift）：字符缓冲 buffer 的累积与清空、
 *   parsedCount 计数；
 * - `AmpacheXmlParser`（AmpacheXmlParser.swift:26-76）：`<error errorCode=…>` 分支——
 *   **每个 Ampache 响应都可能是错误体**，故错误解析下沉到基类，任何子类解析完都能读 [error]。
 *
 * 技术选型：`javax.xml.parsers.SAXParserFactory` + `org.xml.sax.helpers.DefaultHandler`——
 * Android 运行时与 JVM 单测双端可用、零新依赖，且与 iOS 的 SAX `XMLParser` delegate 结构 1:1 同构
 * （逐元素回调 + 缓冲，无需把整个响应建成 DOM——大库同步时响应可达数 MB）。
 *
 * 缓冲语义与 iOS 完全一致：`startElement` 清空缓冲、`endElement` 里**先**交给子类读取、**后**清空。
 * CDATA 段由 SAX 经 `characters` 回调下发（未注册 LexicalHandler 时不区分），无需特殊处理。
 *
 * iOS 的 `ThreadPerformanceMonitor` 降速逻辑（后台 CPU 配额）不移植：
 * Android 无等价的「后台 CPU 超限杀进程」策略，同步走协程 + Dispatchers.IO 即可。
 */
abstract class AmpacheXmlParser<T> : DefaultHandler() {

    /** 当前元素的字符缓冲（对应 iOS GenericXmlParser.buffer） */
    private val buffer = StringBuilder()

    /** 缓冲内容（原样，不做 trim——iOS 亦不 trim，服务器一律用 CDATA 包裹文本） */
    protected val text: String
        get() = buffer.toString()

    /** 已解析的实体条数（对应 iOS parsedCount；分页到底判定按它与每页请求量比较） */
    var parsedCount: Int = 0
        protected set

    /** 服务器给的 `<total_count>`（超集，iOS 不解析，见 AmpacheDto.AmpachePage） */
    var totalCount: Int? = null
        private set

    /** `<error>` 节点（无错误时为 null，对应 iOS AmpacheXmlParser.error） */
    var error: AmpacheResponseError? = null
        private set

    private var errorStatusCode: Int = 0
    private var errorAction: String? = null
    private var errorType: String? = null
    private var errorMessage: String = ""

    /** 解析产物（parse 完成后读取） */
    abstract val result: T

    final override fun startElement(
        uri: String?,
        localName: String?,
        qName: String?,
        attributes: Attributes?,
    ) {
        val name = elementName(localName, qName)
        buffer.setLength(0)
        if (name == ELEMENT_ERROR) {
            errorStatusCode = attributes?.getValue("errorCode")?.trim()?.toIntOrNull() ?: 0
        }
        onStartElement(name, attributes)
    }

    final override fun endElement(uri: String?, localName: String?, qName: String?) {
        val name = elementName(localName, qName)
        when (name) {
            ELEMENT_TOTAL_COUNT -> totalCount = text.trim().toIntOrNull() ?: totalCount
            "errorAction" -> errorAction = text
            "errorType" -> errorType = text
            "errorMessage" -> errorMessage = text
            ELEMENT_ERROR -> error = AmpacheResponseError(
                statusCode = errorStatusCode,
                errorAction = errorAction,
                errorType = errorType,
                message = errorMessage,
            )
        }
        onEndElement(name)
        buffer.setLength(0)
    }

    final override fun characters(ch: CharArray?, start: Int, length: Int) {
        if (ch == null || length <= 0) return
        buffer.append(String(ch, start, length))
    }

    /**
     * XXE 加固的最后一道闸：任何外部实体一律解析为空内容。
     *
     * 必须挂在 handler 上而非 XMLReader 上——`SAXParser.parse(InputSource, DefaultHandler)`
     * 内部会把传入的 handler 同时设为 entityResolver，覆盖调用方给 reader 设的那一个。
     */
    final override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
        InputSource(StringReader(""))

    /** 子类钩子：元素开始（[name] 已按无命名空间归一） */
    protected open fun onStartElement(name: String, attributes: Attributes?) {}

    /** 子类钩子：元素结束（此时 [text] 仍是该元素的文本） */
    protected open fun onEndElement(name: String) {}

    /** 实体计数 +1（子类在实体闭合元素里调用，对应 iOS `parsedCount += 1`） */
    protected fun incrementParsedCount() {
        parsedCount += 1
    }

    private fun elementName(localName: String?, qName: String?): String =
        if (!localName.isNullOrEmpty()) localName else (qName ?: "")

    private companion object {
        const val ELEMENT_ERROR = "error"
        const val ELEMENT_TOTAL_COUNT = "total_count"
    }
}

/**
 * SAX 解析入口：构造**禁用外部实体**的 SAXParser 跑一遍 [handler]。
 *
 * XXE 加固（OWASP XML External Entity）：关 DOCTYPE 声明、关内外部实体、关外部 DTD 加载；
 * 再由 [AmpacheXmlParser.resolveEntity] 把任何外部实体兜底成空内容——Ampache 响应里不存在
 * 合法的 DTD/实体引用，全关不影响任何真实响应，却挡住恶意/被劫持服务器用 `<!ENTITY>`
 * 读设备本地文件。各 `setFeature` 在不同解析器实现上可能不被识别，故逐项 runCatching，尽力而为。
 */
object AmpacheXmlParsing {

    fun <T> parse(xml: String, handler: AmpacheXmlParser<T>): T {
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            setFeatureQuietly("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeatureQuietly("http://xml.org/sax/features/external-general-entities", false)
            setFeatureQuietly("http://xml.org/sax/features/external-parameter-entities", false)
            setFeatureQuietly(
                "http://apache.org/xml/features/nonvalidating/load-external-dtd",
                false,
            )
            runCatching { isXIncludeAware = false }
        }
        try {
            // 外部实体的兜底解析在 handler 上（AmpacheXmlParser.resolveEntity），此处不再设 reader 级 resolver
            factory.newSAXParser().parse(InputSource(StringReader(xml)), handler)
        } catch (e: Exception) {
            throw AmpacheXmlParseException("Ampache XML 解析失败: ${e.message}", e)
        }
        return handler.result
    }

    private fun SAXParserFactory.setFeatureQuietly(name: String, value: Boolean) {
        runCatching { setFeature(name, value) }
    }
}

/**
 * 值转换助手：把 Ampache 的文本值转成 Kotlin 类型。
 *
 * 逐个对应 iOS `AmperfyKit/Common/Utilities.swift` 的 String 扩展
 * （asIso8601Date / asByteCount / asDurationInSeconds）与各 delegate 里的内联转换
 * （`Int(buffer) ?? 0`、`flag == 1`），**包括 iOS 的已知怪癖**——见各函数注释。
 */
object AmpacheXmlValues {

    /** `Int(buffer) ?? 0`：空元素（如 `<rating/>`）恒记 0 */
    fun toIntOrZero(value: String): Int = value.trim().toIntOrNull() ?: 0

    /** `Int(buffer) ?? 0` 的 Long 版（size 可能超 Int） */
    fun toLongOrZero(value: String): Long = value.trim().toLongOrNull() ?: 0L

    /** `Float(buffer) ?? 0.0`；空元素返回 null（Room 侧 ReplayGain 列可空，null 表示服务器没给） */
    fun toFloatOrNull(value: String): Float? = value.trim().toFloatOrNull()

    /** `<flag>` == 1 → 收藏（iOS 各 delegate 的 `flag == 1 ? true : false`） */
    fun flagToBoolean(value: String): Boolean = toIntOrZero(value) == 1

    /** 空串归一为 null（Ampache 大量字段是 `<x><![CDATA[]]></x>` 形态） */
    fun nullIfBlank(value: String): String? = value.trim().ifEmpty { null }

    /**
     * ISO8601 → epoch 毫秒（对应 iOS `String.asIso8601Date`，Utilities.swift:156-159）。
     *
     * iOS 用默认配置的 `ISO8601DateFormatter`（要求带时区的 internet date-time），
     * 解析失败返回 nil → 调用方回退到「当前时刻」。此处同样返回 null 交调用方决定。
     */
    fun parseIso8601Millis(value: String): Long? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        runCatching { return OffsetDateTime.parse(trimmed).toInstant().toEpochMilli() }
        runCatching { return Instant.parse(trimmed).toEpochMilli() }
        return null
    }

    /**
     * 播客单集 `<pubdate>` → epoch 毫秒（逐分支照抄 iOS PodcastEpisodeParserDelegate.swift:74-97）。
     *
     * 两种服务器形态：
     * - 含 "/" 的美式短日期（iOS 注释举例 "3/27/21, 3:30 AM"）——iOS 用的格式串是
     *   `M-d-yy, h:mm a`（**连字符**，与举例的斜杠对不上，是 iOS 侧的既存缺陷）：
     *   此处照抄该格式串，故同样会解析失败并落到 epoch 0，行为与 iOS 一致；
     * - 长度 >= 21 的 ISO 形态（"2026-06-22T09:50:00+00:00"）：取前 19 字符按 UTC 解析。
     *
     * 两种形态都不匹配时返回 null（iOS 此时只记日志、不写 publishDate）。
     */
    fun parsePodcastPubDateMillis(value: String): Long? {
        val raw = value.trim()
        return when {
            raw.contains("/") -> runCatching {
                LocalDateTime.parse(raw, US_SHORT_DATE_FORMATTER).toInstant(ZoneOffset.UTC)
                    .toEpochMilli()
            }.getOrDefault(0L)

            raw.length >= 21 -> runCatching {
                LocalDateTime.parse(raw.substring(0, 19), ISO_LOCAL_SECONDS_FORMATTER)
                    .toInstant(ZoneOffset.UTC).toEpochMilli()
            }.getOrDefault(0L)

            else -> null
        }
    }

    /**
     * "HH:mm:ss" 形态的时长 → 秒（对应 iOS `String.asDurationInSeconds`，Utilities.swift:184-188）。
     *
     * **照抄 iOS 的算式** `[0] * 60 * 24 + [1] * 60 + [2]`——首段乘的是 1440 而非 3600，
     * 是 iOS 侧的既存缺陷。实际无影响：Ampache 的 `<filelength>` 首段几乎恒为 00，
     * 且响应里紧随其后的 `<time>`（纯秒数）会覆盖同一字段（见 AmpachePodcastEpisodeDto）。
     * 保持一致而不"修正"，是为了让两端在异常数据上的表现也可比对。
     */
    fun parseDurationInSeconds(value: String): Int? {
        val components = value.trim().split(":").mapNotNull { it.toIntOrNull() }
        if (components.size != 3) return null
        return (components[0] * 60 * 24) + (components[1] * 60) + components[2]
    }

    /**
     * "17.33 MB" 形态的大小 → 字节（对应 iOS `String.asByteCount`，Utilities.swift:161-182）。
     * 单位按 1000 进制（与 iOS 同），无法识别的形态返回 null。
     *
     * **乘法刻意逐次用 Float 做**（而非先转 Double）：iOS 是
     * `Int(Float(x) * 1000 * 1000)` 的单精度链，转 Double 会把 Float 的舍入误差放大成
     * 差 1 字节的结果（"17.33 MB" → Double 链得 17329999，Float 链与 iOS 一致得 17330000）。
     */
    fun parseByteCount(value: String): Long? {
        val raw = value.trim()
        if (raw.isEmpty()) return null
        val (numberPart, thousandFactors) = when {
            raw.endsWith(" GB") -> raw.dropLast(3) to 3
            raw.endsWith(" MB") -> raw.dropLast(3) to 2
            raw.endsWith(" KB") -> raw.dropLast(3) to 1
            raw.endsWith(" B") -> raw.dropLast(2) to 0
            else -> return null
        }
        var number = numberPart.trim().toFloatOrNull() ?: return null
        repeat(thousandFactors) { number *= 1000f }
        return number.toLong()
    }

    private val US_SHORT_DATE_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("M-d-yy, h:mm a", Locale.US)

    private val ISO_LOCAL_SECONDS_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
}
