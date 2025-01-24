package com.aleatory.price.events;

import org.springframework.context.ApplicationEvent;

public class NewSPXPriceEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    public NewSPXPriceEvent(Object source) {
        super(source);
    }

}
