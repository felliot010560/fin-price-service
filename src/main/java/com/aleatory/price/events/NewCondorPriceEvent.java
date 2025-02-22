package com.aleatory.price.events;

import org.fattails.domain.Price;
import org.springframework.context.ApplicationEvent;

import com.aleatory.common.domain.WirePrice;

public class NewCondorPriceEvent extends ApplicationEvent {
    private static final long serialVersionUID = 1L;
    Price condorPrice;
    boolean fromLegs;

    public NewCondorPriceEvent(Object source, Price condorPrice, boolean fromLegs) {
        super(source);
        this.condorPrice = condorPrice;
        this.fromLegs = fromLegs;
    }

    public WirePrice getPrice() {
        return new WirePrice(condorPrice, fromLegs);
    }

}
