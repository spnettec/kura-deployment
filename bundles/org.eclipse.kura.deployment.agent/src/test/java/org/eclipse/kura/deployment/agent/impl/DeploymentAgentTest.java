/*******************************************************************************
 * Copyright (c) 2017, 2023 Eurotech and/or its affiliates and others
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Eurotech
 *  3 PORT d.o.o.
 ******************************************************************************/
package org.eclipse.kura.deployment.agent.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.bouncycastle.asn1.x500.X500Name;
import org.eclipse.kura.core.testutil.TestUtil;
import org.eclipse.kura.core.testutil.pki.TestCA.CertificateCreationOptions;
import org.eclipse.kura.core.testutil.pki.TestCA;
import org.eclipse.kura.deployment.agent.MarketplacePackageDescriptor;
import org.eclipse.kura.ssl.SslManagerService;
import org.eclipse.kura.system.SystemService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osgi.framework.Version;
import org.osgi.service.deploymentadmin.DeploymentAdmin;
import org.osgi.service.deploymentadmin.DeploymentPackage;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;

public class DeploymentAgentTest {

    @TempDir
    Path directory;

    private final List<DeploymentAgent> agents = new ArrayList<>();
    private final String originalConfiguration = System.getProperty("dpa.configuration");
    private HttpsServer server;
    private ExecutorService serverExecutor;


    private static final String VERSION_1_0_0 = "1.0.0";
    private static final String DP_NAME = "dpName";
    private DeploymentAgent deploymentAgent = new DeploymentAgent();
    private String dpaConfigurationFilepath;

    private MarketplacePackageDescriptor resultingPackageDescriptor;

    private SystemService systemServiceMock = mock(SystemService.class);
    private SslManagerService sslManagerServiceMock = mock(SslManagerService.class);
    private Exception occurredException;

    private static final String DPA_CONF_PATH_PROPNAME = "dpa.configuration";
    @AfterEach
    void closeResources() throws InterruptedException {
        this.deploymentAgent.deactivate();
        for (DeploymentAgent agent : this.agents) {
            agent.deactivate();
        }
        if (this.server != null) {
            this.server.stop(0);
        }
        if (this.serverExecutor != null) {
            this.serverExecutor.shutdownNow();
            assertTrue(this.serverExecutor.awaitTermination(5, TimeUnit.SECONDS));
        }
        if (this.originalConfiguration == null) {
            System.clearProperty(DPA_CONF_PATH_PROPNAME);
        } else {
            System.setProperty(DPA_CONF_PATH_PROPNAME, this.originalConfiguration);
        }
    }

    @Test
    public void testInstallDeploymentPackageAsyncAlreadyDeploying() throws Exception {
        // test the exception thrown when package is already queued

        DeploymentAgent svc = new DeploymentAgent();
        this.agents.add(svc);

        Set<String> set = new HashSet<>();

        TestUtil.setFieldValue(svc, "instPackageUrls", set);

        String url = "dpUrl";

        set.add(url);

        assertTrue(svc.isInstallingDeploymentPackage(url));

        Exception failure = assertThrows(Exception.class, () -> svc.installDeploymentPackageAsync(url));
        assertEquals("Element already exists", failure.getMessage());

        assertEquals(1, set.size());
        assertTrue(set.contains(url));
    }

    @Test
    public void testUninstallDeploymentPackageAsyncAlreadyDeploying() throws Exception {
        // test the exception thrown when package is already queued

        DeploymentAgent svc = new DeploymentAgent();
        this.agents.add(svc);

        Set<String> set = new HashSet<>();

        TestUtil.setFieldValue(svc, "uninstPackageNames", set);

        String name = DP_NAME;

        set.add(name);

        assertTrue(svc.isUninstallingDeploymentPackage(name));

        Exception failure = assertThrows(Exception.class, () -> svc.uninstallDeploymentPackageAsync(name));
        assertEquals("Element already exists", failure.getMessage());

        assertEquals(1, set.size());
        assertTrue(set.contains(name));
    }

