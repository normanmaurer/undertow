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
import io.undertow.websockets.WebSocketFrameType;
import io.undertow.websockets.WebSocketUtils;
import org.xnio.ChannelListener;
import org.xnio.IoUtils;
import org.xnio.XnioExecutor;
import org.xnio.channels.StreamSinkChannel;

import javax.websocket.EncodeException;
import javax.websocket.Encoder;
import javax.websocket.RemoteEndpoint;
import javax.websocket.SendHandler;
import javax.websocket.SendResult;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 *
 * Default {@link RemoteEndpoint} implementation.
 *
 * @author <a href="mailto:nmaurer@redhat.com">Norman Maurer</a>
 */
final class DefaultRemoteEndpoint implements RemoteEndpoint {
    private final WebSocketChannelSession session;
    private volatile boolean batchingAllowed;
    private volatile long asyncSendTimeout;
    private BinaryOutputStream binaryStream;
    private TextWriter textWriter;

    public DefaultRemoteEndpoint(WebSocketChannelSession session) {
        this.session = session;
    }

    @Override
    public void setBatchingAllowed(boolean batchingAllowed) {
        this.batchingAllowed = batchingAllowed;
    }

    @Override
    public boolean getBatchingAllowed() {
        return batchingAllowed;
    }

    @Override
    public void flushBatch() {
        // Do nothing as we not batch yet
    }

    @Override
    public long getAsyncSendTimeout() {
        return asyncSendTimeout;
    }

    @Override
    public void setAsyncSendTimeout(long asyncSendTimeout) {
        this.asyncSendTimeout = asyncSendTimeout;
    }

    @Override
    public void sendString(String s) throws IOException {
        Future<SendResult> future = sendStringByFuture(s);
        waitForCompletion(future);
    }

    private static void waitForCompletion(Future<SendResult> future) throws IOException {
        try {
            SendResult result = future.get();
            if (!result.isOK()) {
                throw WebSocketJsrUtils.wrap(result.getException());
            }
        } catch (Throwable cause) {
            throw WebSocketJsrUtils.wrap(cause);
        }
    }

    @Override
    public void sendBytes(ByteBuffer byteBuffer) throws IOException {
        Future<SendResult> future = sendBytesByFuture(byteBuffer);
        waitForCompletion(future);
    }

    @Override
    public void sendPartialString(String s, boolean last) throws IOException {
        synchronized (this) {
            if (textWriter == null) {
                if (!last) {
                    textWriter = new TextWriter(this);
                }
            }
            if (textWriter != null) {
                textWriter.write(s);
                if (last) {
                    textWriter.close();
                    textWriter = null;
                }
                return;
            }
        }
        sendString(s);
    }

    @Override
    public void sendPartialBytes(ByteBuffer byteBuffer, boolean last) throws IOException {
        synchronized (this) {
            if (binaryStream == null) {
                if (!last) {
                    binaryStream = new BinaryOutputStream(this);
                }
            }
            if (binaryStream != null) {
                binaryStream.write(byteBuffer);
                if (last) {
                    binaryStream.close();
                    binaryStream = null;
                }
                return;
            }
        }
        sendBytes(byteBuffer);
    }

    @Override
    public OutputStream getSendStream() throws IOException {
        return new BinaryOutputStream(this);
    }

