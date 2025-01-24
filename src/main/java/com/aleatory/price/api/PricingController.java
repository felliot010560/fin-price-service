package com.aleatory.price.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.aleatory.common.domain.WireCondor;
import com.aleatory.common.domain.WirePrice;
import com.aleatory.price.IBPricingServiceApplication;
import com.aleatory.price.services.PricingService;

@RestController
public class PricingController {
    private static final Logger logger = LoggerFactory.getLogger(PricingController.class);

    @Autowired
    private PricingService pricingService;

    @GetMapping("/condor")
    @CrossOrigin(origins = "http://localhost:3000", allowCredentials = "true")
    @ResponseBody
    public WireCondor getCondorStrikes() {
        logger.info("Getting condor.");
        return pricingService.getCondor();
    }

    @GetMapping("/condor-price")
    @CrossOrigin(origins = "http://localhost:3000", allowCredentials = "true")
    @ResponseBody
    public WirePrice getCondorPrice() {
        logger.info("Getting condor price.");
        return pricingService.getCondorPrice();
    }

    @GetMapping("/spx-price")
    @CrossOrigin(origins = "http://localhost:3000", allowCredentials = "true")
    @ResponseBody
    public WirePrice getSPXPrice() {
        logger.info("Getting SPX price.");
        return pricingService.getSPXPrice();
    }
    
    @PostMapping("/restart")
    @CrossOrigin(origins = { "http://localhost:3000", "http://192.168.68.51:3030" }, allowCredentials = "true")
    @ResponseBody
    public void restart() {
        IBPricingServiceApplication.restart(IBPricingServiceApplication.class);
    }

}
