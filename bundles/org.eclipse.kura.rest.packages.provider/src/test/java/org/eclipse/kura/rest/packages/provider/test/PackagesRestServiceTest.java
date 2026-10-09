/*******************************************************************************
 * Copyright (c) 2024, 2025 Eurotech and/or its affiliates and others
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
package org.eclipse.kura.rest.packages.provider.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.eclipse.kura.deployment.agent.DeploymentAgentService;
import org.eclipse.kura.deployment.agent.MarketplacePackageDescriptor;
import org.eclipse.kura.internal.rest.deployment.agent.DeploymentRestService;
import org.eclipse.kura.internal.rest.provider.GsonSerializer;
import org.glassfish.jersey.jdkhttp.JdkHttpServerFactory;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.osgi.framework.Version;
import org.osgi.service.deploymentadmin.DeploymentAdmin;
import org.osgi.service.deploymentadmin.DeploymentPackage;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

/** Real Jersey HTTP/JSON/multipart; OSGi registration and runtime authentication remain separate acceptance work. */
class PackagesRestServiceTest {

    @TempDir
    Path directory;

    private final DeploymentAgentService agent = mock(DeploymentAgentService.class);
    private final DeploymentAdmin admin = mock(DeploymentAdmin.class);
    private HttpServer server;
    private ExecutorService executor;
    private HttpClient client;
    private URI endpoint;
    private String originalTempDirectory;

