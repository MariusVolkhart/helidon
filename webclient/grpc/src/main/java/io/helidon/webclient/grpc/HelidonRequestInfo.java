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
package io.helidon.webclient.grpc;

import io.grpc.Attributes;
import io.grpc.CallCredentials;
import io.grpc.CallOptions;
import io.grpc.MethodDescriptor;
import io.grpc.SecurityLevel;

/**
 * Per-RPC context supplied to {@link io.grpc.CallCredentials#applyRequestMetadata} before each call.
 *
 * <p>The security level reflects the channel's TLS configuration:
 * {@link SecurityLevel#PRIVACY_AND_INTEGRITY} when TLS is enabled,
 * {@link SecurityLevel#NONE} on plaintext connections. Credentials that refuse to transmit
 * tokens on insecure channels should check {@link #getSecurityLevel()}.
 *
 * <p>{@link #getTransportAttrs()} always returns {@link Attributes#EMPTY}; Helidon does not
 * expose a gRPC transport attribute bag.
 */
class HelidonRequestInfo extends CallCredentials.RequestInfo {

    private final MethodDescriptor<?, ?> methodDescriptor;
    private final CallOptions callOptions;
    private final String authority;
    private final boolean tlsEnabled;

    HelidonRequestInfo(MethodDescriptor<?, ?> methodDescriptor,
                       CallOptions callOptions,
                       String authority,
                       boolean tlsEnabled) {
        this.methodDescriptor = methodDescriptor;
        this.callOptions = callOptions;
        this.authority = authority;
        this.tlsEnabled = tlsEnabled;
    }

    @Override
    public MethodDescriptor<?, ?> getMethodDescriptor() {
        return methodDescriptor;
    }

    @Override
    public SecurityLevel getSecurityLevel() {
        // Report the channel-level security so credentials can decide whether
        // it is safe to send bearer tokens (e.g., refuse on plaintext channels).
        return tlsEnabled ? SecurityLevel.PRIVACY_AND_INTEGRITY : SecurityLevel.NONE;
    }

    @Override
    public String getAuthority() {
        return authority;
    }

    @Override
    public Attributes getTransportAttrs() {
        // Helidon does not expose a gRPC transport attribute bag; return empty.
        return Attributes.EMPTY;
    }

    @Override
    public CallOptions getCallOptions() {
        return callOptions;
    }
}
