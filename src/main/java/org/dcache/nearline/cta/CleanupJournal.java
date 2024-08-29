package org.dcache.nearline.cta;

import ch.cern.cta.rpc.CtaRpcGrpc;
import ch.cern.cta.rpc.SchedulerRequest;

import java.util.function.BiFunction;

/**
 * Interface to persistent storage for pending requests.
 * <p>
 * The new requests are added to the list and removed on completion.
 * On submit, pool try to add the request to the list. And if already exist,
 * the pending request will cancel (delete) in CTA before submission.
 */
public interface CleanupJournal extends AutoCloseable {
    void cleanup(CtaRpcGrpc.CtaRpcBlockingStub cta);

    void cleanup(CtaRpcGrpc.CtaRpcBlockingStub cta, BiFunction<String, SchedulerRequest, Boolean> function);

    void put(String pnfsid, SchedulerRequest archiveResponse);

    void remove(String pnfsid);

    @Override
    void close();
}
