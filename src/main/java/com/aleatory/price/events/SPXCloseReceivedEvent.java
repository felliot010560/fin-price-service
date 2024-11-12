package com.aleatory.price.events;

import java.time.LocalDate;

import org.springframework.context.ApplicationEvent;

public class SPXCloseReceivedEvent extends ApplicationEvent {
	private static final long serialVersionUID = 1L;

	private Double price;
	private LocalDate forDate;
	private boolean finalPrice;

	public SPXCloseReceivedEvent(Object source, Double price, LocalDate forDate, boolean finalPrice) {
		super(source);
		this.price = price;
		this.forDate = forDate;
		this.finalPrice = finalPrice;
	}

	public Double getPrice() {
		return price;
	}

	public LocalDate getForDate() {
		return forDate;
	}
	
	public boolean isFinalPrice() {
		return finalPrice;
	}

}
