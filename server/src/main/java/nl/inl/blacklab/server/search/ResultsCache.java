package nl.inl.blacklab.server.search;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import nl.inl.blacklab.search.BlackLabIndex;
import nl.inl.blacklab.search.results.SearchResult;
import nl.inl.blacklab.searches.Search;
import nl.inl.blacklab.searches.SearchCache;
import nl.inl.blacklab.searches.SearchCacheEntry;
import nl.inl.blacklab.searches.SearchCacheEntryFromFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ResultsCache implements SearchCache {
    private final ExecutorService threadPool;
    protected Map<Search<?>, Future<? extends SearchResult>> runningSearches = new ConcurrentHashMap<>();
    private final Cache<Search<?>, SearchResult> searches = CacheBuilder.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();


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

    public ResultsCache(ExecutorService threadPool) {
        this.threadPool = threadPool;
    }
    @Override
    public <T extends SearchResult> SearchCacheEntry<T> getAsync(Search<T> search, boolean allowQueue) {
        //simple case the result is in the cache
        SearchResult result = searches.getIfPresent(search);
        if (result != null) {
            return new ResultsCache.CacheEntryWithResults<>((T) result);
        }
        if (runningSearches.containsKey(search)) {
            Future<? extends SearchResult> future = runningSearches.get(search);
            return new SearchCacheEntryFromFuture<>((Future<T>) future);
        }

        Future<T> searchExecution = threadPool.submit(() -> {
            T results = search.executeInternal();
            this.searches.put(search, (T) results);
            this.runningSearches.remove(search);
            return results;
        });
        runningSearches.put(search, searchExecution);
        return new SearchCacheEntryFromFuture<>(searchExecution);
    }

    @Override
    public <T extends SearchResult> SearchCacheEntry<T> remove(Search<T> search) {
        SearchResult removed = searches.asMap().remove(search);
        if (removed != null) {
            return new CacheEntryWithResults<>((T)removed);
        }
        return null;
    }

    @Override
    public void removeSearchesForIndex(BlackLabIndex index) {
        searches.asMap().keySet().removeIf(s -> s.queryInfo().index() == index);
    }

    @Override
    public void clear(boolean cancelRunning) {
        searches.asMap().clear();
        if (cancelRunning) {
            runningSearches.values().stream().filter(f -> !f.isDone()).forEach(f -> f.cancel(true));
            runningSearches.clear();
        }

    }

    @Override
    public void cleanup() {
        clear(true);
    }
}
