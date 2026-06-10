/*
 * Copyright (c) 2026 Oracle and/or its affiliates.
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
package io.helidon.webclient.grpc.tests;

import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.common.configurable.Resource;
import io.helidon.common.tls.Tls;
import io.helidon.webclient.grpc.GrpcClient;
import io.helidon.webclient.grpc.GrpcClientMethodDescriptor;
import io.helidon.webclient.grpc.GrpcServiceDescriptor;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.WebServerConfig;
import io.helidon.webserver.grpc.GrpcRouting;
import io.helidon.webserver.testing.junit5.ServerTest;
import io.helidon.webserver.testing.junit5.SetUpRoute;
import io.helidon.webserver.testing.junit5.SetUpServer;

import io.grpc.CallCredentials;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests that CallCredentials configured on service descriptors, method descriptors,
 * and stubs are applied to outgoing RPCs.
 */
@ServerTest
class GrpcCallCredentialsTest {

    // Captures the Authorization header value seen by the server on each call.
    // Must be static: @SetUpRoute is a static method, so the ServerInterceptor lambda
    // cannot close over instance state. Sequential test execution prevents cross-test
    // contamination; @BeforeEach resets the value before each method.
    private static final AtomicReference<String> CAPTURED_AUTH = new AtomicReference<>();

    static final Metadata.Key<String> AUTHORIZATION_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final GrpcClient grpcClient;

    GrpcCallCredentialsTest(WebServer server) {
        Tls clientTls = Tls.builder()
                .trust(trust -> trust
                        .keystore(store -> store
                                .passphrase("password")
                                .trustStore(true)
                                .keystore(Resource.create("client.p12"))))
                .build();
        this.grpcClient = GrpcClient.builder()
                .tls(clientTls)
                .baseUri("https://localhost:" + server.port())
                .build();
    }

    @SetUpServer
    static void setUpServer(WebServerConfig.Builder builder) {
        builder.tls(tls -> tls.privateKey(key -> key
                        .keystore(store -> store
                                .passphrase("password")
                                .keystore(Resource.create("server.p12"))))
                .privateKeyCertChain(key -> key
                        .keystore(store -> store
                                .trustStore(true)
                                .passphrase("password")
                                .keystore(Resource.create("server.p12")))));
    }

