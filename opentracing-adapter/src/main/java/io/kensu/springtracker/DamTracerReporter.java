package io.kensu.springtracker;

import io.kensu.collector.DamProcessEnvironment;
import io.kensu.collector.model.DamBatchBuilder;
import io.kensu.collector.model.DamDataCatalogEntry;
import io.kensu.collector.model.DamSchemaUtils;
import io.kensu.collector.model.SimpleDamLineageBuilder;
import io.kensu.collector.model.datasource.OrientdbDatasourceNameFormatter;
import io.kensu.jdbc.parser.DamJdbcQueryParser;
import io.kensu.jdbc.parser.ReferencedSchemaFieldsInfo;
import io.kensu.dam.ApiClient;
import io.kensu.dam.ApiException;
import io.kensu.dam.ManageKensuDamEntitiesApi;
import io.kensu.dam.OfflineFileApiClient;
import io.kensu.dam.model.*;
import io.kensu.dam.model.Process;
import io.kensu.collector.model.datasource.HttpDatasourceNameFormatter;
import io.kensu.collector.model.datasource.JdbcDatasourceNameFormatter;
import io.kensu.json.DamJsonSchemaInferrer;
import io.kensu.logging.KensuLogger;
import io.kensu.utils.ConcurrentHashMultimap;
import io.opentracing.contrib.reporter.Reporter;
import io.opentracing.contrib.reporter.SpanData;
import io.opentracing.tag.Tag;
import io.opentracing.tag.Tags;
import net.sf.jsqlparser.JSQLParserException;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

import static io.kensu.json.DamJsonSchemaInferrer.DAM_OUTPUT_SCHEMA_TAG;

public class DamTracerReporter implements Reporter {
    private final KensuLogger logger = new KensuLogger(DamTracerReporter.class);
    protected final AbstractUrlsTransformer urlsTransformer;

    private final ConcurrentHashMultimap<SpanData> spanChildrenCache = new ConcurrentHashMultimap<>();
    private final HttpDatasourceNameFormatter httpFormatter = HttpDatasourceNameFormatter.INST;

    public DamTracerReporter(AbstractUrlsTransformer springUrlsTransformer) {
        this.urlsTransformer = springUrlsTransformer;
    }

    protected DamProcessEnvironment damEnv = new DamProcessEnvironment();

    @Override
    public void start(Instant timestamp, SpanData span) {
        String logMessage = createLogMessage(timestamp, "start", span);
        logger.info(logMessage);
    }

    protected <T> T getTagOrDefault(String tagKey, SpanData span, T defaultValue) {
        return (T)(span.tags.getOrDefault(tagKey, defaultValue));
    }

    protected <T> T getTagOrDefault(Tag<T> tag, SpanData span, T defaultValue) {
        return getTagOrDefault(tag.getKey(), span, defaultValue);
    }

