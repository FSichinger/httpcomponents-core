/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package org.apache.hc.core5.http.impl.nio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.hc.core5.http.ConnectionReuseStrategy;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpConnection;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.MethodNotSupportedException;
import org.apache.hc.core5.http.MisdirectedRequestException;
import org.apache.hc.core5.http.NotImplementedException;
import org.apache.hc.core5.http.ProtocolException;
import org.apache.hc.core5.http.ProtocolVersion;
import org.apache.hc.core5.http.UnsupportedHttpVersionException;
import org.apache.hc.core5.http.impl.LazyEntityDetails;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.apache.hc.core5.http.nio.AsyncPushProducer;
import org.apache.hc.core5.http.nio.AsyncResponseProducer;
import org.apache.hc.core5.http.nio.AsyncServerExchangeHandler;
import org.apache.hc.core5.http.nio.BasicResponseProducer;
import org.apache.hc.core5.http.nio.ContentDecoder;
import org.apache.hc.core5.http.nio.DataStreamChannel;
import org.apache.hc.core5.http.nio.ExpectationChannel;
import org.apache.hc.core5.http.nio.HandlerFactory;
import org.apache.hc.core5.http.nio.ResourceHolder;
import org.apache.hc.core5.http.nio.ResponseChannel;
import org.apache.hc.core5.http.nio.support.ImmediateResponseExchangeHandler;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.apache.hc.core5.http.protocol.HttpProcessor;
import org.apache.hc.core5.util.Asserts;

class ServerHttp1StreamHandler implements ResourceHolder {

    private final HttpConnection connection;
    private final Http1StreamChannel<HttpResponse> outputChannel;
    private final DataStreamChannel internalDataChannel;
    private final HttpProcessor httpProcessor;
    private final HandlerFactory<AsyncServerExchangeHandler> exchangeHandlerFactory;
    private final ConnectionReuseStrategy connectionReuseStrategy;
    private final HttpCoreContext context;
    private final ByteBuffer inputBuffer;
    private final AtomicBoolean responseCommitted;
    private final AtomicBoolean done;

    private volatile AsyncServerExchangeHandler exchangeHandler;
    private volatile HttpRequest receivedRequest;
    private volatile HttpResponse committedResponse;
    private volatile MessageState requestState;
    private volatile MessageState responseState;

    ServerHttp1StreamHandler(
            final HttpConnection connection,
            final Http1StreamChannel<HttpResponse> outputChannel,
            final HttpProcessor httpProcessor,
            final ConnectionReuseStrategy connectionReuseStrategy,
            final HandlerFactory<AsyncServerExchangeHandler> exchangeHandlerFactory,
            final ByteBuffer inputBuffer) {
        this.connection = connection;
        this.outputChannel = outputChannel;
        this.internalDataChannel = new DataStreamChannel() {

            @Override
            public void requestOutput() {
                outputChannel.requestOutput();
            }

            @Override
            public void endStream(final List<Header> trailers) throws IOException {
                outputChannel.complete();
                responseState = MessageState.COMPLETE;
            }

            @Override
            public int write(final ByteBuffer src) throws IOException {
                return outputChannel.write(src);
            }

            @Override
            public void endStream() throws IOException {
                endStream(null);
            }

        };

        this.httpProcessor = httpProcessor;
        this.connectionReuseStrategy = connectionReuseStrategy;
        this.exchangeHandlerFactory = exchangeHandlerFactory;
        this.context = HttpCoreContext.create();
        this.inputBuffer = inputBuffer;
        this.responseCommitted = new AtomicBoolean(false);
        this.done = new AtomicBoolean(false);
        this.requestState = MessageState.HEADERS;
        this.responseState = MessageState.IDLE;
    }

    private void validateResponse(
            final HttpResponse response,
            final EntityDetails responseEntityDetails) throws HttpException {
        final int status = response.getCode();
        switch (status) {
            case HttpStatus.SC_NO_CONTENT:
            case HttpStatus.SC_NOT_MODIFIED:
                if (responseEntityDetails != null) {
                    throw new HttpException("Response " + status + " must not enclose an entity");
                }
        }
    }

