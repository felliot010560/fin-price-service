package com.aleatory.price.services;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.aleatory.common.domain.WireCondor;
import com.aleatory.common.domain.WirePrice;
import com.aleatory.price.provider.CondorProvider;
import com.aleatory.price.provider.ImpliedVolatilityProvider;
import com.aleatory.price.provider.PricingAPIClient;
import com.aleatory.price.provider.SPXPriceProvider;

@Component
public class PricingService {

    @Autowired
    PricingAPIClient client;

    @Autowired
    SPXPriceProvider spxPriceProvider;

    @Autowired
    CondorProvider condorProvider;

    @Autowired
    ImpliedVolatilityProvider impliedVolatilityProvider;

    public WirePrice getSPXPrice() {
        return spxPriceProvider.getSPXPrice();
    }

    public Double getSPXImpliedVol() {
        return impliedVolatilityProvider.getImpliedVolatility();
    }

    /**
     * The {@link WireCondor} class has the condor strikes in the order: <upper band
     * long call>, <upper band short call>, <lower band short put>, <lower band long
     * put>
     * 
     * @return the list of condor strikes in the order specified
     */
    public WireCondor getCondor() {
        return condorProvider.getCurrentCondor();
    }

    public WirePrice getCondorPrice() {
        return condorProvider.getCondorWirePrice();
    }

    public Double getSPXChange() {
        return spxPriceProvider.getSPXChange();
    }

}
