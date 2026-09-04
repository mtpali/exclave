/******************************************************************************
 * Copyright (C) 2021 by nekohasekai <contact-git@sekai.icu>                  *
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

package io.nekohasekai.sagernet.fmt.wireguard;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class WireGuardBean extends AbstractBean {

    public String localAddress;
    public String privateKey;
    public String peerPublicKey;
    public String peerPreSharedKey;
    public Integer mtu;
    public String reserved;
    public Integer keepaliveInterval;
    public String allowedIPs;

    public Boolean isAmneziaWG;
    public Integer awgJc;
    public Integer awgJmin;
    public Integer awgJmax;
    public Integer awgS1;
    public Integer awgS2;
    public Integer awgS3;
    public Integer awgS4;
    public String awgH1;
    public String awgH2;
    public String awgH3;
    public String awgH4;
    public String awgI1;
    public String awgI2;
    public String awgI3;
    public String awgI4;
    public String awgI5;
    public String awgHeaderProtectionKey;
    public String awgContentPaddingAddition;
    public String awgRekeyAfterTime;
    public String awgRekeyTimeout;
    public String awgRejectAfterTime;
    public String awgKeepaliveTimeout;
    public String awgMaxHandshakeAttempts;
    public Boolean awgRandomizePacketTrailers;
    public Boolean awgDisableCookieReplies;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (localAddress == null) localAddress = "";
        if (privateKey == null) privateKey = "";
        if (peerPublicKey == null) peerPublicKey = "";
        if (peerPreSharedKey == null) peerPreSharedKey = "";
        // wireguard default mtu
        if (mtu == null) mtu = 1420;
        if (reserved == null) reserved = "";
        if (keepaliveInterval == null) keepaliveInterval = 0;
        if (allowedIPs == null) allowedIPs = "0.0.0.0/0\n::/0";
        if (isAmneziaWG == null) isAmneziaWG = false;
        if (awgJc == null) awgJc = 0;
        if (awgJmin == null) awgJmin = 0;
        if (awgJmax == null) awgJmax = 0;
        if (awgS1 == null) awgS1 = 0;
        if (awgS2 == null) awgS2 = 0;
        if (awgS3 == null) awgS3 = 0;
        if (awgS4 == null) awgS4 = 0;
        if (awgH1 == null) awgH1 = "";
        if (awgH2 == null) awgH2 = "";
        if (awgH3 == null) awgH3 = "";
        if (awgH4 == null) awgH4 = "";
        if (awgI1 == null) awgI1 = "";
        if (awgI2 == null) awgI2 = "";
        if (awgI3 == null) awgI3 = "";
        if (awgI4 == null) awgI4 = "";
        if (awgI5 == null) awgI5 = "";
        if (awgHeaderProtectionKey == null) awgHeaderProtectionKey = "";
        if (awgContentPaddingAddition == null) awgContentPaddingAddition = "";
        if (awgRekeyAfterTime == null) awgRekeyAfterTime = "";
        if (awgRekeyTimeout == null) awgRekeyTimeout = "";
        if (awgRejectAfterTime == null) awgRejectAfterTime = "";
        if (awgKeepaliveTimeout == null) awgKeepaliveTimeout = "";
        if (awgMaxHandshakeAttempts == null) awgMaxHandshakeAttempts = "";
        if (awgRandomizePacketTrailers == null) awgRandomizePacketTrailers = false;
        if (awgDisableCookieReplies == null) awgDisableCookieReplies = false;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(5);
        super.serialize(output);
        output.writeString(localAddress);
        output.writeString(privateKey);
        output.writeString(peerPublicKey);
        output.writeString(peerPreSharedKey);
        output.writeInt(mtu);
        output.writeString(reserved);
        output.writeInt(keepaliveInterval);
        output.writeString(allowedIPs);
        output.writeBoolean(isAmneziaWG);
        output.writeInt(awgJc);
        output.writeInt(awgJmin);
        output.writeInt(awgJmax);
        output.writeInt(awgS1);
        output.writeInt(awgS2);
        output.writeInt(awgS3);
        output.writeInt(awgS4);
        output.writeString(awgH1);
        output.writeString(awgH2);
        output.writeString(awgH3);
        output.writeString(awgH4);
        output.writeString(awgI1);
        output.writeString(awgI2);
        output.writeString(awgI3);
        output.writeString(awgI4);
        output.writeString(awgI5);
        output.writeString(awgHeaderProtectionKey);
        output.writeString(awgContentPaddingAddition);
        output.writeString(awgRekeyAfterTime);
        output.writeString(awgRekeyTimeout);
        output.writeString(awgRejectAfterTime);
        output.writeString(awgKeepaliveTimeout);
        output.writeString(awgMaxHandshakeAttempts);
        output.writeBoolean(awgRandomizePacketTrailers);
        output.writeBoolean(awgDisableCookieReplies);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        localAddress = input.readString();
        privateKey = input.readString();
        peerPublicKey = input.readString();
        peerPreSharedKey = input.readString();
        if (version <= 2) {
            // On earlier versions, the code copied from Xray accepts non-standard addresses and keys.
            // https://github.com/XTLS/Xray-core/blob/d8934cf83946e88210b6bb95d793bc06e12b6db8/infra/conf/wireguard.go#L75
            // https://github.com/XTLS/Xray-core/blob/d8934cf83946e88210b6bb95d793bc06e12b6db8/infra/conf/wireguard.go#L126-L148
            if (localAddress.isEmpty()) {
                localAddress = "10.0.0.1/32\nfd59:7153:2388:b5fd:0000:0000:0000:0001/128";
            }
            if (!privateKey.isEmpty()) {
                privateKey = String.format("%-44s", privateKey.replace('_', '/').replace('-', '+')).replace(' ', '=');
            }
            if (!peerPublicKey.isEmpty()) {
                peerPublicKey = String.format("%-44s", peerPublicKey.replace('_', '/').replace('-', '+')).replace(' ', '=');
            }
            if (!peerPreSharedKey.isEmpty()) {
                peerPreSharedKey = String.format("%-44s", peerPreSharedKey.replace('_', '/').replace('-', '+')).replace(' ', '=');
            }
        }
        if (version >= 1) {
            mtu = input.readInt();
        }
        if (version >= 2) {
            reserved = input.readString();
        }
        if (version >= 4) {
            keepaliveInterval = input.readInt();
        }
        if (version >= 5) {
            allowedIPs = input.readString();
            isAmneziaWG = input.readBoolean();
            awgJc = input.readInt();
            awgJmin = input.readInt();
            awgJmax = input.readInt();
            awgS1 = input.readInt();
            awgS2 = input.readInt();
            awgS3 = input.readInt();
            awgS4 = input.readInt();
            awgH1 = input.readString();
            awgH2 = input.readString();
            awgH3 = input.readString();
            awgH4 = input.readString();
            awgI1 = input.readString();
            awgI2 = input.readString();
            awgI3 = input.readString();
            awgI4 = input.readString();
            awgI5 = input.readString();
            awgHeaderProtectionKey = input.readString();
            awgContentPaddingAddition = input.readString();
            awgRekeyAfterTime = input.readString();
            awgRekeyTimeout = input.readString();
            awgRejectAfterTime = input.readString();
            awgKeepaliveTimeout = input.readString();
            awgMaxHandshakeAttempts = input.readString();
            awgRandomizePacketTrailers = input.readBoolean();
            awgDisableCookieReplies = input.readBoolean();
        }
    }

    @Override
    public void applyFeatureSettings(AbstractBean other) {
        if (!(other instanceof WireGuardBean bean)) return;
        bean.mtu = mtu;
    }

    @NotNull
    @Override
    public WireGuardBean clone() {
        return KryoConverters.deserialize(new WireGuardBean(), KryoConverters.serialize(this));
    }

    public static final Creator<WireGuardBean> CREATOR = new CREATOR<>() {
        @NonNull
        @Override
        public WireGuardBean newInstance() {
            return new WireGuardBean();
        }

        @Override
        public WireGuardBean[] newArray(int size) {
            return new WireGuardBean[size];
        }
    };
}
