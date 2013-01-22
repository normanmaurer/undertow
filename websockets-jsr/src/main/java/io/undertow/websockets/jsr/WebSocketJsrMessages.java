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

import org.jboss.logging.Messages;
import org.jboss.logging.annotations.Message;
import org.jboss.logging.annotations.MessageBundle;

/**
 * start at 3000
 *
 * @author <a href="mailto:nmaurer@redhat.com">Norman Maurer</a>
 */
@MessageBundle(projectCode = "UNDERTOW")
public interface WebSocketJsrMessages {

    WebSocketJsrMessages MESSAGES = Messages.getBundle(WebSocketJsrMessages.class);

    @Message(id = 3001, value = "MessageHandler must have a Type parameter")
    IllegalStateException missingHandlerTypeParam();

    @Message(id = 3002, value = "MessageHandler for type %s already exists")
    IllegalStateException handlerForTypeExists(String type);

    @Message(id = 3003, value = "Can't determine the message type for %s")
    IllegalStateException unsupportedHandlerType(Class<?> type);

    @Message(id = 3004, value = "PongMessage handling is only supported in MessageHandler.Basic implementations")
    IllegalArgumentException unsupportedPongHandler();
}
