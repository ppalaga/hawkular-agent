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
package org.hawkular.agent.monitor.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hawkular.agent.monitor.api.MetricDataPayloadBuilder;
import org.hawkular.metrics.client.common.SingleMetric;

import com.google.gson.Gson;

/**
 * Allows one to build up a payload request to send to Hawkular by adding
 * data points one by one.
 */
public class HawkularMetricDataPayloadBuilder implements MetricDataPayloadBuilder {

    private HawkularMetricsMetricDataPayloadBuilder metricsPayloadBuilder =
            new HawkularMetricsMetricDataPayloadBuilder();
    private String tenantId = null;

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public HawkularMetricsMetricDataPayloadBuilder toHawkularMetricsMetricDataPayloadBuilder() {
        return metricsPayloadBuilder;
    }

    @Override
    public void addDataPoint(String key, long timestamp, double value) {
        // delegate
        metricsPayloadBuilder.addDataPoint(key, timestamp, value);
    }

    @Override
    public int getNumberDataPoints() {
        return metricsPayloadBuilder.getNumberDataPoints();
    }

    @Override
    public String toPayload() {
        String jsonPayload = new Gson().toJson(toMessageBusObject());
        return jsonPayload;
    }

    public Map<String, Object> toMessageBusObject() {
        if (tenantId == null) {
            throw new IllegalStateException("Do not know the tenant ID");
        }

        List<SingleMetric> singleMetrics = new ArrayList<>();

        // list of maps where map is keyed on metric ID ("id") and value is "data"
        // where "data" is another List<Map<String, Number>> that is the list of metric data.
        // That inner map is keyed on either "timestamp" or "value" (both are Numbers).
        List<Map<String, Object>> allMetrics = metricsPayloadBuilder.toObjectPayload();
        for (Map<String, Object> metric : allMetrics) {
            String metricId = (String) metric.get("id");
            List<Map<String, Number>> metricListData = (List<Map<String, Number>>) metric.get("data");
            for (Map<String, Number> singleMetricData : metricListData) {
                long timestamp = singleMetricData.get("timestamp").longValue();
                double value = singleMetricData.get("value").doubleValue();
                singleMetrics.add(new SingleMetric(metricId, timestamp, value));
            }
        }

        Map<String, Object> data = new HashMap<>(2);
        data.put("tenantId", tenantId);
        data.put("data", singleMetrics);

        Map<String, Object> outerBusObject = new HashMap<>(1);
        outerBusObject.put("metricData", data);

        return outerBusObject;
    }
}
