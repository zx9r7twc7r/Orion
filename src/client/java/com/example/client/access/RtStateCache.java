package com.example.client.access;

/**
 * Duck interface implemented by {@code BlockStateRtCacheMixin} on
 * {@code BlockBehaviour.BlockStateBase}: one int of RT-derived per-state data.
 *
 * <p>Bit layout is owned by {@code SectionCompilerMixin} (see its
 * "RT per-BlockState derived cache" block); this interface only exposes the
 * raw word.  Callers must cast through {@code Object}, since the interface is
 * attached at runtime by Mixin:
 *
 * <pre>{@code ((RtStateCache) (Object) state).rt$getStateCache()}</pre>
 */
public interface RtStateCache {

    int rt$getStateCache();

    void rt$setStateCache(int value);
}
