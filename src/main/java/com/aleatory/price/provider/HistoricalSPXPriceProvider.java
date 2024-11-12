package com.aleatory.price.provider;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

import org.fattails.domain.Price;
import org.fattails.domain.Stock;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import com.aleatory.common.util.TradingDays;
import com.aleatory.price.dao.SPXHistoryDao;
import com.aleatory.price.events.SPXCloseReceivedEvent;
import com.aleatory.price.events.SPXContractValidEvent;

/**
 * This class checks to verify that we haven't missed any closes for SPX (which ensures that
 * we can do expirations).
 */
@Component
public class HistoricalSPXPriceProvider {
    private static final Logger logger = LoggerFactory.getLogger(HistoricalSPXPriceProvider.class);
    
    //Check at 10PM to see if the web-scraped close has changed--can happen, usually minutely
    private static final LocalTime CLOSE_CHANGED_CHECK_TIME = LocalTime.of(22, 0);
    
    @Autowired
    @Qualifier("pricesScheduler")
    private TaskScheduler scheduler;
    
    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;
    
    @Autowired
    private SPXHistoryDao dao;
    
    @Autowired
    private PricingAPIClient client;
    
    @Autowired
    private SPXPriceProvider spxPriceProvider;
    
    @EventListener(ApplicationReadyEvent.class)
    private void scheduleYahooCloseFetch() {
    	ZonedDateTime nextClose = TradingDays.todaySPXCloseTime().plus(1,ChronoUnit.MINUTES);
    	Instant closeInstant = nextClose.toInstant();
    	logger.info("Will get close from web at {}", nextClose);
    	scheduler.schedule(() -> {
    		connectToYahooForClose(true);
    	}, closeInstant);
    }
    
    /**
     * This method screen-scrapes a page with the SPX closing price at one minute after the SPX closing time (3PM usually,
     * 12PM on short days). We do this because our vendor (c,mon, it's IBK) doesn't send the adjusted close until long
     * after the close, and there is the possibility that we will trade when we shouldn't (i.e., the last SPX tick was
     * .999% up or down, then the close is 1.0% up or down). In V1 of this feature, we get the close from the Yahoo
     * web page.
     * 
     * If this method fails for any reason, it starts the process of getting the close from IB.
     * @param scheduleNextCheck if true, schedule a recheck tonight and tomorrow check
     */
    private void connectToYahooForClose(boolean scheduleNextCheck) {
    	Document doc = null;
    	
    	String dateStr = null;
    	Elements elements = null;
    	// Try to connect 5 times before quitting in disgrace and disgust
    	for( int i = 0; i < 5; i++ ) {
        	try {
    			doc = Jsoup.connect("https://finance.yahoo.com/quote/%5EGSPC/history/").get();
    		} catch (IOException e) {
    			logger.info("Error connecting to SPX history page; {} retry.\nError was: {}", (i + 1 < 5) ? "will" : "will not", e.getMessage());
    			wait5Seconds();
    			continue;
    		}
        	elements = doc.select("#nimbus-app > section > section > section > article > div.container > div.table-container > table > tbody > tr:nth-child(1) > td:nth-child(1)");
        	if( elements.size() == 0 ) {
    			logger.info("Error parsing SPX history page; {} retry.", (i + 1 < 5) ? "will" : "will not");
    			wait5Seconds();
    			continue;
        	}
        	dateStr = elements.get(0).text();    		
    	}
    	if( doc == null || dateStr == null || elements == null || elements.size() == 0 ) {
    		logger.info("Unable to read/parse SPX history page in 5 tries; giving it up.");
    		return;
    	}

    	Date closeDate;
    	try {
			closeDate = new SimpleDateFormat("MMM d, yyyy").parse(dateStr);
		} catch (ParseException e) {
			logger.info("Could not parse Yahoo closing date string {}", dateStr);
			return;
		}
    	LocalDate closeDateLocal = LocalDate.ofInstant(closeDate.toInstant(), ZoneId.systemDefault());
    	logger.info("Yahoo close date is {}, converted to local: {}", elements.get(0).text(), closeDateLocal);
    	elements = doc.select("#nimbus-app > section > section > section > article > div.container > div.table-container > table > tbody > tr:nth-child(1) > td:nth-child(6)");
    	String priceStr = elements.get(0).text();
    	priceStr = priceStr.replaceAll(",", "");
    	logger.info("Yahoo close is {}", priceStr);
    	Double price = Double.parseDouble(priceStr);

    	SPXCloseReceivedEvent event = new SPXCloseReceivedEvent(this, price, closeDateLocal, true);
    	applicationEventPublisher.publishEvent(event);
    	
		if (scheduleNextCheck) {
			// Now schedule the next one for 1 minute after the close on the next trading
			// day.
			ZonedDateTime nextClose = TradingDays.nextSPXCloseTime().plus(1, ChronoUnit.MINUTES);
			Instant closeInstant = nextClose.toInstant();
			logger.info("Will get next close from web at {}", nextClose);
			scheduler.schedule(() -> {
				connectToYahooForClose(true);
			}, closeInstant);

			if( LocalDate.now().equals(closeDateLocal) ) {
				ZonedDateTime closeChangedCheckTime = ZonedDateTime.of(closeDateLocal, CLOSE_CHANGED_CHECK_TIME, TradingDays.CHICAGO_ZONEID);
				logger.info("Will check for change in web-scraped close at {}", closeChangedCheckTime);
				Instant closeChangedCheckTimeInstant = closeChangedCheckTime.toInstant();
				scheduler.schedule(() -> {
					connectToYahooForClose(false);
				}, closeChangedCheckTimeInstant);
			}
		}
    }
    