    @Test
    public void testInstaller() throws Exception {
        DeploymentAgent svc = new DeploymentAgent();
        this.agents.add(svc);
        EventAdmin events = mock(EventAdmin.class);
        svc.setEventAdmin(events);
        CompletableFuture<Event> completed = new CompletableFuture<>();
        doAnswer(i -> { completed.complete(i.getArgument(0)); return null; }).when(events).postEvent(any());

        svc.installDeploymentPackageAsync("myUrl"); // Invalid URL exercises the actual executor's failure path.
        Event event = completed.get(2, TimeUnit.SECONDS);
        assertFalse((boolean) event.getProperty("successful"));
        assertEquals("UNKNOWN", event.getProperty("deploymentpackage.name"));
        assertFalse(svc.isInstallingDeploymentPackage("myUrl"));
    }

    @Test
    public void testUninstaller() throws Exception {
        Properties packages = new Properties();
        packages.put(DP_NAME, this.directory.resolve("nonExistingDp.dp").toUri().toString());
        DeploymentAgent svc = new DeploymentAgent() {
            @Override
            protected Properties readDeployedPackages() {
                return packages;
            }
        };
        this.agents.add(svc);
        TestUtil.setFieldValue(svc, "dpaConfPath", this.directory.resolve("dpa.properties").toString());
        EventAdmin events = mock(EventAdmin.class);
        svc.setEventAdmin(events);
        CompletableFuture<Event> completed = new CompletableFuture<>();
        doAnswer(i -> { completed.complete(i.getArgument(0)); return null; }).when(events).postEvent(any());
        DeploymentAdmin admin = mock(DeploymentAdmin.class);
        svc.setDeploymentAdmin(admin);
        DeploymentPackage dp = mock(DeploymentPackage.class);
        when(admin.getDeploymentPackage(DP_NAME)).thenReturn(dp);

        svc.uninstallDeploymentPackageAsync(DP_NAME);
        Event event = completed.get(2, TimeUnit.SECONDS);
        assertTrue((boolean) event.getProperty("successful"));
        assertEquals(DP_NAME, event.getProperty("deploymentpackage.name"));
        verify(dp).uninstall();
        assertFalse(svc.isUninstallingDeploymentPackage(DP_NAME));
        assertFalse(packages.containsKey(DP_NAME));
    }

    @Test
    public void testPostInstalledEvent() throws Throwable {
        // test the post-installation event

        DeploymentAgent svc = new DeploymentAgent();
        this.agents.add(svc);

        EventAdmin eaMock = mock(EventAdmin.class);
        svc.setEventAdmin(eaMock);

        AtomicBoolean invoked = new AtomicBoolean(false);
        doAnswer(invocation -> {
            Event event = invocation.getArgument(0, Event.class);

            assertTrue((boolean) event.getProperty("successful"));

            invoked.set(true);

            return null;
        }).when(eaMock).postEvent(any());

        DeploymentPackage dp = mock(DeploymentPackage.class);
        when(dp.getName()).thenReturn(DP_NAME);
        when(dp.getVersion()).thenReturn(new Version(1, 1, 0));

        TestUtil.invokePrivate(svc, "postInstalledEvent", dp, "dpUrl", true, null);

        assertTrue(invoked.get());
    }

    @Test
    public void testInstallFromConfigExc() throws Throwable {
        // test installation of packages stored in the configuration file - installation exception

        AtomicInteger invoked = new AtomicInteger(0);
        Set<String> paths = new HashSet<>();

        DeploymentAgent svc = new DeploymentAgent() {

            @Override
            public void installDeploymentPackageAsync(String url) throws Exception {
                paths.add(url);
                invoked.incrementAndGet();

                throw new Exception("test");
            }
        };
        this.agents.add(svc);

        String dpaConfPath = this.directory.resolve("dpa.properties").toString();
        TestUtil.setFieldValue(svc, "dpaConfPath", dpaConfPath);

        try (FileWriter writer = new FileWriter(dpaConfPath)) {
            writer.write("dp1=file:/tmp/testdp1.dp\ndp2=file:/tmp/testdp2.dp\n");
        }

        TestUtil.invokePrivate(svc, "installPackagesFromConfFile");

        assertEquals(2, invoked.get());
        assertTrue(paths.contains("file:/tmp/testdp1.dp"));
        assertTrue(paths.contains("file:/tmp/testdp2.dp"));
    }

