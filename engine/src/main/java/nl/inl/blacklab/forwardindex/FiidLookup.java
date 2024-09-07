package nl.inl.blacklab.forwardindex;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.solr.uninverting.UninvertingReader;

import nl.inl.blacklab.exceptions.BlackLabRuntimeException;
import nl.inl.blacklab.search.indexmetadata.Annotation;

/**
 * Class for looking up forward index id, using DocValues or stored fields.
 *
 * This class is thread-safe.
 * (using synchronization on DocValues instance; DocValues are stored for each LeafReader,
 *  and each of those should only be used from one thread at a time)
 */
public class FiidLookup {

    /**
     * Index reader, for getting documents (for translating from Lucene doc id to
     * fiid)
     */
    private IndexReader reader;

    /**
     * fiid field name in the Lucene index (for translating from Lucene doc id to
     * fiid)
     */
    private String fiidFieldName;

    /** The DocValues per segment (keyed by docBase) */
    private Map<Integer, NumericDocValues> cachedFiids = new TreeMap<>();

    /** Any cached mappings from Lucene docId to forward index id (fiid) or null if not using cache */
    private final Map<Integer, Long> docIdToFiidCache = new HashMap<>();

    public FiidLookup(IndexReader reader, Annotation annotation) {
        this.fiidFieldName = annotation.forwardIndexIdField();
        this.reader = reader;
        cachedFiids = new TreeMap<>();
        try {
            for (LeafReaderContext rc : reader.leaves()) {
                LeafReader r = rc.reader();
                NumericDocValues numericDocValues = r.getNumericDocValues(fiidFieldName);
                if (numericDocValues == null) {
                    // Use UninvertingReader to simulate DocValues (slower)
                    @SuppressWarnings("resource")
                    LeafReader uninv = UninvertingReader.wrap(r, (String s) -> UninvertingReader.Type.INTEGER_POINT);
                    // UninvertingReader uninv = new UninvertingReader(r, fields);
                    numericDocValues = uninv.getNumericDocValues(fiidFieldName);
                }
                if (numericDocValues != null) {
                    cachedFiids.put(rc.docBase, numericDocValues);
                }
            }
            if (cachedFiids.isEmpty()) {
                // We don't actually have DocValues.
                cachedFiids = null;
            } else {
                // See if there are actual values stored
                // [this check was introduced when we used the old FieldCache, no longer necessary?]
                int numToCheck = Math.min(AnnotationForwardIndex.NUMBER_OF_CACHE_ENTRIES_TO_CHECK, reader.maxDoc());
                if (!hasFiids(numToCheck))
                    cachedFiids = null;
            }
        } catch (IOException e) {
            BlackLabRuntimeException.wrap(e);
        }
    }

    public int get(int docId) {
        // Is the fiid in the cache (if we have one)?
        Long fiid = docIdToFiidCache.get(docId);
        if (fiid != null)
        {
            // Yes; return value from the cache.
            return (int)(long)fiid;
        }

        // Find the fiid in the correct segment
        Entry<Integer, NumericDocValues> prev = null;
        for (Entry<Integer, NumericDocValues> e : cachedFiids.entrySet()) {
            Integer docBase = e.getKey();
            if (docBase > docId) {
                // Previous segment (the highest docBase lower than docId) is the right one
                Integer prevDocBase = prev.getKey();
                NumericDocValues prevDocValues = prev.getValue();
                return getFiidFromDocValues(prevDocBase, prevDocValues, docId);
            }
            prev = e;
        }
        
        // Last segment is the right one
        assert prev != null;
        Integer prevDocBase = prev.getKey();
        NumericDocValues prevDocValues = prev.getValue();
        return getFiidFromDocValues(prevDocBase, prevDocValues, docId);

        // Not cached; find fiid by reading stored value from Document now
        // INL/Blacklab removed this, seems convinced that this would never happen, but I'm not sure if that's true for us
        // 
        // try {
        //     return (int)Long.parseLong(reader.document(docId).get(fiidFieldName));
        // } catch (IOException e) {
        //     throw BlackLabRuntimeException.wrap(e);
        // }
    }

    public boolean hasFiids(int numToCheck) {
        // Check if the cache was retrieved OK
        boolean allZeroes = true;
        for (int i = 0; i < numToCheck; i++) {
            // (NOTE: we don't check if document wasn't deleted, but that shouldn't matter here)
            if (get(i) != 0) {
                allZeroes = false;
                break;
            }
        }
        return !allZeroes;
    }

    public static List<FiidLookup> getList(List<Annotation> annotations, IndexReader reader) {
        if (annotations == null)
            return null; // HitPoperty.needsContext() can return null
        List<FiidLookup> fiidLookups = new ArrayList<>();
        for (Annotation annotation: annotations) {
            fiidLookups.add(annotation == null ? null : new FiidLookup(reader, annotation));
        }
        return fiidLookups;
    }

    /**
     * Get the requested forward index id from the DocValues object.
     *
     * Optionally caches any skipped values for later.
     *
     * @param docBase doc base for this segement
     * @param docValues doc values for this segment
     * @param docId document to get the fiid for
     * @return forward index id
     */
    private int getFiidFromDocValues(int docBase, NumericDocValues docValues, int docId) {
        try {
            if (docIdToFiidCache == null) {
                // Not caching (because we know our docIds are always increasing)
                docValues.advanceExact(docId - docBase);
                return (int) docValues.longValue();
            } else {
                // Caching; gather all fiid values in our cache until we find the requested one.
                do {
                    docValues.nextDoc();
                    docIdToFiidCache.put(docValues.docID() + docBase, docValues.longValue());
                    if (docValues.docID() == docId - docBase) {
                        // Requested docvalue found.
                        return (int) docValues.longValue();
                    }
                } while (docValues.docID() <= docId - docBase);
                throw new BlackLabRuntimeException("not found in docvalues");
            }
        } catch (IOException e1) {
            throw BlackLabRuntimeException.wrap(e1);
        }
    }
}
