package com.sarab.vision.watch

import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.sarab.vision.wear.shared.WearProtocol

class WatchListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val repository = WatchRepository.get(this)
        when (event.path) {
            WearProtocol.ALERT -> repository.receiveAlert(event.data)
            WearProtocol.RECEIVED -> repository.receiveReceipt(event.data)
            WearProtocol.STATE -> repository.receiveState(event.data)
        }
    }

    override fun onDataChanged(events: DataEventBuffer) {
        val repository = WatchRepository.get(this)
        for (event in events) if (event.type == DataEvent.TYPE_CHANGED) {
            val path = event.dataItem.uri.path.orEmpty()
            when {
                path.startsWith(WearProtocol.ALERTS) -> repository.receiveAlert(event.dataItem.data)
                path.startsWith(WearProtocol.RECEIPTS) -> repository.receiveReceipt(event.dataItem.data)
                path == WearProtocol.STATE -> repository.receiveState(event.dataItem.data)
            }
        }
    }

    override fun onCapabilityChanged(info: CapabilityInfo) { WatchRepository.get(this).refreshConnection() }
}
