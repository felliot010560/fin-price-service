package com.aleatory.price.events;

import java.util.Set;

import org.springframework.context.ApplicationEvent;

public class ReceivedExpirationsAndStrikeEvent extends ApplicationEvent {
    private static final long serialVersionUID = 1L;
    String exchange;
    int underlyingConId;
    String tradingClass;
    String multiplier;
    Set<String> expirations;
    Set<Double> strikes;
    public ReceivedExpirationsAndStrikeEvent(Object source, String exchange, int underlyingConId, String tradingClass,
	    String multiplier, Set<String> expirations, Set<Double> strikes) {
	super(source);
	this.exchange = exchange;
	this.underlyingConId = underlyingConId;
	this.tradingClass = tradingClass;
	this.multiplier = multiplier;
	this.expirations = expirations;
	this.strikes = strikes;
    }
    public String getExchange() {
        return exchange;
    }
    public void setExchange(String exchange) {
        this.exchange = exchange;
    }
    public int getUnderlyingConId() {
        return underlyingConId;
    }
    public void setUnderlyingConId(int underlyingConId) {
        this.underlyingConId = underlyingConId;
    }
    public String getTradingClass() {
        return tradingClass;
    }
    public void setTradingClass(String tradingClass) {
        this.tradingClass = tradingClass;
    }
    public String getMultiplier() {
        return multiplier;
    }
    public void setMultiplier(String multiplier) {
        this.multiplier = multiplier;
    }
    public Set<String> getExpirations() {
        return expirations;
    }
    public void setExpirations(Set<String> expirations) {
        this.expirations = expirations;
    }
    public Set<Double> getStrikes() {
        return strikes;
    }
    public void setStrikes(Set<Double> strikes) {
        this.strikes = strikes;
    }
}