    @Override
    public void finish(Instant timestamp, SpanData span) {
        String logMessage = createLogMessage(timestamp, "Finish", span);
        logger.info(logMessage);

        try {
            String maybeParentId = span.references.get("child_of");
            if (maybeParentId != null) {
                spanChildrenCache.addEntry(maybeParentId, span);
            } else {
                // this is the main SPAN, report all the stuff which was gathered so far
                DamBatchBuilder batchBuilder = new DamBatchBuilder().withDefaultLocation();
                PhysicalLocationRef defaultLocationRef = DamBatchBuilder.DEFAULT_LOCATION_REF;
                //   - http.status_code: 200
                //   - component: java-web-servlet
                //   - span.kind: server
                //   - http.url: http://localhost/people/search/findByLastName
                //   - http.method: GET
                //   - DamOutputSchema: [class FieldDef {
                Integer httpStatus = getTagOrDefault(Tags.HTTP_STATUS, span, 0);
                // FIMXE: need ability to remove param value to get URL pattern!!!
                String httpUrl = getTagOrDefault(Tags.HTTP_URL, span, null);
                String httpMethod = getTagOrDefault(Tags.HTTP_METHOD, span, null);
                String transformedHttpUrl = urlsTransformer.transformUrl(httpMethod, httpUrl);
                String spanKind = getTagOrDefault(Tags.SPAN_KIND, span, null);
                logger.warn(String.format("transformedHttpUrl: %s ( httpStatus: %d\nhttpUrl: %s\nhttpMethod: %s )", transformedHttpUrl, httpStatus, httpUrl, httpMethod));
                if ((httpStatus >= 200) &&
                        (httpStatus < 300) &&
                        transformedHttpUrl != null &&
                        httpMethod != null &&
                        spanKind.equals(Tags.SPAN_KIND_SERVER)) {
                    Set<FieldDef> damOutputFields = getHttpResponseSchema(span);
                    String endpointName = httpFormatter.formatMethod(httpMethod);
                    DamDataCatalogEntry outputCatalogEntry = batchBuilder.addCatalogEntry(
                            "create",
                            damOutputFields,
                            transformedHttpUrl,
                            endpointName,
                            defaultLocationRef,
                            HttpDatasourceNameFormatter.INST
                    );
                    logger.warn("outputCatalogEntry: " + outputCatalogEntry);

                    List<DamDataCatalogEntry> inputCatalogEntries = new ArrayList<>();
                    List<DamDataCatalogEntry> writesCatalogEntries = new ArrayList<>();
                    Set<SpanData> children = getRecursiveSpanChildren(span);
                    if (children != null) {
                        logger.debug(String.format("CHILDREN (count = %d):", children.size()));
                        children.forEach(childSpan -> {
                            logger.debug(createLogMessage(timestamp, "child", childSpan));
                            if (getTagOrDefault(Tags.COMPONENT, childSpan, "").equals("java-jdbc")) {
                                String dbInstance = getTagOrDefault(Tags.DB_INSTANCE, childSpan, "");
                                String dbType = getTagOrDefault(Tags.DB_TYPE, childSpan, "");
                                String dbStatement = getTagOrDefault(Tags.DB_STATEMENT, childSpan, "").toLowerCase();
                                String defaultDbPath = dbInstance; // FIXME: maybe need better handling
                                // SQL reads
                                ReferencedSchemaFieldsInfo readFieldsByTable = DamJdbcQueryParser.parseOrUnkownReferenced(dbInstance, dbType, dbStatement, logger, defaultDbPath, DamJdbcQueryParser::guessReferencedInputTableSchemas);
                                inputCatalogEntries.addAll(batchBuilder.addCatalogEntries("create", readFieldsByTable.schema, dbType, defaultLocationRef, JdbcDatasourceNameFormatter.INST));

                                // SQL writes
                                ReferencedSchemaFieldsInfo writtenFieldsByTable = DamJdbcQueryParser.parseOrUnkownReferenced(dbInstance, dbType, dbStatement, logger, defaultDbPath, DamJdbcQueryParser::guessReferencedOutputTableSchemas);
                                writesCatalogEntries.addAll(batchBuilder.addCatalogEntries(writtenFieldsByTable.lineageOperation, writtenFieldsByTable.schema, dbType, defaultLocationRef, JdbcDatasourceNameFormatter.INST));
                            }

                            // FIXME: maybe determine ODB Vertex/Edge class name as well?
                            //  maybe special partitioning attributes too? but complicated when all are accessed...
                            if (getTagOrDefault(Tags.COMPONENT, childSpan, "").equals("orientdb")) {
                                String odbStatement = getTagOrDefault(Tags.DB_STATEMENT, childSpan, "");
                                String odbUrl = getTagOrDefault(Tags.DB_INSTANCE, childSpan, "");
                                Boolean isWrite = isOrientdbWrite(odbStatement);
                                List<DamDataCatalogEntry> catalogEntries = batchBuilder.addCatalogEntries(
                                        String.format("OrientDB %s", isWrite ? "write" : "read"),
                                        DamSchemaUtils.fieldsWithMissingInfoForPath(String.format("OrientDB :: %s", odbUrl)),
                                        "orientdb",
                                        defaultLocationRef,
                                        OrientdbDatasourceNameFormatter.INST);
                                if (isWrite) {
                                    writesCatalogEntries.addAll(catalogEntries);
                                } else {
                                    inputCatalogEntries.addAll(catalogEntries);
                                }
                            }

                            if (getTagOrDefault(Tags.COMPONENT, childSpan, "").equals("play-ws") &&
                                    getTagOrDefault(Tags.SPAN_KIND, childSpan, "").equals(Tags.SPAN_KIND_CLIENT)) {
                                String callHttpMethod = getTagOrDefault(Tags.HTTP_METHOD, childSpan, "");
                                String callHttpUrl = getTagOrDefault(Tags.HTTP_URL, childSpan, "");
                                String cleanedCallHttpUrl = new SimpleSpringtUrlsTransformer().transformUrl(callHttpMethod, callHttpUrl);
                                Boolean isWrite = isHttpWrite(callHttpMethod);
                                Set<FieldDef> httpResponseSchema = getHttpResponseSchema(childSpan);
                                String lineageTitle = String.format("Remote HTTP %s call to %s", httpMethod, cleanedCallHttpUrl);
                                System.err.println(String.format("Found play-ws access: %s with response schema: %s", lineageTitle, httpResponseSchema));
                                DamDataCatalogEntry catalogEntry = batchBuilder.addCatalogEntry(
                                        lineageTitle,
                                        httpResponseSchema,
                                        cleanedCallHttpUrl,
                                        httpFormatter.formatMethod(callHttpMethod),
                                        defaultLocationRef,
                                        HttpDatasourceNameFormatter.INST);
                                if (isWrite) {
                                    writesCatalogEntries.add(catalogEntry);
                                } else {
                                    inputCatalogEntries.add(catalogEntry);
                                }
                            }

                        });
                    }
                    // Add all-to-all lineage between all inputs and the HTTP output
                    logger.warn("inputCatalogEntries: " + inputCatalogEntries);
                    Process process = damEnv.enqueProcess(batchBuilder);
                    ProcessRun processRun = damEnv.enqueProcessRun(process, endpointName, batchBuilder);
                    String inputToHttpOutputOp = "APPEND";
                    new SimpleDamLineageBuilder(
                            process,
                            processRun,
                            inputCatalogEntries,
                            outputCatalogEntry,
                            inputToHttpOutputOp,
                            "create"
                    ).addToBatch(batchBuilder);
                    // each write will need a different lineage as operation logic may be different
                    logger.warn("writesCatalogEntries: " + writesCatalogEntries);
                    String inputToJdbcWriteOp = "APPEND";
                    writesCatalogEntries.forEach(jdbcWriteOutput -> {
                        new SimpleDamLineageBuilder(
                                process,
                                processRun,
                                Collections.singletonList(outputCatalogEntry),
                                jdbcWriteOutput,
                                inputToJdbcWriteOp,
                                jdbcWriteOutput.lineageTitlePrefix
                        ).addToBatch(batchBuilder);
                    });
                    reportBatchToDam(batchBuilder);
                }
            }
        } catch (RuntimeException e){
            logger.warn("Caught exception in DamTracerReporter...", e);
        }
        // FIXME: catch more...
    }

