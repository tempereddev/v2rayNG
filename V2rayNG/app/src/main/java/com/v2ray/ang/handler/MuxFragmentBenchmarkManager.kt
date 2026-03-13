package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType

object MuxFragmentBenchmarkManager {
    fun supportsMux(profile: ProfileItem): Boolean {
        if (profile.configType != EConfigType.VMESS && profile.configType != EConfigType.VLESS) {
            return false
        }
        return profile.network != NetworkType.XHTTP.type
    }

    fun supportsFragment(profile: ProfileItem): Boolean {
        return profile.security == AppConfig.TLS || profile.security == AppConfig.REALITY
    }
}
