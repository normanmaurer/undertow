package io.undertow.server.handlers.encoding;

import java.util.List;

import io.undertow.server.ConduitWrapper;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;
import io.undertow.util.ConduitFactory;
import io.undertow.util.Headers;
import io.undertow.util.Methods;
import org.xnio.conduits.StreamSinkConduit;

/**
 * An attachment that provides information about the current content encoding that will be chosen for the response
 *
 * @author Stuart Douglas
 */
public class ContentEncoding implements ConduitWrapper<StreamSinkConduit> {

    public static final AttachmentKey<ContentEncoding> CONENT_ENCODING = AttachmentKey.create(ContentEncoding.class);

    private final HttpServerExchange exchange;
    private final List<EncodingMapping> encodings;


    public ContentEncoding(final HttpServerExchange exchange, final List<EncodingMapping> encodings) {
        this.exchange = exchange;
        this.encodings = encodings;
    }

    /**
     * @return The content encoding that will be set, given the current state of the HttpServerExchange
     */
    public String getCurrentContentEncoding() {
        for (EncodingMapping encoding : encodings) {
            if (encoding.getAllowed().resolve(exchange)) {
                return encoding.getName();
            }
        }
        return Headers.IDENTITY.toString();
    }

    @Override
    public StreamSinkConduit wrap(final ConduitFactory<StreamSinkConduit> factory, final HttpServerExchange exchange) {
        //if this is a zero length response we don't want to encode
        if(exchange.getResponseContentLength() != 0
                && exchange.getResponseCode() != 204
                && exchange.getResponseCode() != 304) {
            for (EncodingMapping encoding : encodings) {
                if (encoding.getAllowed().resolve(exchange)) {
                    exchange.getResponseHeaders().put(Headers.CONTENT_ENCODING, encoding.getName());
                    if(exchange.getRequestMethod().equals(Methods.HEAD)) {
                        //we don't create an actual encoder for HEAD requests, but we set the header
                        return factory.create();
                    } else {
                        return encoding.getEncoding().getResponseWrapper().wrap(factory, exchange);
                    }
                }
            }
        }
        return factory.create();
    }
}
