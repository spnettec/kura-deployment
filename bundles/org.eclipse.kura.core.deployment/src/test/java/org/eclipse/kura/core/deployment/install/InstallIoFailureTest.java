/*******************************************************************************
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.kura.core.deployment.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.kura.core.deployment.CloudDeploymentHandlerV2;
import org.eclipse.kura.core.deployment.InstallStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.osgi.framework.Version;
import org.osgi.service.deploymentadmin.DeploymentAdmin;
import org.osgi.service.deploymentadmin.DeploymentPackage;

class InstallIoFailureTest {

    @TempDir
    Path workDir;

    private final CloudDeploymentHandlerV2 callback = mock(CloudDeploymentHandlerV2.class);
    private final DeploymentAdmin deploymentAdmin = mock(DeploymentAdmin.class);
    private final DeploymentPackageInstallOptions options = new DeploymentPackageInstallOptions("package", "1.0.0");

    @Test
    void missingArtifactMustPublishFailure() {
        InstallImpl installer = installer();

        installer.installDp(this.options, this.workDir.resolve("missing.dp").toFile());

        verifyNoInteractions(this.deploymentAdmin);
        assertFailurePublished();
    }

    @Test
    void failedArtifactPersistenceMustPublishFailure() throws Exception {
        InstallImpl installer = installer();
        Path source = Files.writeString(this.workDir.resolve("download.dp"), "test package bytes");
        Path blockedDirectory = Files.writeString(this.workDir.resolve("packages"), "not a directory");
        installer.setPackagesPath(blockedDirectory.toString());
        DeploymentPackage installed = mock(DeploymentPackage.class);
        when(installed.getName()).thenReturn("package");
        when(installed.getVersion()).thenReturn(new Version("1.0.0"));
        when(this.deploymentAdmin.installDeploymentPackage(any())).thenReturn(installed);

        installer.installDp(this.options, source.toFile());

        verify(this.deploymentAdmin).installDeploymentPackage(any());
        assertFailurePublished();
    }

    private InstallImpl installer() {
        this.options.setJobId(1234L);
        this.options.setClientId("test-client");
        InstallImpl installer = new InstallImpl(this.callback, this.workDir.toString(), null);
        installer.setDeploymentAdmin(this.deploymentAdmin);
        installer.setPackagesPath(this.workDir.toString());
        installer.setDpaConfPath(this.workDir.resolve("packages.properties").toString());
        return installer;
    }

    private void assertFailurePublished() {
        ArgumentCaptor<KuraInstallPayload> payload = ArgumentCaptor.forClass(KuraInstallPayload.class);
        verify(this.callback).publishMessage(eq(this.options), payload.capture(), eq(InstallImpl.RESOURCE_INSTALL));
        assertEquals(InstallStatus.FAILED.getStatusString(), payload.getValue().getInstallStatus());
        assertEquals(0, payload.getValue().getInstallProgress());
        assertNotNull(payload.getValue().getErrorMessage());
        assertFalse(payload.getValue().getErrorMessage().isBlank());
    }
}