    @Test
    public void testInstallFromConfig() throws Throwable {
        // test installation of packages stored in the configuration file

        AtomicInteger invoked = new AtomicInteger(0);
        Set<String> paths = new HashSet<>();

        DeploymentAgent svc = new DeploymentAgent() {

            @Override
            public void installDeploymentPackageAsync(String url) throws Exception {
                paths.add(url);
                invoked.incrementAndGet();
            }
        };
        this.agents.add(svc);

        String dpaConfPath = this.directory.resolve("dpa.properties").toString();
        TestUtil.setFieldValue(svc, "dpaConfPath", dpaConfPath);

        try (FileWriter writer = new FileWriter(dpaConfPath)) {
            writer.write("dp1=file:/tmp/testdp1.dp\ndp2=file:/tmp/testdp2.dp\n");
        }

        TestUtil.invokePrivate(svc, "installPackagesFromConfFile");

        assertEquals(2, invoked.get());
        assertTrue(paths.contains("file:/tmp/testdp1.dp"));
        assertTrue(paths.contains("file:/tmp/testdp2.dp"));
    }

    @Test
    public void testInstallDeploymentPackageInternal() throws Throwable {
        // test installation of packages stored in the configuration file

        DeploymentPackage dp = mock(DeploymentPackage.class);
        when(dp.getName()).thenReturn(DP_NAME);
        when(dp.getVersion()).thenReturn(new Version(VERSION_1_0_0));

        DeploymentAgent svc = new DeploymentAgent();
        this.agents.add(svc);

        DeploymentAdmin daMock = mock(DeploymentAdmin.class);
        svc.setDeploymentAdmin(daMock);
        when(daMock.installDeploymentPackage(any())).thenReturn(dp);

        String dpaConfPath = this.directory.resolve("dpa.properties").toString();
        TestUtil.setFieldValue(svc, "dpaConfPath", dpaConfPath);

        String packagesPath = this.directory.toString();
        TestUtil.setFieldValue(svc, "packagesPath", packagesPath);

        File f = this.directory.resolve("testdp.dp").toFile();
        String url = f.toURI().toString();
        f.createNewFile();

        TestUtil.invokePrivate(svc, "installDeploymentPackageInternal", url);

        verify(daMock, times(1)).installDeploymentPackage(any());

        Properties persisted = new Properties();
        try (FileReader reader = new FileReader(dpaConfPath)) {
            persisted.load(reader);
        }
        Path installed = this.directory.resolve(DP_NAME + "_" + VERSION_1_0_0 + ".dp");
        assertEquals("file:" + installed, persisted.getProperty(DP_NAME));
        assertTrue(Files.exists(installed), "DP file should have been moved");
        assertFalse(f.exists());
    }

    @Test
    public void testAddPackageToConfFileNoConfigFile() throws Throwable {
        // test adding packages to configuration file that is not set

        final Properties deployedPackages = mock(Properties.class);
        DeploymentAgent svc = new DeploymentAgent() {

            @Override
            protected Properties readDeployedPackages() {
                return deployedPackages;
            }
        };
        this.agents.add(svc);

        String dpName = "testdp";
        String url = "file:///tmp/testdp.dp";

        TestUtil.invokePrivate(svc, "addPackageToConfFile", dpName, url);

        verify(deployedPackages, times(1)).setProperty(dpName, url);
        verify(deployedPackages, times(0)).store((FileOutputStream) any(), any());
    }

