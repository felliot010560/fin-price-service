package com.aleatory.price.backend.messaging.websockets;

import java.lang.reflect.Type;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.stereotype.Service;

import com.aleatory.common.messaging.impl.websockets.WebsocketsPubSubMessagingOperations;
import com.aleatory.price.api.PriceStompController;

/**
 * This class is largely a no-op; the price server has no upstream servers it
 * subscribes to, and publishing prices is taken care of by the
 * {@link PriceStompController}, so we don't override the superclass
 * {@link #handleFrame(StompHeaders, Object)} or
 * {@link #afterConnected(org.springframework.messaging.simp.stomp.StompSession, StompHeaders)}
 * (since we will never handle frames or connect.
 * 
 * To avoid double-publishing messages to Websockets (in
 * {@link PriceStompController} and here), we also don't override the superclass
 * {@link #publishMessage(String, Object)} implementation, which is a no-op. The
 * backend servers listening to price topics get the same feed as the front end.
 */
@Service
@ConditionalOnProperty(value = "backend.messaging.transport", havingValue = "websockets", matchIfMissing = false)
public class PriceServiceWebsocketsMessaging extends WebsocketsPubSubMessagingOperations {

    public PriceServiceWebsocketsMessaging() {
        super(null, null);
    }

    @Override
    public Type getPayloadType(StompHeaders headers) {
        return null;
    }

}
