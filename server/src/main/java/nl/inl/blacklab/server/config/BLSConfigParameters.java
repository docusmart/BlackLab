package nl.inl.blacklab.server.config;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonSetter;

import nl.inl.blacklab.search.indexmetadata.MatchSensitivity;

public class BLSConfigParameters {
    /** What pattern language to use? */
    String patternLanguage = "corpusql";

    /** What document filter language to use? */
    String filterLanguage = "luceneql";

    /** Should we default to case-/accent-sensitive search or not? */
    MatchSensitivity defaultSearchSensitivity;

    /** After how many hits should we stop processing them? */
    DefaultMax processHits = DefaultMax.get(5000000, 5000000);

    /** After how many hits should we stop counting them? */
    DefaultMax countHits = DefaultMax.get(10000000, 10000000);

    /** How many results should we return per page by default? */
    DefaultMax pageSize = DefaultMax.get(50, 3000);

    /** How many words of context should we return before and after matches? */
    DefaultMax contextSize = DefaultMax.get(5, 200);

    /** Should we return group contents with grouped results? */
    private boolean writeHitsAndDocsInGroupedHits = false;

    /** If a group of length 0 is captured (same start and end position), should we omit it instead? */
    private boolean omitEmptyCaptures = false;


    @JsonGetter("defaultSearchSensitivity")
    public String getDefaultSearchSensitivityName() {
        return defaultSearchSensitivity.toString();
    }

    @JsonSetter("defaultSearchSensitivity")
    public void setDefaultSearchSensitivityName(String value) {
        defaultSearchSensitivity = MatchSensitivity.fromName(value);
    }

    public String getPatternLanguage() {
        return patternLanguage;
    }

    public void setPatternLanguage(String patternLanguage) {
        this.patternLanguage = patternLanguage;
    }

    public String getFilterLanguage() {
        return filterLanguage;
    }

    public void setFilterLanguage(String filterLanguage) {
        this.filterLanguage = filterLanguage;
    }

    public DefaultMax getProcessHits() {
        return processHits;
    }

    public void setProcessHits(DefaultMax processHits) {
        this.processHits = processHits;
    }

    public DefaultMax getCountHits() {
        return countHits;
    }

    public void setCountHits(DefaultMax countHits) {
        this.countHits = countHits;
    }

    public DefaultMax getPageSize() {
        return pageSize;
    }

    public void setPageSize(DefaultMax pageSize) {
        this.pageSize = pageSize;
    }

    public DefaultMax getContextSize() {
        return contextSize;
    }

    public void setContextSize(DefaultMax contextSize) {
        this.contextSize = contextSize;
    }
    
    public MatchSensitivity getDefaultSearchSensitivity() {
        return defaultSearchSensitivity;
    }

    public void setDefaultSearchSensitivity(MatchSensitivity defaultSearchSensitivity) {
        this.defaultSearchSensitivity = defaultSearchSensitivity;
    }

    public boolean isWriteHitsAndDocsInGroupedHits() {
        return writeHitsAndDocsInGroupedHits;
    }

    public void setWriteHitsAndDocsInGroupedHits(boolean writeHitsAndDocsInGroupedHits) {
        this.writeHitsAndDocsInGroupedHits = writeHitsAndDocsInGroupedHits;
    }

    public boolean isOmitEmptyCaptures() {
        return omitEmptyCaptures;
    }

    @SuppressWarnings("unused")
    public void setOmitEmptyCaptures(boolean omitEmptyCaptures) {
        this.omitEmptyCaptures = omitEmptyCaptures;
    }
}