    @Test
    public void testAddPackageToConfFileStoreException() throws Throwable {
        // test adding packages to configuration file, but fail doing so

        final Properties deployedPackages = spy(new Properties());

        when(deployedPackages.entrySet()).thenCallRealMethod();
        doCallRealMethod().when(deployedPackages).setProperty(any(), any());

        DeploymentAgent svc = new DeploymentAgent() {

            @Override
            protected Properties readDeployedPackages() {
                return deployedPackages;
            }
        };
        this.agents.add(svc);

        doThrow(new IOException("test")).when(deployedPackages).store((FileOutputStream) any(), any());

        String dpaConfPath = this.directory.resolve("dpa.properties").toString();
        TestUtil.setFieldValue(svc, "dpaConfPath", dpaConfPath);

        String dpName = "testdp";
        String url = "file:///tmp/testdp.dp";

        TestUtil.invokePrivate(svc, "addPackageToConfFile", dpName, url);

        verify(deployedPackages, times(1)).setProperty(dpName, url);
        verify(deployedPackages, times(1)).store((FileOutputStream) any(), any());
    }

    @Test
    public void testRemovePackageToConfFileNoConfigFile() throws Throwable {
        // test removing packages from configuration file that is not set

        Properties deployedPackages = mock(Properties.class);

        DeploymentAgent svc = new DeploymentAgent() {

            @Override
            protected Properties readDeployedPackages() {
                return deployedPackages;
            }
        };
        this.agents.add(svc);

        String dpName = "testdp";

        TestUtil.invokePrivate(svc, "removePackageFromConfFile", dpName);

        verify(deployedPackages, times(1)).remove(dpName);
        verify(deployedPackages, times(0)).store((FileOutputStream) any(), any());
    }

    @Test
    public void testRemovePackageToConfFileStoreException() throws Throwable {
        // test removing packages from configuration file, but fail doing so

        final Properties props = new Properties();
        props.put("testdp", "file:///tmp/testdp.dp");

        final Properties deployedPackages = spy(props);
        when(deployedPackages.entrySet()).thenCallRealMethod();

        DeploymentAgent svc = new DeploymentAgent() {

            @Override
            protected Properties readDeployedPackages() {
                return deployedPackages;
            }
        };
        this.agents.add(svc);

        doThrow(new IOException("test")).when(deployedPackages).store((FileOutputStream) any(), any());

        String dpaConfPath = this.directory.resolve("dpa.properties").toString();
        TestUtil.setFieldValue(svc, "dpaConfPath", dpaConfPath);

        String dpName = "testdp";

        TestUtil.invokePrivate(svc, "removePackageFromConfFile", dpName);

        verify(deployedPackages, times(1)).remove(dpName);
        verify(deployedPackages, times(1)).store((FileOutputStream) any(), any());
    }

    @Test
    public void testRemovePackageToConfFile() throws Throwable {
        // test removing packages from configuration file

        final Properties props = new Properties();
        props.put("testdp", "file:///tmp/testdp.dp");

        final Properties deployedPackages = spy(props);
        when(deployedPackages.entrySet()).thenCallRealMethod();

        DeploymentAgent svc = new DeploymentAgent() {

            @Override
            protected Properties readDeployedPackages() {
                return deployedPackages;
            }
        };
        this.agents.add(svc);

        String dpaConfPath = this.directory.resolve("dpa.properties").toString();
        TestUtil.setFieldValue(svc, "dpaConfPath", dpaConfPath);

        String dpName = "testdp";

        TestUtil.invokePrivate(svc, "removePackageFromConfFile", dpName);

        verify(deployedPackages, times(1)).remove(dpName);
        verify(deployedPackages, times(1)).store((FileOutputStream) any(), any());
    }

    @Test
    public void shouldNotInstallPackageWithURLInConfFile() throws Exception {
        givenDeploymentAgent();
        givenConfigurationFile(this.directory.resolve("dpa.properties").toString());
        givenDpWithUrlScheme("dp-with-url", "http://fake-url/dp-with-url.dp\n");

        whenActivate();

        thenNoPackageInstalled();
    }

