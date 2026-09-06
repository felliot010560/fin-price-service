package com.aleatory.price.config;

import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisClientConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.scheduling.annotation.EnableAsync;

import com.aleatory.price.backend.messaging.redis.PriceServiceRedisMessaging;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@Configuration
@EnableCaching
@EnableAsync
public class CacheConfig {
    private static final Logger logger = LoggerFactory.getLogger(CacheConfig.class);
    @Value("${spring.redis.host}")
    private String REDIS_HOSTNAME;

    @Value("${spring.redis.port}")
    private int REDIS_PORT;
    
    @Value("${spring.redis.password}")
    private String REDIS_PASSWORD;
    
    @Bean
    public JedisConnectionFactory jedisConnectionFactory() {
        logger.info("Will connect to Redis server on {}:{}", REDIS_HOSTNAME, REDIS_PORT);
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(REDIS_HOSTNAME, REDIS_PORT);
        configuration.setPassword(REDIS_PASSWORD);
        JedisClientConfiguration jedisClientConfiguration = JedisClientConfiguration.builder().build();
        JedisConnectionFactory factory = new JedisConnectionFactory(configuration, jedisClientConfiguration);
        return factory;
    }

    @Bean
    @ConditionalOnProperty(value = "backend.messaging.transport", havingValue = "redis", matchIfMissing = true)
    public RedisTemplate<String, Object> redisTemplate(JedisConnectionFactory factory) {
        final RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        StringRedisSerializer keySerializer = new StringRedisSerializer();
        redisTemplate.setHashKeySerializer(keySerializer);

        ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        mapper.activateDefaultTyping(mapper.getPolymorphicTypeValidator(), ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
        GenericJackson2JsonRedisSerializer valueSerializer = new GenericJackson2JsonRedisSerializer(mapper), defaultSerializer = new GenericJackson2JsonRedisSerializer(mapper);
        redisTemplate.setValueSerializer(valueSerializer);
        redisTemplate.setDefaultSerializer(defaultSerializer);
        redisTemplate.setKeySerializer(keySerializer);

        factory.afterPropertiesSet();
        redisTemplate.setConnectionFactory(factory);

        return redisTemplate;
    }
    
    @Bean
    @ConditionalOnProperty(value = "backend.messaging.transport", havingValue = "redis", matchIfMissing = true)
    RedisMessageListenerContainer redisMessageListenerContainer(JedisConnectionFactory connectionFactory, PriceServiceRedisMessaging messagingOperations,
            @Qualifier("messagingExecutor") Executor messagingExecutor) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(messagingOperations, PatternTopic.of("/topic/trading/prices.stop.calculated"));
        container.addMessageListener(messagingOperations, PatternTopic.of("/topic/prices.spx.last-close"));
        container.setTaskExecutor(messagingExecutor);

        return container;
    }

}
