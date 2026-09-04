/******************************************************************************
 *                                                                            *
 * Copyright (C) 2024  dyhkwong                                               *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <https://www.gnu.org/licenses/>.      *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.fmt.wireguard

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.sshtools.jini.INI
import com.sshtools.jini.INIWriter
import io.nekohasekai.sagernet.fmt.zlibDecompress
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.decodeBase64
import io.nekohasekai.sagernet.ktx.joinHostPort
import io.nekohasekai.sagernet.ktx.listByLineOrComma
import io.nekohasekai.sagernet.ktx.queryParameter
import libexclavecore.Libexclavecore
import java.io.ByteArrayInputStream
import java.io.StringWriter
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import kotlin.jvm.optionals.getOrNull

fun parseWireGuard(server: String): WireGuardBean {
    val link = Libexclavecore.parseURL(server)
    return WireGuardBean().apply {
        serverAddress = link.host
        serverPort = when {
            !link.hasPort() -> error("invalid port")
            else -> link.port
        }
        if (link.username.isNotEmpty()) {
            // https://github.com/XTLS/Xray-core/blob/d8934cf83946e88210b6bb95d793bc06e12b6db8/infra/conf/wireguard.go#L126-L148
            privateKey = link.username.replace('_', '/').replace('-', '+')
            if (privateKey.length == 43) privateKey += "="
            // v2rayNG style link
            // https://github.com/XTLS/Xray-core/blob/d8934cf83946e88210b6bb95d793bc06e12b6db8/infra/conf/wireguard.go#L75
            localAddress = "10.0.0.1/32\nfd59:7153:2388:b5fd:0000:0000:0000:0001/128"
        }
        (link.queryParameter("privatekey") ?: link.queryParameter("privateKey")) ?.let {
            if (it.length == 64) {
                privateKey = Base64.getEncoder().encodeToString(it.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            } else {
                privateKey = it.replace('_', '/').replace('-', '+')
                if (privateKey.length == 43) privateKey += "="
            }
        }
        (link.queryParameter("address") ?: link.queryParameter("ip")) ?.takeIf { it.isNotEmpty() }?.also {
            localAddress = it.split(",").joinToString("\n")
        }
        (link.queryParameter("publickey") ?: link.queryParameter("publicKey")) ?.let {
            if (it.length == 64) {
                peerPublicKey = Base64.getEncoder().encodeToString(it.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            } else {
                peerPublicKey = it.replace('_', '/').replace('-', '+')
                if (peerPublicKey.length == 43) peerPublicKey += "="
            }
        }
        (link.queryParameter("presharedkey") ?: link.queryParameter("preSharedKey")) ?.let {
            if (peerPreSharedKey.length == 43) peerPreSharedKey += "="
            if (it.length == 64) {
                peerPreSharedKey = Base64.getEncoder().encodeToString(it.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            } else {
                peerPreSharedKey = it.replace('_', '/').replace('-', '+')
                if (peerPreSharedKey.length == 43) peerPreSharedKey += "="
            }
        }
        link.queryParameter("mtu")?.toIntOrNull()?.takeIf { it > 0 }?.let {
            mtu = it
        }
        link.queryParameter("reserved")?.let {
            reserved = it
        }
        link.fragment?.let {
            name = it
        }
    }
}

fun parseAmneziaWG(link: String): List<WireGuardBean> {
    val encoded = link.substringAfter("://", "").substringBefore("#")
    require(encoded.isNotEmpty()) { "empty AmneziaWG config" }
    val name = link.substringAfter("#", "").takeIf { it.isNotEmpty() }
        ?.let { URLDecoder.decode(it, Charsets.UTF_8.name()) }
    return parseWireGuardConfig(encoded.decodeBase64(), forceAmnezia = true, profileName = name)
}

/**
 * Imports the universal NekoBox+ `sn://awg?` representation. NekoBox+ uses
 * Kryo's ByteBuffer format here; it is not compressed INI text.
 */
