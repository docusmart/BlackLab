package nl.inl.blacklab.search.results;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import nl.inl.blacklab.resultproperty.ResultProperty;

public abstract class ResultsList<T, P extends ResultProperty<T>> extends Results<T, P> {

    /**
     * The results.
     */
    private List<T> results;
    
    public ResultsList(QueryInfo queryInfo) {
        super(queryInfo);
        setResults(new ArrayList<>());
    }
    
    /**
     * Return an iterator over these hits.
     *
     * @return the iterator
     */
    @Override
    public Iterator<T> iterator() {
        // Construct a custom iterator that iterates over the hits in the hits
        // list, but can also take into account the Spans object that may not have
        // been fully read. This ensures we don't instantiate Hit objects for all hits
        // if we just want to display the first few.
        return new Iterator<T>() {
        
            int index = -1;
        
            @Override
            public boolean hasNext() {
                // Do we still have hits in the hits list?
                ensureResultsRead(index + 2);
                return getResults().size() >= index + 2;
            }
        
            @Override
            public T next() {
                // Check if there is a next, taking unread hits from Spans into account
                if (hasNext()) {
                    index++;
                    return getResults().get(index);
                }
                throw new NoSuchElementException();
            }
        
            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }
    
    @Override
    public synchronized T get(int i) {
        ensureResultsRead(i + 1);
        if (i >= getResults().size())
            return null;
        return getResults().get(i);
    }
    
    @Override
    protected boolean resultsProcessedAtLeast(int lowerBound) {
        ensureResultsRead(lowerBound);
        return getResults().size() >= lowerBound;
    }
    
    @Override
    protected int resultsProcessedTotal() {
        ensureAllResultsRead();
        return getResults().size();
    }
    
    @Override
    protected int resultsProcessedSoFar() {
        return getResults().size();
    }
    
    
    /**
     * Get part of the list of results.
     * 
     * Clients shouldn't use this. Used internally for certain performance-sensitive
     * operations like sorting.
     * 
     * The returned list is a view backed by the results list.
     * 
     * If toIndex is out of range, no exception is thrown, but a smaller list is returned.
     * 
     * @return the list of hits
     */
    protected List<T> resultsSubList(int fromIndex, int toIndex) {
        ensureResultsRead(toIndex);
        if (toIndex > getResults().size())
            toIndex = getResults().size();
        return getResults().subList(fromIndex, toIndex);
    }
    
    /**
     * Get the list of results.
     * 
     * Clients shouldn't use this. Used internally for certain performance-sensitive
     * operations like sorting.
     * 
     * @return the list of hits
     */
    protected List<T> resultsList() {
        ensureAllResultsRead();
        return Collections.unmodifiableList(getResults());
    }

    public List<T> getResults() {
        return results;
    }

    public void setResults(List<T> results) {
        this.results = results;
    }
}
