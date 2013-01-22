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

import io.undertow.websockets.StreamSinkFrameChannel;
import io.undertow.websockets.StreamSourceFrameChannel;
import io.undertow.websockets.WebSocketChannel;
import io.undertow.websockets.WebSocketFrameType;
import io.undertow.websockets.WebSocketUtils;
import org.xnio.Buffers;
import org.xnio.ChannelExceptionHandler;
import org.xnio.ChannelListener;
import org.xnio.ChannelListeners;
import org.xnio.IoUtils;
import org.xnio.Pooled;
import org.xnio.channels.StreamSourceChannel;

import javax.websocket.CloseReason;
import javax.websocket.Endpoint;
import javax.websocket.MessageHandler;
import javax.websocket.PongMessage;
import javax.websocket.RemoteEndpoint;
import javax.websocket.SendResult;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * {@link Session} implementation based on top of our {@link WebSocketChannel} implementation.
 *
 * @author <a href="mailto:nmaurer@redhat.com">Norman Maurer</a>
 */
final class WebSocketChannelSession implements Session {
    private final WebSocketChannel channel;
    private final WebSocketContainer container;
    private final ConcurrentMap<WebSocketFrameType, MessageHandler> handlers = new ConcurrentHashMap<WebSocketFrameType, MessageHandler>();
    private final Map<String, Object> attrs = new ConcurrentHashMap<String, Object>();
    private final URI uri;
    private final Principal principal;
    private final RemoteEndpoint remoteEndpoint;
    private volatile long timeout;
    private volatile long maximumMessageSize;
    private final String id = UUID.randomUUID().toString();
    private final Endpoint endpoint;
    public WebSocketChannelSession(WebSocketChannel channel, WebSocketContainer container, final Endpoint endpoint, Principal principal) {
        this.channel = channel;
        this.container = container;
        try {
            uri = new URI(channel.getUrl());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Should never happen!");
        }
        this.endpoint = endpoint;
        this.principal = principal;
        remoteEndpoint = new DefaultRemoteEndpoint(this);
        channel.getReceiveSetter().set(new WebSocketChannelSessionListener(endpoint));
        channel.resumeReceives();
    }

    WebSocketChannel getChannel() {
        return channel;
    }

    Endpoint getEndpoint() {
        return endpoint;
    }

    @Override
    public WebSocketContainer getContainer() {
        return container;
    }

    @Override
    public void addMessageHandler(MessageHandler messageHandler) throws IllegalStateException {
        TypeVariable[] m = messageHandler.getClass().getTypeParameters();
        if (m.length != 1) {
            throw WebSocketJsrMessages.MESSAGES.missingHandlerTypeParam();
        }

        Class<?> typeClazz = messageHandler.getClass().getTypeParameters()[0].getClass();
        WebSocketFrameType type = type(typeClazz);
        if (type == WebSocketFrameType.PONG && !(messageHandler instanceof MessageHandler.Basic)) {
            throw WebSocketJsrMessages.MESSAGES.unsupportedPongHandler();
        }
        if (handlers.containsKey(type)) {
            throw WebSocketJsrMessages.MESSAGES.handlerForTypeExists(type.name());
        }
        if (handlers.putIfAbsent(type, messageHandler) != null) {
            throw WebSocketJsrMessages.MESSAGES.handlerForTypeExists(type.name());
        }
    }

    private static WebSocketFrameType type(Class<?> typeClazz) {
        if (typeClazz == String.class) {
            return WebSocketFrameType.TEXT;
        }
        if (typeClazz == ByteBuffer.class) {
            return WebSocketFrameType.BINARY;
        }
        if (typeClazz == byte[].class) {
            return WebSocketFrameType.BINARY;
        }
        if (typeClazz == PongMessage.class) {
            return WebSocketFrameType.PONG;
        }
        throw WebSocketJsrMessages.MESSAGES.unsupportedHandlerType(typeClazz);
    }

    private static Class<?> typeOf(Class<? extends MessageHandler> clazz) {
        return clazz.getTypeParameters()[0].getClass();
    }

    @Override
    public Set<MessageHandler> getMessageHandlers() {
        return Collections.unmodifiableSet(new HashSet<MessageHandler>(handlers.values()));
    }

    @Override
    public void removeMessageHandler(MessageHandler messageHandler) {
        Class<?> typeClazz = messageHandler.getClass().getTypeParameters()[0].getClass();
        handlers.remove(type(typeClazz), messageHandler);
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
        return timeout;
    }

