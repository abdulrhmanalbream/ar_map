package com.sarab.vision.wear

import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.sarab.vision.wear.shared.WearProtocol

class PhoneWearListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == WearProtocol.EVENT) PhoneWearBridge.get(this).receiveEvent(event.data, event.sourceNodeId)
    }

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) if (event.type == DataEvent.TYPE_CHANGED &&
            event.dataItem.uri.path?.startsWith(WearProtocol.OUTBOX) == true) {
            PhoneWearBridge.get(this).receiveEvent(event.dataItem.data, event.dataItem.uri.host.orEmpty())
        }
    }

    override fun onCapabilityChanged(info: CapabilityInfo) { PhoneWearBridge.get(this).refreshConnection() }
}
