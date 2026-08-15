package com.atanx.lensrelay;

import com.swmansion.moqkit.publish.Publisher;

import uniffi.moq.MoqBroadcastProducer;

final class MoqPublisherBridge {
    private MoqPublisherBridge() {}

    static MoqBroadcastProducer broadcast(Publisher publisher) {
        return publisher.getBroadcast$moqkit();
    }
}
