package com.aleatory.price.provider.ib;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.fattails.domain.Option;
import org.fattails.domain.PriceableSecurity;
import org.fattails.domain.PriceableSecurity.SecurityType;
import org.fattails.domain.Stock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.aleatory.common.domain.IBIronCondor;
import com.aleatory.common.domain.IBOption;
import com.aleatory.common.domain.IBSecurity;
import com.aleatory.common.domain.IBStock;
import com.aleatory.common.domain.IronCondor;
import com.aleatory.common.events.ConnectionUsableEvent;
import com.aleatory.common.events.ContractInfoAvailableEvent;
import com.aleatory.common.events.MarketDataSubscriptionsNeedRenewalEvent;
import com.aleatory.common.events.TickReceivedEvent;
import com.aleatory.common.provider.IdProvider;
import com.aleatory.common.provider.ib.AbstractIBListener;
import com.aleatory.common.provider.ib.IBUtils;
import com.aleatory.price.events.AllExpirationsAndStrikesReceivedEvent;
import com.aleatory.price.events.OptionImpVolAvailableEvent;
import com.aleatory.price.events.ReceivedExpirationsAndStrikeEvent;
import com.aleatory.price.provider.PricingAPIClient;
import com.ib.client.Bar;
import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.ib.client.TickAttrib;

@Service
public class InteractiveBrokersAPIClient extends AbstractIBListener implements PricingAPIClient {
    private static Logger logger = LoggerFactory.getLogger(InteractiveBrokersAPIClient.class);

    @Autowired
    private IdProvider idProvider;

    private static final Map<String, Integer> tickerIds = new HashMap<>();

    /**
     * Since the system is heavily event-driven, we don't try to connect and
     * initialize the pricing system until after the {@link ApplicationReadyEvent}
     * has been fired, meaning the event infrastructure has been started and will
     * deliver events.
     */
    @EventListener(ApplicationReadyEvent.class)
    private void startPriceConnection() {
        logger.info("Application started, connecting to price API.");
    }

    @EventListener
    private void onConnectionUsable(ConnectionUsableEvent event) {
        client.reqMarketDataType(3);
    }

    public void unsubscribeSPXQuoteData() {
        client.cancelMktData(tickerIds.get(SPX_SYMBOL));
    }

    /**
     * Called when a connection is successfully completed.
     */
    @EventListener(ConnectionUsableEvent.class)
    private void connectionUsable() {
        tickerIds.clear();
    }

    private Map<Integer, IBSecurity> pendingContractRequestIds = new HashMap<>();

    @Override
    public void contractDetails(int reqId, ContractDetails contractDetails) {
        IBSecurity security = pendingContractRequestIds.remove(reqId);
        if (security == null) {
            return;
        }
        security.setIbContractDetails(contractDetails);
        if( !security.getSymbol().equals("SPX") && !contractDetails.contract().tradingClass().equals("SPXW") ) {
            logger.warn("Got contract that is not SPXW:\n{}", contractDetails.contract());
        }
        ContractInfoAvailableEvent event = new ContractInfoAvailableEvent(this, reqId, contractDetails);
        applicationEventPublisher.publishEvent(event);
    }

    @Override
    public void securityDefinitionOptionalParameter(int reqId, String exchange, int underlyingConId, String tradingClass, String multiplier, Set<String> expirations, Set<Double> strikes) {
        logger.debug("Got expirations: {}", expirations);
        logger.debug("Got strikes: {}", strikes);
        ReceivedExpirationsAndStrikeEvent event = new ReceivedExpirationsAndStrikeEvent(this, exchange, underlyingConId, tradingClass, multiplier, expirations, strikes);
        applicationEventPublisher.publishEvent(event);
    }

    @Override
    public void securityDefinitionOptionalParameterEnd(int reqId) {
        AllExpirationsAndStrikesReceivedEvent event = new AllExpirationsAndStrikesReceivedEvent(this);
        applicationEventPublisher.publishEvent(event);
    }

    private int lowestInvalidTickerId;

