package com.aleatory.price.events;

import org.springframework.context.ApplicationEvent;

public class SPXContractValidEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    public SPXContractValidEvent(Object source) {
	super(source);
    }

}
