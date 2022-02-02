package nl.inl.blacklab.server.search;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import nl.inl.blacklab.search.BlackLabIndex;
import nl.inl.blacklab.search.results.SearchResult;
import nl.inl.blacklab.searches.Search;
import nl.inl.blacklab.searches.SearchCache;
import nl.inl.blacklab.searches.SearchCacheEntry;
import nl.inl.blacklab.searches.SearchCacheEntryFromFuture;
import nl.inl.blacklab.server.config.BLSConfigCache;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;
import java.util.concurrent.*;

public class ResultsCache implements SearchCache {
    private static final Logger logger = LogManager.getLogger(ResultsCache.class);
    private final ExecutorService threadPool;
    private final AsyncLoadingCache<Search<? extends SearchResult>, SearchResult> searchCache;
    private final ConcurrentHashMap<Search<? extends SearchResult>, Future<? extends SearchResult>> runningJobs = new ConcurrentHashMap<>();


    public static class CacheEntryWithResults<T extends SearchResult> extends SearchCacheEntry<T> {

        private final T results;

        public CacheEntryWithResults(T results) {
            this.results = results;
        }
        @Override
        public boolean wasStarted() {
            return true;
        }

        @Override
        public void start() {
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            return results;
        }

        @Override
        public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return results;
        }
    }

    public ResultsCache(BLSConfigCache config, ExecutorService threadPool)  {
        this.threadPool = threadPool;

        CacheLoader<Search<? extends SearchResult>, SearchResult> cacheLoader = new CacheLoader<Search<? extends SearchResult>, SearchResult>() {
            @Override
            public @Nullable SearchResult load(Search<?> search) throws Exception {
                long start = System.currentTimeMillis();
                Future<? extends SearchResult> job;
                if (runningJobs.containsKey(search)) {
                    job = runningJobs.get(search);
                } else {
                    job = threadPool.submit(search::executeInternal);
                    runningJobs.put(search, job);
                }
                SearchResult searchResult = job.get();
                //logger.warn("EGZ!!! Search time is {}, for {}", System.currentTimeMillis() - start, search.toString());
                logger.warn("EGZ!!! Search time is {}, for {}", System.currentTimeMillis() - start, "");
                runningJobs.remove(search);
                return searchResult;
            }
        };

        int maxSize = config.getMaxNumberOfJobs();
        logger.info("Creating cache with maxSize:{}", maxSize);
        searchCache = Caffeine.newBuilder()
            .recordStats()
            .maximumSize(maxSize)
            .initialCapacity(maxSize / 10)
            .expireAfterWrite(config.getMaxJobAgeSec(), TimeUnit.SECONDS)
            .buildAsync(cacheLoader);
        CaffeineCacheMetrics.monitor(Metrics.globalRegistry, searchCache, "blacklab-results-cache");
    }
    @Override
    public <T extends SearchResult> SearchCacheEntry<T> getAsync(final Search<T> search, final boolean allowQueue) {
        try {
            //SearchResult searchResult = searchCache.synchronous().get(search);
            //return new CacheEntryWithResults(searchResult);
            CompletableFuture<SearchResult> resultCF = searchCache.get(search);
            return new SearchCacheEntryFromFuture(resultCF);
        }catch (Exception ex) {
            ex.printStackTrace();
            throw ex;
        }
    }

    @Override
    public <T extends SearchResult> SearchCacheEntry<T> remove(Search<T> search) {
        if (searchCache.asMap().containsKey(search)) {
            SearchResult searchResult = searchCache.synchronous().get(search);
            searchCache.synchronous().invalidate(search);
            return new CacheEntryWithResults(searchResult);
        }
        return null;
    }

    @Override
    public void removeSearchesForIndex(BlackLabIndex index) {
        searchCache.asMap().keySet().removeIf(s -> s.queryInfo().index() == index);
    }

    @Override
    public void clear(boolean cancelRunning) {
        searchCache.synchronous().invalidateAll();
    }

    @Override
    public void cleanup() {
        clear(true);
    }
}
