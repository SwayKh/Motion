package com.motion.backend.media;

import com.motion.backend.auth.ApiException;

/** Client-correctable upload failure. Go maps these to HTTP 400 with the original message. */
public class UploadClientException extends ApiException {

    public UploadClientException(String message) {
        super(400, message);
    }
}
