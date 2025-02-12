package com.aleatory.price.events;

import org.springframework.context.ApplicationEvent;

public class ExpirationToTradeEvent extends ApplicationEvent {
    private static final long serialVersionUID = 1L;
    
    private String expirationToTrade;

    public ExpirationToTradeEvent(Object source, String expirationToTrade) {
        super(source);
        this.expirationToTrade = expirationToTrade;
    }

    public String getExpirationToTrade() {
        return expirationToTrade;
    }


}
