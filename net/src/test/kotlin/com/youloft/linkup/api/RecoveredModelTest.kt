package com.youloft.linkup.api

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.youloft.linkup.api.dto.ChannelConfig
import com.youloft.linkup.net.ApiResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * The wire format is the referee for whether the recovered field names are right: neither APK keeps
 * `@SerializedName`, so Gson must be reading plain Kotlin property names. These fixtures were
 * captured from the live test and production gateways, so a rename in the generated models fails here.
 */
class RecoveredModelTest {
    private val gson = Gson()

    private fun load(name: String): ApiResponse<ChannelConfig> {
        val type = TypeToken.getParameterized(ApiResponse::class.java, ChannelConfig::class.java).type
        return gson.fromJson(javaClass.getResourceAsStream("/$name").reader(Charsets.UTF_8), type)
    }

    @Test
    fun `production config parses into the recovered model`() {
        val response = load("prod_client_config.json")
        assertEquals(200, response.status)
        assertEquals("success", response.msg)
        val config = assertNotNull(response.data)
        assertFalse(config.notRelativesLockShow)
        assertFalse(config.halfScreenVipPageShow)
        assertEquals(0, config.autoReportAddressShow)
        assertEquals("周年", config.vipMarketingCopy)
        assertNotNull(config.imGroupHuawei)
        assertNotNull(config.imGroupHonor)
        assertNotNull(config.imGroupVivo)
        assertNotNull(config.imGroupXiaomi)
        assertNotNull(config.imGroupOppo)
    }

    @Test
    fun `test gateway is a different environment than production`() {
        val prod = assertNotNull(load("prod_client_config.json").data)
        val test = assertNotNull(load("test_client_config.json").data)
        assertEquals(true, test.notRelativesLockShow)
        assertEquals(false, prod.notRelativesLockShow)
        assertNotNull(test.imGroupHuawei)
        assertFalse(test.imGroupHuawei == prod.imGroupHuawei)
    }
}
