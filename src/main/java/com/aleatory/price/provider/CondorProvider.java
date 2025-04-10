package com.aleatory.price.provider;

import static com.aleatory.price.provider.PricingAPIClient.SPX_SYMBOL;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;

import org.fattails.domain.Option;
import org.fattails.domain.Price;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import com.aleatory.common.domain.IronCondor;
import com.aleatory.common.domain.WireCondor;
import com.aleatory.common.domain.WireFullCondor;
import com.aleatory.common.domain.WirePrice;
import com.aleatory.common.events.ConnectionClosedEvent;
import com.aleatory.common.events.ConnectionUsableEvent;
import com.aleatory.common.events.TickReceivedEvent;
import com.aleatory.common.events.TickReceivedEvent.PriceType;
import com.aleatory.common.util.TradingDays;
import com.aleatory.price.events.NewCondorEvent;
import com.aleatory.price.events.NewCondorPriceEvent;
import com.aleatory.price.events.NewImpliedVolatilityEvent;
import com.aleatory.price.events.OptionChainCompleteEvent;

@Component
public class CondorProvider {
    private static final Logger logger = LoggerFactory.getLogger(CondorProvider.class);
    private static Logger ticksLogger = LoggerFactory.getLogger("CONDORTICKSLOGGER");
    private static final double MINIMUM_VALID_BID = -10.0;
    private static final long NON_TICKING_CONDORS_INTERVAL_MINUTES = 1;

    @Autowired
    @Qualifier("pricesScheduler")
    private TaskScheduler scheduler;
    
    @Autowired
    @Qualifier("condorsExecutor")
    private Executor executor;

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    @Autowired
    private PricingAPIClient client;

    @Autowired
    private OptionChainPriceProvider optionChainProvider;

    @Autowired
    private SPXPriceProvider spxPriceProvider;

    private Date expDate;

    private double impVol;
    
    private ScheduledFuture<?> checkNonTickingCondorsTickerTask;
    
    private Map<Integer,Option> optionTickers = new HashMap<>();
    
    class CondorTicker {
        private int tickerId = 0;
        private List<Integer> legsTickers = new ArrayList<>(4);
        private IronCondor<?> condor;
        private boolean ticking = true;

        private LocalDateTime lastTick;

        CondorTicker(IronCondor<? extends Option> condor) {
            super();
            this.condor = condor;
            this.lastTick = LocalDateTime.now().minus(1, ChronoUnit.DAYS);
        }
        
        CondorTicker(int tickerId, IronCondor<? extends Option> condor) {
            super();
            this.tickerId = tickerId;
            this.condor = condor;
            this.lastTick = LocalDateTime.now().minus(1, ChronoUnit.DAYS);
        }

        // This method subscribes us to the condor and its individual option legs.
        private void subscribe() {
            tickerId = client.requestMarketData(0, condor, false);
            condor.getLegs().stream().forEach(leg -> {
                int ticker = client.requestMarketData(0, leg, false);
                legsTickers.add(ticker);
                optionTickers.put(ticker, leg);
            });
            logger.debug("Subscribed to {}, leg tickers: {}", condor, legsTickers);

        }

        private void unsubscribe() {
            //Cancel market data for the condor and the options
            client.cancelMarketData(tickerId);
            legsTickers.stream().forEach(legTicker -> {
                if (legTicker != null) {
                    client.cancelMarketData(legTicker);
                }
            });
            //Remove the leg tickers from the map of option tickers.
            optionTickers.keySet().removeAll(legsTickers);
            legsTickers.clear();
        }

    }
    
    private CondorTicker condorTicker;
    
    @EventListener(ConnectionUsableEvent.class)
    private void startNonTickingCondorsCheck() {
        checkNonTickingCondorsTickerTask = scheduler.scheduleAtFixedRate(() -> findNonTickingCondors(), 
                new Date( System.currentTimeMillis() + 60000 ).toInstant(), Duration.of(NON_TICKING_CONDORS_INTERVAL_MINUTES, ChronoUnit.MINUTES));
    }
    
    @EventListener(ConnectionClosedEvent.class)
    private void connectionClosed() {
        logger.info("Connection to trading API closed; resetting condor tickers.");
        condorTicker = null;
        if( checkNonTickingCondorsTickerTask != null ) {
            checkNonTickingCondorsTickerTask.cancel(true);
            checkNonTickingCondorsTickerTask = null;
        }
    }

    public synchronized WirePrice getCondorWirePrice() {
        if (condorTicker == null) {
            return new WirePrice(null, false);
        }
        IronCondor<? extends Option> condor = condorTicker.condor;
        Price condorPrice = condor.getPrice();

        return new WirePrice(condorPrice, !condorTicker.ticking);
    }

    public synchronized Price getCondorPrice() {
        if (condorTicker == null) {
            return null;
        }
        IronCondor<? extends Option> condor = condorTicker.condor;
        if (condor == null) {
            return null;
        }
        Price condorPrice = condor.getPrice();

        return condorPrice;
    }