    @Override
    public void setTimeout(long timeout) {
        this.timeout = timeout;
    }

    @Override
    public void setMaximumMessageSize(long maximumMessageSize) {
        this.maximumMessageSize = maximumMessageSize;
    }

    @Override
    public long getMaximumMessageSize() {
        return maximumMessageSize;
    }

    @Override
    public RemoteEndpoint getRemote() {
        return remoteEndpoint;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public void close() throws IOException {
        close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, null));
    }

    @Override
    public void close(CloseReason closeReason) throws IOException {
        int size = 2;
        String phrase = closeReason.getReasonPhrase();
        if (phrase != null) {
            size += phrase.length();
        }

        Pooled<ByteBuffer> pooled = channel.getBufferPool().allocate();
        ByteBuffer buffer = pooled.getResource();
        buffer.putShort((short) closeReason.getCloseCode().getCode());
        if (phrase != null) {
            buffer.put(phrase.getBytes(WebSocketUtils.UTF_8));
        }
        buffer.flip();
        DefaultFuture future = new DefaultFuture();
        try {
            StreamSinkFrameChannel sink = channel.send(WebSocketFrameType.CLOSE, size);
            WebSocketJsrUtils.send(sink, buffer, future);
            future.get();
        } catch (Throwable cause) {
            future.setResult(new SendResult(cause));
        } finally {
            pooled.free();
        }

    }

    @Override
    public URI getRequestURI() {
        return uri;
    }

    @Override
    public Map<String, List<String>> getRequestParameterMap() {
        // TODO: Implement me
        return Collections.emptyMap();
    }

    @Override
    public String getQueryString() {
        // TODO: Implement me
        return null;
    }

    @Override
    public Map<String, String> getPathParameters() {
        // TODO: Implement me
        return Collections.emptyMap();
    }

    @Override
    public Map<String, Object> getUserProperties() {
        return attrs;
    }

    @Override
    public Principal getUserPrincipal() {
        return principal;
    }

    private final class WebSocketChannelSessionListener implements ChannelListener<WebSocketChannel> {
        private final Endpoint endpoint;
        private WebSocketFrameType type;

        public WebSocketChannelSessionListener(Endpoint endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public void handleEvent(WebSocketChannel webSocketChannel) {
            try {
                StreamSourceFrameChannel frame = webSocketChannel.receive();
                if (frame == null) {
                    return;
                }
                WebSocketFrameType frameType = frame.getType();
                if (frameType == WebSocketFrameType.CONTINUATION) {
                    if (type == null) {
                        // belongs to a frame we not had no handler for so just discard it
                        frame.discard();
                    } else {
                        frameType = type;
                    }
                }
                final MessageHandler messageHandler = handlers.get(frameType);

                if (messageHandler == null) {
                    if (frameType == WebSocketFrameType.CLOSE || frameType == WebSocketFrameType.PING) {
                        long size = frame.getPayloadSize();

                        final StreamSinkFrameChannel sink;
                        if (frameType == WebSocketFrameType.PING) {
                            sink = channel.send(WebSocketFrameType.PONG, size);
                        } else {
                            sink = channel.send(WebSocketFrameType.CLOSE, size);
                        }
                        sink.setFinalFragment(frame.isFinalFragment());
                        sink.setRsv(frame.getRsv());

                        WebSocketUtils.initiateTransfer(frame, sink, new ChannelListener<StreamSourceFrameChannel>() {
                                    @Override
                                    public void handleEvent(StreamSourceFrameChannel streamSourceFrameChannel) {
                                        if (streamSourceFrameChannel.getType() == WebSocketFrameType.CLOSE) {
                                            // TODO: set the correct CloseReason
                                            endpoint.onClose(WebSocketChannelSession.this, null);
                                        }
                                        close(streamSourceFrameChannel);
                                    }
                                }, new ChannelListener<StreamSinkFrameChannel>() {
                                    @Override
                                    public void handleEvent(StreamSinkFrameChannel streamSinkFrameChannel) {
                                        try {
                                            streamSinkFrameChannel.shutdownWrites();
                                        } catch (IOException e) {
                                            endpoint.onError(WebSocketChannelSession.this, e);
                                            IoUtils.safeClose(streamSinkFrameChannel, channel);
                                            return;
                                        }
                                        try {
                                            if (!streamSinkFrameChannel.flush()) {
                                                streamSinkFrameChannel.getWriteSetter().set(ChannelListeners.flushingChannelListener(
                                                        new ChannelListener<StreamSinkFrameChannel>() {
                                                            @Override
                                                            public void handleEvent(StreamSinkFrameChannel streamSinkFrameChannel) {
                                                                streamSinkFrameChannel.getWriteSetter().set(null);
                                                                IoUtils.safeClose(streamSinkFrameChannel);
                                                                if (streamSinkFrameChannel.getType() == WebSocketFrameType.CLOSE) {
                                                                    IoUtils.safeClose(channel);
                                                                }
                                                            }
                                                        }, new ChannelExceptionHandler<StreamSinkFrameChannel>() {
                                                            @Override
                                                            public void handleException(StreamSinkFrameChannel streamSinkFrameChannel, IOException e) {
                                                                endpoint.onError(WebSocketChannelSession.this, e);
                                                                IoUtils.safeClose(streamSinkFrameChannel, channel);

                                                            }
                                                        }
                                                ));
                                                streamSinkFrameChannel.resumeWrites();
                                            } else {
                                                if (streamSinkFrameChannel.getType() == WebSocketFrameType.CLOSE) {
                                                    IoUtils.safeClose(channel);
                                                }
                                                streamSinkFrameChannel.getWriteSetter().set(null);
                                                IoUtils.safeClose(streamSinkFrameChannel);
                                            }
                                        } catch (IOException e) {
                                            endpoint.onError(WebSocketChannelSession.this, e);
                                            IoUtils.safeClose(streamSinkFrameChannel, channel);

                                        }
                                    }
                                }, new ChannelExceptionHandler<StreamSourceFrameChannel>() {
                                    @Override
                                    public void handleException(StreamSourceFrameChannel streamSourceFrameChannel, IOException e) {
                                        endpoint.onError(WebSocketChannelSession.this, e);
                                        IoUtils.safeClose(streamSourceFrameChannel, channel);
                                    }
                                }, new ChannelExceptionHandler<StreamSinkFrameChannel>() {
                                    @Override
                                    public void handleException(StreamSinkFrameChannel streamSinkFrameChannel, IOException e) {
                                        endpoint.onError(WebSocketChannelSession.this, e);
                                        IoUtils.safeClose(streamSinkFrameChannel, channel);
                                    }
                                }, channel.getBufferPool()
                        );
                    }

                    // no handler just discard the frame
                    frame.discard();
                }
                MessageHandler handler;
                if (frameType == WebSocketFrameType.PONG) {
                    handler = new MessageHandler.Basic<ByteBuffer>() {
                        @Override
                        public void onMessage(ByteBuffer byteBuffer) {
                            ((MessageHandler.Basic) messageHandler).onMessage(new DefaultPongMessage(byteBuffer));
                        }
                    };
                } else {
                    handler = messageHandler;
                }
                final Class<?> clazz = typeOf(handler.getClass());

                if (handler instanceof MessageHandler.Async) {
                    final MessageHandler.Async asyncHandler = (MessageHandler.Async) handler;
                    final Pooled<ByteBuffer> pooled = webSocketChannel.getBufferPool().allocate();
                    final ByteBuffer buffer = pooled.getResource();
                    boolean free = true;
                    try {
                        for (;;) {
                            // Not clear from the spec / javadocs if this is ok but otherwise it
                            // just will use to much resources and using a pool will not work out
                            buffer.clear();
                            int r = frame.read(buffer);

                            if (r == -1) {
                                IoUtils.safeClose(frame);
                                // This seems to be valid in terms of spec
                                notifyOnMessage(asyncHandler, Buffers.EMPTY_BYTE_BUFFER, true, clazz);

                            }
                            if (r == 0) {
                                free = false;
                                frame.getReadSetter().set(new ChannelListener<StreamSourceChannel>() {
                                    @Override
                                    public void handleEvent(StreamSourceChannel source) {
                                        boolean free = true;
                                        try {
                                            for (;;) {
                                                // Not clear from the spec / javadocs if this is ok but otherwise it
                                                // just will use to much resources and using a pool will not work out
                                                buffer.clear();
                                                int r = source.read(buffer);

                                                if (r == -1) {
                                                    source.getReadSetter().set(null);
                                                    // This seems to be valid in terms of spec
                                                    notifyOnMessage(asyncHandler, Buffers.EMPTY_BYTE_BUFFER, true, clazz);
                                                    close((StreamSourceFrameChannel) source);
                                                }
                                                if (r == 0) {
                                                    free = false;
                                                    source.resumeReads();
                                                    return;
                                                }
                                                buffer.flip();
                                                notifyOnMessage(asyncHandler, buffer, false, clazz);

                                            }
                                        } catch (Throwable cause) {
                                            endpoint.onError(WebSocketChannelSession.this, cause);
                                            IoUtils.safeClose(channel, source);

                                        } finally {
                                            if (free) {
                                                pooled.free();
                                            }
                                        }
                                    }
                                });
                                frame.resumeReads();
                                return;
                            }
                            buffer.flip();
                            notifyOnMessage(asyncHandler, buffer, false, clazz);

                        }
                    } catch (Throwable cause) {
                        endpoint.onError(WebSocketChannelSession.this, cause);
                        close(frame);
                    } finally {
                        if (free) {
                            pooled.free();
                        }
                    }
                } else if (handler instanceof MessageHandler.Basic) {
                    final MessageHandler.Basic basicHandler = (MessageHandler.Basic) handler;

                    long size = frame.getPayloadSize();
                    final ByteBuffer buffer = ByteBuffer.allocate((int) size);
                    for (;;) {
                        int r = frame.read(buffer);
                        if (r == 0) {
                            frame.getReadSetter().set(new ChannelListener<StreamSourceChannel>() {
                                @Override
                                public void handleEvent(StreamSourceChannel source) {
                                    try {
                                        for (;;) {
                                            int r = source.read(buffer);
                                            if (r == 0) {
                                                source.resumeReads();
                                                return;
                                            }
                                            if (r == -1) {
                                                source.getReadSetter().set(null);
                                                buffer.flip();
                                                notifyOnMessage(basicHandler, buffer, clazz);
                                                close((StreamSourceFrameChannel) source);
                                            }
                                        }
                                    } catch (Throwable cause) {
                                        endpoint.onError(WebSocketChannelSession.this, cause);
                                        IoUtils.safeClose(channel, source);
                                    }
                                }
                            });
                        }
                        if (r == -1) {
                            buffer.flip();
                            notifyOnMessage(basicHandler, buffer, clazz);
                            close(frame);
                        }
                    }

                }
            } catch (IOException e) {
                endpoint.onError(WebSocketChannelSession.this, e);
                IoUtils.safeClose(channel);
            }
        }

        private void close(StreamSourceFrameChannel source) {
            if (source.isFinalFragment()) {
                // final frame receive the stored type information
                type = null;
            }
            IoUtils.safeClose(source);
        }

        private void notifyOnMessage(MessageHandler.Basic basicHandler, ByteBuffer buffer, Class<?> type) {
            if (type == ByteBuffer.class) {
                basicHandler.onMessage(buffer);
            } else if (type == byte[].class) {
                byte[] message;
                if (buffer.hasArray() && buffer.arrayOffset() == 0 && buffer.position() == 0 && buffer.remaining() == buffer.capacity()) {
                    message = buffer.array();
                } else {
                    message = new byte[buffer.remaining()];
                    buffer.get(message);
                }
                basicHandler.onMessage(message);
            }  else if (type == String.class) {
                String message;
                if (buffer.hasArray()) {
                    message = new String(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining(), WebSocketUtils.UTF_8);
                } else {
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    message = new String(bytes, WebSocketUtils.UTF_8);
                }
                basicHandler.onMessage(message);
            }
        }

        private void notifyOnMessage(MessageHandler.Async asyncHandler, ByteBuffer buffer, boolean last, Class<?> type) {
            if (type == ByteBuffer.class) {
                asyncHandler.onMessage(buffer, last);
            } else if (type == byte[].class) {
                byte[] message;
                if (buffer.hasArray() && buffer.arrayOffset() == 0 && buffer.position() == 0 && buffer.remaining() == buffer.capacity()) {
                    message = buffer.array();
                } else {
                    message = new byte[buffer.remaining()];
                    buffer.get(message);
                }
                asyncHandler.onMessage(message, last);
            }  else if (type == String.class) {
                String message;
                if (buffer.hasArray()) {
                    message = new String(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining(), WebSocketUtils.UTF_8);
                } else {
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    message = new String(bytes, WebSocketUtils.UTF_8);
                }
                asyncHandler.onMessage(message, last);
            }
        }
    }
}