    private void commitResponse(
            final HttpResponse response,
            final EntityDetails responseEntityDetails) throws HttpException, IOException {
        if (responseCommitted.compareAndSet(false, true)) {

            Asserts.notNull(receivedRequest, "Received request");
            final String method = receivedRequest.getMethod();
            context.setAttribute(HttpCoreContext.HTTP_RESPONSE, response);
            httpProcessor.process(response, responseEntityDetails, context);

            final boolean endStream = responseEntityDetails == null || method.equalsIgnoreCase("HEAD");
            outputChannel.submit(response, endStream);
            committedResponse = response;
            if (endStream) {
                responseState = MessageState.COMPLETE;
            } else {
                responseState = MessageState.BODY;
                exchangeHandler.produce(internalDataChannel);
            }
        } else {
            throw new HttpException("Response already committed");
        }
    }

    private void commitContinue() throws IOException, HttpException {
        final HttpResponse ack = new BasicHttpResponse(HttpStatus.SC_CONTINUE);
        outputChannel.submit(ack, false);
        responseState = MessageState.ACK;
    }

    private void commitPromise() throws HttpException {
        throw new ProtocolException("HTTP/1.1 does not support server push");
    }

    void activateChannel() throws IOException, HttpException {
        outputChannel.activate();
    }

    boolean isResponseCompleted() {
        return responseState == MessageState.COMPLETE;
    }

    boolean isCompleted() {
        return requestState == MessageState.COMPLETE && responseState == MessageState.COMPLETE;
    }

    boolean keepAlive() {
        return receivedRequest != null && committedResponse != null &&
                connectionReuseStrategy.keepAlive(receivedRequest, committedResponse, context);
    }

    AsyncResponseProducer handleException(final Exception ex) {
        final int code;
        if (ex instanceof MethodNotSupportedException) {
            code = HttpStatus.SC_NOT_IMPLEMENTED;
        } else if (ex instanceof UnsupportedHttpVersionException) {
            code = HttpStatus.SC_HTTP_VERSION_NOT_SUPPORTED;
        } else if (ex instanceof NotImplementedException) {
            code = HttpStatus.SC_NOT_IMPLEMENTED;
        } else if (ex instanceof ProtocolException) {
            code = HttpStatus.SC_BAD_REQUEST;
        } else {
            code = HttpStatus.SC_INTERNAL_SERVER_ERROR;
        }
        String message = ex.getMessage();
        if (message == null) {
            message = ex.toString();
        }
        return new BasicResponseProducer(code, message);
    }