    @EventListener
    private void optionChainComplete(OptionChainCompleteEvent event) {
        this.expDate = event.getExpDate();
        logger.debug("Got option chain complete, setting exp date to {}", expDate);
        if (event.getOptionChain().size() == 0) {
            logger.debug("No options in chain--sending empty condor.");
        }
        IronCondor<? extends Option> condor = new IronCondor<>();
        condor.setExpirationDate(this.expDate);
        applicationEventPublisher.publishEvent(new NewCondorEvent(this, condor));
    }

    @EventListener
    private void handleNewImpliedVolatility(NewImpliedVolatilityEvent event) {
        impVol = event.getImpliedVolatility();
        if (calculateCondorBands(impVol)) {
            logger.info("At SPX price of {} and implied vol of {}, strike band is ({}, {})", spxPriceProvider.getSPXLast(), impVol, lowBandStrike, highBandStrike);
            if (Double.isInfinite(lowBandStrike) || Double.isInfinite(highBandStrike)) {
                logger.warn("Got infinite strike band, discarding.");
                return;
            }
            subscribeToCondor();
        } else {
            // Send out the existing condor even if it doesn't change
            if( condorTicker != null ) {
                IronCondor<? extends Option> condor = condorTicker.condor;
                applicationEventPublisher.publishEvent(new NewCondorEvent(this, condor));
            }
        }
    }

    @EventListener
    synchronized void handleCondorTick(TickReceivedEvent event) {
        if (condorTicker == null || condorTicker.tickerId != event.getTickerId()) {
            return;
        }
        ticksLogger.info("{}, {}, {}", event.getTickerId(), event.getPriceType(), event.getPrice());
        // Throw out garbage prices (bid < -10.0 or >= 0.0, ask >= 0.0)
        if (event.getPrice() >= 0.0 || (event.getPriceType() == PriceType.BID && event.getPrice() < MINIMUM_VALID_BID)) {
            return;
        }
        Price condorPrice = condorTicker.condor.getPrice();
        event.setPriceField(condorPrice);

        double bid = condorPrice.getBid(), ask = condorPrice.getAsk();
        if (bid != Double.NaN || bid != 0.0 || ask != Double.NaN || ask != 0.0) {

            bid = Math.round(bid * 1000.0) / 1000.0;
            ask = Math.round(ask * 1000.0) / 1000.0;

            condorPrice.setBid(bid);
            condorPrice.setAsk(ask);
        }

        if( condorTickValid(bid, ask)) {
            publishNewCondorTick(event.getTickerId(), condorPrice, false);
            condorTicker.lastTick = LocalDateTime.now();
            condorTicker.ticking = true;
            logger.debug("Set condor price to {}/{}/{}", condorPrice, event.getPriceType(), event.getPrice());
        }
    }
    
    @EventListener
    private void handleOptionTick(TickReceivedEvent event) {
        //Don't handle if we're not handling condor ticks yet.
        if (condorTicker == null ) {
            return;
        }
        //Don't handle if it's not a leg option tick
        Option option = optionTickers.get(event.getTickerId());
        if( option == null ) {
            return;
        }
        event.setPriceField(option.getPrice());
        logger.debug("Set leg option price for {}/{}/{}", option, event.getPriceType(), event.getPrice());
        
        condorTicker.condor.calculatePriceFromLegs();
        if( condorTickValid( condorTicker.condor.getPrice().getBid(), condorTicker.condor.getPrice().getAsk() )) {
            publishNewCondorTick(event.getTickerId(), getCondorPrice(), true);
        }
    }
    
    //Don't send bullshit prices.
    private boolean condorTickValid(double bid, double ask) {
        return bid <= ask && bid < 0.0 && ask < 0.0 && bid >= -10.0 && ask >= -10.0;
    }
    
    private void publishNewCondorTick( Integer tickerId, Price condorPrice, boolean fromLegs ) {
        logger.debug("Sending condor tick (from {}) of {}/{}/{}", !fromLegs ? "condor itself" : "legs", condorPrice.getBid(), condorPrice.getAsk(), condorPrice.getMidpoint());
        applicationEventPublisher.publishEvent(new NewCondorPriceEvent(this, condorPrice, fromLegs));
        ticksLogger.debug("Sent: {}, {}, {}", tickerId, condorPrice);
    }
    
    LocalDateTime lastTickCheck;
    /**
     * Runs every N minutes (currently 1). Finds condors that haven't ticked since the last check and 
     * marks it non-ticking and reports it in the log. Presumably we'll get option ticks on the legs
     * to fill in.
     */
    private synchronized void findNonTickingCondors() {
        if( lastTickCheck != null && condorTicker != null ) {
            if( condorTicker.lastTick.isBefore(lastTickCheck) ) {
                condorTicker.ticking = false;
                logger.debug("Condor ticker not ticking.");
            }
        }
        lastTickCheck = LocalDateTime.now();
    }
    
    private double highBandStrike, lowBandStrike;

