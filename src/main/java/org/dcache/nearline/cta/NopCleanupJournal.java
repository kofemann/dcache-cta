package org.dcache.nearline.cta;

import ch.cern.cta.rpc.CtaRpcGrpc;
import ch.cern.cta.rpc.SchedulerRequest;

import java.util.function.BiFunction;

public class NopCleanupJournal implements CleanupJournal {

    @Override
    public void cleanup(CtaRpcGrpc.CtaRpcBlockingStub cta) {
        // NOP
    }

    @Override
    public void cleanup(CtaRpcGrpc.CtaRpcBlockingStub cta, BiFunction<String, SchedulerRequest, Boolean> function) {
        // NOP
    }

    @Override
    public void put(String pnfsid, SchedulerRequest archiveResponse) {
        // NOP
    }

    @Override
    public void remove(String pnfsid) {
        // NOP
    }

    @Override
    public void close() {
        // nop
    }
}
