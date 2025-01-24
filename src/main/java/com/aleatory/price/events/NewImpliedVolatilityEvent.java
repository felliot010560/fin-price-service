package com.aleatory.price.events;

import org.springframework.context.ApplicationEvent;

public class NewImpliedVolatilityEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    private double impliedVolatility;

    public NewImpliedVolatilityEvent(Object source, double impliedVolatility) {
        super(source);
        this.impliedVolatility = impliedVolatility;
    }

    public double getImpliedVolatility() {
        return impliedVolatility;
    }

    public void setImpliedVolatility(double impliedVolatility) {
        this.impliedVolatility = impliedVolatility;
    }
}
