package com.aleatory.price.events;

import org.springframework.context.ApplicationEvent;

import com.aleatory.common.events.TickReceivedEvent.PriceType;

public class SPXExternalTickReceivedEvent extends ApplicationEvent {
    private static final long serialVersionUID = 1L;

    private PriceType ticktype;
    private Double value;
    
    public SPXExternalTickReceivedEvent(Object source, PriceType ticktype, Double value) {
	super(source);
	this.ticktype = ticktype;
	this.value = value;
    }
    
    public PriceType getTicktype() {
        return ticktype;
    }
    public void setTicktype(PriceType ticktype) {
        this.ticktype = ticktype;
    }
    public Double getValue() {
        return value;
    }
    public void setValue(Double value) {
        this.value = value;
    }
}
