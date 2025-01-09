package com.aleatory.price.provider;

import java.time.LocalDate;

import org.fattails.domain.Option;
import org.fattails.domain.PriceableSecurity;
import org.fattails.domain.Stock;

import com.aleatory.common.domain.IBIronCondor;
import com.aleatory.common.domain.IronCondor;

public interface PricingAPIClient {
    static final String SPX_SYMBOL = "SPX";
    static final String SPX_NAME = "S&P 500 Index";
    void cancelMarketData(int tickerId);
    int requestMarketData(int oldTickerId, PriceableSecurity security, boolean isSnapshot);
    int requestPriceVendorSpecificInformation(PriceableSecurity security);
    void requestClosePrices(PriceableSecurity security);
    void requestHistoricalSPXPrices(Stock spx, LocalDate forDate);
    void requestStrikesAndExpirations(PriceableSecurity underlying);
    int startImpliedVolCalculation(Option option, double priceForCalc, double underlyingPrice);
    Option getEmptyVendorSpecificOption();
    Stock getEmptyVendorSpecificStock();
    void checkConnectionAlive();
    /**
     * Builds an iron condor specific to the trading system ({@link IBIronCondor} for IBKR, e.g.)
     * @param longCall
     * @param shortCall
     * @param shortPut
     * @param longPut
     * @param quantity
     * @return
     */
    public IronCondor<? extends Option> buildIronCondor(Option longCall, Option shortCall, Option shortPut, Option longPut, short quantity);
}