    /**
     * Returns whether or not the condor bands have changed.
     * 
     * @param impVol the current implied vol
     * @return whether or not the condor bands have change
     */
    private boolean calculateCondorBands(double impVol) {
        spxPriceProvider.setSPXImpVol(impVol);
        logger.debug("Calculated SPX 7-day implied vol of {}", impVol);
        double spxPrice = spxPriceProvider.getSPXLast();
        double lowBandStrikeNew = Math.floor((spxPrice - spxPrice * impVol) / 5) * 5;
        double highBandStrikeNew = Math.ceil((spxPrice + spxPrice * impVol) / 5) * 5;
        boolean changed = lowBandStrikeNew != lowBandStrike || highBandStrikeNew != highBandStrike;

        lowBandStrike = lowBandStrikeNew;
        highBandStrike = highBandStrikeNew;

        return changed;
    }

    // Subscribe to the calls at the high strike band and the puts at the low
    // If we are in the active trading time we no longer look for a new condor to
    // subscribe to...unless
    // the last condor was invalid (missing an option in the option chain) and then
    // we look for a good one
    private boolean subscribeToCondor() {
        // We don't calculate new condors if we're actively trading (unless we don't
        // have a condor at all yet)
        if (TradingDays.inActiveTradingTime() && condorTicker != null) {
            logger.info("Keeping old condor--in active trading hours.");
            return false;
        }

        Option shortCall = addOptionToSubscribe(highBandStrike, 'C', false);
        Option longCall = addOptionToSubscribe(highBandStrike + 10, 'C', true);
        Option shortPut = addOptionToSubscribe(lowBandStrike, 'P', false);
        Option longPut = addOptionToSubscribe(lowBandStrike - 10, 'P', true);

        if (longCall == null || shortCall == null || longPut == null || shortPut == null) {
            logger.info("Keeping old condor--invalid condor (one or more options missing from chain).");
            return false;
        }

        //Quantity for pricing is always 1--quantity is only significant for trading.
        IronCondor<? extends Option> condor = client.buildIronCondor(longCall, shortCall, shortPut, longPut, (short)1);
        condor.setUnderlying(spxPriceProvider.getSPX());

        applicationEventPublisher.publishEvent(new NewCondorEvent(this, condor));

        requestCondorMarketData(condor);

        return true;
    }

    private Option addOptionToSubscribe(double strike, char putOrCall, boolean isLong) {
        String key = Option.buildSymbol(SPX_SYMBOL, expDate, putOrCall, strike);
        Option option = optionChainProvider.getOptionChain().get(key);
        if (option == null) {
            logger.warn("Could not find condor option in chain: strike: {}, P/C: {}, L/S: {}, key: {}", strike, putOrCall, isLong ? "long" : "short", key);
            requestMissingOptionContractInfo(putOrCall, strike, isLong);
            return null;
        }
        option.setLong(isLong);

        return option;
    }
    
    //Try to get contract information for currently missing option--maybe it started 
    //trading after we got contract info?
    private void requestMissingOptionContractInfo( char putOrCall, double strike, boolean isLong ) {
        Option option = client.getEmptyVendorSpecificOption();
        option.setPutOrCall(putOrCall);
        option.setExpirationDate(expDate);
        option.setStrike(strike);
        option.setLong(isLong);
        option.setUnderlying(spxPriceProvider.getSPX());
        optionChainProvider.requestMissingOptionContractInfo(option);
    }

    /**
     * Will be called whenever we get a new implied volatility, but will only resubscribe
     * when the condor changes (i.e., when the implied vol or underlying price changes the
     * strike band.)
     */
    private synchronized void requestCondorMarketData(IronCondor<? extends Option> condor) {
        if( condorTicker != null ) {    //Do we already have a condor ticker?
            if( condor.toString().equals(condorTicker.condor.toString()) ) {
                logger.debug("Already subscribed to market data for condor {}", condor);
                ticksLogger.info("Current ticker (old): {}", condorTicker.tickerId);
                return;
            }            
        }

        if ( condor == null || condor.getLegs().size() == 0 ) {
            logger.debug("Null or invalid condor {} (no legs); not requesting market data.", condor);
            return;
        }

        //Cancel the previous condor ticker if there is one
        if( condorTicker != null ) {
            condorTicker.unsubscribe();
        }
        //Then create a new one and subscribe to it.
        condorTicker = new CondorTicker(condor);
        condorTicker.subscribe();

        logger.debug("Subscribed on ticker {} for condor {} (leg tickers: {})", condorTicker.tickerId, condor, condorTicker.legsTickers);

    }

    public synchronized WireCondor getCurrentCondor() {
        if( condorTicker == null ) {
            logger.warn("No current condor.");
        }
        IronCondor<? extends Option> condor = condorTicker.condor;
        if (condor == null) {
            logger.warn("No current condor.");
            return null;
        }
        var wireCondor = new WireCondor(condor);
        return wireCondor;
    }

    public IronCondor<?> getCondor() {
        if( condorTicker == null ) {
            return null;
        }
        return condorTicker.condor;
    }

    public synchronized WireFullCondor getCurrentFullCondor() {
        if( condorTicker == null ) {
            return null;
        }
        logger.debug("Sending full condor: current condor: {}", condorTicker.condor);
        return new WireFullCondor(condorTicker.condor);
    }

}
