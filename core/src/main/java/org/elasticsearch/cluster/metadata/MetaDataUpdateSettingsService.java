/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.cluster.metadata;

import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.settings.put.UpdateSettingsClusterStateUpdateRequest;
import org.elasticsearch.action.admin.indices.upgrade.post.UpgradeSettingsClusterStateUpdateRequest;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.cluster.AckedClusterStateUpdateTask;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateListener;
import org.elasticsearch.cluster.ack.ClusterStateUpdateResponse;
import org.elasticsearch.cluster.block.ClusterBlocks;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.routing.allocation.AllocationService;
import org.elasticsearch.cluster.routing.allocation.RoutingAllocation;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.IndexScopeSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.IndexNotFoundException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.elasticsearch.common.settings.Settings.settingsBuilder;

/**
 * Service responsible for submitting update index settings requests
 */
public class MetaDataUpdateSettingsService extends AbstractComponent implements ClusterStateListener {

    private final ClusterService clusterService;

    private final AllocationService allocationService;

    private final IndexNameExpressionResolver indexNameExpressionResolver;
    private final IndexScopeSettings indexScopeSettings;

    @Inject
    public MetaDataUpdateSettingsService(Settings settings, ClusterService clusterService, AllocationService allocationService, IndexScopeSettings indexScopeSettings, IndexNameExpressionResolver indexNameExpressionResolver) {
        super(settings);
        this.clusterService = clusterService;
        this.indexNameExpressionResolver = indexNameExpressionResolver;
        this.clusterService.add(this);
        this.allocationService = allocationService;
        this.indexScopeSettings = indexScopeSettings;
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        // update an index with number of replicas based on data nodes if possible
        if (!event.state().nodes().localNodeMaster()) {
            return;
        }
        // we will want to know this for translating "all" to a number
        final int dataNodeCount = event.state().nodes().dataNodes().size();

        Map<Integer, List<String>> nrReplicasChanged = new HashMap<>();
        // we need to do this each time in case it was changed by update settings
        for (final IndexMetaData indexMetaData : event.state().metaData()) {
            AutoExpandReplicas autoExpandReplicas = IndexMetaData.SETTING_AUTO_EXPAND_REPLICAS_SETTING.get(indexMetaData.getSettings());
            if (autoExpandReplicas.isEnabled()) {
                final int min = autoExpandReplicas.getMinReplicas();
                final int max = autoExpandReplicas.getMaxReplicas(dataNodeCount);
                int numberOfReplicas = dataNodeCount - 1;
                if (numberOfReplicas < min) {
                    numberOfReplicas = min;
                } else if (numberOfReplicas > max) {
                    numberOfReplicas = max;
                }
                // same value, nothing to do there
                if (numberOfReplicas == indexMetaData.getNumberOfReplicas()) {
                    continue;
                }

                if (numberOfReplicas >= min && numberOfReplicas <= max) {

                    if (!nrReplicasChanged.containsKey(numberOfReplicas)) {
                        nrReplicasChanged.put(numberOfReplicas, new ArrayList<>());
                    }

                    nrReplicasChanged.get(numberOfReplicas).add(indexMetaData.getIndex());
                }
            }
        }

        if (nrReplicasChanged.size() > 0) {
            for (final Integer fNumberOfReplicas : nrReplicasChanged.keySet()) {
                Settings settings = Settings.settingsBuilder().put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, fNumberOfReplicas).build();
                final List<String> indices = nrReplicasChanged.get(fNumberOfReplicas);

                UpdateSettingsClusterStateUpdateRequest updateRequest = new UpdateSettingsClusterStateUpdateRequest()
                        .indices(indices.toArray(new String[indices.size()])).settings(settings)
                        .ackTimeout(TimeValue.timeValueMillis(0)) //no need to wait for ack here
                        .masterNodeTimeout(TimeValue.timeValueMinutes(10));

                updateSettings(updateRequest, new ActionListener<ClusterStateUpdateResponse>() {
                    @Override
                    public void onResponse(ClusterStateUpdateResponse response) {
                        for (String index : indices) {
                            logger.info("[{}] auto expanded replicas to [{}]", index, fNumberOfReplicas);
                        }
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        for (String index : indices) {
                            logger.warn("[{}] fail to auto expand replicas to [{}]", index, fNumberOfReplicas);
                        }
                    }
                });
            }
        }
    }

    public void updateSettings(final UpdateSettingsClusterStateUpdateRequest request, final ActionListener<ClusterStateUpdateResponse> listener) {
        Settings.Builder updatedSettingsBuilder = Settings.settingsBuilder();
        updatedSettingsBuilder.put(request.settings()).normalizePrefix(IndexMetaData.INDEX_SETTING_PREFIX);
        Settings.Builder settingsForClosedIndices = Settings.builder();
        Settings.Builder settingsForOpenIndices = Settings.builder();
        Settings.Builder skipppedSettings = Settings.builder();


        // never allow to change the number of shards
        for (Map.Entry<String, String> entry : updatedSettingsBuilder.internalMap().entrySet()) {
            if (entry.getKey().equals(IndexMetaData.SETTING_NUMBER_OF_SHARDS)) {
                listener.onFailure(new IllegalArgumentException("can't change the number of shards for an index"));
                return;
            }
            Setting setting = indexScopeSettings.get(entry.getKey());
            if (setting == null) {
                throw new IllegalArgumentException("setting [" + entry.getKey() + "] is unknown");
            }
            indexScopeSettings.validate(entry.getKey(), entry.getValue());
            settingsForClosedIndices.put(entry.getKey(), entry.getValue());
            if (setting.isDynamic()) {
                settingsForOpenIndices.put(entry.getKey(), entry.getValue());
            } else {
                skipppedSettings.put(entry.getKey(), entry.getValue());
            }
        }
        final Settings skippedSettigns = skipppedSettings.build();
        final Settings closedSettings = settingsForClosedIndices.build();
        final Settings openSettings = settingsForOpenIndices.build();

        clusterService.submitStateUpdateTask("update-settings",
                new AckedClusterStateUpdateTask<ClusterStateUpdateResponse>(Priority.URGENT, request, listener) {

            @Override
            protected ClusterStateUpdateResponse newResponse(boolean acknowledged) {
                return new ClusterStateUpdateResponse(acknowledged);
            }

            @Override
            public ClusterState execute(ClusterState currentState) {
                String[] actualIndices = indexNameExpressionResolver.concreteIndices(currentState, IndicesOptions.strictExpand(), request.indices());
                RoutingTable.Builder routingTableBuilder = RoutingTable.builder(currentState.routingTable());
                MetaData.Builder metaDataBuilder = MetaData.builder(currentState.metaData());

                // allow to change any settings to a close index, and only allow dynamic settings to be changed
                // on an open index
                Set<String> openIndices = new HashSet<>();
                Set<String> closeIndices = new HashSet<>();
                for (String index : actualIndices) {
                    if (currentState.metaData().index(index).getState() == IndexMetaData.State.OPEN) {
                        openIndices.add(index);
                    } else {
                        closeIndices.add(index);
                    }
                }

                if (closeIndices.size() > 0 && closedSettings.get(IndexMetaData.SETTING_NUMBER_OF_REPLICAS) != null) {
                    throw new IllegalArgumentException(String.format(Locale.ROOT,
                            "Can't update [%s] on closed indices [%s] - can leave index in an unopenable state", IndexMetaData.SETTING_NUMBER_OF_REPLICAS,
                            closeIndices
                    ));
                }
                if (!skippedSettigns.getAsMap().isEmpty() && !openIndices.isEmpty()) {
                    throw new IllegalArgumentException(String.format(Locale.ROOT,
                            "Can't update non dynamic settings[%s] for open indices [%s]",
                            skippedSettigns.getAsMap().keySet(),
                            openIndices
                    ));
                }

                int updatedNumberOfReplicas = openSettings.getAsInt(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, -1);
                if (updatedNumberOfReplicas != -1) {
                    routingTableBuilder.updateNumberOfReplicas(updatedNumberOfReplicas, actualIndices);
                    metaDataBuilder.updateNumberOfReplicas(updatedNumberOfReplicas, actualIndices);
                    logger.info("updating number_of_replicas to [{}] for indices {}", updatedNumberOfReplicas, actualIndices);
                }

                ClusterBlocks.Builder blocks = ClusterBlocks.builder().blocks(currentState.blocks());
                final boolean updatedReadOnly = IndexMetaData.INDEX_READ_ONLY_SETTING.get(openSettings);
                for (String index : actualIndices) {
                    if (updatedReadOnly) {
                        blocks.addIndexBlock(index, IndexMetaData.INDEX_READ_ONLY_BLOCK);
                    } else {
                        blocks.removeIndexBlock(index, IndexMetaData.INDEX_READ_ONLY_BLOCK);
                    }
                }
                final boolean updateMetaDataBlock = IndexMetaData.INDEX_BLOCKS_METADATA_SETTING.get(openSettings);
                for (String index : actualIndices) {
                    if (updateMetaDataBlock) {
                        blocks.addIndexBlock(index, IndexMetaData.INDEX_METADATA_BLOCK);
                    } else {
                        blocks.removeIndexBlock(index, IndexMetaData.INDEX_METADATA_BLOCK);
                    }
                }

                final boolean updateWriteBlock = IndexMetaData.INDEX_BLOCKS_WRITE_SETTING.get(openSettings);
                for (String index : actualIndices) {
                    if (updateWriteBlock) {
                        blocks.addIndexBlock(index, IndexMetaData.INDEX_WRITE_BLOCK);
                    } else {
                        blocks.removeIndexBlock(index, IndexMetaData.INDEX_WRITE_BLOCK);
                    }
                }

                final boolean updateReadBlock = IndexMetaData.INDEX_BLOCKS_READ_SETTING.get(openSettings);
                for (String index : actualIndices) {
                    if (updateReadBlock) {
                        blocks.addIndexBlock(index, IndexMetaData.INDEX_READ_BLOCK);
                    } else {
                        blocks.removeIndexBlock(index, IndexMetaData.INDEX_READ_BLOCK);
                    }
                }

                if (!openIndices.isEmpty()) {
                    for (String index : openIndices) {
                        IndexMetaData indexMetaData = metaDataBuilder.get(index);
                        if (indexMetaData == null) {
                            throw new IndexNotFoundException(index);
                        }
                        Settings.Builder updates = Settings.builder();
                        Settings.Builder indexSettings = Settings.builder().put(indexMetaData.getSettings());
                        if (indexScopeSettings.updateSettings(openSettings, indexSettings, updates, index, false)) {
                            metaDataBuilder.put(IndexMetaData.builder(indexMetaData).settings(indexSettings));
                        }
                    }
                }

                if (!closeIndices.isEmpty()) {
                    for (String index : closeIndices) {
                        IndexMetaData indexMetaData = metaDataBuilder.get(index);
                        if (indexMetaData == null) {
                            throw new IndexNotFoundException(index);
                        }
                        Settings.Builder updates = Settings.builder();
                        Settings.Builder indexSettings = Settings.builder().put(indexMetaData.getSettings());
                        if (indexScopeSettings.updateSettings(closedSettings, indexSettings, updates, index, true)) {
                            metaDataBuilder.put(IndexMetaData.builder(indexMetaData).settings(indexSettings));
                        }
                    }
                }


                ClusterState updatedState = ClusterState.builder(currentState).metaData(metaDataBuilder).routingTable(routingTableBuilder.build()).blocks(blocks).build();

                // now, reroute in case things change that require it (like number of replicas)
                RoutingAllocation.Result routingResult = allocationService.reroute(updatedState, "settings update");
                updatedState = ClusterState.builder(updatedState).routingResult(routingResult).build();
                for (String index : openIndices) {
                    indexScopeSettings.dryRun(updatedState.metaData().index(index).getSettings());
                }
                for (String index : closeIndices) {
                    indexScopeSettings.dryRun(updatedState.metaData().index(index).getSettings());
                }
                return updatedState;
            }
        });
    }


    public void upgradeIndexSettings(final UpgradeSettingsClusterStateUpdateRequest request, final ActionListener<ClusterStateUpdateResponse> listener) {


        clusterService.submitStateUpdateTask("update-index-compatibility-versions", new AckedClusterStateUpdateTask<ClusterStateUpdateResponse>(Priority.URGENT, request, listener) {

            @Override
            protected ClusterStateUpdateResponse newResponse(boolean acknowledged) {
                return new ClusterStateUpdateResponse(acknowledged);
            }

            @Override
            public ClusterState execute(ClusterState currentState) {
                MetaData.Builder metaDataBuilder = MetaData.builder(currentState.metaData());
                for (Map.Entry<String, Tuple<Version, String>> entry : request.versions().entrySet()) {
                    String index = entry.getKey();
                    IndexMetaData indexMetaData = metaDataBuilder.get(index);
                    if (indexMetaData != null) {
                        if (Version.CURRENT.equals(indexMetaData.getCreationVersion()) == false) {
                            // No reason to pollute the settings, we didn't really upgrade anything
                            metaDataBuilder.put(IndexMetaData.builder(indexMetaData)
                                            .settings(settingsBuilder().put(indexMetaData.getSettings())
                                                            .put(IndexMetaData.SETTING_VERSION_MINIMUM_COMPATIBLE, entry.getValue().v2())
                                                            .put(IndexMetaData.SETTING_VERSION_UPGRADED, entry.getValue().v1())
                                            )
                            );
                        }
                    }
                }
                return ClusterState.builder(currentState).metaData(metaDataBuilder).build();
            }
        });
    }
}
