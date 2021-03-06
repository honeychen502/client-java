package org.tikv.common.region;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import org.apache.log4j.Logger;
import org.tikv.common.AbstractGRPCClient;
import org.tikv.common.TiConfiguration;
import org.tikv.common.exception.KeyException;
import org.tikv.common.exception.RegionException;
import org.tikv.common.exception.TiClientInternalException;
import org.tikv.common.operation.KVErrorHandler;
import org.tikv.common.util.BackOffer;
import org.tikv.common.util.ChannelFactory;
import org.tikv.common.util.Pair;
import org.tikv.kvproto.Kvrpcpb;
import org.tikv.kvproto.Kvrpcpb.*;
import org.tikv.kvproto.Metapb;
import org.tikv.kvproto.TikvGrpc;
import org.tikv.txn.Lock;
import org.tikv.txn.LockResolverClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.tikv.common.util.BackOffFunction.BackOffFuncType.BoRegionMiss;
import static org.tikv.common.util.BackOffFunction.BackOffFuncType.BoTxnLockFast;

/**
 * RegionStore itself is not thread-safe
 * APIs for Transaction KV Scan/Put/Get/Delete
 */
public class TxnRegionStoreClient extends AbstractGRPCClient<TikvGrpc.TikvBlockingStub, TikvGrpc.TikvStub>
        implements RegionErrorReceiver {
    private static final Logger logger = Logger.getLogger(TxnRegionStoreClient.class);

    private TiRegion region;
    private final RegionManager regionManager;
    @VisibleForTesting
    private final LockResolverClient lockResolverClient;
    private TikvGrpc.TikvBlockingStub blockingStub;
    private TikvGrpc.TikvStub asyncStub;

    public static class TxnRegionStoreClientBuilder {
        private final TiConfiguration conf;
        private final ChannelFactory channelFactory;
        private final RegionManager regionManager;

        public TxnRegionStoreClientBuilder(
                TiConfiguration conf, ChannelFactory channelFactory, RegionManager regionManager) {
            Objects.requireNonNull(conf, "conf is null");
            Objects.requireNonNull(channelFactory, "channelFactory is null");
            Objects.requireNonNull(regionManager, "regionManager is null");
            this.conf = conf;
            this.channelFactory = channelFactory;
            this.regionManager = regionManager;
        }

        public TxnRegionStoreClient build(TiRegion region, Metapb.Store store) {
            Objects.requireNonNull(region, "region is null");
            Objects.requireNonNull(store, "store is null");

            String addressStr = store.getAddress();
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("Create region store client on address %s", addressStr));
            }
            ManagedChannel channel = channelFactory.getChannel(addressStr);

            TikvGrpc.TikvBlockingStub blockingStub = TikvGrpc.newBlockingStub(channel);
            TikvGrpc.TikvStub asyncStub = TikvGrpc.newStub(channel);

            return new TxnRegionStoreClient(
                    conf, region, channelFactory, blockingStub, asyncStub, regionManager);
        }

        public TxnRegionStoreClient build(ByteString key) {
            Pair<TiRegion, Metapb.Store> pair = regionManager.getRegionStorePairByKey(key);
            return build(pair.first, pair.second);
        }

        public TxnRegionStoreClient build(TiRegion region) {
            Metapb.Store store = regionManager.getStoreById(region.getLeader().getStoreId());
            return build(region, store);
        }

        public RegionManager getRegionManager() {
            return regionManager;
        }
    }

    private TxnRegionStoreClient(
            TiConfiguration conf,
            TiRegion region,
            ChannelFactory channelFactory,
            TikvGrpc.TikvBlockingStub blockingStub,
            TikvGrpc.TikvStub asyncStub,
            RegionManager regionManager) {
        super(conf, channelFactory);
        checkNotNull(region, "Region is empty");
        checkNotNull(region.getLeader(), "Leader Peer is null");
        checkArgument(region.getLeader() != null, "Leader Peer is null");
        this.regionManager = regionManager;
        this.region = region;
        this.blockingStub = blockingStub;
        this.asyncStub = asyncStub;
        this.lockResolverClient = new LockResolverClient(conf,
                this.blockingStub, this.asyncStub, channelFactory, regionManager);
    }

    /*public static TxnRegionStoreClient create(TiRegion region, Metapb.Store store, TiSession session) {
        TxnRegionStoreClient client;
        String addressStr = store.getAddress();
        if (logger.isDebugEnabled()) {
            logger.debug(String.format("Create region store client on address %s", addressStr));
        }
        ManagedChannel channel = session.getChannel(addressStr);

        TikvGrpc.TikvBlockingStub blockingStub = TikvGrpc.newBlockingStub(channel);

        TikvGrpc.TikvStub asyncStub = TikvGrpc.newStub(channel);
        client = new TxnRegionStoreClient(region, session, blockingStub, asyncStub);
        return client;
    }*/

    @Override
    protected TikvGrpc.TikvBlockingStub getBlockingStub() {
        return blockingStub.withDeadlineAfter(getConf().getTimeout(), getConf().getTimeoutUnit());
    }

    @Override
    protected TikvGrpc.TikvStub getAsyncStub() {
        return asyncStub.withDeadlineAfter(getConf().getTimeout(), getConf().getTimeoutUnit());
    }

    @Override
    public void close() throws Exception {

    }

    @Override
    public boolean onNotLeader(Metapb.Store newStore) {
        if (logger.isDebugEnabled()) {
            logger.debug(region + ", new leader = " + newStore.getId());
        }
        TiRegion cachedRegion = regionManager.getRegionById(region.getId());
        // When switch leader fails or the region changed its key range,
        // it would be necessary to re-split task's key range for new region.
        if (!region.getStartKey().equals(cachedRegion.getStartKey())
                || !region.getEndKey().equals(cachedRegion.getEndKey())) {
            return false;
        }
        region = cachedRegion;
        String addressStr = regionManager.getStoreById(region.getLeader().getStoreId()).getAddress();
        ManagedChannel channel = channelFactory.getChannel(addressStr);
        blockingStub = TikvGrpc.newBlockingStub(channel);
        asyncStub = TikvGrpc.newStub(channel);
        return true;
    }

    @Override
    public void onStoreNotMatch(Metapb.Store store) {
        String addressStr = store.getAddress();
        ManagedChannel channel = channelFactory.getChannel(addressStr);
        blockingStub = TikvGrpc.newBlockingStub(channel);
        asyncStub = TikvGrpc.newStub(channel);
        if (logger.isDebugEnabled() && region.getLeader().getStoreId() != store.getId()) {
            logger.debug(
                    "store_not_match may occur? "
                            + region
                            + ", original store = "
                            + store.getId()
                            + " address = "
                            + addressStr);
        }
    }

    // APIs for Transaction KV Scan/Put/Get/Delete
    public ByteString get(BackOffer backOffer, ByteString key, long version) {
        while (true) {
            Supplier<GetRequest> factory =
                    () ->
                            GetRequest.newBuilder()
                                    .setContext(region.getContext())
                                    .setKey(key)
                                    .setVersion(version)
                                    .build();

            KVErrorHandler<GetResponse> handler =
                    new KVErrorHandler<>(
                            regionManager,
                            this,
                            region,
                            resp -> resp.hasRegionError() ? resp.getRegionError() : null);


            GetResponse resp = callWithRetry(backOffer, TikvGrpc.METHOD_KV_GET, factory, handler);

            if (getHelper(backOffer, resp)) {
                return resp.getValue();
            }
            // we should refresh region
            region = regionManager.getRegionByKey(key);
        }
    }

    private boolean getHelper(BackOffer backOffer, GetResponse resp) {
        if (resp == null) {
            this.regionManager.onRequestFail(region);
            throw new TiClientInternalException("GetResponse failed without a cause");
        }

        if (resp.hasRegionError()) {
            backOffer.doBackOff(BoRegionMiss, new RegionException(resp.getRegionError()));
            return false;
        }

        if (resp.hasError()) {
            if (resp.getError().hasLocked()) {
                Lock lock = new Lock(resp.getError().getLocked());
                boolean ok =
                        lockResolverClient.resolveLocks(backOffer, new ArrayList<>(Arrays.asList(lock)));
                if (!ok) {
                    // if not resolve all locks, we wait and retry
                    backOffer.doBackOff(
                            BoTxnLockFast, new KeyException((resp.getError().getLocked().toString())));
                }
                return false;
            } else {
                // retry or abort
                // this should trigger Spark to retry the txn
                throw new KeyException(resp.getError());
            }
        }
        return true;
    }


    // TODO: batch get should consider key range split
    public List<KvPair> batchGet(BackOffer backOffer, Iterable<ByteString> keys, long version) {
        while(true) {
            Supplier<BatchGetRequest> request =
                    () ->
                            BatchGetRequest.newBuilder()
                                    .setContext(region.getContext())
                                    .addAllKeys(keys)
                                    .setVersion(version)
                                    .build();
            KVErrorHandler<BatchGetResponse> handler =
                    new KVErrorHandler<>(
                            regionManager,
                            this,
                            region,
                            resp -> resp.hasRegionError() ? resp.getRegionError() : null);
            BatchGetResponse resp =
                    callWithRetry(backOffer, TikvGrpc.METHOD_KV_BATCH_GET, request, handler);
            if(batchGetHelper(backOffer, resp)) {
                return resp.getPairsList();
            }
        }
    }

    // TODO: deal with resolve locks and region errors
    private boolean batchGetHelper(BackOffer bo, BatchGetResponse resp) {
        List<Lock> locks = new ArrayList<>();

        for (KvPair pair : resp.getPairsList()) {
            if (pair.hasError()) {
                if (pair.getError().hasLocked()) {
                    Lock lock = new Lock(pair.getError().getLocked());
                    locks.add(lock);
                } else {
                    throw new KeyException(pair.getError());
                }
            }
        }

        if (!locks.isEmpty()) {
            boolean ok = lockResolverClient.resolveLocks(bo, locks);
            if (!ok) {
                // if not resolve all locks, we wait and retry
                bo.doBackOff(BoTxnLockFast, new KeyException((resp.getPairsList().get(0).getError())));
            }

            // TODO: we should retry
            // fix me
            return false;
        }

        if (resp.hasRegionError()) {
            // TODO, we should redo the split and redo the batchGet
            throw new RegionException(resp.getRegionError());
        }
        return true;
    }

    public void deleteRange(BackOffer backOffer, ByteString startKey, ByteString endKey) {
        while(true) {
            Supplier<DeleteRangeRequest> factory =
                    () -> DeleteRangeRequest.newBuilder().setContext(region.getContext()).setStartKey(startKey).setEndKey(endKey).build();
            KVErrorHandler<DeleteRangeResponse> handler = new KVErrorHandler<>(
                    regionManager,
                    this,
                    region,
                    resp -> resp.hasRegionError() ? resp.getRegionError() : null);
            DeleteRangeResponse resp = callWithRetry(backOffer, TikvGrpc.METHOD_KV_DELETE_RANGE, factory, handler);
            if(deleteHelper(backOffer, resp)){
                break;
            }
        }
    }

    private boolean deleteHelper(BackOffer bo, DeleteRangeResponse resp) {
        if (resp == null){
            this.regionManager.onRequestFail(region);
            throw new TiClientInternalException("DeleteRangeResponse failed without a cause");
        }
        if(resp.hasRegionError()){
            bo.doBackOff(BoRegionMiss, new RegionException(resp.getRegionError()));
            return false;
        }
        String error = resp.getError();
        if(error != null && !error.isEmpty()){
            throw new KeyException(resp.getError());
        }
        return true;
    }

    public List<KvPair> scan(BackOffer backOffer, ByteString startKey, long version) {
        return scan(backOffer, startKey, version, false);
    }

    public List<KvPair> scan(
            BackOffer backOffer, ByteString startKey, long version, boolean keyOnly) {
        Supplier<ScanRequest> request =
                () ->
                        ScanRequest.newBuilder()
                                .setContext(region.getContext())
                                .setStartKey(startKey)
                                .setVersion(version)
                                .setKeyOnly(keyOnly)
                                .setLimit(getConf().getScanBatchSize())
                                .build();

        KVErrorHandler<ScanResponse> handler =
                new KVErrorHandler<>(
                        regionManager,
                        this,
                        region,
                        resp -> resp.hasRegionError() ? resp.getRegionError() : null);
        ScanResponse resp = callWithRetry(backOffer, TikvGrpc.METHOD_KV_SCAN, request, handler);
        return scanHelper(resp, backOffer);
    }

    // TODO: remove helper and change to while style
    // needs to be fixed as batchGet
    // which we shoule retry not throw
    // exception
    private List<KvPair> scanHelper(ScanResponse resp, BackOffer bo) {
        if (resp == null) {
            this.regionManager.onRequestFail(region);
            throw new TiClientInternalException("ScanResponse failed without a cause");
        }

        List<Lock> locks = new ArrayList<>();

        for (KvPair pair : resp.getPairsList()) {
            if (pair.hasError()) {
                if (pair.getError().hasLocked()) {
                    Lock lock = new Lock(pair.getError().getLocked());
                    locks.add(lock);
                } else {
                    throw new KeyException(pair.getError());
                }
            }
        }

        if (!locks.isEmpty()) {
            boolean ok = lockResolverClient.resolveLocks(bo, locks);
            if (!ok) {
                // if not resolve all locks, we wait and retry
                bo.doBackOff(BoTxnLockFast, new KeyException((resp.getPairsList().get(0).getError())));
            }

            // TODO: we should retry
            // fix me
        }
        if (resp.hasRegionError()) {
            throw new RegionException(resp.getRegionError());
        }
        return resp.getPairsList();
    }

    /**
     * Prewrite batch keys
     * @param bo
     * @param primaryLock
     * @param mutations
     * @param startVersion
     * @param ttl
     * @param skipConstraintCheck
     */
    public void prewrite(BackOffer bo, ByteString primaryLock, Iterable<Mutation> mutations, long startVersion, long ttl, boolean skipConstraintCheck){
        while(true) {
            Supplier<PrewriteRequest> factory =
                    () -> PrewriteRequest.newBuilder().
                            setContext(region.getContext()).
                            setStartVersion(startVersion).
                            setPrimaryLock(primaryLock).
                            addAllMutations(mutations).
                            setLockTtl(ttl).
                            setSkipConstraintCheck(skipConstraintCheck).
                            build();
            KVErrorHandler<PrewriteResponse> handler = new KVErrorHandler<>(
                    regionManager,
                    this,
                    region,
                    resp -> resp.hasRegionError() ? resp.getRegionError() : null);
            PrewriteResponse resp = callWithRetry(bo, TikvGrpc.METHOD_KV_PREWRITE, factory, handler);
            if (prewriteHelper(bo, resp)) {
                break;
            }
        }
    }

    private boolean prewriteHelper(BackOffer bo, PrewriteResponse resp) {
        if(resp == null){
            this.regionManager.onRequestFail(region);
            throw new TiClientInternalException("PrewriteResponse failed without a cause");
        }
        if (resp.hasRegionError()) {
            //bo.doBackOff(BoRegionMiss, new RegionException(resp.getRegionError()));
            //return false;
            //Caller method should retry start prewrite
            throw new RegionException(resp.getRegionError());
        }
        for(KeyError err : resp.getErrorsList()){
            if(err.hasLocked()){
                Lock lock = new Lock(err.getLocked());
                boolean ok = lockResolverClient.resolveLocks(bo, new ArrayList<>(Arrays.asList(lock)));
                if(!ok){
                    bo.doBackOff(BoTxnLockFast, new KeyException((err.getLocked().toString())));
                }
                //retry prewrite directly in current method
                return false;
            }
            else{
                throw new KeyException(err.toString());
            }
        }
        return true;
    }

    public void prewrite(BackOffer backOffer, ByteString primary, Iterable<Mutation> mutations, long startTs, long lockTTL) {
        this.prewrite(backOffer, primary, mutations, startTs, lockTTL, false);
    }

    /**
     * Commit batch keys
     * @param backOffer
     * @param keys
     * @param startVersion
     * @param commitVersion
     */
    public void commit(BackOffer backOffer, Iterable<ByteString> keys, long startVersion, long commitVersion) {
        while(true) {
            Supplier<CommitRequest> factory =
                    () -> CommitRequest.newBuilder()
                            .setStartVersion(startVersion)
                            .setCommitVersion(commitVersion)
                            .addAllKeys(keys)
                            .setContext(region.getContext()).build();
            KVErrorHandler<CommitResponse> handler =
                    new KVErrorHandler<>(
                            regionManager,
                            this,
                            region,
                            resp -> resp.hasRegionError() ? resp.getRegionError() : null);
            CommitResponse resp = callWithRetry(backOffer, TikvGrpc.METHOD_KV_COMMIT, factory, handler);
            if(commitHelper(backOffer, resp)){
                break;
            }
        }
    }

    private boolean commitHelper(BackOffer bo, CommitResponse resp) {
        if(resp == null){
            this.regionManager.onRequestFail(region);
            throw new TiClientInternalException("CommitResponse failed without a cause");
        }
        if(resp.hasRegionError()){
            //bo.doBackOff(BoRegionMiss, new RegionException(resp.getRegionError()));
            //return false;
            //Caller method should restart commit
            throw new RegionException(resp.getRegionError());
        }
        //if hasLock, need to resolveLocks and retry?
        if(resp.hasError()) {
            if (resp.getError().hasLocked()) {
                Lock lock = new Lock(resp.getError().getLocked());
                boolean ok = lockResolverClient.resolveLocks(bo, new ArrayList<>(Arrays.asList(lock)));
                if (!ok) {
                    bo.doBackOff(BoTxnLockFast, new KeyException((resp.getError().getLocked().toString())));
                }
                return false;
            } else {
                throw new KeyException(resp.getError());
            }
        }
        return true;
    }

    /**
     * Clean up the uncommitted kv data with the specified key which version equals to startTs
     * @param backOffer
     * @param key
     * @param startTs
     * @return
     */
    public long cleanup(BackOffer backOffer, ByteString key, long startTs) {
        while(true) {
            Supplier<CleanupRequest> factory =
                    () -> CleanupRequest.newBuilder()
                            .setContext(region.getContext())
                            .setKey(key)
                            .setStartVersion(startTs)
                            .build();
            KVErrorHandler<CleanupResponse> handler = new KVErrorHandler<>(
                    regionManager,
                    this,
                    region,
                    resp -> resp.hasRegionError() ? resp.getRegionError() : null);
            CleanupResponse resp = callWithRetry(backOffer, TikvGrpc.METHOD_KV_CLEANUP, factory, handler);
            if(cleanUpHelper(backOffer, resp)) {
                return resp.getCommitVersion();
            }
            // we should refresh region
            region = regionManager.getRegionByKey(key);
        }
    }

    private boolean cleanUpHelper(BackOffer bo, CleanupResponse resp) {
        if(resp == null){
            this.regionManager.onRequestFail(region);
            throw new TiClientInternalException("CleanupResponse failed without a cause");
        }
        if(resp.hasRegionError()){
            bo.doBackOff(BoRegionMiss, new RegionException(resp.getRegionError()));
            return false;
        }
        if(resp.hasError()) {
            if (resp.getError().hasLocked()) {
                Lock lock = new Lock(resp.getError().getLocked());
                boolean ok = lockResolverClient.resolveLocks(bo, new ArrayList<>(Arrays.asList(lock)));
                if (!ok) {
                    bo.doBackOff(BoTxnLockFast, new KeyException((resp.getError().getLocked().toString())));
                }
                return false;
            } else {
                throw new KeyException(resp.getError());
            }
        }
        return true;
    }

    /**
     * Batch roll back, clean up the lock information of the related key list with version equals to startVersion
     * @param backOffer
     * @param keys
     * @param startVersion
     */
    public void batchRollback(BackOffer backOffer, Iterable<ByteString> keys, long startVersion){
        while(true) {
            Supplier<BatchRollbackRequest> factory =
                    () -> BatchRollbackRequest.newBuilder().setStartVersion(startVersion)
                            .setContext(region.getContext()).addAllKeys(keys).build();
            KVErrorHandler<BatchRollbackResponse> handler =
                    new KVErrorHandler<>(
                            regionManager,
                            this,
                            region,
                            resp -> resp.hasRegionError() ? resp.getRegionError() : null);
            BatchRollbackResponse resp = callWithRetry(backOffer, TikvGrpc.METHOD_KV_BATCH_ROLLBACK, factory, handler);
            if(batchRollbackHelper(backOffer, resp)){
                break;
            }
        }
    }

    private boolean batchRollbackHelper(BackOffer bo, BatchRollbackResponse resp) {
        if(resp == null){
            this.regionManager.onRequestFail(region);
            throw new TiClientInternalException("BatchRollbackResponse failed without a cause");
        }
        if(resp.hasRegionError()){
            //bo.doBackOff(BoRegionMiss, new RegionException(resp.getRegionError()));
            //return false;
            //Caller method should restart rollback
            throw new RegionException(resp.getRegionError());
        }
        if(resp.hasError()) {
            if (resp.getError().hasLocked()) {
                Lock lock = new Lock(resp.getError().getLocked());
                boolean ok = lockResolverClient.resolveLocks(bo, new ArrayList<>(Arrays.asList(lock)));
                if (!ok) {
                    bo.doBackOff(BoTxnLockFast, new KeyException((resp.getError().getLocked().toString())));
                }
                return false;
            } else {
                throw new KeyException(resp.getError());
            }
        }
        return true;
    }

    /**
     * Delete all expired history kv data which version less than specific safePoint
     * @param bo
     * @param safePoint
     */
    public void gc(BackOffer bo, long safePoint){
        while(true) {
            Supplier<GCRequest> factory =
                    () -> GCRequest.newBuilder().setSafePoint(safePoint).setContext(region.getContext()).build();
            KVErrorHandler<GCResponse> handler =
                    new KVErrorHandler<>(
                            regionManager,
                            this,
                            region,
                            resp -> resp.hasRegionError() ? resp.getRegionError() : null);
            GCResponse resp = callWithRetry(bo, TikvGrpc.METHOD_KV_GC, factory, handler);
            if (gcHelper(bo, resp)) {
                break;
            }
        }
    }

    private boolean gcHelper(BackOffer bo, GCResponse resp) {
        if(resp == null){
            this.regionManager.onRequestFail(region);
            throw new TiClientInternalException("GCResponse failed without a cause");
        }
        if(resp.hasRegionError()){
            bo.doBackOff(BoRegionMiss, new RegionException(resp.getRegionError()));
            return false;
        }
        if(resp.hasError()) {
            if (resp.getError().hasLocked()) {
                Lock lock = new Lock(resp.getError().getLocked());
                boolean ok = lockResolverClient.resolveLocks(bo, new ArrayList<>(Arrays.asList(lock)));
                if (!ok) {
                    bo.doBackOff(BoTxnLockFast, new KeyException((resp.getError().getLocked().toString())));
                }
                return false;
            } else {
                throw new KeyException(resp.getError());
            }
        }
        return true;
    }

    /**
     * Scan all locks which version less than a specific version
     * @param bo
     * @param startKey
     * @param maxVersion
     * @param limit
     * @return
     */
    public List<LockInfo> scanLock(BackOffer bo, ByteString startKey ,long maxVersion, int limit){
        while(true) {
            Supplier<ScanLockRequest> factory =
                    () -> ScanLockRequest.newBuilder().setContext(region.getContext())
                            .setMaxVersion(maxVersion).setStartKey(startKey).setLimit(limit).build();
            KVErrorHandler<ScanLockResponse> handler =
                    new KVErrorHandler<>(
                            regionManager,
                            this,
                            region,
                            resp -> resp.hasRegionError() ? resp.getRegionError() : null);
            ScanLockResponse resp = callWithRetry(bo, TikvGrpc.METHOD_KV_SCAN_LOCK, factory, handler);
            if (scanLockHelper(bo, resp)) {
                return resp.getLocksList();
            }

            // we should refresh region
            region = regionManager.getRegionByKey(startKey);
        }
    }

    private boolean scanLockHelper(BackOffer bo, ScanLockResponse resp) {
        if(resp == null){
            this.regionManager.onRequestFail(region);
            throw new TiClientInternalException("ScanLockResponse failed without a cause");
        }
        if(resp.hasRegionError()){
            bo.doBackOff(BoRegionMiss, new RegionException(resp.getRegionError()));
            return false;
        }
        if(resp.hasError()) {
            if (resp.getError().hasLocked()) {
                Lock lock = new Lock(resp.getError().getLocked());
                boolean ok = lockResolverClient.resolveLocks(bo, new ArrayList<>(Arrays.asList(lock)));
                if (!ok) {
                    bo.doBackOff(BoTxnLockFast, new KeyException((resp.getError().getLocked().toString())));
                }
                return false;
            } else {
                throw new KeyException(resp.getError());
            }
        }
        return true;
    }
}
