package com.aleatory.price.events;

import org.springframework.context.ApplicationEvent;

public class SPXCloseReceivedEvent extends ApplicationEvent {
    private static final long serialVersionUID = 1L;
    private Double close;

    public SPXCloseReceivedEvent(Object source, Double close) {
        super(source);
        this.close = close;
    }

    public Double getClose() {
        return close;
    }

    public void setClose(Double close) {
        this.close = close;
    }

}
