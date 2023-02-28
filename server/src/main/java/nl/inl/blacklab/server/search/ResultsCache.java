package nl.inl.blacklab.server.search;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.RemovalListener;
import io.micrometer.core.instrument.Counter;
import nl.inl.blacklab.exceptions.BlackLabRuntimeException;
import nl.inl.blacklab.searches.SearchHitsFromBLSpanQuery;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.checkerframework.checker.nullness.qual.Nullable;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import nl.inl.blacklab.search.BlackLabIndex;
import nl.inl.blacklab.search.results.SearchResult;
import nl.inl.blacklab.searches.Search;
import nl.inl.blacklab.searches.SearchCache;
import nl.inl.blacklab.searches.SearchCacheEntry;
import nl.inl.blacklab.searches.SearchCacheEntryFromFuture;
import nl.inl.blacklab.server.config.BLSConfig;

public class ResultsCache implements SearchCache {
    private static final Logger logger = LogManager.getLogger(ResultsCache.class);
    private static final String CACHE_NAME_FOR_METRICS = "blacklab-results-cache";
    private final ExecutorService threadPool;
    private final Counter timedOutJobs = Metrics.globalRegistry.counter("timedout-search-jobs", Tags.empty());
    private final AsyncLoadingCache<SearchInfoWrapper, SearchResult> searchCache;
    private final ConcurrentHashMap<Search<? extends SearchResult>, Future<CacheEntryWithResults<? extends SearchResult>>> runningJobs = new ConcurrentHashMap<>();


    public static class CacheEntryWithResults<T extends SearchResult> extends SearchCacheEntry<T> {

        private final T results;
        private final long runTime;

        public CacheEntryWithResults(T results, long runTime) {
            this.results = results;
            this.runTime = runTime;
        }

        public T getResults() {
            return results;
        }

        @Override
        public boolean wasStarted() {
            return true;
        }

        @Override
        public void start() {
        }

        @Override
        public long timeUserWaitedMs() {
            return runTime;
        }

        @Override
        public boolean threwException() {
            return false;
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

        @Override
        public T peek() {
            return results;
        }

    }

    private static final class SearchInfoWrapper {
        private final Search<? extends SearchResult>  search;
        private final String requestId;

        public SearchInfoWrapper(Search<? extends SearchResult> search, String requestId) {
            this.search = search;
            this.requestId = requestId;
        }

        public Search<? extends SearchResult> getSearch() {
            return search;
        }

        public String getRequestId() {
            return requestId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()){
                return false;
            }
            SearchInfoWrapper that = (SearchInfoWrapper) o;
            return search.equals(that.search);
        }

        @Override
        public int hashCode() {
            return search.hashCode();
        }
    }

