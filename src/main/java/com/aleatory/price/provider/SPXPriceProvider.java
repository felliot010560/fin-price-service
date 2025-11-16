package com.aleatory.price.provider;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

import javax.annotation.PostConstruct;

import org.fattails.domain.Price;
import org.fattails.domain.Stock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import com.aleatory.common.domain.WirePrice;
import com.aleatory.common.events.ConnectionUsableEvent;
import com.aleatory.common.events.ContractInfoAvailableEvent;
import com.aleatory.common.events.TickReceivedEvent;
import com.aleatory.common.events.TickReceivedEvent.PriceType;
import com.aleatory.common.util.TradingDays;
import com.aleatory.price.events.NewSPXPriceEvent;
import com.aleatory.price.events.SPXContractValidEvent;

@Component
public class SPXPriceProvider {
    private static final Logger logger = LoggerFactory.getLogger(SPXPriceProvider.class);
    private static Logger spxLogger = LoggerFactory.getLogger("SPXLOGGER");
    
    private static final String REDIS_KEY = "CONDORS:LAST.MESSAGES";
    //How often (in seconds) to send last SPX price during trading.
    private static final int SEND_INTERVAL_DURING_TRADING = 5;

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    @Autowired
    private PricingAPIClient client;
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    @Autowired
    @Qualifier("pricesScheduler")
    private TaskScheduler scheduler;

    private Stock spx;

    private Price spxPrice;
    private int spxTickerId;

    int spxContractDetailsReqId;

    @PostConstruct
    private void setSPXAndSPXPrice() {
        spx = client.getEmptyVendorSpecificStock();
        spx.setName(PricingAPIClient.SPX_NAME);
        spx.setSymbol(PricingAPIClient.SPX_SYMBOL);
        spx.setIndex(true);

        spxPrice = new Price();
        spxPrice.setSecurity(spx);
        
        //Get SPX last price and change from Redis cache
        WirePrice wireSPXPrice = (WirePrice)redisTemplate.opsForHash().get(REDIS_KEY, "/topic/prices.spx");
        if( wireSPXPrice != null ) {
            wireSPXPrice.populatePrice(spxPrice);
        }
        
        
        scheduleSPXSend();
    }
    
    private ScheduledFuture<?> sPXSendFuture;
    
    //Schedule sending the SPX price every 5 seconds during the next trading time (so trading service has it)
    private void scheduleSPXSend() {
        ZonedDateTime nextTradingStart = TradingDays.getTodaysOrNextTradingStartTime();
        //15 minutes would be fine, but there'd be a chance of recursively scheduling on the same day.
        ZonedDateTime nextTradingEnd = nextTradingStart.plus(16, ChronoUnit.MINUTES);
        logger.info("Scheduling SPX sending every 5 seconds from {} to {}", nextTradingStart, nextTradingEnd);
        sPXSendFuture = scheduler.scheduleAtFixedRate( () -> {
            logger.info("Sending last SPX price during trading.");
            applicationEventPublisher.publishEvent(new NewSPXPriceEvent(this, PriceType.LAST, spxPrice.getLast()));
            if( ZonedDateTime.now().isAfter(nextTradingEnd)) {
                sPXSendFuture.cancel(false);
                scheduleSPXSend();
            }
        }, nextTradingStart.toInstant(), Duration.of(SEND_INTERVAL_DURING_TRADING, ChronoUnit.SECONDS) );
    }

    @EventListener(ConnectionUsableEvent.class)
    private void getSPXInformation() {
        spxContractDetailsReqId = client.requestPriceVendorSpecificInformation(spx);
    }

    public WirePrice getSPXPrice() {
        if (spxPrice.getLast() == 0.0) {
            if (client != null) {
                client.requestMarketData(-1, spx, true);
            }
            return new WirePrice(0.0, 0.0, 0.0);
        }
        return new WirePrice(spxPrice.getLast(), spxPrice.getChange(), spxPrice.getChangePercent());
    }

    public Double getSPXLast() {
        return spxPrice.getLast();
    }

    public Double getSPXChange() {
        return spxPrice.getChange();
    }

    public Double getSPXChangePct() {
        return spxPrice.getChangePercent();
    }

    public void setSPXImpVol(double impVol) {
        spxPrice.setImpliedVolatility(impVol);
    }

    public Double getSPXImpVol() {
        return spxPrice.getImpliedVolatility();
    }

    public Stock getSPX() {
        return spx;
    }

    @EventListener
    private void gotContractInfo(ContractInfoAvailableEvent event) {
        if (spxContractDetailsReqId != 0 && spxContractDetailsReqId == event.getReqId()) {
            subscribeToSPXQuotes();
            applicationEventPublisher.publishEvent(new SPXContractValidEvent(this));
        }
    }

    public Price getRawSPXPrice() {
        return spxPrice;
    }

    @EventListener
    private void onTick(TickReceivedEvent event) {
        if (event.getTickerId() != spxTickerId) {
            return;
        }

        // Throw away anything but last. (We don't do anything with IBKR's close--we
        // fetch that ourselves, since IBKR's is kinda hosed.)
        if (event.getPriceType() != PriceType.LAST && event.getPriceType() != PriceType.CLOSE) {
            spxLogger.debug("SPX tick: {} of {} ", event.getPriceType(), event.getPrice());
            return;
        }
        event.setPriceField(spxPrice);

        // Can happen if we get a tick before we get yesterday's close
        if (spxPrice.getChangePercent() == 100.0) {
            logger.warn("Got 100% change, last is {}, close is {}, change is {}, change pct is {}", spxPrice.getLast(), spxPrice.getAdjustedClose(), spxPrice.getChange(), spxPrice.getChangePercent());
            return;
        }

        spxLogger.info("SPX tick: {} of {} ", event.getPriceType(), event.getPrice());

        applicationEventPublisher.publishEvent(new NewSPXPriceEvent(this, event.getPriceType(), event.getPrice()));
    }

    @EventListener
    private void handleSubscribeEvent(SessionSubscribeEvent event) {
        Map<?, ?> headers = (Map<?, ?>) event.getMessage().getHeaders().get("nativeHeaders");
        String destination = headers.get("destination").toString();
        if ("[/topic/prices.spx]".equals(destination)) {
            logger.info("Got session subscribe event for topic [/topic/prices.spx]");
            applicationEventPublisher.publishEvent(new NewSPXPriceEvent(this, PriceType.LAST, spxPrice.getLast()));
        }
    }

    private void subscribeToSPXQuotes() {
        Price spxPrice = new Price();
        spxPrice.setSecurity(spx);

        logger.debug("Setting SPX ticker id to {}", spxTickerId);

        spxTickerId = client.requestMarketData(0, spx, false);
    }

}
