package com.noway.edge

import android.app.Application
import com.noway.edge.scan.ScanManager
import org.xbill.DNS.config.AndroidResolverConfigProvider

class NowayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidResolverConfigProvider.setContext(this)
        ScanManager.initialize(this)
    }
}
