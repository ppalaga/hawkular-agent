/*
 * Copyright 2015 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hawkular.agent.monitor.scheduler;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.hawkular.agent.monitor.diagnostics.Diagnostics;
import org.hawkular.agent.monitor.diagnostics.DiagnosticsImpl;
import org.hawkular.agent.monitor.diagnostics.JBossLoggingReporter;
import org.hawkular.agent.monitor.diagnostics.JBossLoggingReporter.LoggingLevel;
import org.hawkular.agent.monitor.diagnostics.StorageReporter;
import org.hawkular.agent.monitor.log.MsgLogger;
import org.hawkular.agent.monitor.scheduler.config.AvailDMRPropertyReference;
import org.hawkular.agent.monitor.scheduler.config.DMREndpoint;
import org.hawkular.agent.monitor.scheduler.config.DMRPropertyReference;
import org.hawkular.agent.monitor.scheduler.config.LocalDMREndpoint;
import org.hawkular.agent.monitor.scheduler.config.SchedulerConfiguration;
import org.hawkular.agent.monitor.scheduler.polling.IntervalBasedScheduler;
import org.hawkular.agent.monitor.scheduler.polling.Scheduler;
import org.hawkular.agent.monitor.scheduler.polling.Task;
import org.hawkular.agent.monitor.scheduler.polling.TaskGroup;
import org.hawkular.agent.monitor.scheduler.polling.dmr.AvailDMRTask;
import org.hawkular.agent.monitor.scheduler.polling.dmr.AvailDMRTaskGroupRunnable;
import org.hawkular.agent.monitor.scheduler.polling.dmr.DMRTask;
import org.hawkular.agent.monitor.scheduler.polling.dmr.MetricDMRTask;
import org.hawkular.agent.monitor.scheduler.polling.dmr.MetricDMRTaskGroupRunnable;
import org.hawkular.agent.monitor.service.ServerIdentifiers;
import org.hawkular.agent.monitor.storage.AvailBufferedStorageDispatcher;
import org.hawkular.agent.monitor.storage.HawkularMetricsStorageAdapter;
import org.hawkular.agent.monitor.storage.HawkularStorageAdapter;
import org.hawkular.agent.monitor.storage.MetricBufferedStorageDispatcher;
import org.hawkular.agent.monitor.storage.MetricStorageProxy;
import org.hawkular.agent.monitor.storage.StorageAdapter;
import org.hawkular.dmrclient.Address;
import org.jboss.logging.Logger;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.ScheduledReporter;

/**
 * The core service that schedules tasks and stores the data resulting from those tasks to its storage adapter.
 */
public class SchedulerService {

    private final SchedulerConfiguration schedulerConfig;
    private final ServerIdentifiers selfId;
    private final ModelControllerClientFactory localDMRClientFactory;
    private final Diagnostics diagnostics;
    private final ScheduledReporter diagnosticsReporter;
    private final StorageAdapter storageAdapter;
    private final Scheduler metricScheduler;
    private final Scheduler availScheduler;
    private final MetricBufferedStorageDispatcher metricCompletionHandler;
    private final AvailBufferedStorageDispatcher availCompletionHandler;

    private boolean started = false;

