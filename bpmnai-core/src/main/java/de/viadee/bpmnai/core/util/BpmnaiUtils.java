package de.viadee.bpmnai.core.util;

import de.viadee.bpmnai.core.runner.config.SparkRunnerConfig;
import de.viadee.bpmnai.core.util.logging.BpmnaiLogger;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.*;
import org.apache.spark.sql.*;
import scala.collection.JavaConversions;
import scala.collection.JavaConverters;
import scala.collection.Seq;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class BpmnaiUtils {

    private static BpmnaiUtils instance;

    private BpmnaiUtils(){}

    public static synchronized BpmnaiUtils getInstance(){
        if(instance == null){
            instance = new BpmnaiUtils();
        }
        return instance;
    }

    public String md5CecksumOfObject(Object obj) throws IOException, NoSuchAlgorithmException {
        if (obj == null) {
            return "";
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(obj);
        oos.close();

        MessageDigest m = MessageDigest.getInstance("MD5");
        m.update(baos.toByteArray());

        Base64 codec = new Base64();
        byte[] encoded = codec.encode(m.digest());

        return DigestUtils.md5Hex(new String(encoded)).toUpperCase();
    }

    public void writeDatasetToJson(Dataset<Row> dataset, String targetfolder, SparkRunnerConfig config) {
        String targetFolder = config.getTargetFolder()+"/result/json/"+targetfolder;
        dataset
                .coalesce(1)
                .write()
                .mode(config.getSaveMode())
                .json(targetFolder);
    }

    public void writeDatasetToParquet(Dataset<Row> dataSet, String subDirectory, SparkRunnerConfig config) {

        String targetFolder = config.getTargetFolder()+"/";
        if(subDirectory.equals("result")) {
            targetFolder += "result";
        } else {
            targetFolder += "intermediate/" + String.format("%02d", config.getAndRaiseStepCounter()) + "_" + subDirectory;
        }

        //save dataset into parquet file
        dataSet
        		.repartition(dataSet.col(BpmnaiVariables.VAR_PROCESS_INSTANCE_ID))
                .write()
                .mode(config.getSaveMode())
                .save(targetFolder + "/parquet");

        if(config.getOutputFormat().equals(BpmnaiVariables.OUTPUT_FORMAT_CSV) && subDirectory.equals("result")) {
            SparkSession sparkSession = SparkSession.builder().getOrCreate();
            Dataset<Row> parquetData = sparkSession.read().load(targetFolder + "/parquet");
            parquetData
                    .coalesce(1)
                    .write()
                    .option("header", "true")
                    .option("delimiter", config.getOutputDelimiter())
                    .option("timestampFormat", "yyyy-MM-dd'T'HH:mm:ss.SSS")
                    .option("ignoreLeadingWhiteSpace", "false")
                    .option("ignoreTrailingWhiteSpace", "false")
                    .mode(config.getSaveMode())
                    .csv(targetFolder + "/csv");

            // move resulting csv file
            if(targetFolder.startsWith("hdfs")) {
                // data stored in Hadoop
                Path path = new Path(targetFolder);
                Configuration conf = new Configuration();
                FileSystem fileSystem = null;
                try {
                    fileSystem = FileSystem.get(conf);
                    if(!fileSystem.isDirectory(path)) throw new IllegalStateException("Cannot find result folder!");
                    RemoteIterator<LocatedFileStatus> files = fileSystem.listFiles(path, false);
                    while(files.hasNext()) {
                        LocatedFileStatus file = files.next();
                        if(file.isFile() && file.getPath().getName().contains("part-")) {
                            FileUtil.copy(fileSystem, file.getPath(), fileSystem, new Path(targetFolder + "/result.csv"), true, conf);
                        }
                    }

                    // cleanup
                    FileUtil.fullyDeleteContents(new File(targetFolder + "/csv"));
                    FileUtil.copy(fileSystem, new Path(targetFolder + "/result.csv"), fileSystem, new Path(targetFolder + "/csv/result.csv"), true, conf);

                } catch (IOException e) {
                    BpmnaiLogger.getInstance().writeError("An error occurred during the renaming of the result file in HDFS. Exception: " + e.getMessage());
                }
            } else {
                File dir = new File(targetFolder + "/csv");
                if (!dir.isDirectory()) throw new IllegalStateException("Cannot find result folder!");

                File targetFile = new File(dir + "/../result.csv");
                for (File file : dir.listFiles()) {
                    if (file.getName().startsWith("part-")) {
                        try {
                            Files.move(file.toPath(), targetFile.toPath());
                        } catch (IOException e) {
                            BpmnaiLogger.getInstance().writeError("An error occurred during the renaming of the result file. Exception: " + e.getMessage());
                        }
                    }
                }

                // cleanup
                try {
                    FileUtils.cleanDirectory(dir);
                    Files.move(targetFile.toPath(), new File(dir + "/result.csv").toPath());
                } catch (IOException e) {
                    BpmnaiLogger.getInstance().writeError("An error occurred during the renaming of the result file. Exception: " + e.getMessage());
                }
            }
        }
    }

    public void writeDatasetToCSV(Dataset<Row> dataSet, String subDirectory, SparkRunnerConfig config) {

        boolean aggreateCSVToOneFile = true;

        if(aggreateCSVToOneFile) {
            dataSet = dataSet.coalesce(1);
        }

        String path = config.getTargetFolder()+"/";
        if(subDirectory.equals("result")) {
            path += "result";
        } else {
            path += "intermediate/" + String.format("%02d", config.getAndRaiseStepCounter()) + "_" + subDirectory;
        }

        //save dataset into CSV file
        dataSet.cache();
        dataSet
                .write()
                .option("header", "true")
                .option("delimiter", config.getOutputDelimiter())
                .option("ignoreLeadingWhiteSpace", "false")
                .option("ignoreTrailingWhiteSpace", "false")
                .mode(config.getSaveMode())
                .csv(path);
    }

    public Dataset<Row> removeDuplicatedColumns(Dataset<Row> dataset) {
        Dataset<Row> newDataset;
        //remove duplicated columns
        //find duplicated columns and their first name under which they occurred
        String[] columns = dataset.columns();
        Map<String, Column> uniqueColumnNameMapping = new HashMap<>();

        Pattern p = Pattern.compile("(\\w+_)\\d*");
        for(String col : columns) {
            Matcher m = p.matcher(col);
            if(m.matches()) {
                if(!uniqueColumnNameMapping.keySet().contains(m.group(1))) {
                    uniqueColumnNameMapping.put(m.group(1), new Column(col));
                }
            }
        }

        Seq<Column> selectionColumns =  JavaConverters.asScalaIteratorConverter(uniqueColumnNameMapping.values().iterator()).asScala().toSeq();

        //create new dataset if necessary
        if(columns.length != uniqueColumnNameMapping.size()) {

            newDataset = dataset.select(selectionColumns).toDF();

            //rename columns
            Map<String, String> swappedUniqueColumnNameMapping = new HashMap<>();
            for(String key : uniqueColumnNameMapping.keySet()) {
                swappedUniqueColumnNameMapping.put(uniqueColumnNameMapping.get(key).toString(), key);
            }

            for(String column : newDataset.columns()) {
                newDataset = newDataset.withColumnRenamed(column, swappedUniqueColumnNameMapping.get(column));
            }

            return newDataset;
        } else {
            return  dataset;
        }
    }

    /**
     * removes lines with no process instance id
     * @param dataset dataset to be cleaned
     * @return the cleaned dataset
     */
    public Dataset<Row> removeEmptyLinesAfterImport(Dataset<Row> dataset) {
        return dataset.filter(BpmnaiVariables.VAR_PROCESS_INSTANCE_ID + " <> 'null'")
                .filter(BpmnaiVariables.VAR_PROCESS_INSTANCE_ID + " <> ''");
    }

    /**
     * implemented help method as per https://stackoverflow.com/questions/40741459/scala-collection-seq-doesnt-work-on-java
     * @param values List to be convert to Scala Seq
     * @param <T> Type of objects to be converted
     * @return the Scala Seq
     */
    public <T> Seq<T> asSeq(List<T> values) {
        return JavaConversions.asScalaBuffer(values);
    }

    public Dataset<Row> unionDatasets(Dataset<Row> ds1, Dataset<Row> ds2) {
        Set<String> allUniqueColumns = new HashSet<>();
        allUniqueColumns.addAll(Arrays.asList(ds1.columns()));
        allUniqueColumns.addAll(Arrays.asList(ds2.columns()));

        for(String column : allUniqueColumns) {
            if(!Arrays.stream(ds1.columns()).anyMatch(column::equals)) {
                ds1 = ds1.withColumn(column, functions.lit(null));
            }
        }

        for(String column : allUniqueColumns) {
            if(!Arrays.stream(ds2.columns()).anyMatch(column::equals)) {
                ds2 = ds2.withColumn(column, functions.lit(null));
            }
        }

        return ds1
                .select(BpmnaiUtils.getInstance().asSeq(allUniqueColumns.stream().map(Column::new).collect(Collectors.toList())))
                .union(ds2.select(BpmnaiUtils.getInstance().asSeq(allUniqueColumns.stream().map(Column::new).collect(Collectors.toList()))));
    }
}