    protected Set<FieldDef> getHttpResponseSchema(SpanData span) {
        String jsonSchemaAsStr = getTagOrDefault(DAM_OUTPUT_SCHEMA_TAG, span, null);
        System.err.println("got jsonSchemaAsStr=" + String.valueOf(jsonSchemaAsStr));
        if (jsonSchemaAsStr == null)
            return DamSchemaUtils.EMPTY_SCHEMA;
        return new DamJsonSchemaInferrer().convertToDamSchemaFromString(jsonSchemaAsStr);
    }

    protected Set<SpanData> getRecursiveSpanChildren(SpanData span) {
        HashSet<SpanData> visitedChildren = new HashSet<>();
        HashSet<SpanData> spanChildren = spanChildrenCache.get(span.spanId);
        if (spanChildren == null)
            return visitedChildren;
        Queue<SpanData> toVisitQueue = new ConcurrentLinkedDeque<>(spanChildren);
        while (!toVisitQueue.isEmpty()) {
            SpanData child = toVisitQueue.remove();
            if (!visitedChildren.contains(child)) {
                visitedChildren.add(child);
                HashSet<SpanData> childrenOfChildren = spanChildrenCache.get(child.spanId);
                if (childrenOfChildren != null) {
                    toVisitQueue.addAll(childrenOfChildren);
                }
            }
        }
        return visitedChildren;
    }