    @Test
    public void getMarketplacePackageDescriptorShouldWorkWithCompatible() {
        givenDeploymentAgent();
        givenLocalHttpsServer();
        givenSystemServiceReturnsCurrentKuraVersion("5.4.0");

        givenServerResponse("54435", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + "<marketplace>\n"
                + "  <node id=\"5514714\" name=\"AI Wire Component for Eclipse Kura 5\" url=\"https://marketplace.eclipse.org/content/ai-wire-component-eclipse-kura-5\">\n"
                + "    <type>iot_package</type>\n" + "    <owner>Matteo Maiero</owner>\n"
                + "    <favorited>0</favorited>\n" + "    <installstotal>0</installstotal>\n"
                + "    <installsrecent>0</installsrecent>\n" + "    <shortdescription><![CDATA[]]></shortdescription>\n"
                + "    <body><![CDATA[<p><strong>OFFICIAL ADD-ON for Eclipse Kura</strong>&nbsp; - This wire component enables Eclipse Kura to interact with an Inference Engine to perform machine learning-related tasks.</p>\n"
                + "\n"
                + "<p>This package is an official add-on provided and maintained by the Eclipse Kura Development Team</p>\n"
                + "\n"
                + "<p>To install the package, simply drag and drop the Eclipse Marketplace link into the ESF/Kura Packages section of the Web UI.</p>\n"
                + "\n" + "<p><strong>Compatibility</strong></p>\n" + "\n"
                + "<p>The bundle requires Eclipse Kura 5.1.0+.</p>\n" + "]]></body>\n"
                + "    <created>1648566806</created>\n" + "    <changed>1685628355</changed>\n"
                + "    <foundationmember>1</foundationmember>\n" + "    <homepageurl></homepageurl>\n"
                + "    <image><![CDATA[https://marketplace.eclipse.org/sites/default/files/styles/badge_logo/public/iot-package/logo/Kura_logo_2_44.png?itok=gr-2SSey]]></image>\n"
                + "    <screenshot><![CDATA[https://marketplace.eclipse.org/sites/default/files/styles/medium/public/iot-package/screenshot/kura_marketplace_drag_drop_60.png?itok=pitMd0Qe]]></screenshot>\n"
                + "    <license>EPL 2.0</license>\n" + "    <companyname><![CDATA[Eurotech]]></companyname>\n"
                + "    <status>Production/Stable</status>\n" + "    <supporturl><![CDATA[]]></supporturl>\n"
                + "    <version>1.2.0</version>\n" + "    <min_java_version>java_8</min_java_version>\n"
                + "    <updateurl>https://download.eclipse.org/kura/releases/5.3.0/org.eclipse.kura.wire.ai.component.provider-1.2.0.dp</updateurl>\n"
                + "    <packagetypes>wire_component</packagetypes>\n"
                + "    <sourceurl>https://github.com/eclipse/kura/tree/KURA_5.3.0_RELEASE/kura/org.eclipse.kura.wire.ai.component.provider</sourceurl>\n"
                + "    <versioncompatibility>\n" + "      <from>5.1.0</from>\n" + "      <to></to>\n"
                + "    </versioncompatibility>\n" + "    <environmentrequirements/>\n" + "  </node>\n"
                + "</marketplace>");

        whenGetMarketplacePackageDescriptorIsCalledFor(
                "https://localhost:" + this.server.getAddress().getPort() + "/node/54435/api/p");

        thenNoExceptionOccurred();
        thenDescriptorIsEqualTo(MarketplacePackageDescriptor.builder().nodeId("5514714")
                .url("https://marketplace.eclipse.org/content/ai-wire-component-eclipse-kura-5")
                .dpUrl("https://download.eclipse.org/kura/releases/5.3.0/org.eclipse.kura.wire.ai.component.provider-1.2.0.dp")
                .minKuraVersion("5.1.0").maxKuraVersion("").currentKuraVersion("5.4.0").isCompatible(true).build());
    }

