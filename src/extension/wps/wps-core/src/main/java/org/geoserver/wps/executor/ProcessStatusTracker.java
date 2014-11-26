/* (c) 2014 Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.wps.executor;

import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.geoserver.platform.GeoServerExtensions;
import org.geoserver.wps.MemoryProcessStatusStore;
import org.geoserver.wps.ProcessEvent;
import org.geoserver.wps.ProcessListener;
import org.geoserver.wps.ProcessStatusStore;
import org.geoserver.wps.WPSException;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.util.logging.Logging;
import org.opengis.filter.And;
import org.opengis.filter.Filter;
import org.opengis.filter.FilterFactory;
import org.opengis.filter.Not;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;


public class ProcessStatusTracker implements ApplicationContextAware, ProcessListener {

    static final FilterFactory FF = CommonFactoryFinder.getFilterFactory();

    static final Logger LOGGER = Logging.getLogger(ProcessStatusTracker.class);

    ProcessStatusStore store;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        ProcessStatusStore store = GeoServerExtensions.bean(ProcessStatusStore.class,
                applicationContext);
        if (store == null) {
            store = new MemoryProcessStatusStore();
        }

        this.store = store;
    }

    @Override
    public void submitted(ProcessEvent event) throws WPSException {
        if(store == null) {
            return;
        }
        
        store.save(event.getStatus());
    }

    /**
     * Custom method that updates the status last updated field without touching anything else, to
     * make sure we let the cluster know the process is still running
     * 
     * @param executionId
     * @throws WPSException
     */
    public void touch(String executionId) throws WPSException {
        ExecutionStatus status = store.get(executionId);
        if (status != null) {
            status.setLastUpdated(new Date());
            store.save(status);
        }
    }

    @Override
    public void completed(ProcessEvent event) throws WPSException {
        ExecutionStatus newStatus = event.getStatus();
        ExecutionStatus original = store.get(newStatus.getExecutionId());
        if (isStepCompatible(original, newStatus)) {
            newStatus.setLastUpdated(new Date());
            store.save(newStatus);
        } else {
            LOGGER.log(Level.WARNING, "Invalid process status evolution from " + original + " to "
                    + newStatus + " was not saved");
        }

        // update the status in the event to let the process know it has been cancelled
        if (original.getPhase() == ProcessState.CANCELLED) {
            event.getStatus().setPhase(ProcessState.CANCELLED);
        }
    }

    /**
     * Check if going from s1 to s2 makes sense
     * 
     * @param s1
     * @param s2
     * @return
     */
    private boolean isStepCompatible(ExecutionStatus s1, ExecutionStatus s2) {
        if (s1 == null) {
            return false;
        }

        ProcessState startPhase = s1.getPhase();
        ProcessState endPhase = s2.getPhase();
        return (endPhase.equals(startPhase) || endPhase.isValidSuccessor(startPhase))
                && s2.getProgress() >= s1.getProgress();
    }

    @Override
    public void cancelled(ProcessEvent event) throws WPSException {
        ExecutionStatus status = event.getStatus();
        status.setLastUpdated(new Date());
        store.save(status);
    }

    @Override
    public void failed(ProcessEvent event) {
        ExecutionStatus status = event.getStatus();
        status.setLastUpdated(new Date());
        store.save(status);
    }

    @Override
    public void progress(ProcessEvent event) throws WPSException {
        ExecutionStatus status = event.getStatus();
        status.setLastUpdated(new Date());
        store.save(status);
    }

    public ExecutionStatus getStatus(String executionId) {
        return store.get(executionId);
    }

    public void cleanExpiredStatuses(long expirationThreshold) {
        Date date = new Date(expirationThreshold);
        Not completionTimenotNull = FF.not(FF.isNull(FF.property("completionTime")));
        Filter completionTimeExpired = FF.after(FF.property("completionTime"), FF.literal(date));
        Filter completionTimeFilter = FF.and(completionTimenotNull, completionTimeExpired);
        Not lastUpdatedNotNull = FF.not(FF.isNull(FF.property("lastUpdated")));
        Filter lastUpdatedExpired = FF.after(FF.property("lastUpdated"), FF.literal(date));
        Filter lastUpdatedFilter = FF.and(lastUpdatedNotNull, lastUpdatedExpired);
        And filter = FF.and(completionTimeFilter, lastUpdatedFilter);
        store.remove(filter);
    }

    public ProcessStatusStore getStore() {
        return store;
    }

}