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

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

/** Upstream download resource adapted to an owned HTTP server; DS/JAX-RS wiring remains outside this fixture. */
final class DownloadTestRestService implements HttpHandler {

    private final AtomicInteger requests = new AtomicInteger();

    byte[] content() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/test")) {
            if (input == null) {
                throw new IOException("Missing download fixture");
            }
            return input.readAllBytes();
        }
    }

    int requests() {
        return this.requests.get();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            this.requests.incrementAndGet();
            byte[] content = content();
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
        }
    }
}