    public SchedulerService(
            SchedulerConfiguration configuration,
            ServerIdentifiers selfId,
            MetricStorageProxy metricStorageProxy,
            ModelControllerClientFactory localDMRClientFactory) {

        this.schedulerConfig = configuration;

        // for those tasks that require a DMR client to our own WildFly server, this factory can provide those clients
        this.localDMRClientFactory = localDMRClientFactory;

        // this helps identify where we are running
        this.selfId = selfId;

        // build the diagnostics object that will be used to track our own performance
        final MetricRegistry metricRegistry = new MetricRegistry();
        this.diagnostics = new DiagnosticsImpl(metricRegistry, selfId);

        // determine what our backend storage should be and create its associated adapter
        switch (configuration.getStorageAdapterConfig().type) {
            case HAWKULAR: {
                this.storageAdapter = new HawkularStorageAdapter();
                break;
            }
            case METRICS: {
                this.storageAdapter = new HawkularMetricsStorageAdapter();
                break;
            }
            default: {
                throw new IllegalArgumentException("Invalid storage adapter: "
                        + configuration.getStorageAdapterConfig());
            }
        }

        this.storageAdapter.setSchedulerConfiguration(configuration);
        this.storageAdapter.setDiagnostics(diagnostics);
        this.storageAdapter.setSelfIdentifiers(selfId);

        // determine where we are to store our own diagnostic reports
        switch (configuration.getDiagnosticsConfig().reportTo) {
            case LOG: {
                this.diagnosticsReporter = JBossLoggingReporter.forRegistry(metricRegistry)
                        .convertRatesTo(TimeUnit.SECONDS)
                        .convertDurationsTo(MILLISECONDS)
                        .outputTo(Logger.getLogger(getClass()))
                        .withLoggingLevel(LoggingLevel.INFO)
                        .build();
                break;
            }
            case STORAGE: {
                this.diagnosticsReporter = StorageReporter.forRegistry(metricRegistry, storageAdapter, selfId)
                        .convertRatesTo(TimeUnit.SECONDS)
                        .convertDurationsTo(MILLISECONDS)
                        .build();
                break;
            }
            default: {
                throw new IllegalArgumentException("Invalid diagnostics type: "
                        + configuration.getDiagnosticsConfig().reportTo);
            }
        }

        // create the schedulers - we use two: one for metric collections and one for avail checks
        this.metricCompletionHandler = new MetricBufferedStorageDispatcher(configuration, storageAdapter,
                diagnostics);
        this.metricScheduler = new IntervalBasedScheduler(this, "Hawkular-Monitor-Scheduler-Metrics",
                configuration.getMetricSchedulerThreads());

        this.availCompletionHandler = new AvailBufferedStorageDispatcher(configuration, storageAdapter,
                diagnostics);
        this.availScheduler = new IntervalBasedScheduler(this, "Hawkular-Monitor-Scheduler-Avail",
                configuration.getAvailSchedulerThreads());

        // provide our storage adapater to the proxy - allows external apps to use it to store its own metrics
        metricStorageProxy.setStorageAdapter(storageAdapter);
    }

    public SchedulerConfiguration getSchedulerConfiguration() {
        return this.schedulerConfig;
    }

    public ServerIdentifiers getSelfIdentifiers() {
        return this.selfId;
    }

    public Diagnostics getDiagnostics() {
        return this.diagnostics;
    }

    public void start() {
        if (started) {
            return; // already started
        }

        MsgLogger.LOG.infoStartingScheduler();

        // turn metric DMR refs into Tasks and schedule them now
        List<Task> metricTasks = createMetricDMRTasks(schedulerConfig.getDMRMetricsToBeCollected());
        this.metricCompletionHandler.start();
        this.metricScheduler.schedule(metricTasks);

        // turn avail DMR refs into Tasks and schedule them now
        List<Task> availTasks = createAvailDMRTasks(schedulerConfig.getDMRAvailsToBeChecked());
        this.availCompletionHandler.start();
        this.availScheduler.schedule(availTasks);

        if (this.schedulerConfig.getDiagnosticsConfig().enabled) {
            diagnosticsReporter.start(this.schedulerConfig.getDiagnosticsConfig().interval,
                    this.schedulerConfig.getDiagnosticsConfig().timeUnits);
        }

        started = true;
    }

    public void stop() {
        if (!started) {
            return; // already stopped
        }

        MsgLogger.LOG.infoStoppingScheduler();

        // stop completion handlers
        this.metricCompletionHandler.shutdown();
        this.availCompletionHandler.shutdown();

        // stop the schedulers
        this.metricScheduler.shutdown();
        this.availScheduler.shutdown();

        // stop diagnostic reporting
        this.diagnosticsReporter.stop();

        // spit out a final diagnostics report
        if (this.schedulerConfig.getDiagnosticsConfig().enabled) {
            this.diagnosticsReporter.report();
        }

        started = false;
    }

