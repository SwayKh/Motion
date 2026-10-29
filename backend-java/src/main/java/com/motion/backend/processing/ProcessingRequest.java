package com.motion.backend.processing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** Wire-compatible {@code ProcessRequest}; unknown fields are ignored like the decoder. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder("operations")
public record ProcessingRequest(List<Operation> operations) {

    public ProcessingRequest {
        operations = operations == null ? List.of() : List.copyOf(operations);
    }
}