    @Override
    public Writer getSendWriter() throws IOException {
        return new TextWriter(this);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void sendObject(Object o) throws IOException, EncodeException {
        for (Encoder encoder: session.getConfig().getEncoders()) {
            if (encoder.getClass().getTypeParameters()[0].getClass() == o.getClass()) {
                if (encoder instanceof Encoder.Binary) {
                    sendBytes(((Encoder.Binary) encoder).encode(o));
                    return;
                } else if (encoder instanceof Encoder.BinaryStream) {
                    ((Encoder.BinaryStream) encoder).encode(o, getSendStream());
                    return;
                } else if (encoder instanceof Encoder.Text) {
                    sendString(((Encoder.Text) encoder).encode(o));
                    return;
                } else if (encoder instanceof Encoder.TextStream) {
                    ((Encoder.TextStream) encoder).encode(o, getSendWriter());
                    return;
                }
            }
        }
        throw new EncodeException(o, "No Encoder found");
    }

    @Override
    public void sendStringByCompletion(String s, SendHandler sendHandler) {
        final ByteBuffer buffer = WebSocketUtils.fromUtf8String(s);
        sendByCompletion(WebSocketFrameType.TEXT, buffer, sendHandler, getAsyncSendTimeout());
    }

    @Override
    public Future<SendResult> sendStringByFuture(String s) {
        DefaultFuture future = new DefaultFuture();
        try {
            final ByteBuffer buffer = WebSocketUtils.fromUtf8String(s);
            sendByCompletion(WebSocketFrameType.TEXT, buffer, future, -1);
        } catch (Throwable t) {
            future.setResult(new SendResult(t));
        }
        return future;
    }

    @Override
    public Future<SendResult> sendBytesByFuture(ByteBuffer byteBuffer) {
        DefaultFuture future = new DefaultFuture();
        try {
            sendByCompletion(WebSocketFrameType.BINARY, byteBuffer, future, -1);
        } catch (Throwable t) {
            future.setResult(new SendResult(t));
        }
        return future;
    }

    @Override
    public void sendBytesByCompletion(ByteBuffer byteBuffer, SendHandler sendHandler) {
        sendByCompletion(WebSocketFrameType.BINARY, byteBuffer, sendHandler, getAsyncSendTimeout());
    }

    private void sendByCompletion(WebSocketFrameType type, ByteBuffer byteBuffer, SendHandler sendHandler, long timeout) {
        try {
            StreamSinkFrameChannel sink = session.getChannel().send(type, byteBuffer.remaining());

            if (timeout > 0) {
                final XnioExecutor.Key key = sink.getWriteThread().executeAfter(new WriteTimeoutTask(session, sink), timeout, TimeUnit.MILLISECONDS);
                sink.getCloseSetter().set(new ChannelListener<StreamSinkChannel>() {
                    @Override
                    public void handleEvent(StreamSinkChannel sink) {
                        key.remove();
                    }
                });
            }
            WebSocketJsrUtils.send(sink, byteBuffer, sendHandler);
        } catch (Throwable cause) {
            sendHandler.setResult(new SendResult(cause));
        }
    }
    @Override
    public Future<SendResult> sendObjectByFuture(Object o) {
        DefaultFuture future = new DefaultFuture();
        try {
            sendObjectByCompletion(o, future);
        } catch (Throwable t) {
            future.setResult(new SendResult(t));
        }
        return future;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void sendObjectByCompletion(Object o, SendHandler sendHandler) {
        try {
            for (Encoder encoder : session.getConfig().getEncoders()) {
                if (encoder.getClass().getTypeParameters()[0].getClass() == o.getClass()) {
                    if (encoder instanceof Encoder.Binary) {
                        sendBytesByCompletion(((Encoder.Binary) encoder).encode(o), sendHandler);
                        return;
                    } else if (encoder instanceof Encoder.BinaryStream) {
                        ((Encoder.BinaryStream) encoder).encode(o, getSendStream());
                        return;
                    } else if (encoder instanceof Encoder.Text) {
                        sendStringByCompletion(((Encoder.Text) encoder).encode(o), sendHandler);
                        return;
                    } else if (encoder instanceof Encoder.TextStream) {
                        ((Encoder.TextStream) encoder).encode(o, getSendWriter());
                        return;
                    }
                }
            }
            throw new EncodeException(o, "No Encoder found");
        } catch (Throwable cause) {
            sendHandler.setResult(new SendResult(cause));
        }
    }

    @Override
    public void sendPing(ByteBuffer byteBuffer) {
        try {
            StreamSinkFrameChannel sink = session.getChannel().send(WebSocketFrameType.PING, byteBuffer.remaining());
            DefaultFuture future = new DefaultFuture();

            WebSocketJsrUtils.send(sink, byteBuffer, future);
            future.get();
        } catch (Throwable t) {
            // TODO: Is this correct ?
           session.getEndpoint().onError(session, t);
        }
    }

    @Override
    public void sendPong(ByteBuffer byteBuffer) {
        try {
            StreamSinkFrameChannel sink = session.getChannel().send(WebSocketFrameType.PONG, byteBuffer.remaining());
            DefaultFuture future = new DefaultFuture();
            WebSocketJsrUtils.send(sink, byteBuffer, future);
            future.get();
        } catch (Throwable t) {
            // TODO: Is this correct ?
            session.getEndpoint().onError(session, t);
        }
    }

    private static final class WriteTimeoutTask implements Runnable {
        private final WebSocketChannelSession session;
        private final StreamSinkFrameChannel sink;
        public WriteTimeoutTask(WebSocketChannelSession session, StreamSinkFrameChannel sink) {
            this.session = session;
            this.sink = sink;
        }
        @Override
        public void run() {
            // TODO: Proper status code ?
            try {
                session.close();
                IoUtils.safeClose(sink);
            } catch (IOException e) {
                // ignore
            }
        }
    }
}
