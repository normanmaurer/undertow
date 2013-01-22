/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2013 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.undertow.websockets.jsr;

import javax.websocket.ClientEndpointConfiguration;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.Extension;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import java.net.URI;
import java.util.Collections;
import java.util.Set;

/**
 * Default {@link WebSocketContainer} implementation.
 *
 * @author <a href="mailto:nmaurer@redhat.com">Norman Maurer</a>
 */
public class DefaultWebSocketContainer implements WebSocketContainer {
    private volatile long maxSessionIdleTimeout;
    private volatile long maxBinaryMessageBufferSize;
    private volatile long maxTextMessageBufferSize;
    private volatile long defaultAsyncSendTimeout;

    @Override
    public long getDefaultAsyncSendTimeout() {
        return defaultAsyncSendTimeout;
    }

    @Override
    public void setAsyncSendTimeout(long defaultAsyncSendTimeout) {
        this.defaultAsyncSendTimeout = defaultAsyncSendTimeout;
    }

    @Override
    public Session connectToServer(Class aClass, URI uri) throws DeploymentException {
        return null;  // TODO: Implement me
    }

    @Override
    public Session connectToServer(Class<? extends Endpoint> aClass, ClientEndpointConfiguration clientEndpointConfiguration, URI uri) throws DeploymentException {
        return null;  // TODO: Implement me
    }

    @Override
    public Set<Session> getOpenSessions() {
        return Collections.emptySet();
    }

    @Override
    public long getMaxSessionIdleTimeout() {
        return maxSessionIdleTimeout;
    }

    @Override
    public void setMaxSessionIdleTimeout(long maxSessionIdleTimeout) {
        this.maxSessionIdleTimeout = maxSessionIdleTimeout;
    }

    @Override
    public long getMaxBinaryMessageBufferSize() {
        return maxBinaryMessageBufferSize;
    }

    @Override
    public void setMaxBinaryMessageBufferSize(long maxBinaryMessageBufferSize) {
        this.maxBinaryMessageBufferSize = maxBinaryMessageBufferSize;
    }

    @Override
    public long getMaxTextMessageBufferSize() {
        return maxTextMessageBufferSize;
    }

    @Override
    public void setMaxTextMessageBufferSize(long maxTextMessageBufferSize) {
        this.maxTextMessageBufferSize = maxTextMessageBufferSize;
    }

    @Override
    public Set<Extension> getInstalledExtensions() {
        // TODO: Implement me
        return Collections.emptySet();
    }
}