    void consumeHeader(final HttpRequest request, final boolean requestEndStream) throws HttpException, IOException {
        if (done.get() || requestState != MessageState.HEADERS) {
            throw new ProtocolException("Unexpected message head");
        }
        receivedRequest = request;
        requestState = requestEndStream ? MessageState.COMPLETE : MessageState.BODY;

        final EntityDetails requestEntityDetails = requestEndStream ? null : new LazyEntityDetails(request);
        boolean expectContinue = false;
        if (requestEntityDetails != null) {
            final Header h = request.getFirstHeader(HttpHeaders.EXPECT);
            if (h != null && "100-continue".equalsIgnoreCase(h.getValue())) {
                expectContinue = true;
            }
        }

        AsyncServerExchangeHandler handler;
        try {
            handler = exchangeHandlerFactory.create(request);
        } catch (MisdirectedRequestException ex) {
            handler =  new ImmediateResponseExchangeHandler(HttpStatus.SC_MISDIRECTED_REQUEST, ex.getMessage());
        } catch (HttpException ex) {
            handler =  new ImmediateResponseExchangeHandler(HttpStatus.SC_INTERNAL_SERVER_ERROR, ex.getMessage());
        }
        if (handler == null) {
            handler = new ImmediateResponseExchangeHandler(HttpStatus.SC_NOT_FOUND, "Cannot handle request");
        }

        exchangeHandler = handler;

        final ProtocolVersion transportVersion = request.getVersion();
        context.setProtocolVersion(transportVersion);
        context.setAttribute(HttpCoreContext.HTTP_REQUEST, request);
        context.setAttribute(HttpCoreContext.HTTP_CONNECTION, connection);

        exchangeHandler.setContext(context);

        try {
            httpProcessor.process(request, requestEntityDetails, context);
        } catch (HttpException ex) {
            expectContinue = false;
            final AsyncResponseProducer responseProducer = handleException(ex);
            exchangeHandler = new ImmediateResponseExchangeHandler(responseProducer);
        }

        if (expectContinue) {
            exchangeHandler.verify(request, requestEntityDetails, new ExpectationChannel() {

                @Override
                public void sendResponse(
                        final HttpResponse response, final EntityDetails responseEntityDetails) throws HttpException, IOException {
                    validateResponse(response, responseEntityDetails);
                    commitResponse(response, responseEntityDetails);
                }

                @Override
                public void sendContinue() throws HttpException, IOException {
                    commitContinue();
                }

            });
        } else {
            exchangeHandler.handleRequest(request, requestEntityDetails, new ResponseChannel() {

                @Override
                public void sendResponse(
                        final HttpResponse response, final EntityDetails responseEntityDetails) throws HttpException, IOException {
                    validateResponse(response, responseEntityDetails);
                    commitResponse(response, responseEntityDetails);
                }

                @Override
                public void pushPromise(
                        final HttpRequest promise, final AsyncPushProducer pushProducer) throws HttpException, IOException {
                    commitPromise();
                }

            });
        }
    }

    boolean isOutputReady() {
        switch (responseState) {
            case ACK:
                return true;
            case BODY:
                return exchangeHandler.available() > 0;
            default:
                return false;
        }
    }

    void produceOutput() throws HttpException, IOException {
        switch (responseState) {
            case ACK:
                responseState = MessageState.HEADERS;
                Asserts.notNull(receivedRequest, "Received request");
                exchangeHandler.handleRequest(receivedRequest, new LazyEntityDetails(receivedRequest), new ResponseChannel() {

                    @Override
                    public void sendResponse(
                            final HttpResponse response, final EntityDetails responseEntityDetails) throws HttpException, IOException {
                        validateResponse(response, responseEntityDetails);
                        commitResponse(response, responseEntityDetails);
                    }

                    @Override
                    public void pushPromise(
                            final HttpRequest promise, final AsyncPushProducer pushProducer) throws HttpException, IOException {
                        commitPromise();
                    }

                });
                break;
            case BODY:
                exchangeHandler.produce(internalDataChannel);
                break;
        }
    }

    int consumeData(final ContentDecoder contentDecoder) throws HttpException, IOException {
        if (done.get() || requestState != MessageState.BODY) {
            throw new ProtocolException("Unexpected message data");
        }
        if (responseState == MessageState.ACK) {
            outputChannel.requestOutput();
        }
        int total = 0;
        int byteRead;
        while ((byteRead = contentDecoder.read(inputBuffer)) > 0) {
            total += byteRead;
            inputBuffer.flip();
            final int capacity = exchangeHandler.consume(inputBuffer);
            inputBuffer.clear();
            if (capacity <= 0) {
                if (!contentDecoder.isCompleted()) {
                    outputChannel.suspendInput();
                    exchangeHandler.updateCapacity(outputChannel);
                }
                break;
            }
        }
        if (contentDecoder.isCompleted()) {
            requestState = MessageState.COMPLETE;
            exchangeHandler.streamEnd(null);
            return total > 0 ? total : -1;
        } else {
            return total;
        }
    }

    void failed(final Exception cause) {
        exchangeHandler.failed(cause);
    }

    @Override
    public void releaseResources() {
        if (done.compareAndSet(false, true)) {
            requestState = MessageState.COMPLETE;
            responseState = MessageState.COMPLETE;
            exchangeHandler.releaseResources();
        }
    }

    @Override
    public String toString() {
        return "[" +
                "requestState=" + requestState +
                ", responseState=" + responseState +
                ']';
    }

}
