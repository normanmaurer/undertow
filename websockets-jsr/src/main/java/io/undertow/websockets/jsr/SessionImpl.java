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

import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.websockets.WebSocketChannel;

import javax.websocket.CloseReason;
import javax.websocket.MessageHandler;
import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import java.io.IOException;
import java.lang.reflect.TypeVariable;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author <a href="mailto:nmaurer@redhat.com">Norman Maurer</a>
 */
public class SessionImpl implements Session {
    private final WebSocketChannel channel;
    private final WebSocketContainer container;
    private final ConcurrentMap<Class<?>, MessageHandler> handlers = new ConcurrentHashMap<Class<?>, MessageHandler>();
    private final URI uri;
    private final Principal principal;

    public SessionImpl(WebSocketChannel channel,  WebSocketContainer container, Principal principal) {
        this.channel = channel;
        this.container = container;
        try {
            uri = new URI(channel.getUrl());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Should never happen!");
        }
        this.principal = principal;
    }

    @Override
    public WebSocketContainer getContainer() {
        return container;
    }

    @Override
    public void addMessageHandler(MessageHandler messageHandler) throws IllegalStateException {
        TypeVariable[] m = messageHandler.getClass().getTypeParameters();
        if (m.length != 1) {
            throw new IllegalArgumentException("MessageHandler must have a Type parameter");
        }

        Class<?> typeClazz = messageHandler.getClass().getTypeParameters()[0].getClass();
        checkValidType(typeClazz);
        if (handlers.containsKey(typeClazz)) {
            throw new IllegalStateException("MessageHandler for type " + typeClazz + " exists");
        }
        if (handlers.putIfAbsent(typeClazz, messageHandler) != null) {
            throw new IllegalStateException("MessageHandler for type " + typeClazz + " exists");
        }
    }

    private static void checkValidType(Class<?> typeClazz) {
        if (typeClazz == String.class) {
            return;
        }
        if (typeClazz == ByteBuffer.class) {
            return;
        }
        if (typeClazz == byte[].class) {
            return;
        }
        throw new IllegalArgumentException("Type of " + typeClazz + " not supported");
    }

    @Override
    public Set<MessageHandler> getMessageHandlers() {
        return Collections.unmodifiableSet(new HashSet<MessageHandler>(handlers.values()));
    }

    @Override
    public void removeMessageHandler(MessageHandler messageHandler) {
        Class<?> typeClazz = messageHandler.getClass().getTypeParameters()[0].getClass();
        handlers.remove(typeClazz, messageHandler);
    }

    @Override
    public String getProtocolVersion() {
        return channel.getVersion().toHttpHeaderValue();
    }

    @Override
    public String getNegotiatedSubprotocol() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public List<String> getNegotiatedExtensions() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public boolean isSecure() {
        return channel.isSecure();
    }

    @Override
    public boolean isOpen() {
        return channel.isOpen();
    }

    @Override
    public long getTimeout() {
        return 0;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void setTimeout(long l) {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void setMaximumMessageSize(long l) {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public long getMaximumMessageSize() {
        return 0;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public RemoteEndpoint getRemote() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public String getId() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    @Override
    public void close(CloseReason closeReason) throws IOException {
    }

    @Override
    public URI getRequestURI() {
        return uri;
    }

    @Override
    public Map<String, List<String>> getRequestParameterMap() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public String getQueryString() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public Map<String, String> getPathParameters() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public Map<String, Object> getUserProperties() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public Principal getUserPrincipal() {
        return principal;
    }
}
