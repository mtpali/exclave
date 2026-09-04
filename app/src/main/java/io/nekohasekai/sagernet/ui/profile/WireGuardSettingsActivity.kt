/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>             *
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
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.unwrapIDN

class WireGuardSettingsActivity : ProfileSettingsActivity<WireGuardBean>() {

    companion object {
        const val EXTRA_AMNEZIAWG = "amneziawg"
    }

    override fun createEntity() = WireGuardBean().apply {
        isAmneziaWG = intent.getBooleanExtra(EXTRA_AMNEZIAWG, false)
    }

    override fun WireGuardBean.init() {
        DataStore.profileName = name

        DataStore.serverLocalAddress = localAddress
        DataStore.serverPrivateKey = privateKey

        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort

        DataStore.serverCertificates = peerPublicKey
        DataStore.serverPassword = peerPreSharedKey

        DataStore.serverMTU = mtu
        DataStore.serverWireGuardReserved = reserved
        DataStore.serverWireGuardKeepaliveInterval = keepaliveInterval
        DataStore.serverWireGuardAllowedIPs = allowedIPs
        DataStore.serverWireGuardAmnezia = isAmneziaWG
        DataStore.serverWireGuardAwgJc = awgJc
        DataStore.serverWireGuardAwgJmin = awgJmin
        DataStore.serverWireGuardAwgJmax = awgJmax
        DataStore.serverWireGuardAwgS1 = awgS1
        DataStore.serverWireGuardAwgS2 = awgS2
        DataStore.serverWireGuardAwgS3 = awgS3
        DataStore.serverWireGuardAwgS4 = awgS4
        DataStore.serverWireGuardAwgH1 = awgH1
        DataStore.serverWireGuardAwgH2 = awgH2
        DataStore.serverWireGuardAwgH3 = awgH3
        DataStore.serverWireGuardAwgH4 = awgH4
        DataStore.serverWireGuardAwgI1 = awgI1
        DataStore.serverWireGuardAwgI2 = awgI2
        DataStore.serverWireGuardAwgI3 = awgI3
        DataStore.serverWireGuardAwgI4 = awgI4
        DataStore.serverWireGuardAwgI5 = awgI5
        DataStore.serverWireGuardAwgHeaderProtectionKey = awgHeaderProtectionKey
        DataStore.serverWireGuardAwgContentPadding = awgContentPaddingAddition
        DataStore.serverWireGuardAwgRekeyAfterTime = awgRekeyAfterTime
        DataStore.serverWireGuardAwgRekeyTimeout = awgRekeyTimeout
        DataStore.serverWireGuardAwgRejectAfterTime = awgRejectAfterTime
        DataStore.serverWireGuardAwgKeepaliveTimeout = awgKeepaliveTimeout
        DataStore.serverWireGuardAwgMaxHandshakeAttempts = awgMaxHandshakeAttempts
        DataStore.serverWireGuardAwgRandomizePacketTrailers = awgRandomizePacketTrailers
        DataStore.serverWireGuardAwgDisableCookieReplies = awgDisableCookieReplies
    }

    override fun WireGuardBean.serialize() {
        name = DataStore.profileName

        localAddress = DataStore.serverLocalAddress
        privateKey = DataStore.serverPrivateKey

        serverAddress = DataStore.serverAddress.unwrapIDN()
        serverPort = DataStore.serverPort

        peerPublicKey = DataStore.serverCertificates
        peerPreSharedKey = DataStore.serverPassword

        mtu = DataStore.serverMTU
        reserved = DataStore.serverWireGuardReserved
        keepaliveInterval = DataStore.serverWireGuardKeepaliveInterval
        allowedIPs = DataStore.serverWireGuardAllowedIPs
        isAmneziaWG = DataStore.serverWireGuardAmnezia
        awgJc = DataStore.serverWireGuardAwgJc
        awgJmin = DataStore.serverWireGuardAwgJmin
        awgJmax = DataStore.serverWireGuardAwgJmax
        awgS1 = DataStore.serverWireGuardAwgS1
        awgS2 = DataStore.serverWireGuardAwgS2
        awgS3 = DataStore.serverWireGuardAwgS3
        awgS4 = DataStore.serverWireGuardAwgS4
        awgH1 = DataStore.serverWireGuardAwgH1
        awgH2 = DataStore.serverWireGuardAwgH2
        awgH3 = DataStore.serverWireGuardAwgH3
        awgH4 = DataStore.serverWireGuardAwgH4
        awgI1 = DataStore.serverWireGuardAwgI1
        awgI2 = DataStore.serverWireGuardAwgI2
        awgI3 = DataStore.serverWireGuardAwgI3
        awgI4 = DataStore.serverWireGuardAwgI4
        awgI5 = DataStore.serverWireGuardAwgI5
        awgHeaderProtectionKey = DataStore.serverWireGuardAwgHeaderProtectionKey
        awgContentPaddingAddition = DataStore.serverWireGuardAwgContentPadding
        awgRekeyAfterTime = DataStore.serverWireGuardAwgRekeyAfterTime
        awgRekeyTimeout = DataStore.serverWireGuardAwgRekeyTimeout
        awgRejectAfterTime = DataStore.serverWireGuardAwgRejectAfterTime
        awgKeepaliveTimeout = DataStore.serverWireGuardAwgKeepaliveTimeout
        awgMaxHandshakeAttempts = DataStore.serverWireGuardAwgMaxHandshakeAttempts
        awgRandomizePacketTrailers = DataStore.serverWireGuardAwgRandomizePacketTrailers
        awgDisableCookieReplies = DataStore.serverWireGuardAwgDisableCookieReplies
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.wireguard_preferences)
        findPreference<EditTextPreference>(Key.SERVER_PORT)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }
        findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
        findPreference<EditTextPreference>(Key.SERVER_PRIVATE_KEY)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
        findPreference<EditTextPreference>(Key.SERVER_MTU)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
        findPreference<EditTextPreference>(Key.SERVER_WIREGUARD_KEEPALIVE_INTERVAL)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
        if (DataStore.serverWireGuardAmnezia) {
            findPreference<EditTextPreference>(Key.SERVER_WIREGUARD_ALLOWED_IPS)!!.isVisible = true
            listOf(
                "amneziawg10Category",
                "amneziawg15Category",
                "amneziawg20Category",
                "amneziawg30Category",
                "amneziawg31Category",
            ).forEach { key ->
                findPreference<PreferenceCategory>(key)!!.isVisible = true
            }
        }
        listOf(
            Key.SERVER_WIREGUARD_AWG_JC,
            Key.SERVER_WIREGUARD_AWG_JMIN,
            Key.SERVER_WIREGUARD_AWG_JMAX,
            Key.SERVER_WIREGUARD_AWG_S1,
            Key.SERVER_WIREGUARD_AWG_S2,
            Key.SERVER_WIREGUARD_AWG_S3,
            Key.SERVER_WIREGUARD_AWG_S4,
        ).forEach { key ->
            findPreference<EditTextPreference>(key)!!.apply {
                setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
            }
        }
    }

}