    @BeforeEach
    void startServer() {
        this.originalTempDirectory = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", this.directory.toString());
        DeploymentRestService service = new DeploymentRestService();
        service.setDeploymentAdmin(this.admin);
        service.setDeploymentAgentService(this.agent);
        ResourceConfig config = new ResourceConfig().register(service).register(GsonSerializer.class)
                .register(MultiPartFeature.class);
        this.server = JdkHttpServerFactory.createHttpServer(URI.create("http://localhost:0/services/"), config, false);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.server.setExecutor(this.executor);
        this.server.start();
        this.endpoint = URI.create("http://localhost:" + this.server.getAddress().getPort() + "/services/deploy/v2");
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void stopServer() throws InterruptedException {
        try {
            if (this.client != null) {
                this.client.close();
            }
            if (this.server != null) {
                this.server.stop(0);
            }
            if (this.executor != null) {
                this.executor.shutdownNow();
                assertTrue(this.executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        } finally {
            System.setProperty("java.io.tmpdir", this.originalTempDirectory);
        }
    }

    @Test
    void getShouldWorkWithEmptyList() throws Exception {
        when(this.admin.listDeploymentPackages()).thenReturn(new DeploymentPackage[0]);
        assertJson(request("GET", "", null), 200, "[]");
    }

    @Test
    void getShouldWorkWithNonEmptyList() throws Exception {
        DeploymentPackage[] packages = {deploymentPackage("testPackage", "1.0.0"),
                deploymentPackage("anotherAwesomePackage", "4.2.0")};
        when(this.admin.listDeploymentPackages()).thenReturn(packages);
        assertJson(request("GET", "", null), 200,
                "[{\"name\":\"testPackage\",\"version\":\"1.0.0\"},"
                + "{\"name\":\"anotherAwesomePackage\",\"version\":\"4.2.0\"}]");
    }

    @Test
    void installShouldWorkWithEmptyRequest() throws Exception {
        assertEquals(400, request("POST", "/_install", null).statusCode());
        verify(this.agent, never()).installDeploymentPackageAsync(anyString());
    }

    @Test
    void installShouldWorkWithValidURL() throws Exception {
        assertJson(request("POST", "/_install", "{\"url\":\"http://localhost/testPackage.dp\"}"),
                200, "\"REQUEST_RECEIVED\"");
        verify(this.agent).installDeploymentPackageAsync("http://localhost/testPackage.dp");
    }

    @Test
    void installShouldWorkWithValidURLWhenARequestWasAlreadyIssued() throws Exception {
        when(this.agent.isInstallingDeploymentPackage("http://localhost/testPackage.dp")).thenReturn(true);
        assertJson(request("POST", "/_install", "{\"url\":\"http://localhost/testPackage.dp\"}"),
                200, "\"INSTALLING\"");
        verify(this.agent, never()).installDeploymentPackageAsync(anyString());
    }

    @Test
    void uninstallShouldWorkWithValidPackageName() throws Exception {
        assertJson(request("DELETE", "/testPackage", null), 200, "\"REQUEST_RECEIVED\"");
        verify(this.agent).uninstallDeploymentPackageAsync("testPackage");
    }

    @Test
    void uninstallShouldWorkWithValidPackageNameWhenARequestWasAlreadyIssued() throws Exception {
        when(this.agent.isUninstallingDeploymentPackage("testPackage")).thenReturn(true);
        assertJson(request("DELETE", "/testPackage", null), 200, "\"UNINSTALLING\"");
        verify(this.agent, never()).uninstallDeploymentPackageAsync(anyString());
    }

    @Test
    void installShouldWorkWithFileUpload() throws Exception {
        assertJson(upload(), 200, "\"REQUEST_RECEIVED\"");
        assertUploadedFile();
    }

    @Test
    void installShouldWorkWithFileUploadAndDeploymentAgentThrowing() throws Exception {
        doThrow(new RuntimeException("install rejected")).when(this.agent).installDeploymentPackageAsync(anyString());
        HttpResponse<String> response = upload();
        assertEquals(500, response.statusCode());
        assertEquals("Error installing deployment package: mock.dp", response.body());
        assertUploadedFile();
    }

    @Test
    void getMarketplacePackageDescriptorShouldFailWithDeploymentAgentServiceThrowing() throws Exception {
        when(this.agent.getMarketplacePackageDescriptor(anyString())).thenThrow(new RuntimeException("unavailable"));
        assertEquals(500, request("PUT", "/_packageDescriptor",
                "{\"url\":\"https://marketplace.eclipse.org/marketplace-client-intro?mpc_install=42\"}").statusCode());
        verify(this.agent).getMarketplacePackageDescriptor("https://marketplace.eclipse.org/node/42/api/p");
    }

    @Test
    void getMarketplacePackageDescriptorShouldFailWithWrongUrl() throws Exception {
        assertEquals(400, request("PUT", "/_packageDescriptor",
                "{\"url\":\"https://marketplace.ellipse.org/marketplace-client-intro?mpc_install=69\"}").statusCode());
        verify(this.agent, never()).getMarketplacePackageDescriptor(anyString());
    }

    @Test
    void getMarketplacePackageDescriptorShouldWork() throws Exception {
        when(this.agent.getMarketplacePackageDescriptor(anyString())).thenReturn(MarketplacePackageDescriptor.builder()
                .nodeId("testNodeId").url("testUrl").dpUrl("testDpUrl2").minKuraVersion("1.1.0").maxKuraVersion("5.3.0")
                .currentKuraVersion("5.4.0").isCompatible(true).build());
        assertJson(request("PUT", "/_packageDescriptor",
                "{\"url\":\"https://marketplace.eclipse.org/marketplace-client-intro?mpc_install=70\"}"), 200,
                "{\"nodeId\":\"testNodeId\",\"url\":\"testUrl\",\"dpUrl\":\"testDpUrl2\","
                + "\"minKuraVersion\":\"1.1.0\",\"maxKuraVersion\":\"5.3.0\","
                + "\"currentKuraVersion\":\"5.4.0\",\"isCompatible\":true}");
        verify(this.agent).getMarketplacePackageDescriptor("https://marketplace.eclipse.org/node/70/api/p");
    }

    private DeploymentPackage deploymentPackage(String name, String version) {
        DeploymentPackage dp = mock(DeploymentPackage.class);
        when(dp.getName()).thenReturn(name);
        when(dp.getVersion()).thenReturn(new Version(version));
        return dp;
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        return this.client.send(HttpRequest.newBuilder(URI.create(this.endpoint + path)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static final byte[] UPLOAD = "test deployment package\nwith actual content".getBytes(StandardCharsets.UTF_8);

    private HttpResponse<String> upload() throws Exception {
        String boundary = "deployment-test-boundary";
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"mock.dp\""
                + "\r\nContent-Type: application/octet-stream\r\n\r\n"
                + new String(UPLOAD, StandardCharsets.UTF_8) + "\r\n--" + boundary + "--\r\n";
        return this.client.send(HttpRequest.newBuilder(URI.create(this.endpoint + "/_upload"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void assertUploadedFile() throws Exception {
        ArgumentCaptor<String> capture = ArgumentCaptor.forClass(String.class);
        verify(this.agent).installDeploymentPackageAsync(capture.capture());
        URI uri = URI.create(capture.getValue());
        assertEquals("file", uri.getScheme());
        Path uploaded = Path.of(uri);
        assertEquals(this.directory.toRealPath(), uploaded.getParent().toRealPath());
        assertArrayEquals(UPLOAD, Files.readAllBytes(uploaded));
    }

    private static void assertJson(HttpResponse<String> response, int status, String expected) {
        assertEquals(status, response.statusCode(), response::body);
        assertEquals(JsonParser.parseString(expected), JsonParser.parseString(response.body()));
    }
}
