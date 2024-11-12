package com.aleatory.price.provider;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.annotation.PostConstruct;

import org.fattails.domain.Option;
import org.fattails.domain.OptionPrice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import com.aleatory.common.events.ContractInfoAvailableEvent;
import com.aleatory.common.events.ReinitializeEvent;
import com.aleatory.common.events.TickReceivedEvent;
import com.aleatory.common.events.TickReceivedEvent.PriceType;
import com.aleatory.price.events.AllExpirationsAndStrikesReceivedEvent;
import com.aleatory.price.events.OptionChainCompleteEvent;
import com.aleatory.price.events.OptionGroupImpVolComplete;
import com.aleatory.price.events.OptionImpVolAvailableEvent;
import com.aleatory.price.events.ReceivedExpirationsAndStrikeEvent;
import com.aleatory.price.events.SPXContractValidEvent;

@Component
public class OptionChainPriceProvider {
    private static final Logger logger = LoggerFactory.getLogger(OptionChainPriceProvider.class);
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
    
	@Autowired
	@Qualifier("pricesScheduler")
	TaskScheduler pricesScheduler;

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    @Autowired
    private SPXPriceProvider spxPriceProvider;

    @Autowired
    private PricingAPIClient client;

    private Set<LocalDate> expirations = new TreeSet<>();
    private Set<Double> strikes = new TreeSet<>();

    private Date expDateForOption;

    private Map<String, Option> optionChain;

    private Map<Integer, Option> tickers = new HashMap<>();
    
    private ZonedDateTime lastInitializationTimestamp;
    
    @PostConstruct
    private void scheduleReinitialization() {
    	pricesScheduler.scheduleWithFixedDelay( () -> {
    		applicationEventPublisher.publishEvent(new ReinitializeEvent(this));
    	}, ZonedDateTime.now().plus(1, ChronoUnit.HOURS).toInstant(), Duration.of(1, ChronoUnit.HOURS) );
    }


    public void requestQuoteOrSubscription(Option option) {
        int currTickerId = client.requestMarketData(0, option, true);
        tickers.put(currTickerId, option);

        logger.debug("Requesting option snapshot on ticker ID {}", currTickerId);
    }

    private Map<Integer, List<Option>> pendingGroups = new HashMap<>();
    private Map<Option, Integer> optionToGroupId = new HashMap<>();

    public void requestOptionSnapshotGroup(int groupId, List<Option> optionGroup) {
        pendingGroups.put(groupId, optionGroup);
        logger.debug("Requesting {} options for group Id {}.", optionGroup.size(), groupId);
        optionGroup.forEach(option -> {
            optionToGroupId.put(option, groupId);
            requestQuoteOrSubscription(option);
        });
    }

    public void removeOptionSnapshotGroup(int groupId) {
        List<Option> group = pendingGroups.remove(groupId);
        group.forEach(option -> optionToGroupId.remove(option));
    }

    @EventListener({SPXContractValidEvent.class, ReinitializeEvent.class})
    private void requestOptionChain(ApplicationEvent event) {
        logger.info("Requesting strikes and expirations due to event {}.", event);
        //Don't re-request if it's the same day as the last initialization
        if( event instanceof ReinitializeEvent && lastInitializationTimestamp != null && ZonedDateTime.now().getDayOfYear() == lastInitializationTimestamp.getDayOfYear() ) {
        	logger.info("Not reinitializing--last initialization was at {}", lastInitializationTimestamp);
        	return;
        }
        logger.info("Initializing/reinitializing--last initialization was at {}", lastInitializationTimestamp == null ? "NEVER" : lastInitializationTimestamp);
        lastInitializationTimestamp = ZonedDateTime.now();
        client.requestStrikesAndExpirations(spxPriceProvider.getSPX());
    }

    @EventListener
    private void onReceivedExpirationsAndStrikes(ReceivedExpirationsAndStrikeEvent event) {
        Set<String> expirations = event.getExpirations();
        Set<Double> strikes = event.getStrikes();
        logger.info("Options chain information returned.");
        logger.info("Expirations: {}", expirations);
        List<LocalDate> expirationDates = expirations.stream().map(exp -> LocalDate.parse(exp, formatter)).toList();
        logger.info("Strikes: {}", strikes);
        this.expirations.clear();
        this.expirations.addAll(expirationDates);
        this.strikes.clear();
        this.strikes.addAll(strikes);
    }

    @EventListener
    private void handleTick(TickReceivedEvent event) {
        // Ignore weird -1 prices.
        if (event.getPrice() < 0.0) {
            logger.debug("Got weird -1 price for ticker {}", event.getTickerId());
            return;
        }
        int ticker = event.getTickerId();
        Option option = tickers.get(ticker);
        logger.debug("Got tick for ticker {}, price: {} {}", ticker, event.getPriceType(), event.getPrice());
        if (option == null || (event.getPriceType() != PriceType.LAST && event.getPriceType() != PriceType.BID && event.getPriceType() != PriceType.ASK)) {
            return;
        }
        logger.debug("Processing tick for ticker {}, price: {} {}", ticker, event.getPriceType(), event.getPrice());
        OptionPrice price = (OptionPrice) option.getPrice();
        event.setPriceField(price);

        double priceForCalc = price.getLatestPrice();
        logger.debug("Calculating vol for option {} with price of {}, midpoint {}, last {}, ticker id of {}", option, priceForCalc, price.getMidpoint(), price.getLast(), ticker);
        if (priceForCalc == 0.0 || Double.isNaN(priceForCalc)) {
            logger.debug("No valid price for calc");
            return;
        }
        int impVolTicker = client.startImpliedVolCalculation(option, priceForCalc, spxPriceProvider.getSPXLast());
        tickers.put(impVolTicker, option);
    }

