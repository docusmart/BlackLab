package nl.inl.blacklab.queryParser.corpusql;

import nl.inl.blacklab.exceptions.InvalidQuery;
import nl.inl.blacklab.search.textpattern.TextPattern;

public class SimpleTestParser {
    public static void main(String[]  args) {
        try {
            System.out.println(args[0]);
            String qr = args[0];
            TextPattern result = CorpusQueryLanguageParser.parse(qr);
            System.out.println("Result: " + result + "\n");
        } catch (InvalidQuery e) {
            e.printStackTrace(System.err);
        }
    }
}
