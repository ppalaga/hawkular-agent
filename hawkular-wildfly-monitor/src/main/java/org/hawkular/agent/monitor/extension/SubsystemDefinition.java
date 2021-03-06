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
package org.hawkular.agent.monitor.extension;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUBSYSTEM;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.PersistentResourceDefinition;
import org.jboss.as.controller.SimpleAttributeDefinition;
import org.jboss.as.controller.SimpleAttributeDefinitionBuilder;
import org.jboss.as.controller.SimpleOperationDefinition;
import org.jboss.as.controller.SimpleOperationDefinitionBuilder;
import org.jboss.as.controller.registry.AttributeAccess;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.controller.registry.OperationEntry.Flag;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;

public class SubsystemDefinition extends PersistentResourceDefinition {

    public static final SubsystemDefinition INSTANCE = new SubsystemDefinition();

    // ATTRIBUTES

    static final SimpleAttributeDefinition ENABLED = new SimpleAttributeDefinitionBuilder("enabled",
            ModelType.BOOLEAN)
            .setAllowNull(true)
            .setDefaultValue(new ModelNode(true))
            .setAllowExpression(true)
            .addFlag(AttributeAccess.Flag.RESTART_RESOURCE_SERVICES)
            .build();

    static final SimpleAttributeDefinition API_JNDI = new SimpleAttributeDefinitionBuilder("apiJndiName",
            ModelType.STRING)
            .setAllowNull(true)
            .setDefaultValue(new ModelNode("java:global/hawkular/agent/monitor/api"))
            .setAllowExpression(true)
            .addFlag(AttributeAccess.Flag.RESTART_RESOURCE_SERVICES)
            .build();

    static final SimpleAttributeDefinition NUM_METRIC_SCHEDULER_THREADS = new SimpleAttributeDefinitionBuilder(
            "numMetricSchedulerThreads", ModelType.INT)
            .setAllowNull(true)
            .setDefaultValue(new ModelNode(2))
            .setAllowExpression(true)
            .addFlag(AttributeAccess.Flag.RESTART_RESOURCE_SERVICES)
            .build();

    static final SimpleAttributeDefinition NUM_AVAIL_SCHEDULER_THREADS = new SimpleAttributeDefinitionBuilder(
            "numAvailSchedulerThreads", ModelType.INT)
            .setAllowNull(true)
            .setDefaultValue(new ModelNode(2))
            .setAllowExpression(true)
            .addFlag(AttributeAccess.Flag.RESTART_RESOURCE_SERVICES)
            .build();

    static final SimpleAttributeDefinition NUM_DMR_SCHEDULER_THREADS = new SimpleAttributeDefinitionBuilder(
            "numDmrSchedulerThreads", ModelType.INT)
            .setAllowNull(true)
            .setDefaultValue(new ModelNode(4))
            .setAllowExpression(true)
            .addFlag(AttributeAccess.Flag.RESTART_RESOURCE_SERVICES)
            .build();

    static final AttributeDefinition[] ATTRIBUTES = {
            ENABLED, API_JNDI, NUM_METRIC_SCHEDULER_THREADS, NUM_AVAIL_SCHEDULER_THREADS, NUM_DMR_SCHEDULER_THREADS
    };

    // OPERATIONS

    static final SimpleAttributeDefinition OPPARAM_RESTART = new SimpleAttributeDefinitionBuilder(
            "restart", ModelType.BOOLEAN)
            .setAllowExpression(true)
            .setDefaultValue(new ModelNode(false))
            .build();

    static final SimpleOperationDefinition OP_START = new SimpleOperationDefinitionBuilder(
            "start", SubsystemExtension.getResourceDescriptionResolver())
            .addParameter(OPPARAM_RESTART)
            .build();

    static final SimpleOperationDefinition OP_STOP = new SimpleOperationDefinitionBuilder(
            "stop", SubsystemExtension.getResourceDescriptionResolver())
            .build();

    static final SimpleOperationDefinition OP_STATUS = new SimpleOperationDefinitionBuilder(
            "status", SubsystemExtension.getResourceDescriptionResolver())
            .build();

    private SubsystemDefinition() {
        super(PathElement.pathElement(SUBSYSTEM, SubsystemExtension.SUBSYSTEM_NAME),
                SubsystemExtension.getResourceDescriptionResolver(),
                SubsystemAdd.INSTANCE,
                SubsystemRemove.INSTANCE,
                Flag.RESTART_RESOURCE_SERVICES,
                Flag.RESTART_RESOURCE_SERVICES);
    }

    @Override
    public Collection<AttributeDefinition> getAttributes() {
        return Arrays.asList(ATTRIBUTES);
    }

    @Override
    protected List<? extends PersistentResourceDefinition> getChildren() {
        return Arrays.asList(
                StorageDefinition.INSTANCE,
                DiagnosticsDefinition.INSTANCE,
                ManagedServersDefinition.INSTANCE,
                DMRMetricSetDefinition.INSTANCE,
                DMRAvailSetDefinition.INSTANCE);
    }

    @Override
    public void registerOperations(ManagementResourceRegistration rr) {
        super.registerOperations(rr);

        rr.registerOperationHandler(OP_START, new OperationSubsystemStart());
        rr.registerOperationHandler(OP_STOP, new OperationSubsystemStop());
        rr.registerOperationHandler(OP_STATUS, new OperationSubsystemStatus());
    }

}