    @SetUpRoute
    static void setUpRoute(GrpcRouting.Builder routing) {
        // Capture the Authorization header from every incoming call for assertion.
        routing.intercept(new ServerInterceptor() {
            @Override
            public <ReqT, ResT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, ResT> call,
                    Metadata headers,
                    ServerCallHandler<ReqT, ResT> next) {
                CAPTURED_AUTH.set(headers.get(AUTHORIZATION_KEY));
                return next.startCall(call, headers);
            }
        });
        routing.unary(Strings.getDescriptor(), "StringService", "Upper", GrpcCallCredentialsTest::upper);
    }

    static void upper(Strings.StringMessage req, StreamObserver<Strings.StringMessage> observer) {
        observer.onNext(Strings.StringMessage.newBuilder()
                .setText(req.getText().toUpperCase(Locale.ROOT))
                .build());
        observer.onCompleted();
    }

    @BeforeEach
    void resetCapturedAuth() {
        CAPTURED_AUTH.set(null);
    }

    static CallCredentials fixedBearerToken(String token) {
        return new CallCredentials() {
            @Override
            public void applyRequestMetadata(RequestInfo info, Executor exec, MetadataApplier applier) {
                Metadata headers = new Metadata();
                headers.put(AUTHORIZATION_KEY, "Bearer " + token);
                applier.apply(headers);
            }
        };
    }

    static Strings.StringMessage newStringMessage(String data) {
        return Strings.StringMessage.newBuilder().setText(data).build();
    }

    private GrpcServiceDescriptor serviceDescriptor(CallCredentials serviceCreds,
                                                     CallCredentials methodCreds) {
        GrpcClientMethodDescriptor.Builder methodBuilder =
                GrpcClientMethodDescriptor.unary("StringService", "Upper")
                        .requestType(Strings.StringMessage.class)
                        .responseType(Strings.StringMessage.class);
        if (methodCreds != null) {
            methodBuilder.callCredentials(methodCreds);
        }

        GrpcServiceDescriptor.Builder builder = GrpcServiceDescriptor.builder()
                .serviceName("StringService")
                .putMethod("Upper", methodBuilder.build());
        if (serviceCreds != null) {
            builder.callCredentials(serviceCreds);
        }
        return builder.build();
    }

    @Test
    void stubCredentialsApplied() {
        // Credentials attached via stub.withCallCredentials() must reach the server.
        StringServiceGrpc.StringServiceBlockingStub stub =
                StringServiceGrpc.newBlockingStub(grpcClient.channel())
                        .withCallCredentials(fixedBearerToken("stub-token"));

        stub.upper(newStringMessage("hello"));

        assertThat(CAPTURED_AUTH.get(), is("Bearer stub-token"));
    }

    @Test
    void serviceCredentialsApplied() {
        // Credentials configured on GrpcServiceDescriptor must be applied to every call.
        Strings.StringMessage res = grpcClient.serviceClient(serviceDescriptor(fixedBearerToken("service-token"), null))
                .unary("Upper", newStringMessage("hello"));

        assertThat(res.getText(), is("HELLO"));
        assertThat(CAPTURED_AUTH.get(), is("Bearer service-token"));
    }

    @Test
    void methodCredentialsOverrideServiceCredentials() {
        // Method-level credential wins over service-level credential
        Strings.StringMessage res = grpcClient.serviceClient(
                        serviceDescriptor(fixedBearerToken("service-token"), fixedBearerToken("method-token")))
                .unary("Upper", newStringMessage("hello"));

        assertThat(res.getText(), is("HELLO"));
        assertThat(CAPTURED_AUTH.get(), is("Bearer method-token"));
    }

    @Test
    void noCredentialsSucceeds() {
        // Calls with no credentials at any level must succeed normally
        Strings.StringMessage res = grpcClient.serviceClient(serviceDescriptor(null, null))
                .unary("Upper", newStringMessage("hello"));

        assertThat(res.getText(), is("HELLO"));
        assertThat(CAPTURED_AUTH.get(), is(nullValue()));
    }

    @Test
    void credentialsAppliedPerCall() {
        // applyRequestMetadata() must be called fresh for each RPC — not cached.
        // A credential that returns a counter-based token verifies this.
        AtomicInteger counter = new AtomicInteger(0);
        CallCredentials countingCreds = new CallCredentials() {
            @Override
            public void applyRequestMetadata(RequestInfo info, Executor exec, MetadataApplier applier) {
                Metadata headers = new Metadata();
                headers.put(AUTHORIZATION_KEY, "Bearer call-" + counter.incrementAndGet());
                applier.apply(headers);
            }
        };

        grpcClient.serviceClient(serviceDescriptor(countingCreds, null))
                .unary("Upper", newStringMessage("a"));
        grpcClient.serviceClient(serviceDescriptor(countingCreds, null))
                .unary("Upper", newStringMessage("b"));
        grpcClient.serviceClient(serviceDescriptor(countingCreds, null))
                .unary("Upper", newStringMessage("c"));

        assertThat(counter.get(), is(3));
        // Last call's token should be "call-3"
        assertThat(CAPTURED_AUTH.get(), is("Bearer call-3"));
    }

    @Test
    void asyncCredentialsApplied() {
        // Credentials that complete the applier from a background thread must work.
        // A virtual thread completes the MetadataApplier off the calling thread,
        // verifying that GrpcBaseClientCall blocks until the credential finishes.
        CallCredentials asyncCreds = new CallCredentials() {
            @Override
            public void applyRequestMetadata(RequestInfo info, Executor exec, MetadataApplier applier) {
                Thread.ofVirtual().start(() -> {
                    Metadata headers = new Metadata();
                    headers.put(AUTHORIZATION_KEY, "Bearer async-token");
                    applier.apply(headers);
                });
            }
        };

        Strings.StringMessage res = grpcClient.serviceClient(serviceDescriptor(asyncCreds, null))
                .unary("Upper", newStringMessage("hello"));

        assertThat(res.getText(), is("HELLO"));
        assertThat(CAPTURED_AUTH.get(), is("Bearer async-token"));
    }

    @Test
    void credentialFailureReported() {
        // A credential that calls applier.fail() should cause the RPC to fail
        // with the status the credential reported. Verifies that Status.fromThrowable()
        // recovers the original status code through the asException() roundtrip.
        CallCredentials rejectingCreds = new CallCredentials() {
            @Override
            public void applyRequestMetadata(RequestInfo info, Executor exec, MetadataApplier applier) {
                applier.fail(Status.UNAUTHENTICATED.withDescription("token rejected"));
            }
        };

        StringServiceGrpc.StringServiceBlockingStub stub =
                StringServiceGrpc.newBlockingStub(grpcClient.channel())
                        .withCallCredentials(rejectingCreds);

        StatusRuntimeException thrown = assertThrows(
                StatusRuntimeException.class,
                () -> stub.upper(newStringMessage("hello")));

        assertThat(thrown.getStatus().getCode(), is(Status.Code.UNAUTHENTICATED));
    }

    @Test
    void credentialTimeoutExceeded() {
        // A credential that never calls the applier should cause the call to fail
        // with DEADLINE_EXCEEDED once the call deadline expires.
        CallCredentials hangingCreds = new CallCredentials() {
            @Override
            public void applyRequestMetadata(RequestInfo info, Executor exec, MetadataApplier applier) {
                // intentionally never calls applier.apply() or applier.fail()
            }
        };

        StringServiceGrpc.StringServiceBlockingStub stub =
                StringServiceGrpc.newBlockingStub(grpcClient.channel())
                        .withCallCredentials(hangingCreds)
                        .withDeadlineAfter(200, TimeUnit.MILLISECONDS);

        StatusRuntimeException thrown = assertThrows(
                StatusRuntimeException.class,
                () -> stub.upper(newStringMessage("hello")));

        assertThat(thrown.getStatus().getCode(), is(Status.Code.DEADLINE_EXCEEDED));
    }
}
