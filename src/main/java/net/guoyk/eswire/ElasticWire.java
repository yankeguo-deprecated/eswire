package net.guoyk.eswire;

import org.elasticsearch.action.admin.indices.forcemerge.ForceMergeRequest;
import org.elasticsearch.action.admin.indices.open.OpenIndexRequest;
import org.elasticsearch.action.admin.indices.recovery.RecoveryRequest;
import org.elasticsearch.action.admin.indices.recovery.RecoveryResponse;
import org.elasticsearch.action.admin.indices.settings.put.UpdateSettingsRequest;
import org.elasticsearch.action.support.ActiveShardCount;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.transport.client.PreBuiltTransportClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public class ElasticWire implements Closeable, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticWire.class);

    private final TransportClient client;

    private final ElasticWireOptions options;

    public ElasticWire(ElasticWireOptions options) throws UnknownHostException {
        this.options = options;
        Settings settings = Settings.builder()
                .put("client.transport.ignore_cluster_name", true)
                .build();
        this.client = new PreBuiltTransportClient(settings)
                .addTransportAddress(new TransportAddress(InetAddress.getByName(options.getHost()), 9300));
    }

    public void export(String index, ElasticWireCallback callback) throws ExecutionException, InterruptedException {
        // open and wait for all active shards
        OpenIndexRequest openIndexRequest = new OpenIndexRequest(index);
        openIndexRequest.waitForActiveShards(ActiveShardCount.ALL);
        this.client.admin().indices().open(openIndexRequest).get();
        LOGGER.info("open index: {}", index);
        // force merge
        ForceMergeRequest forceMergeRequest = new ForceMergeRequest(index);
        forceMergeRequest.maxNumSegments(1);
        this.client.admin().indices().forceMerge(forceMergeRequest).get();
        LOGGER.info("force merge index: {}", index);
        // transfer
        UpdateSettingsRequest updateSettingsRequest = new UpdateSettingsRequest(index);
        Settings.Builder settingsBuilder = Settings.builder();
        for (Map.Entry<String, String> entry : this.options.getNodeAttrs().entrySet()) {
            settingsBuilder = settingsBuilder.put("index.routing.allocation.require." + entry.getKey(), entry.getValue());
        }
        updateSettingsRequest.settings(settingsBuilder.build());
        this.client.admin().indices().updateSettings(updateSettingsRequest).get();
        LOGGER.info("update index settings: {}", index);
        // wait for recovery
        for (; ; ) {
            //noinspection BusyWait
            Thread.sleep(5000);
            RecoveryRequest recoveryRequest = new RecoveryRequest(index);
            recoveryRequest.activeOnly(true);
            RecoveryResponse recoveryResponse = this.client.admin().indices().recoveries(recoveryRequest).get();
            if (!recoveryResponse.hasRecoveries()) {
                break;
            }
        }
        LOGGER.info("index recovered: {}", index);
    }

    @Override
    public void close() throws IOException {
        if (this.client != null) {
            this.client.close();
        }
    }

}