    @Override
    public void error(int id, int errorCode, String errorMsg, String advancedOrderRejectJson) {
        if (id == -1) { // Is it an informational message or connectiion errore?
            logger.info("TWS reports: {} (code: {})", errorMsg, errorCode);
            // An 1101 error code indicates the connection is restored but market data
            // subscriptions
            // have been lost
            if (errorCode == 1101) {
                applicationEventPublisher.publishEvent(new MarketDataSubscriptionsNeedRenewalEvent(this));
            }
            return;
        }

        // This error happens when trying to find all valid contracts for chain and not
        // a trading/valid expiration/strike pair
        if (errorCode == 200 && "No security definition has been found for the request".equals(errorMsg)) {
            ContractInfoAvailableEvent event = new ContractInfoAvailableEvent(this, id, null);
            applicationEventPublisher.publishEvent(event);
        }
        // "No sec def found" errors are perfectly normal when fetching option chains
        // (since a bunch of
        // expiration/strike combinations don't actually exist), so suppress them.
        // (So are order canceled messages when we're trading
        if (!suppressError(errorCode, errorMsg)) {
            if (errorCode == 101 && "Max number of tickers has been reached".equals(errorMsg)) {
                if (id < lowestInvalidTickerId) {
                    lowestInvalidTickerId = id;
                }
                logger.error("Lowest invalid ticker id = {}", lowestInvalidTickerId);
            }
            logger.error("Got error from TWS API: id: {}, errorCode: {}, errorMsg: {}, advancedOrderRejectJson: {}", id, errorCode, errorMsg, advancedOrderRejectJson);
        }
    }

    private boolean suppressError(int errorCode, String errorMsg) {
        boolean suppress = (errorCode == 200 && "No security definition has been found for the request".equals(errorMsg))
                || (errorCode == 202 && (errorMsg == null || errorMsg.startsWith("Order Canceled"))) || (errorCode == 300);
        return suppress;
    }

    @Override
    public void tickPrice(int tickerId, int field, double price, TickAttrib attrib) {
        logger.debug("Got tick: tickerId {}, field {}, price {}", tickerId, field, price);
        if (field >= 66 && field <= 68) {
            logger.warn("Getting delayed ticks. Check market data subscriptions.");
        }
        TickReceivedEvent event = new TickReceivedEvent(this, tickerId, field, price);
        if (event.getPriceType() != null) {
            applicationEventPublisher.publishEvent(event);
        }
    }

    private static final int CUSTOM_OPTION_CALCULATION = 53;

    @Override
    public void tickOptionComputation(int tickerId, int field, int tickAttrib, double impliedVol, double delta, double optPrice, double pvDividend, double gamma, double vega, double theta,
            double undPrice) {
        // API sends a slew of impvols based on bid, ask, etc.--we only want the one
        // based on midpoint, our customm calc
        if (field != CUSTOM_OPTION_CALCULATION) {
            if (field == 1 || field == 2) {
                logger.debug("Got impliedVol: tickerId {}, field {}, impVol {}, optPrice {}, undPrice {}", tickerId, field, impliedVol, optPrice, undPrice);
            }
            return;
        }
        logger.debug("Got impliedVol: tickerId {}, field {}, impVol {}, optPrice {}, undPrice {}", tickerId, field, impliedVol, optPrice, undPrice);
        if (impliedVol == 0.0 || impliedVol == Double.MAX_VALUE || impliedVol == Double.POSITIVE_INFINITY || impliedVol == Double.NEGATIVE_INFINITY) {
            logger.debug("Found 0 or very high imp vol--not setting impled vol to bad value ({}).", impliedVol);
            return;
        }
        logger.debug("Received implied vol of {} for ticker ID {}", impliedVol, tickerId);
        OptionImpVolAvailableEvent event = new OptionImpVolAvailableEvent(this, tickerId, impliedVol);
        applicationEventPublisher.publishEvent(event);
    }

    @Override
    public void historicalData(int reqId, Bar bar) {
    }

    @Override
    public void cancelMarketData(int tickerId) {
        if (client == null || !client.isConnected()) {
            return;
        }
        client.cancelMktData(tickerId);
    }

