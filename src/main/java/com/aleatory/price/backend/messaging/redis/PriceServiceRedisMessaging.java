package com.aleatory.price.backend.messaging.redis;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.aleatory.common.messaging.impl.redis.RedisPubSubMessagingOperations;

/**
 * Mostly just an empty subclass, here so we have a local Redis pub-sub
 * implementation.
 */
@Service
@ConditionalOnProperty(value = "backend.messaging.transport", havingValue = "redis", matchIfMissing = true)
public class PriceServiceRedisMessaging extends RedisPubSubMessagingOperations {

}
