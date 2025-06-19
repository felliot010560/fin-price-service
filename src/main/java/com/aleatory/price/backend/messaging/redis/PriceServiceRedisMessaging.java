package com.aleatory.price.backend.messaging.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.aleatory.common.events.StopCalculatedPricesEvent;
import com.aleatory.common.messaging.impl.redis.RedisPubSubMessagingOperations;


@Service
@ConditionalOnProperty(value = "backend.messaging.transport", havingValue = "redis", matchIfMissing = true)
public class PriceServiceRedisMessaging extends RedisPubSubMessagingOperations {
    private static final Logger logger = LoggerFactory.getLogger(PriceServiceRedisMessaging.class);

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    public PriceServiceRedisMessaging() {
        handlers.put("/topic/prices.stop.calculated", (payload) -> fireStopCalculated(payload));
    }

    private void fireStopCalculated(Object payload) {
        logger.info("Got stop calculated prices: {}", payload);
        applicationEventPublisher.publishEvent(new StopCalculatedPricesEvent(this, (Boolean)payload));
    }
}