    public Runnable getTaskGroupRunnable(TaskGroup group) {
        switch (group.getType()) {
            case METRIC: {
                // we are guaranteed the first task is the same kind as all the rest
                Task firstTask = group.getTask(0);
                if (DMRTask.class.isInstance(firstTask)) {
                    // we are guaranteed that all tasks in a group refer to the same endpoint
                    DMREndpoint endpoint = ((DMRTask) firstTask).getEndpoint();
                    ModelControllerClientFactory factory;
                    if (endpoint instanceof LocalDMREndpoint) {
                        factory = this.localDMRClientFactory;
                    } else {
                        factory = new ModelControllerClientFactoryImpl(endpoint);
                    }
                    return new MetricDMRTaskGroupRunnable(group, metricCompletionHandler, getDiagnostics(), factory);
                } else {
                    throw new UnsupportedOperationException("Unsupported metric group: " + group);
                }
            }

            case AVAIL: {
                // we are guaranteed the first task is the same kind as all the rest
                Task firstTask = group.getTask(0);
                if (DMRTask.class.isInstance(firstTask)) {
                    // we are guaranteed that all tasks in a group refer to the same endpoint
                    DMREndpoint endpoint = ((DMRTask) firstTask).getEndpoint();
                    ModelControllerClientFactory factory;
                    if (endpoint instanceof LocalDMREndpoint) {
                        factory = this.localDMRClientFactory;
                    } else {
                        factory = new ModelControllerClientFactoryImpl(endpoint);
                    }
                    return new AvailDMRTaskGroupRunnable(group, availCompletionHandler, getDiagnostics(), factory);
                } else {
                    throw new UnsupportedOperationException("Unsupported avail group: " + group);
                }
            }

            default: {
                throw new IllegalArgumentException("Bad group [" + group + "]. Please report this bug.");
            }
        }
    }

    private List<Task> createMetricDMRTasks(Map<DMREndpoint, List<DMRPropertyReference>> map) {
        List<Task> tasks = new ArrayList<>();

        for (Map.Entry<DMREndpoint, List<DMRPropertyReference>> entry : map.entrySet()) {
            DMREndpoint dmrEndpoint = entry.getKey();
            for (DMRPropertyReference ref : entry.getValue()) {
                // parse sub references (complex attribute support)
                String attribute = ref.getAttribute();
                String subref = null;

                if (attribute != null) {
                    int i = attribute.indexOf("#");
                    if (i > 0) {
                        subref = attribute.substring(i + 1, attribute.length());
                        attribute = attribute.substring(0, i);
                    }
                }

                tasks.add(new MetricDMRTask(ref.getInterval(), dmrEndpoint, Address.parse(ref.getAddress()),
                        attribute, subref));
            }
        }

        return tasks;
    }

    private List<Task> createAvailDMRTasks(Map<DMREndpoint, List<AvailDMRPropertyReference>> map) {
        List<Task> tasks = new ArrayList<>();

        for (Map.Entry<DMREndpoint, List<AvailDMRPropertyReference>> entry : map.entrySet()) {
            DMREndpoint dmrEndpoint = entry.getKey();
            for (AvailDMRPropertyReference ref : entry.getValue()) {

                // parse sub references (complex attribute support)
                String attribute = ref.getAttribute();
                String subref = null;

                if (attribute != null) {
                    int i = attribute.indexOf("#");
                    if (i > 0) {
                        subref = attribute.substring(i + 1, attribute.length());
                        attribute = attribute.substring(0, i);
                    }
                }

                tasks.add(new AvailDMRTask(ref.getInterval(), dmrEndpoint, Address.parse(ref.getAddress()), attribute,
                        subref, ref.getUpRegex()));
            }
        }

        return tasks;
    }
}
