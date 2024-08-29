package org.dcache.nearline.cta;

import static org.dcache.nearline.cta.CtaNearlineStorage.CTA_CA;
import static org.dcache.nearline.cta.CtaNearlineStorage.CTA_ENDPOINT;
import static org.dcache.nearline.cta.CtaNearlineStorage.CTA_GROUP;
import static org.dcache.nearline.cta.CtaNearlineStorage.CTA_INSTANCE;
import static org.dcache.nearline.cta.CtaNearlineStorage.CTA_REQUEST_JOURNAL;
import static org.dcache.nearline.cta.CtaNearlineStorage.CTA_REQUEST_TIMEOUT;
import static org.dcache.nearline.cta.CtaNearlineStorage.CTA_TLS;
import static org.dcache.nearline.cta.CtaNearlineStorage.CTA_USER;
import static org.dcache.nearline.cta.CtaNearlineStorage.IO_PORT;
import static org.dcache.nearline.cta.TestUtils.generateSelfSignedCert;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.Futures;
import diskCacheV111.util.CacheException;
import diskCacheV111.vehicles.GenericStorageInfo;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import org.bouncycastle.operator.OperatorCreationException;
import org.dcache.namespace.FileAttribute;
import org.dcache.pool.nearline.spi.FlushRequest;
import org.dcache.pool.nearline.spi.RemoveRequest;
import org.dcache.pool.nearline.spi.StageRequest;
import org.dcache.util.Checksum;
import org.dcache.util.ChecksumType;
import org.dcache.vehicles.FileAttributes;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class CtaNearlineStorageTest {

    private DummyCta cta;
    private Map<String, String> drvConfig;
    private CtaNearlineStorage driver;

    private CompletableFuture<Void> waitForComplete;

    private static File keyFile;
    private static File certFile;

    @BeforeClass
    public static void setUpGlobal()
          throws IOException, GeneralSecurityException, OperatorCreationException {
        keyFile = File.createTempFile("hostkey-", ".pem");
        certFile = File.createTempFile("hostcert-", ".pem");
        generateSelfSignedCert(certFile, keyFile);
    }

    @AfterClass
    public static void tierDownGlobal() {
        keyFile.delete();
        certFile.delete();
    }

    @Before
    public void setUp() throws Exception {

        cta = new DummyCta(certFile, keyFile);
        cta.start();

        // make mutable config
        drvConfig = new HashMap<>(
              Map.of(
                    CTA_USER, "foo",
                    CTA_GROUP, "bar",
                    CTA_INSTANCE, "foobar",
                    CTA_ENDPOINT, cta.getConnectString(),
                    IO_PORT, "9991",
                    CTA_TLS, "true",
                    CTA_CA, certFile.getAbsolutePath(),
                    CTA_REQUEST_TIMEOUT, "3"
              )
        );
    }

    @After
    public void tierDown() {
        try {
            cta.shutdown();
        } catch (InterruptedException e) {
        }
        if (driver != null) {
            driver.shutdown();
        }
    }

    @Test(expected = NullPointerException.class)
    public void testMissingHsmType() {
        new CtaNearlineStorage(null, "aName");
    }

    @Test(expected = NullPointerException.class)
    public void testMissingHsmName() {
        new CtaNearlineStorage("aType", null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCfgMissingInstance() {

        driver = new CtaNearlineStorage("aType", "aName");

        drvConfig.remove(CTA_INSTANCE);
        driver.configure(drvConfig);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCfgMissingUser() {

        driver = new CtaNearlineStorage("aType", "aName");

        drvConfig.remove(CTA_USER);
        driver.configure(drvConfig);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCfgMissingGroup() {

        driver = new CtaNearlineStorage("aType", "aName");
        drvConfig.remove(CTA_GROUP);
        driver.configure(drvConfig);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCfgMissingEndpoint() {

        driver = new CtaNearlineStorage("aType", "aName");

        drvConfig.remove(CTA_ENDPOINT);
        driver.configure(drvConfig);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCfgInvalidEndpoint() {

        driver = new CtaNearlineStorage("aType", "aName");

        drvConfig.put(CTA_ENDPOINT, "localhost");
        driver.configure(drvConfig);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidTimeoutValue() {
        driver = new CtaNearlineStorage("aType", "aName");
        drvConfig.put(CTA_REQUEST_TIMEOUT, "foo");
        driver.configure(drvConfig);
    }

    @Test
    public void testCfgAcceptValid() {

        driver = new CtaNearlineStorage("aType", "aName");
        driver.configure(drvConfig);
        driver.start();
    }

    @Test
    public void testWithoutTLS() {

        driver = new CtaNearlineStorage("aType", "aName");
        drvConfig.put(CTA_TLS, "false");

        driver.configure(drvConfig);
        driver.start();

        var request = mockedFlushRequest();
        driver.flush(Set.of(request));
        verify(request).failed(any(CacheException.class));
    }

    @Test
    public void testFlushRequestActivationOnSubmit() {

        var request = mockedFlushRequest();
        driver = new CtaNearlineStorage("foo", "bar");
        driver.configure(drvConfig);
        driver.start();

        driver.flush(Set.of(request));

        verify(request).activate();
    }

    @Test
    public void testStageRequestActivationOnSubmit() {

        var request = mockedStageRequest();
        driver = new CtaNearlineStorage("foo", "bar");
        driver.configure(drvConfig);
        driver.start();

        driver.stage(Set.of(request));

        verify(request).activate();
    }

    @Test
    public void testSpaceAllocationOnSubmit() {

        var request = mockedStageRequest();
        driver = new CtaNearlineStorage("foo", "bar");
        driver.configure(drvConfig);
        driver.start();

        driver.stage(Set.of(request));

        verify(request).allocate();
    }

    @Test
    public void testFlushRequestFailActivation() {

        var request = mockedFlushRequest();
        when(request.activate()).thenReturn(
              Futures.immediateFailedFuture(new IOException("injected error")));

        driver = new CtaNearlineStorage("foo", "bar");
        driver.configure(drvConfig);
        driver.start();

        driver.flush(Set.of(request));

        verify(request).failed(any());
    }

    @Test
    public void testStageRequestFailActivation() {

        var request = mockedStageRequest();
        when(request.activate()).thenReturn(
              Futures.immediateFailedFuture(new IOException("injected error")));

        driver = new CtaNearlineStorage("foo", "bar");
        driver.configure(drvConfig);
        driver.start();

        driver.stage(Set.of(request));

        verify(request).failed(any());
    }

    @Test
    public void testStageRequestFailAllocation() {

        var request = mockedStageRequest();
        when(request.allocate()).thenReturn(
              Futures.immediateFailedFuture(new IOException("injected error")));

        driver = new CtaNearlineStorage("foo", "bar");
        driver.configure(drvConfig);
        driver.start();

        driver.stage(Set.of(request));

        verify(request).failed(any());
    }

    @Test
    public void testStartAfterShutdown() {

        driver = new CtaNearlineStorage("aType", "aName");
        driver.configure(drvConfig);
        driver.start();
        driver.shutdown();

        driver = new CtaNearlineStorage("aType", "aName");
        driver.configure(drvConfig);
        driver.start();
        driver.shutdown();
    }

    @Test
    public void testStageRequestFailOnRpcError() {

        var request = mockedStageRequest();

        driver = new CtaNearlineStorage("foo", "bar");
        driver.configure(drvConfig);
        driver.start();

        cta.fail();
        driver.stage(Set.of(request));

        verify(request).failed(any());
    }

    @Test
    public void testFlushRequestFailOnRpcError() {

        var request = mockedFlushRequest();

        driver = new CtaNearlineStorage("foo", "bar");
        driver.configure(drvConfig);
        driver.start();

        cta.fail();
        driver.flush(Set.of(request));

        verify(request).failed(any());
    }

    @Test
    public void testFlushOfEmptyFile() {

        var request = mockedFlushRequest();
        request.getFileAttributes().setSize(0L);

        driver = new CtaNearlineStorage("foo", "bar");
        driver.configure(drvConfig);
        driver.start();

        driver.flush(Set.of(request));
        // TODO: check for correct URI
        verify(request).completed(any());
    }


    @Test
    public void testFlushWithoutChecksum() {

        var request = mockedFlushRequest();
        request.getFileAttributes().undefine(FileAttribute.CHECKSUM);

        driver = new CtaNearlineStorage("foo", "bar");
        driver.configure(drvConfig);
        driver.start();

        driver.flush(Set.of(request));
        verify(request).failed(anyInt(), any());
    }

    @Test
    public void testSuccessOnRemove() {

        var request = mockedRemoveRequest();

        driver = new CtaNearlineStorage("foo", "bar");
        driver.configure(drvConfig);
        driver.start();

        driver.remove(Set.of(request));

        verify(request).completed(any());
    }

    @Test
    public void testRemoveZeroByteFiles() {

        var request = mockedRemoveRequest();
        when(request.getUri()).thenReturn(
                URI.create("cta://cta/0000C9B4E3768770452E8B1B8E0232584872?archiveid=*"));

        driver = new CtaNearlineStorage("foo", "bar");
        driver.configure(drvConfig);
        driver.start();

        driver.remove(Set.of(request));

        verify(request).completed(any());
        // ensure that shortcut is used
        verify(cta.ctaSvc(), never()).delete(any(), any());
    }

    @Test
    public void testRemoveRequestOnRpcError() {

        var request = mockedRemoveRequest();

        driver = new CtaNearlineStorage("foo", "bar");
        driver.configure(drvConfig);
        driver.start();
        cta.fail();

        driver.remove(Set.of(request));

        verify(request).failed(any());
    }

    @Test
    public void testPendingRequestIncrementOnStageSubmit() {

        var request = mockedStageRequest();
        driver = new CtaNearlineStorage("foo", "bar");
        driver.configure(drvConfig);
        driver.start();

        driver.stage(Set.of(request));
        assertEquals("unexpected pending request queue size", 1,
              driver.getPendingRequestsCount());
    }

    @Test
    public void testPendingRequestDecOnStageComplete() {

        var request = mockedStageRequest();
        driver = new CtaNearlineStorage("foo", "bar");
        driver.configure(drvConfig);
        driver.start();

        driver.stage(Set.of(request));

        driver.getRequest("0000C9B4E3768770452E8B1B8E0232584872").getRequest().completed(Set.of());
        assertEquals("pending request count not zero", 0, driver.getPendingRequestsCount());
    }

    @Test
    public void testPendingRequestDecOnFlushComplete() {

        var request = mockedFlushRequest();
        driver = new CtaNearlineStorage("foo", "bar");
        driver.configure(drvConfig);
        driver.start();

        driver.flush(Set.of(request));

        driver.getRequest("0000C9B4E3768770452E8B1B8E0232584872").getRequest().completed(Set.of());
        assertEquals("pending request count not zero", 0, driver.getPendingRequestsCount());
    }

    @Test
    public void testPendingRequestDecOnStageFailedV1() {

        var request = mockedStageRequest();
        driver = new CtaNearlineStorage("foo", "bar");
        driver.configure(drvConfig);
        driver.start();

        driver.stage(Set.of(request));

        driver.getRequest("0000C9B4E3768770452E8B1B8E0232584872").getRequest().failed(new Exception());
        assertEquals("pending request count not zero", 0, driver.getPendingRequestsCount());
    }

    @Test
    public void testPendingRequestDecOnStageFailedV2() {

        var request = mockedStageRequest();
        driver = new CtaNearlineStorage("foo", "bar");
        driver.configure(drvConfig);
        driver.start();

        driver.stage(Set.of(request));

        driver.getRequest("0000C9B4E3768770452E8B1B8E0232584872").getRequest().failed(1, "foo");
        assertEquals("pending request count not zero", 0, driver.getPendingRequestsCount());
    }

    @Test
    public void testPendingRequestIncrementOnFlushSubmit() {

        var request = mockedFlushRequest();
        driver = new CtaNearlineStorage("foo", "bar");
        driver.configure(drvConfig);
        driver.start();

        driver.flush(Set.of(request));

        assertEquals("unexpected pending request queue size", 1,
              driver.getPendingRequestsCount());
    }

    @Test
    public void testPendingRequestDecOnFlushFailedV1() {

        var request = mockedFlushRequest();
        driver = new CtaNearlineStorage("foo", "bar");
        driver.configure(drvConfig);
        driver.start();

        driver.flush(Set.of(request));

        driver.getRequest("0000C9B4E3768770452E8B1B8E0232584872").getRequest().failed(new Exception());
        assertEquals("pending request count not zero", 0, driver.getPendingRequestsCount());
    }

    @Test
    public void testPendingRequestDecOnFlushFailedV2() {

        var request = mockedFlushRequest();
        driver = new CtaNearlineStorage("foo", "bar");
        driver.configure(drvConfig);
        driver.start();

        driver.flush(Set.of(request));

        driver.getRequest("0000C9B4E3768770452E8B1B8E0232584872").getRequest().failed(1, "foo");
        assertEquals("pending request count not zero", 0, driver.getPendingRequestsCount());
    }

    @Test
    public void testCencelOfPendingRequest() {

        var request = mockedStageRequest();
        driver = new CtaNearlineStorage("foo", "bar");
        driver.configure(drvConfig);
        driver.start();

        driver.stage(Set.of(request));

        driver.cancel(request.getId());
        assertEquals("unexpected pending request queue size", 0, driver.getPendingRequestsCount());
    }

    @Test
    public void testCencelByRandomUUID() {

        var request = mockedStageRequest();
        driver = new CtaNearlineStorage("foo", "bar");
        driver.configure(drvConfig);
        driver.start();

        driver.stage(Set.of(request));
        driver.cancel(UUID.randomUUID());
        assertEquals("unexpected pending request queue size", 1, driver.getPendingRequestsCount());
    }

    @Test
    public void testCancelOfPendingStageRequest() {

        var request = mockedStageRequest();
        driver = new CtaNearlineStorage("foo", "bar");
        driver.configure(drvConfig);
        driver.start();

        driver.stage(Set.of(request));
        driver.cancel(request.getId());
        verify(request).failed(any(CancellationException.class));
    }

    @Test
    public void testCancelOfPendingStageRequestOnError() {

        var request = mockedStageRequest();
        driver = new CtaNearlineStorage("foo", "bar");
        driver.configure(drvConfig);
        driver.start();

        driver.stage(Set.of(request));

        cta.fail();
        driver.cancel(request.getId());
        verify(request, times(0)).failed(any(CancellationException.class));
        verify(request, times(0)).failed(anyInt(), any());
        verify(request, times(0)).completed(any());
    }

    @Test
    public void testCancelOfPendingFlushRequest() {

        var request = mockedFlushRequest();
        driver = new CtaNearlineStorage("foo", "bar");
        driver.configure(drvConfig);
        driver.start();

        driver.flush(Set.of(request));
        driver.cancel(request.getId());

        verify(request).failed(any(CancellationException.class));
    }

    @Test
    public void testCancelOfPendingFlushRequestOnError() {

        var request = mockedFlushRequest();
        driver = new CtaNearlineStorage("foo", "bar");
        driver.configure(drvConfig);
        driver.start();

        driver.flush(Set.of(request));

        cta.fail();

        driver.cancel(request.getId());
        verify(request, times(0)).failed(any(CancellationException.class));
        verify(request, times(0)).failed(anyInt(), any());
        verify(request, times(0)).completed(any());
    }

    @Test
    public void testDontSubmitFailedFlushToCta() {

        var request = mockedFlushRequest();
        when(request.activate()).thenReturn(
              Futures.immediateFailedFuture(new IOException("Failed to active request")));

        driver = new CtaNearlineStorage("foo", "bar");
        driver.configure(drvConfig);
        driver.start();

        driver.flush(Set.of(request));
        assertEquals("pending request count not zero", 0, driver.getPendingRequestsCount());
    }

    @Test
    public void testDontSubmitFailedStageToCta() {

        var request = mockedStageRequest();
        when(request.activate()).thenReturn(
              Futures.immediateFailedFuture(new IOException("Failed to active request")));

        driver = new CtaNearlineStorage("foo", "bar");
        driver.configure(drvConfig);
        driver.start();

        driver.stage(Set.of(request));
        assertEquals("pending request count not zero", 0, driver.getPendingRequestsCount());
    }

    @Test
    public void testFailOnLostStageMessage() {

        var request = mockedStageRequest();
        driver = new CtaNearlineStorage("foo", "bar");
        driver.configure(drvConfig);
        driver.start();

        cta.drop();
        driver.stage(Set.of(request));

        verify(request, times(1)).failed(any(CacheException.class));
    }


    @Test
    public void testFailOnLostFlushMessage() {

        var request = mockedFlushRequest();
        driver = new CtaNearlineStorage("foo", "bar");
        driver.configure(drvConfig);
        driver.start();

        cta.drop();
        driver.flush(Set.of(request));

        verify(request, times(1)).failed(any(CacheException.class));
    }

    @Test
    public void testFailOnLostDeleteMessage() {

        var request = mockedRemoveRequest();
        driver = new CtaNearlineStorage("foo", "bar");
        driver.configure(drvConfig);
        driver.start();

        cta.drop();
        driver.remove(Set.of(request));

        verify(request, times(1)).failed(any(CacheException.class));
    }

    @Test
    public void testCleanupJournalInitialization() {

        var request = mockedStageRequest();
        driver = new CtaNearlineStorage("foo", "bar");
        drvConfig.put(CTA_REQUEST_JOURNAL, "/tmp/cta-journal");
        driver.configure(drvConfig);
        driver.start();

        driver.stage(Set.of(request));
    }

    private FlushRequest mockedFlushRequest() {

        var attrs = FileAttributes.of()
              .size(9876543210L)
              .storageClass("a:b")
              .hsm("z")
              .creationTime(System.currentTimeMillis())
              .pnfsId("0000C9B4E3768770452E8B1B8E0232584872")
              .checksum(new Checksum(ChecksumType.ADLER32.createMessageDigest()))
              .build();

        var request = mock(FlushRequest.class);

        when(request.activate()).thenReturn(Futures.immediateFuture(null));
        when(request.getFileAttributes()).thenReturn(attrs);
        when(request.getId()).thenReturn(UUID.randomUUID());

        waitForComplete = new CompletableFuture<>();

        doAnswer(i -> {
            waitForComplete.complete(null);
            return null;
        }).when(request).failed(any());

        doAnswer(i -> {
            waitForComplete.complete(null);
            return null;
        }).when(request).failed(anyInt(), any());

        doAnswer(i -> {
            waitForComplete.complete(null);
            return null;
        }).when(request).completed(any());

        return request;
    }

    private StageRequest mockedStageRequest() {

        var storageInfo = GenericStorageInfo.valueOf("a:b@z", "*");
        storageInfo.addLocation(URI.create("cta://cta?archiveid=9876543210"));

        var attrs = FileAttributes.of()
              .size(9876543210L)
              .storageClass(storageInfo.getStorageClass())
              .hsm(storageInfo.getHsm())
              .storageInfo(storageInfo)
              .creationTime(System.currentTimeMillis())
              .pnfsId("0000C9B4E3768770452E8B1B8E0232584872")
              .build();

        var request = mock(StageRequest.class);

        when(request.activate()).thenReturn(Futures.immediateFuture(null));
        when(request.allocate()).thenReturn(Futures.immediateFuture(null));
        when(request.getFileAttributes()).thenReturn(attrs);
        when(request.getId()).thenReturn(UUID.randomUUID());

        waitForComplete = new CompletableFuture<>();

        doAnswer(i -> {
            waitForComplete.complete(null);
            return null;
        }).when(request).failed(any());

        doAnswer(i -> {
            waitForComplete.complete(null);
            return null;
        }).when(request).failed(anyInt(), any());

        doAnswer(i -> {
            waitForComplete.complete(null);
            return null;
        }).when(request).completed(any());

        return request;
    }

    private RemoveRequest mockedRemoveRequest() {

        var request = mock(RemoveRequest.class);

        when(request.getUri()).thenReturn(
              URI.create("cta://cta/0000C9B4E3768770452E8B1B8E0232584872?archiveid=1234"));

        waitForComplete = new CompletableFuture<>();

        doAnswer(i -> {
            waitForComplete.complete(null);
            return null;
        }).when(request).failed(any());

        doAnswer(i -> {
            waitForComplete.complete(null);
            return null;
        }).when(request).failed(anyInt(), any());

        doAnswer(i -> {
            waitForComplete.complete(null);
            return null;
        }).when(request).completed(any());
        return request;
    }

}