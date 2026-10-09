/*******************************************************************************
 * Copyright (c) 2025 Eurotech and/or its affiliates and others
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Eurotech
 *******************************************************************************/
package org.eclipse.kura.core.deployment.download.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import org.bouncycastle.asn1.x500.X500Name;
import org.eclipse.kura.core.deployment.CloudDeploymentHandlerV2;
import org.eclipse.kura.core.deployment.DownloadStatus;
import org.eclipse.kura.core.deployment.download.DeploymentPackageDownloadOptions;
import org.eclipse.kura.core.ssl.SslManagerServiceImpl;
import org.eclipse.kura.core.testutil.TestUtil;
import org.eclipse.kura.core.testutil.pki.TestCA;
import org.eclipse.kura.core.testutil.pki.TestCA.CertificateCreationOptions;
import org.eclipse.kura.security.keystore.KeystoreService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.service.component.ComponentContext;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

class DownloadImplITTest {

    @TempDir
    Path directory;

    private final boolean originalFollowRedirects = HttpURLConnection.getFollowRedirects();
    private HttpsServer server;
    private ExecutorService serverExecutor;
    private SslManagerServiceImpl sslManager;
    private ComponentContext componentContext;
    private DownloadImpl download;
    private final CloudDeploymentHandlerV2 callback = mock(CloudDeploymentHandlerV2.class);
    private final DownloadTestRestService resource = new DownloadTestRestService();

    @AfterEach
    void closeResources() throws Throwable {
        try {
            if (this.download != null && this.download.getDownloadHelper() != null) {
                this.download.getDownloadHelper().cancelDownload();
            }
        } finally {
            HttpURLConnection.setFollowRedirects(this.originalFollowRedirects);
            if (this.server != null) {
                this.server.stop(0);
            }
            if (this.serverExecutor != null) {
                this.serverExecutor.shutdownNow();
                assertTrue(this.serverExecutor.awaitTermination(5, TimeUnit.SECONDS));
            }
            if (this.sslManager != null) {
                TestUtil.invokePrivate(this.sslManager, "deactivate", this.componentContext);
            }
        }
    }

    @Test
    void shouldStillRejectMismatchedHostnameWhenOnlySslManagerVerificationIsDisabled() throws Throwable {
        // JDK 21 HttpsURLConnection retains its own hostname check. Preserve that current behavior.
        downloadFrom("Server Cert", false);
        assertRejectedHostname();
    }

    @Test
    void shouldRejectMismatchedHostnameWithVerificationEnabled() throws Throwable {
        downloadFrom("Server Cert", true);
        assertRejectedHostname();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldDownloadFromTrustedMatchingHostname(boolean hostnameVerification) throws Throwable {
        downloadFrom("localhost", hostnameVerification);
        assertArrayEquals(this.resource.content(), Files.readAllBytes(downloadPath()), () -> notifications().stream()
                .map(n -> String.valueOf(n.getErrorMessage())).toList().toString());
        List<KuraNotifyPayload> notifications = notifications();
        assertTrue(notifications.stream().anyMatch(n -> isStatus(n, DownloadStatus.COMPLETED)));
        assertFalse(notifications.stream().anyMatch(n -> isStatus(n, DownloadStatus.FAILED)));
        assertEquals(1, this.resource.requests());
    }

    private void assertRejectedHostname() throws Throwable {
        assertEquals(0, Files.size(downloadPath()));
        List<KuraNotifyPayload> notifications = notifications();
        assertTrue(notifications.stream().anyMatch(n -> isStatus(n, DownloadStatus.FAILED)
                && n.getErrorMessage() != null && n.getErrorMessage().contains("localhost")),
                () -> "Expected hostname-specific TLS failure: " + notifications.stream()
                        .map(n -> String.valueOf(n.getErrorMessage())).toList());
        assertFalse(notifications.stream().anyMatch(n -> isStatus(n, DownloadStatus.COMPLETED)));
        assertEquals(0, this.resource.requests());
        verify(this.callback, never()).installDownloadedFile(any(), any());
    }

    private static boolean isStatus(KuraNotifyPayload notification, DownloadStatus status) {
        return status.getStatusString().equals(notification.getTransferStatus());
    }

    private List<KuraNotifyPayload> notifications() {
        ArgumentCaptor<KuraNotifyPayload> capture = ArgumentCaptor.forClass(KuraNotifyPayload.class);
        verify(this.callback, atLeastOnce()).publishMessage(any(), capture.capture(), eq(DownloadImpl.RESOURCE_DOWNLOAD));
        return capture.getAllValues();
    }

    private Path downloadPath() {
        return this.directory.resolve("test_0.0.0.dp");
    }

    private void downloadFrom(String commonName, boolean hostnameVerification) throws Throwable {
        TestCA ca = new TestCA(CertificateCreationOptions.builder(new X500Name("CN=Download Test CA")).build());
        KeyPair pair = TestCA.generateKeyPair();
        Certificate certificate = ca.createAndSignCertificate(
                CertificateCreationOptions.builder(new X500Name("CN=" + commonName)).build(), pair);
        KeyStore keys = KeyStore.getInstance("PKCS12");
        keys.load(null, null);
        char[] password = TestCA.TEST_KEYSTORE_PASSWORD.toCharArray();
        keys.setKeyEntry("server", pair.getPrivate(), password, new Certificate[] {certificate, ca.getCertificate()});
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), null, null);

        this.server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        this.server.setHttpsConfigurator(new HttpsConfigurator(context));
        this.server.createContext("/services/test/download", this.resource);
        this.serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.server.setExecutor(this.serverExecutor);
        this.server.start();

        KeyStore trusted = KeyStore.getInstance("PKCS12");
        trusted.load(null, null);
        trusted.setCertificateEntry("server-ca", ca.getCertificate());
        KeystoreService keystore = mock(KeystoreService.class);
        when(keystore.getKeyStore()).thenReturn(trusted);
        when(keystore.getKeyManagers(anyString())).thenReturn(List.of());
        BundleContext bundleContext = mock(BundleContext.class);
        when(bundleContext.createFilter(anyString())).thenAnswer(i -> FrameworkUtil.createFilter(i.getArgument(0)));
        this.componentContext = mock(ComponentContext.class);
        when(this.componentContext.getBundleContext()).thenReturn(bundleContext);
        this.sslManager = new SslManagerServiceImpl();
        TestUtil.invokePrivate(this.sslManager, "activate", this.componentContext,
                Map.of("ssl.default.protocol", "TLS", "ssl.hostname.verification", hostnameVerification));
        this.sslManager.setKeystoreService(keystore, Map.of("kura.service.pid", "download-test"));

        DeploymentPackageDownloadOptions options = new DeploymentPackageDownloadOptions(
                "https://localhost:" + this.server.getAddress().getPort() + "/services/test/download", "test", "0.0.0");
        options.setJobId(123L);
        options.setClientId("download-test");
        options.setDownloadDirectory(this.directory.toString());
        options.setDownloadProtocol("HTTP");
        options.setBlockDelay(0);
        options.setInstall(false);
        this.download = new DownloadImpl(options, this.callback);
        this.download.setSslManager(this.sslManager);
        this.download.downloadDeploymentPackageInternal();
    }
}
