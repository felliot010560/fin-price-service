package com.aleatory.price.provider;

import static com.aleatory.price.provider.PricingAPIClient.SPX_SYMBOL;
import static java.lang.Double.isNaN;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
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

import com.aleatory.common.events.ConnectionClosedEvent;
import com.aleatory.common.events.ConnectionUsableEvent;
import com.aleatory.common.events.StopCalculatedPricesEvent;
import com.aleatory.common.provider.IdProvider;
import com.aleatory.common.util.TradingDays;
import com.aleatory.price.events.NewImpliedVolatilityEvent;
import com.aleatory.price.events.OptionChainCompleteEvent;
import com.aleatory.price.events.OptionGroupImpVolComplete;

@Component
public class ImpliedVolatilityProvider {
    private static final Logger logger = LoggerFactory.getLogger(ImpliedVolatilityProvider.class);

    @Autowired
    @Qualifier("apiScheduler")
    private TaskScheduler scheduler;

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    @Autowired
    private IdProvider idProvider;

    @Autowired
    private OptionChainPriceProvider optionChainProvider;

    @Autowired
    private SPXPriceProvider spxPriceProvider;

    private Date expDate;

    private Map<String, Option> optionChain;
    private long lastImpVolCalc = 0;
    private double impVol;

    private boolean stopCalculatingImpliedVol;

    @EventListener
    private void optionChainComplete(OptionChainCompleteEvent event) {
        this.optionChain = event.getOptionChain();
        this.expDate = event.getExpDate();

        if (optionChain.size() == 0) {
            LocalDate expiration = expDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            boolean isTradingDay = TradingDays.dayIsTradingDay(expiration);
            logger.info("No options in chain. (Expiration {} {} a trading day.)", expiration, isTradingDay ? "is" : "is not");
            return;
        }
        logger.info("Got option chain complete, calculating imp vol");

        scheduleImpliedVolCalc();

        lastImpVolCalc = System.currentTimeMillis();
    }

    private ScheduledFuture<?> impliedVolCalcTask;
    private static final Duration IMPLIED_VOL_CALC_EVERY = Duration.of(10, ChronoUnit.SECONDS);

    private void scheduleImpliedVolCalc() {
        if (impliedVolCalcTask != null) {
            impliedVolCalcTask.cancel(false);
        }
        logger.info("Scheduling implied vol calcs at {} to run every {}", ZonedDateTime.now(), IMPLIED_VOL_CALC_EVERY);

        impliedVolCalcTask = scheduler.scheduleAtFixedRate(() -> {
            if ( !stopCalculatingImpliedVol && TradingDays.inTradingHours() ) {
                logger.info("Calculating implied volatility, last was {} ms ago.", lastImpVolCalc == 0 ? 0 : System.currentTimeMillis() - lastImpVolCalc);
                startImpliedVolatilityCalculation();
                lastImpVolCalc = System.currentTimeMillis();
            } else {
                logger.info("Not doing imp vol calc because {}", stopCalculatingImpliedVol ? "implied vol calc stopped" : "not in trading hours" );
            }
        }, IMPLIED_VOL_CALC_EVERY);
    }
    
    
    @EventListener(ConnectionClosedEvent.class)
    private void connectionClosed() {
        logger.info("Connection to trading API closed; resetting all condor tickers.");
        optionChain = null;
        if( impliedVolCalcTask != null ) {
            impliedVolCalcTask.cancel(true);
            impliedVolCalcTask = null;
        }
    }

    @EventListener(ConnectionUsableEvent.class)
    private void initOptionChain() {
        if( impliedVolCalcTask != null ) {
            impliedVolCalcTask.cancel(true);
        }
        optionChain = null;
    }

    @EventListener
    private void optionGroupComplete(OptionGroupImpVolComplete event) {
        // Could happen if we started fetching the option group, then got a
        // stop-calculating event, then completed the options group
        if (stopCalculatingImpliedVol) {
            logger.warn("Completed option group {} after stop-calculating event seen; ignoring.", event.getGroupId() );
            return;
        }
        logger.info("Received complete event for option group {}", event.getGroupId());
        if (event.getGroupId() == groupIdForLastCalc) {
            calculateImpVolIfAllOptionPricesPresent();
        }
    }

    private int groupIdForLastCalc;

    // request implied vol for each ATM option
    private void startImpliedVolatilityCalculation() {
        // Check for option chain not yet initialized.
        if (optionChain == null) {
            return;
        }
        List<Option> optionsToGetQuotesFor = getNOptionStrikesAboveAndNBelow(spxPriceProvider.getSPXLast(), 5);
        optionsToGetQuotesFor.stream().forEach((option) -> option.getPrice().setImpliedVolatility(0.0));
        logger.info("Imp vol calc: Requesting market data for {} options in chain", optionsToGetQuotesFor.size());

        groupIdForLastCalc = idProvider.currOptionGroupIdIncrement();
        logger.info("Imp vol calc: Requesting options for option group {}", groupIdForLastCalc);
        optionChainProvider.requestOptionSnapshotGroup(groupIdForLastCalc, optionsToGetQuotesFor);
        logger.info("Imp vol calc: Requested {} snapshot quotes for imp vol calc.", optionsToGetQuotesFor.size());
    }

