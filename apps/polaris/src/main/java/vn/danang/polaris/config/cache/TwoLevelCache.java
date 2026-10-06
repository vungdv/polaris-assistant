package vn.danang.polaris.config.cache;

import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.jspecify.annotations.Nullable;

/**
 * A {@link Cache} backed by two tiers: a fast in-process cache (L1, typically Caffeine) checked
 * first, falling back to a shared out-of-process cache (L2, typically Redis) on an L1 miss so
 * cached values survive restarts and are shared across instances. A hit on L2 is written back
 * into L1 so subsequent reads on this instance avoid the network round trip.
 *
 * <p>L2 access is best-effort: any failure reaching Redis (connection error, timeout) is caught,
 * logged, and treated as a cache miss/no-op rather than propagated, so a Redis outage degrades
 * this cache to L1-only instead of breaking the cached operation. L1 is never allowed to fail
 * this way since it's in-process and has no external failure mode worth handling.
 */
public class TwoLevelCache implements Cache {

    private static final Logger log = LoggerFactory.getLogger(TwoLevelCache.class);

    private final String name;
    private final Cache local;
    private final Cache remote;

    public TwoLevelCache(String name, Cache local, Cache remote) {
        this.name = name;
        this.local = local;
        this.remote = remote;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getNativeCache() {
        return this;
    }

    @Override
    @Nullable
    public ValueWrapper get(Object key) {
        ValueWrapper hit = local.get(key);
        if (hit != null) {
            return hit;
        }
        ValueWrapper remoteHit = tryRemote(() -> remote.get(key), "get");
        if (remoteHit != null) {
            local.put(key, remoteHit.get());
        }
        return remoteHit;
    }

    @Override
    @Nullable
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, @Nullable Class<T> type) {
        ValueWrapper wrapper = get(key);
        if (wrapper == null) {
            return null;
        }
        Object value = wrapper.get();
        if (type != null && value != null && !type.isInstance(value)) {
            throw new IllegalStateException(
                    "Cached value for key [" + key + "] is not of required type [" + type.getName() + "]: " + value);
        }
        return (T) value;
    }

    @Override
    @Nullable
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        ValueWrapper wrapper = get(key);
        if (wrapper != null) {
            return (T) wrapper.get();
        }
        T value;
        try {
            value = valueLoader.call();
        } catch (Exception e) {
            throw new Cache.ValueRetrievalException(key, valueLoader, e);
        }
        put(key, value);
        return value;
    }

    @Override
    public void put(Object key, @Nullable Object value) {
        tryRemote(() -> {
            remote.put(key, value);
            return null;
        }, "put");
        local.put(key, value);
    }

    @Override
    public void evict(Object key) {
        tryRemote(() -> {
            remote.evict(key);
            return null;
        }, "evict");
        local.evict(key);
    }

    @Override
    public void clear() {
        tryRemote(() -> {
            remote.clear();
            return null;
        }, "clear");
        local.clear();
    }

    @Nullable
    private <T> T tryRemote(Callable<T> operation, String opName) {
        try {
            return operation.call();
        } catch (Exception e) {
            log.warn("Redis cache [{}] {} failed, degrading to local-only cache for this call: {}",
                    name, opName, e.getMessage());
            return null;
        }
    }
}
