package nl.inl.blacklab.search.results;

import org.eclipse.collections.api.iterator.MutableIntIterator;

/**
 * A basic Hits object implemented with a list.
 */
public class HitsList extends Hits {
    /** Our window stats, if this is a window; null otherwise. */
    private WindowStats windowStats;

    /** Our sample parameters, if any. null if not a sample of a larger result set */
    private SampleParameters sampleParameters;

    /**
     * Make a wrapper Hits object for a list of Hit objects.
     *
     * Does not copy the list, but reuses it.
     *
     * @param queryInfo query info
     * @param hits the list of hits to wrap, or null for a new list
     * @param capturedGroups the list of hits to wrap, or null for no captured groups
     */
    protected HitsList(QueryInfo queryInfo, HitsArrays hits, CapturedGroups capturedGroups) {
        super(queryInfo, hits);
        this.capturedGroups = capturedGroups;

        setHitsCounted(this.getHitsArrays().size());
        int prevDoc = -1;
        MutableIntIterator it = this.getHitsArrays().docs().intIterator();
        while (it.hasNext()) {
            int docId = it.next();
            if (docId != prevDoc) {
                setDocsRetrieved(getDocsRetrieved() + 1);
                setDocsCounted(getDocsCounted() + 1);
                prevDoc = docId;
            }
        }
    }

    /**
     * Construct a HitsList from all its components.
     *
     * Should only be used internally.
     */
    protected HitsList(
                       QueryInfo queryInfo,
                       HitsArrays hits,
                       WindowStats windowStats,
                       SampleParameters sampleParameters,
                       int hitsCounted,
                       int docsRetrieved,
                       int docsCounted,
                       CapturedGroups capturedGroups
                       ) {
        super(queryInfo, hits);
        this.windowStats = windowStats;
        this.sampleParameters = sampleParameters;
        this.setHitsCounted(hitsCounted);
        this.setDocsRetrieved(docsRetrieved);
        this.setDocsCounted(docsCounted);
        this.capturedGroups = capturedGroups;
    }

    @Override
    public String toString() {
        return "HitsList#" + hitsObjId + " (hits.size()=" + this.size() + "; isWindow=" + isWindow() + ")";
    }

    /**
     * Ensure that we have read at least as many hits as specified in the parameter.
     *
     * @param number the minimum number of hits that will have been read when this
     *            method returns (unless there are fewer hits than this); if
     *            negative, reads all hits
     */
    @Override
    protected void ensureResultsRead(int number) {
        // subclasses may override
    }

    @Override
    public boolean doneProcessingAndCounting() {
        return true;
    }

    @Override
    public SampleParameters sampleParameters() {
        return sampleParameters;
    }

    @Override
    public WindowStats windowStats() {
        return windowStats;
    }

    @Override
    public MaxStats maxStats() {
        return MaxStats.NOT_EXCEEDED;
    }
}
