/*******************************************************************************
 * Copyright (c) 2018, 2020 Eurotech and/or its affiliates and others
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Eurotech
 ******************************************************************************/
package org.eclipse.kura.core.deployment.download.impl;

import org.eclipse.kura.KuraException;
import org.eclipse.kura.KuraInvalidMessageException;
import org.eclipse.kura.core.deployment.DeploymentPackageOptions;
import org.eclipse.kura.core.deployment.download.DeploymentPackageDownloadOptions;
import org.eclipse.kura.core.deployment.hook.DeploymentHookManager;
import org.eclipse.kura.message.KuraPayload;
import org.junit.jupiter.api.Test;

public class DeploymentPackageDownloadOptionsTest {

@Test
    public void testCreateNullDeployUri() throws KuraException {
        org.junit.jupiter.api.Assertions.assertThrows(KuraInvalidMessageException.class, () -> {
        final DeploymentHookManager deploymentHookManager = new DeploymentHookManager();

        KuraPayload request = new KuraPayload();

        new DeploymentPackageDownloadOptions(request, deploymentHookManager, "/tmp");

        });
    }

@Test
    public void testCreateNullName() throws KuraException {
        org.junit.jupiter.api.Assertions.assertThrows(KuraInvalidMessageException.class, () -> {
        final DeploymentHookManager deploymentHookManager = new DeploymentHookManager();

        KuraPayload request = new KuraPayload();
        request.addMetric(DeploymentPackageDownloadOptions.METRIC_DP_DOWNLOAD_URI, "");

        new DeploymentPackageDownloadOptions(request, deploymentHookManager, "/tmp");

        });
    }

@Test
    public void testCreateNullVersion() throws KuraException {
        org.junit.jupiter.api.Assertions.assertThrows(KuraInvalidMessageException.class, () -> {
        final DeploymentHookManager deploymentHookManager = new DeploymentHookManager();

        KuraPayload request = new KuraPayload();
        request.addMetric(DeploymentPackageDownloadOptions.METRIC_DP_DOWNLOAD_URI, "");
        request.addMetric(DeploymentPackageOptions.METRIC_DP_NAME, "name");

        new DeploymentPackageDownloadOptions(request, deploymentHookManager, "/tmp");

        });
    }

@Test
    public void testCreateNullProtocol() throws KuraException {
        org.junit.jupiter.api.Assertions.assertThrows(KuraInvalidMessageException.class, () -> {
        final DeploymentHookManager deploymentHookManager = new DeploymentHookManager();

        KuraPayload request = new KuraPayload();
        request.addMetric(DeploymentPackageDownloadOptions.METRIC_DP_DOWNLOAD_URI, "");
        request.addMetric(DeploymentPackageOptions.METRIC_DP_NAME, "");
        request.addMetric(DeploymentPackageOptions.METRIC_DP_VERSION, "");

        new DeploymentPackageDownloadOptions(request, deploymentHookManager, "/tmp");

        });
    }

@Test
    public void testCreateNullJobId() throws KuraException {
        org.junit.jupiter.api.Assertions.assertThrows(KuraInvalidMessageException.class, () -> {
        final DeploymentHookManager deploymentHookManager = new DeploymentHookManager();

        KuraPayload request = new KuraPayload();
        request.addMetric(DeploymentPackageDownloadOptions.METRIC_DP_DOWNLOAD_URI, "");
        request.addMetric(DeploymentPackageOptions.METRIC_DP_NAME, "");
        request.addMetric(DeploymentPackageOptions.METRIC_DP_VERSION, "");
        request.addMetric(DeploymentPackageDownloadOptions.METRIC_DP_DOWNLOAD_PROTOCOL, "");
        request.addMetric(DeploymentPackageOptions.METRIC_JOB_ID, null);

        new DeploymentPackageDownloadOptions(request, deploymentHookManager, "/tmp");

        });
    }

@Test
    public void testCreateNoJobId() throws KuraException {
        org.junit.jupiter.api.Assertions.assertThrows(KuraInvalidMessageException.class, () -> {
        final DeploymentHookManager deploymentHookManager = new DeploymentHookManager();

        KuraPayload request = new KuraPayload();
        request.addMetric(DeploymentPackageDownloadOptions.METRIC_DP_DOWNLOAD_URI, "");
        request.addMetric(DeploymentPackageOptions.METRIC_DP_NAME, "");
        request.addMetric(DeploymentPackageOptions.METRIC_DP_VERSION, "");
        request.addMetric(DeploymentPackageDownloadOptions.METRIC_DP_DOWNLOAD_PROTOCOL, "");

        new DeploymentPackageDownloadOptions(request, deploymentHookManager, "/tmp");

        });
    }

@Test
    public void testCreateNullUpdate() throws KuraException {
        org.junit.jupiter.api.Assertions.assertThrows(KuraInvalidMessageException.class, () -> {
        final DeploymentHookManager deploymentHookManager = new DeploymentHookManager();

        KuraPayload request = new KuraPayload();
        request.addMetric(DeploymentPackageDownloadOptions.METRIC_DP_DOWNLOAD_URI, "");
        request.addMetric(DeploymentPackageOptions.METRIC_DP_NAME, "");
        request.addMetric(DeploymentPackageOptions.METRIC_DP_VERSION, "");
        request.addMetric(DeploymentPackageDownloadOptions.METRIC_DP_DOWNLOAD_PROTOCOL, "");
        request.addMetric(DeploymentPackageOptions.METRIC_JOB_ID, 123L);

        new DeploymentPackageDownloadOptions(request, deploymentHookManager, "/tmp");

        });
    }
}
