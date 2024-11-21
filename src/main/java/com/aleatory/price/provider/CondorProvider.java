package com.aleatory.price.provider;

import static com.aleatory.price.provider.PricingAPIClient.SPX_SYMBOL;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicInteger;

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
    private static final double MINIMUM_VALID_BID = -10.0;

    @Autowired
    @Qualifier("pricesScheduler")
    private TaskScheduler scheduler;

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    @Autowired
    private PricingAPIClient client;

    @Autowired
    private OptionChainPriceProvider optionChainProvider;

    @Autowired
    private SPXPriceProvider spxPriceProvider;

    private short quantity = 1;

    private Date expDate;
    
    private double impVol;

    //private IronCondor<? extends Option> condor;
    private Map<String, Integer> condorToTicker = Collections.synchronizedMap( new HashMap<>() );
    private Map<Integer, IronCondor<?>> tickersToCondor = Collections.synchronizedMap( new HashMap<>() );

    private AtomicInteger condorTickerId = new AtomicInteger(-1);
    
    @EventListener(ConnectionUsableEvent.class)
    private void getSPXInformation() {
        resetCondorTickers();
    }

    public synchronized WirePrice getCondorWirePrice() {
        if (condorTickerId.get() < 0) {
            return new WirePrice(null);
        }
        IronCondor<? extends Option> condor = tickersToCondor.get(condorTickerId.get());
        if( condor == null ) {
        	return new WirePrice(null);
        }
        Price condorPrice = condor.getPrice();

        return new WirePrice(condorPrice);
    }
    
    public synchronized Price getCondorPrice() {
        if (condorTickerId.get() < 0) {
            return null;
        }
        IronCondor<? extends Option> condor = tickersToCondor.get(condorTickerId.get());
        if( condor == null ) {
        	return null;
        }
        Price condorPrice = condor.getPrice();

        return condorPrice;
    }
    
    @EventListener
    private void optionChainComplete(OptionChainCompleteEvent event) {
        this.expDate = event.getExpDate();
        logger.debug("Got option chain complete, setting exp date to {}", expDate);
        if( event.getOptionChain().size() == 0) {
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
        	//Send out the existing condor even if it doesn't change
        	IronCondor<? extends Option> condor = tickersToCondor.get(condorTickerId.get());
        	applicationEventPublisher.publishEvent(new NewCondorEvent(this, condor));
        }
    }

    @EventListener
    synchronized void handleCondorTick(TickReceivedEvent event) {
        IronCondor<? extends Option> condor = tickersToCondor.get(event.getTickerId());
        //Old tick--probably condor has been removed but ticker not yet cancelled (or it's an SPX tick).
        if( condor == null ) {
        	return;
        }
    	//Throw out garbage prices (bid < -10.0 or >= 0.0, ask >= 0.0)
    	if( event.getPrice() >= 0.0 || ( event.getPriceType() == PriceType.BID && event.getPrice() < MINIMUM_VALID_BID ) ) {
    		return;
    	}
        Price condorPrice = condor.getPrice();
        event.setPriceField(condorPrice);

        double bid = condorPrice.getBid(), ask = condorPrice.getAsk();
        if (bid != Double.NaN || bid != 0.0 || ask != Double.NaN || ask != 0.0) {

            bid = Math.round(bid * 1000.0) / 1000.0;
            ask = Math.round(ask * 1000.0) / 1000.0;

            condorPrice.setBid(bid);
            condorPrice.setAsk(ask);
        }

        // Don't send bullshit prices
        if (bid != 0.0 && ask != 0.0 && !( bid >= ask )) {
            if( condorTickerId.get() == event.getTickerId() ) {
            	applicationEventPublisher.publishEvent(new NewCondorPriceEvent(this, condorPrice));
            }
        }
    }

    double highBandStrike, lowBandStrike;

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
    // If we are in the active trading time we no longer look for a new condor to subscribe to...unless
    // the last condor was invalid (missing an option in the option chain) and then we look for a good one
    private boolean subscribeToCondor() {
    	//We don't calculate new condors if we're actively trading (unless we don't have a condor at all yet)
    	if( TradingDays.inActiveTradingTime() && condorTickerId.get() >= 0 ) {
    		logger.debug("Keeping old condor--in active trading hours.");
    		return false;
    	}
    	
        Option shortCall = addOptionToSubscribe(highBandStrike, 'C', false);
        Option longCall = addOptionToSubscribe(highBandStrike + 10, 'C', true);
        Option shortPut = addOptionToSubscribe(lowBandStrike, 'P', false);
        Option longPut = addOptionToSubscribe(lowBandStrike - 10, 'P', true);

        if (longCall == null || shortCall == null || longPut == null || shortPut == null) {
            logger.debug("Keeping old condor--invalid condor (one or more options missing from chain).");
            return false;
        }

        IronCondor<? extends Option> condor = client.buildIronCondor(longCall, shortCall, shortPut, longPut, quantity);
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
            return null;
        }
        option.setLong(isLong);

        return option;
    }
    
    private class TickerTimestamp implements Comparable<TickerTimestamp> {
    	public TickerTimestamp(int tickerId, LocalDateTime timestamp) {
			this.tickerId = tickerId;
			this.timestamp = timestamp;
		}
		int tickerId;
    	LocalDateTime timestamp;
		@Override
		public int compareTo(TickerTimestamp tt) {
			return this.timestamp.compareTo(tt.timestamp);
		}
    }
    
    private static final int MAX_NUMBER_OF_TICKERS = 20;
    
    private PriorityQueue<TickerTimestamp> tickerAges = new PriorityQueue<>();
    private Map<Integer, TickerTimestamp> tickersToTimestamps = new HashMap<>();

    /**
     * Should only be called when the condor changes (i.e, after a large-enough
     * underlying move or a large-enough ATM implied vol move.
     */
    private synchronized void requestCondorMarketData(IronCondor<? extends Option> condor) {
    	if( condorToTicker.containsKey(condor.toString()) ) {
    		logger.info("Already subscribed to market data for condor {}", condor);
    		condorTickerId.set( condorToTicker.get(condor.toString()) );
    		//Update the timestamp, remove the old timestamp, and reinsert the new one into the priority queue
    		TickerTimestamp timestamp = tickersToTimestamps.get(condorTickerId.get());
    		if( timestamp != null ) {
    			timestamp.timestamp = LocalDateTime.now();
    			tickerAges.remove(timestamp);
    			tickerAges.add(timestamp);
    		}
    		return;
    	}
        
        if( condor.getLegs().size() == 0 ) {
        	logger.debug("Invalid condor {} (no legs); not requesting market data.", condor);
        	return;
        }

        //Get a snapshot in case the condor doesn't tick
        client.requestMarketData(0, condor, true);
        //Then subscribe (and cancel the previous condor ticker)
        condorTickerId.set( client.requestMarketData(condorTickerId.get(), condor, false) );
        //Index the new ticker id.
        condorToTicker.put(condor.toString(), condorTickerId.get());
        tickersToCondor.put(condorTickerId.get(), condor);
        TickerTimestamp timestamp = new TickerTimestamp(condorTickerId.get(), LocalDateTime.now() );
        tickerAges.add(timestamp);
        tickersToTimestamps.put(condorTickerId.get(), timestamp);
        
        //Only keep the most recent tickers (no more than MAX_NUMBER_OF_TICKERS)
        if( tickersToCondor.size() > MAX_NUMBER_OF_TICKERS ) {
        	TickerTimestamp tt = tickerAges.remove();
        	client.cancelMarketData(tt.tickerId);
        	var condorToRemove = tickersToCondor.remove(tt.tickerId);
        	String condorToRemoveString = condorToRemove.toString();
        	condorToTicker.remove(condorToRemoveString);
        	logger.info("Removed ticker {} for condor {} (timestamp of {}); there are now {} tickers in the age queue, {} in tickers map, {} in condors map.", 
        			tt.tickerId, condorToRemove, tt.timestamp, tickerAges.size(), tickersToCondor.size(), condorToTicker.size());	
        }
        
        logger.info("Added ticker {} for condor {}\nThere are now {} tickers.", condorTickerId.get(), condor, tickersToCondor.size());
        
    }
    
    private void resetCondorTickers() {
    	for( int ticker : tickersToCondor.keySet() ) {
    		client.cancelMarketData(ticker);
    	}
    	tickersToCondor.clear();
    	condorToTicker.clear();
    	tickersToTimestamps.clear();
    	tickerAges.clear();
    }

    public synchronized WireCondor getCurrentCondor() {
    	IronCondor<? extends Option> condor = tickersToCondor.get(condorTickerId.get());
        if (condor == null) {
            return null;
        }
        var wireCondor = new WireCondor(condor);
        return wireCondor;
    }
    
    public IronCondor<?> getCondor() {
    	IronCondor<? extends Option> condor = tickersToCondor.get(condorTickerId.get());
        return condor;
    }

    public synchronized WireFullCondor getCurrentFullCondor() {
    	IronCondor<? extends Option> condor = tickersToCondor.get(condorTickerId.get());
        if (condor == null) {
            return null;
        }
        logger.debug("Sending full condor: current condor: {}", condor);
        return new WireFullCondor(condor);
    }

}