fun parseNekoBoxAmneziaBackup(link: String): WireGuardBean {
    val encoded = link.substringAfter("?", "").substringBefore("#")
    require(encoded.isNotEmpty()) { "empty AmneziaWG backup" }
    val bytes = Base64.getUrlDecoder().decode(encoded).zlibDecompress()
    return ByteBufferInput(ByteArrayInputStream(bytes)).use { input ->
        val version = input.readInt()
        require(version >= 4) { "unsupported AmneziaWG backup version: $version" }
        WireGuardBean().apply {
            serverAddress = input.readString()
            serverPort = input.readInt()
            localAddress = input.readString()
            privateKey = input.readString()
            peerPublicKey = input.readString()
            peerPreSharedKey = input.readString()
            keepaliveInterval = input.readString().toIntOrNull() ?: 0
            mtu = input.readInt()
            reserved = input.readString()

            isAmneziaWG = true
            awgJc = input.readInt()
            awgJmin = input.readInt()
            awgJmax = input.readInt()
            awgS1 = input.readInt()
            awgS2 = input.readInt()
            awgH1 = input.readString()
            awgH2 = input.readString()
            // S3/S4 were added between the first and second pair of headers
            // in NekoBox+'s serialized layout.
            awgS3 = input.readInt()
            awgS4 = input.readInt()
            awgH3 = input.readString()
            awgH4 = input.readString()
            awgI1 = input.readString()
            awgI2 = input.readString()
            awgI3 = input.readString()
            awgI4 = input.readString()
            awgI5 = input.readString()
            awgHeaderProtectionKey = input.readString()
            awgContentPaddingAddition = input.readString()
            awgRekeyAfterTime = input.readString()
            awgRekeyTimeout = input.readString()
            awgRejectAfterTime = input.readString()
            awgKeepaliveTimeout = input.readString()
            awgMaxHandshakeAttempts = input.readString()
            awgRandomizePacketTrailers = input.readBoolean()
            awgDisableCookieReplies = input.readBoolean()

            // AbstractBean footer. Newer footer fields are deliberately not
            // consumed because only the profile name is relevant to import.
            input.readInt()
            name = input.readString()
        }.applyDefaultValues()
    }
}

fun parseWireGuardConfig(
    conf: String,
    forceAmnezia: Boolean = false,
    profileName: String? = null,
): List<WireGuardBean> {
    val beans = mutableListOf<WireGuardBean>()
    val ini = try {
        INI.fromString(conf)
    } catch (_: Exception) {
        return beans
    }
    val iface = ini.sectionOr("Interface").getOrNull() ?: return beans
    val wgBean = WireGuardBean().apply {
        localAddress = iface.getAllOr("Address").getOrNull()
            ?.takeIf { it.isNotEmpty() }?.joinToString("\n")
            ?: return beans
        privateKey = iface.getOr("PrivateKey").getOrNull() ?: return beans
        mtu = iface.getOr("MTU").getOrNull()?.toIntOrNull()?.takeIf { it > 0 } ?: 1420
        name = profileName

        val awgKeys = listOf(
            "Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4",
            "H1", "H2", "H3", "H4", "I1", "I2", "I3", "I4", "I5",
            "HeaderProtectionKey", "ContentPaddingAddition", "RekeyAfterTime",
            "RekeyTimeout", "RejectAfterTime", "KeepaliveTimeout",
            "MaxHandshakeAttempts", "RandomizePacketTrailers", "DisableCookieReplies",
        )
        isAmneziaWG = forceAmnezia || awgKeys.any { iface.getOr(it).getOrNull() != null }
        awgJc = iface.getOr("Jc").getOrNull()?.toIntOrNull() ?: 0
        awgJmin = iface.getOr("Jmin").getOrNull()?.toIntOrNull() ?: 0
        awgJmax = iface.getOr("Jmax").getOrNull()?.toIntOrNull() ?: 0
        awgS1 = iface.getOr("S1").getOrNull()?.toIntOrNull() ?: 0
        awgS2 = iface.getOr("S2").getOrNull()?.toIntOrNull() ?: 0
        awgS3 = iface.getOr("S3").getOrNull()?.toIntOrNull() ?: 0
        awgS4 = iface.getOr("S4").getOrNull()?.toIntOrNull() ?: 0
        awgH1 = iface.getOr("H1").getOrNull().orEmpty()
        awgH2 = iface.getOr("H2").getOrNull().orEmpty()
        awgH3 = iface.getOr("H3").getOrNull().orEmpty()
        awgH4 = iface.getOr("H4").getOrNull().orEmpty()
        awgI1 = iface.getOr("I1").getOrNull().orEmpty()
        awgI2 = iface.getOr("I2").getOrNull().orEmpty()
        awgI3 = iface.getOr("I3").getOrNull().orEmpty()
        awgI4 = iface.getOr("I4").getOrNull().orEmpty()
        awgI5 = iface.getOr("I5").getOrNull().orEmpty()
        awgHeaderProtectionKey = iface.getOr("HeaderProtectionKey").getOrNull().orEmpty()
        awgContentPaddingAddition = iface.getOr("ContentPaddingAddition").getOrNull().orEmpty()
        awgRekeyAfterTime = iface.getOr("RekeyAfterTime").getOrNull().orEmpty()
        awgRekeyTimeout = iface.getOr("RekeyTimeout").getOrNull().orEmpty()
        awgRejectAfterTime = iface.getOr("RejectAfterTime").getOrNull().orEmpty()
        awgKeepaliveTimeout = iface.getOr("KeepaliveTimeout").getOrNull().orEmpty()
        awgMaxHandshakeAttempts = iface.getOr("MaxHandshakeAttempts").getOrNull().orEmpty()
        fun parseBoolean(value: String?) = when (value?.trim()?.lowercase()) {
            "1", "true", "yes", "on" -> true
            else -> false
        }
        awgRandomizePacketTrailers = parseBoolean(iface.getOr("RandomizePacketTrailers").getOrNull())
        awgDisableCookieReplies = parseBoolean(iface.getOr("DisableCookieReplies").getOrNull())
    }
    val peers = ini.allSectionsOr("Peer").getOrNull() ?: return beans
    for (peer in peers) {
        val endpoint = peer.getOr("Endpoint").getOrNull()
        if (endpoint.isNullOrEmpty() || !endpoint.contains(":")) {
            continue
        }
        beans.add(wgBean.applyDefaultValues().clone().apply {
            val hostPort = Libexclavecore.splitHostPort(endpoint)
            serverAddress = hostPort.host
            serverPort = hostPort.port
            peerPublicKey = peer.getOr("PublicKey").getOrNull() ?: continue
            peerPreSharedKey = peer.getOr("PreSharedKey").getOrNull().orEmpty()
            keepaliveInterval = peer.getOr("PersistentKeepalive").getOrNull()
                ?.toIntOrNull()?.takeIf { it > 0 } ?: 0
            allowedIPs = peer.getAllOr("AllowedIPs").getOrNull()
                ?.flatMap { it.split(",") }
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.joinToString("\n")
                ?.takeIf { it.isNotEmpty() }
                ?: "0.0.0.0/0\n::/0"
        })
    }
    return beans
}