    @Test
    public void getMarketplacePackageDescriptorShouldWorkWithNotCompatible() {
        givenDeploymentAgent();
        givenLocalHttpsServer();
        givenSystemServiceReturnsCurrentKuraVersion("5.0.0");
        givenServerResponse("54435", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + "<marketplace>\n"
                + "  <node id=\"5514714\" name=\"AI Wire Component for Eclipse Kura 5\" url=\"https://marketplace.eclipse.org/content/ai-wire-component-eclipse-kura-5\">\n"
                + "    <type>iot_package</type>\n" + "    <owner>Matteo Maiero</owner>\n"
                + "    <favorited>0</favorited>\n" + "    <installstotal>0</installstotal>\n"
                + "    <installsrecent>0</installsrecent>\n" + "    <shortdescription><![CDATA[]]></shortdescription>\n"
                + "    <body><![CDATA[<p><strong>OFFICIAL ADD-ON for Eclipse Kura</strong>&nbsp; - This wire component enables Eclipse Kura to interact with an Inference Engine to perform machine learning-related tasks.</p>\n"
                + "\n"
                + "<p>This package is an official add-on provided and maintained by the Eclipse Kura Development Team</p>\n"
                + "\n"
                + "<p>To install the package, simply drag and drop the Eclipse Marketplace link into the ESF/Kura Packages section of the Web UI.</p>\n"
                + "\n" + "<p><strong>Compatibility</strong></p>\n" + "\n"
                + "<p>The bundle requires Eclipse Kura 5.1.0+.</p>\n" + "]]></body>\n"
                + "    <created>1648566806</created>\n" + "    <changed>1685628355</changed>\n"
                + "    <foundationmember>1</foundationmember>\n" + "    <homepageurl></homepageurl>\n"
                + "    <image><![CDATA[https://marketplace.eclipse.org/sites/default/files/styles/badge_logo/public/iot-package/logo/Kura_logo_2_44.png?itok=gr-2SSey]]></image>\n"
                + "    <screenshot><![CDATA[https://marketplace.eclipse.org/sites/default/files/styles/medium/public/iot-package/screenshot/kura_marketplace_drag_drop_60.png?itok=pitMd0Qe]]></screenshot>\n"
                + "    <license>EPL 2.0</license>\n" + "    <companyname><![CDATA[Eurotech]]></companyname>\n"
                + "    <status>Production/Stable</status>\n" + "    <supporturl><![CDATA[]]></supporturl>\n"
                + "    <version>1.2.0</version>\n" + "    <min_java_version>java_8</min_java_version>\n"
                + "    <updateurl>https://download.eclipse.org/kura/releases/5.3.0/org.eclipse.kura.wire.ai.component.provider-1.2.0.dp</updateurl>\n"
                + "    <packagetypes>wire_component</packagetypes>\n"
                + "    <sourceurl>https://github.com/eclipse/kura/tree/KURA_5.3.0_RELEASE/kura/org.eclipse.kura.wire.ai.component.provider</sourceurl>\n"
                + "    <versioncompatibility>\n" + "      <from>5.1.0</from>\n" + "      <to></to>\n"
                + "    </versioncompatibility>\n" + "    <environmentrequirements/>\n" + "  </node>\n"
                + "</marketplace>");

        whenGetMarketplacePackageDescriptorIsCalledFor(
                "https://localhost:" + this.server.getAddress().getPort() + "/node/54435/api/p");

        thenNoExceptionOccurred();
        thenDescriptorIsEqualTo(MarketplacePackageDescriptor.builder().nodeId("5514714")
                .url("https://marketplace.eclipse.org/content/ai-wire-component-eclipse-kura-5")
                .dpUrl("https://download.eclipse.org/kura/releases/5.3.0/org.eclipse.kura.wire.ai.component.provider-1.2.0.dp")
                .minKuraVersion("5.1.0").maxKuraVersion("").currentKuraVersion("5.0.0").isCompatible(false).build());
    }

