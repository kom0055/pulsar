/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.flink.streaming.connectors.pulsar;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;

import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;

import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.impl.PulsarClientImpl;
import org.apache.pulsar.client.impl.conf.ClientConfigurationData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Pulsar Client cache that enables client sharing among different flink tasks in same process.
 */
public class CachedPulsarClient {
    private static final Logger LOG = LoggerFactory.getLogger(CachedPulsarClient.class);

    private static int cacheSize = 5;

    public static void setCacheSize(int size) {
        cacheSize = size;
    }

    private static CacheLoader<ClientConfigurationData, PulsarClientImpl> cacheLoader =
        new CacheLoader<ClientConfigurationData, PulsarClientImpl>() {
            @Override
            public PulsarClientImpl load(ClientConfigurationData key) throws Exception {
                return createPulsarClient(key);
            }
        };

    private static RemovalListener<ClientConfigurationData, PulsarClientImpl> removalListener = notification -> {
        ClientConfigurationData config = notification.getKey();
        PulsarClientImpl client = notification.getValue();
        LOG.debug("Evicting pulsar client {} with config {}, due to {}",
            client.toString(), config.toString(), notification.getCause().toString());
        close(config, client);
    };

    private static LoadingCache<ClientConfigurationData, PulsarClientImpl> guavaCache =
        CacheBuilder.newBuilder().maximumSize(cacheSize).removalListener(removalListener).build(cacheLoader);

    private static PulsarClientImpl createPulsarClient(
            ClientConfigurationData clientConfig) throws PulsarClientException {
        PulsarClientImpl client;
        try {
            client = new PulsarClientImpl(clientConfig);
            LOG.debug("Created a new instance of PulsarClientImpl for clientConf = {}", clientConfig.toString());
        } catch (PulsarClientException e) {
            LOG.error("Failed to create PulsarClientImpl for clientConf = {}", clientConfig.toString());
            throw e;
        }
        return client;
    }

    public static PulsarClientImpl getOrCreate(ClientConfigurationData config) throws ExecutionException {
        PulsarClientImpl instance = guavaCache.get(config);
        if (instance.getState().get() == PulsarClientImpl.State.Open) {
            return instance;
        } else {
            guavaCache.invalidate(config);
            return guavaCache.get(config);
        }
    }

    private static void close(ClientConfigurationData clientConfig, PulsarClientImpl client) {
        try {
            LOG.info("Closing the Pulsar client with config {}", clientConfig.toString());
            client.close();
        } catch (PulsarClientException e) {
            LOG.warn("Error while closing the Pulsar client with config {}", clientConfig.toString(), e);
        }
    }

    static void close(ClientConfigurationData clientConfig) {
        guavaCache.invalidate(clientConfig);
    }

    static void clear() {
        LOG.info("Cleaning up guava cache.");
        guavaCache.invalidateAll();
    }

    static ConcurrentMap<ClientConfigurationData, PulsarClientImpl> getAsMap() {
        return guavaCache.asMap();
    }
}
