package com.aleatory.price.events;

import org.fattails.domain.Price;
import org.springframework.context.ApplicationEvent;

import com.aleatory.common.domain.WirePrice;

public class NewCondorPriceEvent extends ApplicationEvent {
	private static final long serialVersionUID = 1L;
	Price condorPrice;

	public NewCondorPriceEvent(Object source, Price condorPrice) {
		super(source);
		this.condorPrice = condorPrice;
	}
	
	public WirePrice getPrice() {
		return new WirePrice(condorPrice);
	}

}