    protected Boolean isHttpWrite(String callHttpMethod) {
        Boolean isWrite;
        switch (callHttpMethod.toUpperCase()) {
            case "POST":
            case "PUT":
            case "DELETE":
            case "PATCH":
                isWrite = true;
                break;
            default:
                isWrite = false;
                break;
        }
        return isWrite;
    }

    // FIXME: ODB low-level non-SQL java API calls not handled (?)
    protected Boolean isOrientdbWrite(String s) {
        String statement = s.toLowerCase().trim();
        if (statement.contains("insert ") ||
                statement.contains("create ") ||
                statement.contains("alter ") ||
                statement.contains("delete ") ||
                statement.contains("drop ") ||
                statement.contains("update ")) {
            return true;
        }
        return false;
    }

    protected void reportBatchToDam(DamBatchBuilder batchBuilder){
        try {

            ApiClient apiClient;
            if (damEnv.isOffline()) {
                apiClient = new OfflineFileApiClient();
            } else {
                String authToken = damEnv.damIngestionToken();
                String serverHost = damEnv.damIngestionUrl();
                apiClient = new ApiClient()
                        .setBasePath(serverHost)
                        .addDefaultHeader("X-Auth-Token", authToken);
            }
            ManageKensuDamEntitiesApi apiInstance = new ManageKensuDamEntitiesApi(apiClient);
            BatchEntityReportResult result = apiInstance.reportEntityBatch(batchBuilder.getCompactedBatch());
            logger.info(String.format("DAM reportEntityBatch result: %s", result));
        } catch (javax.ws.rs.ProcessingException e){
            if (e.getMessage().equals("Already connected")){
                logger.error("Exception when calling ManageKensuDAMEntitiesApi#reportEntityBatch - " +
                        "SSL verification issue:", e);
            } else {
                logger.error("Exception when calling ManageKensuDAMEntitiesApi#reportEntityBatch", e);
            }
        } catch (ApiException | RuntimeException e) {
            logger.error("Exception when calling ManageKensuDAMEntitiesApi#reportEntityBatch", e);
        }
    }

    @Override
    public void log(Instant timestamp, SpanData span, Map<String, ?> fields) {
        String logMessage = createLogMessage(timestamp, "Log", span, fields);
        logger.trace(logMessage);
    }

    private String createLogMessage(Instant timestamp, String step, SpanData span) {
        StringBuilder sb = new StringBuilder();
        sb.append("{"+timestamp+"} "+step+" span " + span.spanId  + " of trace " + span.context().toSpanId() + " for operation " + span.operationName + "\n");
        sb.append(" + tags:" + "\n");
        for (Map.Entry<String, Object> entry : span.tags.entrySet()) {
            sb.append("   - " + entry.getKey() + ": " + entry.getValue() + "\n");
        }
        sb.append(" + references:" + "\n");
        for (Map.Entry<String, String> entry : span.references.entrySet()) {
            sb.append("   - " + entry.getKey() + ": " + entry.getValue() + "\n");
        }

        return sb.toString();
    }

    private String createLogMessage(Instant timestamp, String step, SpanData span, Map<String, ?> fields) {
        StringBuilder sb = new StringBuilder(createLogMessage(timestamp, step, span));
        sb.append(" + fields" + "\n");
        for (Map.Entry<String, ?> entry : fields.entrySet()) {
            sb.append("   - " + entry.getKey() + ": " + entry.getValue() + "\n");
        }
        return sb.toString();
    }
}

