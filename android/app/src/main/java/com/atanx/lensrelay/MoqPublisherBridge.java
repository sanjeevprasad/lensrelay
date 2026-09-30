package com.atanx.lensrelay;

import com.swmansion.moqkit.publish.Publisher;
import uniffi.moq.MoqBroadcastProducer;

/**
 * Kotlin cannot spell moqkit's dollar-suffixed internal accessors, so the
 * attach call is routed through Java where the name is a legal identifier.
 */
final class MoqPublisherBridge {
    private MoqPublisherBridge() {}

    static void attach(Publisher publisher, MoqBroadcastProducer broadcast) {
        publisher.attachBroadcast$moqkit(broadcast);
    }
}
