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
import org.xnio.ChannelExceptionHandler;
import org.xnio.ChannelListener;
import org.xnio.ChannelListeners;
import org.xnio.IoUtils;
import org.xnio.channels.StreamSinkChannel;

import javax.websocket.SendHandler;
import javax.websocket.SendResult;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.util.concurrent.ExecutionException;

/**
 * Utility methods used accross the JSR WebSocket implementation
 *
 * @author <a href="mailto:nmaurer@redhat.com">Norman Maurer</a>
 */
public final class WebSocketJsrUtils {
    public static final SendResult OK = new SendResult();

    public static final SendHandler NOOP_SEND_HANDLER = new SendHandler() {
        @Override
        public void setResult(SendResult sendResult) {
            // do nothing;
        }
    };

    /**
     * Wrap the given {@link Throwable} in an {@link IOException}. This is only true for checked-exception, unchecked exceptions will
     * be rethrown.
     */
    public static IOException wrap(Throwable cause) {
        if (cause instanceof IOException) {
            return (IOException) cause;
        }
        if (cause instanceof ExecutionException) {
            Throwable wrapped = cause.getCause();
            if (wrapped instanceof  IOException) {
                return (IOException) wrapped;
            }
            rethrowUnchecked(wrapped);
        }
        rethrowUnchecked(cause);
        return new IOException(cause);
    }

    private static void rethrowUnchecked(Throwable cause) {
        if (cause instanceof Error) {
            throw (Error) cause;
        }
        if (cause instanceof RuntimeException) {
            throw (RuntimeException) cause;
        }
    }

    /**
     * Send the given {@link ByteBuffer} via the {@link StreamSinkFrameChannel} and notify the {@link SendHandler} once
     * the operation completes.
     */
    public static void send(final StreamSinkChannel sink, final ByteBuffer buffer, final SendHandler sendHandler) {
        try {
            while (buffer.hasRemaining()) {
                if (sink.write(buffer) == 0) {
                    sink.getWriteSetter().set(new ChannelListener<StreamSinkChannel>() {
                        @Override
                        public void handleEvent(StreamSinkChannel sink) {
                            while (buffer.hasRemaining()) {
                                try {
                                    if (sink.write(buffer) == 0) {
                                        sink.resumeWrites();
                                        return;
                                    }
                                } catch (IOException e) {
                                    sendHandler.setResult(new SendResult(e));
                                }
                            }
                            writeDone(sink, sendHandler);
                        }
                    });
                    sink.resumeWrites();
                }
            }
            writeDone(sink, sendHandler);
        } catch (Throwable t) {
            sendHandler.setResult(new SendResult(t));
        }
    }

    private static void writeDone(final StreamSinkChannel channel, final SendHandler handler) {
        try {
            channel.shutdownWrites();
            if (!channel.flush()) {
                channel.getWriteSetter().set(ChannelListeners.flushingChannelListener(
                        new ChannelListener<StreamSinkChannel>() {
                            @Override
                            public void handleEvent(StreamSinkChannel o) {
                                IoUtils.safeClose(channel);
                                handler.setResult(OK);
                            }
                        }, new ChannelExceptionHandler<Channel>() {
                            @Override
                            public void handleException(Channel channel, IOException e) {
                                IoUtils.safeClose(channel);
                                handler.setResult(new SendResult(e));
                            }
                        }
                ));
                channel.resumeWrites();

            } else {
                IoUtils.safeClose(channel);
                handler.setResult(OK);
            }
        } catch (IOException e) {
            IoUtils.safeClose(channel);
            handler.setResult(new SendResult(e));
        }
    }

    private WebSocketJsrUtils() {
        // Utility class
    }
}
