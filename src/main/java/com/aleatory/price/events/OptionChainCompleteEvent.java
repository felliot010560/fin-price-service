package com.aleatory.price.events;

import java.util.Date;
import java.util.Map;

import org.fattails.domain.Option;
import org.springframework.context.ApplicationEvent;

public class OptionChainCompleteEvent extends ApplicationEvent {
    private static final long serialVersionUID = 1L;
    
    private Map<String, Option> optionChain;
    private Date expDate;

    public OptionChainCompleteEvent(Object source, Map<String, Option> optionChain, Date expDate) {
	super(source);
	this.optionChain = optionChain;
	this.expDate = expDate;
    }

    public Map<String, Option> getOptionChain() {
        return optionChain;
    }

    public Date getExpDate() {
        return expDate;
    }

}
