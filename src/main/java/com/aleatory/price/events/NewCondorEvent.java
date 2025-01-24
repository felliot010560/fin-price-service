package com.aleatory.price.events;

import org.fattails.domain.Option;
import org.springframework.context.ApplicationEvent;

import com.aleatory.common.domain.IronCondor;
import com.aleatory.common.domain.WireCondor;
import com.aleatory.common.domain.WireFullCondor;

public class NewCondorEvent extends ApplicationEvent {
    private static final long serialVersionUID = 1L;

    IronCondor<? extends Option> condor;

    public NewCondorEvent(Object source, IronCondor<? extends Option> condor) {
        super(source);
        this.condor = condor;
    }

    public WireCondor getCondor() {
        return new WireCondor(condor);
    }

    public WireFullCondor getFullCondor() {
        return new WireFullCondor(condor);
    }

}
