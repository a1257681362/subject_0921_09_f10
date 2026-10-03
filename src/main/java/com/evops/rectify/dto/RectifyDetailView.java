package com.evops.rectify.dto;

import com.evops.rectify.entity.RectifyCc;
import com.evops.rectify.entity.RectifyEvent;
import com.evops.rectify.entity.RectifyOrder;
import com.evops.rectify.entity.RectifyPhoto;

import java.util.List;

/** 整改单全量档案：单子 + 事件版本留底 + 留证照片 + 超期抄送记录。 */
public class RectifyDetailView {
    private RectifyOrder order;
    private List<RectifyEvent> events;
    private List<RectifyPhoto> photos;
    private List<RectifyCc> ccs;

    public RectifyOrder getOrder() { return order; }
    public void setOrder(RectifyOrder order) { this.order = order; }
    public List<RectifyEvent> getEvents() { return events; }
    public void setEvents(List<RectifyEvent> events) { this.events = events; }
    public List<RectifyPhoto> getPhotos() { return photos; }
    public void setPhotos(List<RectifyPhoto> photos) { this.photos = photos; }
    public List<RectifyCc> getCcs() { return ccs; }
    public void setCcs(List<RectifyCc> ccs) { this.ccs = ccs; }
}
