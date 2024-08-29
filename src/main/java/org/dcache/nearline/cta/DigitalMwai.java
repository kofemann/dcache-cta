package org.dcache.nearline.cta;


import static io.grpc.Status.Code.NOT_FOUND;
import static java.nio.charset.StandardCharsets.UTF_8;
import ch.cern.cta.rpc.CtaRpcGrpc.CtaRpcBlockingStub;
import ch.cern.cta.rpc.SchedulerRequest;
import com.sleepycat.je.Cursor;
import com.sleepycat.je.CursorConfig;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.OperationStatus;

import com.sleepycat.je.Transaction;
import io.grpc.StatusRuntimeException;

import java.io.File;
import java.util.function.BiFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This class provided a persistent storage for pending requests.
 * <p>
 * The new requests are added to the list and removed on completion.
 * On submit, pool try to add the request to the list. And if already exist,
 * the pending request will cancel (delete) in CTA before submission.
 */
public class DigitalMwai implements CleanupJournal {

    private static final Logger LOGGER = LoggerFactory.getLogger(DigitalMwai.class);

    private static final String CTA_PENDING_REQUEST = "archive-requests";

    private final CursorConfig config = new CursorConfig();
    private final Environment env;
    private final DatabaseConfig dbConfig;
    private Database pendingRequests;


    /**
     * Create a new instance of {@link DigitalMwai}.
     *
     * @param path the path to the directory where the persistent storage will be created.
     */
    DigitalMwai(String path) {

        File dir = new File(path);

        if (!dir.exists()) {
            dir.mkdirs();
        }

        EnvironmentConfig envConfig = new EnvironmentConfig();
        envConfig.setAllowCreate(true);
        envConfig.setTransactional(true);
        envConfig.setReadOnly(false);

        env = new Environment(dir, envConfig);

        dbConfig = new DatabaseConfig();
        dbConfig.setTransactional(true);
        dbConfig.setAllowCreate(true);
        dbConfig.setReadOnly(false);

        pendingRequests = env.openDatabase(null, CTA_PENDING_REQUEST, dbConfig);
    }


    @Override
    public void cleanup(CtaRpcBlockingStub cta) {

        Transaction tx = env.beginTransaction(null, null);
        try (Cursor cursor = pendingRequests.openCursor(tx, config)) {

            DatabaseEntry key = new DatabaseEntry();
            DatabaseEntry data = new DatabaseEntry();

            /*
             * Remove all pending requests that are submitted before restart.
             */
            while (cursor.getNext(key, data, null) == OperationStatus.SUCCESS) {
                boolean canDelete = false;
                try {
                    var cancelRequest = SchedulerRequest.parseFrom(data.getData());
                    switch (cancelRequest.getMd().getWf().getEvent()) {
                        case CLOSEW:
                            cta.delete(cancelRequest);
                            break;
                        case PREPARE:
                            cta.cancelRetrieve(cancelRequest);
                            break;
                        default:
                            LOGGER.warn("Unexpected request type: {}", cancelRequest.getMd().getWf().getEvent());
                    }
                    canDelete = true;
                } catch (StatusRuntimeException e) {
                    if (e.getStatus().getCode() == NOT_FOUND) {
                        canDelete = true;
                    } else {
                        LOGGER.error("Failed to remove pending request: {}", e.getStatus());
                    }
                } catch (Exception e) {
                    LOGGER.error("Unexpected error while removing pending request: {}", e.getMessage());
                }

                if (canDelete) {
                    cursor.delete();
                }
            }
        } finally {
            tx.commit();
        }
    }

    @Override
    public void cleanup(CtaRpcBlockingStub cta, BiFunction<String, SchedulerRequest, Boolean> consumer) {

        Transaction tx = env.beginTransaction(null, null);
        try (Cursor cursor = pendingRequests.openCursor(tx, config)) {

            DatabaseEntry key = new DatabaseEntry();
            DatabaseEntry data = new DatabaseEntry();

            /*
             * Remove all pending requests that are submitted before restart.
             */
            while (cursor.getNext(key, data, null) == OperationStatus.SUCCESS) {

                try {
                    String id = new String(key.getData(), UTF_8);
                    var cancelRequest = SchedulerRequest.parseFrom(data.getData());
                    if (consumer.apply(id, cancelRequest)) {
                        cursor.delete();
                    }
                } catch (Exception e) {
                    LOGGER.error("Unexpected error while removing pending request: {}", e.getMessage());
                }
            }
        } finally {
            tx.commit();
        }
    }

    @Override
    public void put(String pnfsid, SchedulerRequest archiveResponse) {
        DatabaseEntry key = new DatabaseEntry(pnfsid.getBytes(UTF_8));
        DatabaseEntry data = new DatabaseEntry(archiveResponse.toByteArray());

        var status = pendingRequests.putNoOverwrite(null, key, data);
        if (status != OperationStatus.SUCCESS) {
            throw new IllegalStateException("Unexpected status: " + status);
        }
    }

    @Override
    public void remove(String pnfsid) {
        DatabaseEntry key = new DatabaseEntry(pnfsid.getBytes(UTF_8));
        var status = pendingRequests.delete(null, key);
        if (status != OperationStatus.SUCCESS) {
            LOGGER.error("Failed to remove persistent for {} entry: {}", pnfsid, status);
        }
    }

    @Override
    public void close() {
        pendingRequests.close();
        env.close();
    }

}
