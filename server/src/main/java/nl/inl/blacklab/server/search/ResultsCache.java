package nl.inl.blacklab.server.search;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import nl.inl.blacklab.search.BlackLabIndex;
import nl.inl.blacklab.search.results.SearchResult;
import nl.inl.blacklab.searches.Search;
import nl.inl.blacklab.searches.SearchCache;
import nl.inl.blacklab.searches.SearchCacheEntry;
import nl.inl.blacklab.server.config.BLSConfigCache;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;
import java.util.concurrent.*;

public class ResultsCache implements SearchCache {
    private static final Logger logger = LogManager.getLogger(ResultsCache.class);
    private final ExecutorService threadPool;
    private final LoadingCache<Search<? extends SearchResult>, SearchResult> searchCache;


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
                Future<SearchResult> job = threadPool.submit(() ->  {
                    logger.info("Executing search: {}", search.toString());
                    return search.executeInternal();
                });
                return job.get();
            }
        };

        searchCache = Caffeine.newBuilder()
            .maximumSize(1_000)
            .expireAfterWrite(config.getMaxJobAgeSec(), TimeUnit.SECONDS)
            .recordStats()
            .build(cacheLoader);
    }
    @Override
    public <T extends SearchResult> SearchCacheEntry<T> getAsync(final Search<T> search, final boolean allowQueue) {
        //simple case the result is in the cache
        /*SearchResult result = searches.getIfPresent(search);
        if (result != null) {
            return new ResultsCache.CacheEntryWithResults<>((T) result);
        }
        if (runningSearches.containsKey(search)) {
            Future<? extends SearchResult> future = runningSearches.get(search);
            if (future != null) {
                return new SearchCacheEntryFromFuture<>((Future<T>) future);
            } else {
                logger.warn("future was null. Maybe value is in the cache?");
                return new ResultsCache.CacheEntryWithResults<>((T)searches.getIfPresent(search));
            }
        }

        Future<T> searchExecution = threadPool.submit(() -> {
            T results = search.executeInternal();
            this.searches.put(search, (T) results);
            this.runningSearches.remove(search);
            return results;
        });
        runningSearches.put(search, searchExecution);
        return new SearchCacheEntryFromFuture<>(searchExecution);
         */
        SearchResult searchResult = searchCache.get(search);
        return new CacheEntryWithResults(searchResult);
    }

    @Override
    public <T extends SearchResult> SearchCacheEntry<T> remove(Search<T> search) {
        if (searchCache.asMap().containsKey(search)) {
            SearchResult searchResult = searchCache.get(search);
            searchCache.invalidate(search);
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
        searchCache.invalidateAll();
    }

    @Override
    public void cleanup() {
        clear(true);
    }
}
