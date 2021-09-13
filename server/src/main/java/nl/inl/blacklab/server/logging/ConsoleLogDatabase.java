package nl.inl.blacklab.server.logging;

import nl.inl.blacklab.requestlogging.LogLevel;
import nl.inl.blacklab.requestlogging.SearchLogger;
import nl.inl.blacklab.search.results.SearchResult;
import nl.inl.blacklab.server.search.BlsCacheEntry;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.Logger;

public class ConsoleLogDatabase implements LogDatabase {
        private final Logger logger;

        public ConsoleLogDatabase(Logger logger) {
                this.logger = logger;
        }

        @Override
        public List<Request> getRequests(long from, long to) {
                return null;
        }

        @Override
        public List<CacheStats> getCacheStats(long from, long to) {
                return null;
        }

        @Override
        public void close() throws IOException {

        }

        @Override
        public SearchLogger addRequest(String corpus, String type, Map<String, String[]> parameters) {
                return new SearchLogger() {
                        @Override
                        public void log(LogLevel level, String line) {
                                if (level != LogLevel.BASIC) {
                                        return;
                                }
                                ConsoleLogDatabase.this.logger.info(line);
                        }

                        @Override
                        public void setResultsFound(int resultsFound) {

                        }

                        @Override
                        public void close() throws IOException {

                        }
                };
        }

        @Override
        public void addCacheInfo(List<BlsCacheEntry<? extends SearchResult>> snapshot, int numberOfSearches, int numberRunning, int numberPaused, long sizeBytes, long freeMemoryBytes, long largestEntryBytes, int oldestEntryAgeSec) {

        }
}
