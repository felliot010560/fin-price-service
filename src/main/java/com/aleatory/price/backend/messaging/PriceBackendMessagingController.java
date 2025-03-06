package com.aleatory.price.backend.messaging;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import com.aleatory.common.domain.WireCondor;
import com.aleatory.common.domain.WireFullCondor;
import com.aleatory.common.domain.WirePrice;
import com.aleatory.common.messaging.PubSubMessagingOperations;
import com.aleatory.price.api.PriceStompController;
import com.aleatory.price.events.ExpirationToTradeEvent;
import com.aleatory.price.events.NewCondorEvent;
import com.aleatory.price.events.NewCondorPriceEvent;
import com.aleatory.price.events.NewImpliedVolatilityEvent;
import com.aleatory.price.events.NewSPXPriceEvent;
import com.aleatory.price.provider.SPXPriceProvider;

/**
 * Sends messages to the backend services that depend on prices. Depending on
 * the backend implementation, this might be Websockets or Redis pub/sub (and
 * possibly others [SNS/SQS, RabbitMQ) in the future). Note that for Websockets
 * on the backend,
 * {@link PubSubMessagingOperations#publishMessage(String, Object)} is a no-op,
 * which avoids sending the same message twice, once from the
 * {@link PriceStompController} and once from here.
 * 
 * Message-sending should be entirely event-driven.
 */
@Service
public class PriceBackendMessagingController {
    private static final Logger logger = LoggerFactory.getLogger(PriceBackendMessagingController.class);

    @Autowired
    private PubSubMessagingOperations messagingOperations;

    @Autowired
    private SPXPriceProvider spxPriceProvider;

//    @Autowired
//    private CondorProvider condorPriceProvider;

    @Autowired
    @Qualifier("pricesScheduler")
    TaskScheduler pricesScheduler;

    private AtomicLong lastPriceSent = new AtomicLong();

    @EventListener
    private void sendSPXPrice(NewSPXPriceEvent event) {
        WirePrice spxPrice = spxPriceProvider.getSPXPrice();
        logger.debug("Sending spx price to backend: {}", spxPrice);
        messagingOperations.publishMessage("/topic/prices.spx", spxPrice);
        lastPriceSent.set(System.currentTimeMillis());
    }
    
    @EventListener
    private void sendExpirationToTrade(ExpirationToTradeEvent event) {
        logger.info("Sending expiration to trade of {}", event.getExpirationToTrade());
        Map<String,String> expirationForJson = new HashMap<>();
        expirationForJson.put("expiration", event.getExpirationToTrade());
        messagingOperations.publishMessage("/topic/prices.expiration", expirationForJson);
    }

    @EventListener
    private void sendFullCondor(NewCondorEvent event) {
        WireFullCondor wireCondor = event.getFullCondor();
        if (wireCondor == null) {
            return;
        }
        logger.info("Sending full condor: {}", wireCondor.toString());
        messagingOperations.publishMessage("/topic/prices.current.condor.full", wireCondor);
    }

    @EventListener
    private void sendCondor(NewCondorEvent event) {
        WireCondor wireCondor = event.getCondor();
        if (wireCondor == null) {
            return;
        }
        logger.debug("Sending new condor to backend: {}", wireCondor);
        messagingOperations.publishMessage("/topic/prices.current.condor", wireCondor);
        lastPriceSent.set(System.currentTimeMillis());
    }

    @EventListener
    private void sendImpvol(NewImpliedVolatilityEvent event) {
        Double impVol = event.getImpliedVolatility();
        logger.debug("Sending implied vol to backend: {}", impVol);
        messagingOperations.publishMessage("/topic/prices.impvol", impVol);
        lastPriceSent.set(System.currentTimeMillis());
    }

    @EventListener
    private void sendCondorPrice(NewCondorPriceEvent event) {
        WirePrice wirePrice = event.getPrice();
        if (wirePrice == null || wirePrice.hasInvalidBidAsk()) {
            return;
        }
        logger.debug("Sending condor price of {} to backend", wirePrice);
        messagingOperations.publishMessage("/topic/prices.condor", wirePrice);
        lastPriceSent.set(System.currentTimeMillis());
    }

}
