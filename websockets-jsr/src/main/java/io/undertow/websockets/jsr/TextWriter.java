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


import javax.websocket.RemoteEndpoint;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;



/**
 * {@link Writer} which can be used write a text message to a {@link RemoteEndpoint}.
 *
 * The message will be send on {@link #close()} but there is no guarantee that it will success, which is fine by the
 * spec.
 *
 * @author <a href="mailto:nmaurer@redhat.com">Norman Maurer</a>
 */
final class TextWriter extends StringWriter {
    private final RemoteEndpoint endpoint;

    /**
     * Construct a new instance using the given {@link RemoteEndpoint} to finally send the message on {@link #close()}
     */
    public TextWriter(RemoteEndpoint endpoint) {
        this.endpoint = endpoint;
    }

    @Override
    public void close() throws IOException {
        super.close();
        // As the spec says it MAY send the data I think it is ok to not block on send here
        endpoint.sendStringByCompletion(toString(), WebSocketJsrUtils.NOOP_SEND_HANDLER);
    }
}
