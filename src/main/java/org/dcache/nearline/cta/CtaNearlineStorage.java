package org.dcache.nearline.cta;

import static com.google.common.base.Preconditions.checkArgument;

import ch.cern.cta.rpc.CtaRpcGrpc;
import ch.cern.cta.rpc.SchedulerRequest;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.net.HostAndPort;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.protobuf.Empty;
import diskCacheV111.util.CacheException;
import io.grpc.ChannelCredentials;
import io.grpc.ConnectivityState;
import io.grpc.Deadline;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsChannelCredentials;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.channel.nio.NioEventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.socket.nio.NioSocketChannel;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import ch.cern.cta.rpc.CtaRpcGrpc.CtaRpcBlockingStub;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.dcache.namespace.FileAttribute;
import org.dcache.nearline.cta.xrootd.DataMover;
import org.dcache.pool.nearline.spi.FlushRequest;
import org.dcache.pool.nearline.spi.NearlineStorage;
import org.dcache.pool.nearline.spi.RemoveRequest;
import org.dcache.pool.nearline.spi.StageRequest;
import org.dcache.util.Checksum;
import org.dcache.util.ChecksumType;
import org.dcache.vehicles.FileAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CtaNearlineStorage implements NearlineStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger(CtaNearlineStorage.class);

    public static final String CTA_INSTANCE = "cta-instance-name";
    public static final String CTA_ENDPOINT = "cta-frontend-addr";
    public static final String CTA_USER = "cta-user";
    public static final String CTA_GROUP = "cta-group";
    public static final String CTA_TLS = "cta-use-tls";
    public static final String CTA_CA = "cta-ca-chain";
    public static final String IO_ENDPOINT = "io-endpoint";
    public static final String IO_PORT = "io-port";
    public static final String CTA_REQUEST_TIMEOUT = "cta-frontend-timeout";
    public static final String CTA_REQUEST_JOURNAL = "cleanup-journal";

    protected final String type;
    protected final String name;


    /**
     * User name associated with the requests on the CTA side.
     */
    private String ctaUser;

    /**
     * Group name associated with the request on the CTA side.
     */
    private String ctaGroup;

    /**
     * dCache instance identifier within CTA
     */
    private String instanceName;

    /**
     * Foatory, that converts dCache requests to corresponding gRPC messages.
     */
    private RequestsFactory ctaRequestFactory;

    /**
     * CTA gRPC frontend service.
     */
    private CtaRpcBlockingStub cta;

    /**
     * Data mover used to read or write files by CTA.
     */
    private DataMover dataMover;

    /**
     * gRPC communication channel.
     */
    private ManagedChannel channel;

    /**
     * Socket address to for IO by CTA.
     */
    private InetSocketAddress ioSocketAddress;

    /**
     * CTA frontend.
     */
    private HostAndPort ctaEndpoint;

    /**
     * Requests submitted to CTA.
     */
    private final ConcurrentMap<String, PendingRequest> pendingRequests = new ConcurrentHashMap<>();

    /**
     * Path to CA-chain that when TLS is used
     */
    private File caRootChain;

    /**
     * Should TLS be used for gRPC requests
     */
    private boolean useTls;

    /**
     * CTA frontend request timeout.
     */
    private int ctaRequestTimeoutInSec = 30;


    /**
     * Pending request to CTA.
     */
    private CleanupJournal pendingRequestsTracker;

    public CtaNearlineStorage(String type, String name) {

        Objects.requireNonNull(type, "HSM type is not provided");
        Objects.requireNonNull(name, "HSM name is not provided");

        this.type = type;
        this.name = name;
    }

    /**
     * How long we should expect CTA frontend to respond.
     *
     * <p>
     * NOTE: as deadline is a point in time (not a timeout per request), we have to set it for each
     * request.
     */
    private Deadline getRequestDeadline() {
        return Deadline.after(ctaRequestTimeoutInSec, TimeUnit.SECONDS);
    }

    /**
     * Flush all files in {@code requests} to nearline storage.
     *
     * @param requests
     */
    @Override
    public void flush(Iterable<FlushRequest> requests) {

        for (FlushRequest fr : requests) {

            try {
                fr.activate().get();

                // don't flush zero-byte files
                if (fr.getFileAttributes().getSize() == 0) {
                    LOGGER.info("Fake flush of zero-byte file {}", fr.getFileAttributes().getPnfsId());
                    fr.completed(Set.of(createZeroFileUri(fr.getFileAttributes())));
                    continue;
                }

                // CTA can't flush files without checksum, thus, even don't try
                if (!fr.getFileAttributes().isDefined(FileAttribute.CHECKSUM) ||
                        fr.getFileAttributes().getChecksums().isEmpty()) {
                    LOGGER.warn("Can't flush file {} - no checksum", fr.getFileAttributes().getPnfsId());
                    fr.failed(CacheException.NO_ATTRIBUTE, "Can't flush files without checksum");
                    continue;
                }

                var createRequest = ctaRequestFactory.valueOf(fr.getFileAttributes());
                var createResponse =  cta.withDeadline(getRequestDeadline()).create(createRequest);
                LOGGER.info("{}: Create new archive id {} for {}",  fr.getId(),
                        createResponse.getArchiveFileId(),
                        fr.getFileAttributes().getPnfsId()
                );

                submitFlush(fr, createResponse.getArchiveFileId());

            } catch (Exception e) {
                Throwable t = Throwables.getRootCause(e);
                LOGGER.error("Failed to activate or submit flush request: {}", t.getMessage());
                fr.failed(asCacheException(t));
            }
        }

    }

    /**
     * Flush all files in {@code requests} to nearline storage.
     *
     * @param fr
     * @param ctaArchiveId
     */
    public void submitFlush(FlushRequest fr, long ctaArchiveId) {

        var id = fr.getFileAttributes().getPnfsId().toString();

        var r = new ForwardingFlushRequest() {
            @Override
            protected FlushRequest delegate() {
                return fr;
            }

            @Override
            public void failed(Exception e) {
                pendingRequests.remove(id);
                pendingRequestsTracker.remove(id);
                super.failed(e);
            }

            @Override
            public void failed(int i, String s) {
                pendingRequests.remove(id);
                pendingRequestsTracker.remove(id);
                super.failed(i, s);
            }

            @Override
            public void completed(Set<URI> uris) {
                pendingRequests.remove(id);
                pendingRequestsTracker.remove(id);
                super.completed(uris);
            }
        };

        var ar = ctaRequestFactory.valueOf(r, ctaArchiveId);
        var response = cta.withDeadline(getRequestDeadline()).archive(ar);

        LOGGER.info("{} : {} : archive id {}, request: {}",
                r.getId(),
                r.getFileAttributes().getPnfsId(),
                ctaArchiveId,
                response.getObjectstoreId()
        );

        var cancelRequest = SchedulerRequest.newBuilder(ar)
                .setObjectstoreId(response.getObjectstoreId())
                .build();
        pendingRequestsTracker.put(id, cancelRequest);
        pendingRequests.put(id, new PendingRequest(r) {
                    @Override
                    public void cancel() {
                        try {
                            cta.withDeadline(getRequestDeadline()).delete(cancelRequest);
                            super.cancel();
                        } catch (StatusRuntimeException e) {
                            LOGGER.error("Failed to cancel flush request: {}", e.getMessage());
                        }
                    }
                }
        );
    }

    /**
     * Stage all files in {@code requests} from nearline storage.
     *
     * @param requests
     */
    @Override
    public void stage(Iterable<StageRequest> requests) {
        for (var sr : requests) {

            var id = sr.getFileAttributes().getPnfsId().toString();
            var r = new ForwardingStageRequest() {
                @Override
                protected StageRequest delegate() {
                    return sr;
                }

                @Override
                public void failed(Exception e) {
                    pendingRequests.remove(id);
                    pendingRequestsTracker.remove(id);
                    super.failed(e);
                }

                @Override
                public void failed(int i, String s) {
                    pendingRequests.remove(id);
                    pendingRequestsTracker.remove(id);
                    super.failed(i, s);
                }

                @Override
                public void completed(Set<Checksum> checksums) {
                    pendingRequests.remove(id);
                    pendingRequestsTracker.remove(id);
                    super.completed(checksums);
                }
            };

            try {
                r.activate().get();
                r.allocate().get();

                // no need to stage an empty files
                if (sr.getFileAttributes().getSize() == 0) {
                    new File(sr.getReplicaUri().getPath()).createNewFile();
                    sr.completed(Set.of(new Checksum(ChecksumType.ADLER32.createMessageDigest())));
                    continue;
                }

                var rr = ctaRequestFactory.valueOf(r);
                var response = cta.withDeadline(getRequestDeadline()).retrieve(rr);
                LOGGER.info("{} : {} : request: {}",
                        r.getId(),
                        r.getFileAttributes().getPnfsId(),
                        response.getObjectstoreId()
                );

                var cancelRequest = SchedulerRequest.newBuilder(rr)
                        .setObjectstoreId(response.getObjectstoreId())
                        .build();
                pendingRequestsTracker.put(id, cancelRequest);

                pendingRequests.put(id, new PendingRequest(r) {
                            @Override
                            public void cancel() {
                                // on cancel send the request to CTA; on success cancel the requests
                                try {
                                    cta.withDeadline(getRequestDeadline()).cancelRetrieve(cancelRequest);
                                    super.cancel();
                                } catch (StatusRuntimeException e) {
                                    LOGGER.error("Failed to cancel retrieve request: {}", e.getMessage());
                                }
                            }
                        }
                );

            } catch (IOException | ExecutionException | InterruptedException | StatusRuntimeException e) {
                Throwable t = Throwables.getRootCause(e);
                LOGGER.error("Failed to activate / allocate submit space for / submit  retrieve request: {}",
                      t.getMessage());
                r.failed(asCacheException(t));
            }
        }
    }

    /**
     * Delete all files in {@code requests} from nearline storage.
     *
     * @param requests
     */
    @Override
    public void remove(Iterable<RemoveRequest> requests) {

        for (var r : requests) {

            // short-circuit zero-byte files
            if (r.getUri().getQuery().contains("archiveid=*")) {
                r.completed(null);
                continue;
            }

            try {
                var deleteRequest = ctaRequestFactory.valueOf(r);
                var result = cta.withDeadline(getRequestDeadline()).delete(deleteRequest);
                LOGGER.info("Delete request {} submitted {}",
                        r.getId(),
                        r.getUri()
                );
                r.completed(null);
            } catch (Exception e) {
                LOGGER.error("Failed to submit stage request {}", e.getMessage());
                r.failed(asCacheException(e));
            }
        }
    }

    /**
     * Cancel any flush, stage or remove request with the given id.
     *
     * <p>The failed method of any cancelled request should be called with a CancellationException.
     * If the request completes before it can be cancelled, then the cancellation should be ignored
     * and the completed or failed method should be called as appropriate.
     *
     * <p>A call to cancel must be non-blocking.
     *
     * @param uuid id of the request to cancel
     */
    @Override
    public void cancel(UUID uuid) {
        var requestIterator = pendingRequests.entrySet().iterator();
        while (requestIterator.hasNext()) {
            var pendingRequest = requestIterator.next().getValue();
            if (pendingRequest.getRequestId().equals(uuid)) {
                pendingRequest.cancel();
                // no other matches expected.
                break;
            }
        }
    }

    /**
     * Applies a new configuration.
     *
     * @param properties
     * @throws IllegalArgumentException if the configuration is invalid
     */
    @Override
    public void configure(Map<String, String> properties) throws IllegalArgumentException {

        // mandatory options
        String instance = properties.get(CTA_INSTANCE);
        String endpoint = properties.get(CTA_ENDPOINT);
        String user = properties.get(CTA_USER);
        String group = properties.get(CTA_GROUP);
        String timeoutString = properties.get(CTA_REQUEST_TIMEOUT);
        String journal = properties.get(CTA_REQUEST_JOURNAL);

        checkArgument(instance != null, "dCache instance name is not set.");
        checkArgument(endpoint != null, "CTA frontend is not set.");
        checkArgument(user != null, "CTA user is not set.");
        checkArgument(group != null, "CTA group is not set.");

        ctaEndpoint = HostAndPort.fromString(endpoint);
        checkArgument(ctaEndpoint.hasPort(), "Port is not provided for CTA frontend");

        ctaUser = user;
        ctaGroup = group;
        instanceName = instance;

        // Optional options
        String localEndpoint = properties.get(IO_ENDPOINT);
        int localPort = Integer.parseInt(properties.getOrDefault(IO_PORT, "0"));
        if (localEndpoint != null) {
            ioSocketAddress = new InetSocketAddress(localEndpoint, localPort);
        } else {
            ioSocketAddress = new InetSocketAddress(localPort);
        }

        useTls = Boolean.parseBoolean(properties.getOrDefault(CTA_TLS, "false"));
        if (useTls) {
            var caPath = properties.get(CTA_CA);
            if (caPath != null) {
                caRootChain = new File(caPath);
            }
        }

        if (timeoutString != null) {
            ctaRequestTimeoutInSec = Integer.parseInt(timeoutString);
        }

        if (journal != null) {
            pendingRequestsTracker = new DigitalMwai(journal);
        } else {
            pendingRequestsTracker = new NopCleanupJournal();
        }
    }

    @Override
    public void start() {

        dataMover = new DataMover(type, name, ioSocketAddress,       pendingRequests);
        dataMover.startAsync().awaitRunning();

        ChannelCredentials credentials;
        if (useTls) {
            var tlsCredBuilder = TlsChannelCredentials.newBuilder();
            if (caRootChain != null) {
                try {
                    tlsCredBuilder.trustManager(caRootChain);
                } catch (IOException e) {
                    throw new IllegalArgumentException("Can't load root-ca chain file", e);
                }
            }
            credentials = tlsCredBuilder.build();
        } else {
            credentials = InsecureChannelCredentials.create();
        }

        channel = NettyChannelBuilder.forAddress(ctaEndpoint.getHost(), ctaEndpoint.getPort(),
                    credentials)
              .disableServiceConfigLookUp() // no lookup in DNS for service record
              .channelType(NioSocketChannel.class) // use Nio event loop instead of epoll
              .eventLoopGroup(new NioEventLoopGroup(0,
                    new ThreadFactoryBuilder().setNameFormat("cta-grpc-worker-%d").build()))
              .executor(Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()*2,
                    new ThreadFactoryBuilder().setNameFormat("cta-grpc-callback-runner-%d").build()))
              .build();


        cta = CtaRpcGrpc.newBlockingStub(channel);
        channel.notifyWhenStateChanged(ConnectivityState.CONNECTING, () -> {
            try {
                var version = cta.withDeadline(getRequestDeadline()).version(Empty.newBuilder().build());
                LOGGER.info("Connected to CTA version {}", version.getCtaVersion());
            } catch (Exception e) {
                LOGGER.error("Failed to get CTA version {}", e.getMessage());
            }
                }
        );

        pendingRequestsTracker.cleanup(cta, (p, r) -> {
            LOGGER.warn("[Mwai {}]: Found entry for {} : {}", Character.toString(0x1F631), p, r.getObjectstoreId());
            return Boolean.TRUE;
        });

        ctaRequestFactory = new RequestsFactory(instanceName, ctaUser, ctaGroup, dataMover);
    }


    /**
     * Cancels all requests and initiates a shutdown of the nearline storage interface.
     *
     * <p>This method does not wait for actively executing requests to terminate.
     */
    @Override
    public void shutdown() {
        if (dataMover != null) {
            dataMover.stopAsync().awaitTerminated();
        }

        if (channel != null) {
            channel.shutdown();
        }
    }

    @VisibleForTesting
    int getPendingRequestsCount() {
        return pendingRequests.size();
    }

    @VisibleForTesting
    PendingRequest getRequest(String id) {
        return pendingRequests.get(id);
    }


    /**
     * Convert a given Throwable into CacheException to ensure serialization.
     * @param e origianl exception.
     * @return corresponding cache exception
     */
    private CacheException asCacheException(Throwable e) {

        if (e.getClass().isAssignableFrom(CacheException.class)) {
            return CacheException.class.cast(e);
        }

        return new CacheException(CacheException.UNEXPECTED_SYSTEM_EXCEPTION, e.toString());
    }

    private URI createZeroFileUri(FileAttributes attrs) {
        return URI.create(type + "://" + name + "/" + attrs.getPnfsId() + "?archiveid=*");
    }
}