fun WireGuardBean.toConf(): String {
    val ini = INI.create()
    val iface = ini.create("Interface")
    iface.put("Address", localAddress.listByLineOrComma())
    if (mtu > 0) {
        iface.put("MTU", mtu)
    }
    iface.put("PrivateKey", privateKey.ifEmpty { error("empty private key") })
    if (isAmneziaWG) {
        fun putInt(name: String, value: Int) {
            if (value != 0) iface.put(name, value)
        }
        fun putString(name: String, value: String) {
            if (value.isNotEmpty()) iface.put(name, value)
        }
        putInt("Jc", awgJc)
        putInt("Jmin", awgJmin)
        putInt("Jmax", awgJmax)
        putInt("S1", awgS1)
        putInt("S2", awgS2)
        putInt("S3", awgS3)
        putInt("S4", awgS4)
        putString("H1", awgH1)
        putString("H2", awgH2)
        putString("H3", awgH3)
        putString("H4", awgH4)
        putString("I1", awgI1)
        putString("I2", awgI2)
        putString("I3", awgI3)
        putString("I4", awgI4)
        putString("I5", awgI5)
        putString("HeaderProtectionKey", awgHeaderProtectionKey)
        putString("ContentPaddingAddition", awgContentPaddingAddition)
        putString("RekeyAfterTime", awgRekeyAfterTime)
        putString("RekeyTimeout", awgRekeyTimeout)
        putString("RejectAfterTime", awgRejectAfterTime)
        putString("KeepaliveTimeout", awgKeepaliveTimeout)
        putString("MaxHandshakeAttempts", awgMaxHandshakeAttempts)
        if (awgRandomizePacketTrailers) iface.put("RandomizePacketTrailers", true)
        if (awgDisableCookieReplies) iface.put("DisableCookieReplies", true)
    }
    val peer = ini.create("Peer")
    peer.put("Endpoint", joinHostPort(serverAddress, serverPort))
    peer.put("PublicKey", peerPublicKey.ifEmpty { error("empty peer public key") })
    if (peerPreSharedKey.isNotEmpty()) {
        peer.put("PreSharedKey", peerPreSharedKey)
    }
    if (keepaliveInterval > 0) {
        peer.put("PersistentKeepalive", keepaliveInterval)
    }
    if (allowedIPs.isNotEmpty()) {
        peer.put("AllowedIPs", allowedIPs.listByLineOrComma())
    }
    val conf = StringWriter()
    INIWriter.Builder()
        .withIndent(0)
        .withStringQuoteMode(INIWriter.StringQuoteMode.NEVER)
        .build()
        .write(ini, conf)
    return conf.toString()
}

fun WireGuardBean.toAmneziaWGUri(): String {
    require(isAmneziaWG) { "not an AmneziaWG profile" }
    val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(toConf().toByteArray())
    val encodedName = name.takeIf { it.isNotEmpty() }
        ?.let { URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20") }
    return buildString {
        append("amneziawg://")
        append(encoded)
        if (encodedName != null) append('#').append(encodedName)
    }
}
