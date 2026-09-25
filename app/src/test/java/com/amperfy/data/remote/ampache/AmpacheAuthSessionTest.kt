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

package com.amperfy.data.remote.ampache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.MessageDigest

/**
 * Ampache 握手 passphrase 算法合同（Batch 1）。
 *
 * 算法出处：iOS `AmpacheXmlServerApi.generatePassphrase`（AmpacheXmlServerApi.swift:138-144）
 * ——`sha256("<unixtime 秒><sha256(password)>")`，全小写十六进制。
 *
 * 本测试锁的是**结构**（内层预哈希 + 外层拼接），期望值用 java 的 MessageDigest 独立算出后写死，
 * 不复用被测代码，避免「用自己证明自己」。
 */
class AmpacheAuthSessionTest {

    /** password = "sesame" 的 sha256（独立计算并写死） */
    private val sesamePasswordHash =
        "d0c04f4b1951e4aeaaec8223ed2039e542f3aae805a6fa7f6d794e5afff5d272"

    @Test
    fun sha256Hex_matchesIndependentlyComputedVector() {
        assertEquals(sesamePasswordHash, AmpacheAuthSession.sha256Hex("sesame"))
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            AmpacheAuthSession.sha256Hex(""),
        )
    }

    @Test
    fun sha256Hex_isLowercaseHexOf64Chars() {
        val hex = AmpacheAuthSession.sha256Hex("Amperfy")
        assertEquals(64, hex.length)
        assertEquals(hex.lowercase(), hex)
    }

    /**
     * 结构合同：passphrase = sha256(timestamp + sha256(password))。
     * 期望值来自独立计算（timestamp=1660000000、password="sesame"）。
     */
    @Test
    fun generatePassphrase_isSha256OfTimestampPlusPasswordHash() {
        val expected = "f70d6d54ad6e64af6b45ce90ac29f426a265bd6043c57ec699479d1182fc430f"
        assertEquals(
            expected,
            AmpacheAuthSession.generatePassphrase(sesamePasswordHash, 1_660_000_000L),
        )
    }

    /**
     * 内层必须是**密码的 sha256 预哈希**，不是明文密码——
     * 若实现误写成 sha256(timestamp + password)，本用例即失败。
     */
    @Test
    fun generatePassphrase_usesPreHashedPassword_notPlaintext() {
        val plaintextVariant = independentSha256Hex("1660000000" + "sesame")
        assertNotEquals(
            plaintextVariant,
            AmpacheAuthSession.generatePassphrase(sesamePasswordHash, 1_660_000_000L),
        )
    }

    /** 时间戳参与拼接：不同秒必得不同 passphrase */
    @Test
    fun generatePassphrase_changesWithTimestamp() {
        val a = AmpacheAuthSession.generatePassphrase(sesamePasswordHash, 1_660_000_000L)
        val b = AmpacheAuthSession.generatePassphrase(sesamePasswordHash, 1_660_000_001L)
        assertNotEquals(a, b)
    }

    /** 会话有效期判定：reauthenticateTime 之前有效、之后失效（iOS isAuthenticated，:133-136） */
    @Test
    fun handshake_isValidOnlyBeforeReauthenticateTime() {
        val expire = 1_660_710_895_000L
        val handshake = AmpacheAuthHandshake(
            token = "t",
            sessionExpireMillis = expire,
            reauthenticateTimeMillis = expire - 5 * 60 * 1000L,
            libraryChangeDates = AmpacheLibraryChangeDates(0, 0, 0),
        )
        assertEquals(true, handshake.isValidAt(expire - 6 * 60 * 1000L))
        assertEquals(true, handshake.isValidAt(expire - 5 * 60 * 1000L))
        assertEquals(false, handshake.isValidAt(expire - 5 * 60 * 1000L + 1))
        assertEquals(false, handshake.isValidAt(expire))
    }

    // ==================== <api> 版本折算与播客支持判定（Batch 2） ====================

    /**
     * 语义版本形态：`X.Y.Z` → X*100000 + Y*10000 + Z*1000。
     * 这是相对 iOS 的**刻意修正**（iOS `Int("5.5.6")` 得 nil，恒判不支持播客，
     * 见 AmpacheApiVersion）。
     */
    @Test
    fun apiVersion_parsesSemanticVersionStrings() {
        assertEquals(556_000, AmpacheApiVersion.parse("5.5.6"))
        assertEquals(600_000, AmpacheApiVersion.parse("6.0.0"))
        assertEquals(420_000, AmpacheApiVersion.parse("4.2.0"))
        // 两段式按 patch=0 处理
        assertEquals(550_000, AmpacheApiVersion.parse("5.5"))
    }

    /** 旧整数形态（6 位）原样取值；位数不足视为只给了主版本号 */
    @Test
    fun apiVersion_parsesRawIntegerStrings() {
        assertEquals(420_000, AmpacheApiVersion.parse("420000"))
        assertEquals(350_001, AmpacheApiVersion.parse("350001"))
        assertEquals(500_000, AmpacheApiVersion.parse("5"))
        assertEquals(600_000, AmpacheApiVersion.parse(" 6 "))
    }

    /** 空/非法一律 null（调用方按「不支持」处理） */
    @Test
    fun apiVersion_returnsNullForBlankOrMalformed() {
        assertNull(AmpacheApiVersion.parse(null))
        assertNull(AmpacheApiVersion.parse(""))
        assertNull(AmpacheApiVersion.parse("   "))
        assertNull(AmpacheApiVersion.parse("five.five"))
        assertNull(AmpacheApiVersion.parse("5.x.6"))
        assertNull(AmpacheApiVersion.parse("abc"))
    }

    /**
     * 播客支持判定：与 [AmpacheAuthSession.requestServerPodcastSupport] 内的表达式同构
     * （该方法本身要发握手请求，单测只锁判定逻辑）。
     */
    @Test
    fun podcastSupport_decisionMatchesProtocolSemantics() {
        assertEquals(true, supportsPodcast("5.5.6"))
        assertEquals(true, supportsPodcast("420000"))
        assertEquals(true, supportsPodcast("6.0.0"))
        assertEquals(false, supportsPodcast("4.1.0"))
        assertEquals(false, supportsPodcast("350001"))
        assertEquals(false, supportsPodcast(null))
        assertEquals(false, supportsPodcast(""))
    }

    private fun supportsPodcast(serverApiVersion: String?): Boolean {
        val version = AmpacheApiVersion.parse(serverApiVersion) ?: return false
        return version >= AmpacheAuthSession.PODCAST_SUPPORT_MIN_API_VERSION
    }

    private fun independentSha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
