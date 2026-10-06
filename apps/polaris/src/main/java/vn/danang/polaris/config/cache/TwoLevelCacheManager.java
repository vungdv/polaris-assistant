package vn.danang.polaris.config.cache;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.jspecify.annotations.Nullable;

/**
 * {@link CacheManager} that resolves each cache name to a {@link TwoLevelCache} pairing an
 * in-process L1 cache with a shared Redis-backed L2 cache. The L1 and L2 delegates for a given
 * name are looked up lazily from their respective managers on first access, so both managers
 * only need to know about cache names they actually serve (e.g. via {@code CaffeineCacheManager}
 * pre-registered names or {@code RedisCacheManager} per-cache TTL configuration).
 */
public class TwoLevelCacheManager implements CacheManager {

    private final CacheManager localCacheManager;
    private final CacheManager remoteCacheManager;
    private final ConcurrentMap<String, Cache> caches = new ConcurrentHashMap<>();

    public TwoLevelCacheManager(CacheManager localCacheManager, CacheManager remoteCacheManager) {
        this.localCacheManager = localCacheManager;
        this.remoteCacheManager = remoteCacheManager;
    }

    @Override
    public @Nullable Cache getCache(String name) {
        Cache cached = caches.get(name);
        if (cached != null) {
            return cached;
        }
        Cache local = localCacheManager.getCache(name);
        Cache remote = remoteCacheManager.getCache(name);
        if (local == null || remote == null) {
            return null;
        }
        return caches.computeIfAbsent(name, n -> new TwoLevelCache(n, local, remote));
    }

    @Override
    public Collection<String> getCacheNames() {
        return Set.copyOf(localCacheManager.getCacheNames());
    }
}