    public ResultsCache(BLSConfig config, ExecutorService threadPool)  {
        this.threadPool = threadPool;
        int maxSearchTimeSec = config.getCache().getMaxSearchTimeSec();

        CacheLoader<SearchInfoWrapper, SearchResult> cacheLoader = new CacheLoader<SearchInfoWrapper, SearchResult>() {
            @Override
            public SearchResult load(final SearchInfoWrapper searchWrapper) throws Exception {
                final String requestId = searchWrapper.getRequestId();
                ThreadContext.put("requestId", requestId);
                Future<CacheEntryWithResults<? extends SearchResult>> job = runningJobs.computeIfAbsent(searchWrapper.getSearch(), (search) -> ResultsCache.this.threadPool.submit(() -> {
                    ThreadContext.put("requestId", requestId);
                    final long startTime = System.currentTimeMillis();
                    if (search instanceof SearchHitsFromBLSpanQuery) {
                        logger.debug("EGZZZZ---Starting search: {}, {}", search.toString(), System.identityHashCode(search));
                    }
                    SearchResult results = search.executeInternal(null);
                    if (search instanceof SearchHitsFromBLSpanQuery) {
                        logger.debug("EGZZZ---Finished search: {}  {}", search.toString(), System.identityHashCode(search));
                    }
                    ThreadContext.remove("requestId");
                    return new CacheEntryWithResults<>(results, System.currentTimeMillis() - startTime);
                }));
                try {
                    CacheEntryWithResults<? extends SearchResult> searchResult;
                    if (maxSearchTimeSec > 0) {
                         searchResult = job.get((long)maxSearchTimeSec * 1000, TimeUnit.MILLISECONDS);
                    } else {
                         searchResult = job.get();
                    }
                    logger.warn("Internal search time is: {}", searchResult.timeUserWaitedMs());
                    return searchResult.getResults();
                } catch (TimeoutException ex) {
                    logger.warn("Search took to long: {}", searchWrapper.search);
                    timedOutJobs.increment();
                    throw ex;
                } finally {
                    runningJobs.remove(searchWrapper.getSearch());
                }
            }
        };

        RemovalListener removalListener = new RemovalListener() {
            @Override
            public void onRemoval(@Nullable Object key, @Nullable Object value, RemovalCause cause) {
                SearchInfoWrapper wrapper = (SearchInfoWrapper) key;
                logger.debug("*****EGZ Removing: {}, {} {}", wrapper.getSearch(), value, cause.name()) ;
            }
        };



        int maxSize = config.getCache().getMaxNumberOfJobs();
        logger.info("Creating cache with maxSize: {}", maxSize);
        logger.info("Creating cache with max search time: {} sec", maxSearchTimeSec);
        searchCache = Caffeine.newBuilder()
            .recordStats()
            .maximumSize(maxSize)
            .initialCapacity(maxSize / 10)
            .executor(this.threadPool)
            .evictionListener(removalListener)
            .buildAsync(cacheLoader);
        CaffeineCacheMetrics.monitor(Metrics.globalRegistry, searchCache, CACHE_NAME_FOR_METRICS);
        Metrics.globalRegistry.gaugeMapSize("blacklab-job-queue", Tags.empty(), runningJobs);
    }
    @Override
    public <T extends SearchResult> SearchCacheEntry<T> getAsync(final Search<T> search, final boolean allowQueue) {
        try {
            String requestId = ThreadContext.get("requestId");
            String failedReqId = System.getProperty("failedReqId");
            SearchInfoWrapper wrapper = new SearchInfoWrapper(search, requestId);
            if (System.getProperty("failedReqId") != null && requestId.equalsIgnoreCase(failedReqId) && search instanceof SearchHitsFromBLSpanQuery){
                logger.debug("Trying to fetch a request with bad pages: {},  {}", requestId, failedReqId);
                logger.debug("Contains search= {} -> {}", search.toString(), searchCache.asMap().containsKey(wrapper));
                if (!searchCache.asMap().containsKey(wrapper)) {
                    Stream<? extends Search<? extends SearchResult>> searchStream = searchCache.asMap().keySet().stream()
                        .filter(k -> k.requestId.equalsIgnoreCase(requestId)).map(s -> s.getSearch());
                    logger.debug("Since it does not contain the search, but it contians: {}", searchStream);
                }
                System.clearProperty("failedReqId");
            }
            CompletableFuture<SearchResult> resultsFuture = searchCache.get(wrapper);
            return new SearchCacheEntryFromFuture(resultsFuture, search);
        } catch (Exception ex) {
            throw BlackLabRuntimeException.wrap(ex);
        }
    }

    @Override
    public <T extends SearchResult> SearchCacheEntry<T> remove(Search<T> search) {
        SearchInfoWrapper searchWrapper = new SearchInfoWrapper(search, null);
        SearchResult searchResult = searchCache.synchronous().asMap().remove(searchWrapper);
        if (searchResult != null) {
            return new CacheEntryWithResults(searchResult, -1);
        }
        return null;
    }

    @Override
    public void removeSearchesForIndex(BlackLabIndex index) {
        logger.info("Removing searches for index: {}", index.name());
        searchCache.asMap().keySet().removeIf(s -> s.getSearch().queryInfo().index() == index);
    }

    @Override
    public void clear(boolean cancelRunning) {
        searchCache.synchronous().invalidateAll();
    }

    @Override
    public void cleanup() {
        clear(true);
    }

    @Override
    public Map<String, Object> getCacheStatus() {
        return null;
    }

    @Override
    public List<Map<String, Object>> getCacheContent(boolean includeDebugInfo) {
        return null;
    }

    public <T extends SearchResult>  boolean containsSearch(Search<T> search, String requestId){
        SearchInfoWrapper wrapper = new SearchInfoWrapper(search, requestId);
        return searchCache.asMap().containsKey(wrapper);
    }
}
