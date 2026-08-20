package com.mlmvpn.scanner.engines.hybrid

import android.content.Context
import android.util.Log
import com.mlmvpn.core.warp.IVpnEngine
import com.mlmvpn.core.warp.VlessXrayInjector
import com.mlmvpn.scanner.engines.gst.GstLog
import com.mlmvpn.scanner.utils.DomainPreResolver
import com.mlmvpn.scanner.utils.VpnConfig
import com.mlmvpn.scanner.utils.XrayJsonGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Two-hop connector used by the dedicated "V2Ray + Psiphon" screen.
 *
 * Hop 1 (the "Psiphon" layer) is one of:
 *   - `fragment`: Xray TLS-hello fragmentation, the same DPI-evasion idea Psiphon uses
 *     (OSSH / meek-style obfuscation) without embedding a second gomobile Go runtime.
 *     libv2ray.aar already occupies `libgojni.so`, so the official Psiphon AAR cannot
 *     ship in the same APK.
 *   - `socks`: dial the V2Ray outbound through a local SOCKS (the official Psiphon app,
 *     or any other circumvention client listening on 127.0.0.1).
 *
 * Hop 2 is the user's VLESS / VMess / Trojan config, chained with `sockopt.dialerProxy`.
 */
class PsiphonV2rayEngine(private val fd: Int) : IVpnEngine {

    private var injector: VlessXrayInjector? = null

    override suspend fun start(context: Context, config: String, localPort: Int): Boolean {
        hopStatusFlow.value = HopStatus.HOPPING
        _hopMessage.value = ""
        return try {
            val spec = JSONObject(config)
            val v2rayUri = spec.optString("v2rayUri")
            val hop = spec.optString("hop", HOP_FRAGMENT).ifBlank { HOP_FRAGMENT }
            val socksPort = spec.optInt("socksPort", DEFAULT_SOCKS_PORT)
            val socksAddress = spec.optString("socksAddress", "127.0.0.1").ifBlank { "127.0.0.1" }
            val profile = spec.optString("fragmentProfile", PROFILE_BALANCED).ifBlank { PROFILE_BALANCED }

            val vpnConfig = VpnConfig.parseUri(v2rayUri)
            if (vpnConfig == null) {
                fail("invalid V2Ray URI")
                return false
            }

            val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            val backendDns = prefs.getString("backend_dns", "1.1.1.1") ?: "1.1.1.1"
            val allowLan = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(context)
                .getBoolean("allow_lan", false)
            val mtu = context.getSharedPreferences("vpn_routing_prefs", Context.MODE_PRIVATE)
                .getInt("vpn_mtu", 1420)

            val (packets, length, interval) = fragmentFor(profile)

            hopStatusFlow.value = HopStatus.CHAINING
            GstLog.i(
                TAG,
                "hybrid hop=$hop profile=$profile socks=$socksAddress:$socksPort " +
                    "v2ray=${vpnConfig.protocol}://${vpnConfig.address}:${vpnConfig.port}"
            )

            val pinned = if (hop == HOP_SOCKS) emptyMap() else DomainPreResolver.pinnedHostsFor(vpnConfig)
            val jsonConfig = XrayJsonGenerator.generateChainedConfig(
                config = vpnConfig,
                localPort = localPort,
                backendDns = backendDns,
                allowLan = allowLan,
                includeTun = fd > 0,
                mtu = mtu,
                hop = hop,
                socksAddress = socksAddress,
                socksPort = socksPort,
                fragmentPackets = packets,
                fragmentLength = length,
                fragmentInterval = interval,
                pinnedHostIps = pinned
            )

            val engine = VlessXrayInjector(fd)
            val ok = engine.start(context, jsonConfig, localPort)
            if (!ok) {
                fail("xray core failed to start")
                return false
            }
            injector = engine
            hopStatusFlow.value = HopStatus.CONNECTED
            _hopMessage.value = ""
            GstLog.i(TAG, "hybrid connected (fd=$fd, hop=$hop)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            fail(e.message ?: e.javaClass.simpleName)
            false
        }
    }

    override fun stop() {
        try { injector?.stop() } catch (_: Exception) {}
        injector = null
        hopStatusFlow.value = HopStatus.IDLE
        _hopMessage.value = ""
    }

    private fun fail(reason: String) {
        hopStatusFlow.value = HopStatus.FAILED
        _hopMessage.value = reason
        GstLog.e(TAG, "hybrid failed: $reason")
        try { injector?.stop() } catch (_: Exception) {}
        injector = null
    }

    companion object {
        const val CONFIG_TYPE = "psiphon-v2ray"
        const val NODE_ID = "psiphon-v2ray-hybrid"
        const val HOP_FRAGMENT = "fragment"
        const val HOP_SOCKS = "socks"
        const val PROFILE_LIGHT = "light"
        const val PROFILE_BALANCED = "balanced"
        const val PROFILE_AGGRESSIVE = "aggressive"
        const val DEFAULT_SOCKS_PORT = 1081

        /** Official Psiphon Android packages — excluded from the TUN so a local-SOCKS hop cannot loop. */
        val PSIPHON_PACKAGES = arrayOf(
            "com.psiphon3",
            "com.psiphon3.subscription",
            "ca.psiphon.conduit"
        )

        enum class HopStatus { IDLE, HOPPING, CHAINING, CONNECTED, FAILED }

        val hopStatusFlow = MutableStateFlow(HopStatus.IDLE)
        private val _hopMessage = MutableStateFlow("")
        val hopMessageFlow: StateFlow<String> = _hopMessage

        private const val TAG = "PsiphonV2ray"

        fun buildConfigJson(
            v2rayUri: String,
            hop: String,
            fragmentProfile: String = PROFILE_BALANCED,
            socksPort: Int = DEFAULT_SOCKS_PORT,
            socksAddress: String = "127.0.0.1"
        ): String = JSONObject().apply {
            put("type", CONFIG_TYPE)
            put("v2rayUri", v2rayUri)
            put("hop", hop)
            put("fragmentProfile", fragmentProfile)
            put("socksPort", socksPort)
            put("socksAddress", socksAddress)
        }.toString()

        fun fragmentFor(profile: String): Triple<String, String, String> = when (profile) {
            PROFILE_LIGHT -> Triple("tlshello", "50-100", "5-10")
            PROFILE_AGGRESSIVE -> Triple("1-3", "1-5", "5-10")
            else -> Triple("tlshello", "100-200", "10-20")
        }
    }
}
