package com.aleatory.price.api;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.core.MessageSendingOperations;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import com.aleatory.common.domain.WireCondor;
import com.aleatory.common.domain.WireFullCondor;
import com.aleatory.common.domain.WirePrice;
import com.aleatory.price.events.NewCondorEvent;
import com.aleatory.price.events.NewCondorPriceEvent;
import com.aleatory.price.events.NewImpliedVolatilityEvent;
import com.aleatory.price.events.NewSPXPriceEvent;
import com.aleatory.price.provider.CondorProvider;
import com.aleatory.price.provider.SPXPriceProvider;

/**
 * This is the controller that sends data to the front end--and only to the front end. Communicating with 
 * other backend services (like the trading service) is done via the classes in {@link com.aleatory.price.backend.messaging}.
 * 
 * When using websockets as the backend transport, care must be taken not to send messages twice, since the same
 * messsaging system (websockets) will be used for both the frontend and backend. When using other backend transport
 * (currently only Redis), you must send to both the frontend and backend independently; this will generally entail
 * listening for the same events as in this controller and sending to the correct topics to ensure that the other
 * backend services receive those messages on the correct transport.
 */
@Component
@ConditionalOnProperty(value = "frontend.direct.websockets", matchIfMissing = false)
public class PriceStompController {
    private static final Logger logger = LoggerFactory.getLogger(PriceStompController.class);
    
    @Autowired
    private MessageSendingOperations<String> messagingTemplate;
    
    @Autowired
    private SPXPriceProvider spxPriceProvider;
    
    @Autowired
    private CondorProvider condorPriceProvider;
    
    @EventListener
    private void handleSubscribeEvent(SessionSubscribeEvent event) {
	Map<?,?> headers = (Map<?,?>)event.getMessage().getHeaders().get("nativeHeaders");
	String destination = headers.get("destination").toString();
	destination = destination.substring(1, destination.length()-1); //Strip off []s
	logger.info("Got session subscribe event for topic {}", destination );
	
	switch (destination) {
	case ("/topic/prices.spx"):
	    sendSPXPrice(null);
	    break;
	case ("/topic/prices.current.condor"):
	    sendCondor(null);
	    break;
	case ("/topic/prices.current.condor.full"):
	    sendFullCondor(null);
	    break;
	case ("/topic/prices.impvol"):
	    sendImpvol(null);
	    break;
	case ("/topic/prices.condor"):
	    sendCondorPrice(null);
	    break;
	}
    }
    
    @EventListener
    private void sendSPXPrice(NewSPXPriceEvent event) {
	WirePrice spxPrice = spxPriceProvider.getSPXPrice();
	logger.debug("Sending spx price: {}", spxPrice);
	messagingTemplate.convertAndSend("/topic/prices.spx", spxPrice);
    }
    
    @EventListener
    private void sendCondor(NewCondorEvent event) {
	WireCondor wireCondor = condorPriceProvider.getCurrentCondor();
	if( wireCondor == null ) {
	    return;
	}
	messagingTemplate.convertAndSend("/topic/prices.current.condor", wireCondor);
    }
    
    @EventListener
    private void sendFullCondor(NewCondorEvent event) {
	WireFullCondor wireCondor = condorPriceProvider.getCurrentFullCondor();
	if( wireCondor == null ) {
	    return;
	}
	messagingTemplate.convertAndSend("/topic/prices.current.condor.full", wireCondor);
    }
    
    @EventListener
    private void sendImpvol(NewImpliedVolatilityEvent event) {
        messagingTemplate.convertAndSend("/topic/prices.impvol", event.getImpliedVolatility());
    }
    
    @EventListener
    private void sendCondorPrice(NewCondorPriceEvent event) {
	WirePrice wirePrice = condorPriceProvider.getCondorWirePrice();
	if( wirePrice == null ) {
	    return;
	}
	logger.info("Sending condor price of {}", wirePrice);
	messagingTemplate.convertAndSend("/topic/prices.condor", wirePrice);
    }

}
