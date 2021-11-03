package nl.inl.blacklab.server.search;

<<<<<<< HEAD
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import nl.inl.blacklab.exceptions.InvalidQuery;
import nl.inl.blacklab.search.BlackLab;
import nl.inl.blacklab.search.BlackLabEngine;
import nl.inl.blacklab.search.indexmetadata.MatchSensitivity;
import nl.inl.blacklab.search.results.SearchResult;
import nl.inl.blacklab.searches.Search;
import nl.inl.blacklab.server.Metrics;
=======
import java.io.File;
import java.io.IOException;

import nl.inl.blacklab.search.BlackLab;
import nl.inl.blacklab.search.BlackLabEngine;
>>>>>>> inl/dev
import nl.inl.blacklab.server.config.BLSConfig;
import nl.inl.blacklab.server.exceptions.ConfigurationException;
import nl.inl.blacklab.server.index.IndexManager;
import nl.inl.blacklab.server.logging.LogDatabase;
import nl.inl.blacklab.server.logging.LogDatabaseDummy;
import nl.inl.blacklab.server.logging.LogDatabaseImpl;

<<<<<<< HEAD
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;

=======
/**
 * Manages the lifetime of a number of objects needed for the web service.
 */
>>>>>>> inl/dev
public class SearchManager {

    //private static final Logger logger = LogManager.getLogger(SearchManager.class);

    /** Our config */
    private BLSConfig config;

    /** All running searches as well as recently run searches */
    private BlsCache cache;

    /** System for determining the current user. */
    private AuthManager authSystem;

    /** Manages all the indices we have available and/or open */
    private IndexManager indexMan;

    /** Main BlackLab object, containing the search executor service */
    private BlackLabEngine blackLab;

    /** Database for logging detailed debug information (if enabled) */
    private LogDatabase logDatabase;

    public SearchManager(BLSConfig config) throws ConfigurationException {
        this.config = config;

        // Create BlackLab instance with the desired number of search threads
        int numberOfSearchThreads = config.getPerformance().getMaxConcurrentSearches();
        int maxThreadsPerSearch = config.getPerformance().getMaxThreadsPerSearch();
        blackLab = BlackLab.createEngine(numberOfSearchThreads, maxThreadsPerSearch);
        startMonitoringOfThreadPools(blackLab);

        // Open log database
        try {
            String sqliteDatabase = config.getLog().getSqliteDatabase();
            if (sqliteDatabase != null) {
                File dbFile = new File(sqliteDatabase);
                String url = "jdbc:sqlite:" + dbFile.getCanonicalPath().replaceAll("\\\\", "/");
                Class.forName("org.sqlite.JDBC");
                logDatabase = new LogDatabaseImpl(url);
            } else {
                logDatabase = new LogDatabaseDummy();
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Error opening log database", e);
        }

        // Create the cache
        int abandonedCountAbortTimeSec = config.getPerformance().getAbandonedCountAbortTimeSec();
        int maxConcurrentSearches = config.getPerformance().getMaxConcurrentSearches();
        boolean traceCache = config.getLog().getTrace().isCache();
        cache = new BlsCache(config.getCache(), maxConcurrentSearches, abandonedCountAbortTimeSec, traceCache, logDatabase);

        // Find the indices
        indexMan = new IndexManager(this, config);

        // Init auth system
        authSystem = new AuthManager(config.getAuthentication());
    }

    private void startMonitoringOfThreadPools(BlackLabEngine blackLab) {
        publishSearchExecutorsQueueSize(blackLab.searchExecutorService());
    }

    private void publishSearchExecutorsQueueSize(ExecutorService searchExecutorService) {
        assert searchExecutorService instanceof ForkJoinPool;
        ForkJoinPool executorService = (ForkJoinPool) searchExecutorService;
        String name = "SearchExecutorQueueLen";
        String description = "A metric tracking the lengths of all the queues in the SearchExecutorQueue";
        Metrics.createGauge(name, description,  Tags.of("name", "submission"), executorService,
                Metrics.toDoubleFn(ForkJoinPool::getQueuedSubmissionCount));
        Metrics.createGauge(name, description,  Tags.of("name", "task"), executorService,
                Metrics.toDoubleFn(ForkJoinPool::getQueuedTaskCount));
        Metrics.createGauge(name, description,  Tags.of("name", "steal"), executorService,
                Metrics.toDoubleFn(ForkJoinPool::getStealCount));
    }


    /**
     * Clean up resources.
     *
     * In particular, stops the load manager thread and cancels any running
     * searches.
     */
    public synchronized void cleanup() {

        // Stop any running searches
        cache.cleanup();
        cache = null;

        try {
            if (logDatabase != null) {
                logDatabase.close();
                logDatabase = null;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        blackLab.close();
        blackLab = null;

        // Set other variables to null in case it helps GC
        config = null;
        authSystem = null;
        indexMan = null;
    }

    public LogDatabase getLogDatabase() {
        return logDatabase;
    }

    public BlsCache getBlackLabCache() {
        return cache;
    }

    public BLSConfig config() {
        return config;
    }

    public AuthManager getAuthSystem() {
        return authSystem;
    }

    public IndexManager getIndexManager() {
        return indexMan;
    }

    public BlackLabEngine blackLabInstance() {
        return blackLab;
    }

}
