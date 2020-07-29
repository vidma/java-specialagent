package io.kensu.springtracker;

import java.util.*;
import java.util.regex.Pattern;


public class SimpleSpringtUrlsTransformer extends AbstractUrlsTransformer {
    private static final List<ReplacementRule> replacementRules = Arrays.asList(
            new ReplacementRule(Pattern.compile("k-\\p{XDigit}{30,300}"), "[kensu-id]"),
            new ReplacementRule(Pattern.compile("([a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}){1}"), "[uuid]")
            //new ReplacementRule(Pattern.compile("\\d+"), "[num]")
    );

    public SimpleSpringtUrlsTransformer() {
    }

    @Override
    public String transformUrl(String httpMethod, String fullUrl) {
        if (httpMethod != null && fullUrl != null) {
            String processed = fullUrl;
            for (ReplacementRule pattern: replacementRules){
                processed =   pattern.pattern.matcher(processed).replaceAll(pattern.replacement);
            }
            return processed;
        }
        return null;
    }

}

class ReplacementRule {
    String replacement;
    Pattern pattern;

    public ReplacementRule(Pattern pattern, String replacement){
        this.replacement = replacement;
        this.pattern = pattern;
    }
}
