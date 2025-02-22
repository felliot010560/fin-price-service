package com.aleatory.price.provider;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

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

import com.aleatory.common.events.BackendConnectionStartedEvent;
import com.aleatory.common.events.ContractInfoAvailableEvent;
import com.aleatory.common.events.TickReceivedEvent;
import com.aleatory.common.events.TickReceivedEvent.PriceType;
import com.aleatory.price.events.AllExpirationsAndStrikesReceivedEvent;
import com.aleatory.price.events.ExpirationToTradeEvent;
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

    private Map<Integer, Option> tickers;

    private ZonedDateTime lastInitializationTimestamp;
    
    private static class OptionGroup {
        LocalDateTime created;
        List<Option> options;
        
        OptionGroup(LocalDateTime created, List<Option> options) {
            this.created = created;
            this.options = options;
        }
    }
    private Map<Integer, OptionGroup> pendingGroups;
    private Map<Option, Integer> optionToGroupId;
    
    public OptionChainPriceProvider() {
        tickers = Collections.synchronizedMap(new HashMap<>());
        pendingGroups = Collections.synchronizedMap(new HashMap<>());
        optionToGroupId = Collections.synchronizedMap(new HashMap<>());
    }
    
    @EventListener(BackendConnectionStartedEvent.class)
    private void setExpiration() {
        LocalDate nextWeek = LocalDate.now().plus(7, ChronoUnit.DAYS);

        expDateForOption = Date.from(nextWeek.atStartOfDay(ZoneId.of("America/Chicago")).toInstant());
        String expirationDateString = DateTimeFormatter.ofPattern("MMM-dd-yy").format(nextWeek);
        ExpirationToTradeEvent event = new ExpirationToTradeEvent(this, expirationDateString);
        applicationEventPublisher.publishEvent(event);
        logger.info("Expiration to trade is {}", expDateForOption.toString());        
    }

    private void requestOneTimeQuote(Option option) {
        int currTickerId = client.requestMarketData(0, option, true);
        tickers.put(currTickerId, option);

        logger.debug("Requesting option snapshot on ticker ID {}, option {}", currTickerId, option);
    }

    public synchronized void requestOptionSnapshotGroup(int groupId, List<Option> options) {
        OptionGroup optionGroup = new OptionGroup(LocalDateTime.now(), options);
        pendingGroups.put(groupId, optionGroup);
        logger.info("Requesting {} options for group Id {}.", optionGroup.options.size(), groupId);
        optionGroup.options.forEach(option -> {
            option.getPrice().setBid(0.0);
            option.getPrice().setAsk(0.0);
            optionToGroupId.put(option, groupId);
            requestOneTimeQuote(option);
        });
    }

    public void removeOptionSnapshotGroup(int groupId) {
        OptionGroup optionGroup = pendingGroups.remove(groupId);
        List<Option> group = optionGroup.options;
        if( group == null ) {
            logger.info("Attempt to remove nonexistent option group {}", groupId);
            return;
        }
        logger.info("Removed option group/with options {}/{}", groupId, group);
    }

    @EventListener({ SPXContractValidEvent.class })
    private void requestOptionChain(ApplicationEvent event) {
        logger.info("Requesting strikes and expirations due to event {}.", event);

        logger.info("Initializing/reinitializing--last initialization was at {}", lastInitializationTimestamp == null ? "NEVER" : lastInitializationTimestamp);
        lastInitializationTimestamp = ZonedDateTime.now();
        client.requestStrikesAndExpirations(spxPriceProvider.getSPX());
    }

    /**
     * Handles the event fired when we receive <i>some</i> expirations and
     * strikes--not necessarily all.
     * 
     * @param event {@link ReceivedExpirationsAndStrikeEvent} that holds the strikes
     *              and expirations received.
     */
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

    /**
     * Handles the event fired when we're done getting all expirations and strikes.
     * @param event {@link AllExpirationsAndStrikesReceivedEvent} that signals we've got all the expirations
     */
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
    

    private void getOptionInformation() {
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
    
    private String optionChainKey(Option forOption) {
        return forOption.getSymbol();
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
        logger.info("Requested all option chain information for {}", expDateForOption);
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


    /**
     * This method handles option ticks, <i>only</i> for imp-vol calculations. An option must
     * have bid and ask before it can do the implied vol calc; if it does, we request the
     * imp-vol calc for the option.
     * @param event
     */
    @EventListener
    private void handleTick(TickReceivedEvent event) {
        // Ignore weird -1 prices.
        if (event.getPrice() < 0.0) {
            logger.debug("Got weird -1 price for ticker {}, {} snapshot ticker, {}", event.getTickerId(), tickers.containsKey(event.getTickerId()) ? "is" : "is not", event.getPriceType());
            return;
        }
        int ticker = event.getTickerId();
        Option option = tickers.get(ticker);
        logger.debug("Got tick for ticker {}, price: {} {}", ticker, event.getPriceType(), event.getPrice());
        if (option == null || (event.getPriceType() != PriceType.BID && event.getPriceType() != PriceType.ASK)) {
            return;
        }
        logger.debug("Processing tick for ticker {}, price: {} {}", ticker, event.getPriceType(), event.getPrice());
        OptionPrice price = (OptionPrice) option.getPrice();
        event.setPriceField(price);
        
        //Both bid and ask need to be set.
        if( price.getBid() == 0.0 || price.getAsk() == 0.0 ) {
            logger.debug("Not calculating imp vol, bid/ask: {}/{}", price.getBid(), price.getAsk());
            return;
        }

        double priceForCalc = price.getLatestPrice();
        logger.debug("On tick for {}, calculating vol for option {} with price of {}, midpoint {}, last {}, ticker id of {}", event.getPriceType(), priceForCalc, price.getMidpoint(), price.getLast(), ticker);
        if (priceForCalc == 0.0 || Double.isNaN(priceForCalc)) {
            logger.debug("No valid price for calc");
            return;
        }
        int impVolTicker = client.startImpliedVolCalculation(option, priceForCalc, spxPriceProvider.getSPXLast());
        logger.debug("Imp vol ticker/option: {}/{}", impVolTicker, option);
        tickers.put(impVolTicker, option);
    }

    /**
     * Receives the result of the imp-vol calc for a single option. If all the options in the group
     * have returned imp vol, we remove the group and fire an {@link OptionGroupImpVolComplete} 
     * event, which the {@link ImpliedVolatilityProvider} handles.
     * 
     * We also do a check for old (> 10 minutes) groups and delete them.
     * @param event {@link OptionImpVolAvailableEvent} fired by low-level client
     */
    @EventListener
    private synchronized void handleOptionImpVol(OptionImpVolAvailableEvent event) {
        int ticker = event.getTickerId();// > IMP_VOL_FLAG ? event.getTickerId() - IMP_VOL_FLAG : event.getTickerId();
        Option option = tickers.remove(ticker);
        if (option == null) {
            return;
        }
        logger.debug("Received implied vol of {} for ticker {}, option {}", event.getImpVol(), event.getTickerId(), option);
        option.getPrice().setImpliedVolatility(event.getImpVol());

        List<Option> group;
        Integer groupId;

        groupId = optionToGroupId.get(option);
        if (groupId == null) { // Not part of a group
            logger.warn("Got implied vol for no-group ticker/option {}--{}", ticker, option);
            logger.warn("Dumping optionToGroupId:\n{}", optionToGroupId);
            logger.warn("Dumping pendingGroups:\n{}", pendingGroups);
            return;
        }
        logger.debug("Past null group check.");
        OptionGroup optionGroup = pendingGroups.get(groupId);
        group = optionGroup.options;
        group.remove(option);
        optionToGroupId.remove(option);
        logger.debug("Removed option {} from group {}, {} left in group", option, groupId, optionGroup.options.size());
        if (group.size() == 0) {
            removeOptionSnapshotGroup(groupId);
            logger.info("Found complete option group {}", groupId);
            applicationEventPublisher.publishEvent(new OptionGroupImpVolComplete(this, groupId));
        }

        checkOldIncompleteOptionGroups();
    }
    
    /**
     * Check for old option groups (> 10 minutes) and delete them.
     */
    private void checkOldIncompleteOptionGroups() {
        final LocalDateTime tenMinutesAgo = LocalDateTime.now().minus(10, ChronoUnit.MINUTES);
        List<Integer> oldGroupIds = pendingGroups.keySet().stream().filter(currGroupId -> pendingGroups.get(currGroupId).created.isBefore(tenMinutesAgo)).toList();
        for( Integer oldGroupId : oldGroupIds ) {
            logger.info("Removing old group ID {}", oldGroupId);
            removeOptionSnapshotGroup(oldGroupId);
        }
    }
    
    private Map<Integer, Option> missingOptionContractInfoRequests = new HashMap<>();
    
    /**
     * We do this if an option is missing contract info after the initial contract
     * info fetch--it might have started trading later in the day. 
     * @param option The option that has no contract info and is therefore not in the chain.
     */
    public void requestMissingOptionContractInfo(Option option) {
        int reqId = client.requestPriceVendorSpecificInformation(option);
        logger.info("Requested missing contract info for option {}/request id {}.", option, reqId);
        missingOptionContractInfoRequests.put(reqId, option);
    }
    
    /**
     * If we have a request for missing contract info that matches the event, we 
     * put the option into the chain. The contract info has already been set by
     * the low-level handler that fired the {@link ContractInfoAvailableEvent}
     * @param event
     */
    @EventListener
    private void handleMissingOptionContractInfo(ContractInfoAvailableEvent event) {
        Option option = missingOptionContractInfoRequests.remove(event.getReqId());
        //This one is not a missing option request.
        if( option == null ) {
            return;
        }
        if( event.getContractDetails() != null ) {
            logger.info("Got missing contract info for request id {}/{}.", event.getReqId(), option);
            optionChain.put(optionChainKey(option), option);
        } else {
            logger.info("Could not get missing contract info for request id {}/{}.", event.getReqId(), option);
        }
    }

    public Map<String, Option> getOptionChain() {
        return optionChain;
    }

}
