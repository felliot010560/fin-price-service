package com.aleatory.price.events;

import org.springframework.context.ApplicationEvent;

public class OptionGroupImpVolComplete extends ApplicationEvent {
    private static final long serialVersionUID = 1L;
    private int groupId;

    public OptionGroupImpVolComplete(Object source, int groupId) {
	super(source);
	this.groupId = groupId;
    }

    public int getGroupId() {
	return groupId;
    }
    
    
}
