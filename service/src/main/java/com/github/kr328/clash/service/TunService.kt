package com.github.kr328.clash.service

import android.content.Intent
import android.net.VpnService
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.clash.ClashRuntime
import com.github.kr328.clash.service.clash.module.*
import com.github.kr328.clash.service.settings.ServiceSettings
import com.github.kr328.clash.service.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TunService : VpnService(), CoroutineScope by MainScope() {
    companion object {
        // from https://github.com/shadowsocks/shadowsocks-android/blob/master/core/src/main/java/com/github/shadowsocks/bg/VpnService.kt
        private const val VPN_MTU = 65535
        private const val PRIVATE_VLAN4_SUBNET = 30
        private const val PRIVATE_VLAN6_SUBNET = 126
        private const val PRIVATE_VLAN4_CLIENT = "172.31.255.253"
        private const val PRIVATE_VLAN6_CLIENT = "fdfe:dcba:9876::1"
        private const val PRIVATE_VLAN4_MIRROR = "172.31.255.254"
        private const val PRIVATE_VLAN6_MIRROR = "fdfe:dcba:9876::2"
        private const val PRIVATE_VLAN_DNS = "198.18.0.1"
    }

    private val service = this
    private val runtime = ClashRuntime(this)
    private var reason: String? = null

    override fun onCreate() {
        super.onCreate()

        Clash.initialize(this)

        if (ServiceStatusProvider.serviceRunning)
            return stopSelf()

        ServiceStatusProvider.serviceRunning = true

        StaticNotificationModule.createNotificationChannel(this)
        StaticNotificationModule.notifyLoadingNotification(this)

        launch {
            val settings = ServiceSettings(service)
            val dnsInject = DnsInjectModule()

            runtime.install(ReloadModule(service)) {
                onLoaded {
                    if ( it != null ) {
                        reason = it.message

                        stopSelf()

                        TunModule.requestStop()
                    }
                    else {
                        broadcastProfileLoaded()
                    }
                }
                onEmpty {
                    launch {
                        reason = "No selected profile"

                        stopSelf()

                        TunModule.requestStop()
                    }
                }
            }
            runtime.install(CloseModule()) {
                onClose {
                    launch {
                        reason = null

                        stopSelf()

                        TunModule.requestStop()
                    }
                }
            }

            if (settings.get(ServiceSettings.NOTIFICATION_REFRESH))
                runtime.install(DynamicNotificationModule(service))
            else
                runtime.install(StaticNotificationModule(service))

            runtime.install(TunModule(service)) {
                configure = TunConfigure(settings)
            }

            runtime.install(dnsInject) {
                dnsOverride = settings.get(ServiceSettings.OVERRIDE_DNS)
            }

            runtime.install(NetworkObserveModule(service)) {
                onNetworkChanged { network, dnsServers ->
                    setUnderlyingNetworks(network?.let { arrayOf(it) })

                    if (settings.get(ServiceSettings.AUTO_ADD_SYSTEM_DNS)) {
                        val dnsStrings = dnsServers.map {
                            it.asSocketAddressText(53)
                        }

                        dnsInject.appendDns = dnsStrings
                    }

                    broadcastNetworkChanged()
                }
            }

            runtime.exec()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        broadcastClashStarted()

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        ServiceStatusProvider.serviceRunning = false

        broadcastClashStopped(reason)

        cancel()

        super.onDestroy()
    }

    private inner class TunConfigure(private val settings: ServiceSettings) : TunModule.Configure {
        override val builder: Builder
            get() = Builder()
        override val mtu: Int
            get() = VPN_MTU
        override val gateway4: String
            get() = "$PRIVATE_VLAN4_CLIENT/$PRIVATE_VLAN4_SUBNET"
        override val mirror4: String
            get() = PRIVATE_VLAN4_MIRROR
        override val route4: List<String>
            get() {
                return if (settings.get(ServiceSettings.BYPASS_PRIVATE_NETWORK))
                    resources.getStringArray(R.array.bypass_private_route).toList()
                else
                    listOf("0.0.0.0/0")
            }
        override val gateway6: String?
            get() {
                return if (settings.get(ServiceSettings.IPV6_SUPPORT))
                    "$PRIVATE_VLAN6_CLIENT/$PRIVATE_VLAN6_SUBNET"
                else null
            }
        override val mirror6: String?
            get() {
                return if (settings.get(ServiceSettings.IPV6_SUPPORT))
                    PRIVATE_VLAN6_MIRROR
                else null
            }
        override val route6: List<String>?
            get() {
                // from https://github.com/shadowsocks/shadowsocks-android/commit/cc840c9fddb3f4f6677005de18f1fcb387b84064#diff-e089fe63dcb3674c0a1e459a95508e3e
                return if (settings.get(ServiceSettings.IPV6_SUPPORT))
                    listOf("2000::/3")
                else null
            }
        override val dnsAddress: String
            get() = PRIVATE_VLAN_DNS
        override val dnsHijacking: Boolean
            get() = settings.get(ServiceSettings.DNS_HIJACKING)
        override val allowApplications: List<String>
            get() {
                return if (settings.get(ServiceSettings.ACCESS_CONTROL_MODE) == ServiceSettings.ACCESS_CONTROL_MODE_WHITELIST) {
                    settings.get(ServiceSettings.ACCESS_CONTROL_PACKAGES).toList()
                } else emptyList()
            }
        override val disallowApplication: List<String>
            get() {
                return if (settings.get(ServiceSettings.ACCESS_CONTROL_MODE) == ServiceSettings.ACCESS_CONTROL_MODE_BLACKLIST) {
                    settings.get(ServiceSettings.ACCESS_CONTROL_PACKAGES).toList()
                } else emptyList()
            }

    }
}