    @Override
    public int requestMarketData(int oldTickerId, PriceableSecurity security, boolean isSnapshot) {
        if (client == null || !client.isConnected()) {
            return 0;
        }

        int tickerId = idProvider.currTickerIdIncrement();
        logger.debug("Requesting market data for security/ticker/snapshot: {}/{}/{}", security, tickerId, isSnapshot);

        IBSecurity ibSecurity = (IBSecurity) security;
        if (ibSecurity.getIbContractDetails() == null) {
            logger.warn("Could not request market data for {} because we don't have contract info.", ibSecurity);
            return 0;
        }
        if (security.getClass().isAssignableFrom(IronCondor.class) && !isSnapshot) {
            logger.info("Requesting market data for {}.", security);
        }
        client.reqMktData(tickerId, ibSecurity.getContract(), "", isSnapshot, false, null);
        return tickerId;
    }

    @Override
    public int requestPriceVendorSpecificInformation(PriceableSecurity security) {
        if (client == null || !client.isConnected()) {
            return 0;
        }
        IBSecurity ibSecurity = (IBSecurity) security;
        int requestId = idProvider.currRequestIdIncrement();
        Contract contract;
        if (security.getSymbol().equals(SPX_SYMBOL)) {
            contract = IBUtils.buildSPXContract();
        } else if (security.getType() == SecurityType.OPTION) {
            IBOption option = (IBOption) security;
            contract = IBUtils.buildOptionContract(option);
        } else {
            contract = ibSecurity.getContract();
        }
        pendingContractRequestIds.put(requestId, ibSecurity);
        client.reqContractDetails(requestId, contract);
        return requestId;
    }

    @Override
    public void requestClosePrices(PriceableSecurity security) {
        if (client == null || !client.isConnected()) {
            return;
        }
        logger.info("Requesting close price at {}", ZonedDateTime.now());
        IBSecurity ibSecurity = (IBSecurity) security;

        client.reqHistoricalData(idProvider.currTickerIdIncrement(), ibSecurity.getIbContractDetails().contract(), "", "3 D", "1 day", "ADJUSTED_LAST", 1, 1, false, null);
    }

    private DateTimeFormatter historicalFormatter = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Override
    public void requestHistoricalSPXPrices(Stock spx, LocalDate forDate) {
        IBStock ibSPX = (IBStock) spx;
        // The IBKR API wants the *end* date/time, append 11:59:59PM to the date string
        String dateString = forDate.plus(1, ChronoUnit.DAYS).format(historicalFormatter) + " 23:59:59 US/Central";
        client.reqHistoricalData(idProvider.currTickerIdIncrement(), ibSPX.getIbContractDetails().contract(), dateString, "2 D", "1 day", "TRADES", 1, 1, false, null);
    }

    @Override
    public void requestStrikesAndExpirations(PriceableSecurity underlying) {
        if (client == null || !client.isConnected()) {
            return;
        }
        int conid = ((IBSecurity) underlying).getContract().conid();
        String type = underlying.getType() == SecurityType.INDEX ? "IND" : "STK";
        client.reqSecDefOptParams(idProvider.currRequestIdIncrement(), PricingAPIClient.SPX_SYMBOL, "", type, conid);
    }

    @Override
    public int startImpliedVolCalculation(Option option, double priceForCalc, double underlyingPrice) {
        if (client == null || !client.isConnected()) {
            return 0;
        }
        int ticker = idProvider.currTickerIdIncrement();
        IBOption ibOption = (IBOption) option;
        client.calculateImpliedVolatility(ticker, ibOption.getIbContractDetails().contract(), priceForCalc, underlyingPrice, null);
        return ticker;
    }

    @Override
    public Option getEmptyVendorSpecificOption() {
        return new IBOption();
    }

    @Override
    public Stock getEmptyVendorSpecificStock() {
        return new IBStock();
    }

    /**
     * Builds an iron condor specifically for IB (using {@link IBOption}
     * 
     * @param longCall
     * @param shortCall
     * @param shortPut
     * @param longPut
     * @param quantity
     * @return
     */
    public IronCondor<?> buildIronCondor(Option longCall, Option shortCall, Option shortPut, Option longPut, short quantity) {
        return new IBIronCondor((IBOption) longCall, (IBOption) shortCall, (IBOption) shortPut, (IBOption) longPut, quantity);
    }

    @Override
    public void checkConnectionAlive() {
        if (client == null) {
            return;
        }
        client.reqIds(-1);
    }

}