    /**
     * Gets the given number of the strikes (both puts and calls) above and below
     * the underlying price
     * 
     * @param chain           The option chain to get the strikes from
     * @param underlyingPrice The underlying price to get x strikes above and x
     *                        strikes below
     * @param howMany         how many strikes above and below the underlying price
     *                        to get
     * @return A list of N options above the underlying price and N options below
     *         the underlying price
     */
    private List<Option> getNOptionStrikesAboveAndNBelow(double underlyingPrice, int howMany) {
        double firstAboveStrike = 5.0 * Math.ceil(Math.abs(underlyingPrice / 5.0));
        double firstBelowStrike = 5.0 * Math.floor(Math.abs(underlyingPrice / 5.0));
        List<Option> aboveAndBelowOptions = new ArrayList<>();
        aboveAndBelowOptions.addAll(findStrikesAboveOrBelow(firstAboveStrike, howMany, true));
        aboveAndBelowOptions.addAll(findStrikesAboveOrBelow(firstBelowStrike, howMany, false));

        Collections.sort(aboveAndBelowOptions);

        return aboveAndBelowOptions;
    }

    private List<Option> findStrikesAboveOrBelow(double startStrike, int howMany, boolean isAbove) {
        double inc = isAbove ? 5.0 : -5.0;
        List<Option> aboveOrBelowOptions = new ArrayList<>();
        int numAbove = 0;
        for (double strike = startStrike; numAbove < howMany; strike += inc) {
            String key = Option.buildSymbol(SPX_SYMBOL, expDate, 'P', strike);
            Option put = optionChain.get(key);
            key = Option.buildSymbol(SPX_SYMBOL, expDate, 'C', strike);
            Option call = optionChain.get(key);
            if (put != null) {
                aboveOrBelowOptions.add(put);
            }
            if (call != null) {
                aboveOrBelowOptions.add(call);
            }
            if (put != null || call != null) {
                numAbove++;
            }
        }
        return aboveOrBelowOptions;
    }

    private double calculateImpliedVol() {
        impVol = Double.NaN;
        List<Option> atmOptions = getNOptionStrikesAboveAndNBelow(spxPriceProvider.getSPXLast(), 5);

        logger.debug("Starting imp vol calc.");
        List<Double> atmOptionsImpVols = atmOptions.stream().map(option -> option.getPrices().get(0).getImpliedVolatility()).toList();
        logger.debug("ATM options imp vols: {}", atmOptionsImpVols);
        double sum = atmOptions.stream().reduce(0.0, (subtotal, option) -> subtotal + option.getPrices().get(0).getImpliedVolatility(), Double::sum);
        logger.debug("Sum of atm imp vols == {}", sum);
        double averageImpVol = sum / atmOptions.size();
        logger.debug("Avg. imp. vol == {}", averageImpVol);
        LocalDate localExpDate = expDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        double numDays = Math.abs(ChronoUnit.DAYS.between(localExpDate, LocalDate.now()));
        logger.debug("Num days == {}", numDays);
        impVol = (averageImpVol * Math.sqrt(numDays)) / Math.sqrt(365.0);
        logger.debug("Imp vol == {}", impVol);

        logger.info("Sending implied vol event.");
        applicationEventPublisher.publishEvent(new NewImpliedVolatilityEvent(this, impVol));

        return impVol;
    }

    // Does the imp vol calculation if we have all the option prices we need.
    private void calculateImpVolIfAllOptionPricesPresent() {
        try {
            logger.debug("Checking if we have all prices for imp vol calc");
            if (!allATMOptionsHavePrices()) {
                logger.info("Missing prices for imp vol calc--skipping");
                return;
            }
            logger.info("Calculating implied volatility (all ATM options have imp vol).");
            double impVol = calculateImpliedVol();
            // We sometimes get an Infinity value for imp-vol--discard it.
            if (Double.isInfinite(impVol)) {
                logger.warn("Got Infinity for implied-vol, discarding.");
                return;
            }
            if (impVol > 1.0) {
                logger.warn("Got very large implied volatility of {}", impVol);
            }

        } catch (RuntimeException e) {
            logger.error("Got runtime exception while subscribing to condor; subscription discarded.", e);
        }
    }

    private boolean allATMOptionsHavePrices() {
        List<Option> atmOptions = getNOptionStrikesAboveAndNBelow(spxPriceProvider.getSPXLast(), 5);
        for (Option option : atmOptions) {
            Price price = option.getPrices().get(0);
            if (isNaN(price.getImpliedVolatility())) {
                return false;
            }
        }
        return true;
    }

    public double getImpliedVolatility() {
        return impVol;
    }
    
    @EventListener
    public void stopCalculatingHandler( StopCalculatedPricesEvent event ) {
        stopCalculatingImpliedVol = event.isStop();
    }
}
