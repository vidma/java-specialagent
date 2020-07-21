package io.kensu.collector.model.datasource;

public class OrientdbDatasourceNameFormatter extends DatasourceNameFormatter {
    public static OrientdbDatasourceNameFormatter INST = new OrientdbDatasourceNameFormatter();

    public String formatLocation(String tableName, String datasourceFormat){
        return String.format("%s :: %s", datasourceFormat, tableName);
    }
}