    private synchronized void wait5Seconds() {
    	try {
			this.wait(5000);
		} catch (InterruptedException e) {
			logger.warn("Five second wait interrupted.");
		}
    }

    /**
     * When the SPX vendor (okay, IB) contract data is available, checks to see if
     * we have all the closing prices for the underlying (okay, SPX) and fills in
     * the gaps if there are any missing, presumably because we weren't running 
     * since the close became available. Hence we only run this check once during 
     * startup--we won't have missed any closes while we're running.
     * 
     * We also check for a new close (presumably for the current date) once an hour;
     * we skip the check if we're in trading hours, which essentially means after
     * trading has completed, since we won't get a close for today before trading 
     * starts and we're not checking during trading.
     */
    @EventListener(SPXContractValidEvent.class)
    private void checkSPXHistoryCompleteAndScheduleCloseCheck() {
        Stock spx = spxPriceProvider.getSPX();
        List<LocalDate> spxDatesWithCloses = dao.fetchAllSPXCloses();
        List<LocalDate> missingTradingDays = TradingDays.tradingDaysSince(spxDatesWithCloses.get(0));
        missingTradingDays.removeAll(spxDatesWithCloses);
        if( !missingTradingDays.isEmpty() ) {
            logger.info("Found missing trading days; requesting SPX close for: {}", missingTradingDays);
            for( LocalDate current : missingTradingDays ) {
                client.requestHistoricalSPXPrices(spx, current);
            }
        }
        
        //Do a close check right now.
        client.requestClosePrices(spx);
        
        scheduleCloseCheck();
    }
    
    private ScheduledFuture<?> closeCheckFuture = null;
    private void scheduleCloseCheck() {
        if( closeCheckFuture != null ) {
            return;
        }
        //Don't try to get close during trading hours
        closeCheckFuture = scheduler.scheduleAtFixedRate(() -> {
            logger.info("Checking for SPX close at {}", ZonedDateTime.now());
            client.requestClosePrices(spxPriceProvider.getSPX());
        }, Duration.of(1, ChronoUnit.HOURS));
    }
    
    @EventListener
    private void handleSPXClose(SPXCloseReceivedEvent event) {
        // If today is a trading day the close will be the trading day 
        // *before* today; if today is not a trading day, the close will
        // be the trading day *before* the previous trading day.
        // E.g., for Wednesday, close will be for Tuesday; if Saturday
        // or Sunday, close will be Thursday, so change will be Friday
        // last - Thursday close.
        // We consider it a trading day if it's after the pre-start (one hour
        // before trading begins, or 7:30AM CT). So up until 7:30 we get the 
        // day before last's close; from 7:30 on we get the last day's close.
        // We throw away a close that does not fit this logic.
        boolean todayIsTradingDayAfterPreStart = TradingDays.isTradingDayAfterPreStart();
        LocalDate lastTradingDay = TradingDays.lastTradingDay();
        LocalDate tradingDayBeforeLast = TradingDays.tradingDayBefore(lastTradingDay);
        if ( todayIsTradingDayAfterPreStart && !event.getForDate().equals(lastTradingDay) ) {
            return;
        } else if( !todayIsTradingDayAfterPreStart && !event.getForDate().equals(tradingDayBeforeLast) ) {
            return;
        }
        
        handleClose(event.getForDate(), event.getPrice());
    }
    
    private void handleClose(LocalDate forDate, Double price) {
        
        Price spxPrice = spxPriceProvider.getRawSPXPrice();
        
        logger.info( "Setting close (date of {}) to {}.", forDate, price );
        
        spxPrice.setAdjustedClose(price);
        spxPrice.setCloseDate(forDate);
        if( spxPrice.getLast() != 0 && spxPrice.getAdjustedClose() != 0 ) {
            double change = spxPrice.getLast() - spxPrice.getAdjustedClose();
            spxPrice.setChange(change);
            if (spxPrice.getLast() != 0.0) {
                spxPrice.setChangePercent((change / spxPrice.getAdjustedClose()) * 100.0);
            }        	
        }
    }
}