    @Test
    public void getMarketplacePackageDescriptorShouldThrowWithoutDownloadUrl() {
        givenDeploymentAgent();
        givenLocalHttpsServer();
        givenSystemServiceReturnsCurrentKuraVersion("5.0.0");
        givenServerResponse("54435", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + "<marketplace>\n"
                + "  <node id=\"5514714\" name=\"AI Wire Component for Eclipse Kura 5\" url=\"https://marketplace.eclipse.org/content/ai-wire-component-eclipse-kura-5\">\n"
                + "    <type>iot_package</type>\n" + "    <owner>Matteo Maiero</owner>\n"
                + "    <favorited>0</favorited>\n" + "    <installstotal>0</installstotal>\n"
                + "    <installsrecent>0</installsrecent>\n" + "    <shortdescription><![CDATA[]]></shortdescription>\n"
                + "    <body><![CDATA[<p><strong>OFFICIAL ADD-ON for Eclipse Kura</strong>&nbsp; - This wire component enables Eclipse Kura to interact with an Inference Engine to perform machine learning-related tasks.</p>\n"
                + "\n"
                + "<p>This package is an official add-on provided and maintained by the Eclipse Kura Development Team</p>\n"
                + "\n"
                + "<p>To install the package, simply drag and drop the Eclipse Marketplace link into the ESF/Kura Packages section of the Web UI.</p>\n"
                + "\n" + "<p><strong>Compatibility</strong></p>\n" + "\n"
                + "<p>The bundle requires Eclipse Kura 5.1.0+.</p>\n" + "]]></body>\n"
                + "    <created>1648566806</created>\n" + "    <changed>1685628355</changed>\n"
                + "    <foundationmember>1</foundationmember>\n" + "    <homepageurl></homepageurl>\n"
                + "    <image><![CDATA[https://marketplace.eclipse.org/sites/default/files/styles/badge_logo/public/iot-package/logo/Kura_logo_2_44.png?itok=gr-2SSey]]></image>\n"
                + "    <screenshot><![CDATA[https://marketplace.eclipse.org/sites/default/files/styles/medium/public/iot-package/screenshot/kura_marketplace_drag_drop_60.png?itok=pitMd0Qe]]></screenshot>\n"
                + "    <license>EPL 2.0</license>\n" + "    <companyname><![CDATA[Eurotech]]></companyname>\n"
                + "    <status>Production/Stable</status>\n" + "    <supporturl><![CDATA[]]></supporturl>\n"
                + "    <version>1.2.0</version>\n" + "    <min_java_version>java_8</min_java_version>\n"
                + "    <packagetypes>wire_component</packagetypes>\n"
                + "    <sourceurl>https://github.com/eclipse/kura/tree/KURA_5.3.0_RELEASE/kura/org.eclipse.kura.wire.ai.component.provider</sourceurl>\n"
                + "    <versioncompatibility>\n" + "      <from>5.1.0</from>\n" + "      <to></to>\n"
                + "    </versioncompatibility>\n" + "    <environmentrequirements/>\n" + "  </node>\n"
                + "</marketplace>");

        whenGetMarketplacePackageDescriptorIsCalledFor(
                "https://localhost:" + this.server.getAddress().getPort() + "/node/54435/api/p");

        thenExceptionOccurred(IllegalStateException.class);
        assertNotNull(this.occurredException.getCause());
        assertEquals("Cannot find download URL in the deployment package descriptor",
                this.occurredException.getCause().getMessage());
    }

    /*
     * GIVEN
     */

    private void givenSystemServiceReturnsCurrentKuraVersion(String returnedVersion) {
        when(this.systemServiceMock.getKuraMarketplaceCompatibilityVersion()).thenReturn(returnedVersion);
    }

