package com.aleatory.price.events;

import org.springframework.context.ApplicationEvent;

public class OptionImpVolAvailableEvent extends ApplicationEvent {
    private static final long serialVersionUID = 1L;
    private double impVol;
    private int tickerId;

    public OptionImpVolAvailableEvent(Object source, int tickerId, double impVol) {
        super(source);
        this.tickerId = tickerId;
        this.impVol = impVol;
    }

    public double getImpVol() {
        return impVol;
    }

    public int getTickerId() {
        return tickerId;
    }

}