    @EventListener
    private void handleOptionImpVol(OptionImpVolAvailableEvent event) {
        int ticker = event.getTickerId();// > IMP_VOL_FLAG ? event.getTickerId() - IMP_VOL_FLAG : event.getTickerId();
        Option option = tickers.get(ticker);
        if (option == null) {
            return;
        }
        logger.debug("Received implied vol of {} for ticker {}, option {}", event.getImpVol(), event.getTickerId(), option);
        option.getPrice().setImpliedVolatility(event.getImpVol());

        Integer groupId = optionToGroupId.get(option);
        if (groupId == null) { // Not part of a group
            logger.debug("Got implied vol for no-group option {}", option);
            return;
        }
        logger.debug("Past null group check.");
        List<Option> group = pendingGroups.get(groupId);
        List<Option> noImpVolOptions = group.stream().filter(opt -> opt.getPrice().getImpliedVolatility() == 0.0).toList();
        logger.debug("Found {} no-imp-vol options in group {}", noImpVolOptions.size(), groupId);
        if (noImpVolOptions.isEmpty()) {
            logger.debug("Found complete option group {}", groupId);
            applicationEventPublisher.publishEvent(new OptionGroupImpVolComplete(this, groupId));
        }
    }

    private String optionChainKey(Option forOption) {
        return forOption.getSymbol();
    }

    private void getOptionInformation() {
        LocalDate nextWeek = LocalDate.now().plus(7, ChronoUnit.DAYS);
        
        expDateForOption = Date.from(nextWeek.atStartOfDay(ZoneId.of("America/Chicago")).toInstant());
        logger.info("Expiration to trade is {}", expDateForOption.toString());
        // Create all the optionChain and request contract details
        for (Double strike : strikes) {
            Option put = client.getEmptyVendorSpecificOption(), call = client.getEmptyVendorSpecificOption();
            put.setPutOrCall('P');
            put.setExpirationDate(expDateForOption);
            put.setStrike(strike);
            put.setUnderlying(spxPriceProvider.getSPX());
            optionChain.put(optionChainKey(put), put);
            call.setPutOrCall('C');
            call.setExpirationDate(expDateForOption);
            call.setStrike(strike);
            call.setUnderlying(spxPriceProvider.getSPX());
            optionChain.put(optionChainKey(call), call);
        }
        logger.info("Finished building empty option chain.");
    }

    @EventListener
    private void getContractDetailsForOptions(AllExpirationsAndStrikesReceivedEvent event) {
        logger.info("Finished getting option chains.");
        logger.info("Got all expirations (sorted): \n{}", expirations);
        logger.info("Got all strikes: {}", strikes);

        optionChain = new TreeMap<>();
        getOptionInformation();

        logger.info("Got {} possible options (some do not trade).", this.optionChain.size());
        requestContractDetailsForOptionChain();
    }

    Map<Integer, Option> pendingContractRequestIds = new HashMap<>();
    private int expirationStrikesNotFound = 0;
    
    // Get contract data for each option in the chain
    private void requestContractDetailsForOptionChain() {
        pendingContractRequestIds.clear();
        expirationStrikesNotFound = 0;
        for (String key : optionChain.keySet()) {
            Option option = optionChain.get(key);
            int reqId = client.requestPriceVendorSpecificInformation(option);
            pendingContractRequestIds.put(reqId, option);
        }
        logger.debug("Requested all option chain information for {}", expDateForOption);
    }

    @EventListener
    private void receivedOptionContractDetails(ContractInfoAvailableEvent event) {
        Option option = pendingContractRequestIds.remove(event.getReqId());
        // Probably SPX or condor contract details.
        if (option == null) {
            return;
        }
        // Not a trading/valid expiration/strike pair
        if (event.getContractDetails() == null) {
            expirationStrikesNotFound++;
        }
        if (pendingContractRequestIds.isEmpty()) {
            optionChainComplete();
            logger.info("Done with option chain, {} expiration strikes not found.", expirationStrikesNotFound);
            applicationEventPublisher.publishEvent(new OptionChainCompleteEvent(this, optionChain, option.getExpirationDate()));
        }
    }

    private void optionChainComplete() {
        // Find the keys for the options that aren't known to IB
        List<String> unknowns = optionChain.keySet().stream().filter(key -> optionChain.get(key).getVendorContractInformation() == null).toList();
        // Then delete all of them
        optionChain.keySet().removeAll(unknowns);
    }

    public Map<String, Option> getOptionChain() {
        return optionChain;
    }
    
    

}
