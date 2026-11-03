package com.motion.backend.timeline;

/** Resolves an asset ID that the requesting user is allowed to use. */
@FunctionalInterface
public interface TimelineAssetResolver {

    TimelineAsset resolve(String id);
}