    private void givenServerResponse(String nodeId, String responseXML) {
        this.server.createContext("/node/" + nodeId + "/api/p", exchange -> {
            try (exchange) {
                byte[] body = responseXML.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/xml");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        this.server.start();
    }

    private void givenDpWithUrlScheme(String dpName, String dpUrl) throws IOException {
        try (FileWriter writer = new FileWriter(dpaConfigurationFilepath)) {
            writer.write(String.join("=", dpName, dpUrl) + "\n");
        }
    }

    private void givenDeploymentAgent() {
        Properties properties = new Properties();
        properties.put("kura.packages", this.directory.resolve("packages").toString());
        when(systemServiceMock.getProperties()).thenReturn(properties);
        this.deploymentAgent.setSystemService(systemServiceMock);
        this.deploymentAgent = spy(this.deploymentAgent);
    }

    private void givenLocalHttpsServer() {
        try {
            TestCA ca = new TestCA(
                    CertificateCreationOptions
                            .builder(new X500Name("CN=Marketplace Test CA")).build());
            KeyPair pair = TestCA.generateKeyPair();
            Certificate certificate = ca.createAndSignCertificate(
                    CertificateCreationOptions
                            .builder(new X500Name("CN=localhost")).build(), pair);
            KeyStore keys = KeyStore.getInstance("PKCS12");
            keys.load(null, null);
            char[] password = "test-password".toCharArray();
            keys.setKeyEntry("server", pair.getPrivate(), password,
                    new Certificate[] {certificate, ca.getCertificate()});
            KeyManagerFactory km = KeyManagerFactory
                    .getInstance(KeyManagerFactory.getDefaultAlgorithm());
            km.init(keys, password);
            SSLContext serverContext = SSLContext.getInstance("TLS");
            serverContext.init(km.getKeyManagers(), null, null);
            this.server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
            this.server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
            this.serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
            this.server.setExecutor(this.serverExecutor);
            KeyStore trusted = KeyStore.getInstance("PKCS12");
            trusted.load(null, null);
            trusted.setCertificateEntry("test-ca", ca.getCertificate());
            TrustManagerFactory tm = TrustManagerFactory
                    .getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tm.init(trusted);
            SSLContext clientContext = SSLContext.getInstance("TLS");
            clientContext.init(null, tm.getTrustManagers(), null);
            when(this.sslManagerServiceMock.getSSLSocketFactory()).thenReturn(clientContext.getSocketFactory());
            this.deploymentAgent.setSslManagerService(this.sslManagerServiceMock);
        } catch (Exception e) {
            fail("Could not prepare local HTTPS fixture", e);
        }
    }

    private void givenConfigurationFile(String dpaConfigurationFilepath) throws NoSuchFieldException {
        this.dpaConfigurationFilepath = dpaConfigurationFilepath;
        System.setProperty(DPA_CONF_PATH_PROPNAME, dpaConfigurationFilepath);
    }

    /*
     * WHEN
     */

    private void whenActivate() {
        this.deploymentAgent.activate();
    }

    private void whenGetMarketplacePackageDescriptorIsCalledFor(String url) {
        try {
            this.resultingPackageDescriptor = this.deploymentAgent.getMarketplacePackageDescriptor(url);
        } catch (Exception e) {
            this.occurredException = e;
        }
    }

    /*
     * THEN
     */

    private void thenNoPackageInstalled() throws Exception {
        verify(this.deploymentAgent, times(0)).installDeploymentPackageAsync(anyString());
    }

    private void thenDescriptorIsEqualTo(MarketplacePackageDescriptor expectedDescriptor) {
        assertEquals(expectedDescriptor, this.resultingPackageDescriptor);
    }

    private void thenNoExceptionOccurred() {
        String errorMessage = "Empty message";
        if (Objects.nonNull(this.occurredException)) {
            StringWriter sw = new StringWriter();
            this.occurredException.printStackTrace(new PrintWriter(sw));

            errorMessage = String.format("No exception expected, \"%s\" found. Caused by: %s",
                    this.occurredException.getClass().getName(), sw.toString());
        }

        assertNull(this.occurredException, errorMessage);
    }

    private <E extends Exception> void thenExceptionOccurred(Class<E> expectedException) {
        assertNotNull(this.occurredException);
        assertEquals(expectedException.getName(), this.occurredException.getClass().getName());
    }

}
