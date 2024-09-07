package nl.inl.blacklab.search.lucene;

import java.io.IOException;
import java.util.Map;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermContext;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.spans.SpanQuery;
import org.apache.lucene.search.spans.SpanWeight;

/**
 * Base class for our SpanWeight classes. Ensures that getSpans returns a
 * BLSpans.
 */
public abstract class BLSpanWeight extends SpanWeight {

    public BLSpanWeight(SpanQuery query, IndexSearcher searcher, Map<Term, TermContext> termContexts)
            throws IOException {

        // NOTE (chun.yu): About the 1.0f. I added this while upgrading the Lucene version for security.
        //
        // This constructor argument was added in Lucene 7.0 without much documentation. It appears to be
        // a multiplier to be applied onto some scores. INL/Blacklab opted to have the BLSpanWeight constructor take a
        // boost argument as well, allowing callers to specify. However, as of September 2024, the only concrete number used
        // is 1.0f, so I'm hardcoding here to keep my upgrade simple and avoid modifying another ~20 files.
        super(query, searcher, termContexts, 1.0f);
    }

    @Override
    public abstract BLSpans getSpans(LeafReaderContext ctx, Postings requiredPostings) throws IOException;

}
