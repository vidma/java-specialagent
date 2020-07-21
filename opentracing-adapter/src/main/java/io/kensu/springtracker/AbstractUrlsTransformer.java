package io.kensu.springtracker;

public abstract class AbstractUrlsTransformer {
    public abstract String transformUrl(String httpMethod, String fullUrl);
}
