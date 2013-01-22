/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012 Red Hat, Inc., and individual contributors
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
package io.undertow.websockets;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.xnio.Buffers;
import org.xnio.ChannelExceptionHandler;
import org.xnio.ChannelListener;
import org.xnio.ChannelListeners;
import org.xnio.Pool;
import org.xnio.Pooled;
import org.xnio.channels.StreamSinkChannel;
import org.xnio.channels.StreamSourceChannel;

/**
 * Utility class which holds general useful utility methods which
 * can be used within WebSocket implementations.
 *
 * @author <a href="mailto:nmaurer@redhat.com">Norman Maurer</a>
 */
public final class WebSocketUtils {

    /**
     * UTF-8 {@link Charset} which is used to encode Strings in WebSockets
     */
    public static final Charset UTF_8 = Charset.forName("UTF-8");

    /**
     * Generate the MD5 hash out of the given {@link ByteBuffer}
     */
    public static ByteBuffer md5(ByteBuffer buffer) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(buffer);
            return ByteBuffer.wrap(md.digest());
        } catch (NoSuchAlgorithmException e) {
            // Should never happen
            throw new InternalError("MD5 not supported on this platform");
        }
    }

    /**
     * Create a {@link ByteBuffer} which holds the UTF8 encoded bytes for the
     * given {@link String}.
     *
     * @param utfString The {@link String} to convert
     * @return buffer   The {@link ByteBuffer} which was created
     */
    public static ByteBuffer fromUtf8String(String utfString) {
        if (utfString == null || utfString.isEmpty()) {
            return Buffers.EMPTY_BYTE_BUFFER;
        } else {
            return ByteBuffer.wrap(utfString.getBytes(UTF_8));
        }
    }

    /**
     * Transfer the data from the source to the sink using the given throughbuffer to pass data through.
     */
    public static long transfer(final ReadableByteChannel source, final long count, final ByteBuffer throughBuffer, final WritableByteChannel sink) throws IOException {
        long total = 0L;
        while (total < count) {
            throughBuffer.clear();
            if (count - total < throughBuffer.remaining()) {
                throughBuffer.limit((int) (count - total));
            }

            try {
                long res = source.read(throughBuffer);
                if (res <= 0) {
                    return total == 0L ? res : total;
                }
            } finally {
                throughBuffer.flip();

            }
            while (throughBuffer.hasRemaining()) {
                long res = sink.write(throughBuffer);
                if (res <= 0) {
                    return total;
                }
                total += res;
            }
        }
        return total;
    }



    /**
     * Initiate a low-copy transfer between two stream channels.  The pool should be a direct buffer pool for best
     * performance.
     *
     * @param source                the source channel
     * @param sink                  the target channel
     * @param sourceListener        the source listener to set and call when the transfer is complete, or {@code null} to clear the listener at that time
     * @param sinkListener          the target listener to set and call when the transfer is complete, or {@code null} to clear the listener at that time
     * @param readExceptionHandler  the read exception handler to call if an error occurs during a read operation
     * @param writeExceptionHandler the write exception handler to call if an error occurs during a write operation
     * @param pool                  the pool from which the transfer buffer should be allocated
     */
    public static <I extends StreamSourceChannel, O extends StreamSinkChannel> void initiateTransfer(final I source, final O sink, final ChannelListener<? super I> sourceListener, final ChannelListener<? super O> sinkListener, final ChannelExceptionHandler<? super I> readExceptionHandler, final ChannelExceptionHandler<? super O> writeExceptionHandler, Pool<ByteBuffer> pool) {
        if (pool == null) {
            throw new IllegalArgumentException("pool is null");
        }
        final Pooled<ByteBuffer> allocated = pool.allocate();
        boolean free = true;
        try {
            final ByteBuffer buffer = allocated.getResource();
            buffer.clear();
            long transferred;
            do {
                try {
                    transferred = source.transferTo(Long.MAX_VALUE, buffer, sink);
                } catch (IOException e) {
                    invokeChannelExceptionHandler(source, readExceptionHandler, e);
                    return;
                }
                if (transferred == -1) {
                    source.suspendReads();
                    sink.suspendWrites();
                    ChannelListeners.invokeChannelListener(source, sourceListener);
                    ChannelListeners.invokeChannelListener(sink, sinkListener);
                    return;
                }
                while (buffer.hasRemaining()) {
                    final int res;
                    try {
                        res = sink.write(buffer);
                    } catch (IOException e) {
                        invokeChannelExceptionHandler(sink, writeExceptionHandler, e);
                        return;
                    }
                    if (res == 0) {
                        // write first listener
                        final TransferListener<I, O> listener = new TransferListener<I, O>(allocated, source, sink, sourceListener, sinkListener, writeExceptionHandler, readExceptionHandler, 1);
                        source.suspendReads();
                        source.getReadSetter().set(listener);
                        sink.getWriteSetter().set(listener);
                        sink.resumeWrites();
                        free = false;
                        return;
                    } else if (res == -1) {
                        source.suspendReads();
                        sink.suspendWrites();
                        ChannelListeners.invokeChannelListener(source, sourceListener);
                        ChannelListeners.invokeChannelListener(sink, sinkListener);
                        return;
                    }
                }
            } while (transferred > 0L);
            final TransferListener<I, O> listener = new TransferListener<I, O>(allocated, source, sink, sourceListener, sinkListener, writeExceptionHandler, readExceptionHandler, 0);
            sink.suspendWrites();
            sink.getWriteSetter().set(listener);
            source.getReadSetter().set(listener);
            // read first listener
            sink.suspendWrites();
            source.resumeReads();
            free = false;
        } finally {
            if (free) {
                allocated.free();
            }
        }
    }


    /**
     * Safely invoke a channel exception handler, logging any errors.
     *
     * @param channel          the channel
     * @param exceptionHandler the exception handler
     * @param exception        the exception to pass in
     * @param <T>              the exception type
     */
    public static <T extends Channel> void invokeChannelExceptionHandler(final T channel, final ChannelExceptionHandler<? super T> exceptionHandler, final IOException exception) {
        try {
            exceptionHandler.handleException(channel, exception);
        } catch (Throwable t) {
            WebSocketLogger.REQUEST_LOGGER.errorf(t, "A channel exception handler threw an exception");
        }
    }


    static final class TransferListener<I extends StreamSourceChannel, O extends StreamSinkChannel> implements ChannelListener<Channel> {
        private final Pooled<ByteBuffer> pooledBuffer;
        private final I source;
        private final O sink;
        private final ChannelListener<? super I> sourceListener;
        private final ChannelListener<? super O> sinkListener;
        private final ChannelExceptionHandler<? super O> writeExceptionHandler;
        private final ChannelExceptionHandler<? super I> readExceptionHandler;
        private volatile int state;

        TransferListener(final Pooled<ByteBuffer> pooledBuffer, final I source, final O sink, final ChannelListener<? super I> sourceListener, final ChannelListener<? super O> sinkListener, final ChannelExceptionHandler<? super O> writeExceptionHandler, final ChannelExceptionHandler<? super I> readExceptionHandler, final int state) {
            this.pooledBuffer = pooledBuffer;
            this.source = source;
            this.sink = sink;
            this.sourceListener = sourceListener;
            this.sinkListener = sinkListener;
            this.writeExceptionHandler = writeExceptionHandler;
            this.readExceptionHandler = readExceptionHandler;
            this.state = state;
        }

        @Override
        public void handleEvent(final Channel channel) {
            final ByteBuffer buffer = pooledBuffer.getResource();
            int state = this.state;
            long lres;
            int ires;

            switch (state) {
                case 0: {
                    // read listener
                    for (; ; ) {
                        if(buffer.hasRemaining()) {
                            WebSocketLogger.REQUEST_LOGGER.error("BUFFER HAS REMAINING!!!!!");
                        }
                        try {
                            lres = source.transferTo(Long.MAX_VALUE, buffer, sink);
                        } catch (IOException e) {
                            readFailed(e);
                            return;
                        }
                        if (lres == 0 && !buffer.hasRemaining()) {
                            return;
                        }
                        if (lres == -1) {
                            // possibly unexpected EOF
                            // it's OK; just be done
                            done();
                            return;
                        }
                        while (buffer.hasRemaining()) {
                            try {
                                ires = sink.write(buffer);
                            } catch (IOException e) {
                                writeFailed(e);
                                return;
                            }
                            if (ires == 0) {
                                this.state = 1;
                                source.suspendReads();
                                sink.resumeWrites();
                                return;
                            }
                        }
                    }
                }
                case 1: {
                    // write listener
                    for (; ; ) {
                        while (buffer.hasRemaining()) {
                            try {
                                ires = sink.write(buffer);
                            } catch (IOException e) {
                                writeFailed(e);
                                return;
                            }
                            if (ires == 0) {
                                return;
                            }
                        }
                        try {
                            lres = source.transferTo(Long.MAX_VALUE, buffer, sink);
                        } catch (IOException e) {
                            readFailed(e);
                            return;
                        }
                        if (lres == 0 && !buffer.hasRemaining()) {
                            this.state = 0;
                            sink.suspendWrites();
                            source.resumeReads();
                            return;
                        }
                        if (lres == -1) {
                            done();
                            return;
                        }
                    }
                }
            }
        }

        private void writeFailed(final IOException e) {
            try {
                source.suspendReads();
                sink.suspendWrites();
                invokeChannelExceptionHandler(sink, writeExceptionHandler, e);
            } finally {
                pooledBuffer.free();
            }
        }

        private void readFailed(final IOException e) {
            try {
                source.suspendReads();
                sink.suspendWrites();
                invokeChannelExceptionHandler(source, readExceptionHandler, e);
            } finally {
                pooledBuffer.free();
            }
        }

        private void done() {
            try {
                final ChannelListener<? super I> sourceListener = this.sourceListener;
                final ChannelListener<? super O> sinkListener = this.sinkListener;
                final I source = this.source;
                final O sink = this.sink;
                source.suspendReads();
                sink.suspendWrites();

                ChannelListeners.invokeChannelListener(source, sourceListener);
                ChannelListeners.invokeChannelListener(sink, sinkListener);
            } finally {
                pooledBuffer.free();
            }
        }

        public String toString() {
            return "Transfer channel listener (" + source + " to " + sink + ") -> (" + sourceListener + " and " + sinkListener + ')';
        }
    }

    private WebSocketUtils() {
        // utility class
    }
}
