package com.aleatory.price.events;

import org.springframework.context.ApplicationEvent;

import com.aleatory.common.events.TickReceivedEvent.PriceType;

public class NewSPXPriceEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;
    
    private PriceType type;
    private Double price;

    public NewSPXPriceEvent(Object source, PriceType type, Double price) {
        super(source);
        this.type = type;
        this.price = price;
    }

    public PriceType getType() {
        return type;
    }

    public Double getPrice() {
        return price;
    }

}
